import io
import tempfile
import unittest
from urllib.error import HTTPError
from email.message import Message
from pathlib import Path
from unittest.mock import patch

import collect


class Reply(io.BytesIO):
    def __init__(self, body, mime):
        super().__init__(body)
        self.headers = Message()
        self.headers["Content-Type"] = mime


class Source:
    def __init__(self, rules, body):
        self.rules = rules
        self.body = body
        self.urls = []

    def open(self, request, timeout):
        self.urls.append(request.full_url)
        if request.full_url.endswith("/robots.txt"):
            return Reply(self.rules, "text/plain")
        return Reply(self.body, "text/html")


class PilotTests(unittest.TestCase):
    row = {"id": "one", "mode": "collect_text", "url": "https://www.cdc.gov/page"}

    def run_case(self, rules, body):
        folder = Path(self.enterContext(tempfile.TemporaryDirectory()))
        source = Source(rules, body)
        self.enterContext(patch.object(collect, "INTERVAL", 0))
        batch = collect.Batch(folder, source)
        return folder, source, batch, batch.collect([self.row])

    def test_restart_and_draft(self):
        html = b"<nav>outside</nav><main><h2>Title</h2><p>" + b"hello " * 30 + b"</p><script>hidden</script></main>"
        folder, source, _, status = self.run_case(b"User-agent: *\nDisallow: /private", html)
        self.assertEqual(status, 0)
        record = collect.json.loads((folder / "one.json").read_text())
        self.assertEqual(record["review_status"], "DRAFT")
        self.assertNotIn("hidden", record["text"])
        self.assertNotIn("outside", record["text"])
        self.assertEqual(collect.Batch(folder, source).collect([self.row]), 0)
        self.assertEqual(len(source.urls), 2)

    def test_robots_denial(self):
        folder, source, _, status = self.run_case(b"User-agent: *\nDisallow: /", b"")
        self.assertEqual(status, 1)
        self.assertEqual(len(source.urls), 1)
        self.assertFalse((folder / "one.json").exists())

    def test_stream_cap_across_restart(self):
        with patch.object(collect, "BODY_LIMIT", 100):
            folder, source, batch, status = self.run_case(b"User-agent: *\nDisallow:", b"x" * 101)
            self.assertEqual(status, 1)
            self.assertFalse((folder / "one.json").exists())
            with patch.object(collect, "MAX_BYTES", batch.state["charged_bytes"] + 99):
                with self.assertRaises(collect.Limit):
                    collect.Batch(folder, source).collect([self.row])
            self.assertEqual(len(source.urls), 2)

    def test_blocked_host_is_skipped_after_restart(self):
        folder = Path(self.enterContext(tempfile.TemporaryDirectory()))
        source = Source(b"", b"")
        with patch.object(source, "open", side_effect=HTTPError(
            "https://www.cdc.gov/robots.txt", 429, "Rate limited", {"Retry-After": "3600"}, None
        )) as fetch:
            self.assertEqual(collect.Batch(folder, source).collect([self.row]), 1)
            self.assertEqual(collect.Batch(folder, source).collect([self.row]), 1)
            self.assertEqual(fetch.call_count, 1)

    def test_applicable_wildcards_and_other_crawlers(self):
        rules = collect.robots_denials(
            "User-agent: AnotherBot\nDisallow: /\nUser-agent: *\nDisallow: /images/*/full$"
        )
        self.assertTrue(any(r.match("/images/1/full") for r in rules))
        self.assertFalse(any(r.match("/conditions/hives/") for r in rules))
