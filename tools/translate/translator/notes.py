"""Reviewer guidance for the LLM: the review policy and every line a human reviewer corrected, with the reason.

The correction files are the ones of ``paths.human_corrections`` (later file wins for the same chunk line,
as when the corrections are applied). A correction whose new line breaks a decision of the policy (the word
``nhũ nhi``, replaced by the age decisions of 18/11) is left out, so a note never contradicts the rules.

``exclude`` drops the notes of some chunks: an evaluation on reviewed chunks passes the chunks it translates,
so the score shows whether the model applies the guidance to lines it has not seen corrected.
"""
from __future__ import annotations

import difflib
import json
import re
from pathlib import Path
from typing import Iterable

# wording the user's later decisions removed from older corrections
OVERRULED = ("nhũ nhi",)
_URL = re.compile(r"https?://\S+")
_SOURCE_LABEL = re.compile(r"\s*Nguồn\s*:?\s*$", re.IGNORECASE)  # left when the link is cut
EN_CONTEXT = 160


def _words(text: str) -> list[str]:
    return (text or "").split()


def _changes(old: str, new: str) -> list[tuple[str, str]]:
    """(rejected words, corrected words) for every changed span, words around kept out."""
    o, n = _words(old), _words(new)
    return [(" ".join(o[i1:i2]), " ".join(n[j1:j2]))
            for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, o, n, autojunk=False).get_opcodes()
            if tag != "equal"]


def _short(text: str, limit: int = EN_CONTEXT) -> str:
    text = " ".join((text or "").split())
    return text if len(text) <= limit else text[:limit].rsplit(" ", 1)[0] + " …"


def _reason(row: dict) -> str:
    text = " ".join(_URL.sub("", row.get("reason") or "").split())
    return _SOURCE_LABEL.sub("", text).strip()


def load_rows(paths: Iterable[Path]) -> list[dict]:
    """Corrections of all files, one per chunk line, the later file winning."""
    rows: dict[tuple[str, int], dict] = {}
    for path in paths:
        if Path(path).exists():
            for row in json.loads(Path(path).read_text(encoding="utf-8")).get("corrections", []):
                if row.get("chunk_id") and row.get("new_vi"):
                    rows[(row["chunk_id"], int(row.get("line") or 0))] = row
    return list(rows.values())


def note_lines(rows: Iterable[dict], exclude: set[str] | frozenset[str] = frozenset()) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for row in rows:
        if row["chunk_id"] in exclude or any(word in row["new_vi"] for word in OVERRULED):
            continue
        kind = " / ".join(x for x in (row.get("error_type"), row.get("severity")) if x)
        if row.get("old_vi"):
            changes = _changes(row["old_vi"], row["new_vi"])
            if not changes:
                continue
            fix = "; ".join(f'"{a}" -> "{b}"' if a and b else (f'drop "{a}"' if a else f'add "{b}"')
                            for a, b in changes)
        else:
            fix = f'write "{_short(row["new_vi"], 2 * EN_CONTEXT)}"'
        note = f'- EN "{_short(row.get("en", ""))}": {fix}'
        if kind:
            note += f" [{kind}]"
        if _reason(row):
            note += f" — {_reason(row)}"
        if note not in seen:
            seen.add(note)
            out.append(note)
    return out


def reviewer_notes(policy_path: Path | None, correction_paths: Iterable[Path],
                   exclude: set[str] | frozenset[str] = frozenset()) -> str:
    """Text added to the system prompt; empty when there is neither policy nor note."""
    parts: list[str] = []
    if policy_path and Path(policy_path).exists():
        parts.append(Path(policy_path).read_text(encoding="utf-8").strip())
    notes = note_lines(load_rows(correction_paths), exclude)
    if notes:
        parts.append("Lines the reviewer corrected (rejected words -> corrected words, type, reason):\n"
                     + "\n".join(notes))
    return "\n\n".join(parts)
