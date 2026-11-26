"""Compare a translation run with a human review (SCRUM-148 format).

Usage (from the repo root):
    python -B tests/eval/review_compare.py docs/reviews/scrum148/mau_82
    python -B tests/eval/review_compare.py docs/reviews/scrum148/mau_82 --baseline .data/translate/run2 \
        --out .data/translate/compare_mau_82.md

The review folder holds ``review_decisions.json`` (one decision per chunk: APPROVED / RETURNED)
and ``corrections.json`` (the lines the reviewer rewrote). For every corrected line the report
shows the rejected Vietnamese, the reviewer's line and the machine line of this run
(``candidates.jsonl`` field ``machine``, i.e. before human corrections are applied), and whether
the gate of this run still flags it. Whether a new line is right stays a reviewer's call; the
report counts what can be counted:

    unchanged      the run wrote the rejected line again: not fixed
    as_reviewer    the run wrote the reviewer's line (spaces and case ignored)
    changed        a different line: to be read

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / "tools" / "translate"
sys.path.insert(0, str(HERE))

from translator.glossary import Rules  # noqa: E402
from translator.quality import check_line  # noqa: E402
from translator.segments import split_marker  # noqa: E402


def _jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _flat(text: str) -> str:
    return " ".join(split_marker(text)[1].casefold().split())


def _cell(text: str) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def compare(review_dir: Path, run_dir: Path, baseline: Path | None, rules: Rules,
            settings: dict[str, Any] | None = None) -> tuple[dict[str, Any], str]:
    decisions = json.loads((review_dir / "review_decisions.json").read_text(encoding="utf-8"))["decisions"]
    corrections = json.loads((review_dir / "corrections.json").read_text(encoding="utf-8"))["corrections"]
    records = {r["chunk_id"]: r for r in _jsonl(run_dir / "chunks_vi.jsonl")}
    machine = {c["chunk_id"]: [line.get("machine", line["final"]) for line in c["lines"]]
               for c in _jsonl(run_dir / "candidates.jsonl")}
    old = {r["chunk_id"]: r for r in _jsonl(baseline / "chunks_vi.jsonl")} if baseline else {}

    def flags(en: str, vi: str) -> list[str]:
        return [f"{i.check}" + (f" ({i.detail.split(':')[0]})" if i.check == "wording" else "")
                for i in check_line(split_marker(en)[1], split_marker(vi)[1], rules, settings) if i.hard or i.review]

    def status(record: dict[str, Any] | None) -> str:
        return record["translation_metadata"]["quality_status"] if record else "—"

    sample = {d["chunk_id"]: d["sample"] for d in decisions}
    verdicts: Counter = Counter()
    line_rows = []
    for c in corrections:
        lines = machine.get(c["chunk_id"])
        if lines is None or not 0 < int(c["line"]) <= len(lines):
            verdicts["missing"] += 1
            continue
        new = lines[int(c["line"]) - 1]
        if _flat(new) == _flat(c["new_vi"]):
            verdict = "as_reviewer"
        elif _flat(new) == _flat(c["old_vi"]):
            verdict = "unchanged"
        else:
            verdict = "changed"
        verdicts[verdict] += 1
        found = flags(c["en"], new)
        verdicts["still_flagged"] += bool(found)
        line_rows.append((sample.get(c["chunk_id"], "?"), c["line"], c.get("error_type", ""), c["en"], c["old_vi"],
                          c["new_vi"], new, verdict, ", ".join(found)))

    chunk_rows = []
    status_counts: dict[str, Counter] = {"RETURNED": Counter(), "APPROVED": Counter()}
    for d in decisions:
        record = records.get(d["chunk_id"])
        new_status = status(record)
        status_counts.setdefault(d["decision"], Counter())[new_status] += 1
        chunk_rows.append((d["sample"], d["decision"], d.get("source_name", ""), d.get("article_vi", ""),
                           status(old.get(d["chunk_id"])) if baseline else "", new_status))

    summary = {"corrected_lines": len(corrections), "lines": dict(verdicts),
               "chunks": {k: dict(v) for k, v in status_counts.items()},
               "run": str(run_dir), "baseline": str(baseline) if baseline else ""}
    out = [f"# So sánh lần chạy với kết quả duyệt — {review_dir.name}", "",
           f"- Lần chạy: `{run_dir}`" + (f" · bản trước: `{baseline}`" if baseline else ""),
           f"- {len(corrections)} câu người duyệt đã sửa: " + ", ".join(f"{k} {v}" for k, v in verdicts.items()),
           "- Trạng thái mới của chunk theo quyết định của người duyệt: "
           + "; ".join(f"{k}: " + ", ".join(f"{s} {n}" for s, n in v.most_common()) for k, v in status_counts.items()),
           "", "## Từng câu đã sửa", "",
           "| Mẫu | Dòng | Loại lỗi | English | Bản bị trả lại | Bản người duyệt | Bản máy lần này | Kết quả | Cổng còn gắn cờ |",
           "|---|---|---|---|---|---|---|---|---|"]
    out += ["| " + " | ".join(_cell(x) for x in row) + " |" for row in line_rows]
    out += ["", "## Từng chunk", "", "| Mẫu | Quyết định | Nguồn | Bài | Trạng thái trước | Trạng thái lần này |",
            "|---|---|---|---|---|---|"]
    out += ["| " + " | ".join(_cell(x) for x in row) + " |" for row in chunk_rows]
    return summary, "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    from run import load_config
    config = load_config()
    parser = argparse.ArgumentParser(description="Compare a translation run with a human review")
    parser.add_argument("review_dir", type=Path, help="folder with review_decisions.json and corrections.json")
    parser.add_argument("--run", type=Path, default=ROOT / config["paths"]["output_dir"])
    parser.add_argument("--baseline", type=Path, default=None, help="output folder of the run that was reviewed")
    parser.add_argument("--out", type=Path, default=None, help="write the Markdown report here")
    args = parser.parse_args(argv)
    summary, report = compare(args.review_dir, args.run, args.baseline, Rules.load(), config.get("quality"))
    if args.out:
        args.out.write_bytes(report.encode("utf-8"))
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
