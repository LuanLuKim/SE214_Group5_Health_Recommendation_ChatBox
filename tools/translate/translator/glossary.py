"""Localisation rules, number checks and medicine names for the translator.

Nothing here is a word list written into code:

* UK -> Viet Nam rules come from ``tools/translate/localisation.json``.
* Medicine names come from the crawled medicine index (the same source the
  normalizer uses), see :func:`load_medicine_names`.

Earlier versions masked drug names with ``«DRUG_000»`` placeholders and pasted
Vietnamese phrases into the English source. Both damaged the machine
translation input, so the module now (1) rewrites UK-only services into
neutral *English* before translation and (2) verifies names and numbers on the
Vietnamese result instead of hiding them from the models.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import importlib.util
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable

HERE = Path(__file__).resolve().parent
DEFAULT_RULES = HERE.parent / "localisation.json"

# '45 900' (thousands grouped by a space, as WHO and the Vietnamese output write them) is one number
_NUMBER = re.compile(r"(?<!\d)(?<!\d[.,])\d{1,3}(?: \d{3})+(?!\d|[.,]\d)|\d+(?:[.,]\d+)*")
_NAME_BEFORE = re.compile(r"[A-Za-z]-$")
_ORDINAL = re.compile(r"\b\d+(?:st|nd|rd|th)\b", re.I)
_WORD = re.compile(r"[A-Za-z]+")
_LETTERS = re.compile(r"[^\W\d_]+")


def numbers_in(text: str) -> list[str]:
    """Numeric tokens with separators removed: '1,000' and '1.000' -> '1000', '37.5' and '37,5' -> '375'.

    A display helper only: the checks compare values (:func:`number_values`), so 1.5 and 15 differ.
    """
    return [re.sub(r"[., ]", "", m.group(0)) for m in _NUMBER.finditer(text)]


def _decimal(whole: str, fraction: str) -> str:
    fraction = fraction.rstrip("0")
    return f"{int(whole)}.{fraction}" if fraction else str(int(whole))


def number_values(token: str) -> set[str]:
    """Every value a numeric token can be: separators mean thousands or a decimal point.

    '1,000' / '1.000' -> {'1000', '1'}; '37.5' / '37,5' -> {'37.5'}; '1,999' -> {'1999', '1.999'}.
    Vietnamese writes '1.000' and '37,5', the English source '1,000' and '37.5', a machine
    translation either: both readings are accepted, a lost decimal point (1.5 -> 15) is not.
    A space only groups thousands: '45 900' -> {'45900', '45', '900'} (the parts too, so a
    list such as 'aged 5 100' is not lost).
    """
    if " " in token:
        parts = token.split(" ")
        return {str(int("".join(parts)))} | {str(int(p)) for p in parts}
    groups = re.split(r"[.,]", token)
    if len(groups) == 1:
        return {str(int(token))}
    values = set()
    if 1 <= len(groups[0]) <= 3 and all(len(g) == 3 for g in groups[1:]):
        values.add(str(int("".join(groups))))
    if len(groups) == 2:
        values.add(_decimal(*groups))
    return values or {str(int("".join(groups)))}


def source_value(token: str) -> str:
    """The value an English source token stands for: ',' or ' ' + three digits groups thousands, '.' is a decimal point."""
    if " " in token:
        return str(int(token.replace(" ", "")))
    groups = re.split(r"[.,]", token)
    if "," in token and "." not in token and all(len(g) == 3 for g in groups[1:]):
        return str(int("".join(groups)))
    if len(groups) == 2:
        return _decimal(*groups)
    return str(int("".join(groups)))


_SENTENCE_START = re.compile(r"(?:^|[.!?]\s+)$")


def _keep_case(match: re.Match, replacement: str) -> str:
    """Capitalise the replacement when the match opened a sentence or was a capitalised word ('Call 999').

    An acronym in the middle of a sentence ('go to A&E', 'your GP') does not make the replacement a capital.
    """
    original = match.group(0)
    capitalised_word = original[:1].isupper() and original[1:2].islower()
    starts_sentence = not original[:1].islower() and bool(_SENTENCE_START.search(match.string[:match.start()]))
    return replacement[:1].upper() + replacement[1:] if capitalised_word or starts_sentence else replacement


@dataclass(frozen=True)
class MeaningCheck:
    """One reviewer finding turned into a rule: when the source says X, the Vietnamese must (not) say Y."""

    label: str
    source: re.Pattern
    hint: str
    forbid: re.Pattern | None = None
    require: re.Pattern | None = None
    unless_source: re.Pattern | None = None
    on_rewritten: bool = False

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "MeaningCheck":
        def opt(key: str) -> re.Pattern | None:
            return re.compile(data[key]) if data.get(key) else None

        if not (data.get("forbid") or data.get("require")):
            raise ValueError(f"meaning check {data.get('label')!r} needs 'forbid' or 'require'")
        return cls(label=data["label"], source=re.compile(data["source"]), hint=data.get("hint", ""),
                   forbid=opt("forbid"), require=opt("require"), unless_source=opt("unless_source"),
                   on_rewritten=data.get("on") == "rewritten")

    def broken(self, source: str, rewritten: str, target: str) -> bool:
        text = rewritten if self.on_rewritten else source
        if not self.source.search(text) or (self.unless_source and self.unless_source.search(text)):
            return False
        return bool((self.forbid and self.forbid.search(target)) or (self.require and not self.require.search(target)))


@dataclass
class Rules:
    """Compiled view of localisation.json."""

    version: str
    rewrites: list[tuple[re.Pattern, str]]
    number_map: dict[str, str | None]
    number_words: dict[str, str]
    allowed_added: frozenset[str]
    forbidden: list[tuple[str, re.Pattern]]
    discouraged: list[tuple[str, re.Pattern]]
    target_number_words: dict[str, list[str]] = field(default_factory=dict)
    ambiguous_number_words: dict[str, frozenset[str]] = field(default_factory=dict)
    tables: dict[str, list[dict[str, str]]] = field(default_factory=dict)
    number_format: dict[str, str] = field(default_factory=dict)
    # (pattern on the source, numbers the Vietnamese line may add): 'In May' -> 'tháng 5', 'infant' -> 'dưới 1 tuổi'
    tolerated_by_source: list[tuple[re.Pattern, frozenset[str]]] = field(default_factory=list)
    # (pattern on the source or None, pattern on the target, replacement): fixed wording after the final line is chosen
    target_replacements: list[tuple[re.Pattern | None, re.Pattern, str]] = field(default_factory=list)
    # meaning checks: units of a dose, direction of a limit, negation, wording reviewers returned
    units: list[tuple[str, re.Pattern, re.Pattern]] = field(default_factory=list)
    direction_checks: list[tuple[str, re.Pattern, str]] = field(default_factory=list)
    negation: tuple[int, re.Pattern, re.Pattern, re.Pattern] | None = None  # min words, source, target, exempt
    meaning_checks: list[MeaningCheck] = field(default_factory=list)

    @classmethod
    def load(cls, path: Path = DEFAULT_RULES) -> "Rules":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.from_dict(data)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Rules":
        return cls(
            version=data["version"],
            rewrites=[(re.compile(r["pattern"], re.I), r["replace"]) for r in data.get("source_rewrites", [])],
            number_map=dict(data.get("number_map", {})),
            number_words={k.casefold(): v for k, v in data.get("number_words", {}).items()},
            allowed_added=frozenset(data.get("allowed_added_numbers", [])),
            forbidden=[(r["label"], re.compile(r["pattern"])) for r in data.get("forbidden_in_target", [])],
            discouraged=[(r["label"], re.compile(r["pattern"], re.I)) for r in data.get("discouraged_in_target", [])],
            target_number_words={k: [w.casefold() for w in v] for k, v in data.get("target_number_words", {}).items()},
            ambiguous_number_words={w.casefold(): frozenset(x.casefold() for x in r.get("not_after", []))
                                    for w, r in data.get("ambiguous_target_number_words", {}).items()},
            tables={k: data.get(k, []) for k in ("service_table", "age_table", "terminology")},
            number_format={k: v for k, v in data.get("number_format", {}).items() if k in ("decimal", "thousands")},
            tolerated_by_source=[(re.compile(r["pattern"]), frozenset(r["numbers"]))
                                 for r in data.get("tolerated_by_source", [])],
            target_replacements=[(re.compile(r["source"], re.I) if r.get("source") else None,
                                  re.compile(r["pattern"], re.I), r["replace"])
                                 for r in data.get("target_replacements", [])],
            units=[(r["label"], re.compile(r["source"]), re.compile(r["target"])) for r in data.get("units", [])],
            direction_checks=[(r["label"], re.compile(r["source"]), r["target_opposite"])
                              for r in data.get("direction_checks", [])],
            negation=(int(data["negation"].get("min_words", 0)), re.compile(data["negation"]["source"]),
                      re.compile(data["negation"]["target"]),
                      re.compile(data["negation"].get("source_exempt") or r"(?!)")) if data.get("negation") else None,
            meaning_checks=[MeaningCheck.from_dict(r) for r in data.get("meaning_checks", [])],
        )

    # -- source side -------------------------------------------------------
    def rewrite_source(self, text: str) -> str:
        """UK-only services -> neutral English, so every MT engine sees clean English."""
        for pattern, replacement in self.rewrites:
            text = pattern.sub(lambda m, r=replacement: _keep_case(m, r), text)
        return text

    # -- target side -------------------------------------------------------
    def required_numbers(self, source: str) -> set[str]:
        """Numbers of the source line that the Vietnamese line must contain.

        Ordinals ('2nd') may be spelled out in Vietnamese, so they are not required;
        ``number_map`` renames (999 -> 115) or removes (111) localised numbers.
        """
        required: set[str] = set()
        for match in _NUMBER.finditer(_ORDINAL.sub(" ", source)):
            number = source_value(match.group(0))
            mapped = self.number_map.get(number, number)
            if mapped is not None:
                required.add(mapped)
        return required

    def tolerated_numbers(self, source: str) -> set[str]:
        """Numbers that may appear in the Vietnamese line without being a digit in the source."""
        words = [w.casefold() for w in _WORD.findall(source)]
        spelled = {self.number_words[w] for w in words if w in self.number_words}
        # 'seventy-fourth', 'twenty five': a tens word followed by a unit word is one number
        for tens, unit in zip(words, words[1:]):
            t, u = self.number_words.get(tens, ""), self.number_words.get(unit, "")
            if t.isdigit() and u.isdigit() and int(t) % 10 == 0 and 20 <= int(t) <= 90 and 1 <= int(u) <= 9:
                spelled.add(str(int(t) + int(u)))
        ordinals = {re.sub(r"\D", "", m.group(0)) for m in _ORDINAL.finditer(source)}
        by_source = set().union(*(numbers for pattern, numbers in self.tolerated_by_source if pattern.search(source)))
        return spelled | ordinals | by_source | set(self.allowed_added)

    def replace_target(self, source: str, target: str) -> str:
        """Fixed wording of ``target_replacements`` (a rule with ``source`` applies only when the source matches)."""
        for source_pattern, pattern, replacement in self.target_replacements:
            if source_pattern is None or source_pattern.search(source):
                target = pattern.sub(lambda m, r=replacement: (r[:1].upper() + r[1:]) if m.group(0)[:1].isupper()
                                     else r, target)
        return target

    def _number_words_in(self, target: str) -> set[str]:
        """Vietnamese words of the line that really stand for a number.

        An ambiguous word ('năm': five / year) counts only before another word and not after
        one of its ``not_after`` words: 'năm ngày' is 5 days, 'mỗi năm.' is every year.
        """
        tokens = re.findall(r"\w+|[^\w\s]", target.casefold())
        words = set()
        for i, token in enumerate(tokens):
            if token in self.ambiguous_number_words:
                after_word = i + 1 < len(tokens) and _LETTERS.fullmatch(tokens[i + 1]) is not None
                previous = tokens[i - 1] if i else ""
                if not after_word or previous in self.ambiguous_number_words[token]:
                    continue
            words.add(token)
        return words

    def check_numbers(self, source: str, target: str) -> tuple[list[str], list[str]]:
        """(missing, added) numbers of one aligned line, compared by value."""
        tokens = [m.group(0) for m in _NUMBER.finditer(target)]
        found = set().union(*(number_values(t) for t in tokens)) if tokens else set()
        required = self.required_numbers(source)
        words = self._number_words_in(target)
        spelled_out = {n for n in required - found if words & set(self.target_number_words.get(n, []))}
        # '5 100' in the source may be two numbers side by side: having both parts is enough
        parts = {source_value(t): {str(int(p)) for p in t.split(" ")}
                 for t in (m.group(0) for m in _NUMBER.finditer(source)) if " " in t}
        missing = sorted(n for n in required - found - spelled_out if not parts.get(n, {n}) <= found)
        expected = required | self.tolerated_numbers(source)
        # a number that is part of a name ('COVID-19', 'ICD-11') is not a value the translation added
        named = {m.group(0) for m in _NUMBER.finditer(target) if _NAME_BEFORE.search(target[:m.start()])}
        added = sorted({t for t in tokens if t not in named and not number_values(t) & expected})  # for the reviewer
        return missing, added

    def localise_numbers(self, source: str, target: str) -> str:
        """Write the numbers of a Vietnamese line the Vietnamese way, from the value they have in the source.

        Machine translation and the LLM often copy '1.5' and '1,500' from the English source: a
        Vietnamese reader takes '1,500 µg' for 1,5 µg. A token is read the English way when the source
        has the same token (the model copied it), otherwise by the one reading that is a source value;
        a token that matches no source value or two of them is left as written.
        """
        decimal, thousands = self.number_format.get("decimal"), self.number_format.get("thousands")
        if not decimal or thousands is None:
            return target
        source_tokens = {m.group(0) for m in _NUMBER.finditer(source)}
        wanted = {source_value(t) for t in source_tokens}

        def rewrite(match: re.Match) -> str:
            token = match.group(0)
            if not re.search(r"[.,]", token):
                return token
            readings = {source_value(token)} if token in source_tokens else number_values(token) & wanted
            if len(readings) != 1:
                return token
            groups = re.split(r"[.,]", token)
            if "." in readings.pop():
                return decimal.join(groups) if len(groups) == 2 else token
            if 1 <= len(groups[0]) <= 3 and all(len(g) == 3 for g in groups[1:]):
                return thousands.join(groups)
            return token

        return _NUMBER.sub(rewrite, target)

    def forbidden_in(self, target: str) -> list[str]:
        return [label for label, pattern in self.forbidden if pattern.search(target)]

    # -- meaning checks ----------------------------------------------------
    def missing_units(self, source: str, target: str) -> list[str]:
        """Units of the source line (mg, g, ml...) that the Vietnamese line lost: 5 mg -> 5 g changes a dose."""
        return [label for label, src, tgt in self.units if src.search(source) and not tgt.search(target)]

    def inverted_directions(self, source: str, target: str) -> list[str]:
        """Limits whose direction flipped: 'under 5' written 'trên 5', 'over 65' written 'dưới 65'."""
        numbers = {label: {next(g for g in m.groups() if g) for m in pattern.finditer(source)}
                   for label, pattern, _ in self.direction_checks}
        found = []
        for label, _, opposite in self.direction_checks:
            # 'aged 5 and over ... under 5': both directions of one number in a line cannot be told apart
            other = set().union(*(n for key, n in numbers.items() if key != label))
            for number in sorted(numbers[label] - other):
                if re.search(opposite.replace("{n}", re.escape(number)), target):
                    found.append(label.replace("N", number))
        return found

    def negation_lost(self, source: str, target: str) -> bool:
        """The source line says no / not / never / without and the Vietnamese line has no negation at all."""
        if self.negation is None:
            return False
        min_words, src, tgt, exempt = self.negation
        return len(source.split()) >= min_words and bool(src.search(exempt.sub(" ", source))) and not tgt.search(target)

    def meaning_problems(self, source: str, target: str) -> list[MeaningCheck]:
        """Wording rules (``meaning_checks``) the Vietnamese line breaks."""
        rewritten = self.rewrite_source(source)
        return [rule for rule in self.meaning_checks if rule.broken(source, rewritten, target)]

    def discouraged_in(self, target: str) -> list[str]:
        return [label for label, pattern in self.discouraged if pattern.search(target)]


# -- medicine names ---------------------------------------------------------

def load_medicine_names(root: Path, index_urls: Path, collection: Path) -> frozenset[str]:
    """Names from the crawled medicine index, read through the normalizer's own loader.

    Returns an empty set (the checks that need names are then skipped) when the
    normalizer or the crawl is not available, so the translator still runs alone.
    """
    normalize_dir = root / "tools" / "normalize"
    script = normalize_dir / "run.py"
    if not script.exists() or not Path(index_urls).exists():
        return frozenset()
    sys.path.insert(0, str(normalize_dir))
    try:
        spec = importlib.util.spec_from_file_location("_normalize_run", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)  # type: ignore[union-attr]
        return frozenset(module.load_medicine_names(Path(index_urls), Path(collection)))
    except Exception:  # noqa: BLE001 - an optional upstream tool must not stop a translation run
        return frozenset()
    finally:
        sys.path.remove(str(normalize_dir))


def compile_names(names: Iterable[str]) -> re.Pattern | None:
    ordered = sorted({n for n in names if n}, key=len, reverse=True)
    if not ordered:
        return None
    return re.compile(r"(?<![\w-])(" + "|".join(re.escape(n) for n in ordered) + r")(?![\w-])", re.I)


def find_names(text: str, pattern: re.Pattern | None) -> list[str]:
    """Medicine names of the index that occur in ``text`` (lower case, first-seen order)."""
    if pattern is None:
        return []
    seen: dict[str, None] = {}
    for match in pattern.finditer(text):
        seen.setdefault(match.group(1).casefold(), None)
    return list(seen)


def name_kept(name: str, target: str) -> bool:
    """True when the international name is still readable in the Vietnamese text.

    Vietnamese spelling habits are accepted: a dropped final 'e'
    (hydrocortisone -> hydrocortison) and 'X acid' written 'acid X' / 'axit X'.
    """
    low = target.casefold()
    name = name.casefold()
    if name in low:
        return True
    if name.endswith("e") and len(name) > 5 and name[:-1] in low:
        return True
    if name.endswith(" acid"):
        stem = name[: -len(" acid")]
        return any(f"{acid} {stem}" in low or f"{acid} {stem[:-1]}" in low for acid in ("acid", "axit"))
    return False
