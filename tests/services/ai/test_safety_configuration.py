"""Configuration and approval invariants; all rules below are synthetic test fixtures."""

import pytest
from fastapi.testclient import TestClient

from app.config import ConfigError, DEFAULT_SAFETY_PRIORITY, Settings, load_settings
from app.main import create_app
from app.safety import CARE_LEVELS, evaluate_facts
from test_safety_rules import rule


CUSTOM = (
    "EMERGENCY_NOW",
    "ROUTINE_CONSULT",
    "SEEK_CARE_SOON",
    "GENERAL_SELF_CARE_INFO",
    "INSUFFICIENT_INFO",
)


def test_load_priority_from_environment():
    settings = load_settings({"SAFETY_CARE_LEVEL_PRIORITY": ",".join(CUSTOM)})
    assert settings.safety_care_level_priority == CUSTOM
    assert load_settings({}).safety_care_level_priority == DEFAULT_SAFETY_PRIORITY


@pytest.mark.parametrize("value", [
    "",
    "EMERGENCY_NOW",
    ",".join(DEFAULT_SAFETY_PRIORITY[:-1]),
    ",".join(DEFAULT_SAFETY_PRIORITY + ("EMERGENCY_NOW",)),
    ",".join(reversed(DEFAULT_SAFETY_PRIORITY)),
    ",".join(DEFAULT_SAFETY_PRIORITY[:-1] + ("UNKNOWN",)),
])
def test_invalid_priority_fails_configuration(value):
    with pytest.raises(ConfigError):
        load_settings({"SAFETY_CARE_LEVEL_PRIORITY": value})


def test_configured_priority_changes_winner_but_never_suppresses_emergency():
    rules = [rule("routine", "ROUTINE_CONSULT"), rule("soon", "SEEK_CARE_SOON")]
    assert evaluate_facts({"synthetic_sign"}, rules, CUSTOM).careLevel == "ROUTINE_CONSULT"
    rules.append(rule("emergency", "EMERGENCY_NOW", required=("unknown",)))
    assert evaluate_facts({"synthetic_sign"}, rules, CUSTOM).careLevel == "EMERGENCY_NOW"


def test_api_uses_server_configuration():
    client = TestClient(create_app(Settings(service_key="test", safety_care_level_priority=CUSTOM)))
    response = client.post("/v1/safety/evaluate", headers={"X-Service-Key": "test"}, json={
        "facts": ["synthetic_sign"],
        "rules": [rule("routine", "ROUTINE_CONSULT").model_dump(),
                  rule("soon", "SEEK_CARE_SOON").model_dump()],
    })
    assert response.status_code == 200
    assert response.json()["data"]["careLevel"] == "ROUTINE_CONSULT"


@pytest.mark.parametrize("status", ["DRAFT", "PENDING_REVIEW", "RETURNED", "RETIRED"])
@pytest.mark.parametrize("level", CARE_LEVELS)
@pytest.mark.parametrize("active", [True, False])
def test_every_unapproved_status_is_inert(status, level, active):
    candidate = rule("unapproved", level, status=status, active=active)
    decision = evaluate_facts({"synthetic_sign"}, [candidate])
    assert decision.status == "UNAVAILABLE"
    assert decision.careLevel is None
    assert decision.matchedRuleIds == []
    assert decision.sources == []
    approved = rule("approved", "ROUTINE_CONSULT")
    decision = evaluate_facts({"synthetic_sign"}, [candidate, approved])
    assert decision.careLevel == "ROUTINE_CONSULT"
    assert decision.matchedRuleIds == ["approved"]


@pytest.mark.parametrize("level", CARE_LEVELS)
def test_inactive_approved_rules_are_inert(level):
    assert evaluate_facts(
        {"synthetic_sign"}, [rule("inactive", level, active=False)]
    ).status == "UNAVAILABLE"