"""Transform extracted block-level JSON into NormalizedArticle instances.

Reads documents produced by the crawler (``extracted/*.json``), cleans the
blocks, groups them into sections that remember their full heading path, and
drops navigation/boilerplate sections with a recorded reason.

No imports from apps/, packages/, database/ or any backend/frontend code.
Only stdlib + sibling normalizer modules.
"""
from __future__ import annotations

import re
import unicodedata
from typing import Any

from .schema import Block, Section, NormalizedArticle, digest
from .tags import scope_tags  # re-exported for callers and tests

__all__ = ["transform", "scope_tags", "clean_blocks"]

# ── Text cleaning ──────────────────────────────────────────────────────
_INVISIBLE = dict.fromkeys(map(ord, "­​‌‍⁠﻿"), None)
# WHO writes inline lists as "<br/>- &shy;item"; the soft hyphen marks a bullet.
_SOFT_BULLET = re.compile(r"\s*-\s*­\s*")
# A short UI label on its own line, e.g. NHS "Information:" before an inset box.
# Sentence lead-ins such as "The main symptoms include:" are longer and stay.
_LABEL_ONLY = re.compile(r"^[^\W\d_][\w'’&-]*(?: [\w'’&-]+){0,2}:$")
_PAGE_META = re.compile(r"^(?:Page last reviewed:|Next review due:|Media last reviewed:)", re.I)
_HAS_WORD = re.compile(r"\w")

# Sections that are navigation or promotion, not health content.
_BOILERPLATE_HEADING = re.compile(
    r"^(?:help improve nhs services|related information(?: about .+)?|advice in other parts of the uk|"
    r"more in .+|find out more|more information|more about .+|easy read|support links|technical links|"
    r"other useful information|useful links|contents)$",
    re.I,
)

# NHS care-card callouts are marked up as h2/h3 but belong to the section above them.
_CALLOUT_HEADING = re.compile(
    r"^(?:urgent advice|non-urgent advice|immediate action required|important|information|warning):", re.I)

_HEADING_TAGS = {"h1", "h2", "h3", "h4", "h5", "h6"}


def _clean(text: str) -> str:
    text = unicodedata.normalize("NFC", text).replace(" ", " ")
    return " ".join(text.translate(_INVISIBLE).split())


def _render_table(cells: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Turn table cell blocks into one readable line per row.

    With a header row (all ``th``) each row reads ``Flu: …; Cold: …`` so the
    row/column relation survives in plain text.
    """
    rows: dict[int, dict[int, list[str]]] = {}
    header_row: dict[int, bool] = {}
    for cell in cells:
        pos = cell["table"]
        row, col = int(pos["row"]), int(pos["column"])
        rows.setdefault(row, {}).setdefault(col, []).append(cell["text"])
        header_row[row] = header_row.get(row, True) and cell["tag"] == "th"
    ordered = sorted(rows)
    header: dict[int, str] = {}
    if ordered and header_row[ordered[0]] and len(ordered) > 1:
        header = {c: " ".join(t) for c, t in rows[ordered[0]].items()}
        ordered = ordered[1:]
    out = []
    for row in ordered:
        parts = []
        for col in sorted(rows[row]):
            value = " ".join(rows[row][col])
            label = header.get(col)
            parts.append(f"{label}: {value}" if label else value)
        out.append({"id": cells[0]["id"] + f"-r{row}", "tag": "tr", "text": "; ".join(parts),
                    "list_depth": 1, "links": []})
    return out


def clean_blocks(raw_blocks: list[dict[str, Any]], title: str) -> list[dict[str, Any]]:
    """Normalise text, render tables, split soft-hyphen lists, merge labels,
    and remove page metadata or symbol-only blocks."""
    blocks: list[dict[str, Any]] = []
    i = 0
    while i < len(raw_blocks):
        raw = raw_blocks[i]
        table = raw.get("table")
        if table:
            cells = []
            while i < len(raw_blocks) and (raw_blocks[i].get("table") or {}).get("id") == table["id"]:
                cell = dict(raw_blocks[i], text=_clean(raw_blocks[i]["text"]))
                if cell["text"]:
                    cells.append(cell)
                i += 1
            blocks.extend(_render_table(cells) if cells else [])
            continue
        i += 1
        text = unicodedata.normalize("NFC", raw.get("text", ""))
        pieces = _SOFT_BULLET.split(text) if "­" in text else [text]
        for n, piece in enumerate(pieces):
            piece = _clean(piece)
            if not piece or not _HAS_WORD.search(piece) or _PAGE_META.match(piece):
                continue
            block = dict(raw, text=piece)
            if n > 0:
                block.update(id=f"{raw['id']}-b{n}", tag="li", list_depth=1)
            blocks.append(block)

    # Merge a standalone label ("Information:") into the following body block.
    merged: list[dict[str, Any]] = []
    pending_label = ""
    for block in blocks:
        is_heading = block["tag"] in _HEADING_TAGS or block["tag"] == "summary"
        if not is_heading and block["tag"] == "text" and _LABEL_ONLY.match(block["text"]):
            pending_label = (pending_label + " " + block["text"]).strip()
            continue
        if pending_label and not is_heading:
            block = dict(block, text=f"{pending_label} {block['text']}")
            pending_label = ""
        elif pending_label:
            pending_label = ""  # label followed by a heading carries no content
        merged.append(block)
    return merged


def _is_link_only(block: Block) -> bool:
    link_text = " ".join(link.get("text", "") for link in block.links)
    return bool(block.links) and len(link_text.split()) >= 0.8 * len(block.text.split())


# ── Main transform ─────────────────────────────────────────────────────
def transform(extracted: dict[str, Any], *, topic: str = "general") -> NormalizedArticle:
    """Convert one extracted document JSON into a NormalizedArticle."""
    title = _clean(extracted["title"])
    url = extracted["url"]
    final_url = extracted.get("final_url") or url
    page_id = digest(url)[:16]
    blocks_raw = clean_blocks(extracted["blocks"], title)

    sections: list[Section] = []
    # stack entries: (level, heading text, details container dom path or None)
    stack: list[tuple[int, str, str | None]] = []
    counter = 0

    def open_section() -> Section:
        nonlocal counter
        section = Section(
            id=f"{page_id}-s{counter:02d}",
            heading=stack[-1][1] if stack else "",
            heading_level=stack[-1][0] if stack else 0,
            heading_path=[entry[1] for entry in stack],
        )
        counter += 1
        sections.append(section)
        return section

    current = open_section()
    for raw in blocks_raw:
        tag, text = raw["tag"], raw["text"]
        # Leaving a <details> block: what follows belongs to the parent heading.
        # Checked for headings too, so consecutive accordions are siblings, not nested.
        dom_path = raw.get("dom_path") or ""
        popped = False
        while stack and stack[-1][2] and not dom_path.startswith(stack[-1][2] + " > "):
            stack.pop()
            popped = True
        if tag in _HEADING_TAGS or tag == "summary":
            if tag == "h1" and text == title:
                continue
            if tag == "summary":
                level = (stack[-1][0] + 1) if stack else 3
                container = raw.get("context_path")
            else:
                level, container = int(tag[1]), None
            if _CALLOUT_HEADING.match(text):
                # A warning card belongs to the top-level section it sits in, never
                # to a previous card, a sub-heading or a link list.
                while len(stack) > 1 or (stack and (_CALLOUT_HEADING.match(stack[-1][1])
                                                    or _BOILERPLATE_HEADING.match(stack[-1][1]))):
                    stack.pop()
                if stack:
                    level = stack[-1][0] + 1
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, text, container))
            current = open_section()
            continue

        if popped:
            current = open_section()
        current.blocks.append(Block(
            id=raw["id"], tag=tag, text=text,
            list_depth=raw.get("list_depth", 0) or 0,
            links=raw.get("links") or [],
        ))

    kept: list[Section] = []
    dropped: list[dict[str, str]] = []
    for section in sections:
        if not section.blocks:
            continue
        # Only navigation headings are dropped. Link-only *advice* lists (an NHS
        # "Do" list whose items are links) are content and are kept.
        if any(_BOILERPLATE_HEADING.match(h) for h in section.heading_path):
            reason = "links_only" if all(_is_link_only(b) for b in section.blocks) else "boilerplate"
            dropped.append({"section_id": section.id, "heading": " › ".join(section.heading_path), "reason": reason})
            continue
        section.scope_tags = scope_tags(" › ".join(section.heading_path), section.body_text)
        kept.append(section)

    # A hub page only lists links to its sub-pages: no body of its own.
    body_blocks = [b for s in sections for b in s.blocks]
    link_items = [b for b in body_blocks if b.tag == "li" and _is_link_only(b)]
    kept_words = sum(len(s.body_text.split()) for s in kept)
    content_kind = "hub" if len(link_items) >= 3 and len(link_items) >= 0.6 * len(body_blocks) and kept_words < 80 else "article"

    return NormalizedArticle(
        id=page_id,
        title=title,
        topic=topic or extracted.get("topic") or "general",
        language=extracted.get("language") or "en",
        source_name=_source_name(extracted.get("adapter", "")),
        source_url=final_url,
        canonical_url=url,
        source_updated_at=extracted.get("source_updated_at"),
        source_published_at=extracted.get("source_published_at"),
        source_last_reviewed=extracted.get("source_last_reviewed"),
        source_next_review_due=extracted.get("source_next_review_due"),
        fetched_at=extracted.get("fetched_at", ""),
        sections=kept,
        content_kind=content_kind,
        dropped_sections=dropped,
    )


def _source_name(adapter: str) -> str:
    names = {"nhs": "NHS", "who": "WHO", "cdc": "CDC"}
    return names.get(adapter, adapter.upper() if adapter else "Unknown")
