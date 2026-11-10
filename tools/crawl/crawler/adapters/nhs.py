"""NHS template metadata; no networking or disease-specific rules."""
from datetime import date
import re

from .base import TemplateAdapter

MONTHS = {name: i for i, name in enumerate("January February March April May June July August September October November December".split(), 1)}


class NHSAdapter(TemplateAdapter):
    def metadata(self, soup, root):
        text, result = root.get_text(" ", strip=True), {}
        for key, label in (("source_last_reviewed", "Page last reviewed:"), ("source_next_review_due", "Next review due:")):
            match = re.search(re.escape(label) + r"\s*(\d{1,2})\s+(\w+)\s+(\d{4})", text)
            result[key] = None
            if match:
                day, month, year = match.groups()
                try:
                    result[key] = date(int(year), MONTHS[month], int(day)).isoformat()
                except (ValueError, KeyError):
                    result[key + "_invalid"] = True
        return result
