"""Environment configuration. Secrets come only from environment variables (or services/ai/.env)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit


class ConfigError(ValueError):
    pass


@dataclass(frozen=True)
class Settings:
    service_key: str = ""
    llm_enabled: bool = False
    llm_base_url: str = "https://api.openai.com/v1"
    llm_api_key: str = ""
    llm_model: str = ""
    llm_timeout_ms: int = 20000


def _valid_base_url(value: str) -> bool:
    parts = urlsplit(value)
    return (
        parts.scheme == "https"
        and bool(parts.hostname)
        and not parts.username
        and not parts.password
        and not parts.query
        and not parts.fragment
    )


def load_settings(env: Mapping[str, str] | None = None) -> Settings:
    """Validate configuration. The error message never repeats the values."""
    env = os.environ if env is None else env
    enabled = env.get("LLM_ENABLED", "false")
    if enabled not in ("true", "false"):
        raise ConfigError("Invalid environment configuration. Check the .env.example fields.")
    base_url = env.get("LLM_BASE_URL", "https://api.openai.com/v1")
    if not _valid_base_url(base_url):
        raise ConfigError("Invalid environment configuration. Check the .env.example fields.")
    try:
        timeout = int(env.get("LLM_TIMEOUT_MS", "20000"))
    except ValueError as error:
        raise ConfigError("Invalid environment configuration. Check the .env.example fields.") from error
    if not 100 <= timeout <= 120000:
        raise ConfigError("Invalid environment configuration. Check the .env.example fields.")
    return Settings(
        service_key=env.get("AI_SERVICE_KEY", ""),
        llm_enabled=enabled == "true",
        llm_base_url=base_url,
        llm_api_key=env.get("LLM_API_KEY", ""),
        llm_model=env.get("LLM_MODEL", ""),
        llm_timeout_ms=timeout,
    )


def read_dotenv(path: Path) -> dict[str, str]:
    """Minimal KEY=VALUE reader for local development; real environment variables win."""
    values: dict[str, str] = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return {**values, **os.environ}
