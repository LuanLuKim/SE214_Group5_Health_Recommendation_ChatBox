"""Normalize extracted documents into articles, RAG chunks and DB rows.

Usage (from repo root, inside conda env):
    python -B tools/normalize/run.py                  # URLs enabled in docs/urls.txt
    python -B tools/normalize/run.py --all-extracted  # every extracted document (e.g. the example)
    python -B tools/normalize/run.py --status         # show current output summary
    python -B tools/normalize/run.py --corpus expert_reference
        # docs/urls_reference.txt -> .data/normalized_reference; never RAG

Corpora: ``rag`` is the chatbot knowledge base (still DRAFT until reviewed).
``expert_reference`` holds medicine pages that content experts consult while
writing general articles; the project excludes drug lookup, so these chunks are
written with ``rag_eligible = false`` and ``corpus = "expert_reference"``
regardless of review status, and the loader must keep them out of retrieval.

Reads the *current* extraction of each URL through the crawler's
``pages/<sha256(url)>.json`` pointer; older extraction versions stay on disk
but are ignored. Output is deterministic: same input, same ids and bytes.

No imports from apps/, packages/, database/ or any backend/frontend/LLM code.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter
from datetime import date
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COLLECTION_DIR = ROOT / ".data" / "collection"
EXTRACTED_DIR = COLLECTION_DIR / "extracted"
OUTPUT_DIR = ROOT / ".data" / "normalized"
CORPORA = {
    "rag": (ROOT / "docs" / "urls.txt", OUTPUT_DIR),
    "expert_reference": (ROOT / "docs" / "urls_reference.txt", ROOT / ".data" / "normalized_reference"),
}


def load_url_topics(path: Path) -> dict[str, str]:
    """Read enabled URLs and their topic from urls.txt (``<URL> [topic]``)."""
    mapping: dict[str, str] = {}
    if not path.exists():
        return mapping
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        mapping[parts[0]] = parts[1] if len(parts) > 1 else "general"
    return mapping


def load_extracted(collection: Path, urls: list[str] | None) -> tuple[list[dict], list[dict]]:
    """Return (documents, problems). With ``urls`` only those URLs are read,
    each through its cache pointer; otherwise the newest file per URL."""
    docs: list[dict] = []
    problems: list[dict] = []
    extracted = collection / "extracted"
    if urls is not None:
        for url in urls:
            pointer = collection / "pages" / (sha256(url.encode("utf-8")).hexdigest() + ".json")
            if not pointer.exists():
                problems.append({"url": url, "reason": "not_crawled"})
                continue
            record = json.loads(pointer.read_text(encoding="utf-8"))
            path = extracted / record.get("extraction_file", "")
            if not record.get("extraction_file") or not path.exists():
                problems.append({"url": url, "reason": "missing_extraction"})
                continue
            doc = json.loads(path.read_text(encoding="utf-8"))
            doc["_source_file"] = path.name
            docs.append(doc)
        return docs, problems
    newest: dict[str, tuple[float, Path]] = {}
    for path in sorted(extracted.glob("*.json")):
        url = json.loads(path.read_text(encoding="utf-8")).get("url", path.name)
        stamp = path.stat().st_mtime
        if url not in newest or stamp > newest[url][0]:
            newest[url] = (stamp, path)
    for url in sorted(newest):
        path = newest[url][1]
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc["_source_file"] = path.name
        docs.append(doc)
    return docs, problems


def load_medicine_names(index_urls: Path, collection: Path) -> frozenset[str]:
    """Medicine names from the crawled index pages whose topic is 'medication_index'."""
    from normalizer.vocabulary import medicine_names

    urls = [url for url, topic in load_url_topics(index_urls).items() if topic == "medication_index"]
    docs, _ = load_extracted(collection, urls) if urls else ([], [])
    return medicine_names(docs)


def _mark_corpus(data: dict, corpus: str) -> None:
    """Stamp the corpus on the article, its DB row and every chunk."""
    data["db_row"]["corpus"] = corpus
    for record in [data, *data["chunks"]]:
        record["corpus"] = corpus
        if corpus != "rag":
            record["rag_eligible"] = False


def _dump(path: Path, data) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    from normalizer.schema import DEFAULT_MAX_WORDS, DEFAULT_MIN_WORDS, VERSION

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", choices=sorted(CORPORA), default="rag",
                        help="rag (default) or expert_reference (medicine pages, never RAG)")
    parser.add_argument("--urls", type=Path, help="URL list with topic mappings (default depends on --corpus)")
    parser.add_argument("--index-urls", type=Path, default=ROOT / "docs" / "urls_discovery.txt",
                        help="index pages; those with topic 'medication_index' supply medicine names")
    parser.add_argument("--all-extracted", action="store_true", help="ignore urls.txt; newest extraction of every URL")
    parser.add_argument("--collection", type=Path, default=COLLECTION_DIR, help=argparse.SUPPRESS)
    parser.add_argument("--output", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--status", action="store_true", help="show summary of normalized output")
    parser.add_argument("--chunks-only", action="store_true", help="skip db_rows.json")
    parser.add_argument("--max-chunk-words", type=int, default=DEFAULT_MAX_WORDS)
    parser.add_argument("--min-chunk-words", type=int, default=DEFAULT_MIN_WORDS)
    parser.add_argument("--as-of", default=date.today().isoformat(),
                        help="date (YYYY-MM-DD) used for the stale_source flag; default today")
    args = parser.parse_args(argv)
    default_urls, default_output = CORPORA[args.corpus]
    args.urls = args.urls or default_urls
    output: Path = args.output or default_output

    if args.status:
        summary_path = output / "summary.json"
        print(summary_path.read_text(encoding="utf-8") if summary_path.exists() else "No normalization output yet.")
        return 0

    from normalizer.transform import transform

    url_topics = load_url_topics(args.urls)
    if not args.all_extracted and not url_topics:
        print(f"No enabled URLs in {args.urls}; use --all-extracted to read every extraction.", file=sys.stderr)
        return 1
    docs, problems = load_extracted(args.collection, None if args.all_extracted else list(url_topics))
    if not docs:
        print("No extracted files found.", file=sys.stderr)
        return 1

    output.mkdir(parents=True, exist_ok=True)
    articles_dir = output / "articles"
    articles_dir.mkdir(exist_ok=True)
    for stale_file in articles_dir.glob("*.json"):
        stale_file.unlink()

    medicine_names = load_medicine_names(args.index_urls, args.collection)
    if not medicine_names:
        print(f"  WARN no medicine names: no crawled 'medication_index' page in {args.index_urls}", file=sys.stderr)
    limits = dict(max_words=args.max_chunk_words, min_words=args.min_chunk_words, as_of=args.as_of,
                  medicine_names=medicine_names)
    all_articles, all_chunks, errors, hubs = [], [], [], []
    dropped_chunks, dropped_sections = [], []

    for doc in docs:
        source_file = doc.pop("_source_file", "unknown")
        url = doc.get("url", "")
        try:
            article = transform(doc, topic=url_topics.get(url, "general"))
            data = article.to_dict(**limits)
            _mark_corpus(data, args.corpus)
            data["_source_file"] = source_file
            _dump(articles_dir / f"{article.id}.json", data)
            dropped_sections += [{"url": url, **d} for d in data["dropped_sections"]]
            dropped_chunks += [{"url": url, **d} for d in data["dropped_chunks"]]
            if article.content_kind == "hub":
                hubs.append(url)
                print(f"  HUB  {article.title[:40]:<40s}  (link list only, no chunks)")
                continue
            all_articles.append(data)
            all_chunks.extend(data["chunks"])
            tags = sorted({t for c in data["chunks"] for t in c["scope_tags"]})
            print(f"  OK   {article.title[:40]:<40s}  topic={article.topic:<13s} "
                  f"{len(data['chunks']):>2d} chunks  {len(article.content_text.split()):>5d} words  tags={tags}")
        except Exception as exc:  # report and continue: one bad page must not stop the batch
            errors.append({"file": source_file, "error": str(exc), "type": type(exc).__name__})
            print(f"  FAIL {source_file}: {type(exc).__name__}: {exc}", file=sys.stderr)

    (output / "chunks.jsonl").write_text(
        "".join(json.dumps(c, ensure_ascii=False) + "\n" for c in all_chunks), encoding="utf-8", newline="\n")
    if not args.chunks_only:
        _dump(output / "db_rows.json", [a["db_row"] for a in all_articles])

    words = [c["word_count"] for c in all_chunks] or [0]
    tag_counts = Counter(t for c in all_chunks for t in c["scope_tags"])
    summary = {
        "normalizer_version": VERSION,
        "corpus": args.corpus,
        "medicine_names_from_index": len(medicine_names),
        "as_of": args.as_of,
        "limits": {"max_chunk_words": args.max_chunk_words, "min_chunk_words": args.min_chunk_words},
        "extracted_documents": len(docs),
        "articles_produced": len(all_articles),
        "hub_pages": hubs,
        "total_chunks": len(all_chunks),
        "chunks_over_limit": sum(1 for c in all_chunks if c["over_limit"]),
        "chunks_below_min": sum(1 for c in all_chunks if c["word_count"] < args.min_chunk_words),
        "chunk_words": {"min": min(words), "median": statistics.median(words), "max": max(words),
                        "mean": round(statistics.fmean(words), 1)},
        "languages": dict(sorted(Counter(c["language"] for c in all_chunks).items())),
        "scope_tag_counts": dict(sorted(tag_counts.items())),
        "interaction_term_counts": dict(sorted(Counter(t for c in all_chunks for t in c["interaction_terms"]).items())),
        "review_status": dict(Counter(c["review_status"] for c in all_chunks)),
        "rag_eligible": sum(1 for c in all_chunks if c["rag_eligible"]),
        "dropped_sections": dropped_sections,
        "dropped_chunks": dropped_chunks,
        "input_problems": problems,
        "errors": errors,
        "articles": [
            {"id": a["id"], "uuid": a["uuid"], "title": a["title"], "topic": a["topic"],
             "language": a["language"], "source_name": a["source_name"], "source_url": a["source_url"],
             "sections": len(a["sections"]), "chunks": len(a["chunks"]),
             "content_words": len(a["content_text"].split()), "stale_source": a["stale_source"],
             "content_sha256": a["content_sha256"]}
            for a in all_articles
        ],
    }
    _dump(output / "summary.json", summary)

    print(f"\n{'=' * 70}")
    print(f"Normalized {len(all_articles)} articles from {len(docs)} documents ({len(hubs)} hub pages skipped)")
    print(f"  Chunks: {len(all_chunks)}  words min/median/max = "
          f"{summary['chunk_words']['min']}/{summary['chunk_words']['median']}/{summary['chunk_words']['max']}")
    print(f"  Over limit: {summary['chunks_over_limit']}  below min: {summary['chunks_below_min']}")
    print(f"  Dropped: {len(dropped_sections)} sections, {len(dropped_chunks)} chunks")
    print(f"  Tags: {summary['scope_tag_counts']}")
    if problems:
        print(f"  Input problems: {problems}")
    if errors:
        print(f"  Errors: {len(errors)}")
    print(f"  Output: {output}")
    print("=" * 70)
    return 1 if errors or problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
