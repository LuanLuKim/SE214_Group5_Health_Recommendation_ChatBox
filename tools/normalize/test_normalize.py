"""Tests for the normalization pipeline — no network, no DB, no app imports.

Synthetic markup only; the last two tests also run on the local dataset in
``.data/`` when it exists and are skipped otherwise.
"""
import json
import shutil
import sys
import tempfile
from hashlib import sha256
from pathlib import Path
from unittest import SkipTest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COLLECTION = ROOT / ".data" / "collection"

from normalizer.schema import Block, Section, NormalizedArticle, digest, article_uuid
from normalizer.transform import transform, scope_tags, clean_blocks
from run import CORPORA, load_url_topics, load_url_topics_from, load_extracted, main


def _doc(blocks, *, title="Flu", url="https://www.nhs.uk/conditions/example/", **extra):
    out = []
    for i, b in enumerate(blocks, 1):
        tag, text = b[0], b[1]
        block = {"id": f"L{i:04d}", "tag": tag, "text": text, "list_depth": 1 if tag == "li" else 0,
                 "dom_path": f"main > div:nth-of-type({i})", "context_path": None, "table": None, "links": []}
        if len(b) > 2:
            block.update(b[2])
        out.append(block)
    return {"url": url, "final_url": extra.pop("final_url", url), "title": title, "language": "en",
            "adapter": "nhs", "fetched_at": "2026-01-01T00:00:00Z", "blocks": out, **extra}


def _words(n, word="word"):
    return " ".join([word] * n) + "."


# ── Scope tags ─────────────────────────────────────────────────────────

def test_scope_tags_medication():
    assert "medication" in scope_tags("Treatment", "Take antihistamines daily.")
    assert "medication" in scope_tags("Điều trị", "Sử dụng paracetamol hạ sốt.")
    assert "medication" in scope_tags("Uống thuốc theo chỉ dẫn.")


def test_scope_tags_paediatric():
    assert "paediatric" in scope_tags("When to act", "Watch your child for symptoms.")
    assert "paediatric" in scope_tags("Đối tượng", "Trẻ em và trẻ sơ sinh cần chú ý.")
    assert "paediatric" in scope_tags("Cho bé uống nhiều nước.")


def test_vietnamese_substrings_do_not_trigger_tags():
    assert "paediatric" not in scope_tags("Béo phì làm tăng nguy cơ bệnh tim.")
    assert "medication" not in scope_tags("Khoảng 3/4 số ca mắc COPD là do hút thuốc lá.")
    assert "medication" not in scope_tags("Bỏ thuốc lá giúp giảm nguy cơ.")


def test_scope_tags_services():
    assert "foreign_service" in scope_tags("", "Call 999 immediately.")
    assert "vn_service" in scope_tags("", "Đến trạm y tế gần nhất.")


def test_scope_tags_emergency_anywhere_in_text():
    assert "emergency" in scope_tags("Immediate action required: Call 999", "Something serious.")
    assert "emergency" in scope_tags("Dấu hiệu cảnh báo cấp cứu", "Khó thở dữ dội.")
    long_intro = _words(80, "rest")
    assert "emergency" in scope_tags("Self-care", long_intro + " If it gets worse, call 999 or go to A&E.")
    assert "emergency" in scope_tags("It can be life-threatening and needs an ambulance.")


def test_food_interaction_needs_substance_next_to_a_medicine():
    from normalizer.tags import interaction_terms
    assert "food_interaction" in scope_tags("Do not drink alcohol while taking this medicine.")
    # medicine names come from the index vocabulary, not from code
    names = frozenset({"examplestatin"})
    assert interaction_terms("Grapefruit juice can raise the level of examplestatin.") == []
    assert interaction_terms("Grapefruit juice can raise the level of examplestatin.", medicine_names=names) == ["grapefruit"]
    assert interaction_terms("Không uống rượu bia khi đang dùng thuốc kháng sinh.") == ["alcohol"]
    assert interaction_terms("Uống thuốc cách xa sữa và cà phê ít nhất 2 giờ.") == ["dairy", "caffeine"]
    # nutrition facts alone are not interactions
    assert interaction_terms("Milk and cheese are good sources of calcium.") == []
    assert interaction_terms("Sữa mẹ có nhiều canxi.") == []
    # tobacco is not a medicine; breast milk is not dairy
    assert interaction_terms("Hút thuốc lá và uống rượu làm tăng nguy cơ.") == []
    assert interaction_terms("Cho trẻ bú sữa mẹ khi uống thuốc.") == []


def test_food_interaction_reads_lead_in_with_its_bullets_and_header():
    from normalizer.tags import interaction_terms
    text = "Overview › How to take it\nDo not take your medicine at the same time as:\n• milk\n• tea or coffee\nSome other paragraph."
    assert interaction_terms(text) == ["dairy", "caffeine"]
    # on a medicine page (its topic says so) every paragraph has a medicine context
    assert interaction_terms("Example › Common questions\nAvoid grapefruit juice.") == []
    assert interaction_terms("Example › Common questions\nAvoid grapefruit juice.", medicine_page=True) == ["grapefruit"]
    # the header line is not scanned: a medicine name in the title alone is not enough
    assert interaction_terms("Examplestatin › Overview\nGood sources include cheese.",
                             medicine_names=frozenset({"examplestatin"})) == []
    # sibling bullets are separate: an allergen list naming milk and medicines is not an interaction
    assert interaction_terms("Allergies › Causes\nCommon allergens include:\n• foods such as cows' milk\n"
                             "• medicines such as antibiotics") == []
    # a heading is not the page subject
    assert interaction_terms("Food poisoning › A pharmacist can help\nGet help if:\n• vomit looks like ground coffee") == []
    # the substance word does not supply its own medicine context, and an allergy is not an interaction
    assert interaction_terms("Cough › Treatment\nTry a herbal medicine called pelargonium.") == []
    assert interaction_terms("Nosebleed › Treatment\nA doctor may prescribe a cream; check it if you have a soya allergy.") == []
    # calcium as nutrition (prescribed or recommended itself) is lifestyle content, not an interaction
    assert interaction_terms("Bones › Supplements\nIf you have osteoporosis, your GP may prescribe a calcium supplement.\n"
                             "Go for one that contains calcium and vitamin D. Your GP or pharmacist can help you choose.") == []
    assert interaction_terms("Bones › Supplements\nDo not take calcium at the same time as this medicine.") == ["calcium"]


def test_interaction_mentions_and_breast_milk_and_drug_class():
    from normalizer.tags import interaction_review
    review = interaction_review("Example › About\nExamplodipine is a type of medicine called a calcium-channel blocker.",
                                medicine_names=frozenset({"examplodipine"}))
    assert review["terms"] == [] and review["mentions"] == ["calcium"]
    assert interaction_review("Example › Breastfeeding\nSmall amounts of the medicine pass into breast milk.")["mentions"] == []


def test_resolver_precedence_human_llm_resolution_and_safety():
    from normalizer.tags import interaction_candidates, interaction_review, resolve
    text = "Example › Food\nTake this medicine with a glass of milk."  # rule: linked → interaction
    cand = interaction_candidates(text)[0]
    assert cand["rule"] is True and cand["terms"] == ["dairy"]
    assert resolve(cand, None)["by"] == "rule"
    agree = resolve(cand, {"interaction": True, "terms": ["dairy"], "model": "m"})
    assert agree == {"terms": ["dairy"], "by": "m", "conflict": False, "needs_review": False}
    # LLM disagrees and nothing settled it: keep as interaction (safety first) and ask a person
    unresolved = resolve(cand, {"interaction": False, "model": "m"})
    assert unresolved["terms"] == ["dairy"] and unresolved["conflict"] and unresolved["needs_review"]
    # an adjudication settles the conflict
    settled = resolve(cand, {"interaction": False, "model": "m", "resolution": {"interaction": False, "model": "m2"}})
    assert settled["terms"] == [] and settled["by"] == "m2:resolved" and not settled["needs_review"]
    # a human decision always wins
    human = resolve(cand, {"interaction": True, "model": "m", "human": {"interaction": False}})
    assert human["terms"] == [] and human["by"] == "human"
    # verdicts flow through interaction_review by candidate id
    review = interaction_review(text, verdicts={cand["id"]: {"interaction": False, "model": "m",
                                                             "resolution": {"interaction": False, "model": "m"}}})
    assert review["terms"] == [] and review["mentions"] == ["dairy"]
    assert review["evidence"][0]["conflict"] is True


def test_chunk_tag_resolver_and_heading_lines():
    from normalizer.tags import interaction_candidates, resolve_tags
    rule = ["medication", "foreign_service"]
    assert resolve_tags(rule, None) == {"tags": rule, "by": "rule", "conflicts": [], "needs_review": [], "flags": []}
    review = {"tags": ["emergency", "medication"], "flags": ["ui_leftover", "bogus"], "model": "m"}
    open_ = resolve_tags(rule, review)  # emergency added, foreign_service dropped: both disputed, both kept
    assert open_["tags"] == ["emergency", "medication", "foreign_service"]
    assert open_["conflicts"] == open_["needs_review"] == ["emergency", "foreign_service"] and open_["flags"] == ["ui_leftover"]
    settled = resolve_tags(rule, {**review, "resolution": {"emergency": False, "foreign_service": True, "_reason": "x"}})
    assert settled["tags"] == ["medication", "foreign_service"] and settled["needs_review"] == []
    assert resolve_tags(rule, {**review, "human": {"tags": ["paediatric"]}})["tags"] == ["paediatric"]
    # a merged section's heading line is context, not a claim; a bullet keeps its whole lead-in
    text = ("Example › Food\nExample › Food and alcohol with this medicine\nYou can eat normally.\n"
            "Some medicines can stop it working. These include:\n• the herbal supplement St John's wort")
    cands = interaction_candidates(text)
    assert [c["sentence"] for c in cands] == [
        "Some medicines can stop it working. These include: the herbal supplement St John's wort"]


def test_chunks_carry_interaction_terms():
    doc = _doc([("h2", "Common questions"), ("p", "Do not drink alcohol while taking this medicine " + _words(20))],
               title="Example")
    chunk = transform(doc).to_chunks()[0]
    assert chunk["interaction_terms"] == ["alcohol"] and "food_interaction" in chunk["scope_tags"]
    # a page whose URL-list topic is "medication" gives every paragraph a medicine context
    doc = _doc([("h2", "Food and drink"), ("p", "Avoid grapefruit juice. " + _words(20))], title="Example")
    assert transform(doc).to_chunks()[0]["interaction_terms"] == []
    assert transform(doc, topic="medication").to_chunks()[0]["interaction_terms"] == ["grapefruit"]
    # names from the index vocabulary reach chunk tagging
    doc = _doc([("h2", "Food"), ("p", "Grapefruit juice affects examplestatin. " + _words(20))], title="Example")
    chunk = transform(doc).to_chunks(medicine_names=frozenset({"examplestatin"}))[0]
    assert chunk["interaction_terms"] == ["grapefruit"]


def test_medicine_names_come_from_index_links():
    from normalizer.vocabulary import medicine_names
    base = "https://www.example.org/medicines/"
    links = [("Examplol for adults", "examplol-for-adults/"), ("Examplol for children", "examplol-for-children/"),
             ("Brandix, see Genericine (Brandix)", "genericine/"), ("Ferric thing", "ferric-thing/"),
             ("Back to top", "#top"), ("Elsewhere", "https://www.example.org/conditions/x/")]
    blocks = [{"text": t, "links": [{"text": t, "href": h if h.startswith("http") else base + h}]} for t, h in links]
    names = medicine_names([{"url": base, "blocks": blocks}])
    assert {"examplol for adults", "examplol for children", "examplol", "brandix", "genericine", "ferric thing"} <= names
    # a first word starting only one entry is not a name on its own; links off the index are not medicines
    assert "ferric" not in names and "back to top" not in names and "elsewhere" not in names


def test_scope_tags_clean():
    assert scope_tags("Overview", "This is a general health topic.") == []


# ── Block cleaning ─────────────────────────────────────────────────────

def test_table_rows_keep_column_labels():
    def cell(tag, text, row, col):
        return (tag, text, {"table": {"id": "t1", "row": row, "column": col}})
    blocks = clean_blocks(_doc([
        cell("th", "Flu", 0, 0), cell("th", "Cold", 0, 1),
        cell("p", "Appears quickly", 1, 0), cell("p", "Appears gradually", 1, 1),
    ])["blocks"], "Flu")
    assert [b["text"] for b in blocks] == ["Flu: Appears quickly; Cold: Appears gradually"]


def test_labels_soft_hyphen_lists_and_page_metadata():
    blocks = clean_blocks(_doc([
        ("text", "Information:"), ("p", "Do not drive to A&E."),
        ("text", "Các biện pháp như: - ­Rửa tay - ­Đeo khẩu trang"),
        ("text", "-"),
        ("p", "The main symptoms include:"), ("li", "a cough"),
        ("p", "Page last reviewed: 03 February 2026 Next review due: 03 February 2029"),
    ])["blocks"], "Flu")
    assert [b["text"] for b in blocks] == [
        "Information: Do not drive to A&E.", "Các biện pháp như:", "Rửa tay", "Đeo khẩu trang",
        "The main symptoms include:", "a cough"]
    assert blocks[2]["tag"] == "li"


# ── Sections and chunks ────────────────────────────────────────────────

def test_heading_path_keeps_parents():
    article = transform(_doc([
        ("h1", "Flu"), ("h2", "How to treat flu yourself"), ("p", _words(10)),
        ("h3", "Do"), ("p", "rest and sleep"), ("h3", "Don't"), ("p", "do not give aspirin to children"),
    ]))
    paths = [s.heading_path for s in article.sections]
    assert ["How to treat flu yourself", "Do"] in paths
    assert ["How to treat flu yourself", "Don't"] in paths
    chunk = article.to_chunks()[0]
    assert chunk["text"].startswith("Flu › How to treat flu yourself\n")
    assert "\nDo\nrest and sleep" in chunk["text"]


def test_summary_heading_ends_with_its_details_block():
    details = "main > details:nth-of-type(1)"
    article = transform(_doc([
        ("h2", "Symptoms"), ("p", _words(5)),
        ("summary", "Rash on dark skin", {"context_path": details}),
        ("p", "Inside the expander.", {"dom_path": details + " > div:nth-of-type(1) > p:nth-of-type(1)"}),
        ("p", "After the expander."),
    ]))
    by_text = {b.text: s.heading_path for s in article.sections for b in s.blocks}
    assert by_text["Inside the expander."] == ["Symptoms", "Rash on dark skin"]
    assert by_text["After the expander."] == ["Symptoms"]


def test_consecutive_details_blocks_are_siblings():
    # NHS "Common questions" pages: one <details> per question, nothing between them.
    first, second = "main > details:nth-of-type(1)", "main > details:nth-of-type(2)"
    article = transform(_doc([
        ("summary", "Question one?", {"context_path": first, "dom_path": first + " > summary:nth-of-type(1)"}),
        ("p", "Answer one.", {"dom_path": first + " > div:nth-of-type(1) > p:nth-of-type(1)"}),
        ("summary", "Question two?", {"context_path": second, "dom_path": second + " > summary:nth-of-type(1)"}),
        ("p", "Answer two.", {"dom_path": second + " > div:nth-of-type(1) > p:nth-of-type(1)"}),
    ]))
    by_text = {b.text: s.heading_path for s in article.sections for b in s.blocks}
    assert by_text["Answer one."] == ["Question one?"]
    assert by_text["Answer two."] == ["Question two?"]


def test_warning_card_after_link_list_is_kept_under_section():
    article = transform(_doc([
        ("h2", "Symptoms"), ("p", _words(30)),
        ("h3", "Find out more"), ("li", "Stroke Association", {"links": [{"text": "Stroke Association", "href": "x"}]}),
        ("h3", "Immediate action required: Call 999 now if:"), ("li", "the face has dropped on one side"),
    ]))
    paths = [s.heading_path for s in article.sections]
    assert ["Symptoms", "Immediate action required: Call 999 now if:"] in paths
    assert [d["reason"] for d in article.dropped_sections] == ["links_only"]
    assert all("emergency" in c["scope_tags"] for c in article.to_chunks() if "999" in c["text"])


def test_small_sections_merge_and_large_sections_split():
    small = transform(_doc([("h2", "A"), ("p", _words(20, "a")), ("h2", "B"), ("p", _words(20, "b")), ("h2", "C"), ("p", _words(20, "c"))]))
    assert len(small.to_chunks(max_words=400, min_words=120)) == 1
    big = transform(_doc([("h2", "Big"), *[("p", _words(49, f"w{i}")) for i in range(10)]]))
    chunks = big.to_chunks(max_words=150, min_words=50)
    assert len(chunks) > 1
    for chunk in chunks:
        assert chunk["word_count"] <= 150 and not chunk["over_limit"]
        assert chunk["text"].startswith("Flu › Big\n")


def test_flags_are_per_chunk_not_per_section():
    article = transform(_doc([
        ("h2", "Part one"), *[("p", _words(60, f"w{i}")) for i in range(5)],
        ("p", "If you cannot breathe, call 999."),
    ]))
    chunks = article.to_chunks(max_words=200, min_words=50)
    assert len(chunks) > 1
    assert [("emergency" in c["scope_tags"]) for c in chunks].count(True) == 1


def test_emergency_card_keeps_its_triggers_with_its_expander():
    # NHS 999 card: trigger list, a "What we mean by severe pain" <details>, then an
    # "Information:" note. Packing used to cut after the triggers, so the next chunk
    # showed the 999 heading over the pain definitions with no triggers.
    details = "main > details:nth-of-type(1)"
    card = "Immediate action required: Call 999 or go to A&E if you:"
    article = transform(_doc([
        ("h2", "Part"), ("p", _words(90, "intro")),
        ("h2", card), ("li", "vomit blood or have vomit that looks like coffee"),
        ("li", "have a sudden severe headache"), ("li", "are confused or not responding"),
        ("summary", "What we mean by severe pain", {"context_path": details}),
        ("p", _words(30, "pain"), {"dom_path": details + " > div:nth-of-type(1) > p:nth-of-type(1)"}),
        ("p", "Information: Do not drive to A&E. Ask someone to drive you."),
    ]))
    chunks = article.to_chunks(max_words=150, min_words=30)
    with_pain = [c for c in chunks if "pain pain" in c["text"]]
    assert len(with_pain) == 1
    text = with_pain[0]["text"]
    assert "have a sudden severe headache" in text and "Do not drive to A&E" in text
    assert all(c["word_count"] <= 150 for c in chunks)


def test_junk_and_duplicates_are_dropped_with_reason():
    from normalizer.chunking import build_chunks
    article = transform(_doc([
        ("h2", "Warning"), ("p", _words(200, "same")),
        ("h2", "Warning again"), ("p", _words(200, "same")),
        ("h2", "Find out more"), ("li", "Charity", {"links": [{"text": "Charity", "href": "x"}]}),
        ("h2", "Help improve NHS services"), ("p", "Join the panel and share your experiences."),
    ]))
    assert sorted(d["reason"] for d in article.dropped_sections) == ["boilerplate", "links_only"]
    chunks, dropped = build_chunks(article, max_words=250, min_words=50)
    assert len(chunks) == 1  # same body under another heading is still a duplicate
    assert [d["reason"] for d in dropped] == ["duplicate"] and dropped[0]["duplicate_of"] == chunks[0]["chunk_key"]


def test_hub_page_produces_no_chunks():
    links = [("li", f"Part {i}", {"links": [{"text": f"Part {i}", "href": f"/p{i}"}]}) for i in range(6)]
    article = transform(_doc([("h1", "Flu"), ("p", "Flu is an illness."), *links]))
    assert article.content_kind == "hub"
    assert article.to_chunks() == []


def test_ids_are_deterministic_and_uuid_based():
    doc = _doc([("h2", "A"), ("p", _words(30))], final_url="https://www.nhs.uk/conditions/final/")
    first, second = transform(doc).to_dict(), transform(json.loads(json.dumps(doc))).to_dict()
    assert first["chunks"] == second["chunks"]
    assert first["db_row"]["id"] == article_uuid("https://www.nhs.uk/conditions/example/")
    assert first["chunks"][0]["article_uuid"] == first["db_row"]["id"]
    assert first["db_row"]["source_url"] == "https://www.nhs.uk/conditions/final/"
    assert len(first["chunks"][0]["id"]) == 36


def test_stale_source_and_draft_status():
    article = transform(_doc([("h2", "A"), ("p", _words(30))], source_next_review_due="2026-01-01"))
    chunk = article.to_chunks(as_of="2026-11-10")[0]
    assert chunk["stale_source"] and "stale_source" in chunk["scope_tags"]
    assert not article.to_chunks(as_of="2025-12-31")[0]["stale_source"]
    assert chunk["review_status"] == "DRAFT" and chunk["rag_eligible"] is False
    assert article.to_db_row()["review_status"] == "DRAFT"


def test_section_body_text_bullets():
    s = Section(id="s01", heading="Test", heading_level=2, blocks=[
        Block(id="L1", tag="p", text="Paragraph one."),
        Block(id="L2", tag="li", text="Item A", list_depth=1),
    ])
    assert s.body_text == "Paragraph one.\n• Item A"


# ── Runner ─────────────────────────────────────────────────────────────

def test_load_url_topics():
    topics = load_url_topics(ROOT / "docs" / "urls.txt")
    assert len(topics) >= 5
    assert topics.get("https://www.nhs.uk/conditions/asthma/") == "respiratory"
    assert "https://www.nhs.uk/conditions/stroke/" not in topics  # hub page is commented out


def test_rag_corpus_reads_several_lists_and_rejects_topic_conflicts():
    lists, _ = CORPORA["rag"]
    assert [p.name for p in lists] == ["urls.txt", "urls_lifestyle.txt", "urls_who.txt"]
    tmp = Path(tempfile.mkdtemp())
    try:
        (tmp / "a.txt").write_text("https://www.nhs.uk/a/ skin\nhttps://www.nhs.uk/b/ sleep\n", encoding="utf-8")
        (tmp / "b.txt").write_text("https://www.nhs.uk/b/ sleep\nhttps://www.nhs.uk/c/\n", encoding="utf-8")
        assert load_url_topics_from([tmp / "a.txt", tmp / "b.txt"]) == {
            "https://www.nhs.uk/a/": "skin", "https://www.nhs.uk/b/": "sleep", "https://www.nhs.uk/c/": "general"}
        (tmp / "b.txt").write_text("https://www.nhs.uk/a/ exercise\n", encoding="utf-8")
        try:
            load_url_topics_from([tmp / "a.txt", tmp / "b.txt"])
            raise AssertionError("topic conflict not detected")
        except ValueError as exc:
            assert "URL_TOPIC_CONFLICT" in str(exc)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_runner_reads_current_extraction_and_ids_match():
    tmp = Path(tempfile.mkdtemp())
    try:
        url = "https://www.nhs.uk/conditions/example/"
        key = sha256(url.encode()).hexdigest()
        (tmp / "col" / "extracted").mkdir(parents=True)
        (tmp / "col" / "pages").mkdir()
        old = _doc([("h2", "Old"), ("p", _words(30))])
        new = _doc([("h2", "New"), ("p", _words(30))])
        (tmp / "col" / "extracted" / "old.json").write_text(json.dumps(old), encoding="utf-8")
        (tmp / "col" / "extracted" / "new.json").write_text(json.dumps(new), encoding="utf-8")
        (tmp / "col" / "pages" / f"{key}.json").write_text(json.dumps({"url": url, "extraction_file": "new.json"}), encoding="utf-8")
        (tmp / "urls.txt").write_text(f"{url} respiratory\n", encoding="utf-8")
        args = ["--urls", str(tmp / "urls.txt"), "--collection", str(tmp / "col"), "--output", str(tmp / "out"), "--as-of", "2026-11-10"]
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            assert main(args) == 0
            first = (tmp / "out" / "chunks.jsonl").read_bytes()
            assert main(args) == 0
        assert first == (tmp / "out" / "chunks.jsonl").read_bytes()
        chunks = [json.loads(line) for line in first.decode().splitlines()]
        assert len(chunks) == 1 and "New" in chunks[0]["text"] and chunks[0]["topic"] == "respiratory"
        article = json.loads(next((tmp / "out" / "articles").glob("*.json")).read_text(encoding="utf-8"))
        assert [c["id"] for c in article["chunks"]] == [c["id"] for c in chunks]
        rows = json.loads((tmp / "out" / "db_rows.json").read_text(encoding="utf-8"))
        assert rows[0]["id"] == chunks[0]["article_uuid"]
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_expert_reference_corpus_is_never_rag_eligible():
    tmp = Path(tempfile.mkdtemp())
    try:
        url = "https://www.nhs.uk/medicines/example/"
        key = sha256(url.encode()).hexdigest()
        (tmp / "col" / "extracted").mkdir(parents=True)
        (tmp / "col" / "pages").mkdir()
        doc = _doc([("h2", "Common questions"), ("p", "Avoid alcohol with this medicine. " + _words(30))], url=url)
        (tmp / "col" / "extracted" / "x.json").write_text(json.dumps(doc), encoding="utf-8")
        (tmp / "col" / "pages" / f"{key}.json").write_text(json.dumps({"url": url, "extraction_file": "x.json"}), encoding="utf-8")
        (tmp / "urls.txt").write_text(f"{url} medication\n", encoding="utf-8")
        args = ["--corpus", "expert_reference", "--urls", str(tmp / "urls.txt"), "--collection", str(tmp / "col"),
                "--output", str(tmp / "out"), "--as-of", "2026-11-10"]
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            assert main(args) == 0
        chunks = [json.loads(line) for line in (tmp / "out" / "chunks.jsonl").read_text(encoding="utf-8").splitlines()]
        assert chunks and all(c["corpus"] == "expert_reference" and c["rag_eligible"] is False for c in chunks)
        rows = json.loads((tmp / "out" / "db_rows.json").read_text(encoding="utf-8"))
        assert rows[0]["corpus"] == "expert_reference"
        summary = json.loads((tmp / "out" / "summary.json").read_text(encoding="utf-8"))
        assert summary["corpus"] == "expert_reference" and summary["interaction_term_counts"] == {"alcohol": 1}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ── Local dataset (skipped on a fresh clone) ───────────────────────────

def _real_docs():
    urls = load_url_topics(ROOT / "docs" / "urls.txt")
    docs, _ = load_extracted(COLLECTION, list(urls))
    if not docs:
        raise SkipTest("No local extracted dataset")
    return docs


def test_real_data_transforms_and_respects_limits():
    for doc in _real_docs():
        article = transform(doc)
        assert article.title and article.source_url.startswith("https://")
        for chunk in article.to_chunks():
            assert chunk["text_sha256"] == digest(chunk["text"])
            assert not chunk["over_limit"], f"{article.title}: {chunk['word_count']} words"
            assert chunk["review_status"] == "DRAFT" and chunk["rag_eligible"] is False


def test_real_data_emergency_text_is_always_flagged():
    import re
    pattern = re.compile(r"immediate action required|call 999|\bA&E\b|ambulance|cấp cứu|gọi 115", re.I)
    for doc in _real_docs():
        for chunk in transform(doc).to_chunks():
            if pattern.search(chunk["text"]):
                assert "emergency" in chunk["scope_tags"], chunk["chunk_key"]


def _run_tests():
    passed = failed = skipped = 0
    for name, func in sorted((k, v) for k, v in globals().items() if k.startswith("test_")):
        try:
            func()
            print(f"  OK {name}")
            passed += 1
        except SkipTest as exc:
            print(f"  SKIP {name}: {exc}")
            skipped += 1
        except Exception as exc:
            print(f"  FAIL {name}: {type(exc).__name__}: {exc}")
            failed += 1
    print(f"\n{passed} passed, {failed} failed, {skipped} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_run_tests())
