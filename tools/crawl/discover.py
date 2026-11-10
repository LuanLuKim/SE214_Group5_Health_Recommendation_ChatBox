"""Suggest new URLs from links inside pages that were already crawled.

Offline: reads the current extraction of every enabled URL, never fetches.
It only *suggests*; a person reviews the list and copies chosen lines into a
URL list (docs/urls.txt, docs/urls_reference.txt), so the crawler still only
collects approved URLs.

    python -B tools/crawl/discover.py                       # all configured hosts
    python -B tools/crawl/discover.py --prefix /medicines/  # only NHS medicine pages
    python -B tools/crawl/discover.py --prefix /medicines/ --format urls > candidates.txt

Search by name: read links from an index page (e.g. the NHS A-Z, listed in
docs/urls_discovery.txt) and keep those whose link text or last path segment
matches a seed name.

    python -B tools/crawl/discover.py --urls docs/urls_discovery.txt --seeds docs/medicine_seeds.txt

Pick sub-pages by what the page itself calls them, not by guessing slugs: after
crawling the hubs, keep only the links whose text starts with a phrase.

    python -B tools/crawl/discover.py --urls docs/urls_reference.txt --prefix /medicines/
        --link-text "Common questions" --link-text "How and when to take"
"""
import argparse
from collections import defaultdict
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit, urlunsplit

from crawler.config import ROOT, load_sources

# Service finders, accounts, feedback forms and media are never health content.
SKIP_PATH_PARTS = ("/service-search/", "/nhs-services/", "/our-policies/", "/contact-us", "/login",
                   "/accessibility", "/about-us", "/news/", "/media-centre/", "/campaigns/")
URL_LISTS = ("urls.txt", "urls_reference.txt", "urls_discovery.txt")


def canonical(href):
    parts = urlsplit(href)
    if parts.scheme != "https" or not parts.hostname:
        return None
    path = parts.path if parts.path.endswith("/") or "." in parts.path.rsplit("/", 1)[-1] else parts.path + "/"
    if parts.hostname == "www.who.int":
        path = parts.path  # WHO paths have no trailing slash
    return urlunsplit(("https", parts.hostname, path, "", ""))


def enabled_urls(path):
    """Return (enabled URLs, URLs that appear only in comments, e.g. disabled hubs)."""
    urls, disabled = [], set()
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        active = line.split("#", 1)[0].split()
        if active:
            urls.append(active[0])
        elif "#" in line:
            disabled.update(word for word in line.split() if word.startswith("https://"))
    return urls, disabled


def _key(text):
    """Compare names ignoring case, spacing and hyphens: 'Alendronic acid' == 'alendronic-acid'."""
    return re.sub(r"[\s_-]+", " ", text.casefold()).strip()


def load_seeds(path):
    """Return [(canonical name, {name keys})] from 'name | alias | alias' lines."""
    seeds = []
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        names = [n.strip() for n in line.split("#", 1)[0].split("|") if n.strip()]
        if names:
            seeds.append((names[0], {_key(n) for n in names}))
    return seeds


def slug(url):
    return urlsplit(url).path.rstrip("/").rsplit("/", 1)[-1]


def match_seed(url, texts, seeds):
    """Return the seed whose name equals the link text or the last path segment."""
    keys = {_key(slug(url))} | {_key(t) for t in texts}
    for name, names in seeds:
        if keys & names:
            return name
    return None


def discover(urls, known, hosts, collection, prefix=None):
    found = defaultdict(lambda: {"texts": set(), "from": set()})
    for url in urls:
        pointer = collection / "pages" / (sha256(url.encode("utf-8")).hexdigest() + ".json")
        if not pointer.exists():
            continue
        record = json.loads(pointer.read_text(encoding="utf-8"))
        doc = json.loads((collection / "extracted" / record["extraction_file"]).read_text(encoding="utf-8"))
        for block in doc["blocks"]:
            for link in block.get("links") or []:
                target = canonical(link.get("href", ""))
                if not target or target in known:
                    continue
                parts = urlsplit(target)
                if parts.hostname not in hosts or any(p in parts.path for p in SKIP_PATH_PARTS):
                    continue
                if prefix and not parts.path.startswith(prefix):
                    continue
                found[target]["texts"].add(" ".join(link.get("text", "").split()))
                found[target]["from"].add(url)
    return sorted(found.items(), key=lambda item: (-len(item[1]["from"]), item[0]))


def known_urls(lists, collection):
    """Every URL already listed (enabled or commented out) plus its final URL after redirects."""
    known = set()
    for path in lists:
        if not path.exists():
            continue
        urls, disabled = enabled_urls(path)
        known |= {canonical(u) for u in urls} | {canonical(u) for u in disabled}
        for url in urls:
            pointer = collection / "pages" / (sha256(url.encode("utf-8")).hexdigest() + ".json")
            if pointer.exists():
                known.add(canonical(json.loads(pointer.read_text(encoding="utf-8"))["final_url"]))
    known.discard(None)
    return known


def seed_rows(rows, seeds):
    """Keep rows matching a seed; return (rows, seed names with no match)."""
    matched, hits = [], set()
    for target, info in rows:
        name = match_seed(target, info["texts"], seeds)
        if name:
            hits.add(name)
            matched.append((target, {**info, "seed": name}))
    return matched, [name for name, _ in seeds if name not in hits]


def link_text_rows(rows, phrases):
    """Keep rows whose link text starts with one of ``phrases`` (case and spacing ignored)."""
    wanted = [_key(p) for p in phrases]
    return [(target, info) for target, info in rows
            if any(_key(text).startswith(p) for text in info["texts"] for p in wanted)]


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--urls", type=Path, default=ROOT / "docs" / "urls.txt", help="pages whose links are read")
    parser.add_argument("--known", type=Path, action="append",
                        help="URL list whose entries are not suggested again (repeatable; "
                             "default: docs/" + ", docs/".join(URL_LISTS) + ")")
    parser.add_argument("--prefix", help="keep only paths starting with this, e.g. /medicines/")
    parser.add_argument("--seeds", type=Path, help="keep only links matching a name in this file")
    parser.add_argument("--link-text", action="append", metavar="PHRASE",
                        help="keep only links whose text starts with PHRASE (repeatable)")
    parser.add_argument("--format", choices=("table", "urls"), default="table")
    parser.add_argument("--collection", type=Path, default=ROOT / ".data" / "collection", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    sources = load_sources()
    hosts = {h for s in sources.values() if s["policy"]["mode"] == "collect_text" for h in s["hosts"]}
    urls, _ = enabled_urls(args.urls)
    lists = args.known or [ROOT / "docs" / name for name in URL_LISTS]
    known = known_urls([args.urls, *lists], args.collection)
    rows = discover(urls, known, hosts, args.collection, args.prefix)

    if args.seeds:
        rows, missing = seed_rows(rows, load_seeds(args.seeds))
        if missing:
            print("# no link found for seed(s): " + ", ".join(missing), file=sys.stderr)
    if args.link_text:
        rows = link_text_rows(rows, args.link_text)

    for target, info in rows:
        if args.format == "urls":
            print(target)
        elif "seed" in info:
            print(f"{info['seed']:<32s} {target}")
        else:
            print(f"{len(info['from']):>3d}  {target}  — {'; '.join(sorted(info['texts']))[:80]}")
    print(f"# {len(rows)} candidate URL(s); review before adding to a URL list", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
