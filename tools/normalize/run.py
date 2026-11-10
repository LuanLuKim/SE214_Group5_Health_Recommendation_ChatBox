"""Normalize all extracted documents into a canonical format.

Usage (from repo root, inside conda env):
    python -B tools/normalize/run.py                 # process all extracted files
    python -B tools/normalize/run.py --status        # show current output summary
    python -B tools/normalize/run.py --chunks-only   # only emit JSONL chunk file

No imports from apps/, packages/, database/ or any backend/frontend/LLM code.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXTRACTED_DIR = ROOT / ".data" / "collection" / "extracted"
OUTPUT_DIR = ROOT / ".data" / "normalized"


def load_extracted_files() -> list[dict]:
    """Load all extracted JSON documents from the collection."""
    if not EXTRACTED_DIR.exists():
        print(f"No extracted data at {EXTRACTED_DIR}", file=sys.stderr)
        return []
    docs = []
    for path in sorted(EXTRACTED_DIR.glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc["_source_file"] = path.name
        docs.append(doc)
    return docs


def load_url_topics(path: Path) -> dict[str, str]:
    """Read topic mappings configured in urls.txt (<URL> [topic])."""
    mapping: dict[str, str] = {}
    if not path.exists():
        return mapping
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        url = parts[0]
        topic = parts[1] if len(parts) > 1 else "general"
        mapping[url] = topic
    return mapping


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--urls", type=Path, default=ROOT / "docs" / "urls.txt", help="Path to urls.txt with topic mappings")
    parser.add_argument("--status", action="store_true", help="Show summary of normalized output")
    parser.add_argument("--chunks-only", action="store_true", help="Only produce chunks JSONL")
    parser.add_argument("--max-chunk-words", type=int, default=500, help="Max words per chunk (default 500)")
    args = parser.parse_args()

    if args.status:
        summary_path = OUTPUT_DIR / "summary.json"
        if summary_path.exists():
            print(summary_path.read_text(encoding="utf-8"))
        else:
            print("No normalization output yet.")
        return 0

    from normalizer.transform import transform

    extracted_docs = load_extracted_files()
    if not extracted_docs:
        print("No extracted files found.", file=sys.stderr)
        return 1

    url_topics = load_url_topics(args.urls)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    articles_dir = OUTPUT_DIR / "articles"
    articles_dir.mkdir(exist_ok=True)

    all_articles = []
    all_chunks = []
    errors = []

    for doc in extracted_docs:
        source_file = doc.pop("_source_file", "unknown")
        url = doc.get("url", "")
        topic = url_topics.get(url, "general")
        try:
            article = transform(doc, topic=topic)
            article_data = article.to_dict()
            article_data["_source_file"] = source_file

            out_path = articles_dir / f"{article.id}.json"
            out_path.write_text(
                json.dumps(article_data, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

            all_articles.append(article_data)
            chunks = article.to_chunks(max_words=args.max_chunk_words)
            all_chunks.extend(chunks)

            over_limit = sum(1 for c in chunks if c["over_limit"])
            tags = sorted({t for s in article.sections for t in s.scope_tags})
            title_display = article.title.encode("ascii", "replace").decode()
            print(
                f"  OK {title_display:<40s}  "
                f"topic={article.topic:<14s}  "
                f"{len(article.sections):>2d} sections  "
                f"{len(chunks):>2d} chunks  "
                f"{len(article.content_text.split()):>4d} words  "
                f"{'!! ' + str(over_limit) + ' over limit' if over_limit else 'size ok':>12s}  "
                f"tags={tags}"
            )

        except Exception as exc:
            errors.append({"file": source_file, "error": str(exc), "type": type(exc).__name__})
            print(f"  FAIL {source_file}: {exc}", file=sys.stderr)

    # Write chunks JSONL
    chunks_path = OUTPUT_DIR / "chunks.jsonl"
    chunks_path.write_text(
        "".join(json.dumps(c, ensure_ascii=False) + "\n" for c in all_chunks),
        encoding="utf-8",
    )

    # Write DB-ready rows
    if not args.chunks_only:
        db_rows = [a["db_row"] for a in all_articles]
        db_path = OUTPUT_DIR / "db_rows.json"
        db_path.write_text(
            json.dumps(db_rows, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    # Write summary
    summary = {
        "normalizer_version": "normalize-v1",
        "extracted_files": len(extracted_docs),
        "articles_produced": len(all_articles),
        "total_chunks": len(all_chunks),
        "chunks_over_limit": sum(1 for c in all_chunks if c["over_limit"]),
        "articles_with_scope_tags": sum(
            1 for a in all_articles
            if any(s["scope_tags"] for s in a["sections"])
        ),
        "all_scope_tags": sorted({
            t for a in all_articles
            for s in a["sections"]
            for t in s["scope_tags"]
        }),
        "errors": errors,
        "articles": [
            {
                "id": a["id"],
                "title": a["title"],
                "topic": a["topic"],
                "source_name": a["source_name"],
                "sections": len(a["sections"]),
                "chunks": len(a["chunks"]),
                "content_words": len(a["content_text"].split()),
                "content_sha256": a["content_sha256"],
            }
            for a in all_articles
        ],
    }
    summary_path = OUTPUT_DIR / "summary.json"
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"\n{'='*70}")
    print(f"Normalized {len(all_articles)}/{len(extracted_docs)} documents")
    print(f"  Total Chunks: {len(all_chunks)} (all <= {args.max_chunk_words} words)")
    print(f"  Chunks over limit: {summary['chunks_over_limit']}")
    print(f"  Scope tags found: {summary['all_scope_tags']}")
    if errors:
        print(f"  Errors: {len(errors)}")
    print(f"  Output directory: {OUTPUT_DIR}")
    print(f"{'='*70}")

    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
