"""Port of apps/api/tests/llm.test.ts. No real network request is ever made."""

import json

import httpx
import pytest

from app.config import ConfigError, load_settings
from app.llm import LlmClient, LlmError

ENABLED = {
    "AI_LLM_ENABLED": "true",
    "AI_LLM_BASE_URL": "https://provider.example.invalid/v1",
    "AI_LLM_API_KEY": "synthetic-test-key",
    "AI_LLM_MODEL": "synthetic-test-model",
}


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
    ["https://api.openai.com/v1", "https://generativelanguage.googleapis.com/v1beta/openai/",
     "http://127.0.0.1:8080/v1", "http://host.docker.internal:8080/v1"],
)
def test_openai_compatible_request_works(base):
    def handler(request: httpx.Request):
        assert str(request.url) == base.rstrip("/") + "/chat/completions"
        assert request.headers["Authorization"] == "Bearer synthetic-test-key"
        body = json.loads(request.content)
        assert body["model"] == "synthetic-test-model"
        assert body["stream"] is False
        return httpx.Response(200, json={"choices": [{"message": {"content": "Synthetic response"}}]})

    settings = load_settings({**ENABLED, "AI_LLM_BASE_URL": base})
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
        load_settings({"AI_LLM_BASE_URL": "https://user:secret@example.invalid/v1"})
    with pytest.raises(ConfigError):
        load_settings({"AI_LLM_BASE_URL": "http://insecure.example.invalid/v1"})


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


@pytest.mark.parametrize("base", [
    "http://localhost:8080/v1", "http://127.0.0.1:8080/v1",
    "http://[::1]:8080/v1", "http://host.docker.internal:8080/v1",
])
@pytest.mark.parametrize("stream", [False, True])
def test_local_router_without_key_does_not_send_authorization(base, stream):
    def handler(request):
        assert str(request.url) == base + "/chat/completions"
        assert "Authorization" not in request.headers
        assert json.loads(request.content)["stream"] is stream
        if stream:
            return httpx.Response(200, content=(
                b'data: {"choices":[{"delta":{"content":"Local response"}}]}\n\n'
                b'data: [DONE]\n\n'
            ))
        return httpx.Response(200, json={"choices": [{"message": {"content": "Local response"}}]})

    settings = load_settings({**ENABLED, "AI_LLM_BASE_URL": base, "AI_LLM_API_KEY": ""})
    client = LlmClient(settings, httpx.MockTransport(handler))
    result = "".join(client.stream([])) if stream else client.complete([])
    assert result == "Local response"


@pytest.mark.parametrize("base", [
    "http://provider.example.invalid/v1", "http://192.168.1.5/v1",
    "http://localhost.example.invalid/v1", "http://127.0.0.1.example.invalid/v1",
    "http://host.docker.internal.example.invalid/v1", "ftp://localhost/v1",
    "http://user:secret@localhost/v1", "http://localhost/v1?key=secret",
    "http://localhost/v1#fragment", "http://localhost:invalid/v1",
    "http://localhost:65536/v1", "http://localhost:0/v1", "http://[::1/v1",
    "http://local host/v1", "http://localhost\\evil/v1",
])
def test_unsafe_or_malformed_urls_are_rejected_without_leaking_values(base):
    with pytest.raises(ConfigError) as error:
        load_settings({"AI_LLM_BASE_URL": base})
    assert base not in str(error.value)


@pytest.mark.parametrize("stream", [False, True])
@pytest.mark.parametrize("overrides", [
    {"AI_LLM_BASE_URL": ""}, {"AI_LLM_MODEL": ""},
    {"AI_LLM_API_KEY": ""}, {"AI_LLM_ENABLED": "false"},
])
def test_incomplete_configuration_never_makes_requests(overrides, stream):
    called = []

    def handler(request):
        called.append(request)
        raise AssertionError("Unexpected call")

    client = LlmClient(load_settings({**ENABLED, **overrides}), httpx.MockTransport(handler))
    with pytest.raises(LlmError, match="LLM_NOT_CONFIGURED"):
        list(client.stream([])) if stream else client.complete([])
    assert called == []


def test_translation_and_review_configuration_are_never_used_by_chatbox():
    settings = load_settings({
        f"{role}_LLM_{field}": value
        for role in ("TRANSLATE", "REVIEW")
        for field, value in (("BASE_URL", "https://offline.example.invalid/v1"),
                             ("API_KEY", "synthetic-offline-key"), ("MODEL", "offline-model"))
    })
    assert settings.llm_enabled is False
    assert settings.llm_base_url == ""
    assert settings.llm_api_key == ""
    assert settings.llm_model == ""
