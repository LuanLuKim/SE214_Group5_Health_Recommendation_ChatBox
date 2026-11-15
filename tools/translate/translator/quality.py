"""Quality gate for translated medical text.

Source and translation are aligned line by line, so most checks run per line
and point at the exact line a reviewer has to look at.

Hard checks (the chunk fails the gate):
  non_empty, line_count, bullet_count, length_ratio, min_words   structure
  numbers_preserved   every dose, age, duration, temperature of the source line is in the Vietnamese line
  no_uk_services      999 / 111 / GP / A&E must have been localised
  no_artifacts        model control tokens, placeholders, broken characters
  no_untranslated     a line left in English
  no_repetition       a generation loop: a short phrase said back to back, or a long phrase
                      repeated until it fills most of the line

Soft checks (the chunk passes but is flagged for a human reviewer):
  numbers_added, uk_reference_left, medicine_name_changed, line_length, low_support
  (``low_support``: no machine draft resembles the final line: possible invention).

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
import re
from typing import Any

from .consensus import support
from .glossary import Rules, name_kept
from .segments import split_marker

_ARTIFACT = re.compile(r"<\|[^|>]*\|>|«\s*DRUG_\d+\s*»|�|<unk>|</?s>", re.I)
_LOOP_WORD = re.compile(r"[^\W_]+(?:[.,'’-][^\W_]+)*")
_VIETNAMESE = re.compile(r"[ăâđêôơưàảãáạằẳẵắặầẩẫấậèẻẽéẹềểễếệìỉĩíịòỏõóọồổỗốộờởỡớợùủũúụừửữứựỳỷỹýỵ]", re.I)

HARD_CHECKS = ("non_empty", "line_count", "bullet_count", "length_ratio", "min_words",
               "numbers_preserved", "no_uk_services", "no_artifacts", "no_untranslated", "no_repetition")

DEFAULTS: dict[str, Any] = {
    "min_length_ratio": 0.3, "max_length_ratio": 3.0, "min_words": 3,
    "line_min_length_ratio": 0.25, "line_max_length_ratio": 4.0, "line_ratio_min_chars": 25,
    "untranslated_min_words": 4, "repeat_ngram": 4, "repeat_times": 4,
    "repeat_long_ngram": 8, "repeat_long_times": 3, "repeat_long_coverage": 0.5,
    "low_agreement_chrf": 0.25, "always_review_tags": ["emergency"],
}


@dataclass
class Issue:
    check: str
    hard: bool
    detail: str
    line: int | None = None  # 0-based index of the line, None for a whole-chunk issue

    def to_dict(self) -> dict[str, Any]:
        return {"check": self.check, "hard": self.hard, "line": self.line, "detail": self.detail}


@dataclass
class QualityReport:
    """Quality assessment for a single translated chunk."""
    chunk_id: str
    passed: bool
    checks: dict[str, bool] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
    needs_human_review: bool = False

    @property
    def status(self) -> str:
        if self.passed:
            return "auto_verified" if not self.warnings else "auto_verified_with_warnings"
        return "failed_quality_gate"

    def to_dict(self) -> dict[str, Any]:
        return {
            "chunk_id": self.chunk_id,
            "passed": self.passed,
            "status": self.status,
            "needs_human_review": self.needs_human_review,
            "checks": self.checks,
            "warnings": self.warnings,
            "issues": [issue.to_dict() for issue in self.issues],
        }


def count_bullets(text: str) -> int:
    """Number of list items (lines that start with a bullet or a number marker)."""
    return sum(1 for line in text.split("\n") if split_marker(line)[0])


def _loop_words(text: str) -> list[str]:
    """Words for loop detection: punctuation and "/" separate words, "vi-rút" or "3,6" stay one."""
    return _LOOP_WORD.findall(text.casefold())


def _tandem_repeat(words: list[str], n: int, times: int) -> bool:
    """A phrase of at most ``n`` words said ``times`` times back to back ("chanh chanh chanh chanh")."""
    for period in range(1, n + 1):
        if period * times < n:
            continue
        need, run = period * (times - 1), 0
        for i in range(len(words) - period):
            run = run + 1 if words[i] == words[i + period] else 0
            if run >= need:
                return True
    return False


def _long_repeat(words: list[str], n: int, times: int, min_coverage: float) -> bool:
    """A longer phrase (``n`` words) said ``times`` times, with repeated phrases covering most of the line.

    The coverage condition separates a loop (a sentence or a "/" list said over and over) from a
    Vietnamese term that a long paragraph naturally repeats ("các nước thu nhập thấp và trung bình").
    """
    if len(words) < n * times:
        return False
    grams = [tuple(words[i:i + n]) for i in range(len(words) - n + 1)]
    counts = Counter(grams)
    if max(counts.values()) < times:
        return False
    covered = [False] * len(words)
    for i, gram in enumerate(grams):
        if counts[gram] >= 2:
            covered[i:i + n] = [True] * n
    return sum(covered) / len(words) >= min_coverage


def _has_loop(text: str, cfg: dict[str, Any]) -> bool:
    words = _loop_words(text)
    return _tandem_repeat(words, cfg["repeat_ngram"], cfg["repeat_times"]) or _long_repeat(
        words, cfg["repeat_long_ngram"], cfg["repeat_long_times"], cfg["repeat_long_coverage"])


def check_line(source: str, target: str, rules: Rules, settings: dict[str, Any] | None = None, *,
               drafts: list[str] | None = None, index: int | None = None) -> list[Issue]:
    """Issues of one aligned line (bodies without the bullet marker)."""
    cfg = {**DEFAULTS, **(settings or {})}
    issues: list[Issue] = []
    if not source.strip():
        return issues
    if not target.strip():
        return [Issue("non_empty", True, "line is empty", index)]
    missing, added = rules.check_numbers(source, target)
    if missing:
        issues.append(Issue("numbers_preserved", True, f"missing number(s): {', '.join(missing)}", index))
    if added:
        issues.append(Issue("numbers_added", False, f"number(s) not in the source: {', '.join(added)}", index))
    forbidden = rules.forbidden_in(target)
    if forbidden:
        issues.append(Issue("no_uk_services", True, f"UK service left: {', '.join(forbidden)}", index))
    discouraged = rules.discouraged_in(target)
    if discouraged:
        issues.append(Issue("uk_reference_left", False, f"check wording: {', '.join(discouraged)}", index))
    if _ARTIFACT.search(target):
        issues.append(Issue("no_artifacts", True, "model token or broken character in the text", index))
    words = len(source.split())
    flat = " ".join(target.casefold().split())
    english_inputs = {" ".join(text.casefold().split()) for text in (source, rules.rewrite_source(source))}
    if words >= cfg["untranslated_min_words"] and not _VIETNAMESE.search(target) and flat in english_inputs:
        issues.append(Issue("no_untranslated", True, "line is still English", index))
    if _has_loop(target, cfg) and not _has_loop(source, cfg):
        issues.append(Issue("no_repetition", True, "repeated phrase (generation loop)", index))
    if len(source) >= cfg["line_ratio_min_chars"]:
        ratio = len(target) / len(source)
        if not cfg["line_min_length_ratio"] <= ratio <= cfg["line_max_length_ratio"]:
            issues.append(Issue("line_length", False, f"line length ratio {ratio:.2f}", index))
        if drafts and support(target, drafts) < cfg["low_agreement_chrf"]:
            issues.append(Issue("low_support", False, "no machine draft resembles this line", index))
    return issues


def check_quality(chunk_id: str, source_text: str, translated_text: str, *, rules: Rules | None = None,
                  settings: dict[str, Any] | None = None, drafts: list[list[str]] | None = None,
                  names: list[str] | None = None, tags: list[str] | None = None,
                  min_length_ratio: float | None = None, max_length_ratio: float | None = None,
                  bullet_tolerance: int = 0) -> QualityReport:
    """Run the gate on one chunk.

    ``drafts[i]`` are the machine drafts of line ``i`` (for ``low_support``);
    ``names`` are medicine names of the source that the drafts kept unchanged,
    so the final text is expected to keep them too; ``tags`` are the chunk's
    scope tags (some always require a human reviewer).
    """
    cfg = {**DEFAULTS, **(settings or {})}
    if min_length_ratio is not None:
        cfg["min_length_ratio"] = min_length_ratio
    if max_length_ratio is not None:
        cfg["max_length_ratio"] = max_length_ratio
    rules = rules or Rules.load()
    issues: list[Issue] = []
    checks = {name: True for name in HARD_CHECKS}

    if not translated_text.strip():
        issues.append(Issue("non_empty", True, "translation is empty"))
    ratio = len(translated_text) / max(len(source_text), 1)
    if not cfg["min_length_ratio"] <= ratio <= cfg["max_length_ratio"]:
        issues.append(Issue("length_ratio", True,
                            f"length ratio {ratio:.2f} outside [{cfg['min_length_ratio']}, {cfg['max_length_ratio']}]"))
    if len(translated_text.split()) < cfg["min_words"] <= len(source_text.split()):
        issues.append(Issue("min_words", True, f"translation has only {len(translated_text.split())} words"))
    src_bullets, tgt_bullets = count_bullets(source_text), count_bullets(translated_text)
    if abs(src_bullets - tgt_bullets) > bullet_tolerance:
        issues.append(Issue("bullet_count", True, f"bullets: source={src_bullets}, translated={tgt_bullets}"))

    src_lines, tgt_lines = source_text.split("\n"), translated_text.split("\n")
    if len(src_lines) != len(tgt_lines):
        issues.append(Issue("line_count", True, f"lines: source={len(src_lines)}, translated={len(tgt_lines)}"))
        issues += check_line(source_text.replace("\n", " "), translated_text.replace("\n", " "), rules, cfg)
    else:
        for i, (src, tgt) in enumerate(zip(src_lines, tgt_lines)):
            line_drafts = drafts[i] if drafts and i < len(drafts) else None
            issues += check_line(split_marker(src)[1], split_marker(tgt)[1], rules, cfg, drafts=line_drafts, index=i)

    changed = [name for name in (names or []) if not name_kept(name, translated_text)]
    if changed:
        issues.append(Issue("medicine_name_changed", False, f"international name not found: {', '.join(changed)}"))

    for issue in issues:
        if issue.hard:
            checks[issue.check] = False
    warnings = []
    for issue in issues:
        where = "" if issue.line is None else f"line {issue.line + 1}: "
        warnings.append(f"{issue.check}: {where}{issue.detail}")
    passed = all(checks.values())
    review = (not passed) or bool(warnings) or bool(set(tags or []) & set(cfg["always_review_tags"]))
    return QualityReport(chunk_id=chunk_id, passed=passed, checks=checks, warnings=warnings, issues=issues,
                         needs_human_review=review)
