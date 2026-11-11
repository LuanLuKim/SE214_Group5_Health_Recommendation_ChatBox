"""Safety Gate (M1, UC-07). Care direction is chosen by reviewed rules, never by an LLM."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

CareLevel = Literal[
    "EMERGENCY_NOW",
    "SEEK_CARE_SOON",
    "ROUTINE_CONSULT",
    "GENERAL_SELF_CARE_INFO",
    "INSUFFICIENT_INFO",
]
CARE_LEVELS: tuple[str, ...] = CareLevel.__args__  # type: ignore[attr-defined]


class SourceReference(BaseModel):
    id: str
    name: str
    url: str
    verifiedAt: str


class SafetyDecision(BaseModel):
    status: Literal["EVALUATED", "UNAVAILABLE"]
    careLevel: CareLevel | None
    matchedRuleIds: list[str]
    missingFields: list[str]
    specialtyIds: list[str]
    sources: list[SourceReference]


def unavailable() -> SafetyDecision:
    return SafetyDecision(
        status="UNAVAILABLE",
        careLevel=None,
        matchedRuleIds=[],
        missingFields=[],
        specialtyIds=[],
        sources=[],
    )


def evaluate(text: str) -> SafetyDecision:
    """No medical rules have been supplied or approved. This MUST NOT return low risk."""
    return unavailable()
