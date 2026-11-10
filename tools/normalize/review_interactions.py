"""Offline LLM review of food/drink-medicine sentences (food_interaction tag).

The normalizer finds *candidate* sentences (a substance next to a medicine) and
decides them with a rule. This script asks an LLM, through any OpenAI-compatible
endpoint you configure, whether each candidate really describes an
interaction, then runs a second *resolve* pass on sentences where the LLM and
the rule disagree. Answers are cached in ``.data/review/interaction_verdicts.json``;
the normalizer reads that file as data, so its output stays deterministic and it
imports no LLM code. A human can add ``"human": {"interaction": ..., "terms": [...]}``
to any entry; that always wins (see ``normalizer.tags.resolve``).

Bring your own OpenAI-compatible endpoint; nothing is preset (the values below are
placeholders, the key is never committed):

    set LLM_BASE_URL=http://localhost:8000/v1
    set LLM_MODEL=your-model-name
    set LLM_API_KEY=your-key
    set LLM_REASONING=medium        (optional, sent as reasoning_effort)

    python -B tools/normalize/review_interactions.py --dry-run
    python -B tools/normalize/review_interactions.py            # until done or out of quota
    python -B tools/normalize/run.py && python -B tools/normalize/run.py --corpus expert_reference

Requests are few and large (``--batch`` candidates each) for rate-limited
endpoints. On a
rate-limit/quota answer the script saves what it has and stops; run it again later.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from normalizer.chunking import MEDICINE_TOPICS  # noqa: E402
from normalizer.tags import SUBSTANCES, interaction_candidates  # noqa: E402
from run import COLLECTION_DIR, OUTPUT_DIR, ROOT, VERDICTS, load_medicine_names  # noqa: E402

CORPUS_OUTPUTS = (OUTPUT_DIR, ROOT / ".data" / "normalized_reference")
STOP_STATUS = {401, 402, 403, 429}

JOB_NOTE = """You check sentences from public health pages (NHS, WHO) for a health chatbot's data pipeline.
Job: for each item decide if the sentence says that a food, drink or supplement INTERACTS with a medicine
(changes how it works, its level or side effects, or must be timed/avoided while taking it).
Not an interaction: nutrition facts ("milk is rich in calcium"), the substance itself being the treatment or
a recommended supplement ("your GP may prescribe calcium"), a drug class name ("calcium-channel blocker"),
breast milk/breastfeeding, allergies, tobacco, or general lifestyle advice that does not involve a medicine.
Use only the sentence and its section header; do not add outside facts.
Answer ONLY a JSON array, one object per item, same ids:
[{"id": "...", "interaction": true|false, "terms": ["<keys from the item that interact>"], "reason": "<max 15 words>"}]"""

RESOLVE_NOTE = """You settle disagreements between a keyword rule and a first LLM review about whether a sentence
describes a food/drink/supplement-medicine interaction (same definition: changes how the medicine works, its
level or side effects, or must be timed/avoided while taking it; nutrition facts, the substance as the treatment,
drug class names, breast milk, allergies and tobacco are NOT interactions). Each item shows the sentence,
its header, the rule's view and the first review's view with its reason. Decide from the sentence only.
Answer ONLY a JSON array: [{"id": "...", "interaction": true|false, "terms": [...], "reason": "<max 15 words>"}]"""


def collect_candidates(outputs=CORPUS_OUTPUTS) -> dict[str, dict]:
    names = load_medicine_names(ROOT / "docs" / "urls_discovery.txt", COLLECTION_DIR)
    found: dict[str, dict] = {}
    for output in outputs:
        path = Path(output) / "chunks.jsonl"
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            chunk = json.loads(line)
            for cand in interaction_candidates(chunk["text"], medicine_names=names,
                                               medicine_page=chunk["topic"] in MEDICINE_TOPICS):
                found.setdefault(cand["id"], cand)
    return found


def load_store(path: Path) -> dict:
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"schema_version": 1, "verdicts": {}}


def save_store(path: Path, store: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(store, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(path)


class Stop(Exception):
    """Rate limit, quota or auth problem: keep results, end the run."""


def chat(note: str, items: list[dict], *, base: str, key: str, model: str, effort: str, timeout: int = 600) -> list:
    body = json.dumps({"model": model, "reasoning_effort": effort,
                       "messages": [{"role": "system", "content": note},
                                    {"role": "user", "content": json.dumps(items, ensure_ascii=False)}]}).encode()
    request = Request(base.rstrip("/") + "/chat/completions", data=body, method="POST",
                      headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    for attempt in range(3):
        try:
            with urlopen(request, timeout=timeout) as response:
                data = json.loads(response.read().decode("utf-8"))
            break
        except HTTPError as exc:
            if exc.code in STOP_STATUS:
                raise Stop(f"HTTP {exc.code}") from None
            if attempt == 2:
                raise Stop(f"HTTP {exc.code} after retries") from None
        except (URLError, TimeoutError, OSError) as exc:
            if attempt == 2:
                raise Stop(type(exc).__name__) from None
        time.sleep(10 * (attempt + 1))
    if data.get("error"):
        raise Stop(str(data["error"].get("code") or data["error"].get("type") or "error"))
    text = data["choices"][0]["message"]["content"].strip()
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end < start:
        return []
    try:
        answer = json.loads(text[start:end + 1])
    except ValueError:
        return []
    return answer if isinstance(answer, list) else []


def accept(answers: list, batch: list[dict], model: str) -> dict[str, dict]:
    """Keep only well-formed answers for ids that were asked, with known term keys."""
    asked = {item["id"]: item for item in batch}
    kept = {}
    for answer in answers:
        if not isinstance(answer, dict) or answer.get("id") not in asked or not isinstance(answer.get("interaction"), bool):
            continue
        allowed = asked[answer["id"]]["terms"]
        terms = [t for t in answer.get("terms") or [] if t in allowed] if answer["interaction"] else []
        kept[answer["id"]] = {"interaction": answer["interaction"], "terms": terms or (allowed if answer["interaction"] else []),
                              "reason": str(answer.get("reason", ""))[:200], "model": model,
                              "at": datetime.now(timezone.utc).date().isoformat()}
    return kept


def conflicts(cands: dict[str, dict], verdicts: dict) -> list[str]:
    return [cid for cid, v in verdicts.items() if cid in cands and "interaction" in v and "resolution" not in v
            and v.get("human") is None and v["interaction"] != cands[cid]["rule"]]


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verdicts", type=Path, default=VERDICTS)
    parser.add_argument("--batch", type=int, default=80, help="candidates per request (large: for rate-limited endpoints)")
    parser.add_argument("--max-requests", type=int, default=0, help="0 = until done or out of quota")
    parser.add_argument("--stage", choices=("all", "classify", "resolve"), default="all")
    parser.add_argument("--dry-run", action="store_true", help="count work, send nothing")
    args = parser.parse_args(argv)

    base = os.environ.get("LLM_BASE_URL", "")
    model = os.environ.get("LLM_MODEL", "")
    effort = os.environ.get("LLM_REASONING", "medium")
    cands = collect_candidates()
    store = load_store(args.verdicts)
    verdicts = store["verdicts"]
    stale = [k for k, v in verdicts.items() if k not in cands and v.get("human") is None]
    for k in stale:  # text changed or sentence gone: the old answer no longer applies (human entries are kept)
        del verdicts[k]
    todo = [cid for cid in sorted(cands) if "interaction" not in verdicts.get(cid, {})]
    print(f"pruned={len(stale)} candidates={len(cands)} reviewed={len(cands) - len(todo)} to_classify={len(todo)} "
          f"conflicts_to_resolve={len(conflicts(cands, verdicts))} model={model} effort={effort}")
    if args.dry_run:
        return 0
    key = os.environ.get("LLM_API_KEY")
    missing = [name for name, value in (("LLM_BASE_URL", base), ("LLM_MODEL", model), ("LLM_API_KEY", key)) if not value]
    if missing:
        print(f"set your own endpoint first: {', '.join(missing)} (see the module docstring)", file=sys.stderr)
        return 2
    sent = 0

    def budget_left():
        return not args.max_requests or sent < args.max_requests

    try:
        if args.stage in ("all", "classify"):
            for i in range(0, len(todo), args.batch):
                if not budget_left():
                    break
                batch = [{"id": c["id"], "header": c["header"], "sentence": c["sentence"], "terms": c["terms"]}
                         for c in (cands[cid] for cid in todo[i:i + args.batch])]
                got = accept(chat(JOB_NOTE, batch, base=base, key=key, model=model, effort=effort), batch, model)
                sent += 1
                for cid, verdict in got.items():
                    verdicts.setdefault(cid, {}).update(verdict)
                save_store(args.verdicts, store)
                print(f"classify batch {sent}: {len(got)}/{len(batch)} answered", flush=True)
        if args.stage in ("all", "resolve"):
            pending = conflicts(cands, verdicts)
            for i in range(0, len(pending), args.batch):
                if not budget_left():
                    break
                batch = []
                for cid in pending[i:i + args.batch]:
                    c, v = cands[cid], verdicts[cid]
                    batch.append({"id": cid, "header": c["header"], "sentence": c["sentence"], "terms": c["terms"],
                                  "rule": ("interaction: medicine and a linking phrase in the same sentence"
                                           if c["rule"] else "no interaction: no linking phrase with a medicine"),
                                  "first_review": {"interaction": v["interaction"], "reason": v.get("reason", "")}})
                got = accept(chat(RESOLVE_NOTE, batch, base=base, key=key, model=model, effort=effort), batch, model)
                sent += 1
                for cid, verdict in got.items():
                    verdicts[cid]["resolution"] = verdict
                save_store(args.verdicts, store)
                print(f"resolve batch: {len(got)}/{len(batch)} answered", flush=True)
    except Stop as exc:
        save_store(args.verdicts, store)
        print(f"stopped ({exc}); progress saved — run again later", file=sys.stderr)
        return 3
    left = sum("interaction" not in verdicts.get(cid, {}) for cid in cands)
    print(f"requests={sent} unanswered={left} conflicts_unresolved={len(conflicts(cands, verdicts))}")
    return 0 if not left else 1


if __name__ == "__main__":
    raise SystemExit(main())
