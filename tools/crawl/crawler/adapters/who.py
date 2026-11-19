"""WHO has independent Q&A and topic templates, configured outside the runner."""
from datetime import date
import re

from .base import TemplateAdapter

MONTHS = {name: i for i, name in enumerate("January February March April May June July August September October November December".split(), 1)}
_DATE = re.compile(r"^\s*(\d{1,2})\s+([A-Z][a-z]+)\s+(\d{4})\s*\|")


class WHOAdapter(TemplateAdapter):
    version = "2"

    def metadata(self, soup, root):
        """Q&A pages print the publication date under the title ("2 October 2024 | Questions and answers")."""
        for node in root.select(".qa-details__title + span"):
            match = _DATE.match(node.get_text())
            if match:
                day, month, year = match.groups()
                try:
                    return {"source_published_at": date(int(year), MONTHS[month], int(day)).isoformat()}
                except (ValueError, KeyError):
                    return {"source_published_at_invalid": True}
        return {}
