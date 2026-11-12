"""Safety Gate (M1, UC-07). Care direction is chosen by reviewed rules, never by an LLM."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

CareLevel = Literal[
    "EMERGENCY_NOW",
    "SEEK_CARE_SOON",
    "ROUTINE_CONSULT",
    "GENERAL_SELF_CARE_INFO",
    "INSUFFICIENT_INFO",
]
CARE_LEVELS: tuple[str, ...] = CareLevel.__args__  # type: ignore[attr-defined]


class SourceReference(BaseModel):
    id: str = Field(min_length=1, pattern=r"\S")
    name: str = Field(min_length=1, pattern=r"\S")
    url: str = Field(min_length=1, pattern=r"^https?://")
    verifiedAt: str = Field(min_length=1, pattern=r"\S")


class SafetyDecision(BaseModel):
    status: Literal["EVALUATED", "UNAVAILABLE"]
    careLevel: CareLevel | None
    matchedRuleIds: list[str]
    missingFields: list[str]
    specialtyIds: list[str]
    sources: list[SourceReference]


class Conditions(BaseModel):
    all: list[str] = Field(min_length=1, max_length=20)
    any: list[str] = Field(max_length=20)


class WarningRule(BaseModel):
    """An explicitly approved rule version; an inactive version stays inert."""

    id: str
    conditions: Conditions
    requiredFields: list[str]
    careLevel: CareLevel
    specialtyIds: list[str]
    source: SourceReference
    reviewStatus: Literal["DRAFT", "PENDING_REVIEW", "APPROVED", "RETURNED", "RETIRED"]
    active: bool


_PRIORITY = {
    "EMERGENCY_NOW": 0,
    "SEEK_CARE_SOON": 1,
    "ROUTINE_CONSULT": 2,
    "GENERAL_SELF_CARE_INFO": 3,
    "INSUFFICIENT_INFO": 4,
}


def _unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))


def _decision(rules: list[WarningRule]) -> SafetyDecision:
    chosen = sorted(rules, key=lambda rule: rule.id)
    return SafetyDecision(
        status="EVALUATED",
        careLevel=chosen[0].careLevel,
        matchedRuleIds=[rule.id for rule in chosen],
        missingFields=[],
        specialtyIds=_unique([specialty for rule in chosen for specialty in rule.specialtyIds]),
        sources=list({rule.source.id: rule.source for rule in chosen}.values()),
    )


def evaluate_facts(facts: set[str], rules: list[WarningRule]) -> SafetyDecision:
    """Use positive, explicitly observed facts only; absent facts remain unknown."""
    candidates = [
        rule
        for rule in rules
        if rule.reviewStatus == "APPROVED"
        and rule.active
        and set(rule.conditions.all).issubset(facts)
        and (not rule.conditions.any or bool(set(rule.conditions.any) & facts))
    ]
    if not candidates:
        return unavailable()

    emergencies = [rule for rule in candidates if rule.careLevel == "EMERGENCY_NOW"]
    if emergencies:
        return _decision(emergencies)

    complete = [rule for rule in candidates if set(rule.requiredFields).issubset(facts)]
    incomplete = [rule for rule in candidates if rule not in complete]
    best_complete = min(complete, key=lambda rule: _PRIORITY[rule.careLevel], default=None)
    best_complete_priority = _PRIORITY[best_complete.careLevel] if best_complete else len(_PRIORITY)
    higher_incomplete = [
        rule for rule in incomplete if _PRIORITY[rule.careLevel] < best_complete_priority
    ]
    if higher_incomplete:
        missing = _unique(
            [field for rule in higher_incomplete for field in rule.requiredFields if field not in facts]
        )
        return SafetyDecision(
            status="EVALUATED",
            careLevel="INSUFFICIENT_INFO",
            matchedRuleIds=[],
            missingFields=missing,
            specialtyIds=[],
            sources=[],
        )
    if best_complete:
        return _decision(
            [rule for rule in complete if rule.careLevel == best_complete.careLevel]
        )
    return unavailable()


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
