"""LLM meaning checker: a second reader that compares every Vietnamese line with its source.

The quality gate checks what can be counted (numbers, units, bullets, UK
services, wording rules). It cannot see that "unlikely" became "không có khả
năng" or that "babies" became "trẻ sơ sinh" in a sentence no rule covers. In
the SCRUM-148 reviews every returned chunk had passed the gate, so this step
asks the LLM, as a reviewer and not as a translator, to list the lines whose
medical meaning changed. Lines it reports go back to the post-editor once
(``pipeline.verify``); what is still reported afterwards is attached to the
chunk as a ``meaning_check`` issue, so the chunk goes to a human reviewer.

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

import json
from typing import Any

from .glossary import Rules

VERIFY_VERSION = "verify-1"
PROBLEM_TYPES = ("negation", "scope", "certainty", "time", "route", "dose", "term", "added", "dropped", "context")


def _table(rows: list[dict[str, str]]) -> str:
    return "\n".join(f"- {row['en']} -> {row['vi']}" for row in rows)


def verify_prompt(rules: Rules) -> str:
    return f"""You review an English -> Vietnamese translation of public health information (NHS and WHO pages) \
before a chatbot quotes it to ordinary readers in Viet Nam. You do not translate: you find errors.

Input: a JSON array of items. Each item has "id", "title", "section" and "lines"; each line has "n" (its number), \
"en" (the source) and "vi" (the translation).

Report a line only when its Vietnamese changes the medical meaning, i.e. a doctor would correct it:
- negation: a not / never / without / unless / except is lost, added or reversed
- scope: only / any / all / both / either / each moved or lost; a group narrowed or widened (babies are not only \
newborns, "cut yourself" is not "cut your hand"); "any" refers to something else than in the source
- certainty: may / might / can / likely / unlikely / usually / always changed ("unlikely" is "ít có khả năng", \
never "không có khả năng")
- time: before / after / during / within / straight after changed ("after you have been in water" is "sau khi lên \
khỏi nước")
- route: oral, injected, vaginal, inhaled, applied changed (an oral vaccine is never "tiêm"); a form of a medicine \
changed (tablet, pessary, ring, cream)
- dose: a dose, unit, number, age, duration or frequency changed
- term: a wrong medical term or a word-for-word calque (see the terms below)
- added: information that is not in the source (an explanation, an age limit such as "dưới 28 ngày tuổi")
- dropped: information of the source that is missing (a list item, a qualifier, "on the NHS")
- context: something only true in the UK (an NHS service, buying medicines in a supermarket, self-referral, the age \
limits of a service) reads as if it applied in Viet Nam; it must keep "NHS (tại Anh)" or "Tại Anh,"

Do NOT report: style, word order, a synonym that keeps the meaning, decimal commas or space-grouped thousands \
(1,5 and 1 500 are correct), or these planned localisations of UK services:
{_table(rules.tables.get("service_table", []))}

Expected words for ages:
{_table(rules.tables.get("age_table", []))}

Expected terms:
{_table(rules.tables.get("terminology", []))}

Answer ONLY a JSON array with one object per item, same ids. "problems" is empty when the item is correct:
[{{"id": "...", "problems": [{{"line": 3, "type": "scope", "detail": "lỗi và cách viết đúng, bằng tiếng Việt"}}]}}]
"type" is one of: {", ".join(PROBLEM_TYPES)}."""


def build_check_item(unit: Any, bodies: list[str]) -> dict[str, Any]:
    """Reviewer view of one unit: numbered source / translation pairs (lines without letters are left out)."""
    lines = [{"n": i + 1, "en": en, "vi": vi} for i, (en, vi) in enumerate(zip(unit.bodies, bodies))
             if any(ch.isalpha() for ch in en)]
    return {"id": unit.id, "title": unit.title, "section": unit.section, "lines": lines}


def parse_findings(text: str, expected: dict[str, int]) -> dict[str, list[dict[str, Any]]]:
    """Problems per unit id from a well-formed answer; ``expected`` maps the id to its number of lines.

    A unit missing from the answer is left out (not answered), a unit with an empty list is correct.
    """
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end < start:
        return {}
    try:
        answer = json.loads(text[start:end + 1])
    except ValueError:
        return {}
    found: dict[str, list[dict[str, Any]]] = {}
    for entry in answer if isinstance(answer, list) else []:
        if not isinstance(entry, dict) or entry.get("id") not in expected or not isinstance(entry.get("problems"), list):
            continue
        problems = []
        for problem in entry["problems"]:
            if not isinstance(problem, dict):
                continue
            line, detail = problem.get("line"), str(problem.get("detail") or "").strip()
            if isinstance(line, int) and 1 <= line <= expected[entry["id"]] and detail:
                kind = str(problem.get("type") or "")
                problems.append({"line": line, "type": kind if kind in PROBLEM_TYPES else "other", "detail": detail})
        found[entry["id"]] = problems
    return found
