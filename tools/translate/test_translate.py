"""Test suite for tools/translate: localisation, number checks, segments, consensus,
cache, engines, quality gate, LLM post-editor, pipeline and the CLI.

Run:
    python -B tools/translate/test_translate.py

No model is loaded, torch is not needed, nothing goes to the network and the
real .data/ directory is never read or written: engines and the LLM client are
fakes, every file lives in a temporary directory.

Tests marked "REGRESSION" reproduce defects this suite found in the code (all
fixed); they guard against the defect coming back and must not be weakened.

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
import threading
import unittest
from unittest import mock
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parent))

import run  # noqa: E402
from translator.cache import JsonlCache, digest  # noqa: E402
from translator.consensus import agreement, chrf, pick, support  # noqa: E402
from translator.engines import KINDS, TranslationEngine, get_engine, resolve_device  # noqa: E402
from translator.glossary import (  # noqa: E402
    Rules, compile_names, find_names, load_medicine_names, name_kept, numbers_in)
from translator.pipeline import (  # noqa: E402
    Unit, build_units, consensus_lines, engine_statistics, finalise, machine_translate, refine)
from translator.quality import HARD_CHECKS, check_line, check_quality, count_bullets  # noqa: E402
from translator.refine import LLMClient, Stop, build_item, parse_answer, system_prompt  # noqa: E402
from translator.segments import harmonise, split_marker, split_sentences  # noqa: E402

CONFIG = run.load_config()
RULES = Rules.load()
QUALITY = CONFIG["quality"]

# UK terms in any case; the target-side patterns of localisation.json are case-sensitive on purpose
UK_TERMS = re.compile(r"(?<![\d.,])(?:999|111)(?![\d.,]*\d)|\bGPs?\b|\bA\s?&\s?E\b")
_DIGITS = re.compile(r"\d+(?:[.,]\d+)*\s*")


def quiet(*_) -> None:
    """Log sink for pipeline stages."""


def tmpdir(test: unittest.TestCase) -> Path:
    path = Path(tempfile.mkdtemp(prefix="translate-test-"))
    test.addCleanup(shutil.rmtree, path, True)
    return path


# -- fakes --------------------------------------------------------------------

# Vietnamese for the (already localised) English segments used below
GOOD = {
    "Treatment for a cold": "Điều trị cảm lạnh",
    "Take 2 paracetamol tablets every 4 to 6 hours.": "Uống 2 viên paracetamol mỗi 4 đến 6 giờ.",
    "Do not take more than 8 tablets in 24 hours.": "Không uống quá 8 viên trong 24 giờ.",
    "Immediate action required: Call the emergency number 115 or go to the hospital emergency department if:":
        "Cần xử trí ngay: Gọi số cấp cứu 115 hoặc đến khoa cấp cứu của bệnh viện nếu:",
    "your temperature is 39.5C or higher": "nhiệt độ của bạn từ 39,5 độ C trở lên",
    "you have chest pain": "bạn bị đau ngực",
    "Drink plenty of fluids to stay hydrated.": "Uống nhiều nước để cơ thể đủ nước.",
    "Cold and flu": "Cảm lạnh và cúm",
    "When to get help": "Khi nào cần trợ giúp",
    "Treatment": "Điều trị",
    "Flu": "Cúm",
    "Self-care": "Tự chăm sóc",
}


def good(text: str) -> str:
    return GOOD.get(text, "Bản dịch: " + text)


def paraphrase(text: str) -> str:
    return good(text).replace("Uống", "Hãy uống").replace(" của ", " ")


def dropper(text: str) -> str:
    """An engine that loses every number."""
    return " ".join(_DIGITS.sub("", good(text)).split())


class FakeEngine:
    """Duck-typed engine: .key, .model_id, .signature, .translate(texts, on_batch), .unload()."""

    def __init__(self, key: str, fn=good, batch_size: int = 3, fail_after: int | None = None):
        self.key = key
        self.model_id = f"fake/{key}"
        self.signature = json.dumps({"fake": key})
        self.fn = fn
        self.batch_size = batch_size
        self.fail_after = fail_after
        self.calls: list[list[str]] = []
        self.unloaded = 0

    @property
    def seen(self) -> list[str]:
        return [t for call in self.calls for t in call]

    def translate(self, texts, on_batch=None):
        self.calls.append(list(texts))
        out = []
        for start in range(0, len(texts), self.batch_size):
            if self.fail_after is not None and start >= self.fail_after:
                raise RuntimeError("engine crashed")
            batch = texts[start:start + self.batch_size]
            done = {t: self.fn(t) for t in batch}
            out += [done[t] for t in batch]
            if on_batch:
                on_batch(done)
        return out

    def unload(self):
        self.unloaded += 1


class FakeLLM:
    """Duck-typed LLM client (.model, .effort, .chat). ``script(n, items)`` returns the answer text."""

    def __init__(self, script=None, model: str = "fake-llm", effort: str = ""):
        self.model, self.effort = model, effort
        self.script = script or self.copy_first_draft
        self.payloads: list[list[dict]] = []
        self._lock = threading.Lock()

    @staticmethod
    def line_for(line: dict) -> str:
        """A correct line, whatever the drafts say."""
        return harmonise(line["en"], good(RULES.rewrite_source(line["en"])))

    @classmethod
    def copy_first_draft(cls, n: int, items: list[dict]) -> str:
        return json.dumps([{"id": item["id"], "vi": [cls.line_for(line) for line in item["lines"]]}
                           for item in items], ensure_ascii=False)

    @property
    def calls(self) -> int:
        return len(self.payloads)

    def chat(self, system: str, user: str) -> str:
        items = json.loads(user)
        with self._lock:
            self.payloads.append(items)
            n = len(self.payloads)
        return self.script(n, items)


def make_unit(uid: str, lines: list[str], drafts: dict[str, list[str]] | None = None, tags=(), hint=(),
              kept=()) -> Unit:
    unit = Unit(id=uid, kind="chunk", title="Cold and flu", section="Treatment", lines=list(lines), tags=list(tags))
    unit.drafts = dict(drafts or {})
    unit.hint_names = list(hint)
    unit.kept_names = list(kept)
    return unit


def chunk(cid: str, article: str, text: str, *, heading: str = "", path=(), title: str = "Cold and flu",
          tags=(), language: str = "en", section: str = "s00") -> dict:
    return {"id": cid, "chunk_key": f"{article}-{section}-{cid}", "article_id": article,
            "article_uuid": f"uuid-{article}", "section_id": f"{article}-{section}", "heading_path": list(path),
            "heading": heading, "title": title, "text": text, "language": language, "scope_tags": list(tags),
            "topic": "respiratory", "corpus": "rag", "review_status": "DRAFT", "text_sha256": "0" * 64,
            "word_count": len(text.split()), "source_url": f"https://example.org/{article}", "source_name": "NHS"}


A1_TEXT = ("Treatment for a cold\nTake 2 paracetamol tablets every 4 to 6 hours.\n"
           "Do not take more than 8 tablets in 24 hours.")
A2_TEXT = "Immediate action required: Call 999 or go to A&E if:\n• your temperature is 39.5C or higher\n• you have chest pain"
B1_TEXT = "Drink plenty of fluids to stay hydrated."
V1_TEXT = "Bạn nên uống đủ nước.\nNghỉ ngơi nhiều."


def sample_chunks() -> list[dict]:
    return [
        chunk("c-a1", "art1", A1_TEXT, heading="Treatment", section="s01"),
        chunk("c-a2", "art1", A2_TEXT, heading="When to get help", tags=["emergency", "foreign_service"],
              section="s02"),
        chunk("c-v1", "art2", V1_TEXT, heading="Lời khuyên", title="Cảm lạnh", language="vi"),
        chunk("c-b1", "art3", B1_TEXT, heading="Self-care", title="Flu"),
    ]


def sample_db_rows() -> list[dict]:
    return [
        {"id": "uuid-art1", "title": "Cold and flu", "topic": "respiratory", "source_name": "NHS",
         "source_url": "https://example.org/art1",
         "content": A1_TEXT + "\n\nWhen to get help\n" + A2_TEXT},
        {"id": "uuid-art2", "title": "Cảm lạnh", "topic": "respiratory", "source_name": "Bộ Y tế",
         "source_url": "https://example.org/art2", "content": V1_TEXT},
        {"id": "uuid-art3", "title": "Flu", "topic": "respiratory", "source_name": "NHS",
         "source_url": "https://example.org/art3", "content": "Self-care\n" + B1_TEXT},
    ]


# =============================================================================
# glossary: numbers
# =============================================================================

class TestNumbers(unittest.TestCase):

    def missing(self, source: str, target: str) -> list[str]:
        return RULES.check_numbers(source, target)[0]

    def added(self, source: str, target: str) -> list[str]:
        return RULES.check_numbers(source, target)[1]

    def test_numbers_in_strips_separators(self):
        self.assertEqual(numbers_in("1,000 mg, 37.5C, 2-3 days, 24/7"), ["1000", "375", "2", "3", "24", "7"])

    def test_decimal_styles_are_equivalent(self):
        self.assertEqual(self.missing("a temperature of 37.5C", "nhiệt độ 37,5 độ C"), [])
        self.assertEqual(self.missing("a temperature of 37,5C", "nhiệt độ 37.5 độ C"), [])

    def test_thousand_separators_are_equivalent(self):
        self.assertEqual(self.missing("up to 1,000mg a day", "tối đa 1.000mg mỗi ngày"), [])
        self.assertEqual(self.missing("up to 1,000mg a day", "tối đa 1000mg mỗi ngày"), [])

    def test_changed_dose_fails(self):
        self.assertEqual(self.missing("Take 2 tablets", "Uống 3 viên"), ["2"])

    def test_dropped_age_fails(self):
        self.assertEqual(self.missing("Do not give aspirin to children under 16.", "Không cho trẻ em dùng aspirin."),
                         ["16"])

    def test_changed_temperature_fails(self):
        self.assertEqual(self.missing("a temperature of 38C or above", "nhiệt độ từ 39 độ C trở lên"), ["38"])

    def test_changed_duration_fails(self):
        self.assertEqual(self.missing("for 3 days", "trong 5 ngày"), ["3"])

    def test_range_needs_both_ends(self):
        self.assertEqual(self.missing("every 4 to 6 hours", "mỗi 4 giờ"), ["6"])
        self.assertEqual(self.missing("2–3 days", "2 đến 3 ngày"), [])

    def test_lost_decimal_point_fails(self):
        # REGRESSION: separators were stripped, so 1.5 ml and 15 ml (a tenfold dose) looked the same
        self.assertEqual(self.missing("Give 1.5ml of the liquid", "Cho uống 15ml dung dịch"), ["1.5"])
        self.assertEqual(self.missing("Give 1.5ml of the liquid", "Cho uống 1,5ml dung dịch"), [])

    def test_decimal_that_looks_like_111_is_still_required(self):
        # REGRESSION: "11.1" is normalised to "111" and number_map then drops it, so losing the
        # diabetes threshold 11.1 mmol/L is not detected
        self.assertTrue(self.missing("a blood sugar level of 11.1mmol/L or above", "đường huyết cao"))

    def test_decimal_that_looks_like_999_is_not_mapped_to_115(self):
        # REGRESSION: "9.99" is normalised to "999" and then required as "115"
        self.assertEqual(self.missing("It costs 9.99 pounds", "Giá 9,99 bảng"), [])

    def test_999_must_become_115(self):
        self.assertEqual(self.missing("Call 999 now", "Gọi 115 ngay"), [])
        self.assertEqual(self.missing("Call 999 now", "Gọi cấp cứu ngay"), ["115"])
        self.assertEqual(self.missing("Call 999 now", "Gọi 999 ngay"), ["115"])

    def test_111_is_not_required(self):
        self.assertEqual(RULES.required_numbers("call 111 for advice"), set())
        self.assertEqual(self.missing("call 111 for advice", "liên hệ bác sĩ để được tư vấn"), [])

    def test_vietnamese_number_words_count_as_the_number(self):
        self.assertEqual(self.missing("Take 2 tablets", "Uống hai viên"), [])
        self.assertEqual(self.missing("for 10 days", "trong mười ngày"), [])

    def test_vietnamese_word_for_year_does_not_stand_for_5(self):
        # REGRESSION: "năm" (five) is also "year"; a dropped 5 passes when the line says "mỗi năm"
        self.assertEqual(self.missing("Repeat the test every 5 years.", "Làm lại xét nghiệm mỗi năm."), ["5"])

    def test_english_number_words_are_tolerated(self):
        self.assertEqual(self.added("Take two tablets twice a day", "Uống 2 viên, 2 lần mỗi ngày"), [])
        self.assertEqual(self.added("Take one tablet once a day", "Uống 1 viên mỗi ngày 1 lần"), [])

    def test_ordinals_may_be_spelled_out_or_kept(self):
        self.assertEqual(RULES.check_numbers("on the 3rd day", "vào ngày thứ ba"), ([], []))
        self.assertEqual(RULES.check_numbers("on the 3rd day", "vào ngày thứ 3"), ([], []))

    def test_allowed_added_numbers(self):
        self.assertEqual(self.added("a newborn baby", "trẻ sơ sinh (dưới 28 ngày tuổi)"), [])
        self.assertEqual(self.added("call the emergency number", "gọi cấp cứu 115"), [])

    def test_invented_number_is_reported_as_added(self):
        self.assertEqual(self.added("Take the tablets with water", "Uống 3 viên với nước"), ["3"])


# =============================================================================
# glossary: localisation rewrites (English -> neutral English)
# =============================================================================

class TestLocalisationRewrites(unittest.TestCase):

    CORPUS = [
        "Immediate action required: Call 999 or go to A&E if:",
        "Information: Do not drive to A&E. Ask someone to drive you or call 999 and ask for an ambulance.",
        "You can call 111 or get help from 111 online.",
        "Urgent advice: Ask for an urgent GP appointment or get help from NHS 111 if:",
        "Non-urgent advice: See a GP if:",
        "ask the reception staff at your GP surgery",
        "GPs do not recommend antibiotics for colds",
        "The person you speak to at 999 will give you advice about what to do.",
        "111 will tell you what to do.",
        "If you're not sure what to do, call 111.",
        "Your GP's surgery can help.",
        "Go to your nearest A&E department.",
        "Get help from NHS 111 online.",
        "Phone 999 if someone has stopped breathing.",
    ]

    def rewrite(self, text: str) -> str:
        return RULES.rewrite_source(text)

    def test_no_uk_service_left_in_english(self):
        for text in self.CORPUS:
            with self.subTest(text=text):
                out = self.rewrite(text)
                self.assertIsNone(UK_TERMS.search(out), out)
                self.assertEqual(RULES.forbidden_in(out), [], out)

    def test_999_becomes_115(self):
        for text in self.CORPUS:
            if "999" in text:
                with self.subTest(text=text):
                    self.assertIn("115", self.rewrite(text))

    def test_sentence_start_stays_capitalised(self):
        for text in self.CORPUS:
            with self.subTest(text=text):
                out = self.rewrite(text)
                self.assertEqual(out[:1].isupper(), not text[:1].islower(), out)

    def test_call_999_or_go_to_ae(self):
        out = self.rewrite("Immediate action required: Call 999 or go to A&E if:")
        self.assertTrue(out.startswith("Immediate action required: Call the emergency number 115"), out)
        self.assertIn("or go to the hospital emergency department if:", out)

    def test_mid_sentence_replacements_are_lower_case(self):
        out = self.rewrite("Information: Do not drive to A&E. Ask someone to drive you or call 999 and ask for an ambulance.")
        self.assertIn("Do not drive to the hospital emergency department.", out)
        self.assertIn("drive you or call the emergency number 115 and ask", out)
        self.assertIn("staff at your clinic", self.rewrite("ask the reception staff at your GP surgery"))
        self.assertIn("See a doctor if:", self.rewrite("Non-urgent advice: See a GP if:"))

    def test_capital_after_full_stop(self):
        out = self.rewrite("Call 999. A&E staff will help.")
        self.assertEqual(out, "Call the emergency number 115. The hospital emergency department staff will help.")

    def test_lower_case_list_item_stays_lower_case(self):
        self.assertEqual(self.rewrite("call 999 if they are not breathing"),
                         "call the emergency number 115 if they are not breathing")

    def test_111_phrasings(self):
        self.assertEqual(self.rewrite("You can call 111 or get help from 111 online."),
                         "You can contact a doctor or the nearest health facility for advice.")
        out = self.rewrite("Urgent advice: Ask for an urgent GP appointment or get help from NHS 111 if:")
        self.assertTrue(out.startswith("Urgent advice: See a doctor urgently"), out)
        self.assertTrue(self.rewrite("111 will tell you what to do.").startswith("The "))
        self.assertTrue(self.rewrite("GPs do not recommend antibiotics for colds").startswith("Doctors do not"))

    def test_nhs_111_online_leaves_no_dangling_online(self):
        # REGRESSION: "get help from NHS 111" is rewritten before "111 online" is seen, leaving
        # "contact a doctor or the nearest health facility online."
        out = self.rewrite("Get help from NHS 111 online.")
        self.assertNotIn("facility online", out)

    def test_possessive_gp(self):
        self.assertEqual(self.rewrite("your GP's advice"), "your doctor's advice")
        self.assertEqual(self.rewrite("your GP’s advice"), "your doctor’s advice")

    def test_rules_do_not_fire_inside_other_numbers(self):
        for text in ("Code 2111", "9990 people", "1111 cases", "it was 19991"):
            with self.subTest(text=text):
                self.assertEqual(self.rewrite(text), text)

    def test_999_inside_a_thousand_separated_number_is_kept(self):
        # REGRESSION: \b999\b matches after the comma: "1,999" becomes "1,115"
        self.assertEqual(self.rewrite("It costs 1,999 pounds"), "It costs 1,999 pounds")

    def test_111_inside_a_thousand_separated_number_is_kept(self):
        # REGRESSION: "1,111 people" becomes "1,the health advice service people"
        self.assertEqual(self.rewrite("1,111 people were asked"), "1,111 people were asked")

    def test_gps_device_is_not_a_doctor(self):
        # REGRESSION: rules are compiled with re.I, so \bGPs\b matches "GPS"
        self.assertEqual(self.rewrite("Use the GPS on your phone"), "Use the GPS on your phone")

    def test_curly_apostrophes_and_dashes_are_untouched(self):
        text = "Don’t take ibuprofen – it’s not suitable if you’re pregnant"
        self.assertEqual(self.rewrite(text), text)

    def test_rules_from_dict(self):
        rules = Rules.from_dict({"version": "t", "source_rewrites": [{"pattern": r"\bfoo\b", "replace": "bar"}],
                                 "number_map": {"7": "8"}, "number_words": {"Seven": "7"}})
        self.assertEqual(rules.rewrite_source("Foo and foo"), "Bar and bar")
        self.assertEqual(rules.required_numbers("7 days"), {"8"})
        self.assertIn("7", rules.tolerated_numbers("SEVEN days"))
        self.assertEqual(rules.forbidden_in("anything"), [])


class TestTargetTerms(unittest.TestCase):

    def test_forbidden_terms(self):
        self.assertEqual(RULES.forbidden_in("Gọi 999 ngay."), ["999"])
        self.assertEqual(RULES.forbidden_in("Gọi 111."), ["111"])
        self.assertEqual(RULES.forbidden_in("Gặp bác sĩ GP của bạn."), ["GP"])
        self.assertEqual(RULES.forbidden_in("Hỏi các GPs."), ["GP"])
        self.assertEqual(RULES.forbidden_in("Đến A&E."), ["A&E"])
        self.assertEqual(RULES.forbidden_in("Đến A & E."), ["A&E"])

    def test_forbidden_numbers_only_as_whole_numbers(self):
        for text in ("Giá 1.999.000 đồng", "1,999 người", "mã 2111", "11,1 mmol/L", "Gọi 115"):
            with self.subTest(text=text):
                self.assertEqual(RULES.forbidden_in(text), [])

    def test_discouraged_terms(self):
        self.assertEqual(RULES.discouraged_in("Trang web NHS (tại Anh)"), ["NHS"])
        self.assertEqual(RULES.discouraged_in("trẻ nhũ nhi"), ["nhũ nhi"])
        self.assertEqual(RULES.discouraged_in("Gọi 0300 123 4567"), ["UK phone number"])
        self.assertEqual(RULES.discouraged_in("Gọi 116 123"), ["UK phone number"])
        self.assertEqual(RULES.discouraged_in("Gọi cấp cứu 115"), [])


# =============================================================================
# glossary: medicine names
# =============================================================================

class TestMedicineNames(unittest.TestCase):

    def test_compile_and_find(self):
        pattern = compile_names(["paracetamol", "codamol", "co-codamol", "", "Ibuprofen"])
        self.assertEqual(find_names("Take Co-codamol, not codamol or IBUPROFEN. Paracetamol, paracetamol.", pattern),
                         ["co-codamol", "codamol", "ibuprofen", "paracetamol"])
        self.assertEqual(find_names("paracetamolum", pattern), [])
        self.assertIsNone(compile_names([]))
        self.assertEqual(find_names("paracetamol", None), [])

    def test_name_kept(self):
        self.assertTrue(name_kept("paracetamol", "Uống Paracetamol"))
        self.assertTrue(name_kept("hydrocortisone", "kem hydrocortison"))
        self.assertTrue(name_kept("folic acid", "uống axit folic"))
        self.assertTrue(name_kept("folic acid", "uống acid folic"))
        self.assertFalse(name_kept("paracetamol", "uống thuốc hạ sốt"))
        self.assertFalse(name_kept("folic acid", "uống vitamin B9"))

    def test_load_without_normalizer_is_empty(self):
        root = tmpdir(self)
        self.assertEqual(load_medicine_names(root, root / "urls.txt", root / "collection"), frozenset())

    def _fake_normalizer(self, body: str) -> Path:
        root = tmpdir(self)
        (root / "tools" / "normalize").mkdir(parents=True)
        (root / "tools" / "normalize" / "run.py").write_text(body, encoding="utf-8")
        (root / "urls.txt").write_text("", encoding="utf-8")
        return root

    def test_load_through_the_normalizer(self):
        root = self._fake_normalizer("def load_medicine_names(index_urls, collection):\n"
                                     "    return {'paracetamol', 'ibuprofen'}\n")
        before = list(sys.path)
        names = load_medicine_names(root, root / "urls.txt", root / "collection")
        self.assertEqual(names, frozenset({"paracetamol", "ibuprofen"}))
        self.assertEqual(sys.path, before)

    def test_broken_normalizer_is_ignored(self):
        root = self._fake_normalizer("raise RuntimeError('broken')\n")
        before = list(sys.path)
        self.assertEqual(load_medicine_names(root, root / "urls.txt", root / "collection"), frozenset())
        self.assertEqual(sys.path, before)

    def test_missing_index_file_is_empty(self):
        root = self._fake_normalizer("def load_medicine_names(a, b):\n    return {'x'}\n")
        self.assertEqual(load_medicine_names(root, root / "absent.txt", root / "collection"), frozenset())


# =============================================================================
# segments
# =============================================================================

class TestSegments(unittest.TestCase):

    def test_split_marker(self):
        cases = {
            "• a cough": ("• ", "a cough"),
            "- a cough": ("- ", "a cough"),
            "* a cough": ("* ", "a cough"),
            "– a cough": ("– ", "a cough"),
            "1. Wash your hands": ("1. ", "Wash your hands"),
            "10) Dry them": ("10) ", "Dry them"),
            "  • indented": ("  • ", "indented"),
            "37.5C is a high temperature": ("", "37.5C is a high temperature"),
            "Overview - Contact dermatitis": ("", "Overview - Contact dermatitis"),
            "•no space": ("", "•no space"),
            "": ("", ""),
        }
        for line, expected in cases.items():
            with self.subTest(line=line):
                self.assertEqual(split_marker(line), expected)

    def test_count_bullets(self):
        self.assertEqual(count_bullets("Heading\n• a\n• b\n1. c\nplain"), 3)
        self.assertEqual(count_bullets(""), 0)

    def test_short_line_stays_whole(self):
        self.assertEqual(split_sentences("Take it. Rest.", 60), ["Take it. Rest."])

    def test_long_line_is_split_and_rejoins(self):
        text = ("Paracetamol can help with pain. It is safe for most people. Take 2 tablets every 4 hours. "
                "Do not take more than 8 tablets in 24 hours. Speak to a pharmacist if you are not sure.")
        parts = split_sentences(text, 10)
        self.assertGreater(len(parts), 1)
        self.assertEqual(" ".join(parts), text)
        for part in parts:
            self.assertLessEqual(len(part.split()), 10)

    def test_decimals_are_not_sentence_ends(self):
        text = "A temperature of 37.5C is normal for most people and babies. " * 3
        self.assertTrue(all("37.5C" in p for p in split_sentences(text.strip(), 12)))

    def test_one_long_sentence_is_kept_whole(self):
        text = " ".join(["word"] * 80)
        self.assertEqual(split_sentences(text, 60), [text])

    def test_harmonise(self):
        self.assertEqual(harmonise("a cough", "Ho."), "ho")
        self.assertEqual(harmonise("a cough.", "Ho."), "ho.")
        self.assertEqual(harmonise("A cough", "Ho."), "Ho")
        self.assertEqual(harmonise("You have a cough", "Bạn bị ho."), "Bạn bị ho")
        self.assertEqual(harmonise("and then...", "và sau đó..."), "và sau đó...")
        self.assertEqual(harmonise("covid", "COVID-19."), "COVID-19")
        self.assertEqual(harmonise("a cough", "  Ho   khan  "), "ho khan")
        self.assertEqual(harmonise("a cough", ""), "")
        self.assertEqual(harmonise("", "Ho."), "Ho.")
        self.assertEqual(harmonise("is it serious?", "Có nghiêm trọng không?"), "có nghiêm trọng không?")


# =============================================================================
# consensus
# =============================================================================

class TestConsensus(unittest.TestCase):

    def test_chrf_bounds(self):
        self.assertEqual(chrf("uống nước", "uống nước"), 1.0)
        self.assertEqual(chrf("", "uống nước"), 0.0)
        self.assertEqual(chrf("uống nước", "  "), 0.0)
        value = chrf("uống nhiều nước", "uống nước")
        self.assertGreater(value, 0.0)
        self.assertLess(value, 1.0)
        self.assertGreater(chrf("uống nhiều nước", "uống nước"), chrf("đi ngủ sớm", "uống nước"))

    def test_agreement_and_support(self):
        self.assertEqual(agreement("x", []), 1.0)
        self.assertEqual(agreement("x", ["", " "]), 1.0)
        self.assertEqual(support("uống nước", ["uống nước", "khác hẳn"]), 1.0)
        self.assertEqual(support("x", []), 1.0)

    def test_outlier_loses(self):
        best, scores = pick({"a": "Uống 2 viên mỗi 4 giờ", "b": "Uống 2 viên mỗi 4 tiếng",
                             "c": "Bạn nên đi khám bác sĩ ngay"})
        self.assertIn(best, ("a", "b"))
        self.assertLess(scores["c"], scores["a"])

    def test_invalid_candidate_is_not_chosen(self):
        candidates = {"drop": "Uống viên mỗi giờ", "x": "Uống 2 viên mỗi 4 giờ", "y": "Uống viên mỗi giờ nhé"}
        best, _ = pick(candidates, {"drop": False, "x": True, "y": False})
        self.assertEqual(best, "x")

    def test_all_invalid_still_picks_a_non_empty_one(self):
        best, _ = pick({"a": "", "b": "Uống viên"}, {"a": False, "b": False})
        self.assertEqual(best, "b")
        best, _ = pick({"a": "", "b": ""})
        self.assertEqual(best, "a")

    def test_empty_candidate_is_never_preferred(self):
        best, _ = pick({"a": "", "b": "Uống nước", "c": "Uống nhiều nước"})
        self.assertIn(best, ("b", "c"))

    def test_first_engine_wins_ties(self):
        self.assertEqual(pick({"a": "Uống nước", "b": "Uống nước"})[0], "a")
        self.assertEqual(pick({"b": "Uống nước", "a": "Ăn cơm"})[0], "b")


# =============================================================================
# cache
# =============================================================================

class TestCache(unittest.TestCase):

    def test_roundtrip_and_reload(self):
        path = tmpdir(self) / "sub" / "e.jsonl"
        cache = JsonlCache(path, "sig")
        cache.put_many({"Take 2 tablets.": "Uống 2 viên.", "A cough": ""})
        self.assertEqual(cache.get("Take 2 tablets."), "Uống 2 viên.")
        reloaded = JsonlCache(path, "sig")
        self.assertIn("Take 2 tablets.", reloaded)
        self.assertEqual(reloaded.get("A cough"), "")
        self.assertIn("Uống 2 viên.", path.read_text(encoding="utf-8"))

    def test_signature_separates_entries(self):
        path = tmpdir(self) / "e.jsonl"
        JsonlCache(path, "sig-1").put_many({"x": "y"})
        self.assertNotIn("x", JsonlCache(path, "sig-2"))
        self.assertIsNone(JsonlCache(path, "sig-2").get("x"))

    def test_put_nothing_writes_nothing(self):
        path = tmpdir(self) / "e.jsonl"
        JsonlCache(path, "sig").put_many({})
        self.assertFalse(path.exists())

    def test_truncated_last_line_is_skipped(self):
        path = tmpdir(self) / "e.jsonl"
        JsonlCache(path, "sig").put_many({"a": "A"})
        with path.open("a", encoding="utf-8") as handle:
            handle.write('{"k": "dead", "v": "cut sh')
        cache = JsonlCache(path, "sig")
        self.assertEqual(cache.get("a"), "A")

    def test_entries_written_after_a_crash_survive(self):
        # REGRESSION: the next entry is appended to the cut line and both are lost on reload
        path = tmpdir(self) / "e.jsonl"
        JsonlCache(path, "sig").put_many({"a": "A"})
        with path.open("a", encoding="utf-8") as handle:
            handle.write('{"k": "dead", "v": "cut sh')
        JsonlCache(path, "sig").put_many({"b": "B"})
        self.assertEqual(JsonlCache(path, "sig").get("b"), "B")

    def test_digest(self):
        self.assertEqual(digest("a", "b"), digest("a", "b"))
        self.assertNotEqual(digest("ab", "c"), digest("a", "bc"))


# =============================================================================
# engines (no model is loaded)
# =============================================================================

class _Model:
    def eval(self):
        return self


class ScriptedEngine(TranslationEngine):
    """Real TranslationEngine.translate() over a scripted _generate."""

    kind = "scripted"

    def __init__(self, batch_size: int = 4, max_batch: int | None = None, error: str | None = None):
        super().__init__("scripted", {"model_id": "fake/scripted", "batch_size": batch_size}, device="cpu")
        self.max_batch, self.error = max_batch, error
        self.loads = 0
        self.batches: list[list[str]] = []

    def _load(self):
        self.loads += 1
        self._model = _Model()

    def _generate(self, batch):
        self.batches.append(list(batch))
        if self.error:
            raise RuntimeError(self.error)
        if self.max_batch and len(batch) > self.max_batch:
            raise RuntimeError("CUDA out of memory. Tried to allocate 20.00 MiB")
        return [f"  vi \n {text}  " for text in batch]

    # the real one imports torch; an out-of-memory error only exists with torch installed
    _free_cuda = staticmethod(lambda: None)


class TestEngines(unittest.TestCase):

    def test_factory_reads_config(self):
        for name in CONFIG["engines"]:
            with self.subTest(name=name):
                engine = get_engine(name, CONFIG, device="cpu")
                self.assertEqual(engine.key, name)
                self.assertEqual(engine.model_id, CONFIG["engines"][name]["model_id"])
                self.assertIsInstance(engine, KINDS[CONFIG["engines"][name]["kind"]])
                self.assertIsNone(engine._model)

    def test_alias_and_case(self):
        engine = get_engine("MedEV", CONFIG, device="cpu")
        self.assertEqual(engine.key, "vinai")
        self.assertEqual(engine.kind, "mbart")
        self.assertEqual(get_engine("NLLB", CONFIG).key, "nllb")

    def test_unknown_name_or_kind(self):
        with self.assertRaises(ValueError):
            get_engine("bogus", CONFIG)
        with self.assertRaises(ValueError):
            get_engine("x", {"engines": {"x": {"kind": "nope", "model_id": "m"}}})

    def test_signature(self):
        base = {"kind": "nllb", "model_id": "m", "num_beams": 4, "batch_size": 8}
        a = get_engine("x", {"engines": {"x": base}})
        b = get_engine("x", {"engines": {"x": {**base, "batch_size": 64}}})
        c = get_engine("x", {"engines": {"x": {**base, "num_beams": 5}}})
        self.assertEqual(a.signature, b.signature)
        self.assertNotEqual(a.signature, c.signature)

    def test_resolve_device(self):
        self.assertEqual(resolve_device("cpu"), "cpu")
        self.assertEqual(resolve_device("cuda:1"), "cuda:1")

    def test_translate_keeps_input_order_and_normalises_whitespace(self):
        engine = ScriptedEngine(batch_size=2)
        texts = ["a", "ccc", "bb", "dddd", "ee"]
        self.assertEqual(engine.translate(texts), [f"vi {t}" for t in texts])
        self.assertEqual(engine.batches, [["dddd", "ccc"], ["bb", "ee"], ["a"]])
        self.assertEqual(engine.loads, 1)
        engine.translate(["x"])
        self.assertEqual(engine.loads, 1)

    def test_on_batch_receives_every_batch(self):
        engine = ScriptedEngine(batch_size=2)
        seen: list[dict] = []
        engine.translate(["a", "bb", "ccc"], on_batch=seen.append)
        self.assertEqual(seen, [{"ccc": "vi ccc", "bb": "vi bb"}, {"a": "vi a"}])

    def test_out_of_memory_halves_the_batch(self):
        engine = ScriptedEngine(batch_size=8, max_batch=2)
        texts = [f"text {i}" for i in range(5)]
        self.assertEqual(engine.translate(texts), [f"vi {t}" for t in texts])
        self.assertEqual([len(b) for b in engine.batches], [5, 4, 2, 2, 1])

    def test_out_of_memory_at_batch_size_one_raises(self):
        engine = ScriptedEngine(batch_size=1, error="CUDA out of memory")
        with self.assertRaises(RuntimeError):
            engine.translate(["a"])

    def test_other_runtime_errors_are_not_retried(self):
        engine = ScriptedEngine(batch_size=4, error="device-side assert triggered")
        with self.assertRaises(RuntimeError):
            engine.translate(["a", "b"])
        self.assertEqual(len(engine.batches), 1)

    def test_empty_input_loads_nothing(self):
        engine = ScriptedEngine()
        self.assertEqual(engine.translate([]), [])
        self.assertEqual(engine.loads, 0)

    def test_unload_works_without_a_model(self):
        engine = ScriptedEngine()
        engine.translate(["a"])
        engine.unload()
        self.assertIsNone(engine._model)
        self.assertIsNone(engine._tokenizer)


# =============================================================================
# quality gate
# =============================================================================

def hard(issues) -> set[str]:
    return {issue.check for issue in issues if issue.hard}


def soft(issues) -> set[str]:
    return {issue.check for issue in issues if not issue.hard}


class TestCheckLine(unittest.TestCase):

    def line(self, source: str, target: str, **kw):
        return check_line(source, target, RULES, QUALITY, **kw)

    def test_clean_line(self):
        self.assertEqual(self.line("Take 2 tablets every 4 hours.", "Uống 2 viên mỗi 4 giờ."), [])

    def test_empty_source_line_has_no_issue(self):
        self.assertEqual(self.line("", ""), [])
        self.assertEqual(self.line("   ", "x"), [])

    def test_empty_translation(self):
        self.assertEqual(hard(self.line("a cough", " ")), {"non_empty"})

    def test_number_lost(self):
        issues = self.line("Take 2 tablets every 4 hours.", "Uống thuốc mỗi giờ.", index=3)
        self.assertIn("numbers_preserved", hard(issues))
        self.assertEqual(issues[0].line, 3)

    def test_uk_service_left(self):
        for target in ("Gọi 999 ngay", "Gặp GP của bạn", "Đến A&E ngay", "Gọi 111 để được tư vấn"):
            with self.subTest(target=target):
                self.assertIn("no_uk_services", hard(self.line("call for help now", target)))

    def test_999_left_fails_twice(self):
        self.assertEqual(hard(self.line("Call 999 now", "Gọi 999 ngay")), {"numbers_preserved", "no_uk_services"})

    def test_discouraged_term_is_soft(self):
        issues = self.line("Visit the NHS website", "Truy cập trang web NHS (tại Anh)")
        self.assertEqual(hard(issues), set())
        self.assertIn("uk_reference_left", soft(issues))

    def test_artifacts(self):
        for target in ("Uống nước<|VIETNAMESE_TEXT_END|>", "Uống nước </s>", "Uống <unk> nước",
                       "Uống nư�c", "Uống «DRUG_001» mỗi ngày", "<|im_end|>Uống nước"):
            with self.subTest(target=target):
                self.assertIn("no_artifacts", hard(self.line("Drink water", target)))

    def test_untranslated_line(self):
        source = "Speak to a pharmacist if you are worried."
        self.assertIn("no_untranslated", hard(self.line(source, source)))
        self.assertIn("no_untranslated", hard(self.line(source, "  speak to a PHARMACIST if you are   worried. ")))

    def test_short_names_may_stay_english(self):
        self.assertEqual(hard(self.line("Paracetamol", "Paracetamol")), set())
        self.assertEqual(hard(self.line("Ibuprofen and paracetamol", "Ibuprofen and paracetamol")), set())

    def test_english_echo_of_the_localised_source_fails(self):
        # REGRESSION: the engines translate the rewritten source, but no_untranslated compares
        # with the original line, so an engine that echoes its English input passes the gate
        source = "Immediate action required: Call 999 or go to A&E if:"
        echo = RULES.rewrite_source(source)
        self.assertIn("no_untranslated", hard(self.line(source, echo)))

    def test_generation_loop(self):
        target = "Uống nhiều nước " * 8
        self.assertIn("no_repetition", hard(self.line("Drink plenty of fluids to stay hydrated.", target)))

    def test_repetitive_source_is_not_a_loop(self):
        source = "wash your hands well " * 4
        target = "rửa tay thật sạch " * 4
        self.assertNotIn("no_repetition", hard(self.line(source, target)))

    def test_line_length_is_soft(self):
        issues = self.line("Drink plenty of fluids to stay hydrated and rest.", "Uống")
        self.assertEqual(hard(issues), set())
        self.assertIn("line_length", soft(issues))

    def test_low_support_is_soft(self):
        source = "Drink plenty of fluids to stay hydrated."
        issues = self.line(source, "Hãy đi bộ mỗi buổi sáng cùng gia đình bạn.",
                           drafts=["Uống nhiều nước để cơ thể đủ nước."])
        self.assertEqual(hard(issues), set())
        self.assertIn("low_support", soft(issues))
        self.assertEqual(self.line(source, "Uống nhiều nước để cơ thể đủ nước.",
                                   drafts=["Uống nhiều nước để cơ thể đủ nước."]), [])

    def test_added_number_is_soft(self):
        issues = self.line("Take the tablets with water", "Uống 3 viên với nước")
        self.assertEqual(soft(issues), {"numbers_added"})


class TestCheckQuality(unittest.TestCase):

    SOURCE = "Treatment\n• Take 2 tablets every 4 hours\n• drink plenty of water"
    TARGET = "Điều trị\n• Uống 2 viên mỗi 4 giờ\n• uống nhiều nước"

    def gate(self, source=SOURCE, target=TARGET, **kw):
        return check_quality("c1", source, target, rules=RULES, settings=QUALITY, **kw)

    def test_clean_chunk(self):
        report = self.gate()
        self.assertTrue(report.passed)
        self.assertEqual(report.status, "auto_verified")
        self.assertFalse(report.needs_human_review)
        self.assertEqual(set(report.checks), set(HARD_CHECKS))
        self.assertTrue(all(report.checks.values()))

    def test_report_dict(self):
        data = self.gate(target=self.TARGET.replace("4 giờ", "4 giờ, 5 lần")).to_dict()
        self.assertEqual(set(data), {"chunk_id", "passed", "status", "needs_human_review", "checks", "warnings",
                                     "issues"})
        self.assertEqual(data["status"], "auto_verified_with_warnings")
        self.assertEqual(data["issues"][0], {"check": "numbers_added", "hard": False, "line": 1,
                                             "detail": "number(s) not in the source: 5"})
        self.assertTrue(data["warnings"][0].startswith("numbers_added: line 2: "))
        json.dumps(data)

    def test_emergency_chunk_always_needs_review(self):
        report = self.gate(tags=["emergency"])
        self.assertTrue(report.passed)
        self.assertEqual(report.status, "auto_verified")
        self.assertTrue(report.needs_human_review)

    def test_dropped_number_fails(self):
        report = self.gate(target=self.TARGET.replace("2 viên", "viên"))
        self.assertFalse(report.passed)
        self.assertFalse(report.checks["numbers_preserved"])
        self.assertEqual(report.status, "failed_quality_gate")
        self.assertTrue(report.needs_human_review)

    def test_dropped_line_fails(self):
        report = self.gate(target="Điều trị\n• Uống 2 viên mỗi 4 giờ")
        self.assertFalse(report.checks["line_count"])
        self.assertFalse(report.checks["bullet_count"])

    def test_merged_lines_are_still_number_checked(self):
        report = self.gate(target="Điều trị • Uống viên thuốc • uống nhiều nước")
        self.assertFalse(report.checks["line_count"])
        self.assertFalse(report.checks["numbers_preserved"])

    def test_lost_bullet_marker_fails(self):
        report = self.gate(target="Điều trị\nUống 2 viên mỗi 4 giờ\n• uống nhiều nước")
        self.assertFalse(report.checks["bullet_count"])
        self.assertTrue(report.checks["line_count"])

    def test_numbered_list_marker_counts(self):
        source = "Steps\n1. Wash your hands for 20 seconds\n2. Dry them"
        self.assertTrue(self.gate(source, "Các bước\n1. Rửa tay trong 20 giây\n2. Lau khô").passed)
        self.assertFalse(self.gate(source, "Các bước\nRửa tay trong 20 giây\nLau khô").checks["bullet_count"])

    def test_empty_translation(self):
        report = self.gate(target="")
        self.assertFalse(report.checks["non_empty"])
        self.assertFalse(report.passed)

    def test_length_ratio(self):
        source = "Drink plenty of fluids to stay hydrated and get as much rest as you can over the next few days."
        self.assertFalse(self.gate(source, "Uống nước").checks["length_ratio"])
        self.assertFalse(self.gate(source, "Uống nước " * 60).checks["length_ratio"])

    def test_min_words(self):
        self.assertFalse(self.gate("Rest at home today", "Nghỉ ngơi").checks["min_words"])
        self.assertTrue(self.gate("Rest", "Nghỉ ngơi").checks["min_words"])

    def test_uk_service_in_one_line_fails_the_chunk(self):
        report = self.gate("Help\n• call 999 if it gets worse", "Trợ giúp\n• gọi 999 nếu nặng hơn")
        self.assertFalse(report.checks["no_uk_services"])
        self.assertFalse(report.checks["numbers_preserved"])

    def test_digit_only_line(self):
        self.assertTrue(self.gate("Dose\n• 500\n• 250", "Liều dùng\n• 500\n• 250").passed)

    def test_medicine_name_changed_is_soft(self):
        report = self.gate("Take paracetamol for pain", "Uống thuốc hạ sốt khi đau", names=["paracetamol"])
        self.assertTrue(report.passed)
        self.assertEqual(report.status, "auto_verified_with_warnings")
        self.assertTrue(report.needs_human_review)
        self.assertTrue(any(w.startswith("medicine_name_changed") for w in report.warnings))

    def test_explicit_ratio_arguments_override_settings(self):
        report = self.gate("one two three four", "một hai ba bốn năm sáu bảy tám chín mười mười một",
                           max_length_ratio=10.0)
        self.assertTrue(report.checks["length_ratio"])


# =============================================================================
# LLM post-editor helpers
# =============================================================================

class _Response:
    def __init__(self, body: dict):
        self.body = json.dumps(body).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self):
        return self.body


class TestLLMClient(unittest.TestCase):

    ENV = {"LLM_BASE_URL": "http://llm.invalid/v1/", "LLM_MODEL": "model-x", "LLM_API_KEY": "test-key"}

    def client(self, **kw) -> LLMClient:
        return LLMClient("http://llm.invalid/v1", "test-key", "model-x", backoff=0, **kw)

    def test_from_env(self):
        self.assertIsNone(LLMClient.from_env({}, env={}))
        self.assertIsNone(LLMClient.from_env({}, env={**self.ENV, "LLM_API_KEY": ""}))
        client = LLMClient.from_env({"default_reasoning": "low", "timeout_seconds": 30}, env=self.ENV)
        self.assertEqual((client.base, client.model, client.effort, client.timeout),
                         ("http://llm.invalid/v1", "model-x", "low", 30))
        self.assertEqual(LLMClient.from_env({}, env={**self.ENV, "LLM_REASONING": "high"}).effort, "high")

    def test_missing_env(self):
        self.assertEqual(LLMClient.missing_env({"LLM_MODEL": "m"}), ["LLM_BASE_URL", "LLM_API_KEY"])
        self.assertEqual(LLMClient.missing_env(self.ENV), [])

    def test_chat_request_and_answer(self):
        seen = []

        def fake_urlopen(request, timeout):
            seen.append(request)
            return _Response({"choices": [{"message": {"content": "[]"}}]})

        with mock.patch("translator.refine.urlopen", fake_urlopen):
            self.assertEqual(self.client(effort="low").chat("sys", "user"), "[]")
        request = seen[0]
        self.assertEqual(request.full_url, "http://llm.invalid/v1/chat/completions")
        payload = json.loads(request.data)
        self.assertEqual(payload["model"], "model-x")
        self.assertEqual(payload["reasoning_effort"], "low")
        self.assertEqual([m["role"] for m in payload["messages"]], ["system", "user"])
        self.assertTrue(request.get_header("Authorization").startswith("Bearer "))

    def test_no_effort_sends_no_reasoning_field(self):
        seen = []

        def fake_urlopen(request, timeout):
            seen.append(json.loads(request.data))
            return _Response({"choices": [{"message": {"content": None}}]})

        with mock.patch("translator.refine.urlopen", fake_urlopen):
            self.assertEqual(self.client().chat("s", "u"), "")
        self.assertNotIn("reasoning_effort", seen[0])

    def test_rate_limit_stops_at_once(self):
        calls = []

        def fake_urlopen(request, timeout):
            calls.append(1)
            raise HTTPError(request.full_url, 429, "Too Many Requests", {}, None)

        with mock.patch("translator.refine.urlopen", fake_urlopen):
            with self.assertRaises(Stop):
                self.client(retries=3).chat("s", "u")
        self.assertEqual(len(calls), 1)

    def test_server_error_is_retried_then_stops(self):
        calls = []

        def fake_urlopen(request, timeout):
            calls.append(1)
            if len(calls) == 1:
                raise URLError("connection reset")
            raise HTTPError(request.full_url, 500, "Server Error", {}, None)

        with mock.patch("translator.refine.urlopen", fake_urlopen):
            with self.assertRaises(Stop):
                self.client(retries=3).chat("s", "u")
        self.assertEqual(len(calls), 3)

    def test_retry_then_success(self):
        calls = []

        def fake_urlopen(request, timeout):
            calls.append(1)
            if len(calls) < 2:
                raise TimeoutError()
            return _Response({"choices": [{"message": {"content": "ok"}}]})

        with mock.patch("translator.refine.urlopen", fake_urlopen):
            self.assertEqual(self.client(retries=3).chat("s", "u"), "ok")

    def test_error_payload_stops(self):
        with mock.patch("translator.refine.urlopen",
                        lambda r, timeout: _Response({"error": {"code": "insufficient_quota"}})):
            with self.assertRaises(Stop) as ctx:
                self.client().chat("s", "u")
        self.assertIn("insufficient_quota", str(ctx.exception))

    def test_odd_payload_is_empty_answer(self):
        with mock.patch("translator.refine.urlopen", lambda r, timeout: _Response({"choices": []})):
            self.assertEqual(self.client().chat("s", "u"), "")


class TestRefineHelpers(unittest.TestCase):

    def test_system_prompt_shows_the_tables(self):
        prompt = system_prompt(RULES)
        for table in ("service_table", "age_table", "terminology"):
            for row in RULES.tables[table]:
                self.assertIn(f"- {row['en']} -> {row['vi']}", prompt)
        self.assertIn('[{"id": "...", "vi": ["...", "..."]}]', prompt)

    def test_build_item(self):
        unit = make_unit("u1", ["Heading", "• take 2 tablets", ""],
                         {"a": ["Tiêu đề", "uống 2 viên", ""], "b": ["Tiêu đề", "uống hai viên", ""]},
                         hint=["paracetamol"])
        item = build_item(unit)
        self.assertEqual(item["id"], "u1")
        self.assertEqual(item["lines"], [{"en": "Heading", "mt": ["Tiêu đề"]},
                                         {"en": "take 2 tablets", "mt": ["uống 2 viên", "uống hai viên"]},
                                         {"en": "", "mt": []}])
        self.assertEqual(item["medicine_names"], ["paracetamol"])
        self.assertNotIn("previous", item)
        repair = build_item(unit, previous=["a", "b", ""], problems=["line 2: missing number(s): 2"])
        self.assertEqual(repair["previous"], ["a", "b", ""])
        self.assertEqual(repair["problems"], ["line 2: missing number(s): 2"])
        self.assertNotIn("medicine_names", build_item(make_unit("u2", ["x"], {"a": ["y"]})))

    def test_parse_answer(self):
        expected = {"u1": 2, "u2": 1}
        answer = json.dumps([{"id": "u1", "vi": ["Một  dòng\nnày", "hai"]}, {"id": "u2", "vi": ["ba"]}])
        self.assertEqual(parse_answer(answer, expected), {"u1": ["Một dòng này", "hai"], "u2": ["ba"]})

    def test_parse_answer_tolerates_fences_and_prose(self):
        answer = 'Here you are:\n```json\n[{"id": "u1", "vi": ["a", "b"]}]\n```\nDone.'
        self.assertEqual(parse_answer(answer, {"u1": 2}), {"u1": ["a", "b"]})

    def test_parse_answer_rejects_bad_entries(self):
        expected = {"u1": 2, "u2": 1, "u3": 1}
        answer = json.dumps([{"id": "u1", "vi": ["only one"]}, {"id": "u2", "vi": [1]},
                             {"id": "zz", "vi": ["x"]}, "noise", {"id": "u3", "vi": "not a list"}])
        self.assertEqual(parse_answer(answer, expected), {})

    def test_parse_answer_malformed(self):
        self.assertEqual(parse_answer("", {"u1": 1}), {})
        self.assertEqual(parse_answer("no json here", {"u1": 1}), {})
        self.assertEqual(parse_answer('[{"id": "u1", "vi": ["a"]', {"u1": 1}), {})
        self.assertEqual(parse_answer('] then [', {"u1": 1}), {})
        self.assertEqual(parse_answer('[1, 2]', {"u1": 1}), {})


# =============================================================================
# pipeline
# =============================================================================

class TestBuildUnits(unittest.TestCase):

    def test_units_from_chunks_and_strings(self):
        chunks = [chunk("c1", "a", "Heading\n• a cough\n", heading="H", path=["Cold", "", "Symptoms › Adults"],
                        tags=["emergency"])]
        units = build_units(chunks, ["Cold", "", "  ", "Cold", "Flu"], compile_names(["paracetamol"]), extra_size=1)
        self.assertEqual([u.id for u in units], ["c1", "extra-0000", "extra-0001"])
        first = units[0]
        self.assertEqual(first.lines, ["Heading", "• a cough", ""])
        self.assertEqual(first.markers, ["", "• ", ""])
        self.assertEqual(first.bodies, ["Heading", "a cough", ""])
        self.assertEqual(first.section, "Cold > Symptoms › Adults")
        self.assertEqual(first.tags, ["emergency"])
        self.assertEqual(first.source_text, "Heading\n• a cough\n")
        self.assertEqual([u.lines for u in units[1:]], [["Cold"], ["Flu"]])
        self.assertEqual(units[1].kind, "extra")

    def test_section_falls_back_to_heading_and_names_are_found(self):
        units = build_units([chunk("c1", "a", "Take Paracetamol daily", heading="Dosage")], [],
                            compile_names(["paracetamol"]))
        self.assertEqual(units[0].section, "Dosage")
        self.assertEqual(units[0].hint_names, ["paracetamol"])


class TestMachineTranslate(unittest.TestCase):

    def translate(self, units, engines, cache_dir=None, max_words=60):
        cache_dir = cache_dir or tmpdir(self)
        return machine_translate(units, engines, cache_dir, RULES, max_words, log=quiet), cache_dir

    def test_engines_only_see_localised_english(self):
        unit = make_unit("u1", A2_TEXT.split("\n") + ["Ask your GP or call 111.", "GPs can help."])
        engine = FakeEngine("e")
        self.translate([unit], [engine])
        self.assertTrue(engine.seen)
        for text in engine.seen:
            self.assertIsNone(UK_TERMS.search(text), text)
        self.assertIn("115", " ".join(engine.seen))

    def test_drafts_keep_marker_aside_and_follow_harmonise(self):
        unit = make_unit("u1", ["• you have chest pain", "Treatment for a cold", "Drink plenty of fluids to stay hydrated."])
        self.translate([unit], [FakeEngine("e", lambda t: good(t).rstrip(".").capitalize() + ".")])
        self.assertEqual(unit.drafts["e"], ["bạn bị đau ngực", "Điều trị cảm lạnh", "Uống nhiều nước để cơ thể đủ nước."])

    def test_duplicate_lines_are_translated_once(self):
        units = [make_unit("u1", ["you have chest pain", "Treatment for a cold"]),
                 make_unit("u2", ["• you have chest pain"])]
        engine = FakeEngine("e")
        self.translate(units, [engine])
        self.assertEqual(sorted(engine.seen), ["Treatment for a cold", "you have chest pain"])
        self.assertEqual(units[1].drafts["e"], ["bạn bị đau ngực"])

    def test_lines_without_letters_are_not_sent(self):
        unit = make_unit("u1", ["• 500", "", "---", "Treatment for a cold"])
        engine = FakeEngine("e")
        self.translate([unit], [engine])
        self.assertEqual(engine.seen, ["Treatment for a cold"])
        self.assertEqual(unit.drafts["e"], ["500", "", "---", "Điều trị cảm lạnh"])

    def test_long_line_is_split_into_sentences_and_rejoined(self):
        line = ("Take 2 paracetamol tablets every 4 to 6 hours. Do not take more than 8 tablets in 24 hours. "
                "Drink plenty of fluids to stay hydrated.")
        unit = make_unit("u1", [line])
        engine = FakeEngine("e")
        self.translate([unit], [engine], max_words=10)
        self.assertEqual(engine.seen, ["Take 2 paracetamol tablets every 4 to 6 hours.",
                                       "Do not take more than 8 tablets in 24 hours.",
                                       "Drink plenty of fluids to stay hydrated."])
        self.assertEqual(unit.drafts["e"], [" ".join(GOOD[s] for s in engine.seen)])

    def test_second_run_is_served_from_the_cache(self):
        cache_dir = tmpdir(self)
        first = FakeEngine("e")
        stats, _ = self.translate([make_unit("u1", A1_TEXT.split("\n"))], [first], cache_dir)
        self.assertEqual(stats["e"]["translated_now"], 3)
        self.assertEqual(first.unloaded, 1)
        again = FakeEngine("e", fn=lambda t: "KHÔNG ĐƯỢC GỌI")
        unit = make_unit("u1", A1_TEXT.split("\n"))
        stats, _ = self.translate([unit], [again], cache_dir)
        self.assertEqual(again.calls, [])
        self.assertEqual(stats["e"]["translated_now"], 0)
        self.assertEqual(unit.drafts["e"], [GOOD[line] for line in A1_TEXT.split("\n")])

    def test_changed_signature_translates_again(self):
        cache_dir = tmpdir(self)
        self.translate([make_unit("u1", ["Treatment for a cold"])], [FakeEngine("e")], cache_dir)
        changed = FakeEngine("e")
        changed.signature = json.dumps({"fake": "e", "num_beams": 9})
        self.translate([make_unit("u1", ["Treatment for a cold"])], [changed], cache_dir)
        self.assertEqual(changed.seen, ["Treatment for a cold"])

    def test_crash_keeps_finished_batches_and_unloads(self):
        cache_dir = tmpdir(self)
        lines = [f"line number {word}" for word in ("one", "two", "three", "four", "five")]
        crashing = FakeEngine("e", batch_size=2, fail_after=2)
        with self.assertRaises(RuntimeError):
            self.translate([make_unit("u1", lines)], [crashing], cache_dir)
        self.assertEqual(crashing.unloaded, 1)
        resumed = FakeEngine("e")
        self.translate([make_unit("u1", lines)], [resumed], cache_dir)
        self.assertEqual(len(resumed.seen), 3)

    def test_kept_names_are_those_most_engines_kept(self):
        line = "Take paracetamol or antihistamines for 3 days."
        units = build_units([chunk("c1", "a", line)], [], compile_names(["paracetamol", "antihistamines"]))
        keeps_both = FakeEngine("a", lambda t: "Dùng paracetamol hoặc antihistamines trong 3 ngày.")
        translates_class = FakeEngine("b", lambda t: "Dùng paracetamol hoặc thuốc kháng histamin trong 3 ngày.")
        third = FakeEngine("c", lambda t: "Uống paracetamol hoặc thuốc kháng histamin trong 3 ngày.")
        self.translate(units, [keeps_both, translates_class, third])
        self.assertEqual(units[0].hint_names, ["paracetamol", "antihistamines"])
        self.assertEqual(units[0].kept_names, ["paracetamol"])

    def test_no_engines_leaves_no_drafts(self):
        unit = make_unit("u1", ["Treatment for a cold"])
        stats, _ = self.translate([unit], [])
        self.assertEqual((stats, unit.drafts, unit.kept_names), ({}, {}, []))


class TestConsensusAndFinalise(unittest.TestCase):

    def unit_with(self, engines, lines=None, **kw):
        unit = make_unit("u1", lines or A1_TEXT.split("\n"), **kw)
        machine_translate([unit], engines, tmpdir(self), RULES, 60, log=quiet)
        return unit

    def test_consensus_beats_an_engine_that_drops_numbers(self):
        unit = self.unit_with([FakeEngine("drop", dropper), FakeEngine("good", good), FakeEngine("para", paraphrase)])
        lines, picks = consensus_lines(unit, RULES, QUALITY)
        self.assertEqual(picks[0], "drop")  # no number on that line: the first engine wins the tie
        self.assertNotIn("drop", picks[1:])
        result = finalise(unit, None, RULES, QUALITY)
        self.assertTrue(result.report.passed, result.report.warnings)
        self.assertEqual(result.methods, ["consensus"] * 3)
        self.assertIn("2 viên", result.text)

    def test_only_a_dropping_engine_fails_the_gate(self):
        result = finalise(self.unit_with([FakeEngine("drop", dropper)]), None, RULES, QUALITY)
        self.assertFalse(result.report.passed)
        self.assertFalse(result.report.checks["numbers_preserved"])

    def test_clean_llm_line_is_used(self):
        unit = self.unit_with([FakeEngine("good", good)])
        llm = ["Cách điều trị cảm lạnh", "Uống 2 viên paracetamol cách nhau 4 đến 6 giờ.",
               "Không dùng quá 8 viên trong 24 giờ."]
        result = finalise(unit, llm, RULES, QUALITY)
        self.assertEqual(result.bodies, llm)
        self.assertEqual(result.methods, ["llm"] * 3)
        self.assertTrue(result.report.passed)

    def test_bad_llm_line_falls_back_to_consensus(self):
        unit = self.unit_with([FakeEngine("good", good)])
        llm = ["Cách điều trị cảm lạnh", "Uống paracetamol cách nhau vài giờ.", "Gọi 999 nếu dùng quá 8 viên trong 24 giờ."]
        result = finalise(unit, llm, RULES, QUALITY)
        self.assertEqual(result.methods, ["llm", "consensus_fallback", "consensus_fallback"])
        self.assertEqual(result.bodies[1:], [GOOD[line] for line in A1_TEXT.split("\n")[1:]])
        self.assertTrue(result.report.passed)

    def test_bad_llm_line_is_kept_when_consensus_is_bad_too(self):
        unit = self.unit_with([FakeEngine("drop", dropper)])
        result = finalise(unit, ["Điều trị", "Uống thuốc.", "Không uống quá liều."], RULES, QUALITY)
        self.assertEqual(result.methods, ["llm", "llm", "llm"])
        self.assertFalse(result.report.passed)

    def test_llm_only_without_drafts(self):
        unit = make_unit("u1", ["Treatment for a cold", "• Take 2 tablets"])
        result = finalise(unit, ["Điều trị cảm lạnh", "Uống 2 viên"], RULES, QUALITY)
        self.assertEqual(result.methods, ["llm", "llm"])
        self.assertEqual(result.text, "Điều trị cảm lạnh\n• Uống 2 viên")
        self.assertTrue(result.report.passed)

    def test_lines_without_letters_are_copied(self):
        unit = self.unit_with([FakeEngine("good", good)], ["Dose", "• 500", "", "Treatment for a cold"])
        result = finalise(unit, ["Liều", "SAI", "SAI", "Điều trị cảm lạnh"], RULES, QUALITY)
        self.assertEqual(result.methods, ["llm", "copy", "copy", "llm"])
        self.assertEqual(result.lines, ["Liều", "• 500", "", "Điều trị cảm lạnh"])

    def test_copied_line_is_localised(self):
        # REGRESSION: a line with no letter is copied from the original source, not the
        # localised one, so a bare "999" line stays 999 in the Vietnamese text
        unit = self.unit_with([FakeEngine("good", good)], ["Emergency number", "999"])
        result = finalise(unit, None, RULES, QUALITY)
        self.assertEqual(result.bodies[1], "115")
        self.assertTrue(result.report.passed, result.report.warnings)

    def test_medicine_name_most_engines_kept_must_be_kept(self):
        line = "Take paracetamol or antihistamines for 3 days."
        units = build_units([chunk("c1", "a", line)], [], compile_names(["paracetamol", "antihistamines"]))
        engines = [FakeEngine("a", lambda t: "Dùng paracetamol hoặc thuốc kháng histamin trong 3 ngày."),
                   FakeEngine("b", lambda t: "Uống paracetamol hoặc thuốc kháng histamin trong 3 ngày.")]
        machine_translate(units, engines, tmpdir(self), RULES, 60, log=quiet)
        lost = finalise(units[0], ["Dùng thuốc hạ sốt hoặc thuốc kháng histamin trong 3 ngày."], RULES, QUALITY)
        self.assertTrue(lost.report.passed)
        self.assertTrue(lost.report.needs_human_review)
        self.assertEqual([i.check for i in lost.report.issues], ["medicine_name_changed"])
        self.assertIn("paracetamol", lost.report.warnings[0])
        kept = finalise(units[0], ["Dùng paracetamol hoặc thuốc kháng histamin trong 3 ngày."], RULES, QUALITY)
        self.assertEqual(kept.report.issues, [])

    def test_emergency_unit_needs_review_even_when_clean(self):
        unit = self.unit_with([FakeEngine("good", good)], A2_TEXT.split("\n"), tags=["emergency"])
        result = finalise(unit, None, RULES, QUALITY)
        self.assertTrue(result.report.passed, result.report.warnings)
        self.assertTrue(result.report.needs_human_review)
        self.assertIn("115", result.text)

    def test_engine_statistics(self):
        unit = self.unit_with([FakeEngine("drop", dropper), FakeEngine("good", good)], A1_TEXT.split("\n") + [""])
        stats = engine_statistics([finalise(unit, None, RULES, QUALITY)], RULES, QUALITY)
        self.assertEqual(stats["drop"]["lines"], 3)
        self.assertAlmostEqual(stats["drop"]["hard_issue_line_rate"], round(2 / 3, 4))
        self.assertEqual(stats["good"]["hard_issue_line_rate"], 0.0)
        self.assertAlmostEqual(stats["good"]["consensus_pick_rate"], round(2 / 3, 4))
        self.assertEqual(stats["good"]["mean_chrf_vs_final"], 1.0)


class TestRefine(unittest.TestCase):

    SETTINGS = {"units_per_request": 2, "workers": 1, "repair_rounds": 1}

    def units(self, n: int = 3) -> list[Unit]:
        texts = [A1_TEXT, A2_TEXT, B1_TEXT, "Treatment for a cold", "you have chest pain"]
        units = [make_unit(f"u{i}", texts[i].split("\n")) for i in range(n)]
        machine_translate(units, [FakeEngine("good", good)], self.cache_dir, RULES, 60, log=quiet)
        return units

    def setUp(self):
        self.cache_dir = tmpdir(self)

    def refine(self, units, client, settings=None, **kw):
        return refine(units, client, RULES, self.cache_dir, settings or self.SETTINGS, QUALITY, log=quiet, **kw)

    def test_answers_are_used_and_cached(self):
        units = self.units(3)
        client = FakeLLM()
        results, stats = self.refine(units, client)
        self.assertEqual(set(results), {"u0", "u1", "u2"})
        self.assertEqual(results["u0"], units[0].drafts["good"])
        self.assertEqual((stats["requests"], stats["answered_units"], stats["stopped"]), (2, 3, ""))
        again = FakeLLM()
        results2, stats2 = self.refine(units, again)
        self.assertEqual(again.calls, 0)
        self.assertEqual(stats2["cached_units"], 3)
        self.assertEqual(results2, results)

    def test_items_carry_the_drafts(self):
        units = self.units(1)
        client = FakeLLM()
        self.refine(units, client)
        item = client.payloads[0][0]
        self.assertEqual(item["id"], "u0")
        self.assertEqual(item["lines"][1], {"en": "Take 2 paracetamol tablets every 4 to 6 hours.",
                                            "mt": [GOOD["Take 2 paracetamol tablets every 4 to 6 hours."]]})

    def test_wrong_line_count_is_rejected(self):
        units = self.units(2)

        def script(n, items):
            return json.dumps([{"id": item["id"], "vi": ["chỉ một dòng"]} for item in items])

        results, stats = self.refine(units, FakeLLM(script))
        self.assertEqual(results, {})
        self.assertEqual(stats["answered_units"], 0)
        self.assertEqual(finalise(units[0], results.get("u0"), RULES, QUALITY).methods, ["consensus"] * 3)

    def test_repair_round_fixes_a_hard_issue(self):
        units = self.units(1)
        broken = ["Điều trị cảm lạnh", "Uống paracetamol mỗi vài giờ.", GOOD[A1_TEXT.split("\n")[2]]]
        fixed = [GOOD[line] for line in A1_TEXT.split("\n")]

        def script(n, items):
            return json.dumps([{"id": items[0]["id"], "vi": broken if n == 1 else fixed}], ensure_ascii=False)

        client = FakeLLM(script)
        results, stats = self.refine(units, client)
        self.assertEqual(client.calls, 2)
        repair = client.payloads[1][0]
        self.assertEqual(repair["previous"], broken)
        self.assertTrue(any("line 2" in p and "2, 4, 6" in p for p in repair["problems"]), repair["problems"])
        self.assertEqual(results["u0"], fixed)
        self.assertEqual(stats["repaired_units"], 1)
        again = FakeLLM()
        self.assertEqual(self.refine(units, again)[0], results)
        self.assertEqual(again.calls, 0)

    def test_repair_that_is_not_better_is_ignored(self):
        units = self.units(1)
        broken = ["Điều trị cảm lạnh", "Uống paracetamol mỗi vài giờ.", GOOD[A1_TEXT.split("\n")[2]]]
        client = FakeLLM(lambda n, items: json.dumps([{"id": "u0", "vi": broken}], ensure_ascii=False))
        results, stats = self.refine(units, client)
        self.assertEqual(results["u0"], broken)
        self.assertEqual(stats["repaired_units"], 0)
        self.assertEqual(finalise(units[0], results["u0"], RULES, QUALITY).methods,
                         ["llm", "consensus_fallback", "llm"])

    def test_no_repair_when_rounds_is_zero(self):
        units = self.units(1)
        client = FakeLLM(lambda n, items: json.dumps([{"id": "u0", "vi": ["a", "b", "c"]}]))
        self.refine(units, client, {**self.SETTINGS, "repair_rounds": 0})
        self.assertEqual(client.calls, 1)

    def test_max_requests(self):
        units = self.units(5)
        client = FakeLLM()
        results, _ = self.refine(units, client, {**self.SETTINGS, "units_per_request": 1}, max_requests=2)
        self.assertEqual(client.calls, 2)
        self.assertEqual(len(results), 2)

    def test_stop_ends_the_stage_quietly(self):
        units = self.units(2)

        def script(n, items):
            raise Stop("HTTP 429")

        results, stats = self.refine(units, FakeLLM(script), {**self.SETTINGS, "units_per_request": 2})
        self.assertEqual(results, {})
        self.assertEqual(stats["stopped"], "HTTP 429")

    def test_stop_keeps_answered_units_and_the_next_run_resumes(self):
        units = self.units(2)

        def script(n, items):
            if n == 2:
                raise Stop("HTTP 402")
            return FakeLLM.copy_first_draft(n, items)

        settings = {**self.SETTINGS, "units_per_request": 1}
        results, stats = self.refine(units, FakeLLM(script), settings)
        self.assertEqual(set(results), {"u0"})
        self.assertEqual(stats["stopped"], "HTTP 402")
        resumed = FakeLLM()
        results, stats = self.refine(units, resumed, settings)
        self.assertEqual(resumed.calls, 1)
        self.assertEqual(stats["cached_units"], 1)
        self.assertEqual(set(results), {"u0", "u1"})

    def test_stop_with_requests_still_queued(self):
        # REGRESSION: after a Stop the queued futures are cancelled, and as_completed() then
        # yields them: future.result() raises CancelledError, which escapes refine()
        units = self.units(3)

        def script(n, items):
            raise Stop("HTTP 429")

        results, stats = self.refine(units, FakeLLM(script), {**self.SETTINGS, "units_per_request": 1})
        self.assertEqual(results, {})
        self.assertEqual(stats["stopped"], "HTTP 429")

    def test_parallel_workers(self):
        units = self.units(5)
        client = FakeLLM()
        results, stats = self.refine(units, client, {"units_per_request": 1, "workers": 3, "repair_rounds": 1})
        self.assertEqual(len(results), 5)
        self.assertEqual(stats["requests"], 5)


# =============================================================================
# run.py helpers
# =============================================================================

def meta(refiner: str = "") -> dict:
    return {"src_lang": "en", "tgt_lang": "vi", "translated_at": "T", "refiner_model": refiner,
            "engine_models": ["fake/good"]}


class TestRunHelpers(unittest.TestCase):

    def results_for(self, chunks, fn=good):
        units = build_units([c for c in chunks if c["language"] == "en"],
                            ["Cold and flu", "When to get help", "Treatment", "Flu", "Self-care"], None)
        machine_translate(units, [FakeEngine("good", fn)], tmpdir(self), RULES, 60, log=quiet)
        results = {u.id: finalise(u, None, RULES, QUALITY) for u in units}
        strings = {}
        for result in results.values():
            if result.unit.kind == "extra":
                strings.update(zip(result.unit.lines, result.lines))
        return results, strings

    def test_localised_tags(self):
        self.assertEqual(run.localised_tags(["foreign_service", "emergency"], "Gọi cấp cứu 115", RULES),
                         ["emergency", "vn_service"])
        self.assertEqual(run.localised_tags(["foreign_service"], "Trang web NHS", RULES), ["foreign_service"])
        self.assertEqual(run.localised_tags(["foreign_service"], "Gọi 999", RULES), ["foreign_service"])
        self.assertEqual(run.localised_tags([], "Nghỉ ngơi", RULES), [])

    def test_chunk_record(self):
        chunks = sample_chunks()
        results, strings = self.results_for(chunks)
        record = run.chunk_record(chunks[1], results["c-a2"], strings, meta(), RULES)
        self.assertEqual(record["chunk_id"], "c-a2")
        self.assertEqual(record["text_en"], A2_TEXT)
        self.assertEqual(len(record["text_vi"].split("\n")), 3)
        self.assertTrue(record["text_vi"].split("\n")[1].startswith("• "))
        self.assertEqual(record["heading_vi"], "Khi nào cần trợ giúp")
        self.assertEqual(record["title_vi"], "Cảm lạnh và cúm")
        self.assertEqual(record["scope_tags"], ["emergency", "vn_service"])
        self.assertEqual(record["scope_tags_source"], ["emergency", "foreign_service"])
        self.assertFalse(record["rag_eligible"])
        md = record["translation_metadata"]
        self.assertEqual((md["method"], md["model"], md["prompt_version"]), ("consensus", "consensus", ""))
        self.assertEqual(md["quality_status"], "auto_verified")
        self.assertTrue(md["needs_human_review"])
        self.assertEqual(md["localisation_version"], RULES.version)
        self.assertEqual(md["line_methods"], {"consensus": 3})
        json.dumps(record, ensure_ascii=False)

    def test_passthrough_record(self):
        record = run.passthrough_record(sample_chunks()[2], meta())
        self.assertEqual((record["text_vi"], record["text_en"], record["title_vi"]), (V1_TEXT, "", "Cảm lạnh"))
        self.assertEqual(record["translation_metadata"]["quality_status"], "passthrough_native")
        self.assertFalse(record["translation_metadata"]["needs_human_review"])

    def test_db_rows_rebuilt_line_by_line(self):
        chunks = sample_chunks()
        results, strings = self.results_for(chunks)
        rows, missing = run.db_rows_vi(sample_db_rows(), chunks, results, strings, meta())
        self.assertEqual(missing, 0)
        by_id = {row["id"]: row for row in rows}
        art1 = by_id["uuid-art1"]
        expected = results["c-a1"].lines + [""] + ["Khi nào cần trợ giúp"] + results["c-a2"].lines
        self.assertEqual(art1["content"].split("\n"), expected)
        self.assertEqual((art1["title"], art1["title_en"], art1["language"]), ("Cảm lạnh và cúm", "Cold and flu", "vi"))
        self.assertEqual(art1["translation_model"], "consensus")
        self.assertEqual(art1["untranslated_lines"], 0)
        self.assertEqual(by_id["uuid-art2"]["content"], V1_TEXT)
        self.assertEqual(by_id["uuid-art2"]["translation_model"], "passthrough")

    def test_partly_translated_article_is_not_written(self):
        chunks = sample_chunks()
        results, strings = self.results_for(chunks)
        del results["c-a2"]
        rows, _ = run.db_rows_vi(sample_db_rows(), chunks, results, strings, meta())
        self.assertNotIn("uuid-art1", {row["id"] for row in rows})

    def test_unknown_lines_are_counted(self):
        chunks = sample_chunks()
        results, strings = self.results_for(chunks)
        db = sample_db_rows()
        db[2]["content"] += "\nA line that is in no chunk"
        rows, missing = run.db_rows_vi(db, chunks, results, strings, meta())
        art3 = {row["id"]: row for row in rows}["uuid-art3"]
        self.assertEqual((missing, art3["untranslated_lines"]), (1, 1))
        self.assertTrue(art3["content"].endswith("A line that is in no chunk"))

    def test_empty_translated_line_does_not_crash(self):
        # REGRESSION: a final line that is "" is falsy, so db_rows_vi falls through to
        # strings[line] and raises KeyError (one engine that returned nothing is enough)
        chunks = sample_chunks()
        results, strings = self.results_for(chunks, fn=lambda t: "" if t.startswith("Drink") else good(t))
        self.assertEqual(results["c-b1"].lines, [""])
        rows, _ = run.db_rows_vi(sample_db_rows(), chunks, results, strings, meta())
        self.assertIn("uuid-art3", {row["id"] for row in rows})

    def test_review_queue_order_and_escaping(self):
        def record(cid, status, tags, review=True, title="T"):
            return {"chunk_id": cid, "chunk_key": cid, "title_en": title, "heading_en": "H", "source_url": "u",
                    "scope_tags_source": tags, "text_en": "a | b\nc", "text_vi": "x | y\nz",
                    "translation_metadata": {"needs_human_review": review, "quality_status": status}}

        records = [record("ok", "auto_verified", [], review=False, title="A"),
                   record("warn", "auto_verified_with_warnings", [], title="A"),
                   record("emerg", "auto_verified", ["emergency"], title="Z"),
                   record("fail", "failed_quality_gate", [], title="Z")]
        text = run.review_queue(records, {"fail": {"warnings": ["numbers_preserved: line 1: missing"]}})
        order = [re.search(r"`(\w+)`", line).group(1) for line in text.split("\n") if line.startswith("- chunk:")]
        self.assertEqual(order, ["fail", "emerg", "warn"])
        self.assertIn("| 1 | a \\| b | x \\| y |", text)
        self.assertIn("- cảnh báo: numbers_preserved: line 1: missing", text)


# =============================================================================
# run.main end to end (fake engines, fake or no LLM, temporary files)
# =============================================================================

ENGINE_FUNCS = {"nllb": dropper, "vinai": good, "medcrab": paraphrase}


class FakeFactory:
    def __init__(self, funcs=None):
        self.funcs = funcs or ENGINE_FUNCS
        self.created: list[FakeEngine] = []

    def __call__(self, name, config, device="auto"):
        key = config.get("engine_aliases", {}).get(name.lower(), name.lower())
        if key not in self.funcs:
            raise ValueError(f"Unknown engine '{name}'")
        engine = FakeEngine(key, self.funcs[key])
        self.created.append(engine)
        return engine

    @property
    def translate_calls(self) -> int:
        return sum(len(e.calls) for e in self.created)


class TestRunMain(unittest.TestCase):

    def setUp(self):
        self.dir = tmpdir(self)
        self.input = self.dir / "chunks.jsonl"
        self.db = self.dir / "db_rows.json"
        self.out = self.dir / "out"
        self.cache = self.dir / "cache"
        self.write_input(sample_chunks(), sample_db_rows())
        names = mock.patch.object(run, "load_medicine_names", return_value=frozenset({"paracetamol"}))
        names.start()
        self.addCleanup(names.stop)
        self.factory = self.patch_engines()

    def patch_engines(self, funcs=None) -> FakeFactory:
        factory = FakeFactory(funcs)
        patcher = mock.patch.object(run, "get_engine", factory)
        patcher.start()
        self.addCleanup(patcher.stop)
        return factory

    def patch_llm(self, client):
        patcher = mock.patch.object(run.LLMClient, "from_env", return_value=client)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write_input(self, chunks, db_rows, newline="\n"):
        with self.input.open("w", encoding="utf-8", newline="") as handle:
            handle.write("".join(json.dumps(c, ensure_ascii=False) + newline for c in chunks))
        self.db.write_text(json.dumps(db_rows, ensure_ascii=False), encoding="utf-8")

    def main(self, *extra, refiner="consensus"):
        argv = ["--input", str(self.input), "--db-rows", str(self.db), "--output", str(self.out),
                "--cache-dir", str(self.cache)]
        if refiner:
            argv += ["--refiner", refiner]
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = run.main(argv + list(extra))
        self.stdout, self.stderr = out.getvalue(), err.getvalue()
        return code

    def records(self) -> dict[str, dict]:
        lines = (self.out / "chunks_vi.jsonl").read_text(encoding="utf-8").splitlines()
        return {r["chunk_id"]: r for r in map(json.loads, lines)}

    def read_json(self, name: str):
        return json.loads((self.out / name).read_text(encoding="utf-8"))

    # -- consensus only ---------------------------------------------------
    def test_consensus_run_writes_every_output(self):
        self.assertEqual(self.main(), 0, self.stdout + self.stderr)
        for name in ("chunks_vi.jsonl", "db_rows_vi.json", "candidates.jsonl", "quality_report.json",
                     "review_queue.md", "summary.json"):
            self.assertTrue((self.out / name).exists(), name)
        records = self.records()
        self.assertEqual(set(records), {"c-a1", "c-a2", "c-b1", "c-v1"})
        a2 = records["c-a2"]
        self.assertNotIn("999", a2["text_vi"])
        self.assertIn("115", a2["text_vi"])
        self.assertIsNone(UK_TERMS.search(a2["text_vi"]))
        self.assertEqual(a2["scope_tags"], ["emergency", "vn_service"])
        self.assertTrue(a2["translation_metadata"]["needs_human_review"])
        self.assertEqual(a2["translation_metadata"]["engines"], ["fake/nllb", "fake/vinai", "fake/medcrab"])
        self.assertIn("2 viên", records["c-a1"]["text_vi"])
        self.assertEqual(records["c-v1"]["translation_metadata"]["method"], "passthrough")
        self.assertEqual(records["c-v1"]["text_vi"], V1_TEXT)

    def test_engines_never_see_uk_services(self):
        self.main()
        seen = [t for e in self.factory.created for t in e.seen]
        self.assertTrue(seen)
        for text in seen:
            self.assertIsNone(UK_TERMS.search(text), text)
        self.assertNotIn("Bạn nên uống đủ nước.", seen)

    def test_summary_and_reports(self):
        self.main()
        summary = self.read_json("summary.json")
        self.assertEqual((summary["chunks_translated"], summary["chunks_passthrough_vi"],
                          summary["total_chunks_output"]), (3, 1, 4))
        self.assertEqual((summary["quality_passed"], summary["quality_failed"]), (3, 0))
        self.assertEqual(summary["always_review_chunks"], 1)
        self.assertEqual(summary["always_review_chunks_passed"], 1)
        self.assertEqual(summary["refiner"], {"model": "consensus"})
        self.assertEqual(summary["db_rows_written"], 3)
        self.assertEqual(summary["db_rows_untranslated_lines"], 0)
        self.assertGreater(summary["engine_vs_final"]["nllb"]["hard_issue_line_rate"], 0)
        reports = self.read_json("quality_report.json")
        self.assertEqual([r["chunk_id"] for r in reports[:3]], ["c-a1", "c-a2", "c-b1"])
        self.assertTrue(all(r["chunk_id"].startswith("extra-") for r in reports[3:]))
        queue = (self.out / "review_queue.md").read_text(encoding="utf-8")
        self.assertIn("`c-a2`", queue)
        self.assertNotIn("`c-b1`", queue)
        candidates = [json.loads(line) for line in (self.out / "candidates.jsonl").read_text(encoding="utf-8")
                      .splitlines()]
        self.assertEqual(len(candidates), 3)
        line = candidates[0]["lines"][1]
        self.assertEqual(set(line), {"en", "nllb", "vinai", "medcrab", "consensus", "final", "method"})
        self.assertEqual(line["final"], line["consensus"])

    def test_db_rows_vi(self):
        self.main()
        rows = {row["id"]: row for row in self.read_json("db_rows_vi.json")}
        records = self.records()
        art1 = rows["uuid-art1"]["content"].split("\n")
        self.assertEqual(art1, records["c-a1"]["text_vi"].split("\n") + ["", "Khi nào cần trợ giúp"]
                         + records["c-a2"]["text_vi"].split("\n"))
        self.assertEqual(rows["uuid-art3"]["content"], "Tự chăm sóc\n" + records["c-b1"]["text_vi"])
        self.assertEqual(rows["uuid-art2"]["translation_model"], "passthrough")

    def test_second_run_translates_nothing(self):
        self.main()
        first = self.records()
        factory = self.patch_engines()
        self.assertEqual(self.main(), 0)
        self.assertEqual(factory.translate_calls, 0)
        self.assertEqual({k: r["text_vi"] for k, r in self.records().items()},
                         {k: r["text_vi"] for k, r in first.items()})

    def test_failed_gate_exit_code(self):
        self.assertEqual(self.main("--engines", "nllb"), 1)
        summary = self.read_json("summary.json")
        self.assertIn("c-a1", summary["failed_chunk_ids"])
        self.assertIn("numbers_preserved", summary["hard_issues"])
        queue = (self.out / "review_queue.md").read_text(encoding="utf-8")
        listed = [line for line in queue.splitlines() if line.startswith("- chunk:")]
        self.assertIn("`c-a1`", queue)
        self.assertIn("failed_quality_gate", listed[0])

    def test_max_chunks_and_partial_articles(self):
        self.assertEqual(self.main("--max-chunks", "1"), 0)
        records = self.records()
        self.assertEqual(set(records), {"c-a1", "c-v1"})
        rows = {row["id"] for row in self.read_json("db_rows_vi.json")}
        self.assertEqual(rows, {"uuid-art2"})

    def test_only_tag(self):
        self.assertEqual(self.main("--only-tag", "emergency"), 0)
        self.assertEqual(set(self.records()), {"c-a2", "c-v1"})

    def test_engine_alias_on_the_command_line(self):
        self.assertEqual(self.main("--engines", "medev"), 0)
        self.assertEqual([e.key for e in self.factory.created], ["vinai"])

    def test_windows_newlines_and_unicode(self):
        chunks = [chunk("c-u", "artu", "Don’t take ibuprofen – it’s not safe\n• if you’re pregnant",
                        heading="Who can’t take it", path=["Ibuprofen", "Who can’t take it › Adults"],
                        title="Ibuprofen")]
        self.write_input(chunks, [], newline="\r\n")
        funcs = {"vinai": lambda t: {"Don’t take ibuprofen – it’s not safe": "Không dùng ibuprofen – không an toàn",
                                     "if you’re pregnant": "nếu bạn đang mang thai"}.get(t, "Bản dịch: " + t)}
        self.patch_engines(funcs)
        self.assertEqual(self.main("--engines", "vinai"), 0, self.stdout + self.stderr)
        record = self.records()["c-u"]
        self.assertEqual(record["text_vi"], "Không dùng ibuprofen – không an toàn\n• nếu bạn đang mang thai")
        self.assertEqual(record["heading_path_en"], ["Ibuprofen", "Who can’t take it › Adults"])
        self.assertEqual(len(record["heading_path_vi"]), 2)
        self.assertTrue(all(record["heading_path_vi"]))
        raw = (self.out / "chunks_vi.jsonl").read_bytes().decode("utf-8")
        self.assertIn("Không dùng ibuprofen – không an toàn", raw)

    # -- with an LLM ------------------------------------------------------
    def test_llm_run_and_resume(self):
        client = FakeLLM()
        self.patch_llm(client)
        self.assertEqual(self.main(refiner="llm"), 0, self.stdout + self.stderr)
        record = self.records()["c-a1"]
        md = record["translation_metadata"]
        self.assertEqual((md["method"], md["model"]), ("llm_fusion", "fake-llm"))
        self.assertTrue(md["prompt_version"])
        self.assertEqual(md["line_methods"], {"llm": 3})
        self.assertEqual(self.read_json("summary.json")["refiner"]["model"], "fake-llm")
        self.assertEqual({row["translation_model"] for row in self.read_json("db_rows_vi.json")},
                         {"fake-llm", "passthrough"})
        first_calls = client.calls
        self.assertGreater(first_calls, 0)
        factory = self.patch_engines()
        self.assertEqual(self.main(refiner="llm"), 0)
        self.assertEqual(factory.translate_calls, 0)
        self.assertEqual(client.calls, first_calls)

    def test_auto_refiner_uses_the_llm_when_configured(self):
        self.patch_llm(FakeLLM())
        self.assertEqual(self.main(refiner=None), 0)
        self.assertEqual(self.records()["c-a1"]["translation_metadata"]["method"], "llm_fusion")

    def test_auto_refiner_without_llm_is_consensus(self):
        self.patch_llm(None)
        self.assertEqual(self.main(refiner=None), 0)
        self.assertEqual(self.records()["c-a1"]["translation_metadata"]["method"], "consensus")

    def test_llm_only(self):
        client = FakeLLM()
        self.patch_llm(client)
        self.assertEqual(self.main("--engines", "none", refiner="llm"), 0, self.stdout + self.stderr)
        self.assertEqual(self.factory.created, [])
        self.assertIn("115", self.records()["c-a2"]["text_vi"])

    def test_bad_llm_line_falls_back_per_line(self):
        def script(n, items):
            answer = []
            for item in items:
                lines = [FakeLLM.line_for(line) for line in item["lines"]]
                lines = [_DIGITS.sub("", line) if "2 viên" in line else line for line in lines]
                answer.append({"id": item["id"], "vi": lines})
            return json.dumps(answer, ensure_ascii=False)

        self.patch_llm(FakeLLM(script))
        self.assertEqual(self.main(refiner="llm"), 0)
        record = self.records()["c-a1"]
        self.assertEqual(record["translation_metadata"]["line_methods"], {"llm": 2, "consensus_fallback": 1})
        self.assertIn("2 viên", record["text_vi"])

    def test_llm_stop_exit_code_and_resume(self):
        def script(n, items):
            raise Stop("HTTP 429")

        self.patch_llm(FakeLLM(script))
        self.assertEqual(self.main(refiner="llm"), 3)
        self.assertEqual(self.read_json("summary.json")["refiner"]["stopped"], "HTTP 429")
        self.assertEqual(self.records()["c-a1"]["translation_metadata"]["method"], "consensus")
        client = FakeLLM()
        self.patch_llm(client)
        self.assertEqual(self.main(refiner="llm"), 0)
        self.assertGreater(client.calls, 0)
        self.assertEqual(self.records()["c-a1"]["translation_metadata"]["method"], "llm_fusion")

    # -- configuration problems -------------------------------------------
    def test_no_chunks(self):
        self.input.write_text("", encoding="utf-8")
        self.assertEqual(self.main(), 2)
        self.assertIn("No normalized chunks", self.stderr)
        self.input.unlink()
        self.assertEqual(self.main(), 2)
        self.assertFalse(self.out.exists())

    def test_unknown_engine(self):
        self.assertEqual(self.main("--engines", "nllb,bogus"), 2)
        self.assertIn("bogus", self.stderr)

    def test_unknown_engine_with_the_real_factory(self):
        with mock.patch.object(run, "get_engine", get_engine):
            self.assertEqual(self.main("--engines", "bogus"), 2)

    def test_llm_refiner_without_endpoint(self):
        self.patch_llm(None)
        with mock.patch.object(run.LLMClient, "missing_env", return_value=["LLM_BASE_URL", "LLM_API_KEY"]):
            self.assertEqual(self.main(refiner="llm"), 2)
        self.assertIn("LLM_BASE_URL", self.stderr)
        self.assertFalse(self.out.exists())

    def test_nothing_can_translate(self):
        self.assertEqual(self.main("--engines", "none"), 2)
        self.patch_llm(None)
        self.assertEqual(self.main("--engines", "none", refiner="auto"), 2)
        self.assertIn("Nothing can translate", self.stderr)

    # -- dry run / status -------------------------------------------------
    def test_dry_run_loads_nothing(self):
        self.assertEqual(self.main("--dry-run"), 0)
        self.assertEqual(self.factory.translate_calls, 0)
        self.assertFalse(self.out.exists())
        self.assertFalse(self.cache.exists())
        self.assertIn("3 to translate", self.stdout)
        self.assertIn("[c-a1]", self.stdout)

    def test_status(self):
        self.assertEqual(self.main("--status"), 0)
        self.assertIn("No translation output yet.", self.stdout)
        self.main()
        self.assertEqual(self.main("--status"), 0)
        self.assertEqual(json.loads(self.stdout), self.read_json("summary.json"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
