"""Canonical schema for normalized health articles.

Transforms extracted block-level data from the crawler into section-based
documents aligned with the ``knowledge.articles`` DB table schema.

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from hashlib import sha256
from typing import Any

VERSION = "normalize-v1"


def digest(text: str) -> str:
    return sha256(text.encode("utf-8")).hexdigest()


# ── Lowest-level: a single text block from extracted data ──────────────
@dataclass
class Block:
    id: str
    tag: str
    text: str
    list_depth: int = 0
    links: list[dict[str, str]] = field(default_factory=list)


# ── Mid-level: a heading + its body blocks ─────────────────────────────
@dataclass
class Section:
    """One heading-scoped section of a health article."""
    id: str
    heading: str
    heading_level: int  # 1–6 from h1–h6; 0 for preamble
    blocks: list[Block] = field(default_factory=list)
    scope_tags: list[str] = field(default_factory=list)

    @property
    def body_text(self) -> str:
        """Join block texts with newline, preserving list items."""
        lines: list[str] = []
        for block in self.blocks:
            prefix = "• " if block.tag == "li" and block.list_depth >= 1 else ""
            lines.append(prefix + block.text)
        return "\n".join(lines)


# ── Top-level: a complete normalized document ──────────────────────────
@dataclass
class NormalizedArticle:
    """A health article ready for the knowledge pipeline."""
    id: str
    title: str
    topic: str
    language: str
    source_name: str
    source_url: str
    source_updated_at: str | None
    source_last_reviewed: str | None
    source_next_review_due: str | None
    fetched_at: str
    sections: list[Section]
    normalizer_version: str = VERSION
    review_status: str = "DRAFT"
    rag_eligible: bool = False

    # ── Derived fields ─────────────────────────────────────────────
    @property
    def content_text(self) -> str:
        """Full article text: heading + body per section, separated by
        double newlines.  This maps to ``knowledge.articles.content``."""
        parts: list[str] = []
        for section in self.sections:
            body = section.body_text
            if not body:
                continue
            if section.heading and section.heading_level > 0:
                parts.append(f"{section.heading}\n{body}")
            else:
                parts.append(body)
        return "\n\n".join(parts)

    @property
    def content_sha256(self) -> str:
        return digest(self.content_text)

    def to_chunks(self, *, max_words: int = 650) -> list[dict[str, Any]]:
        """Produce RAG-oriented chunks, one per section.

        If a section exceeds ``max_words``, it is cleanly subdivided across
        block boundaries with a part indicator ``(phần N)``.
        """
        chunks: list[dict[str, Any]] = []
        for section in self.sections:
            if not section.blocks:
                continue
            heading = section.heading or "Overview"

            # Subdivide blocks if the section is too large for a single chunk
            header_words = len(f"{self.title}\n{heading}\n".split())
            sub_chunks: list[list[Block]] = []
            current_group: list[Block] = []
            current_words = header_words

            for block in section.blocks:
                b_words = len(block.text.split())
                if current_group and (current_words + b_words > max_words):
                    sub_chunks.append(current_group)
                    current_group = [block]
                    current_words = header_words + b_words
                else:
                    current_group.append(block)
                    current_words += b_words

            if current_group:
                sub_chunks.append(current_group)

            for idx, blks in enumerate(sub_chunks):
                sub_sec = Section(
                    id=section.id,
                    heading=heading,
                    heading_level=section.heading_level,
                    blocks=blks,
                )
                body = sub_sec.body_text
                if not body:
                    continue
                sub_heading = (
                    f"{heading} (phần {idx + 1})"
                    if len(sub_chunks) > 1
                    else heading
                )
                chunk_text = f"{self.title}\n{sub_heading}\n{body}"
                word_count = len(chunk_text.split())
                chunk_id = (
                    f"{section.id}-p{idx:02d}-{digest(chunk_text)[:12]}"
                    if len(sub_chunks) > 1
                    else f"{section.id}-{digest(chunk_text)[:12]}"
                )
                chunks.append({
                    "id": chunk_id,
                    "article_id": self.id,
                    "section_id": section.id,
                    "heading": sub_heading,
                    "text": chunk_text,
                    "text_sha256": digest(chunk_text),
                    "word_count": word_count,
                    "over_limit": word_count > max_words,
                    "language": self.language,
                    "source_url": self.source_url,
                    "source_name": self.source_name,
                    "fetched_at": self.fetched_at,
                    "scope_tags": section.scope_tags,
                    "review_status": self.review_status,
                    "rag_eligible": self.rag_eligible,
                    "normalizer_version": self.normalizer_version,
                })
        return chunks

    def to_db_row(self) -> dict[str, Any]:
        """Map to ``knowledge.articles`` columns."""
        return {
            "title": self.title,
            "topic": self.topic,
            "content": self.content_text,
            "source_name": self.source_name,
            "source_url": self.source_url,
            "verified_at": None,
            "review_status": self.review_status,
            "is_synthetic": False,
        }

    def to_dict(self) -> dict[str, Any]:
        """Full serializable representation for the JSON output."""
        data = asdict(self)
        data["content_text"] = self.content_text
        data["content_sha256"] = self.content_sha256
        data["chunks"] = self.to_chunks()
        data["db_row"] = self.to_db_row()
        return data
