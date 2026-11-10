"""Historical text extraction retained only for validating the three pilot records."""
from html.parser import HTMLParser

class MainText(HTMLParser):
    """Keep readable text in main, including headings and table row boundaries."""
    def __init__(self):
        super().__init__()
        self.main = False
        self.hidden = []
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == "main":
            self.main = True
        if self.main and tag in {"script", "style", "nav", "figure", "aside", "button", "svg"}:
            self.hidden.append(tag)
        if self.main and not self.hidden:
            if tag in {"p", "div", "section", "li", "tr", "br", "h1", "h2", "h3", "h4"}:
                self.parts.append("\n")
            elif tag in {"td", "th"}:
                self.parts.append(" | ")

    def handle_endtag(self, tag):
        if self.hidden and self.hidden[-1] == tag:
            self.hidden.pop()
        if tag == "main":
            self.main = False
        if tag in {"p", "li", "tr", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if self.main and not self.hidden:
            self.parts.append(data)

    def text(self):
        lines = (" ".join(line.split()) for line in "".join(self.parts).splitlines())
        return "\n".join(line for line in lines if line)
