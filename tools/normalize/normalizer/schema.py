"""Canonical schema for normalized health articles.

Transforms extracted block-level data from the crawler into section-based
documents aligned with the ``knowledge.articles`` DB table, plus RAG chunks
for ``knowledge.chunks``.

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from hashlib import sha256
from typing import Any
from uuid import UUID, uuid5

VERSION = "normalize-v2"

# Fixed namespace so ids are identical on every machine and every run.
# Never change it: database rows are keyed by these ids.
NAMESPACE = UUID("6f1c2a8e-3b7d-5e94-a0c1-5e214a7d0b5e")

DEFAULT_MAX_WORDS = 400
DEFAULT_MIN_WORDS = 120


def digest(text: str) -> str:
    return sha256(text.encode("utf-8")).hexdigest()


def article_uuid(canonical_url: str) -> str:
    return str(uuid5(NAMESPACE, "article:" + canonical_url))


def chunk_uuid(chunk_key: str) -> str:
    return str(uuid5(NAMESPACE, "chunk:" + chunk_key))


# ── Lowest-level: a single text block from extracted data ──────────────
@dataclass
class Block:
    id: str
    tag: str
    text: str
    list_depth: int = 0
    links: list[dict[str, str]] = field(default_factory=list)

    @property
    def line(self) -> str:
        bullet = self.tag in {"li", "tr"} and self.list_depth >= 1
        return ("• " if bullet else "") + self.text


# ── Mid-level: a heading + its body blocks ─────────────────────────────
@dataclass
class Section:
    """One heading-scoped section. ``heading_path`` excludes the article title."""
    id: str
    heading: str
    heading_level: int  # 1–6 from h1–h6; 0 for preamble
    blocks: list[Block] = field(default_factory=list)
    heading_path: list[str] = field(default_factory=list)
    scope_tags: list[str] = field(default_factory=list)

    @property
    def body_text(self) -> str:
        return "\n".join(block.line for block in self.blocks)


# ── Top-level: a complete normalized document ──────────────────────────
@dataclass
class NormalizedArticle:
    """A health article ready for the knowledge pipeline."""
    id: str
    title: str
    topic: str
    language: str
    source_name: str
    source_url: str  # citation URL (final URL after redirects)
    source_updated_at: str | None
    source_last_reviewed: str | None
    source_next_review_due: str | None
    fetched_at: str
    sections: list[Section]
    canonical_url: str = ""  # URL as configured in urls.txt; identity key
    source_published_at: str | None = None
    content_kind: str = "article"  # "article" | "hub" (link list only)
    dropped_sections: list[dict[str, str]] = field(default_factory=list)
    normalizer_version: str = VERSION
    review_status: str = "DRAFT"
    rag_eligible: bool = False

    def __post_init__(self) -> None:
        if not self.canonical_url:
            self.canonical_url = self.source_url

    @property
    def uuid(self) -> str:
        return article_uuid(self.canonical_url)

    # ── Derived fields ─────────────────────────────────────────────
    @property
    def content_text(self) -> str:
        """Full article text (``knowledge.articles.content``)."""
        parts: list[str] = []
        for section in self.sections:
            body = section.body_text
            if not body:
                continue
            if section.heading_path:
                parts.append(f"{' › '.join(section.heading_path)}\n{body}")
            else:
                parts.append(body)
        return "\n\n".join(parts)

    @property
    def content_sha256(self) -> str:
        return digest(self.content_text)

    def is_stale(self, as_of: str | None) -> bool:
        due = self.source_next_review_due
        return bool(as_of and due and due < as_of)

    def to_chunks(
        self,
        *,
        max_words: int = DEFAULT_MAX_WORDS,
        min_words: int = DEFAULT_MIN_WORDS,
        as_of: str | None = None,
    ) -> list[dict[str, Any]]:
        from .chunking import build_chunks

        return build_chunks(self, max_words=max_words, min_words=min_words, as_of=as_of)[0]

    def to_db_row(self) -> dict[str, Any]:
        """Map to ``knowledge.articles`` columns."""
        return {
            "id": self.uuid,
            "title": self.title,
            "topic": self.topic,
            "content": self.content_text,
            "source_name": self.source_name,
            "source_url": self.source_url,
            "verified_at": None,
            "review_status": self.review_status,
            "is_synthetic": False,
        }

    def to_dict(
        self,
        *,
        max_words: int = DEFAULT_MAX_WORDS,
        min_words: int = DEFAULT_MIN_WORDS,
        as_of: str | None = None,
    ) -> dict[str, Any]:
        """Full serializable representation. Chunks use the same thresholds as
        ``chunks.jsonl`` so chunk ids match between the two outputs."""
        from .chunking import build_chunks

        chunks, dropped = build_chunks(self, max_words=max_words, min_words=min_words, as_of=as_of)
        data = asdict(self)
        data["uuid"] = self.uuid
        data["content_text"] = self.content_text
        data["content_sha256"] = self.content_sha256
        data["stale_source"] = self.is_stale(as_of)
        data["chunks"] = chunks
        data["dropped_chunks"] = dropped
        data["db_row"] = self.to_db_row()
        return data
