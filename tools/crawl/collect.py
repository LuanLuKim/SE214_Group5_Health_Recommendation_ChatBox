"""Collect a small, resumable batch of source text. Run with Python 3.12+."""
import argparse
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
from hashlib import sha256
from html.parser import HTMLParser
from http.client import HTTPException
import json
from pathlib import Path
import re
import sqlite3
import sys
import time
from urllib.error import HTTPError
from urllib.parse import unquote, urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parents[1] / ".data" / "crawl"
MIB = 1024 * 1024
BODY_LIMIT = 2 * MIB
MAX_REQUESTS = 200
MAX_BYTES = 150 * MIB
AGENT = "SE214Pilot"
INTERVAL = 3


def now():
    return datetime.now(timezone.utc).isoformat()


def save(path, record):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class MainText(HTMLParser):
    """Keep readable text in main, including headings and table row boundaries."""
    def __init__(self):
        super().__init__()
        self.main = False
        self.hidden = []
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == "main":
            self.main = True
        if self.main and tag in {"script", "style", "nav", "figure", "aside", "button", "svg"}:
            self.hidden.append(tag)
        if self.main and not self.hidden:
            if tag in {"p", "div", "section", "li", "tr", "br", "h1", "h2", "h3", "h4"}:
                self.parts.append("\n")
            elif tag in {"td", "th"}:
                self.parts.append(" | ")

    def handle_endtag(self, tag):
        if self.hidden and self.hidden[-1] == tag:
            self.hidden.pop()
        if tag == "main":
            self.main = False
        if tag in {"p", "li", "tr", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if self.main and not self.hidden:
            self.parts.append(data)

    def text(self):
        lines = (" ".join(line.split()) for line in "".join(self.parts).splitlines())
        return "\n".join(line for line in lines if line)


class Limit(Exception):
    pass


def robots_denials(text):
    # Conservative union of matching groups. A matching Disallow always wins.
    agents, patterns = [], []
    has_rule = seen_agent = False
    for line in text.splitlines():
        field, _, value = line.split("#", 1)[0].partition(":")
        field, value = field.strip().lower(), value.strip()
        if field == "user-agent":
            if has_rule:
                agents = []
            agents.append(value.lower())
            seen_agent, has_rule = True, False
        elif field:
            has_rule = True
            if not any(a == "*" or a and a in AGENT.lower() for a in agents):
                continue
            if field == "disallow" and value:
                pattern = re.escape(unquote(value)).replace(r"\*", ".*")
                if value.endswith("$"):
                    pattern = pattern[:-2] + "$"
                patterns.append(re.compile(pattern))
            if field in {"crawl-delay", "request-rate"}:
                raise ValueError("review source-specific timing before collection")
    if not seen_agent:
        raise ValueError("invalid robots policy")
    return patterns


class Batch:
    def __init__(self, folder, opener):
        self.folder = folder
        self.opener = opener
        self.checkpoint = folder / "state.json"
        self.state = json.loads(self.checkpoint.read_text(encoding="utf-8")) if self.checkpoint.exists() else {
            "requests": 0, "charged_bytes": 0, "attempts": {}, "last_request": 0, "blocked": {}
        }

    def persist(self):
        save(self.checkpoint, self.state)

    def log(self, event, **fields):
        line = json.dumps({"at": now(), "event": event, **fields}, ensure_ascii=False)
        with (self.folder / "events.jsonl").open("a", encoding="utf-8") as stream:
            print(line, file=stream)
        print(line.encode("ascii", "backslashreplace").decode(), flush=True)

    def fetch(self, url, mime):
        state = self.state
        if state["requests"] >= MAX_REQUESTS or state["charged_bytes"] + BODY_LIMIT > MAX_BYTES:
            raise Limit("batch request/byte budget exhausted")
        if sum(f.stat().st_size for f in self.folder.iterdir() if f.is_file()) + 3 * BODY_LIMIT > 250 * MIB:
            raise Limit("disk budget exhausted")
        time.sleep(max(0, state["last_request"] + INTERVAL - time.time()))
        # Reserve the full body before network I/O. An interrupted download stays charged.
        state["requests"] += 1
        state["charged_bytes"] += BODY_LIMIT
        state["last_request"] = time.time()
        self.persist()
        self.log("request", url=url, number=state["requests"])
        request = Request(url, headers={"User-Agent": AGENT + "/1.0", "Accept-Encoding": "identity"})
        with self.opener.open(request, timeout=20) as response:
            if response.headers.get_content_type() != mime or response.headers.get("Content-Encoding", "identity") != "identity":
                raise ValueError("unexpected content type or encoding")
            length = response.headers.get("Content-Length")
            if length and int(length) > BODY_LIMIT:
                raise ValueError("response too large")
            body = bytearray()
            deadline = time.monotonic() + 20
            while len(body) <= BODY_LIMIT:
                if time.monotonic() >= deadline:
                    raise TimeoutError("response deadline")
                chunk = response.read1(min(65536, BODY_LIMIT + 1 - len(body)))
                if not chunk:
                    break
                body.extend(chunk)
            if len(body) > BODY_LIMIT:
                raise ValueError("response too large")
            charset = response.headers.get_content_charset() or "utf-8"
        state["charged_bytes"] -= BODY_LIMIT - len(body)
        self.persist()
        return body.decode(charset)

    def collect(self, rows):
        robots = {}
        counts = Counter()
        for row in rows:
            key, url = row["id"], row["url"]
            parts = urlsplit(url)
            if not re.fullmatch(r"[a-z0-9-]+", key):
                raise ValueError("invalid manifest id")
            target = self.folder / (key + ".json")
            if row["mode"] == "metadata_only":
                result = "metadata_only"
            elif row["mode"] != "collect_text" or parts.scheme != "https" or parts.netloc not in {"www.cdc.gov", "www.nhs.uk"}:
                raise ValueError("unsupported collection scope")
            elif target.exists():
                if json.loads(target.read_text(encoding="utf-8"))["url"] != url:
                    raise ValueError("manifest id reused for a different URL")
                result = "already_saved"
            elif parts.netloc in self.state["blocked"]:
                result = "host_blocked"
            elif self.state["attempts"].get(url, 0) >= 2:
                result = "attempt_limit"
            else:
                try:
                    if parts.netloc not in robots:
                        rules = self.fetch("https://" + parts.netloc + "/robots.txt", "text/plain")
                        robots[parts.netloc] = robots_denials(rules)
                    if robots[parts.netloc] is None:
                        result = "robots_unavailable"
                    elif any(rule.match(unquote(parts.path + ("?" + parts.query if parts.query else ""))) for rule in robots[parts.netloc]):
                        result = "robots_denied"
                    else:
                        self.state["attempts"][url] = self.state["attempts"].get(url, 0) + 1
                        self.persist()
                        html = self.fetch(url, "text/html")
                        parser = MainText()
                        parser.feed(html)
                        parser.close()
                        text = parser.text()
                        if len(text) < 100:
                            raise ValueError("missing main article")
                        if re.search(r"©|all rights reserved|used (?:by|with) permission", text, re.I):
                            raise ValueError("article contains a rights notice: review before retaining")
                        save(target, {**row, "source": parts.netloc, "language": "en", "fetched_at": now(),
                                      "source_updated_at": None, "review_status": "DRAFT",
                                      "text_sha256": sha256(text.encode()).hexdigest(), "text": text})
                        result = "saved"
                except Limit:
                    raise
                except (OSError, HTTPException, ValueError, LookupError) as exc:
                    result = "error"
                    self.log("error", url=url, reason=str(exc))
                    if isinstance(exc, HTTPError) and exc.code in {401, 403, 429, 503}:
                        self.state["blocked"][parts.netloc] = {"at": now(), "status": exc.code,
                                                               "retry_after": exc.headers.get("Retry-After")}
                        self.persist()
                    if parts.netloc not in robots:
                        self.log("stop", reason="robots unavailable or unsupported")
                        robots[parts.netloc] = None
            counts[result] += 1
            self.log(result, id=key, url=url)
        self.log("summary", counts=dict(counts), requests=self.state["requests"],
                 charged_bytes=self.state["charged_bytes"])
        return int(any(counts[k] for k in ("error", "attempt_limit", "host_blocked", "robots_denied")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--status", action="store_true", help="read last summary without network access")
    args = parser.parse_args()
    rows = json.loads((HERE / "sources.json").read_text(encoding="utf-8"))
    ids = [row["id"] for row in rows]
    if len(rows) > 60 or len(set(ids)) != len(ids) or "state" in ids:
        parser.error("Manifest must contain at most 60 articles with distinct non-reserved IDs")
    if args.status:
        events = OUTPUT / "events.jsonl"
        if events.exists():
            with events.open(encoding="utf-8") as stream:
                from collections import deque
                for line in deque(stream, maxlen=12):
                    print(line.rstrip().encode("ascii", "backslashreplace").decode())
        else:
            print("No run yet.")
        return 0
    OUTPUT.mkdir(parents=True, exist_ok=True)
    # SQLite releases the interprocess lock even if Windows kills the process.
    with closing(sqlite3.connect(OUTPUT / "lock.sqlite", timeout=0)) as lock:
        lock.execute("BEGIN EXCLUSIVE")
        batch = Batch(OUTPUT, build_opener(NoRedirect()))
        batch.log("start", urls=len(rows))
        try:
            return batch.collect(rows)
        except (Limit, KeyboardInterrupt) as exc:
            batch.log("stop", reason=str(exc) or "interrupted")
            return 1


if __name__ == "__main__":
    sys.exit(main())

