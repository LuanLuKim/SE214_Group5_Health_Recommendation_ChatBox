"""LLM translator, in one of two modes.

``fusion``: the LLM post-edits the drafts of the MT engines into the final Vietnamese line.
``direct``: the LLM translates every line from the English source alone (no MT drafts).

In both modes it returns exactly one Vietnamese line per source line; the quality gate then
checks numbers, localisation and meaning. A line that fails is sent back once with the list of
problems. In fusion mode a line that still fails is replaced by the engines' consensus; in direct
mode there is no consensus, so the line stays and the chunk goes to a reviewer.

Bring your own OpenAI-compatible endpoint, as for the review scripts of
tools/normalize; nothing is preset and the key is never written to a file:

    LLM_BASE_URL, LLM_MODEL, LLM_API_KEY, optional LLM_REASONING (sent as reasoning_effort)

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

import json
import os
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .glossary import Rules

PROMPT_VERSION = "fusion-4"
DIRECT_PROMPT_VERSION = "direct-3"
MODES = ("fusion", "direct")
STOP_STATUS = {401, 402, 403, 429}  # auth, quota, rate limit: stop and keep what is done


class Stop(Exception):
    """Rate limit, quota or auth problem: keep results, end the LLM stage."""


class LLMClient:
    def __init__(self, base: str, key: str, model: str, effort: str = "", timeout: int = 600,
                 retries: int = 3, backoff: float = 10.0):
        self.base, self.key, self.model, self.effort = base.rstrip("/"), key, model, effort
        self.timeout, self.retries, self.backoff = timeout, retries, backoff

    @classmethod
    def from_env(cls, settings: dict[str, Any] | None = None, env: dict[str, str] | None = None) -> "LLMClient | None":
        """Client from LLM_* variables, or None when the endpoint is not configured."""
        env = os.environ if env is None else env
        settings = settings or {}
        base, model, key = env.get("LLM_BASE_URL", ""), env.get("LLM_MODEL", ""), env.get("LLM_API_KEY", "")
        if not (base and model and key):
            return None
        return cls(base, key, model, env.get("LLM_REASONING", settings.get("default_reasoning", "")),
                   timeout=int(settings.get("timeout_seconds", 600)))

    @staticmethod
    def missing_env(env: dict[str, str] | None = None) -> list[str]:
        env = os.environ if env is None else env
        return [name for name in ("LLM_BASE_URL", "LLM_MODEL", "LLM_API_KEY") if not env.get(name)]

    def chat(self, system: str, user: str) -> str:
        payload: dict[str, Any] = {"model": self.model,
                                   "messages": [{"role": "system", "content": system},
                                                {"role": "user", "content": user}]}
        if self.effort:
            payload["reasoning_effort"] = self.effort
        request = Request(self.base + "/chat/completions", data=json.dumps(payload).encode("utf-8"), method="POST",
                          headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.key}"})
        data: dict[str, Any] = {}
        for attempt in range(self.retries):
            try:
                with urlopen(request, timeout=self.timeout) as response:
                    data = json.loads(response.read().decode("utf-8"))
                break
            except HTTPError as exc:
                if exc.code in STOP_STATUS:
                    raise Stop(f"HTTP {exc.code}") from None
                if attempt == self.retries - 1:
                    raise Stop(f"HTTP {exc.code} after retries") from None
            except (URLError, TimeoutError, OSError, ValueError) as exc:
                if attempt == self.retries - 1:
                    raise Stop(type(exc).__name__) from None
            time.sleep(self.backoff * (attempt + 1))
        if data.get("error"):
            error = data["error"]
            raise Stop(str(error.get("code") or error.get("type") or "error") if isinstance(error, dict) else str(error))
        try:
            return data["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError, TypeError):
            return ""


def _table(rows: list[dict[str, str]]) -> str:
    return "\n".join(f"- {row['en']} -> {row['vi']}" for row in rows)


def prompt_version(mode: str = "fusion") -> str:
    return DIRECT_PROMPT_VERSION if mode == "direct" else PROMPT_VERSION


_FUSION_INTRO = """You are the final editor of an English -> Vietnamese translation of public health information (NHS and \
WHO pages) for ordinary readers in Viet Nam. A chatbot will quote these texts, so they must be exact.

Input: a JSON array of items. Each item has "id", "title", "section", optional "medicine_names", and "lines".
Each line has "en" (the source line) and "mt" (drafts from machine-translation systems; they may contain errors, \
and they were made from a source in which UK services were already replaced). Some items carry "previous" (your \
earlier answer) and "problems" (what an automatic check found in it): fix exactly those problems.

For every line write ONE Vietnamese line. Use the drafts as raw material: keep what is right, fix what is wrong, \
and follow the source when the drafts disagree with it. When a draft already has the right meaning and the preferred \
terms below, keep its wording: change words only to fix an error or to apply the tables."""

_DIRECT_INTRO = """You are a medical translator. Translate public health information (NHS and WHO pages) from English \
into Vietnamese for ordinary readers in Viet Nam. A chatbot will quote these texts, so they must be exact.

Input: a JSON array of items. Each item has "id", "title", "section", optional "medicine_names", and "lines".
Each line has "en" (the source line). Some items carry "previous" (your earlier answer) and "problems" (what an \
automatic check found in it): fix exactly those problems and keep the rest.

Translate every line yourself from the English source. Read the whole item first so terms, ages and references \
stay the same from line to line, then write ONE Vietnamese line per source line."""


def _notes(rules: Rules) -> str:
    if not rules.reviewer_notes:
        return ""
    return ("9. Review policy and reviewer notes (human review of earlier runs, in Vietnamese). Rules 1-8 and their "
            "tables win over a note. A note shows a correction and why: apply the same reasoning to every similar "
            "line, do not copy a note onto a line it does not fit.\n" + rules.reviewer_notes + "\n")


def system_prompt(rules: Rules, mode: str = "fusion") -> str:
    intro = _DIRECT_INTRO if mode == "direct" else _FUSION_INTRO
    return intro + f"""

Rules
1. Faithful: add nothing, drop nothing, never weaken or strengthen a warning. Do not explain, do not summarise. \
Human reviewers returned lines for these changes of meaning, so check each of them:
   - negation and conditions: keep every not / never / without / unless / if / except; "not X or Y" means \
neither X nor Y ("until you've not been sick or had diarrhoea for 2 days" = "cho đến khi đã hết cả nôn và tiêu chảy \
ít nhất 2 ngày", not "không còn nôn hoặc tiêu chảy")
   - degree and speed words (sudden, rapid, severe, mild) stay on every word they modify ("sudden growth or weight \
gain" = "phát triển hoặc tăng cân đột ngột")
   - scope: keep only / any / all / both / either / each on the same words; "any" refers to what the source refers \
to; never narrow or widen a group ("cut yourself" is any cut, babies are not only newborns)
   - certainty: may / might / can / likely / unlikely / usually keep their strength ("unlikely" = "ít có khả năng")
   - time and order: before / after / during / within ("straight after you have been in water" = "ngay sau khi lên \
khỏi nước")
   - route and form: oral is never "tiêm"; keep tablet / pessary / ring / cream / vaginal as in the source
   - names of products stay as written (Aqueous Cream)
2. Keep every number, dose, unit, age, duration, temperature and percentage exactly as digits; do not convert units. \
Codes and identifiers stay exactly as written (WHA74.8 keeps its dot; ICD-10, YLDs). Clock times use the 24-hour \
Vietnamese form without changing the time (11am -> 11 giờ, 3pm -> 15 giờ).
3. One output line per input line, same order. Never merge, split, reorder or skip lines. A heading stays a short \
heading, a list item stays a list item (no bullet character, no full stop if the source has none).
4. Medicines: keep the international name of an individual medicine exactly as in the source (paracetamol, \
ibuprofen...). Translate names of medicine groups (antibiotics -> thuốc kháng sinh). Never replace a name with a brand.
5. Plain, natural Vietnamese that a patient understands; address the reader as "bạn". No English left except \
proper names and medicine names. A full English sentence becomes a full Vietnamese sentence with a clear subject.
6. Localise UK services for Viet Nam; what exists only in the UK keeps its UK context:
{_table(rules.tables.get("service_table", []))}
7. Ages: say them the way Vietnamese doctors and parents do, never "nhũ nhi" or "trẻ chập chững"; follow the \
source word and the context, never add an age limit the source does not give:
{_table(rules.tables.get("age_table", []))}
8. Preferred terms (a UK programme name such as 5 A Day is explained once in Vietnamese):
{_table(rules.tables.get("terminology", []))}
{_notes(rules)}
Answer ONLY a JSON array, one object per item, same ids, "vi" with exactly as many strings as the item has lines:
[{{"id": "...", "vi": ["...", "..."]}}]"""


def build_item(unit: Any, previous: list[str] | None = None, problems: list[str] | None = None,
               mode: str = "fusion") -> dict[str, Any]:
    """LLM view of one unit: source lines, with their distinct machine drafts in fusion mode."""
    lines: list[dict[str, Any]] = []
    for i, body in enumerate(unit.bodies):
        if mode == "direct":
            lines.append({"en": body})
            continue
        drafts: list[str] = []
        for engine_lines in unit.drafts.values():
            if engine_lines[i] and engine_lines[i] not in drafts:
                drafts.append(engine_lines[i])
        lines.append({"en": body, "mt": drafts})
    item: dict[str, Any] = {"id": unit.id, "title": unit.title, "section": unit.section, "lines": lines}
    if unit.hint_names:
        item["medicine_names"] = unit.hint_names
    if previous is not None:
        item["previous"] = previous
        item["problems"] = problems or []
    return item


def parse_answer(text: str, expected: dict[str, int]) -> dict[str, list[str]]:
    """Well-formed answers only: a known id and exactly the expected number of non-empty strings."""
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end < start:
        return {}
    try:
        answer = json.loads(text[start:end + 1])
    except ValueError:
        return {}
    kept: dict[str, list[str]] = {}
    for entry in answer if isinstance(answer, list) else []:
        if not isinstance(entry, dict) or entry.get("id") not in expected:
            continue
        lines = entry.get("vi")
        if (isinstance(lines, list) and len(lines) == expected[entry["id"]]
                and all(isinstance(line, str) for line in lines)):
            kept[entry["id"]] = [" ".join(line.split()) for line in lines]
    return kept
