"""Shared bounded HTTP transport, persistent budgets and robots checks."""
from datetime import datetime, timezone
from http.client import HTTPException
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError
from urllib.parse import unquote, urlsplit
from urllib.request import HTTPRedirectHandler, Request

HERE = Path(__file__).resolve().parents[1]
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

    def fetch_response(self, url, mime):
        """Return bounded response bytes and metadata without losing the raw body."""
        state = self.state
        if state["requests"] >= MAX_REQUESTS or state["charged_bytes"] + BODY_LIMIT > MAX_BYTES:
            raise Limit("batch request/byte budget exhausted")
        if sum(f.stat().st_size for name in ("crawl", "collection", "review") for f in (self.folder.parent / name).rglob("*") if f.is_file()) + 3 * BODY_LIMIT > 250 * MIB:
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
            metadata = {
                "http_status": getattr(response, "status", 200),
                "final_url": response.geturl() if hasattr(response, "geturl") else url,
                "content_type": response.headers.get_content_type(), "charset": charset,
                "content_encoding": response.headers.get("Content-Encoding", "identity"),
            }
        state["charged_bytes"] -= BODY_LIMIT - len(body)
        self.persist()
        return {**metadata, "body": bytes(body)}

    def fetch(self, url, mime):
        response = self.fetch_response(url, mime)
        return response["body"].decode(response["charset"])


class Transport:
    """Bounded requests and persistent retry tasks; adapters never see this class."""
    def __init__(self, batch, sources):
        self.batch, self.sources = batch, sources
        self.robots = {}
        self.started = time.monotonic()
        self.used = 0

    def request(self, url, mime):
        if self.used >= 20 or time.monotonic() - self.started >= 240:
            raise Limit("RUN_LIMIT")
        self.used += 1
        return self.batch.fetch_response(url, mime)

    def permitted(self, url):
        from .config import validate_url
        validate_url(url, self.sources)
        host = urlsplit(url).hostname
        if host in self.batch.state['blocked']:
            raise ValueError('HOST_BLOCKED')
        if host not in self.robots:
            task = self.batch.state.setdefault('robots_tasks_v1', {}).setdefault(host, {'attempts': 0, 'complete': False})
            if task['complete']:
                task.update(attempts=0, complete=False)
            while True:
                if task['attempts'] >= 2:
                    raise ValueError('ATTEMPT_LIMIT')
                task['attempts'] += 1
                self.batch.persist()
                try:
                    response = self.request(f'https://{host}/robots.txt', 'text/plain')
                    self.robots[host] = robots_denials(response['body'].decode(response['charset']))
                    task['complete'] = True
                    self.batch.persist()
                    break
                except (OSError, HTTPException, ValueError) as exc:
                    transient = (isinstance(exc, HTTPError) and exc.code in {500, 502, 504}) or (isinstance(exc, (OSError, HTTPException)) and not isinstance(exc, HTTPError))
                    if not transient:
                        task['attempts'] = 2
                    self.batch.persist()
                    if not transient or task['attempts'] >= 2:
                        raise
                    time.sleep(3)
        parts = urlsplit(url)
        if any(rule.match(unquote(parts.path + ('?' + parts.query if parts.query else ''))) for rule in self.robots[host]):
            raise ValueError('ROBOTS_DENIED')

    def download(self, url):
        from urllib.parse import urljoin
        original, redirects = url, []
        tasks = self.batch.state.setdefault('fetch_tasks_v1', {})
        task = tasks.setdefault(original, {'attempts': 0, 'complete': False})
        if task['complete']:
            task.update(attempts=0, complete=False)
        while True:
            try:
                self.permitted(url)
                while True:
                    if task['attempts'] >= 2:
                        raise ValueError('ATTEMPT_LIMIT')
                    task['attempts'] += 1
                    self.batch.persist()
                    try:
                        response = self.request(url, 'text/html')
                        break
                    except HTTPError as exc:
                        if exc.code in {301, 302, 303, 307, 308}:
                            target = urljoin(url, exc.headers.get('Location', ''))
                            if (urlsplit(target).netloc != urlsplit(url).netloc or target == url or len(redirects) >= 2):
                                raise ValueError('REDIRECT_NEEDS_REVIEW') from None
                            redirects.append({'url': url, 'status': exc.code, 'location': target})
                            url = target
                            # A redirect is not a failed article attempt.
                            task['attempts'] -= 1
                            self.batch.persist()
                            self.permitted(url)
                            continue
                        if exc.code not in {500, 502, 504}:
                            task['attempts'] = 2
                            self.batch.persist()
                            raise
                        if task['attempts'] >= 2:
                            raise
                        time.sleep(3)
                    except (OSError, HTTPException):
                        if task['attempts'] >= 2:
                            raise
                        time.sleep(3)
                task['complete'] = True
                self.batch.persist()
                return {**response, 'url': original, 'final_url': url, 'redirects': redirects, 'fetched_at': now()}
            except HTTPError as exc:
                if exc.code in {401, 403, 429, 503}:
                    self.batch.state['blocked'][urlsplit(url).hostname] = {'at': now(), 'status': exc.code, 'retry_after': exc.headers.get('Retry-After')}
                    self.batch.persist()
                raise
