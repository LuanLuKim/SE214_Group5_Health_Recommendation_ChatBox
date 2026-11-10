"""Declarative template extraction; source subclasses only add source metadata."""
from datetime import datetime
import json

from bs4 import BeautifulSoup

from ..html import digest, page_metadata, space, structured_blocks


class TemplateAdapter:
    version = "1"

    def metadata(self, soup, root):
        return {}

    def extract(self, html, url, source):
        soup = BeautifulSoup(html, "html.parser")
        matches = [(template, soup.select(template["root"])) for template in source["templates"]]
        matches = [(template, roots) for template, roots in matches if roots]
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
