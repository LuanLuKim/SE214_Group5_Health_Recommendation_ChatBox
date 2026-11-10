"""WHO has independent Q&A and topic templates, configured outside the runner."""
from .base import TemplateAdapter


class WHOAdapter(TemplateAdapter):
    version = "1"
