"""Small configuration helpers for offline tools; no service dependencies."""
from __future__ import annotations

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read_env(path: Path = ROOT / ".env") -> dict[str, str]:
    """Read simple KEY=VALUE entries; actual environment variables always win."""
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return {**values, **os.environ}


def load_paths(path: Path = ROOT / "config" / "paths.yaml") -> dict[str, str]:
    """Read the JSON-compatible YAML subset without an extra runtime dependency."""
    values = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(values, dict) or any(
        not isinstance(value, str) or not value.strip() for value in values.values()
    ):
        raise ValueError("Path configuration must map names to non-empty strings")
    return values


def data_path(name: str) -> Path:
    """Resolve configured paths against the repository, never the working directory."""
    return (ROOT / load_paths()[name]).resolve()