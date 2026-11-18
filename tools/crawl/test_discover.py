"""Offline tests for discover.py on a synthetic collection; no network, no real pages."""
from contextlib import redirect_stderr, redirect_stdout
from hashlib import sha256
import io
import json
from pathlib import Path
import tempfile
import unittest

import discover

HOST = "https://www.nhs.uk"


def page(url, links, final_url=None):
    return {"url": url, "final_url": final_url or url,
            "links": [{"text": text, "href": href if href.startswith("http") else HOST + href} for text, href in links]}


class DiscoverTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.collection = root / "collection"
        (self.collection / "pages").mkdir(parents=True)
        (self.collection / "extracted").mkdir()
        self.root = root

    def tearDown(self):
        self.tmp.cleanup()

    def crawl(self, *pages):
        for i, p in enumerate(pages):
            key = sha256(p["url"].encode("utf-8")).hexdigest()
            name = f"x{i}.json"
            (self.collection / "pages" / f"{key}.json").write_text(
                json.dumps({"url": p["url"], "final_url": p["final_url"], "extraction_file": name}), encoding="utf-8")
            blocks = [{"text": "Index", "links": p["links"]}]
            (self.collection / "extracted" / name).write_text(json.dumps({"blocks": blocks}), encoding="utf-8")

    def write(self, name, text):
        path = self.root / name
        path.write_text(text, encoding="utf-8")
        return path

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            self.assertEqual(discover.main([*args, "--collection", str(self.collection)]), 0)
        return [line for line in out.getvalue().splitlines() if line], err.getvalue()

    def test_canonical_adds_trailing_slash_and_drops_query_and_fragment(self):
        self.assertEqual(discover.canonical(HOST + "/medicines/x?a=1#top"), HOST + "/medicines/x/")
        self.assertEqual(discover.canonical("https://www.who.int/vietnam/vi/a"), "https://www.who.int/vietnam/vi/a")
        self.assertIsNone(discover.canonical("mailto:someone@example.org"))

    def test_suggests_unknown_links_on_configured_hosts_only(self):
        index = HOST + "/medicines/"
        self.crawl(page(index, [("Examplol", "/medicines/examplol/"), ("Known", "/medicines/known/"),
                                ("Find a GP", "/service-search/find-a-gp/"), ("Other site", "https://example.org/a/")]))
        urls = self.write("index.txt", f"{index} medication_index\n")
        known = self.write("known.txt", f"{HOST}/medicines/known/ medication\n")
        lines, _ = self.run_main("--urls", str(urls), "--known", str(known), "--format", "urls")
        self.assertEqual(lines, [HOST + "/medicines/examplol/"])

    def test_seed_names_match_link_text_or_slug_and_report_missing(self):
        index = HOST + "/medicines/"
        self.crawl(page(index, [("Examplol for adults", "/medicines/examplol-for-adults/"),
                                ("Brandix, see Genericine (Brandix)", "/medicines/genericine/"),
                                ("Unrelated", "/medicines/unrelated/")]))
        urls = self.write("index.txt", f"{index} medication_index\n")
        seeds = self.write("seeds.txt", "# comment\nexamplol for adults | Examplol\ngenericine\nmissingol | Brand\n")
        lines, err = self.run_main("--urls", str(urls), "--known", str(urls), "--seeds", str(seeds))
        self.assertEqual([line.split()[-1] for line in lines],
                         [HOST + "/medicines/examplol-for-adults/", HOST + "/medicines/genericine/"])
        self.assertIn("missingol", err)

    def test_sub_pages_are_chosen_by_the_hub_link_text(self):
        hub = HOST + "/medicines/examplol/"
        self.crawl(page(hub, [("Common questions", "/medicines/examplol/faq-page/"),
                              ("How and when to take it", "/medicines/examplol/dosing/"),
                              ("Side effects", "/medicines/examplol/side-effects/")]))
        urls = self.write("hubs.txt", f"{hub} hub\n")
        lines, _ = self.run_main("--urls", str(urls), "--known", str(urls), "--prefix", "/medicines/examplol/",
                                 "--link-text", "common questions", "--link-text", "How and when to take",
                                 "--format", "urls")
        # the slugs are whatever the page uses; nothing is derived from the medicine name
        self.assertEqual(lines, [hub + "dosing/", hub + "faq-page/"])

    def test_commented_and_redirected_urls_count_as_known(self):
        index = HOST + "/medicines/"
        self.crawl(page(index, [("Old", "/medicines/old/"), ("Moved", "/medicines/moved/"), ("New", "/medicines/new/")]),
                   page(HOST + "/medicines/moved-from/", [], final_url=HOST + "/medicines/moved/"))
        urls = self.write("index.txt", f"{index} medication_index\n")
        known = self.write("known.txt", f"# disabled hub {HOST}/medicines/old/\n{HOST}/medicines/moved-from/ x\n")
        lines, _ = self.run_main("--urls", str(urls), "--known", str(known), "--format", "urls")
        self.assertEqual(lines, [HOST + "/medicines/new/"])


if __name__ == "__main__":
    unittest.main()
