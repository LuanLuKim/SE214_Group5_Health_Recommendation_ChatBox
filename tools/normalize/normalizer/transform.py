"""Transform extracted block-level JSON into NormalizedArticle instances.

Reads the ``extracted/*.json`` files produced by the crawler and groups
their flat ``blocks[]`` array into heading-scoped sections, applies
scope-tagging, and produces NormalizedArticle objects.

Config-driven: topics and filters are supplied from configuration,
not hard-coded in this module.

No imports from apps/, packages/, database/ or any backend/frontend code.
Only stdlib + sibling normalizer modules.
"""
from __future__ import annotations

import re
from typing import Any

from .schema import Block, Section, NormalizedArticle, digest

# ── Generic Scope Tagging (Safety & Editorial boundaries) ──────────────
# Flags chunks that require human expert review before production use.
_MEDICATION_RE = re.compile(
    r"antihistamin|corticosteroid|steroid|adrenaline|epipen|jext|"
    r"menthol|emollient|moisturis|paracetamol|ibuprofen|antibiot|"
    r"thuốc|kháng sinh|aspirin|hạ sốt|truyền dịch",
    re.I,
)
_PAEDIATRIC_RE = re.compile(
    r"\b(child|children|baby|babies|toddler|infant|infants|neonate|neonates|pediatric|paediatric)\b|"
    r"trẻ em|trẻ sơ sinh|trẻ nhỏ|trẻ mới sinh|bé|"
    r"\b\d+\s*(ngày tuổi|tháng tuổi|tuổi)\b|"
    r"dưới \d+\s*(ngày|tháng|tuổi)|bú mẹ|bú sữa",
    re.I,
)
_FOREIGN_SERVICE_RE = re.compile(r"\b(999|111|GP|NHS)\b|A&E")
_VN_SERVICE_RE = re.compile(r"\b(115|trạm y tế|bệnh viện tuyến)\b", re.I)
_EMERGENCY_RE = re.compile(
    r"immediate action required|cấp cứu|khẩn cấp|nguy kịch|"
    r"dấu hiệu nguy hiểm|dấu hiệu cảnh báo|call 999|gọi 115",
    re.I,
)


def scope_tags(heading: str, body: str) -> list[str]:
    """Detect content that needs editorial attention before going live."""
    combined = heading + "\n" + body
    tags: list[str] = []
    if _MEDICATION_RE.search(combined):
        tags.append("medication")
    if _PAEDIATRIC_RE.search(combined):
        tags.append("paediatric")
    if _FOREIGN_SERVICE_RE.search(combined):
        tags.append("foreign_service")
    if _VN_SERVICE_RE.search(combined):
        tags.append("vn_service")
    if _EMERGENCY_RE.search(heading) or _EMERGENCY_RE.search(body[:200]):
        tags.append("emergency")
    return tags


# ── Heading detection ──────────────────────────────────────────────────
_HEADING_TAGS = {"h1", "h2", "h3", "h4", "h5", "h6"}


def _heading_level(tag: str) -> int:
    if tag in _HEADING_TAGS:
        return int(tag[1])
    return 0


# ── Main transform ─────────────────────────────────────────────────────
def transform(extracted: dict[str, Any], *, topic: str = "general") -> NormalizedArticle:
    """Convert one extracted document JSON into a NormalizedArticle.

    Parameters
    ----------
    extracted : dict
        The full JSON object from ``.data/collection/extracted/*.json``.
    topic : str, default 'general'
        Topic configured for this document (e.g. from urls.txt or manifest).
    """
    blocks_raw = extracted["blocks"]
    title = extracted["title"].strip()
    url = extracted["url"]
    source_name = _source_name(extracted.get("adapter", ""))
    page_id = digest(url)[:16]

    # Group blocks into sections based on structural headings
    sections: list[Section] = []
    current_section = Section(
        id=f"{page_id}-s00",
        heading="",
        heading_level=0,
    )
    section_index = 0

    for raw_block in blocks_raw:
        text = raw_block["text"].strip()
        if not text:
            continue

        tag = raw_block["tag"]
        level = _heading_level(tag)

        # A heading starts a new section (skip title preamble if repeated)
        if level > 0 and text != title:
            if current_section.blocks or current_section.heading:
                sections.append(current_section)
            section_index += 1
            current_section = Section(
                id=f"{page_id}-s{section_index:02d}",
                heading=text,
                heading_level=level,
            )
        else:
            block = Block(
                id=raw_block["id"],
                tag=tag,
                text=text,
                list_depth=raw_block.get("list_depth", 0),
                links=raw_block.get("links", []),
            )
            current_section.blocks.append(block)

    # Append trailing section
    if current_section.blocks or current_section.heading:
        sections.append(current_section)

    # If the document had no subheadings, provide a clean default
    if not sections:
        sections.append(Section(id=f"{page_id}-s00", heading="Overview", heading_level=1))

    # Apply scope tags
    for section in sections:
        section.scope_tags = scope_tags(section.heading, section.body_text)

    # Use explicit topic if given, else check extracted payload
    resolved_topic = topic or extracted.get("topic") or "general"

    return NormalizedArticle(
        id=page_id,
        title=title,
        topic=resolved_topic,
        language=extracted.get("language", "en") or "en",
        source_name=source_name,
        source_url=url,
        source_updated_at=extracted.get("source_updated_at"),
        source_last_reviewed=extracted.get("source_last_reviewed"),
        source_next_review_due=extracted.get("source_next_review_due"),
        fetched_at=extracted.get("fetched_at", ""),
        sections=sections,
    )


def _source_name(adapter: str) -> str:
    names = {"nhs": "NHS", "who": "WHO", "cdc": "CDC"}
    return names.get(adapter, adapter.upper() if adapter else "Unknown")
