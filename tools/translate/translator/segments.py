"""Line and sentence segmentation.

A normalized chunk is a block of short lines (headings, sentences, bullets).
Sentence-level MT models truncate or drop content when given the whole block,
so the unit of translation is the *line*: the bullet marker is kept aside and
put back unchanged, and only unusually long lines are split into sentences.
The Vietnamese text therefore has exactly the lines of the source, which is
what makes line-by-line verification possible.
"""
from __future__ import annotations

import re

_MARKER = re.compile(r"^(\s*(?:[•\-\*–]|\d+[.)])\s+)(.*)$", re.S)
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(])")
_TERMINAL = ".!?:;…"


def split_marker(line: str) -> tuple[str, str]:
    """('• ', 'a cough') for a list item, ('', line) otherwise."""
    match = _MARKER.match(line)
    if match:
        return match.group(1), match.group(2)
    return "", line


def split_sentences(text: str, max_words: int) -> list[str]:
    """Sentences of a long line, packed back together up to ``max_words``; short lines stay whole."""
    if len(text.split()) <= max_words:
        return [text]
    packed: list[str] = []
    current: list[str] = []
    count = 0
    for sentence in _SENTENCE_END.split(text):
        words = len(sentence.split())
        if current and count + words > max_words:
            packed.append(" ".join(current))
            current, count = [], 0
        current.append(sentence)
        count += words
    if current:
        packed.append(" ".join(current))
    return packed


def harmonise(source: str, candidate: str) -> str:
    """Undo two habits of sentence-level MT on list fragments.

    The models end every output with a full stop and start it with a capital,
    even for '• a cough'. When the source fragment has neither, remove them so
    list items stay list items.
    """
    candidate = " ".join(candidate.split())
    if not candidate or not source.strip():
        return candidate
    src = source.strip()
    if src[-1] not in _TERMINAL and candidate.endswith(".") and not candidate.endswith(".."):
        candidate = candidate[:-1].rstrip()
    first = next((c for c in src if c.isalpha()), "")
    if first and first.islower() and len(candidate) > 1 and candidate[0].isupper() and not candidate[1].isupper():
        candidate = candidate[0].lower() + candidate[1:]
    return candidate
