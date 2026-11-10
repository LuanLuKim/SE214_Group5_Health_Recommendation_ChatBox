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
    ("dairy", r"\b(?:milk|dairy|yog(?:h)?urts?|cheese)\b(?!\s+(?:allerg|intoleran))|\bsữa\b(?! mẹ)(?! công thức)"),
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
_GENERIC_MEDICINE_RE = re.compile(
    r"\bmedicines?\b|\bmedication\b|\bprescri|\bpharmacist\b|\binteract|"
    r"(?<!hút )\bthuốc\b(?! lá)|\bkháng sinh\b|\btương tác\b|\bdược sĩ\b",
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


def interaction_terms(text: str, *, medicine_names: frozenset[str] = frozenset(),
                      medicine_page: bool = False) -> list[str]:
    """Substances mentioned next to a medicine.

    "Next to" means the same paragraph; for a bullet, the bullet itself or the
    lead-in line it belongs to ("Do not take it with:"), never a sibling bullet,
    so a list of allergens that names both milk and medicines does not count.
    On a medicine page (``medicine_page``: the page's topic says so) every
    paragraph is about that medicine. The first line of a multi-line text is the
    chunk header (``Title › heading``) and is not scanned itself.
    """
    units = _units(text)
    if len(units) < 2:  # a lone sentence has no header
        units = ["", *units]
    found: set[str] = set()
    for unit in units[1:]:
        lead, *bullets = unit.split("\n")
        lead_is_medicine = medicine_page or _is_medicine(lead, medicine_names)
        if lead_is_medicine:
            found |= _substances(lead)
        for bullet in bullets:
            if lead_is_medicine or _is_medicine(bullet, medicine_names):
                found |= _substances(bullet)
    return [key for key in SUBSTANCES if key in found]


def scope_tags(*texts: str, medicine_names: frozenset[str] = frozenset(), medicine_page: bool = False) -> list[str]:
    """Return the tags that apply to the concatenation of ``texts``."""
    text = "\n".join(t for t in texts if t)
    found = {
        "emergency": bool(_EMERGENCY_RE.search(text)),
        "medication": bool(_MEDICATION_RE.search(text)),
        "food_interaction": bool(interaction_terms(text, medicine_names=medicine_names, medicine_page=medicine_page)),
        "paediatric": bool(_PAEDIATRIC_RE.search(text)),
        "foreign_service": bool(_FOREIGN_SERVICE_RE.search(text)),
        "vn_service": bool(_VN_SERVICE_RE.search(text)),
    }
    return [tag for tag in ORDER if found[tag]]
