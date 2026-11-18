import json
from typing import Iterator

import httpx
import pytest
from starlette.testclient import TestClient

from app.chat import (
    ChatAnswerRequest,
    extract_facts,
    is_out_of_scope,
    orchestrate_chat,
)
from app.config import Settings
from app.llm import LlmClient, LlmError
from app.main import create_app
from app.safety import Conditions, SourceReference, WarningRule

TEST_SETTINGS = Settings(
    service_key="test-key",
    llm_enabled=True,
    llm_base_url="https://api.openai.com/v1",
    llm_api_key="sk-test",
    llm_model="test-model",
    llm_timeout_ms=5000,
)

EMERGENCY_RULE = WarningRule(
    id="rule-emerg-01",
    conditions=Conditions(all=["dau_nguc"], any=["kho_tho"]),
    requiredFields=[],
    careLevel="EMERGENCY_NOW",
    specialtyIds=["tim_mach", "cap_cuu"],
    source=SourceReference(id="src-1", name="WHO", url="https://who.int/emerg", verifiedAt="2026-11-01"),
    reviewStatus="APPROVED",
    active=True,
)

INSUFFICIENT_RULE = WarningRule(
    id="rule-fever-clarify",
    conditions=Conditions(all=["sot"], any=[]),
    requiredFields=["thoi_gian_khoi_phat", "trieu_chung_kem_theo"],
    careLevel="ROUTINE_CONSULT",
    specialtyIds=["noi_tong_quat"],
    source=SourceReference(id="src-2", name="MOH", url="https://moh.gov.vn/fever", verifiedAt="2026-11-01"),
    reviewStatus="APPROVED",
    active=True,
)


def _collect_sse(generator: Iterator[str]) -> list[dict]:
    events = []
    for line in generator:
        if line.startswith("data: "):
            events.append(json.loads(line[6:].strip()))
    return events


def test_fact_extraction():
    text = "Tôi bị sốt cao kèm đau ngực và khó thở từ hôm qua"
    facts = extract_facts(text)
    assert "sot" in facts or "sot_cao" in facts
    assert "dau_nguc" in facts
    assert "kho_tho" in facts


def test_out_of_scope_detection():
    assert is_out_of_scope("Hãy kê đơn thuốc cho tôi")
    assert is_out_of_scope("Tôi nên uống thuốc gì bây giờ?")
    assert is_out_of_scope("Cho tôi biết liều dùng paracetamol")
    assert not is_out_of_scope("Tôi bị sốt thì nên chăm sóc thế nào?")


def test_empty_message_validation():
    req = ChatAnswerRequest(session_id="s1", message="   ")
    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS))
    assert events[0]["type"] == "error"
    assert events[0]["data"]["code"] == "INVALID_REQUEST"
    assert events[1]["type"] == "done"


def test_too_long_message_validation():
    with pytest.raises(Exception):
        ChatAnswerRequest(session_id="s1", message="a" * 4001)

    app = create_app(TEST_SETTINGS)
    client = TestClient(app)
    resp = client.post(
        "/v1/chat/answer",
        headers={"X-Service-Key": "test-key"},
        json={"session_id": "s1", "message": "a" * 4001},
    )
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "INVALID_REQUEST"


def test_out_of_scope_request_refusal():
    req = ChatAnswerRequest(session_id="s1", message="Bác sĩ hãy kê đơn thuốc kháng sinh cho tôi")
    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS))
    types = [e["type"] for e in events]
    assert "safety" in types
    assert "token" in types
    token_event = next(e for e in events if e["type"] == "token")
    assert "không được phép kê đơn" in token_event["data"]


def test_flow_a1_emergency_skips_llm():
    req = ChatAnswerRequest(
        session_id="s1",
        message="Tôi đang bị đau ngực dữ dội và khó thở",
        rules=[EMERGENCY_RULE],
    )
    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS))
    types = [e["type"] for e in events]
    assert "safety" in types
    assert "token" in types

    safety_event = next(e for e in events if e["type"] == "safety")
    assert safety_event["data"]["careLevel"] == "EMERGENCY_NOW"

    token_event = next(e for e in events if e["type"] == "token")
    assert "115" in token_event["data"]

    done_event = next(e for e in events if e["type"] == "done")
    assert done_event["data"]["status"] == "EMERGENCY_HANDLED"


def test_flow_a2_insufficient_info_asks_clarification():
    req = ChatAnswerRequest(
        session_id="s1",
        message="Tôi bị sốt",
        rules=[INSUFFICIENT_RULE],
    )
    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS))
    safety_event = next(e for e in events if e["type"] == "safety")
    assert safety_event["data"]["careLevel"] == "INSUFFICIENT_INFO"

    token_event = next(e for e in events if e["type"] == "token")
    assert "thông tin còn thiếu" in token_event["data"]

    done_event = next(e for e in events if e["type"] == "done")
    assert done_event["data"]["status"] == "CLARIFICATION_NEEDED"


def test_flow_a5_llm_failure_retains_safety_decision():
    def mock_handler(_request):
        return httpx.Response(500, text="internal server error")

    mock_client = LlmClient(TEST_SETTINGS, httpx.MockTransport(mock_handler))
    req = ChatAnswerRequest(session_id="s1", message="Tôi cảm thấy mệt mỏi")

    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS, llm_client=mock_client))
    types = [e["type"] for e in events]
    assert "safety" in types
    assert "error" in types

    error_event = next(e for e in events if e["type"] == "error")
    assert error_event["data"]["code"] == "LLM_SERVICE_UNAVAILABLE"
    assert error_event["data"]["canRetry"] is True


def test_flow_a4_output_checker_blocks_violation():
    # LLM outputs a prohibited diagnosis
    def mock_handler(_request):
        sse = b'data: {"choices":[{"delta":{"content":"Ch\xe1\xba\xa9n \xc4\x91o\xc3\xa1n c\xe1\xbb\xa7a b\xe1\xba\xa1n l\xc3\xa0 vi\xc3\xaam h\xe1\xbb\x8dng."}}]}\n\ndata: [DONE]\n\n'
        return httpx.Response(200, content=sse)

    mock_client = LlmClient(TEST_SETTINGS, httpx.MockTransport(mock_handler))
    req = ChatAnswerRequest(session_id="s1", message="Tôi bị đau rát họng")

    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS, llm_client=mock_client))
    tokens = [e["data"] for e in events if e["type"] == "token"]
    assembled_answer = "".join(tokens)
    assert "bị chặn vì phát hiện thông tin không được phép" in assembled_answer


def test_successful_flow_with_rag_and_output_checking():
    def mock_handler(_request):
        sse = b'data: {"choices":[{"delta":{"content":"B\xe1\xba\xa1n n\xc3\xaan ngh\xe1\xbb\x89 ng\xc6\xa1i v\xc3\xa0 u\xe1\xbb\x91ng nhi\xe1\xbb\x81u n\xc6\xb0\xe1\xbb\x9bc."}}]}\n\ndata: [DONE]\n\n'
        return httpx.Response(200, content=sse)

    mock_client = LlmClient(TEST_SETTINGS, httpx.MockTransport(mock_handler))
    req = ChatAnswerRequest(session_id="s1", message="Tôi bị cảm cúm nhẹ")

    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS, llm_client=mock_client))
    types = [e["type"] for e in events]
    assert "safety" in types
    assert "token" in types
    assert "sources" in types
    assert "done" in types

    tokens = [e["data"] for e in events if e["type"] == "token"]
    assembled = "".join(tokens)
    assert "nghỉ ngơi" in assembled
    assert "tham khảo" in assembled  # Disclaimer appended by output checker


def test_api_chat_answer_endpoint():
    app = create_app(TEST_SETTINGS)
    client = TestClient(app)

    # 1. Missing service key
    resp = client.post("/v1/chat/answer", json={"session_id": "s1", "message": "hello"})
    assert resp.status_code == 401

    # 2. Emergency flow via API
    resp = client.post(
        "/v1/chat/answer",
        headers={"X-Service-Key": "test-key"},
        json={
            "session_id": "s1",
            "message": "Tôi bị đau ngực và khó thở",
            "rules": [EMERGENCY_RULE.model_dump()],
        },
    )
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]
    text = resp.text
    assert "EMERGENCY_NOW" in text
    assert "115" in text
