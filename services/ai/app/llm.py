"""OpenAI-compatible chat completion adapter (OpenAI, Gemini OpenAI endpoint, Gemma via a compatible server).

Transport only. M4 must apply M1's Safety Gate, approved RAG sources, data minimisation and
output checks before any answer reaches a user.
"""

from __future__ import annotations

import json
from typing import Iterator, Literal, TypedDict

import httpx

from .config import Settings


class LlmMessage(TypedDict):
    role: Literal["system", "user", "assistant"]
    content: str


class LlmError(RuntimeError):
    """LLM_NOT_CONFIGURED or LLM_REQUEST_FAILED. Never carries provider bodies or keys."""


class LlmClient:
    def __init__(self, settings: Settings, transport: httpx.BaseTransport | None = None):
        self._settings = settings
        self._transport = transport

    def complete(self, messages: list[LlmMessage]) -> str:
        s = self._settings
        if not s.llm_enabled or not s.llm_api_key or not s.llm_model:
            raise LlmError("LLM_NOT_CONFIGURED")
        url = s.llm_base_url.rstrip("/") + "/chat/completions"
        try:
            with httpx.Client(
                transport=self._transport,
                timeout=s.llm_timeout_ms / 1000,
                follow_redirects=False,
            ) as client:
                response = client.post(
                    url,
                    headers={"Authorization": f"Bearer {s.llm_api_key}"},
                    json={"model": s.llm_model, "messages": messages, "stream": False},
                )
            if response.status_code != 200:
                raise LlmError("LLM_REQUEST_FAILED")
            content = response.json()["choices"][0]["message"]["content"]
            if not isinstance(content, str) or not content:
                raise LlmError("LLM_REQUEST_FAILED")
            return content
        except LlmError:
            raise
        except Exception as error:  # Network, JSON or shape errors: no details leave this function.
            raise LlmError("LLM_REQUEST_FAILED") from error

    def stream(self, messages: list[LlmMessage]) -> Iterator[str]:
        """Stream completion tokens. Transport only; caller must validate output before display."""
        s = self._settings
        if not s.llm_enabled or not s.llm_api_key or not s.llm_model:
            raise LlmError("LLM_NOT_CONFIGURED")
        url = s.llm_base_url.rstrip("/") + "/chat/completions"
        try:
            with httpx.Client(
                transport=self._transport,
                timeout=s.llm_timeout_ms / 1000,
                follow_redirects=False,
            ) as client:
                with client.stream(
                    "POST",
                    url,
                    headers={"Authorization": f"Bearer {s.llm_api_key}"},
                    json={"model": s.llm_model, "messages": messages, "stream": True},
                ) as response:
                    if response.status_code != 200:
                        raise LlmError("LLM_REQUEST_FAILED")
                    for line in response.iter_lines():
                        if line.startswith("data: "):
                            raw_chunk = line[6:].strip()
                            if raw_chunk == "[DONE]":
                                break
                            try:
                                payload = json.loads(raw_chunk)
                                delta = payload["choices"][0].get("delta", {})
                                token = delta.get("content")
                                if token:
                                    yield token
                            except Exception:
                                continue
        except LlmError:
            raise
        except Exception as error:
            raise LlmError("LLM_REQUEST_FAILED") from error
