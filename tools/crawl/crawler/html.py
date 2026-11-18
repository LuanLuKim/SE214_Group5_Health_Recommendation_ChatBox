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


HEADINGS = {"h2", "h3", "h4", "h5", "h6"}


def link_led(node):
    """True when the first non-blank child of ``node`` is a link with an href."""
    for child in node.children:
        if isinstance(child, Comment):
            continue
        if isinstance(child, NavigableString):
            if child.strip():
                return False
            continue
        return child.name == "a" and bool(child.get("href"))
    return False


def link_only(node):
    """True when the node's whole text is its single link (a trailing full stop allowed)."""
    links = node.find_all("a")
    return len(links) == 1 and bool(links[0].get("href")) and space(node.get_text()).rstrip(".") == space(links[0].get_text()).rstrip(".")


def _signpost_item(node):
    if node.name == "p":
        return link_led(node)
    if node.name in {"ul", "ol"}:
        items = node.find_all("li", recursive=False)
        return bool(items) and all(link_led(item) for item in items)
    return False


def signpost_children(section, keep=()):
    """Direct children to drop when ``section`` only points elsewhere, else [].

    A signpost section starts with a heading and every other direct child is a
    link-led paragraph, a list whose items all start with a link, or a link-free
    "card description" paragraph that sits between a sub-heading (h3-h6) and such
    a link. Children matching ``keep`` (e.g. inset callouts) are never dropped and
    are ignored when classifying the section.
    """
    children = [c for c in section.find_all(recursive=False) if not any(c.css.match(k) for k in keep)]
    if not children or children[0].name not in HEADINGS or all(c.name in HEADINGS for c in children):
        return []
    for i, child in enumerate(children):
        if child.name in HEADINGS or _signpost_item(child):
            continue
        before = children[i - 1] if i else None
        after = children[i + 1] if i + 1 < len(children) else None
        if (child.name == "p" and not child.find("a") and before is not None and before.name in HEADINGS - {"h2"}
                and after is not None and _signpost_item(after)):
            continue
        return []
    return children


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
