"""Synthetic markup only; no clinical statements or copied source articles."""
import copy
from pathlib import Path
import tempfile
import unittest

from crawler.config import load_sources, load_urls, validate_url
from crawler.adapters import extract


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.sources = load_sources()

    def parse(self, body, source="nhs", path="/conditions/example/"):
        config = self.sources[source]
        return extract(body, "https://" + config["hosts"][0] + path, config)

    def test_labels_inline_lists_tables_and_asides_survive(self):
        doc = self.parse('''<html lang="vi"><nav>outside</nav><main><h1>Ví dụ</h1>
          <p>A <strong>bold</strong> word.</p><strong>Label:</strong>
          <ul><li>outer<ul><li>inner</li></ul>tail</li></ul>
          <table><tr><th>Key</th><td>Value</td></tr></table>
          <aside><h2>Warning</h2><p>Keep this.</p></aside>
          <nav>inside navigation</nav></main><footer>outside footer</footer></html>''')
        self.assertEqual([b["text"] for b in doc["blocks"]],
                         ["Ví dụ", "A bold word.", "Label:", "outer", "inner", "tail", "Key", "Value", "Warning", "Keep this."])
        self.assertEqual(doc["language"], "vi")
        nested = next(b for b in doc["blocks"] if b["text"] == "inner")
        self.assertEqual(nested["list_depth"], 2)
        cells = [b for b in doc["blocks"] if b["tag"] in {"td", "th"}]
        self.assertEqual([b["table"]["column"] for b in cells], [0, 1])
        self.assertTrue(doc["body_text_coverage"])

    def test_who_qa_keeps_button_question_and_hidden_answer(self):
        doc = self.parse('''<html lang="vi"><h1>Hỏi đáp</h1><nav>menu</nav>
          <div class="qa-details__wrapper"><div class="sf-accordion">
          <button>Question?</button><div hidden><p>Answer.</p></div></div></div></html>''',
                         "who", "/news/questions-and-answers/q-a-detail/example")
        self.assertEqual(doc["title"], "Hỏi đáp")
        self.assertEqual([b["text"] for b in doc["blocks"]], ["Question?", "Answer."])

    def test_who_accordion_trigger_becomes_heading_and_date_is_metadata(self):
        doc = self.parse('''<html lang="vi"><div class="qa-details__wrapper"><div class="col">
          <h1 class="qa-details__title">Hỏi đáp</h1><span>2 October 2024 | Questions and answers</span></div>
          <div class="sf-accordion__panel"><div class="sf-accordion__trigger-panel"><a href="#"><span>Question?</span></a></div>
          <div><p>Answer.</p></div></div></div></html>''', "who", "/news/questions-and-answers/q-a-detail/example")
        self.assertEqual([(b["tag"], b["text"]) for b in doc["blocks"]], [("h1", "Hỏi đáp"), ("h2", "Question?"), ("p", "Answer.")])
        self.assertEqual(doc["source_published_at"], "2024-10-02")

    def test_who_topic_drops_header_label_and_link_sidebar(self):
        doc = self.parse('''<h1>Topic</h1><article class="health-topic--detail"><div class="sf-header-title"><span>Label</span></div>
          <h2>Section</h2><p>Body.</p><div class="sf_colsIn"><p class="section-header">Technical links</p><ul><li>Fact sheets</li></ul></div>
          </article>''', "who", "/health-topics/example")
        self.assertEqual([b["text"] for b in doc["blocks"]], ["Section", "Body."])

    def test_nhs_action_links_and_video_cards_are_ui(self):
        doc = self.parse('''<main><h1>Example</h1><p>Go to A&amp;E.</p><a class="nhsuk-action-link" href="/x"><span>Find a place</span></a>
          <div class="app-brightcove-video"><h3>Video: example</h3><p>Watch this video.</p></div></main>''')
        self.assertEqual([b["text"] for b in doc["blocks"]], ["Example", "Go to A&E."])

    def test_who_topic_and_links(self):
        doc = self.parse('<h1>Topic</h1><article class="health-topic--detail"><h2>Section</h2><p>See <a href="/more">more</a>.</p></article>',
                         "who", "/health-topics/example")
        self.assertEqual(doc["blocks"][-1]["links"][0]["href"], "https://www.who.int/more")
        self.assertIsNone(doc["language"])

    def test_text_inside_malformed_br_is_preserved(self):
        doc = self.parse('<main><h1>Example</h1><p>Before<br>After</br></p></main>')
        self.assertEqual(doc['blocks'][-1]['text'], 'Before After')

    def test_wrapped_table_cell_does_not_abort_extraction(self):
        doc = self.parse('<main><h1>Example</h1><table><tr><div><td>Value</td></div></tr></table></main>')
        self.assertEqual(doc['blocks'][-1]['text'], 'Value')
        self.assertEqual(doc['blocks'][-1]['table']['column'], 0)

    def test_unknown_or_ambiguous_template_fails_without_body_fallback(self):
        for markup in ('<body><h1>Title</h1><p>Body</p></body>',
                       '<main><h1>One</h1></main><main><h1>Two</h1></main>'):
            with self.subTest(markup=markup), self.assertRaisesRegex(ValueError, "TEMPLATE|ROOT"):
                self.parse(markup)

    def test_nhs_figure_keeps_caption_but_drops_image_credit(self):
        markup = """<main><h1>Example</h1><figure class="nhsuk-image"><img src="x.jpg" alt="alt text">
          <figcaption class="nhsuk-image__caption">Caption describing the picture.</figcaption>
          <div aria-hidden="true" class="nhsuk-u-visually-hidden">Credit: <p>Photo agency <a href="https://photo.example/1">link</a></p></div>
          </figure><div class="nhsuk-gallery"><figure><img src="y.jpg"><div>Gallery credit</div></figure></div></main>"""
        for path in ("/conditions/topic/", "/live-well/topic/"):
            with self.subTest(path=path):
                self.assertEqual([b["text"] for b in self.parse(markup, path=path)["blocks"]],
                                 ["Example", "Caption describing the picture."])

    def test_template_is_chosen_by_page_type_path(self):
        markup = '<main><h1>Example</h1><p>Body.</p></main>'
        self.assertEqual(self.parse(markup, path="/live-well/topic/")["template_id"], "nhs-livewell-v1")
        self.assertEqual(self.parse(markup, path="/conditions/topic/")["template_id"], "nhs-main-v1")
        source = copy.deepcopy(self.sources["nhs"])
        source["templates"].append(dict(source["templates"][1], id="other-livewell", paths=["/live-well/topic/"]))
        with self.assertRaisesRegex(ValueError, "AMBIGUOUS_TEMPLATE"):
            extract(markup, "https://www.nhs.uk/live-well/topic/page/", source)
        self.assertEqual(extract(markup, "https://www.nhs.uk/live-well/elsewhere/", source)["template_id"], "nhs-livewell-v1")

    def test_index_template_applies_only_to_its_exact_path(self):
        # The hub lists its children under its own path; children must keep their topic template.
        hub = '''<html><title>Danh mục</title><div class="sf-content-block content-block">
          <a href="/vietnam/vi/health-topics/a">A</a><a href="/vietnam/vi/health-topics/b">B</a></div></html>'''
        doc = self.parse(hub, "who", "/vietnam/vi/health-topics")
        self.assertEqual(doc["template_id"], "who-hub-vn-topics-v1")
        self.assertEqual(self.parse(hub, "who", "/vietnam/vi/health-topics/")["template_id"], "who-hub-vn-topics-v1")
        self.assertEqual([link["href"] for b in doc["blocks"] for link in b["links"]],
                         ["https://www.who.int/vietnam/vi/health-topics/a", "https://www.who.int/vietnam/vi/health-topics/b"])
        child = '''<html><h1>Chủ đề</h1><article class="health-topic--detail"><h2>Mục</h2><p>Nội dung.</p></article>
          <div class="sf-content-block content-block"><a href="/vietnam/vi/health-topics/a">A</a></div></html>'''
        self.assertEqual(self.parse(child, "who", "/vietnam/vi/health-topics/b")["template_id"], "who-topic-v1")

    def test_factsheet_index_keeps_only_the_alphabetical_list(self):
        doc = self.parse('''<html><h1>Fact sheets</h1><nav><a href="/menu">Menu</a></nav>
          <ul class="alphabetical-nav alphabetical-nav--list"><li><a href="/news-room/fact-sheets/detail/x">X</a></li></ul>
          </html>''', "who", "/news-room/fact-sheets")
        self.assertEqual(doc["template_id"], "who-hub-factsheets-v1")
        self.assertEqual([link["href"] for b in doc["blocks"] for link in b["links"]],
                         ["https://www.who.int/news-room/fact-sheets/detail/x"])

    def test_factsheet_keeps_article_sections_but_not_the_page_header(self):
        doc = self.parse('''<html lang="en"><div class="row sf-detail-content">
          <div class="sf-item-header-wrapper"><h1>Example</h1><div class="date">1 January 2026 Reading time:</div></div>
          <article class="sf-detail-body-wrapper"><h2>Key facts</h2><ul><li>Fact.</li></ul>
          <h2>Symptoms</h2><p>Sign.</p></article></div></html>''', "who", "/news-room/fact-sheets/detail/example")
        self.assertEqual(doc["template_id"], "who-factsheet-v1")
        self.assertEqual(doc["title"], "Example")
        self.assertEqual([(b["tag"], b["text"]) for b in doc["blocks"]],
                         [("h2", "Key facts"), ("li", "Fact."), ("h2", "Symptoms"), ("p", "Sign.")])

    def test_livewell_drops_signposts_but_keeps_callouts_and_advice(self):
        page = '''<main><h1>Example</h1><nav class="beta-hub-sibling-nav"><h2>More in Topic</h2><a href="/a">A</a></nav>
          <section><p>Intro text.</p><div class="nhsuk-card nhsuk-card--care"><h3>Non-urgent advice: Speak to a GP if:</h3><p>Advice text.</p></div>
            <p><a href="/tool/">Use the tool</a></p><p>Keep <a href="/x">inline</a> link.</p></section>
          <section><div class="nhsuk-inset-text"><h2>Callout</h2><p>Callout text.</p><p><a href="/calc/">Use the calculator</a></p></div></section>
          <section><h2>Real section</h2><p>Real text.</p><p>Read <a href="/more">more about it</a>.</p><p><a href="/more">Read more about it</a>.</p></section>
          <section><h2>Real with list</h2><p>Lead text.</p><ul><li><a href="/x">link first</a> then text</li></ul></section>
          <section><h2>Further</h2><h3><b>Card</b></h3><p>Card description.</p><p><a href="https://other.example/">Visit</a></p>
            <ul><li><a href="/y">Y</a> has more</li></ul><div class="nhsuk-inset-text"><h2>Kept inset</h2><p>Kept text.</p></div></section>
          <section><h2>Quiz</h2><p>Try the <a href="https://www.nhs.uk/better-health/healthy-choices-quiz/">quiz</a> today.</p></section>
          </main>'''
        texts = [b["text"] for b in self.parse(page, path="/live-well/topic/")["blocks"]]
        self.assertEqual(texts, ["Example", "Intro text.", "Non-urgent advice: Speak to a GP if:", "Advice text.", "Keep inline link.",
                                 "Callout", "Callout text.", "Real section", "Real text.", "Read more about it.",
                                 "Real with list", "Lead text.", "link first then text", "Kept inset", "Kept text."])
        conditions = [b["text"] for b in self.parse(page, path="/conditions/topic/")["blocks"]]
        self.assertIn("Card description.", conditions)
        self.assertIn("Use the tool", conditions)

    def test_metadata_dates_and_malformed_json_are_nonfatal(self):
        doc = self.parse('''<script type="application/ld+json">{"@type":"MedicalWebPage","dateModified":"2026-10-01"}</script>
          <script type="application/ld+json">oops</script><main><h1>Example</h1><p>Page last reviewed: 2 October 2025</p><p>Next review due: 2 October 2028</p></main>''')
        self.assertEqual(doc["source_updated_at"], "2026-10-01")
        self.assertEqual(doc["source_last_reviewed"], "2025-10-02")
        self.assertEqual(doc["source_next_review_due"], "2028-10-02")
        self.assertIn("INVALID_JSONLD", doc["warnings"])

    def test_third_source_only_needs_configuration(self):
        source = copy.deepcopy(self.sources["nhs"])
        source.update(id="example", adapter="generic", hosts=["example.org"])
        source["templates"] = [{"id": "article", "root": ".story", "title": "h1", "remove": [".toolbar"]}]
        doc = extract('<h1>Example</h1><div class="story"><p>Keep me</p><div class="toolbar">UI</div></div>',
                      "https://example.org/a", source)
        self.assertEqual([b["text"] for b in doc["blocks"]], ["Keep me"])

    def test_url_input_deduplicates_comments_but_preserves_queries(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "urls.txt"
            path.write_text('\ufeff# comment\nhttps://www.nhs.uk/a?x=1#part\nhttps://www.nhs.uk/a?x=1\nhttps://www.nhs.uk/a?x=2 # note', encoding="utf-8")
            self.assertEqual(load_urls(path, self.sources), ["https://www.nhs.uk/a?x=1", "https://www.nhs.uk/a?x=2"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "urls_group.txt"
            path.write_text("".join(f"https://www.nhs.uk/p{i}/\n" for i in range(200)), encoding="utf-8")
            self.assertEqual(len(load_urls(path, self.sources)), 200)
            path.write_text("".join(f"https://www.nhs.uk/p{i}/\n" for i in range(201)), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "URL_COUNT_MUST_BE_1_TO_200"):
                load_urls(path, self.sources)
        for url in ["http://www.nhs.uk/a", "https://www.nhs.uk:444/a", "https://x@www.nhs.uk/a", "https://evil.example/a", "https://www.nhs.uk/a b"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_url(url, self.sources)


if __name__ == "__main__":
    unittest.main()
