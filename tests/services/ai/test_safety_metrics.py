import json
from pathlib import Path

import pytest

from app.safety import CARE_LEVELS
from app.safety_metrics import measure


def test_perfect_predictions():
    expected = ["EMERGENCY_NOW"] * 30 + list(CARE_LEVELS[1:])
    result = measure(expected, expected)
    assert result["m01Recall"] == 1
    assert result["m02Accuracy"] == 1
    assert result["m02MacroF1"] == 1
    assert result["thresholdsMet"]


def test_abstentions_are_not_removed_from_denominator():
    expected = ["EMERGENCY_NOW"] * 30 + list(CARE_LEVELS[1:])
    result = measure(expected, [None] * len(expected))
    assert result["m01Recall"] == 0
    assert result["m02Accuracy"] == 0
    assert result["m02MacroF1"] == 0
    assert not result["thresholdsMet"]


def test_macro_f1_uses_all_five_labels():
    result = measure(list(CARE_LEVELS), ["EMERGENCY_NOW"] * 5)
    assert result["m01Recall"] == 1
    assert result["m02Accuracy"] == pytest.approx(0.2)
    assert result["m02MacroF1"] == pytest.approx((2 / 6) / 5)
    assert not result["coverageSufficient"]


@pytest.mark.parametrize("expected,predicted", [
    ([], []), (["EMERGENCY_NOW"], []), (["invalid"], [None]),
    (["EMERGENCY_NOW"], ["invalid"]),
])
def test_invalid_labels_rejected(expected, predicted):
    with pytest.raises(ValueError):
        measure(expected, predicted)


def test_draft_corpus_has_thirty_distinct_emergencies_and_all_labels():
    corpus = json.loads(
        (Path(__file__).parents[3] / "services/ai/evaluation/uc07_cases.draft.json").read_text(encoding="utf-8")
    )
    cases = corpus["cases"]
    assert corpus["reviewStatus"] == "DRAFT"
    assert len(cases) == len({case["id"] for case in cases}) == 38
    assert sum(case["expected"] == "EMERGENCY_NOW" for case in cases) == 30
    assert {case["expected"] for case in cases} == set(CARE_LEVELS)
    assert all(case["facts"] and case["text"] for case in cases)