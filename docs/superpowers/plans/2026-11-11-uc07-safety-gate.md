# UC-07 Safety Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evaluate normalized, explicitly supplied facts against approved warning rules and return one conservative care decision to UC-06.

**Architecture:** Spring Boot owns and filters stored rules, then passes approved rule versions and normalized facts to the FastAPI Safety Gate. FastAPI applies deterministic precedence and returns a decision; Spring Boot enriches it only with approved specialty mappings and verified directory entries. UC-06 renders this decision independently of LLM output.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, Python, FastAPI, Pydantic, unittest/pytest.

**Spec:** UC-07 requirements supplied in the conversation; `docs/requirements/PROJECT_CONTEXT.md` and GitHub issue #41.

## Global Constraints

- Unmentioned symptoms and fields are unknown, never negative.
- Emergency rules run before clarification; the LLM cannot weaken or replace the decision.
- Only approved, active, sourced rules may be evaluated. No rules, no match, or store failure means `UNAVAILABLE` and `careLevel: null`.
- No real medical warning rules or facility records are invented in tests; use explicitly synthetic fixtures.
- M-01 requires at least 27/30 emergency cases; M-02 requires accuracy >= 0.75 and macro-F1 >= 0.70 on the agreed labeled set.

## Review Focus

- Missing facts must not be interpreted as explicit negatives.
- Emergency matches must win even if other rules have missing fields.
- Duplicate missing fields must be returned once in stable order.
- Stale or inactive rules must not reach the evaluator even when marked approved.
- A failed rule store or AI service must produce a safe decision envelope, not a low-risk label.

### Task 1: Deterministic decision core

**Files:** `services/ai/app/safety.py`, `services/ai/tests/test_safety_rules.py`.

**Interface:** `evaluate_facts(facts: set[str], rules: list[WarningRule]) -> SafetyDecision`. Rules carry `id`, `conditions.all`, `conditions.any`, `requiredFields`, `careLevel`, `specialtyIds`, and approved source metadata. The caller supplies only facts explicitly extracted from the normalized user input.

- [x] Write failing tests for no rules, no match, emergency precedence, missing fields, multiple matches, and duplicate fields.
- [x] Run tests and confirm failure is caused by the absent evaluator.
- [x] Implement one deterministic result with `UNAVAILABLE` when no rule applies and `INSUFFICIENT_INFO` only when a rule can apply but critical facts are missing.
- [ ] Run focused tests and all AI tests; focused `unittest` has 10 passing tests, and existing API test functions pass; full `pytest` is unavailable in this Python environment.

### Task 2: Approved rule loading and FastAPI boundary

**Files:** `backend/src/main/java/vn/edu/uit/se214/health/safety/RuleRepository.java`, an M1 evaluator adapter, `services/ai/app/main.py`, corresponding Java/Python tests.

- [x] Write failing tests for approved, active, verified-source filtering and the internal adapter. The SQL predicate excludes all other review states, inactive and synthetic rules.
- [x] Run focused tests to confirm the loader and adapter were absent.
- [x] Load only approved active versions and send explicit facts and rule data over the authenticated service boundary.
- [x] Run focused tests and verify missing store/service configuration returns `UNAVAILABLE`; full backend `mvn verify` passed with 47 tests, zero failures/errors.

### Task 3: UC-06 result and verified directory enrichment

**Files:** M1/M4 integration, approved specialty mapping and facility lookup, contract tests.

- [ ] Write tests for emergency display before LLM, immutable care level, no fabricated facility, and AI-service failure fallback.
- [ ] Implement adapter and verified catalog lookup without allowing LLM output to alter the SafetyDecision.
- [ ] Run integration tests and inspect the returned decision envelope.

### Task 4: Acceptance dataset and metrics

**Files:** versioned, expert-reviewed case set and offline evaluator script/tests.

- [ ] Agree on 30 emergency cases and the full labeled care-direction set; record provenance and review status.
- [ ] Measure M-01 and M-02 from actual predictions and publish a confusion matrix and failing cases.
- [ ] Investigate every missed emergency and report observed scores without claiming target thresholds that were not reached.

## Progress and rulings (2026-11-11)

- Ruling: Text alone does not prove a symptom is present or absent. `evaluate(String)` remains unavailable; UC-06 must supply explicitly extracted facts through `evaluateFacts` before the real evaluator can act. This keeps current callers safe but leaves UC-06 integration outstanding.
- Ruling: An active rule requires `APPROVED` plus `source_verified_at`; migration V009 enforces this. The UC-17 review handler must set activation and verification metadata in one decision transaction. Until then production returns `UNAVAILABLE`.
- Ruling: Synthetic rules are excluded from the Spring Boot loader. Synthetic fixtures exercise decision logic directly without being offered as medical advice.
- Ruling: Spring Boot sends only explicit fact tokens and approved rule payloads to FastAPI; the normalized user text is not needed by the deterministic core and stays out of the internal request.
- Ruling: Spring Boot validates that a returned care level matches the approved rule IDs and that source/specialty IDs come from those rules. An inconsistent response becomes `UNAVAILABLE`.
- Pending: the clinical rule set, approved specialty mapping, verified facility selection, UC-06 rendering/LLM boundary, and the reviewed acceptance dataset are outside the current implementation and still required for issue #41.
