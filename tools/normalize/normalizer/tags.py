"""Scope tags: flags that tell reviewers and the Safety Gate what a chunk contains.

Tags are computed on the final chunk text (after splitting and merging), never
inherited from a section. Python ``re`` treats Vietnamese letters as ``\\w``,
so ``\\b`` is a real word boundary for both languages (``bé`` does not match
``béo``). Input text must be NFC-normalised first.
"""
from __future__ import annotations

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

ORDER = ("emergency", "medication", "paediatric", "foreign_service", "vn_service")


def scope_tags(*texts: str) -> list[str]:
    """Return the tags that apply to the concatenation of ``texts``."""
    text = "\n".join(t for t in texts if t)
    found = {
        "emergency": bool(_EMERGENCY_RE.search(text)),
        "medication": bool(_MEDICATION_RE.search(text)),
        "paediatric": bool(_PAEDIATRIC_RE.search(text)),
        "foreign_service": bool(_FOREIGN_SERVICE_RE.search(text)),
        "vn_service": bool(_VN_SERVICE_RE.search(text)),
    }
    return [tag for tag in ORDER if found[tag]]
