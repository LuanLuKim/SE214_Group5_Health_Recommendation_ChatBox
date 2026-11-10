"""DOM structure helpers independent of publisher and medical subject."""
from hashlib import sha256
import json
import re
from urllib.parse import urljoin

from bs4 import Comment, NavigableString, Tag

BLOCK_TAGS = {"p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "summary", "td", "th", "dt", "dd", "blockquote", "button"}
CONTAINERS = BLOCK_TAGS | {"div", "section", "article", "main", "ul", "ol", "table", "tr", "tbody", "thead", "tfoot", "aside", "details", "dl", "header"}


def space(text):
    return " ".join(text.split())


def digest(text):
    return sha256(text.encode("utf-8")).hexdigest()


def dom_path(node):
    parts = []
    while isinstance(node, Tag) and node.name != "[document]":
        index = 1 + sum(s.name == node.name for s in node.previous_siblings if isinstance(s, Tag))
        parts.append(f"{node.name}:nth-of-type({index})")
        node = node.parent
    return " > ".join(reversed(parts))


def structured_blocks(root, url):
    blocks = []

    def emit(node, content, tag):
        text = space("".join(str(c) if isinstance(c, NavigableString) else c.get_text() for c in content))
        if not text:
            return
        links = []
        for item in content:
            if isinstance(item, Tag):
                for link in ([item] if item.name == "a" else []) + item.find_all("a"):
                    links.append({"text": space(link.get_text()), "href": urljoin(url, link.get("href", ""))})
        cell = node if node.name in {"td", "th"} else node.find_parent(["td", "th"])
        table = None
        if cell and cell.find_parent("tr") and cell.find_parent("table"):
            row, parent = cell.find_parent("tr"), cell.find_parent("table")
            table = {"id": dom_path(parent), "row": next(i for i, r in enumerate(parent.find_all("tr")) if r is row),
                     "column": next(i for i, c in enumerate(c for c in row.find_all(["td", "th"]) if c.find_parent("tr") is row) if c is cell),
                     "rowspan": cell.get("rowspan", "1"), "colspan": cell.get("colspan", "1")}
        context = node.find_parent(["li", "aside", "details", "section", "article"])
        blocks.append({"id": f"L{len(blocks) + 1:04d}", "tag": tag, "text": text,
                       "html_line": getattr(node, "sourceline", None), "dom_path": dom_path(node),
                       "list_depth": len(node.find_parents(["ul", "ol"])),
                       "context_path": dom_path(context) if context else None,
                       "table": table, "links": links})

    def visit(node, depth=0):
        if depth > 200:
            raise ValueError("HTML_DEPTH_LIMIT")
        pending = []

        def flush():
            emit(node, pending, node.name if node.name in BLOCK_TAGS else "text")
            pending.clear()

        for child in node.children:
            if isinstance(child, Comment):
                continue
            if isinstance(child, Tag):
                if child.name == "br":
                    pending.append(NavigableString(" "))
                    pending.append(NavigableString(child.get_text()))
                elif child.name in CONTAINERS or child.find(list(CONTAINERS)):
                    flush()
                    visit(child, depth + 1)
                else:
                    pending.append(child)
            else:
                pending.append(child)
        flush()

    visit(root)
    original = re.sub(r"\s+", "", root.get_text())
    extracted = re.sub(r"\s+", "", "".join(block["text"] for block in blocks))
    if original != extracted:
        raise ValueError("TEXT_COVERAGE_MISMATCH")
    return blocks


def page_metadata(soup):
    pages, warnings = [], []

    def scan(value):
        if isinstance(value, dict):
            types = value.get("@type", [])
            types = [types] if isinstance(types, str) else types
            if isinstance(types, list) and any(isinstance(t, str) and t in {"MedicalWebPage", "WebPage", "Article", "NewsArticle", "FAQPage"} for t in types):
                pages.append(value)
            for child in value.values():
                scan(child)
        elif isinstance(value, list):
            for child in value:
                scan(child)

    for script in soup.select('script[type="application/ld+json"]'):
        try:
            scan(json.loads(script.string or script.get_text()))
        except (ValueError, RecursionError):
            warnings.append("INVALID_JSONLD")
    page = next((p for p in pages if p.get("@type") == "MedicalWebPage"), pages[0] if pages else {})
    return page, warnings
