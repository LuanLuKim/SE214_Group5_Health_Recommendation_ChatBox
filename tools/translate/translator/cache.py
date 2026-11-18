"""Append-only JSONL cache, one file per engine (and one for the LLM).

Every translated segment is written as soon as its batch finishes, so a run
that stops (out of memory, rate limit, closed laptop) continues where it was.
The key covers the engine's model and settings: change them and the old
entries are simply not found.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import threading


def digest(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()


class JsonlCache:
    def __init__(self, path: Path, signature: str):
        self.path = Path(path)
        self.signature = signature
        self._lock = threading.Lock()
        self._data: dict[str, object] = {}
        self._cut_line = False  # the file ends without a newline: the next write starts a new line
        if self.path.exists():
            content = self.path.read_text(encoding="utf-8")
            self._cut_line = bool(content) and not content.endswith("\n")
            for line in content.splitlines():
                try:
                    row = json.loads(line)
                    self._data[row["k"]] = row["v"]
                except (ValueError, KeyError, TypeError):
                    continue  # a line cut short by a crash; the entry is redone

    def key(self, text: str) -> str:
        return digest(self.signature, text)

    def get(self, text: str):
        return self._data.get(self.key(text))

    def __contains__(self, text: str) -> bool:
        return self.key(text) in self._data

    def put_many(self, items: dict[str, object]) -> None:
        if not items:
            return
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as handle:
                if self._cut_line:
                    handle.write("\n")
                    self._cut_line = False
                for text, value in items.items():
                    key = self.key(text)
                    self._data[key] = value
                    handle.write(json.dumps({"k": key, "v": value}, ensure_ascii=False) + "\n")
