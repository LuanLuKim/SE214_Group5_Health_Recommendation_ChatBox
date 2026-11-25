"""Offline UC-07 metrics on independently labelled cases, including abstentions as errors.

Usage (from services/ai):
python -m app.safety_metrics --cases cases.json --rules approved-rules.json
No rule is promoted or activated by this command.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .config import load_settings
from .safety import CARE_LEVELS, WarningRule, evaluate_facts


def measure(expected: list[str], predicted: list[str | None]) -> dict:
    if not expected or len(expected) != len(predicted):
        raise ValueError("Nonempty aligned labels are required.")
    if any(label not in CARE_LEVELS for label in expected):
        raise ValueError("Invalid expected care level.")
    if any(label is not None and label not in CARE_LEVELS for label in predicted):
        raise ValueError("Invalid predicted care level.")
    emergency_count = expected.count("EMERGENCY_NOW")
    emergency_correct = sum(
        actual == guess == "EMERGENCY_NOW" for actual, guess in zip(expected, predicted)
    )
    per_label = {}
    for label in CARE_LEVELS:
        tp = sum(a == label and p == label for a, p in zip(expected, predicted))
        fp = sum(a != label and p == label for a, p in zip(expected, predicted))
        fn = sum(a == label and p != label for a, p in zip(expected, predicted))
        per_label[label] = {
            "support": expected.count(label),
            "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else 0.0,
        }
    accuracy = sum(a == p for a, p in zip(expected, predicted)) / len(expected)
    macro_f1 = sum(row["f1"] for row in per_label.values()) / len(CARE_LEVELS)
    recall = emergency_correct / emergency_count if emergency_count else None
    coverage = emergency_count >= 30 and all(row["support"] for row in per_label.values())
    return {
        "caseCount": len(expected),
        "emergencyCount": emergency_count,
        "emergencyCorrect": emergency_correct,
        "m01Recall": recall,
        "m02Accuracy": accuracy,
        "m02MacroF1": macro_f1,
        "perLabel": per_label,
        "coverageSufficient": coverage,
        "thresholdsMet": bool(
            coverage and recall is not None and recall >= 0.90
            and accuracy >= 0.75 and macro_f1 >= 0.70
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--rules", type=Path, required=True)
    args = parser.parse_args()
    corpus = json.loads(args.cases.read_text(encoding="utf-8"))
    cases = corpus["cases"]
    if len({case["id"] for case in cases}) != len(cases):
        raise ValueError("Duplicate case IDs.")
    rules = [WarningRule.model_validate(item)
             for item in json.loads(args.rules.read_text(encoding="utf-8"))]
    settings = load_settings()
    expected = [case["expected"] for case in cases]
    predicted = [
        evaluate_facts(set(case["facts"]), rules, settings.safety_care_level_priority).careLevel
        for case in cases
    ]
    report = measure(expected, predicted)
    report["evaluationScope"] = "explicit-facts-only; not text extraction or end-to-end chat"
    report["labelsReviewed"] = corpus.get("reviewStatus") == "APPROVED"
    report["acceptanceReady"] = report["labelsReviewed"] and report["thresholdsMet"]
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["acceptanceReady"] else 1


if __name__ == "__main__":
    raise SystemExit(main())