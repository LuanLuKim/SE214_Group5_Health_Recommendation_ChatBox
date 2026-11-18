"""Batch translation CLI for normalized medical chunks (EN -> VI).

Usage (from the repo root, inside the se214-translate environment):
    python -B tools/translate/run.py --dry-run                 # count the work, load nothing
    python -B tools/translate/run.py --max-chunks 10           # small trial, all engines
    python -B tools/translate/run.py --engines nllb,vinai      # choose the engines
    python -B tools/translate/run.py --refiner consensus       # no LLM: engines' consensus only
    python -B tools/translate/run.py                           # everything; resumes from the cache
    python -B tools/translate/run.py --status                  # summary of the last run

Pipeline:
    .data/normalized/chunks.jsonl
        -> lines (bullet markers kept aside)
        -> UK services rewritten in English (localisation.json)
        -> machine drafts from every engine (cached per segment)
        -> LLM fusion of the drafts (optional: LLM_BASE_URL, LLM_MODEL, LLM_API_KEY)
        -> LLM meaning check, one repair of what it finds (config llm.verify; --no-verify skips it)
        -> lines a human reviewer corrected (config paths.human_corrections)
        -> quality gate per line, consensus fallback
        -> .data/translated/chunks_vi.jsonl, db_rows_vi.json, candidates.jsonl,
           quality_report.json, review_queue.md, summary.json

Exit code: 0 all chunks passed the gate, 1 some failed, 2 configuration problem,
3 the LLM stopped early (rate limit / quota): run again to continue.

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from translator.engines import get_engine  # noqa: E402
from translator.glossary import Rules, compile_names, load_medicine_names  # noqa: E402
from translator.pipeline import (  # noqa: E402
    UnitResult, build_units, engine_statistics, finalise, machine_translate, refine, verify)
from translator.refine import PROMPT_VERSION, LLMClient  # noqa: E402
from translator.verify import VERIFY_VERSION  # noqa: E402

CONFIG_PATH = HERE / "config.json"


def load_config(path: Path = CONFIG_PATH) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def load_chunks(path: Path) -> list[dict[str, Any]]:
    """Load normalized chunks from JSONL."""
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_db_rows(path: Path) -> list[dict[str, Any]]:
    """Load normalized DB rows from JSON."""
    if not path.exists():
        return []
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def load_corrections(paths: list[Path], chunks: list[dict[str, Any]]) -> tuple[dict[str, dict[int, str]], dict[str, Any]]:
    """Lines a human reviewer corrected: chunk id -> {0-based line: Vietnamese line}.

    Each file holds ``{"corrections": [{"chunk_id", "line" (1-based), "en", "new_vi"}, ...]}``. A
    correction applies only while its English line is still in the chunk (at ``line``, or once
    elsewhere when the chunk moved); otherwise it is reported as not matched. A later file wins.
    """
    lines = {c["id"]: c["text"].split("\n") for c in chunks}
    out: dict[str, dict[int, str]] = {}
    stats: dict[str, Any] = {"files": [], "applied": 0, "not_matched": []}
    for path in paths:
        if not path.exists():
            stats["not_matched"].append({"file": path.name, "reason": "file not found"})
            continue
        rows = json.loads(path.read_text(encoding="utf-8")).get("corrections", [])
        stats["files"].append({"file": path.name, "corrections": len(rows)})
        for row in rows:
            source = lines.get(row.get("chunk_id"), [])
            index = int(row.get("line") or 0) - 1
            if not (0 <= index < len(source) and source[index] == row.get("en")):
                found = [i for i, line in enumerate(source) if line == row.get("en")]
                index = found[0] if len(found) == 1 else -1
            if index < 0 or not row.get("new_vi"):
                stats["not_matched"].append({"file": path.name, "chunk_id": row.get("chunk_id"), "line": row.get("line")})
                continue
            out.setdefault(row["chunk_id"], {})[index] = row["new_vi"]
    stats["applied"] = sum(len(v) for v in out.values())
    return out, stats


def localised_tags(tags: list[str], text_vi: str, rules: Rules) -> list[str]:
    """Scope tags of the Vietnamese chunk: UK services were replaced, Vietnamese ones may have appeared."""
    out = set(tags)
    if "foreign_service" in out and not rules.forbidden_in(text_vi) and not rules.discouraged_in(text_vi):
        out.discard("foreign_service")
    if any(str(number) in text_vi for number in rules.number_map.values() if number):
        out.add("vn_service")
    return sorted(out)


def chunk_record(chunk: dict[str, Any], result: UnitResult, strings: dict[str, str], meta: dict[str, Any],
                 rules: Rules) -> dict[str, Any]:
    methods = Counter(m for m in result.methods if m != "copy")
    method = "llm_fusion" if methods.get("llm") else "consensus"
    path = list(chunk.get("heading_path") or [])
    return {
        "chunk_id": chunk["id"],
        "chunk_key": chunk.get("chunk_key", ""),
        "article_id": chunk["article_id"],
        "article_uuid": chunk.get("article_uuid", ""),
        "section_id": chunk["section_id"],
        "language": meta["tgt_lang"],
        "title_en": chunk.get("title", ""),
        "title_vi": strings.get(chunk.get("title", ""), ""),
        "heading_en": chunk.get("heading", ""),
        "heading_vi": strings.get(chunk.get("heading", ""), ""),
        "heading_path_en": path,
        "heading_path_vi": [strings.get(p, "") for p in path],
        "text_en": chunk["text"],
        "text_vi": result.text,
        "translation_metadata": {
            "method": method,
            "model": meta["refiner_model"] if method == "llm_fusion" else "consensus",
            "engines": meta["engine_models"],
            "line_methods": dict(methods),
            "src_lang": meta["src_lang"],
            "tgt_lang": meta["tgt_lang"],
            "localisation_version": rules.version,
            "prompt_version": PROMPT_VERSION if method == "llm_fusion" else "",
            "checker_version": meta.get("checker_version", ""),
            "human_corrected_lines": methods.get("human", 0),
            "source_text_sha256": chunk.get("text_sha256", ""),
            "translated_at": meta["translated_at"],
            "quality_status": result.report.status,
            "needs_human_review": result.report.needs_human_review,
        },
        "scope_tags": localised_tags(chunk.get("scope_tags", []), result.text, rules),
        "scope_tags_source": chunk.get("scope_tags", []),
        "topic": chunk.get("topic", ""),
        "corpus": chunk.get("corpus", ""),
        "review_status": chunk.get("review_status", "DRAFT"),
        "rag_eligible": False,
        "word_count_en": chunk.get("word_count", len(chunk["text"].split())),
        "word_count_vi": len(result.text.split()),
        "source_url": chunk.get("source_url", ""),
        "source_name": chunk.get("source_name", ""),
    }


def passthrough_record(chunk: dict[str, Any], meta: dict[str, Any]) -> dict[str, Any]:
    """A chunk that is already in the target language."""
    path = list(chunk.get("heading_path") or [])
    return {
        "chunk_id": chunk["id"], "chunk_key": chunk.get("chunk_key", ""), "article_id": chunk["article_id"],
        "article_uuid": chunk.get("article_uuid", ""), "section_id": chunk["section_id"],
        "language": meta["tgt_lang"],
        "title_en": "", "title_vi": chunk.get("title", ""),
        "heading_en": "", "heading_vi": chunk.get("heading", ""),
        "heading_path_en": [], "heading_path_vi": path,
        "text_en": "", "text_vi": chunk["text"],
        "translation_metadata": {
            "method": "passthrough", "model": "passthrough", "engines": [], "line_methods": {},
            "src_lang": meta["tgt_lang"], "tgt_lang": meta["tgt_lang"], "localisation_version": "",
            "prompt_version": "", "source_text_sha256": chunk.get("text_sha256", ""),
            "translated_at": meta["translated_at"], "quality_status": "passthrough_native",
            "needs_human_review": False,
        },
        "scope_tags": chunk.get("scope_tags", []), "scope_tags_source": chunk.get("scope_tags", []),
        "topic": chunk.get("topic", ""), "corpus": chunk.get("corpus", ""),
        "review_status": chunk.get("review_status", "DRAFT"), "rag_eligible": False,
        "word_count_en": 0, "word_count_vi": chunk.get("word_count", len(chunk["text"].split())),
        "source_url": chunk.get("source_url", ""), "source_name": chunk.get("source_name", ""),
    }


def db_rows_vi(db_rows: list[dict[str, Any]], chunks: list[dict[str, Any]], results: dict[str, UnitResult],
               strings: dict[str, str], meta: dict[str, Any]) -> tuple[list[dict[str, Any]], int]:
    """Vietnamese article rows, rebuilt line by line from the translated chunks.

    Only articles whose chunks were all translated in this run are written.
    Returns the rows and the number of content lines that found no translation.
    """
    by_article: dict[str, list[dict[str, Any]]] = {}
    for chunk in chunks:
        by_article.setdefault(chunk.get("article_uuid", ""), []).append(chunk)
    rows, untranslated = [], 0
    for row in db_rows:
        own = by_article.get(row["id"], [])
        if not own:
            continue
        if all(c.get("language") == meta["tgt_lang"] for c in own):
            rows.append({**row, "language": meta["tgt_lang"], "translation_model": "passthrough"})
            continue
        if any(c["id"] not in results for c in own if c.get("language") == meta["src_lang"]):
            continue
        line_map: dict[str, str] = {}
        for chunk in own:
            if chunk["id"] in results:
                result = results[chunk["id"]]
                line_map.update(zip(result.unit.lines, result.lines))
        content, missing = [], 0
        for line in row.get("content", "").split("\n"):
            if not line.strip():
                content.append(line)
            elif line in line_map:
                content.append(line_map[line])
            elif line in strings:
                content.append(strings[line])
            else:
                content.append(line)
                missing += 1
        untranslated += missing
        rows.append({**row, "title": strings.get(row.get("title", ""), row.get("title", "")),
                     "title_en": row.get("title", ""), "content": "\n".join(content), "language": meta["tgt_lang"],
                     "translation_model": meta["refiner_model"] or "consensus",
                     "translated_at": meta["translated_at"], "untranslated_lines": missing})
    return rows, untranslated


def review_queue(records: list[dict[str, Any]], reports: dict[str, dict[str, Any]]) -> str:
    """Side-by-side EN / VI of every chunk a human has to read, worst first."""
    def cell(text: str) -> str:
        return text.replace("|", "\\|")

    queue = [r for r in records if r["translation_metadata"]["needs_human_review"]]
    queue.sort(key=lambda r: (r["translation_metadata"]["quality_status"] != "failed_quality_gate",
                              "emergency" not in r["scope_tags_source"], r["title_en"], r["chunk_key"]))
    out = ["# Hàng chờ rà soát bản dịch", "",
           f"{len(queue)} chunk cần người rà soát (chunk không đạt cổng kiểm định và chunk cấp cứu đứng trước).", ""]
    for record in queue:
        report = reports.get(record["chunk_id"], {})
        out += [f"## {record['title_en']} — {record['heading_en']}", "",
                f"- chunk: `{record['chunk_id']}` · trạng thái: `{record['translation_metadata']['quality_status']}`"
                f" · thẻ: {', '.join(record['scope_tags_source']) or '—'}",
                f"- nguồn: {record['source_url']}"]
        out += [f"- cảnh báo: {warning}" for warning in report.get("warnings", [])]
        out += ["", "| # | English | Tiếng Việt |", "|---|---|---|"]
        for n, (en, vi) in enumerate(zip(record["text_en"].split("\n"), record["text_vi"].split("\n")), 1):
            out.append(f"| {n} | {cell(en)} | {cell(vi)} |")
        out.append("")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    config = load_config()
    paths = config["paths"]
    parser = argparse.ArgumentParser(description="Translate normalized medical chunks EN->VI",
                                     epilog="Models and thresholds: tools/translate/config.json")
    parser.add_argument("--engines", "--engine", default=",".join(config["default_engines"]),
                        help="comma-separated engines (%(default)s); 'none' = LLM only")
    parser.add_argument("--refiner", choices=("auto", "llm", "consensus"), default="auto",
                        help="auto: LLM when LLM_BASE_URL/LLM_MODEL/LLM_API_KEY are set, else consensus")
    parser.add_argument("--device", default="auto", help="auto, cpu, cuda, cuda:0 ...")
    parser.add_argument("--max-chunks", type=int, default=0, help="limit the number of chunks (0 = all)")
    parser.add_argument("--only-tag", default="", help="only chunks with this scope tag, e.g. emergency")
    parser.add_argument("--llm-max-requests", type=int, default=0, help="0 = until done or out of quota")
    parser.add_argument("--no-verify", action="store_true", help="skip the LLM meaning check (config llm.verify)")
    parser.add_argument("--no-corrections", action="store_true",
                        help="ignore the human corrections of config paths.human_corrections")
    parser.add_argument("--input", type=Path, default=ROOT / paths["input_chunks"])
    parser.add_argument("--db-rows", type=Path, default=ROOT / paths["input_db_rows"])
    parser.add_argument("--output", type=Path, default=ROOT / paths["output_dir"])
    parser.add_argument("--cache-dir", type=Path, default=None, help="default: <default output>/cache")
    parser.add_argument("--dry-run", action="store_true", help="count the work without loading models")
    parser.add_argument("--status", action="store_true", help="show the summary of the last run")
    args = parser.parse_args(argv)
    cache_dir = args.cache_dir or ROOT / paths["output_dir"] / "cache"

    if args.status:
        summary_path = args.output / "summary.json"
        print(summary_path.read_text(encoding="utf-8") if summary_path.exists() else "No translation output yet.")
        return 0

    chunks = load_chunks(args.input)
    if not chunks:
        print(f"No normalized chunks found. Expected: {args.input}", file=sys.stderr)
        return 2
    src_lang, tgt_lang = config["languages"]["source"], config["languages"]["target"]
    to_translate = [c for c in chunks if c.get("language", src_lang) == src_lang]
    passthrough = [c for c in chunks if c.get("language", src_lang) == tgt_lang]
    if args.only_tag:
        to_translate = [c for c in to_translate if args.only_tag in c.get("scope_tags", [])]
    if args.max_chunks > 0:
        to_translate = to_translate[:args.max_chunks]

    try:
        engine_names = [] if args.engines.strip().lower() == "none" else [
            name.strip() for name in args.engines.split(",") if name.strip()]
        engines = [get_engine(name, config, device=args.device) for name in engine_names]
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    client = None if args.refiner == "consensus" else LLMClient.from_env(config.get("llm"))
    if args.refiner == "llm" and client is None:
        print(f"--refiner llm needs: {', '.join(LLMClient.missing_env())}", file=sys.stderr)
        return 2
    if client is None and not engines:
        print("Nothing can translate: no engine and no LLM endpoint.", file=sys.stderr)
        return 2

    rules = Rules.load()
    names = load_medicine_names(ROOT, ROOT / paths["medicine_index_urls"], ROOT / paths["collection_dir"])
    db_rows = load_db_rows(args.db_rows)

    # loose strings: titles and headings, plus article lines that are in no chunk (section headings)
    selected = {c["id"] for c in to_translate}
    extra: list[str] = []
    chunk_lines: dict[str, set[str]] = {}
    for chunk in to_translate:
        extra += [chunk.get("title", ""), chunk.get("heading", ""), *(chunk.get("heading_path") or [])]
        chunk_lines.setdefault(chunk.get("article_uuid", ""), set()).update(chunk["text"].split("\n"))
    complete = {c.get("article_uuid", "") for c in to_translate} - {
        c.get("article_uuid", "") for c in chunks if c.get("language", src_lang) == src_lang and c["id"] not in selected}
    for row in db_rows:
        if row["id"] in complete:
            extra.append(row.get("title", ""))
            extra += [line for line in row.get("content", "").split("\n") if line not in chunk_lines[row["id"]]]

    units = build_units(to_translate, extra, compile_names(names), int(config["llm"].get("extra_lines_per_unit", 40)))
    line_total = sum(len(u.lines) for u in units)
    print("=" * 70)
    print("Medical Translation Pipeline — tools/translate/")
    print("=" * 70)
    print(f"  Engines:         {', '.join(e.key for e in engines) or 'none'}")
    print(f"  Refiner:         {'LLM ' + client.model if client else 'consensus of the engines (no LLM endpoint)'}")
    print(f"  Chunks:          {len(chunks)} total, {len(to_translate)} to translate, {len(passthrough)} already "
          f"{tgt_lang}")
    print(f"  Units / lines:   {len(units)} / {line_total}")
    print(f"  Medicine names:  {len(names)} from the crawled index")
    print(f"  Localisation:    {rules.version}")
    print()
    if args.dry_run:
        for i, chunk in enumerate(to_translate[:10], 1):
            print(f"  {i:3d}. [{chunk['id']}] {chunk.get('title', '')[:40]} — {chunk['text'][:50]!r}")
        if len(to_translate) > 10:
            print(f"  ... and {len(to_translate) - 10} more")
        return 0

    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    quality_settings = config.get("quality", {})
    engine_stats = machine_translate(units, engines, cache_dir, rules, int(config["segments"]["max_words"]))
    llm_lines: dict[str, list[str]] = {}
    llm_stats: dict[str, Any] = {}
    flags: dict[str, list[Any]] = {}
    verify_stats: dict[str, Any] = {}
    if client:
        llm_lines, llm_stats = refine(units, client, rules, cache_dir, config["llm"], quality_settings,
                                      max_requests=args.llm_max_requests)
        if config["llm"].get("verify") and not args.no_verify and not llm_stats.get("stopped"):
            llm_lines, flags, verify_stats = verify(units, llm_lines, client, rules, cache_dir, config["llm"],
                                                    quality_settings, max_requests=args.llm_max_requests)
    correction_paths = [] if args.no_corrections else [ROOT / p for p in paths.get("human_corrections", [])]
    corrections, correction_stats = load_corrections(correction_paths, to_translate)
    results = {u.id: finalise(u, llm_lines.get(u.id), rules, quality_settings, flags=flags.get(u.id),
                              corrections=corrections.get(u.id)) for u in units}

    strings: dict[str, str] = {}
    for result in results.values():
        if result.unit.kind == "extra":
            strings.update(zip(result.unit.lines, result.lines))
    meta = {"src_lang": src_lang, "tgt_lang": tgt_lang, "translated_at": now,
            "refiner_model": client.model if client else "", "engine_models": [e.model_id for e in engines],
            "checker_version": VERIFY_VERSION if verify_stats else ""}
    records = [chunk_record(c, results[c["id"]], strings, meta, rules) for c in to_translate]
    records += [passthrough_record(c, meta) for c in passthrough]
    reports = {r.unit.id: r.report.to_dict() for r in results.values()}
    chunk_reports = [reports[c["id"]] for c in to_translate]
    rows, untranslated = db_rows_vi(db_rows, chunks, results, strings, meta)

    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "chunks_vi.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records), encoding="utf-8")
    (args.output / "candidates.jsonl").write_text("".join(json.dumps({
        "chunk_id": c["id"],
        "lines": [{"en": en, **{key: drafts[i] for key, drafts in results[c["id"]].unit.drafts.items()},
                   "consensus": results[c["id"]].consensus[i], "machine": results[c["id"]].machine_bodies[i],
                   "final": results[c["id"]].bodies[i], "method": results[c["id"]].methods[i]}
                  for i, en in enumerate(results[c["id"]].unit.bodies)],
    }, ensure_ascii=False) + "\n" for c in to_translate), encoding="utf-8")
    write_json(args.output / "db_rows_vi.json", rows)
    write_json(args.output / "quality_report.json", chunk_reports + [
        reports[u.id] for u in units if u.kind == "extra"])
    (args.output / "review_queue.md").write_text(review_queue(records, reports), encoding="utf-8")

    line_methods = Counter(m for c in to_translate for m in results[c["id"]].methods if m != "copy")
    failed = [r["chunk_id"] for r in chunk_reports if not r["passed"]]
    hard = Counter(i["check"] for r in chunk_reports for i in r["issues"] if i["hard"])
    soft = Counter(i["check"] for r in chunk_reports for i in r["issues"] if not i["hard"])
    review_tags = set(quality_settings.get("always_review_tags", []))
    summary = {
        "translated_at": now,
        "engines": engine_stats,
        "engine_vs_final": engine_statistics([results[c["id"]] for c in to_translate], rules, quality_settings),
        "refiner": llm_stats or {"model": "consensus"},
        "meaning_check": verify_stats,
        "human_corrections": correction_stats,
        "localisation_version": rules.version,
        "prompt_version": PROMPT_VERSION if client else "",
        "checker_version": meta["checker_version"],
        "medicine_names_from_index": len(names),
        "total_chunks_input": len(chunks),
        "chunks_translated": len(to_translate),
        "chunks_passthrough_vi": len(passthrough),
        "total_chunks_output": len(records),
        "lines_translated": sum(line_methods.values()),
        "line_methods": dict(line_methods),
        "quality_passed": len(chunk_reports) - len(failed),
        "quality_failed": len(failed),
        "quality_status": dict(Counter(r["status"] for r in chunk_reports)),
        "needs_human_review": sum(r["needs_human_review"] for r in chunk_reports),
        "hard_issues": dict(hard),
        "soft_issues": dict(soft),
        "always_review_chunks": sum(bool(review_tags & set(c.get("scope_tags", []))) for c in to_translate),
        "always_review_chunks_passed": sum(bool(review_tags & set(c.get("scope_tags", [])))
                                           and reports[c["id"]]["passed"] for c in to_translate),
        "db_rows_written": len(rows),
        "db_rows_untranslated_lines": untranslated,
        "failed_chunk_ids": failed,
        "output_files": {name: str((args.output / name).relative_to(ROOT)) if args.output.is_relative_to(ROOT)
                         else str(args.output / name)
                         for name in ("chunks_vi.jsonl", "db_rows_vi.json", "candidates.jsonl",
                                      "quality_report.json", "review_queue.md")},
    }
    write_json(args.output / "summary.json", summary)

    print()
    print("=" * 70)
    print("Translation complete")
    print("=" * 70)
    print(f"  Translated:        {len(to_translate)} chunks, {summary['lines_translated']} lines {dict(line_methods)}")
    print(f"  Passthrough ({tgt_lang}):  {len(passthrough)} chunks")
    print(f"  Quality passed:    {summary['quality_passed']}/{len(chunk_reports)}   hard issues: {dict(hard)}")
    print(f"  Needs review:      {summary['needs_human_review']}   soft issues: {dict(soft)}")
    if verify_stats:
        print(f"  Meaning check:     {verify_stats['checked_units']} units checked, "
              f"{verify_stats['units_with_problems']} with problems, {verify_stats['repaired_units']} repaired, "
              f"{verify_stats['flagged_units']} still flagged")
    print(f"  Human corrections: {correction_stats['applied']} lines applied, "
          f"{len(correction_stats['not_matched'])} not matched")
    print(f"  Article rows:      {len(rows)} ({untranslated} lines without translation)")
    print(f"  Output:            {args.output}")
    if llm_stats.get("stopped") or verify_stats.get("stopped"):
        return 3
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
