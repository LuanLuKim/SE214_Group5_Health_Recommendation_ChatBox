"""Port of apps/api/tests/llm.test.ts. No real network request is ever made."""

import json

import httpx
import pytest

from app.config import ConfigError, load_settings
from app.llm import LlmClient, LlmError

ENABLED = {"LLM_ENABLED": "true", "LLM_API_KEY": "synthetic-test-key", "LLM_MODEL": "synthetic-test-model"}


def test_llm_defaults_to_disabled_and_makes_no_network_request():
    called = []

    def handler(request):
        called.append(request)
        raise AssertionError("Unexpected call")

    client = LlmClient(load_settings({}), httpx.MockTransport(handler))
    with pytest.raises(LlmError, match="LLM_NOT_CONFIGURED"):
        client.complete([{"role": "user", "content": "Synthetic"}])
    assert called == []


@pytest.mark.parametrize(
    "base",
    ["https://api.openai.com/v1", "https://generativelanguage.googleapis.com/v1beta/openai/"],
)
def test_openai_compatible_request_works(base):
    def handler(request: httpx.Request):
        assert str(request.url) == base.rstrip("/") + "/chat/completions"
        assert request.headers["Authorization"] == "Bearer synthetic-test-key"
        body = json.loads(request.content)
        assert body["model"] == "synthetic-test-model"
        assert body["stream"] is False
        return httpx.Response(200, json={"choices": [{"message": {"content": "Synthetic response"}}]})

    settings = load_settings({**ENABLED, "LLM_BASE_URL": base})
    client = LlmClient(settings, httpx.MockTransport(handler))
    assert client.complete([{"role": "user", "content": "Synthetic"}]) == "Synthetic response"


def test_provider_failures_and_malformed_output_do_not_leak_provider_details():
    settings = load_settings(ENABLED)
    bad_status = LlmClient(settings, httpx.MockTransport(lambda r: httpx.Response(429, text="sensitive-provider-body")))
    with pytest.raises(LlmError) as error:
        bad_status.complete([])
    assert str(error.value) == "LLM_REQUEST_FAILED"
    assert "sensitive" not in str(error.value)

    malformed = LlmClient(settings, httpx.MockTransport(lambda r: httpx.Response(200, json={"choices": []})))
    with pytest.raises(LlmError, match="LLM_REQUEST_FAILED"):
        malformed.complete([])

    with pytest.raises(ConfigError):
        load_settings({"LLM_BASE_URL": "https://user:secret@example.invalid/v1"})
    with pytest.raises(ConfigError):
        load_settings({"LLM_BASE_URL": "http://insecure.example.invalid/v1"})


def test_llm_stream_works():
    sse_body = (
        b'data: {"choices":[{"delta":{"content":"Hel"}}]}\n\n'
        b'data: {"choices":[{"delta":{"content":"lo"}}]}\n\n'
        b"data: [DONE]\n\n"
    )

    def handler(request: httpx.Request):
        assert request.headers["Authorization"] == "Bearer synthetic-test-key"
        body = json.loads(request.content)
        assert body["stream"] is True
        return httpx.Response(200, content=sse_body)

    settings = load_settings(ENABLED)
    client = LlmClient(settings, httpx.MockTransport(handler))
    tokens = list(client.stream([{"role": "user", "content": "Hi"}]))
    assert tokens == ["Hel", "lo"]


def test_llm_stream_error_handling():
    settings = load_settings(ENABLED)
    bad_status = LlmClient(settings, httpx.MockTransport(lambda r: httpx.Response(500, text="error")))
    with pytest.raises(LlmError, match="LLM_REQUEST_FAILED"):
        list(bad_status.stream([]))
