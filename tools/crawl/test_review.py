"""Offline regression tests: lossy extraction, provenance and approval boundaries."""
from copy import deepcopy
from datetime import date
from html import escape
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review


# Small invented document following the pilot layout. This is not medical guidance.
TEXT = """Hives
Example overview for technical testing.
Symptoms of hives
The test list can:
have a first item
have a second item
Here is an image gallery with images and detailed descriptions. Select an image tab to get the bigger version of the image and to access the description.
1: Example skin (thumbnail).
1
If you're not sure it's hives
Example child navigation.
A pharmacist can help with hives
Ask about antihistamine suitability.
This treatment might not be suitable for young children.
Find a pharmacy
Non-urgent advice: Ask for an urgent GP appointment or get help from NHS 111 if:
example condition first
example condition last
Immediate action required: Call 999 if:
example warning first
example warning last
Do not drop this test negation.
Find your nearest A&E
Treatment for hives from a GP
Example treatment text.
You cannot always prevent hives
Keep the following number as content:
5
Page last reviewed: 26 April 2024
Next review due: 26 April 2027"""


def fixture():
    return {"id": "hives", "topic": "allergy", "text": TEXT,
            "url": review.URLS["hives"], "text_sha256": review.digest(TEXT),
            "fetched_at": "2026-11-02T17:09:01+00:00", "note": "Internal test fixture",
            "review_status": "DRAFT"}


def fixture_html(record):
    headings = review.PROFILES["hives"]["headings"]
    blocks = []
    gallery = False
    for line in record["text"].splitlines():
        if line == review.GALLERY_INTRO:
            gallery = True
            blocks.append('<div class="nhsuk-gallery"><p>' + escape(line) + '</p>')
        elif gallery and line in headings:
            blocks.append("</div>")
            gallery = False
            blocks.append("<h2>" + escape(line) + "</h2>")
        elif line == "Hives":
            blocks.append("<h1>Hives</h1>")
        elif line in headings:
            blocks.append("<h2>" + escape(line) + "</h2>")
        else:
            blocks.append("<p>" + escape(line) + "</p>")
    return ('<script type="application/ld+json">'
            '{"@type":"MedicalWebPage","dateModified":"2026-05-01T12:00:00+00:00"}'
            '</script><main>' + "".join(blocks) + '</main>')


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.record = fixture()
        self.doc = review.normalize(self.record, date(2026, 11, 3))
        self.folder = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.html = fixture_html(self.record)
        self.save_snapshot(self.html)

    def save_snapshot(self, html):
        parser = review.MainText()
        parser.feed(html)
        (self.folder / "hives.html").write_text(html, encoding="utf-8")
        (self.folder / "hives.json").write_text(json.dumps({
            "url": self.record["url"], "checked_at": "2026-11-03T06:00:00+00:00",
            "html_sha256": review.digest(html), "extracted_text_sha256": review.digest(parser.text()),
        }), encoding="utf-8")

    def checks(self):
        return {c["name"]: c["passed"] for c in review.check_document(self.record, self.doc, self.folder)}

    def test_complete_fixture_passes_independent_dom_and_provenance(self):
        checks = self.checks()
        self.assertTrue(all(checks.values()), checks)

    def test_warning_list_and_negation_are_one_atomic_chunk(self):
        warnings = [c for c in self.doc["chunks"] if "emergency" in c["scope_tags"]]
        self.assertEqual(len(warnings), 1)
        chunk = warnings[0]
        for line in ("Call 999 if:", "example warning first", "example warning last", "Do not drop this test negation."):
            self.assertIn(line, chunk["text"])
        self.assertFalse(review.eligible(chunk))

    def test_medication_caveat_stays_with_its_context(self):
        chunk = next(c for c in self.doc["chunks"] if c["heading"] == "A pharmacist can help with hives")
        self.assertIn("Ask about antihistamine suitability.", chunk["text"])
        self.assertIn("This treatment might not be suitable for young children.", chunk["text"])
        self.assertIn("medication", chunk["scope_tags"])
        self.assertIn("paediatric", chunk["scope_tags"])

    def test_gallery_removal_does_not_remove_numeric_content(self):
        self.assertEqual(sum(row["disposition"] == "remove:gallery_control" for row in self.doc["removed_lines"]), 3)
        self.assertIn("\n5", self.doc["chunks"][-1]["text"])

    def test_every_input_line_is_reconstructable(self):
        self.assertEqual("\n".join(row["text"] for row in self.doc["line_audit"]), TEXT)
        self.assertTrue(all(row["disposition"].startswith("remove:") for row in self.doc["removed_lines"]))

    def test_missing_or_duplicate_heading_fails(self):
        for text in (TEXT.replace("Symptoms of hives\n", ""), TEXT.replace("Symptoms of hives\n", "Symptoms of hives\nSymptoms of hives\n")):
            with self.subTest(text=text):
                record = {**self.record, "text": text}
                with self.assertRaises(ValueError):
                    review.normalize(record, date(2026, 11, 3))

    def test_unknown_document_fails(self):
        with self.assertRaises(KeyError):
            review.normalize({**self.record, "id": "new-source"}, date(2026, 11, 3))

    def test_missing_review_date_fails(self):
        with self.assertRaises(ValueError):
            review.normalize({**self.record, "text": TEXT.replace("Next review due: 26 April 2027", "")}, date(2026, 11, 3))

    def test_overdue_is_explicit_and_all_sections_are_held(self):
        doc = review.normalize(self.record, date(2027, 4, 27))
        self.assertTrue(doc["source_review_overdue"])
        self.assertTrue(all("overdue_review" in c["scope_tags"] for c in doc["chunks"]))

    def test_modified_date_is_separate_from_review_and_fetch(self):
        self.checks()
        self.assertEqual(self.doc["source_updated_at"], "2026-05-01T12:00:00+00:00")
        self.assertEqual(self.doc["source_last_reviewed"], "2024-04-26")
        self.assertEqual(self.doc["fetched_at"], self.record["fetched_at"])

    def test_raw_hash_tampering_fails(self):
        self.record["text_sha256"] = "0" * 64
        self.assertFalse(self.checks()["raw_hash"])
        self.assertFalse(self.checks()["chunk_provenance"])

    def test_changed_source_body_fails(self):
        self.save_snapshot(self.html.replace("example warning last", "CHANGED warning"))
        self.assertFalse(self.checks()["source_text_match"])
        self.assertFalse(self.checks()["independent_dom_coverage"])

    def test_independent_parser_detects_aside_lost_by_crawler(self):
        self.save_snapshot(self.html.replace("</main>", "<aside>Important omitted condition</aside></main>"))
        checks = self.checks()
        self.assertTrue(checks["source_text_match"])
        self.assertFalse(checks["independent_dom_coverage"])

    def test_unrecognized_source_heading_fails(self):
        self.save_snapshot(self.html.replace("</main>", "<h2>New source heading</h2></main>"))
        self.assertFalse(self.checks()["source_heading_structure"])

    def test_snapshot_tampering_fails_even_if_visible_text_is_same(self):
        (self.folder / "hives.html").write_text(self.html + "<!-- changed -->", encoding="utf-8")
        self.assertFalse(self.checks()["snapshot_hash"])

    def test_missing_snapshot_fails_closed(self):
        (self.folder / "hives.html").unlink()
        self.assertFalse(self.checks()["source_snapshot_available"])

    def test_chunk_ids_are_stable_and_change_with_content(self):
        again = review.normalize(self.record, date(2026, 11, 3))
        self.assertEqual(self.doc["chunks"], again["chunks"])
        changed = review.normalize({**self.record, "text": TEXT.replace("example warning last", "changed warning")}, date(2026, 11, 3))
        self.assertEqual(self.doc["chunks"][0]["id"], changed["chunks"][0]["id"])
        old_warning = next(c for c in self.doc["chunks"] if "emergency" in c["scope_tags"])
        new_warning = next(c for c in changed["chunks"] if "emergency" in c["scope_tags"])
        self.assertNotEqual(old_warning["id"], new_warning["id"])

    def test_candidate_never_implicitly_approves(self):
        doc = review.normalize({**self.record, "review_status": "APPROVED", "verified_at": "2026-11-03"}, date(2026, 11, 3))
        self.assertEqual(doc["review_status"], "DRAFT")
        self.assertTrue(all(not review.eligible(c) for c in doc["chunks"]))

    def test_approval_gate_requires_all_explicit_conditions(self):
        candidate = deepcopy(self.doc["chunks"][0])
        complete = {**candidate, "review_status": "APPROVED", "verified_at": "2026-11-03T06:00:00Z",
                    "rights_reviewed": True, "scope_status": "APPROVED_FOR_VN_ADULTS", "scope_tags": [],
                    "technical_status": "PASS", "source_checked_at": "2026-11-03T06:00:00Z",
                    "purpose": "PRODUCTION", "rag_eligible": True}
        self.assertTrue(review.eligible(complete))
        for field in ("review_status", "verified_at", "rights_reviewed", "scope_status", "scope_tags",
                      "technical_status", "source_checked_at", "purpose", "rag_eligible"):
            broken = dict(complete)
            del broken[field]
            with self.subTest(field=field):
                self.assertFalse(review.eligible(broken))
        self.assertFalse(review.eligible({**complete, "scope_tags": ["medication"]}))

    def test_html_report_escapes_source_content(self):
        self.doc["title_vi_label"] = '<script>alert("x")</script>'
        self.doc["technical_checks"] = []
        self.doc["technical_status"] = "PASS"
        summary = {"as_of": "2026-11-03", "documents_passed": 1, "documents": 1,
                   "candidate_chunks": 8, "eligible_chunks": 0, "checks_passed": 0, "checks_total": 0}
        review.render_html([self.doc], summary, self.folder)
        html = (self.folder / "index.html").read_text(encoding="utf-8")
        self.assertNotIn('<script>alert("x")</script>', html)
        self.assertIn("&lt;script&gt;", html)


if __name__ == "__main__":
    unittest.main()
