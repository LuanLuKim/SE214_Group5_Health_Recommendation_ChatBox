"""The only code registry that needs changing for a new custom adapter."""
from .base import TemplateAdapter
from .nhs import NHSAdapter
from .who import WHOAdapter

ADAPTERS = {"generic": TemplateAdapter, "nhs": NHSAdapter, "who": WHOAdapter}


def extract(html, url, source):
    adapter = ADAPTERS.get(source["adapter"])
    if adapter is None:
        raise ValueError("UNKNOWN_ADAPTER")
    return adapter().extract(html, url, source)
