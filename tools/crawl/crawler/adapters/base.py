"""Declarative template extraction; source subclasses only add source metadata."""
from datetime import datetime
import json
from urllib.parse import urlsplit

from bs4 import BeautifulSoup

from ..html import digest, link_only, page_metadata, signpost_children, space, structured_blocks


def _declares_path(template):
    return bool(template.get("paths") or template.get("paths_exact"))


def select_by_path(matches, path):
    """Keep templates for this page type. ``paths_exact`` (whole URL paths, e.g. an index
    page whose children share its path as a prefix) wins over ``paths`` (URL path
    prefixes), which wins over templates declaring neither. A template that declares a
    path never applies elsewhere."""
    exact = [(t, r) for t, r in matches if path.rstrip("/") in {p.rstrip("/") for p in t.get("paths_exact", [])}]
    prefix = [(t, r) for t, r in matches if any(path.startswith(p) for p in t.get("paths", []))]
    return exact or prefix or [(t, r) for t, r in matches if not _declares_path(t)]


class TemplateAdapter:
    version = "1"

    def metadata(self, soup, root):
        return {}

    def extract(self, html, url, source):
        soup = BeautifulSoup(html, "html.parser")
        matches = [(template, soup.select(template["root"])) for template in source["templates"]]
        matches = [(template, roots) for template, roots in matches if roots]
        matches = select_by_path(matches, urlsplit(url).path)
        if len(matches) != 1:
            raise ValueError("UNSUPPORTED_TEMPLATE" if not matches else "AMBIGUOUS_TEMPLATE")
        template, roots = matches[0]
        if len(roots) != 1:
            raise ValueError("AMBIGUOUS_CONTENT_ROOT")
        titles = soup.select(template["title"])
        if len(titles) != 1 or not space(titles[0].get_text()):
            raise ValueError("INVALID_TITLE")
        title = space(titles[0].get_text())
        page, warnings = page_metadata(soup)
        root = roots[0]
        extra = self.metadata(soup, root)
        for node in root.select(",".join(["script", "style", "svg", "iframe", "img"] + template.get("remove", []))):
            if node.parent is not None:
                node.decompose()
        # Page-type options: sections that only point to other pages, then bare link paragraphs.
        signposts = template.get("drop_signpost_sections")
        if signposts is not None:
            for section in root.select(signposts.get("scope", "section")):
                for node in signpost_children(section, signposts.get("keep", [])):
                    node.decompose()
        if template.get("drop_link_only_paragraphs"):
            for node in root.select("p"):
                if node.parent is not None and link_only(node):
                    node.decompose()
        # Template-declared structure: e.g. accordion triggers that act as headings.
        for selector, tag in template.get("headings", {}).items():
            if tag not in {"h2", "h3", "h4", "h5", "h6"}:
                raise ValueError("INVALID_TEMPLATE_HEADING")
            for node in root.select(selector):
                node.name = tag
        blocks = structured_blocks(root, url)
        if not blocks or not any(b["text"] != title for b in blocks):
            raise ValueError("EMPTY_CONTENT")
        dates = {}
        for field, key in (("source_updated_at", "dateModified"), ("source_published_at", "datePublished")):
            value = page.get(key)
            try:
                if value is not None:
                    datetime.fromisoformat(value.replace("Z", "+00:00"))
            except (ValueError, AttributeError, TypeError):
                warnings.append("INVALID_" + key.upper())
                value = None
            dates[field] = value
        return {"schema_version": 1, "adapter": source["adapter"], "adapter_version": self.version,
                "template_id": template["id"], "config_sha256": digest(json.dumps(source, sort_keys=True)),
                "title": title, "language": soup.html.get("lang") if soup.html else None,
                **dates, "source_last_reviewed": None, "source_next_review_due": None, **extra,
                "blocks": blocks, "body_text_coverage": True,
                "extracted_text_sha256": digest("\n".join(b["text"] for b in blocks)),
                "warnings": warnings, "review_status": "DRAFT", "rag_eligible": False}
