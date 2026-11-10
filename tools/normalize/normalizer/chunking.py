"""Section-aware chunking for RAG.

Rules
-----
* Never merge across a top-level heading (the first entry of ``heading_path``).
* Inside one top-level heading, pack consecutive sections until ``max_words``;
  a chunk below ``min_words`` keeps absorbing the next section.
* A section longer than ``max_words`` is split on block boundaries.
* The first line of every chunk is ``Title › heading › path`` so a chunk
  read alone still says what it is about.
* Scope tags are computed on the final chunk text.
* Junk and in-article duplicates are dropped *with a reason*, never silently.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, TYPE_CHECKING

from .schema import Block, chunk_uuid, digest
from .tags import scope_tags

if TYPE_CHECKING:
    from .schema import NormalizedArticle, Section

MIN_BODY_WORDS = 8


@dataclass
class _Piece:
    section_id: str
    path: list[str]
    blocks: list[Block]

    @property
    def words(self) -> int:
        return sum(len(b.line.split()) for b in self.blocks) + sum(len(h.split()) for h in self.path[-1:])


def _common_prefix(paths: list[list[str]]) -> list[str]:
    prefix = list(paths[0])
    for path in paths[1:]:
        n = 0
        while n < len(prefix) and n < len(path) and prefix[n] == path[n]:
            n += 1
        prefix = prefix[:n]
    return prefix


def _split_section(section: "Section", budget: int) -> list[_Piece]:
    pieces: list[_Piece] = []
    group: list[Block] = []
    words = 0
    for block in section.blocks:
        size = len(block.line.split())
        if group and words + size > budget:
            pieces.append(_Piece(section.id, section.heading_path, group))
            group, words = [], 0
        group.append(block)
        words += size
    if group:
        pieces.append(_Piece(section.id, section.heading_path, group))
    return pieces


def _render(title: str, pieces: list[_Piece]) -> tuple[str, list[str], str]:
    common = _common_prefix([p.path for p in pieces])
    header = " › ".join([title, *common])
    lines: list[str] = []
    last_rel: list[str] | None = None
    for piece in pieces:
        rel = piece.path[len(common):]
        if rel and rel != last_rel:
            lines.append(" › ".join(rel))
        last_rel = rel
        lines.extend(block.line for block in piece.blocks)
    body = "\n".join(lines)
    return f"{header}\n{body}", common, body


def build_chunks(
    article: "NormalizedArticle", *, max_words: int, min_words: int, as_of: str | None = None
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Return ``(chunks, dropped)`` for one article."""
    if article.content_kind == "hub":
        return [], [{"chunk_key": article.id, "reason": "hub_page"}]

    header_words = len(article.title.split()) + 4
    budget = max(40, max_words - header_words)

    # 1. pieces, grouped by top-level heading
    groups: list[list[_Piece]] = []
    last_top: object = object()
    for section in article.sections:
        top = section.heading_path[0] if section.heading_path else None
        if top != last_top:
            groups.append([])
            last_top = top
        groups[-1].extend(_split_section(section, budget))

    # 2. pack each group
    packed: list[list[_Piece]] = []
    for group in groups:
        chunks_in_group: list[list[_Piece]] = []
        current: list[_Piece] = []
        words = 0
        queue = list(group)
        while queue:
            piece = queue.pop(0)
            if current and words + piece.words > budget:
                if words < min_words and len(piece.blocks) > 1:
                    # Top up a small chunk with leading blocks of the next piece.
                    room, take = budget - words, 0
                    while take < len(piece.blocks) - 1 and \
                            sum(len(b.line.split()) for b in piece.blocks[:take + 1]) + 3 <= room:
                        take += 1
                    if take:
                        current.append(_Piece(piece.section_id, piece.path, piece.blocks[:take]))
                        queue.insert(0, _Piece(piece.section_id, piece.path, piece.blocks[take:]))
                        chunks_in_group.append(current)
                        current, words = [], 0
                        continue
                chunks_in_group.append(current)
                current, words = [], 0
            current.append(piece)
            words += piece.words
        if current:
            chunks_in_group.append(current)
        packed.extend(chunks_in_group)

    # 3. a chunk still below min_words joins its neighbour when both fit, even
    #    across top-level headings: each keeps its own heading line in the text.
    def size(pieces: list[_Piece]) -> int:
        return sum(p.words for p in pieces) + sum(len(h.split()) for p in pieces for h in p.path[:-1])

    merged: list[list[_Piece]] = []
    for pieces in packed:
        if merged and (size(merged[-1]) < min_words or size(pieces) < min_words)                 and size(merged[-1]) + size(pieces) <= budget:
            merged[-1] = merged[-1] + pieces
        else:
            merged.append(pieces)
    packed = merged

    # 4. render, filter, dedupe
    stale = article.is_stale(as_of)
    chunks: list[dict[str, Any]] = []
    dropped: list[dict[str, Any]] = []
    seen: dict[str, str] = {}
    for pieces in packed:
        text, common, body = _render(article.title, pieces)
        key = f"{pieces[0].section_id}-{digest(text)[:12]}"
        body_words = len(body.split())
        body_hash = digest(body)
        if body_words < MIN_BODY_WORDS:
            dropped.append({"chunk_key": key, "reason": "too_short", "words": body_words})
            continue
        if body_hash in seen:
            dropped.append({"chunk_key": key, "reason": "duplicate", "duplicate_of": seen[body_hash]})
            continue
        seen[body_hash] = key
        tags = scope_tags(text)
        if stale:
            tags.append("stale_source")
        word_count = len(text.split())
        chunks.append({
            "id": chunk_uuid(key),
            "chunk_key": key,
            "article_id": article.id,
            "article_uuid": article.uuid,
            "section_id": pieces[0].section_id,
            "section_ids": list(dict.fromkeys(p.section_id for p in pieces)),
            "heading_path": common,
            "heading": " › ".join(common) if common else "Introduction",
            "text": text,
            "text_sha256": digest(text),
            "word_count": word_count,
            "over_limit": word_count > max_words,
            "language": article.language,
            "title": article.title,
            "topic": article.topic,
            "source_name": article.source_name,
            "source_url": article.source_url,
            "canonical_url": article.canonical_url,
            "fetched_at": article.fetched_at,
            "source_last_reviewed": article.source_last_reviewed,
            "source_next_review_due": article.source_next_review_due,
            "scope_tags": tags,
            "stale_source": stale,
            "review_status": article.review_status,
            "rag_eligible": article.rag_eligible,
            "normalizer_version": article.normalizer_version,
        })
    return chunks, dropped
