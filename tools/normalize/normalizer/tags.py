"""Scope tags: flags that tell reviewers and the Safety Gate what a chunk contains.

Tags are computed on the final chunk text (after splitting and merging), never
inherited from a section. Python ``re`` treats Vietnamese letters as ``\\w``,
so ``\\b`` is a real word boundary for both languages (``bé`` does not match
``béo``). Input text must be NFC-normalised first.
"""
from __future__ import annotations

from functools import lru_cache
import re

_MEDICATION_RE = re.compile(
    r"antihistamin|corticosteroid|steroid|adrenaline|epipen|jext|"
    r"menthol|emollient|moisturis|paracetamol|ibuprofen|antibiot|aspirin|"
    r"\bmedicines?\b|\bmedication\b|\bdose\b|\btablets?\b|"
    # "thuốc" (medicine) but not "thuốc lá" / "hút thuốc" (tobacco)
    r"(?<!hút )\bthuốc\b(?! lá)|kháng sinh|hạ sốt|truyền dịch",
    re.I,
)
_PAEDIATRIC_RE = re.compile(
    r"\b(?:child|children|baby|babies|toddlers?|infants?|neonates?|newborns?|pediatric|paediatric)\b|"
    r"\btrẻ em\b|\btrẻ sơ sinh\b|\btrẻ nhỏ\b|\btrẻ mới sinh\b|\bem bé\b|\bbé\b|"
    r"\b\d+\s*(?:ngày tuổi|tháng tuổi)\b|"
    r"\bdưới \d+\s*(?:ngày|tháng|tuổi)\b|\bbú mẹ\b|\bbú sữa\b",
    re.I,
)
_FOREIGN_SERVICE_RE = re.compile(r"\b(?:999|111|GP|GPs|NHS)\b|\bA&E\b")
_VN_SERVICE_RE = re.compile(r"\b115\b|\btrạm y tế\b|\bbệnh viện tuyến\b", re.I)
_EMERGENCY_RE = re.compile(
    r"immediate action required|\bcall 999\b|\b999\b|\bgo to (?:your nearest |an? )?A&E\b|\bA&E\b|"
    r"\bambulance\b|life[- ]threatening|medical emergency|\bunconscious\b|"
    r"\bcấp cứu\b|\bkhẩn cấp\b|\bnguy kịch\b|dấu hiệu nguy hiểm|dấu hiệu cảnh báo|\bgọi 115\b",
    re.I,
)

# Food, drink and supplements that sources warn about when someone takes a
# medicine. A substance alone is nutrition ("milk is rich in calcium"); it counts
# only next to a medicine context (see _MEDICINE_CONTEXT_RE). Keys are stable
# identifiers for the Safety Gate and reviewers; order is the output order.
SUBSTANCES: dict[str, re.Pattern[str]] = {key: re.compile(pattern, re.I) for key, pattern in (
    ("alcohol", r"\balcohol(?:ic)?\b|\bbeer\b|\bwine\b|\brượu\b|\bbia\b|đồ uống có cồn"),
    ("grapefruit", r"\bgrapefruits?\b|\bbưởi\b"),
    ("dairy", r"(?<!breast )\b(?:milk|dairy|yog(?:h)?urts?|cheese)\b(?!\s+(?:allerg|intoleran))|\bsữa\b(?! mẹ)(?! công thức)"),
    ("calcium", r"\bcalcium\b|\bcanxi\b"),
    ("iron", r"\biron\b|\bsắt\b"),
    ("vitamin_k", r"\bvitamin K\b"),
    ("caffeine", r"\bcaffeine\b|\bcoffee\b|\btea\b|\bcola\b|\benergy drinks?\b|\bcà phê\b|\btrà\b"),
    ("tyramine", r"\btyramine\b"),
    ("potassium", r"\bpotassium\b|\bsalt substitutes?\b|\bkali\b"),
    ("liquorice", r"\bliquorice\b|\blicorice\b|\bcam thảo\b"),
    ("cranberry", r"\bcranberr(?:y|ies)\b|\bnam việt quất\b"),
    ("soya", r"\b(?:soya?|soy)\b(?!\s+allerg)|\bđậu nành\b"),
    ("herbal", r"\bSt\.? John'?s wort\b|\bherbal (?:remed(?:y|ies)|medicines?|supplements?)\b|\bthảo dược\b"),
    ("supplements", r"\b(?:vitamin|mineral|dietary|food) supplements?\b|\bthực phẩm (?:chức năng|bổ sung)\b"),
)}
# Generic words only. Names of medicines are not written here: they come from
# crawled index pages (normalizer.vocabulary) and are passed in as
# ``medicine_names``, so "Grapefruit juice raises the level of simvastatin"
# counts because the source lists simvastatin.
# Who prescribes or advises (GP, pharmacist, "may prescribe") is not a medicine:
# "your GP may prescribe a calcium supplement" or "a pharmacist can help you
# choose one with calcium" is nutrition advice about the substance itself
# (lifestyle content), not a food-medicine interaction.
_GENERIC_MEDICINE_RE = re.compile(
    r"\bmedicines?\b|\bmedication\b|\binteract|"
    r"(?<!hút )\bthuốc\b(?! lá)|\bkháng sinh\b|\btương tác\b",
    re.I,
)


@lru_cache(maxsize=8)
def _names_re(names: frozenset[str]) -> re.Pattern[str] | None:
    if not names:
        return None
    # Singular and plural of each name: the index says "Antihistamines", a page "an antihistamine".
    stems = {n[:-1] if n.endswith("s") and not n.endswith("ss") else n for n in names}
    alternatives = "|".join(re.escape(n) for n in sorted(stems, key=len, reverse=True))
    return re.compile(rf"(?<!\w)(?:{alternatives})s?(?!\w)", re.I)

ORDER = ("emergency", "medication", "food_interaction", "paediatric", "foreign_service", "vn_service")


def _units(text: str) -> list[str]:
    """Paragraphs with the bullet lines that follow them: a lead-in such as
    "Do not take it with:" and its "• milk" items are read together."""
    units: list[str] = []
    for line in text.splitlines():
        if line.lstrip().startswith(("•", "-", "*")) and units:
            units[-1] += "\n" + line
        elif line.strip():
            units.append(line)
    return units


def _substances(line: str) -> set[str]:
    return {key for key, pattern in SUBSTANCES.items() if pattern.search(line)}


def _is_medicine(line: str, names: frozenset[str]) -> bool:
    """Medicine context outside the substance words ("herbal medicine" is not one)."""
    for pattern in SUBSTANCES.values():
        line = pattern.sub(" ", line)
    named = _names_re(names)
    return bool(_GENERIC_MEDICINE_RE.search(line) or (named and named.search(line)))


# Words that link a substance to a medicine inside one sentence: timing, "with",
# effect on the medicine, advice to avoid. Generic phrases only, no drug names.
_RELATION_RE = re.compile(
    r"\bwith\b|\bat the same time\b|\btogether\b|\bgap\b|\bhours? (?:before|after)\b|\bbefore or after\b|"
    r"\bwhile (?:you(?:'re| are) )?(?:taking|on|using)\b|\bwhen (?:you(?:'re| are)? )?(?:tak(?:e|ing)|on)\b|"
    r"\baffect|\bwork(?:ing)? properly\b|\bstop(?:s)? (?:it|the medicine|\w+) (?:from )?working\b|\babsor|"
    r"\binteract|\b(?:raise|increase|lower|reduce|boost)s? (?:the )?(?:level|amount|effect|risk|chance)|"
    r"\bavoid\b|\bdo not\b|\bdon't\b|\bshould not\b|\bcut down\b|\blimit\b|"
    r"\bkhi\b|\bcùng\b|\bcách xa\b|\btrước\b|\bsau khi\b|\btránh\b|\bkhông (?:nên )?(?:uống|ăn|dùng)\b|"
    r"\btương tác\b|\blàm (?:giảm|tăng)\b|\bảnh hưởng\b",
    re.I,
)
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def candidate_id(header: str, sentence: str) -> str:
    """Stable key of one sentence in its section (the header carries the subject)."""
    from hashlib import sha256
    return sha256(f"{header}\n{sentence}".encode("utf-8")).hexdigest()[:20]


def interaction_candidates(text: str, *, medicine_names: frozenset[str] = frozenset(),
                           medicine_page: bool = False) -> list[dict]:
    """Sentences that name a substance next to a medicine, with the rule's verdict.

    A candidate needs a substance in the sentence and a medicine in the same
    paragraph (for a bullet: the bullet or its lead-in line, never a sibling
    bullet), in the header (``Title › heading``, e.g. "Taking X with other
    medicines"), or a medicine page (``medicine_page``: the page's topic). The
    rule confirms it (``rule = True``) only when the sentence itself also links
    the two: a medicine in the sentence/header/page and a relation phrase.
    Otherwise it is a *mention* ("Amlodipine is a calcium-channel blocker").
    """
    units = _units(text)
    if len(units) < 2:  # a lone sentence has no header
        units = ["", *units]
    header = units[0]
    header_is_medicine = bool(header) and _is_medicine(header, medicine_names)
    found: list[dict] = []
    for unit in units[1:]:
        lead, *bullets = unit.split("\n")
        if " › " in lead and not bullets:
            # a merged section's own heading line ("Title › heading"): context for what follows, not a claim
            header = lead
            header_is_medicine = _is_medicine(header, medicine_names)
            continue
        lead_is_medicine = _is_medicine(lead, medicine_names)
        sentences = [(s, lead_is_medicine) for s in _SENTENCE_END.split(lead)]
        for bullet in bullets:
            item = bullet.lstrip("•-* ").strip()
            # a bullet reads with its whole lead-in paragraph: "Some medicines can stop X
            # working. These include: St John's wort" (the last lead sentence alone loses the claim)
            sentences.append((f"{lead} {item}", lead_is_medicine or _is_medicine(item, medicine_names)))
        for sentence, paragraph_medicine in sentences:
            terms = [key for key in SUBSTANCES if key in _substances(sentence)]
            if not terms or not (medicine_page or header_is_medicine or paragraph_medicine):
                continue
            linked = medicine_page or header_is_medicine or _is_medicine(sentence, medicine_names)
            found.append({"id": candidate_id(header, sentence), "header": header, "sentence": sentence,
                          "terms": terms, "rule": bool(linked and _RELATION_RE.search(sentence))})
    return found


def _verdict_terms(cand: dict, verdict: dict) -> list[str]:
    if not verdict.get("interaction"):
        return []
    picked = [t for t in cand["terms"] if t in (verdict.get("terms") or cand["terms"])]
    return picked or list(cand["terms"])


def resolve(cand: dict, verdict: dict | None) -> dict:
    """Decide one candidate from the rule, the LLM verdict and any later decisions.

    Precedence: a human decision (``verdict["human"]``) always wins; without an
    LLM verdict the rule decides; when the LLM agrees with the rule its terms
    are used. When they disagree it is a *conflict*: an adjudication
    (``verdict["resolution"]``, a second LLM pass that sees both sides) decides
    it; an unresolved conflict is kept as an interaction (safety first: the
    Safety Gate may route a question to a pharmacist it did not need, never the
    reverse) and marked ``needs_review`` for a person.
    """
    rule_terms = list(cand["terms"]) if cand["rule"] else []
    if verdict and verdict.get("human") is not None:
        return {"terms": _verdict_terms(cand, verdict["human"]), "by": "human", "conflict": False, "needs_review": False}
    if not verdict or "interaction" not in verdict:
        return {"terms": rule_terms, "by": "rule", "conflict": False, "needs_review": False}
    llm_terms = _verdict_terms(cand, verdict)
    model = verdict.get("model", "llm")
    if bool(llm_terms) == bool(rule_terms):
        return {"terms": llm_terms, "by": model, "conflict": False, "needs_review": False}
    resolution = verdict.get("resolution")
    if resolution and "interaction" in resolution:
        return {"terms": _verdict_terms(cand, resolution), "by": resolution.get("model", model) + ":resolved",
                "conflict": True, "needs_review": False}
    union = [t for t in cand["terms"] if t in set(rule_terms) | set(llm_terms)]
    return {"terms": union, "by": "conflict:safety", "conflict": True, "needs_review": True}


def interaction_review(text: str, *, medicine_names: frozenset[str] = frozenset(), medicine_page: bool = False,
                       verdicts: dict | None = None) -> dict:
    """Confirmed interaction terms, mere mentions, and the evidence for each.

    ``verdicts`` (``{candidate_id: {"interaction": bool, "terms": [...], "model": ...}}``)
    come from the offline review (``review_interactions.py``) and override the
    rule for the sentences they cover; the normalizer only reads them as data.
    """
    confirmed: set[str] = set()
    mentioned: set[str] = set()
    evidence: list[dict] = []
    for cand in interaction_candidates(text, medicine_names=medicine_names, medicine_page=medicine_page):
        decision = resolve(cand, (verdicts or {}).get(cand["id"]))
        terms = decision["terms"]
        confirmed |= set(terms)
        mentioned |= set(cand["terms"]) - set(terms)
        evidence.append({"candidate_id": cand["id"], "sentence": cand["sentence"], "terms": cand["terms"],
                         "interaction": bool(terms), **{k: v for k, v in decision.items() if k != "terms"}})
    return {"terms": [k for k in SUBSTANCES if k in confirmed],
            "mentions": [k for k in SUBSTANCES if k in mentioned - confirmed],
            "evidence": evidence}


def interaction_terms(text: str, *, medicine_names: frozenset[str] = frozenset(),
                      medicine_page: bool = False, verdicts: dict | None = None) -> list[str]:
    """Substances confirmed as interacting with a medicine (see ``interaction_review``)."""
    return interaction_review(text, medicine_names=medicine_names, medicine_page=medicine_page, verdicts=verdicts)["terms"]


def scope_tags(*texts: str, medicine_names: frozenset[str] = frozenset(), medicine_page: bool = False,
               verdicts: dict | None = None) -> list[str]:
    """Return the tags that apply to the concatenation of ``texts``."""
    text = "\n".join(t for t in texts if t)
    found = {
        "emergency": bool(_EMERGENCY_RE.search(text)),
        "medication": bool(_MEDICATION_RE.search(text)),
        "food_interaction": bool(interaction_terms(text, medicine_names=medicine_names, medicine_page=medicine_page,
                                                   verdicts=verdicts)),
        "paediatric": bool(_PAEDIATRIC_RE.search(text)),
        "foreign_service": bool(_FOREIGN_SERVICE_RE.search(text)),
        "vn_service": bool(_VN_SERVICE_RE.search(text)),
    }
    return [tag for tag in ORDER if found[tag]]


# Quality flags an offline reviewer (LLM or person) may raise on a chunk. They
# never change the text; they tell the content expert (UC-17) where to look.
REVIEW_FLAGS = ("ui_leftover", "not_standalone", "out_of_scope", "unsafe_or_wrong")


def resolve_tags(rule_tags: list[str], verdict: dict | None) -> dict:
    """Final tag set from the keyword rules and an offline chunk review.

    ``verdict`` (from ``review_chunks.py``): ``{"tags": [...], "flags": [...],
    "model": ..., "resolution": {tag: bool}, "human": {"tags": [...]}}``.
    Per tag, with the same precedence as ``resolve``: a human list wins; with no
    review the rule decides; agreement keeps it; a disagreement is decided by the
    resolution pass (a second LLM call that sees both sides); an unresolved one
    keeps the tag (every tag is a caution for the Safety Gate, so keeping it is
    the safe side) and is listed in ``needs_review``.
    """
    rule = [t for t in ORDER if t in rule_tags]
    if verdict and verdict.get("human") is not None:
        tags = [t for t in ORDER if t in verdict["human"].get("tags", [])]
        return {"tags": tags, "by": "human", "conflicts": [t for t in ORDER if (t in rule) != (t in tags)],
                "needs_review": [], "flags": [f for f in verdict["human"].get("flags", []) if f in REVIEW_FLAGS]}
    if not verdict or "tags" not in verdict:
        return {"tags": rule, "by": "rule", "conflicts": [], "needs_review": [], "flags": []}
    llm = set(verdict["tags"])
    resolution = verdict.get("resolution") or {}
    tags, conflicts, open_ = [], [], []
    for tag in ORDER:
        if (tag in rule) == (tag in llm):
            keep = tag in rule
        else:
            conflicts.append(tag)
            if isinstance(resolution.get(tag), bool):
                keep = resolution[tag]
            else:
                keep = True
                open_.append(tag)
        if keep:
            tags.append(tag)
    return {"tags": tags, "by": verdict.get("model", "llm"), "conflicts": conflicts, "needs_review": open_,
            "flags": [f for f in verdict.get("flags", []) if f in REVIEW_FLAGS]}
