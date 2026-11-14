"""Output Checker (M4/UC-06). Validates LLM responses before display to users.

Checks:
1. Prohibited diagnosis assertions (BR-09, NFR-10)
2. Prohibited drug prescribing / dosage / treatment-stop instructions (BR-10, NFR-10)
3. Fabricated sources (citations must match provided sources) (BR-07, BR-11)
4. Weakening or contradicting Safety Gate care direction (BR-04, NFR-10)
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Sequence

from .safety import CareLevel, SourceReference

SAFE_FALLBACK_VIOLATION = (
    "Nội dung trả lời đã bị chặn vì phát hiện thông tin không được phép "
    "(chẩn đoán bệnh, hướng dẫn dùng thuốc hoặc điều trị y khoa). "
    "Trợ lý AI chỉ cung cấp thông tin tham khảo. Vui lòng tham khảo ý kiến của bác sĩ "
    "hoặc nhân viên y tế để được thăm khám chính xác."
)

DEFAULT_DISCLAIMER = (
    "Lưu ý: Thông tin này chỉ mang tính tham khảo và hỗ trợ giáo dục sức khỏe chung, "
    "không thay thế cho chẩn đoán hoặc chỉ định điều trị của bác sĩ chuyên khoa."
)

# Regex patterns for prohibited medical content
_DIAGNOSIS_PATTERNS = [
    re.compile(r"\bchẩn đoán(?:\s+của bạn|\s+là|\s*:\s*)\b", re.IGNORECASE),
    re.compile(r"\b(?:kết luận|khẳng định|chắc chắn)\s+(?:rằng\s+)?(?:bạn|bệnh nhân)\s+(?:bị|mắc)\b", re.IGNORECASE),
    re.compile(r"\bbạn\s+(?:đang\s+)?(?:bị|mắc)\s+(?:chính xác\s+)?(?:bệnh|chứng)\s+[a-zà-ỹ0-9\s]+", re.IGNORECASE),
]

_PRESCRIPTION_PATTERNS = [
    re.compile(r"\b(?:hãy|nên|cần|phải)?\s*uống\s+\d+\s*(?:viên|mg|ml|gam|g)\b", re.IGNORECASE),
    re.compile(r"\bliều\s+(?:dùng|lượng)(?:\s*:\s*|\s+là\s+)\d+", re.IGNORECASE),
    re.compile(r"\bngừng\s+(?:dùng\s+|uống\s+)?thuốc\b", re.IGNORECASE),
    re.compile(r"\bkê\s+đơn(?:\s+thuốc)?\b", re.IGNORECASE),
    re.compile(r"\btiêm\s+\d+\s*(?:liều|ml|mg|đơn vị)\b", re.IGNORECASE),
]

_WEAKENING_PATTERNS = [
    re.compile(r"\bkhông\s+cần\s+(?:đi\s+)?(?:khám|gặp\s+bác\s+sĩ|đến\s+bệnh\s+viện)\b", re.IGNORECASE),
    re.compile(r"\bhoàn\s+toàn\s+bình\s+thường,\s*không\s+sao\s+đâu\b", re.IGNORECASE),
    re.compile(r"\bở\s+nhà\s+tự\s+khỏi\s+không\s+cần\s+bác\s+sĩ\b", re.IGNORECASE),
]

_URL_PATTERN = re.compile(r"https?://[^\s)\]>\"']+", re.IGNORECASE)


@dataclass
class CheckResult:
    passed: bool
    violations: list[str] = field(default_factory=list)
    safe_replacement: str | None = None
    sanitized_text: str | None = None


def check_output(
    text: str,
    care_level: CareLevel | None = None,
    provided_sources: Sequence[SourceReference] = (),
) -> CheckResult:
    """Validate generated answer.

    If violations are detected, returns passed=False with SAFE_FALLBACK_VIOLATION.
    If valid, returns passed=True and ensures the medical disclaimer is appended if missing.
    """
    if not text or not text.strip():
        return CheckResult(
            passed=False,
            violations=["EMPTY_OUTPUT"],
            safe_replacement=SAFE_FALLBACK_VIOLATION,
        )

    violations: list[str] = []

    # 1. Prohibited diagnosis
    for pattern in _DIAGNOSIS_PATTERNS:
        if pattern.search(text):
            violations.append("DIAGNOSIS_ASSERTION")
            break

    # 2. Prohibited drug / prescription / dosage
    for pattern in _PRESCRIPTION_PATTERNS:
        if pattern.search(text):
            violations.append("PRESCRIPTION_OR_DOSAGE")
            break

    # 3. Weakening of care level
    if care_level in ("EMERGENCY_NOW", "SEEK_CARE_SOON", "ROUTINE_CONSULT"):
        for pattern in _WEAKENING_PATTERNS:
            if pattern.search(text):
                violations.append("CARE_LEVEL_WEAKENED")
                break

    # 4. Fabricated sources (any URL mentioned must match approved sources)
    if provided_sources is not None:
        allowed_urls = {src.url.rstrip("/") for src in provided_sources}
        found_urls = _URL_PATTERN.findall(text)
        for url in found_urls:
            clean_url = url.rstrip("/")
            if clean_url not in allowed_urls:
                violations.append("FABRICATED_SOURCE")
                break

    if violations:
        return CheckResult(
            passed=False,
            violations=violations,
            safe_replacement=SAFE_FALLBACK_VIOLATION,
        )

    # Ensure disclaimer is included if not already present
    sanitized = text.strip()
    if "chỉ mang tính tham khảo" not in sanitized.lower():
        sanitized = f"{sanitized}\n\n---\n{DEFAULT_DISCLAIMER}"

    return CheckResult(
        passed=True,
        violations=[],
        sanitized_text=sanitized,
    )
