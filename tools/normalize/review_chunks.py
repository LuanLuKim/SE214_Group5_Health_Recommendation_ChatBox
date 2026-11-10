"""Offline LLM review of every chunk's scope tags and quality (both corpora).

The keyword rules in ``normalizer.tags`` tag each chunk. This script sends every
chunk (many per request, for rate-limited endpoints) with a short project note
to an OpenAI-compatible endpoint you configure and asks for the tag set and
quality flags. A second pass
*resolves* tags where the LLM and the rule disagree. Answers are cached by the
chunk's ``text_sha256`` in ``.data/review/chunk_verdicts.json``; a changed text
is simply reviewed again. ``normalizer.tags.resolve_tags`` merges rule, LLM,
resolution and an optional ``"human"`` entry (which always wins); the
normalizer only reads the file, so its output stays deterministic.

Bring your own OpenAI-compatible endpoint; nothing is preset (the values below are
placeholders, the key is never committed):

    set LLM_BASE_URL=http://localhost:8000/v1
    set LLM_MODEL=your-model-name
    set LLM_API_KEY=your-key
    set LLM_REASONING=medium        (optional, sent as reasoning_effort)

    python -B tools/normalize/review_chunks.py --dry-run
    python -B tools/normalize/review_chunks.py          # until done or out of quota
    python -B tools/normalize/run.py && python -B tools/normalize/run.py --corpus expert_reference

Run ``review_interactions.py`` first: the ``food_interaction`` rule tag already
includes its sentence-level verdicts.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from normalizer.tags import ORDER, REVIEW_FLAGS  # noqa: E402
from review_interactions import CORPUS_OUTPUTS, Stop, chat, load_store, save_store  # noqa: E402
from run import CHUNK_VERDICTS  # noqa: E402

PROJECT_NOTE = """Context: a student project building a Vietnamese health-information chatbot (RAG). These chunks come
from public NHS (UK) and WHO pages, as DRAFT data that content experts will review. The chatbot gives general
health and lifestyle information (healthy eating, exercise, sleep, weight, skin care, when to see a doctor). It does
NOT diagnose, does NOT answer questions about a named medicine (no drug lookup: medicine pages are only reference for
experts), and does NOT give personal meal plans, calorie targets or training programmes. Users are in Vietnam.
A Safety Gate reads the tags of retrieved chunks to decide how careful the answer must be.

Tags (give every tag that applies to THIS chunk's text; do not guess beyond it):
- emergency: describes signs or situations needing urgent/emergency care now (call 999/115, go to A&E/cấp cứu,
  life-threatening symptoms). A passing mention of A&E in an unrelated list is still emergency if it tells the
  reader when to go.
- medication: the text talks about medicines/drugs/treatment products (taking, doses, side effects, prescriptions,
  creams, supplements given as treatment). Not just the word "treatment" for lifestyle measures.
- food_interaction: a food, drink or supplement changes how a medicine works or must be avoided/timed with it.
  Nutrition facts ("milk has calcium", "calcium is good for bones") are NOT interactions.
- paediatric: advice specifically about babies, children or young people.
- foreign_service: points to UK-only services or numbers (NHS 111, GP, 999, A&E, NHS apps/websites) that the chatbot
  must replace with Vietnamese equivalents.
- vn_service: mentions Vietnamese services (115, trạm y tế, bệnh viện tuyến…).
Quality flags (only when clearly true):
- ui_leftover: navigation, adverts, "find out more" link lists, video/app promos left in the text.
- not_standalone: the chunk cannot be understood on its own (starts mid-list, missing its subject).
- out_of_scope: personal meal plans, calorie targets, training programmes, drug-specific dosing in the chatbot corpus.
- unsafe_or_wrong: the text as cut could mislead (e.g. a warning separated from what it warns about).
"""

CLASSIFY_NOTE = PROJECT_NOTE + """
Each item: id, corpus, topic, text. Answer ONLY a JSON array, one object per item, same ids:
[{"id": "...", "tags": ["<tag>", ...], "flags": ["<flag>", ...], "note": "<max 20 words, why, esp. unusual tags>"}]"""

RESOLVE_NOTE = PROJECT_NOTE + """
Each item shows a chunk, the tags where a keyword rule and a first LLM review disagree, what each said, and the
first review's note. The keyword rule only matches words (it cannot tell context); the first review may have
missed something. Decide each listed tag from the text only. Answer ONLY a JSON array:
[{"id": "...", "decisions": {"<tag>": true|false, ...}, "reason": "<max 20 words>"}]"""


def load_chunks(outputs=CORPUS_OUTPUTS) -> dict[str, dict]:
    chunks: dict[str, dict] = {}
    for output in outputs:
        path = Path(output) / "chunks.jsonl"
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                chunk = json.loads(line)
                chunks.setdefault(chunk["text_sha256"], chunk)
    return chunks


def accept_tags(answers: list, batch: list[dict], model: str) -> dict[str, dict]:
    asked = {item["id"] for item in batch}
    kept = {}
    for answer in answers:
        if not isinstance(answer, dict) or answer.get("id") not in asked or not isinstance(answer.get("tags"), list):
            continue
        kept[answer["id"]] = {"tags": [t for t in ORDER if t in answer["tags"]],
                              "flags": [f for f in REVIEW_FLAGS if f in (answer.get("flags") or [])],
                              "note": str(answer.get("note", ""))[:240], "model": model,
                              "at": datetime.now(timezone.utc).date().isoformat()}
    return kept


def accept_resolution(answers: list, batch: list[dict], model: str) -> dict[str, dict]:
    asked = {item["id"]: set(item["disputed"]) for item in batch}
    kept = {}
    for answer in answers:
        if not isinstance(answer, dict) or answer.get("id") not in asked or not isinstance(answer.get("decisions"), dict):
            continue
        decisions = {t: v for t, v in answer["decisions"].items() if t in asked[answer["id"]] and isinstance(v, bool)}
        if decisions:
            kept[answer["id"]] = {**decisions, "_reason": str(answer.get("reason", ""))[:240], "_model": model}
    return kept


def disputed(chunk: dict, verdict: dict) -> list[str]:
    rule = set(chunk.get("rule_tags", chunk["scope_tags"]))
    done = verdict.get("resolution") or {}
    return [t for t in ORDER if (t in rule) != (t in verdict["tags"]) and t not in done]


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verdicts", type=Path, default=CHUNK_VERDICTS)
    parser.add_argument("--batch", type=int, default=20, help="chunks per request (~250 words each)")
    parser.add_argument("--max-requests", type=int, default=0, help="0 = until done or out of quota")
    parser.add_argument("--stage", choices=("all", "classify", "resolve"), default="all")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    base = os.environ.get("LLM_BASE_URL", "")
    model = os.environ.get("LLM_MODEL", "")
    effort = os.environ.get("LLM_REASONING", "medium")
    chunks = load_chunks()
    store = load_store(args.verdicts)
    verdicts = store["verdicts"]
    stale = [k for k, v in verdicts.items() if k not in chunks and v.get("human") is None]
    for k in stale:  # text changed or sentence gone: the old answer no longer applies (human entries are kept)
        del verdicts[k]
    todo = [k for k in sorted(chunks) if "tags" not in verdicts.get(k, {})]

    def open_disputes():
        return [k for k in sorted(chunks) if "tags" in verdicts.get(k, {}) and verdicts[k].get("human") is None
                and disputed(chunks[k], verdicts[k])]

    print(f"pruned={len(stale)} chunks={len(chunks)} reviewed={len(chunks) - len(todo)} to_classify={len(todo)} "
          f"to_resolve={len(open_disputes())} model={model} effort={effort}")
    if args.dry_run:
        return 0
    key = os.environ.get("LLM_API_KEY")
    missing = [name for name, value in (("LLM_BASE_URL", base), ("LLM_MODEL", model), ("LLM_API_KEY", key)) if not value]
    if missing:
        print(f"set your own endpoint first: {', '.join(missing)} (see the module docstring)", file=sys.stderr)
        return 2
    sent = 0
    try:
        if args.stage in ("all", "classify"):
            for i in range(0, len(todo), args.batch):
                if args.max_requests and sent >= args.max_requests:
                    break
                batch = [{"id": k, "corpus": chunks[k].get("corpus", "rag"), "topic": chunks[k]["topic"],
                          "text": chunks[k]["text"]} for k in todo[i:i + args.batch]]
                got = accept_tags(chat(CLASSIFY_NOTE, batch, base=base, key=key, model=model, effort=effort), batch, model)
                sent += 1
                for k, verdict in got.items():
                    verdicts.setdefault(k, {}).update(verdict)
                save_store(args.verdicts, store)
                print(f"classify batch {sent}: {len(got)}/{len(batch)} answered", flush=True)
        if args.stage in ("all", "resolve"):
            pending = open_disputes()
            for i in range(0, len(pending), args.batch):
                if args.max_requests and sent >= args.max_requests:
                    break
                batch = []
                for k in pending[i:i + args.batch]:
                    c, v = chunks[k], verdicts[k]
                    tags = disputed(c, v)
                    rule = set(c.get("rule_tags", c["scope_tags"]))
                    batch.append({"id": k, "topic": c["topic"], "text": c["text"], "disputed": tags,
                                  "keyword_rule": {t: t in rule for t in tags},
                                  "first_review": {t: t in v["tags"] for t in tags}, "first_note": v.get("note", "")})
                got = accept_resolution(chat(RESOLVE_NOTE, batch, base=base, key=key, model=model, effort=effort),
                                        batch, model)
                sent += 1
                for k, decisions in got.items():
                    verdicts[k].setdefault("resolution", {}).update(decisions)
                save_store(args.verdicts, store)
                print(f"resolve batch: {len(got)}/{len(batch)} answered", flush=True)
    except Stop as exc:
        save_store(args.verdicts, store)
        print(f"stopped ({exc}); progress saved — run again later", file=sys.stderr)
        return 3
    left = sum("tags" not in verdicts.get(k, {}) for k in chunks)
    print(f"requests={sent} unreviewed={left} disputes_open={len(open_disputes())}")
    return 0 if not left else 1


if __name__ == "__main__":
    raise SystemExit(main())
