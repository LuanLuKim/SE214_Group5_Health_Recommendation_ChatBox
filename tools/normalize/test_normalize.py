"""Tests for the normalization pipeline — no network, no DB, no app imports."""
import json
import sys
from pathlib import Path
from unittest import SkipTest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EXTRACTED_DIR = ROOT / ".data" / "collection" / "extracted"

from normalizer.schema import Block, Section, NormalizedArticle, digest
from normalizer.transform import transform, scope_tags
from run import load_url_topics


# ── Unit tests ─────────────────────────────────────────────────────────

def test_digest():
    assert len(digest("hello")) == 64
    assert digest("hello") == digest("hello")
    assert digest("hello") != digest("world")


def test_block_dataclass():
    b = Block(id="L0001", tag="p", text="Hello world")
    assert b.text == "Hello world"
    assert b.list_depth == 0
    assert b.links == []


def test_section_body_text():
    s = Section(id="s01", heading="Test", heading_level=2, blocks=[
        Block(id="L1", tag="p", text="Paragraph one."),
        Block(id="L2", tag="li", text="Item A", list_depth=1),
        Block(id="L3", tag="li", text="Item B", list_depth=1),
        Block(id="L4", tag="p", text="Paragraph two."),
    ])
    body = s.body_text
    assert "Paragraph one." in body
    assert "• Item A" in body
    assert "• Item B" in body
    assert "Paragraph two." in body


def test_scope_tags_medication():
    assert "medication" in scope_tags("Treatment", "Take antihistamines daily.")
    assert "medication" in scope_tags("Điều trị", "Sử dụng paracetamol hạ sốt.")


def test_scope_tags_paediatric():
    assert "paediatric" in scope_tags("When to act", "Watch your child for symptoms.")
    assert "paediatric" in scope_tags("Đối tượng", "Trẻ em và trẻ sơ sinh cần chú ý.")


def test_scope_tags_services():
    assert "foreign_service" in scope_tags("", "Call 999 immediately.")
    assert "vn_service" in scope_tags("", "Đến trạm y tế gần nhất.")


def test_scope_tags_emergency():
    assert "emergency" in scope_tags("Immediate action required: Call 999", "Something serious.")
    assert "emergency" in scope_tags("Dấu hiệu cảnh báo cấp cứu", "Khó thở dữ dội.")


def test_scope_tags_clean():
    assert scope_tags("Overview", "This is a general health topic.") == []


def test_generic_chunk_accumulation():
    """Verify that generic block-accumulation chunker cleanly subdivides
    large sections at block boundaries without exceeding max_words."""
    # Create a section with 10 paragraphs, 50 words each = 500 words total
    blocks = [
        Block(id=f"L{i}", tag="p", text="word " * 49 + f"end{i}.")
        for i in range(10)
    ]
    article = NormalizedArticle(
        id="chunk_test",
        title="Accumulation Test",
        topic="test",
        language="en",
        source_name="Test",
        source_url="https://example.com/test",
        source_updated_at=None,
        source_last_reviewed=None,
        source_next_review_due=None,
        fetched_at="2026-01-01T00:00:00Z",
        sections=[Section(id="s01", heading="Big Section", heading_level=2, blocks=blocks)],
    )

    # Chunk with max_words = 150
    chunks = article.to_chunks(max_words=150)
    assert len(chunks) > 1, f"Expected subdivision, got {len(chunks)} chunks"
    for c in chunks:
        assert c["word_count"] <= 150, f"Chunk exceeded limit: {c['word_count']} words"
        assert not c["over_limit"]
        assert "Accumulation Test\nBig Section" in c["text"]


def test_normalized_article_db_row():
    article = NormalizedArticle(
        id="test03", title="DB Test", topic="cardiovascular", language="en",
        source_name="NHS", source_url="https://example.com/db",
        source_updated_at=None, source_last_reviewed=None,
        source_next_review_due=None, fetched_at="2026-01-01T00:00:00Z",
        sections=[
            Section(id="s00", heading="", heading_level=0, blocks=[
                Block(id="L1", tag="p", text="Heart health info."),
            ]),
        ],
    )
    row = article.to_db_row()
    assert row["title"] == "DB Test"
    assert row["topic"] == "cardiovascular"
    assert "Heart health info." in row["content"]
    assert row["source_name"] == "NHS"
    assert row["review_status"] == "DRAFT"
    assert row["is_synthetic"] is False


def test_load_url_topics(tmp_path=None):
    from pathlib import Path
    urls_file = ROOT / "docs" / "urls.txt"
    topics = load_url_topics(urls_file)
    assert isinstance(topics, dict)
    assert len(topics) >= 5
    assert topics.get("https://www.nhs.uk/conditions/asthma/") == "respiratory"


# ── Integration tests with all 10 real extracted files ─────────────────

def _load_all_extracted() -> list[dict]:
    paths = [
        path for path in sorted(EXTRACTED_DIR.glob("*.json"))
        if path.name != "extracted.example.json"
    ]
    if not paths:
        raise SkipTest("No local extracted dataset")
    return [json.loads(path.read_text(encoding="utf-8")) for path in paths]


def test_transform_real_data():
    """All 10 extracted files (NHS & WHO) must transform without error."""
    docs = _load_all_extracted()
    assert len(docs) >= 10, f"Expected ≥10 extracted files, got {len(docs)}"
    for doc in docs:
        article = transform(doc)
        assert article.title
        assert article.source_url.startswith("https://")
        assert len(article.sections) >= 1
        assert len(article.content_text) > 100, f"{article.title}: content too short"


def test_chunks_provenance_and_limits():
    """Every chunk must have provenance and satisfy max_words bound."""
    docs = _load_all_extracted()
    for doc in docs:
        article = transform(doc)
        for chunk in article.to_chunks(max_words=500):
            assert chunk["source_url"] == article.source_url
            assert chunk["source_name"] == article.source_name
            assert chunk["text_sha256"] == digest(chunk["text"])
            assert not chunk["over_limit"], (
                f"{article.title} / {chunk['heading']}: {chunk['word_count']} words > 500"
            )


# ── Runner ─────────────────────────────────────────────────────────────

def _run_tests():
    passed = failed = skipped = 0
    test_funcs = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for func in test_funcs:
        name = func.__name__
        try:
            func()
            print(f"  OK {name}")
            passed += 1
        except SkipTest as exc:
            print(f"  SKIP {name}: {exc}")
            skipped += 1
        except Exception as exc:
            print(f"  FAIL {name}: {exc}")
            failed += 1
    print(f"\n{passed} passed, {failed} failed, {skipped} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_run_tests())
