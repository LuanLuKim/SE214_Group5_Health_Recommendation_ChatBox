"""Agreement between machine translations (no model, no dependency).

``chrf`` is the character n-gram F-score (n = 1..6, beta = 2) used by chrF.
``pick`` chooses, among the engines' versions of one line, the one closest to
all the others (minimum Bayes risk): an engine that invents or drops content
disagrees with the rest and loses. The same score, measured between the final
text and the machine drafts, flags a final line that no draft supports.
"""
from __future__ import annotations

from collections import Counter

_ORDER = 6
_BETA = 2.0


def _ngrams(text: str, n: int) -> Counter:
    compact = "".join(text.casefold().split())
    return Counter(compact[i:i + n] for i in range(len(compact) - n + 1))


def chrf(hypothesis: str, reference: str) -> float:
    """0..1; 1.0 for identical strings, 0.0 when either is empty."""
    if not hypothesis.strip() or not reference.strip():
        return 0.0
    if hypothesis == reference:
        return 1.0
    precisions, recalls = [], []
    for n in range(1, _ORDER + 1):
        hyp, ref = _ngrams(hypothesis, n), _ngrams(reference, n)
        if not hyp or not ref:
            continue
        overlap = sum((hyp & ref).values())
        precisions.append(overlap / sum(hyp.values()))
        recalls.append(overlap / sum(ref.values()))
    if not precisions:
        return 0.0
    precision = sum(precisions) / len(precisions)
    recall = sum(recalls) / len(recalls)
    if precision + recall == 0:
        return 0.0
    beta2 = _BETA ** 2
    return (1 + beta2) * precision * recall / (beta2 * precision + recall)


def agreement(text: str, others: list[str]) -> float:
    """Mean symmetric chrF of ``text`` against the other versions (1.0 when there is nothing to compare)."""
    others = [o for o in others if o.strip()]
    if not others:
        return 1.0
    return sum((chrf(text, o) + chrf(o, text)) / 2 for o in others) / len(others)


def support(text: str, drafts: list[str]) -> float:
    """How well the best machine draft supports ``text`` (0..1)."""
    drafts = [d for d in drafts if d.strip()]
    if not drafts:
        return 1.0
    return max((chrf(text, d) + chrf(d, text)) / 2 for d in drafts)


def pick(candidates: dict[str, str], valid: dict[str, bool] | None = None) -> tuple[str, dict[str, float]]:
    """Name of the consensus candidate and every candidate's agreement score.

    ``candidates`` keeps the engines' priority order (first wins ties).
    Candidates marked invalid (empty, lost a number, forbidden term) are only
    chosen when no valid one exists.
    """
    names = list(candidates)
    scores = {name: agreement(candidates[name], [candidates[o] for o in names if o != name]) for name in names}
    usable = [n for n in names if candidates[n].strip() and (valid is None or valid.get(n, True))]
    pool = usable or [n for n in names if candidates[n].strip()] or names
    best = max(pool, key=lambda n: (scores[n], -names.index(n)))
    return best, scores
