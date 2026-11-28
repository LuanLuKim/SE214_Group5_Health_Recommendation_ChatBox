import json
from typing import Iterator

import httpx
import pytest
from pydantic import ValidationError
from starlette.testclient import TestClient

from app.chat import (
    ChatAnswerRequest,
    RagChunk,
    extract_facts,
    is_out_of_scope,
    load_chat_lexicon,
    orchestrate_chat,
    retrieve_relevant_chunks,
)
from app.config import Settings
from app.llm import LlmClient
from app.main import create_app
from app.safety import SafetyDecision, SourceReference

TEST_SETTINGS = Settings(
    service_key="test-key",
    llm_enabled=True,
    llm_base_url="https://api.openai.com/v1",
    llm_api_key="sk-test",
    llm_model="test-model",
    llm_timeout_ms=5000,
    enable_synthetic_rag_stub=False,
)

TEST_SOURCE = SourceReference(
    id="__SYNTHETIC_ONLY__src-1",
    name="[TEST] Nguồn kiểm thử",
    url="https://example.invalid/rule",
    verifiedAt="2026-11-01T00:00:00Z",
)

EMERGENCY_DECISION = SafetyDecision(
    status="EVALUATED",
    careLevel="EMERGENCY_NOW",
    matchedRuleIds=["rule-emerg-01"],
    missingFields=[],
    specialtyIds=["cardiology", "emergency"],
    sources=[TEST_SOURCE],
)

INSUFFICIENT_DECISION = SafetyDecision(
    status="EVALUATED",
    careLevel="INSUFFICIENT_INFO",
    matchedRuleIds=[],
    missingFields=["onset", "accompanying_symptoms"],
    specialtyIds=[],
    sources=[],
)


def _collect_sse(generator: Iterator[str]) -> list[dict]:
    events = []
    for line in generator:
        if line.startswith("data: "):
            events.append(json.loads(line[6:].strip()))
    return events


def test_lexicon_loaded_from_external_file():
    lexicon = load_chat_lexicon()
    assert lexicon.symptom_keywords
    assert lexicon.scope_refusal_patterns
    assert lexicon.synthetic_rag_chunks
    assert lexicon.symptom_keywords["ho"] == "cough"
    assert lexicon.symptom_keywords["đau ngực"] == "chest_pain"


def test_fact_extraction_uses_m1_vocabulary_and_word_boundaries():
    text = "Tôi bị sốt cao kèm đau ngực và khó thở từ hôm qua"
    facts = extract_facts(text)
    assert {"fever", "high_body_temperature", "chest_pain", "breathlessness"}.issubset(facts)

    # Substring false-positive regression: "cho", "nhỏ", "họ", "hoa" must never match "ho" -> "cough"
    benign = extract_facts("Xin cho tôi hỏi nhỏ về họ hàng và hoa quả sạch")
    assert "cough" not in benign

    # Whole-word "ho" matches cleanly
    assert "cough" in extract_facts("Tôi bị ho khan nhiều ngày")


def test_rag_stub_disabled_by_default_and_synthetic_when_enabled():
    # Production default: stub returns [] so A3 applies when backend passes no chunks
    assert retrieve_relevant_chunks("Tôi bị sốt và ho") == []

    # When enabled explicitly in mock/test mode: only synthetic markers, no moh.gov.vn/who.int
    chunks = retrieve_relevant_chunks("Tôi bị ho", enable_stub=True)
    assert len(chunks) == 1
    assert "__SYNTHETIC_ONLY__" in chunks[0].id
    assert "[TEST]" in chunks[0].title
    assert chunks[0].source.url.startswith("https://example.invalid/")

    # Word boundary prevents "cho" / "nhỏ" from triggering respiratory chunk
    assert retrieve_relevant_chunks("Hãy cho tôi biết thông tin nhỏ này", enable_stub=True) == []


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
    with pytest.raises(ValidationError):
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


def test_flow_a1_emergency_skips_llm_using_m1_decision():
    req = ChatAnswerRequest(
        session_id="s1",
        message="Tôi đang bị đau ngực dữ dội và khó thở",
        safety_decision=EMERGENCY_DECISION,
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
        safety_decision=INSUFFICIENT_DECISION,
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

    safety_event = next(e for e in events if e["type"] == "safety")
    assert safety_event["data"]["status"] == "UNAVAILABLE"
    assert safety_event["data"]["careLevel"] is None

    error_event = next(e for e in events if e["type"] == "error")
    assert error_event["data"]["code"] == "LLM_SERVICE_UNAVAILABLE"
    assert error_event["data"]["canRetry"] is True


def test_flow_a4_output_checker_blocks_violation():
    def mock_handler(_request):
        sse = b'data: {"choices":[{"delta":{"content":"Ch\xe1\xba\xa9n \xc4\x91o\xc3\xa1n c\xe1\xbb\xa7a b\xe1\xba\xa1n l\xc3\xa0 vi\xc3\xaam h\xe1\xbb\x8dng."}}]}\n\ndata: [DONE]\n\n'
        return httpx.Response(200, content=sse)

    mock_client = LlmClient(TEST_SETTINGS, httpx.MockTransport(mock_handler))
    req = ChatAnswerRequest(session_id="s1", message="Tôi bị đau rát họng")

    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS, llm_client=mock_client))
    tokens = [e["data"] for e in events if e["type"] == "token"]
    assembled_answer = "".join(tokens)
    assert "bị chặn vì phát hiện thông tin không được phép" in assembled_answer


def test_successful_flow_with_supplied_rag_chunks_and_budget():
    captured_prompts: list[str] = []

    def mock_handler(request):
        body = json.loads(request.content.decode("utf-8"))
        captured_prompts.append(body["messages"][0]["content"])
        sse = b'data: {"choices":[{"delta":{"content":"B\xe1\xba\xa1n n\xc3\xaan ngh\xe1\xbb\x89 ng\xc6\xa1i v\xc3\xa0 u\xe1\xbb\x91ng nhi\xe1\xbb\x81u n\xc6\xb0\xe1\xbb\x9bc."}}]}\n\ndata: [DONE]\n\n'
        return httpx.Response(200, content=sse)

    supplied_chunks = [
        RagChunk(
            id=f"chunk-{i}",
            title=f"[TEST] Tài liệu {i}",
            text=" ".join(["word"] * 400),
            source=TEST_SOURCE,
        )
        for i in range(6)
    ]

    mock_client = LlmClient(TEST_SETTINGS, httpx.MockTransport(mock_handler))
    req = ChatAnswerRequest(session_id="s1", message="Tôi bị cảm cúm nhẹ", chunks=supplied_chunks)

    events = _collect_sse(orchestrate_chat(req, TEST_SETTINGS, llm_client=mock_client))
    types = [e["type"] for e in events]
    assert "safety" in types
    assert "token" in types
    assert "sources" in types
    assert "done" in types

    # Prompt uses UNAVAILABLE explicit warning (never assumes low risk) and caps chunks to 1500 words (3 chunks * 400 = 1200)
    assert "UNAVAILABLE" in captured_prompts[0]
    assert "[TEST] Tài liệu 2" in captured_prompts[0]
    assert "[TEST] Tài liệu 3" not in captured_prompts[0]

    tokens = [e["data"] for e in events if e["type"] == "token"]
    assembled = "".join(tokens)
    assert "nghỉ ngơi" in assembled
    assert "tham khảo" in assembled


def test_api_chat_answer_endpoint():
    app = create_app(TEST_SETTINGS)
    client = TestClient(app)

    # 1. Missing service key
    resp = client.post("/v1/chat/answer", json={"session_id": "s1", "message": "hello"})
    assert resp.status_code == 401

    # 2. Emergency flow via API using authoritative safety_decision from M1
    resp = client.post(
        "/v1/chat/answer",
        headers={"X-Service-Key": "test-key"},
        json={
            "session_id": "s1",
            "message": "Tôi bị đau ngực và khó thở",
            "safety_decision": EMERGENCY_DECISION.model_dump(),
        },
    )
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]
    text = resp.text
    assert "EMERGENCY_NOW" in text
    assert "115" in text

    # 3. Extra 'rules' field is rejected because only M1 evaluates rules
    bad_resp = client.post(
        "/v1/chat/answer",
        headers={"X-Service-Key": "test-key"},
        json={"session_id": "s1", "message": "hello", "rules": []},
    )
    assert bad_resp.status_code == 400
