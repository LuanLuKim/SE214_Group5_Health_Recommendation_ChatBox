"""Synthetic decision cases for UC-07; no clinical warning rules live here."""

import unittest

from pydantic import ValidationError
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.safety import SourceReference, WarningRule, evaluate_facts


SOURCE = SourceReference(
    id="synthetic-source", name="[TEST] source", url="https://example.invalid/rule", verifiedAt="2026-11-11T00:00:00Z"
)


def rule(
    rule_id: str,
    level: str,
    *,
    all_signs: tuple[str, ...] = ("synthetic_sign",),
    any_signs: tuple[str, ...] = (),
    required: tuple[str, ...] = (),
    status: str = "APPROVED",
    active: bool = True,
) -> WarningRule:
    return WarningRule.model_validate(
        {
            "id": rule_id,
            "conditions": {"all": list(all_signs), "any": list(any_signs)},
            "requiredFields": list(required),
            "careLevel": level,
            "specialtyIds": ["synthetic-specialty"],
            "source": SOURCE.model_dump(),
            "reviewStatus": status,
            "active": active,
        }
    )


class SafetyRuleDecisionTest(unittest.TestCase):
    def test_no_approved_rule_or_no_match_is_unavailable(self):
        for rules in (
            [],
            [rule("draft", "EMERGENCY_NOW", status="DRAFT")],
            [rule("inactive", "EMERGENCY_NOW", active=False)],
            [rule("approved", "EMERGENCY_NOW")],
        ):
            with self.subTest(rules=rules):
                facts = {"different_sign"} if rules and rules[0].id == "approved" else {"synthetic_sign"}
                decision = evaluate_facts(facts, rules)
                self.assertEqual(decision.status, "UNAVAILABLE")
                self.assertIsNone(decision.careLevel)

    def test_emergency_wins_before_clarification_and_lower_levels(self):
        rules = [
            rule("routine", "ROUTINE_CONSULT"),
            rule("urgent", "EMERGENCY_NOW", required=("onset",)),
            rule("soon", "SEEK_CARE_SOON"),
        ]
        decision = evaluate_facts({"synthetic_sign"}, rules)
        self.assertEqual(decision.careLevel, "EMERGENCY_NOW")
        self.assertEqual(decision.matchedRuleIds, ["urgent"])
        self.assertEqual(decision.missingFields, [])

    def test_more_urgent_complete_rule_wins_regardless_of_input_order(self):
        rules = [rule("general", "GENERAL_SELF_CARE_INFO"), rule("soon", "SEEK_CARE_SOON")]
        for order in (rules, list(reversed(rules))):
            decision = evaluate_facts({"synthetic_sign"}, order)
            self.assertEqual(decision.careLevel, "SEEK_CARE_SOON")
            self.assertEqual(decision.matchedRuleIds, ["soon"])

    def test_missing_fields_are_unique_and_unknown_is_not_negative(self):
        rules = [
            rule("a", "ROUTINE_CONSULT", required=("onset", "progression")),
            rule("b", "ROUTINE_CONSULT", required=("onset",)),
        ]
        decision = evaluate_facts({"synthetic_sign"}, rules)
        self.assertEqual(decision.careLevel, "INSUFFICIENT_INFO")
        self.assertEqual(decision.missingFields, ["onset", "progression"])
        self.assertEqual(decision.matchedRuleIds, [])

    def test_incomplete_higher_risk_rule_prevents_low_risk_label(self):
        rules = [
            rule("general", "GENERAL_SELF_CARE_INFO"),
            rule("soon", "SEEK_CARE_SOON", required=("onset",)),
        ]
        decision = evaluate_facts({"synthetic_sign"}, rules)
        self.assertEqual(decision.careLevel, "INSUFFICIENT_INFO")
        self.assertEqual(decision.missingFields, ["onset"])

    def test_rule_requires_at_least_one_positive_condition(self):
        with self.assertRaises(ValidationError):
            rule("unsafe-catch-all", "GENERAL_SELF_CARE_INFO", all_signs=())

    def test_rule_requires_reviewed_source_metadata(self):
        with self.assertRaises(ValidationError):
            WarningRule.model_validate(
                {
                    **rule("unsourced", "ROUTINE_CONSULT").model_dump(),
                    "source": {**SOURCE.model_dump(), "verifiedAt": ""},
                }
            )

    def test_any_condition_needs_an_explicitly_observed_fact(self):
        candidate = rule("any", "ROUTINE_CONSULT", any_signs=("sign_a", "sign_b"))
        self.assertEqual(evaluate_facts({"synthetic_sign"}, [candidate]).status, "UNAVAILABLE")
        self.assertEqual(
            evaluate_facts({"synthetic_sign", "sign_b"}, [candidate]).careLevel,
            "ROUTINE_CONSULT",
        )


class SafetyRuleApiTest(unittest.TestCase):
    def test_internal_endpoint_evaluates_only_approved_rules_with_supplied_facts(self):
        client = TestClient(create_app(Settings(service_key="synthetic-service-key")))
        body = {
            "text": "[TEST] normalized content",
            "facts": ["synthetic_sign"],
            "rules": [
                rule("draft", "EMERGENCY_NOW", status="DRAFT").model_dump(),
                rule("approved", "SEEK_CARE_SOON").model_dump(),
            ],
        }
        response = client.post(
            "/v1/safety/evaluate", json=body, headers={"X-Service-Key": "synthetic-service-key"}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"]["careLevel"], "SEEK_CARE_SOON")
        self.assertEqual(response.json()["data"]["matchedRuleIds"], ["approved"])

    def test_internal_endpoint_needs_only_explicit_facts_not_user_text(self):
        client = TestClient(create_app(Settings(service_key="synthetic-service-key")))
        response = client.post(
            "/v1/safety/evaluate",
            json={
                "facts": ["synthetic_sign"],
                "rules": [rule("approved", "SEEK_CARE_SOON").model_dump()],
            },
            headers={"X-Service-Key": "synthetic-service-key"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["data"]["careLevel"], "SEEK_CARE_SOON")


if __name__ == "__main__":
    unittest.main()
