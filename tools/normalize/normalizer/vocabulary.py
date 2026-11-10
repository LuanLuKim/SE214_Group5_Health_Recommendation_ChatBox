"""Medicine names read from crawled index pages, not written into code.

An index page (topic ``medication_index`` in docs/urls_discovery.txt, e.g. the
NHS Medicines A-Z) links to one page per medicine. Every link that leads below
the index URL names a medicine:

* ``Aciclovir (Zovirax)``                     -> aciclovir, zovirax
* ``Clarityn, see Loratadine (Clarityn)``     -> clarityn, loratadine
* ``Paracetamol for adults`` + ``... for children`` -> both names, and
  ``paracetamol`` because the first word starts more than one entry.

A multi-word name whose first word starts only one entry is kept whole
(``Ferrous sulfate``), so a common first word never becomes a medicine on
its own unless the index itself uses it as a family name.
"""
from __future__ import annotations

from collections import Counter
import re
from typing import Any, Iterable
from urllib.parse import urlsplit

_PAREN = re.compile(r"\(([^)]*)\)")


def _clean(name: str) -> str:
    return " ".join(_PAREN.sub(" ", name).split()).strip(" ,").casefold()


def link_names(text: str) -> list[str]:
    """Names in one index link text: the entry, its 'see' target and brands in brackets."""
    names: list[str] = []
    for part in text.split(", see "):
        names.append(_clean(part))
        names += [_clean(b) for inner in _PAREN.findall(part) for b in inner.split(",")]
    return [n for n in names if len(n) > 2]


def index_entries(doc: dict[str, Any]) -> list[str]:
    """Link texts of links that lead below the index page itself."""
    base = urlsplit(doc.get("final_url") or doc["url"])
    prefix = base.path if base.path.endswith("/") else base.path + "/"
    texts = []
    for block in doc.get("blocks", []):
        for link in block.get("links") or []:
            target = urlsplit(link.get("href", ""))
            if target.hostname == base.hostname and target.path.startswith(prefix) and target.path != prefix:
                texts.append(link.get("text", ""))
    return texts


def medicine_names(index_docs: Iterable[dict[str, Any]]) -> frozenset[str]:
    names: set[str] = set()
    for doc in index_docs:
        for text in index_entries(doc):
            names.update(link_names(text))
    first_words = Counter(n.split()[0] for n in names if " " in n)
    names |= {word for word, count in first_words.items() if count > 1 and len(word) > 3}
    return frozenset(names)
