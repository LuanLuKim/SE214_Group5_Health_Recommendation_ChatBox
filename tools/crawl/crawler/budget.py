"""Per-group crawl budgets: which URL list a request belongs to.

A crawl group is one URL list (``docs/urls*.txt``); its name is the file stem.
Each group has its own request/byte cap (see ``http.MAX_REQUESTS``/``MAX_BYTES``).
``allocate`` re-assigns the request history in ``events.jsonl`` (written before
groups existed) to groups without network access.
"""
from pathlib import Path
import re
from urllib.parse import urlsplit, urlunsplit

URL = re.compile(r"https://\S+")
UNASSIGNED = "unassigned"


def _key(url):
    parts = urlsplit(url)
    return urlunsplit(("https", (parts.hostname or "").lower(), parts.path or "/", parts.query, ""))


def memberships(paths):
    """Map URL -> group. Enabled lines win over commented ones; earlier lists win ties."""
    enabled, commented = {}, {}
    for path in paths:
        group = Path(path).stem
        for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
            value, _, comment = line.partition("#")
            for match in URL.findall(value)[:1]:
                enabled.setdefault(_key(match), group)
            for match in URL.findall(comment):
                commented.setdefault(_key(match), group)
    return {**commented, **enabled}


def allocate(requests, membership):
    """Return {group: request count} for ``requests`` (URLs in request order).

    A page URL counts for the list that contains it. ``robots.txt`` counts for the
    page request right after it (the page that needed it). A URL in no list is a
    redirect target and counts for the request right before it.
    """
    groups, previous, waiting = [], None, 0
    for url in requests:
        if urlsplit(url).path == "/robots.txt":
            waiting += 1
            continue
        group = membership.get(_key(url)) or previous or UNASSIGNED
        groups.extend([group] * (waiting + 1))
        previous, waiting = group, 0
    groups.extend([previous or UNASSIGNED] * waiting)
    counts = {}
    for group in groups:
        counts[group] = counts.get(group, 0) + 1
    return counts


def split_bytes(total, counts):
    """Split charged bytes in proportion to requests; the remainder goes to the largest group."""
    requests = sum(counts.values())
    if not requests:
        return {}
    shares = {group: total * n // requests for group, n in counts.items()}
    largest = max(counts, key=lambda g: (counts[g], g))
    shares[largest] += total - sum(shares.values())
    return shares
