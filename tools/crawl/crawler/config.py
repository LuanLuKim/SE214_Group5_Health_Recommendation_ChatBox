"""Source configuration and explicit URL inputs; no page-specific routing."""
import json
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

HERE = Path(__file__).resolve().parents[1]
ROOT = HERE.parents[1]


def load_sources(path=None):
    sources = json.loads(Path(path or HERE / "templates.json").read_text(encoding="utf-8"))
    policies = json.loads((HERE / "source_policies.json").read_text(encoding="utf-8"))
    seen = set()
    for key, source in sources.items():
        source["id"] = key
        if not source.get("hosts") or not isinstance(source.get("templates"), list):
            raise ValueError("INVALID_SOURCE_CONFIG")
        for host in source["hosts"]:
            if host in seen or host != urlsplit("https://" + host).hostname:
                raise ValueError("INVALID_OR_DUPLICATE_HOST")
            seen.add(host)
        source.setdefault("policy", policies.get(source["hosts"][0], {"mode": "metadata_only"}))
        if source["policy"]["mode"] not in {"metadata_only", "collect_text"}:
            raise ValueError("INVALID_SOURCE_MODE")
    return sources


def source_for(url, sources):
    host = urlsplit(url).hostname
    matches = [source for source in sources.values() if host in source["hosts"]]
    if len(matches) != 1:
        raise ValueError("UNKNOWN_SOURCE")
    return matches[0]


def validate_url(url, sources):
    parts = urlsplit(url)
    if (parts.scheme != "https" or not parts.hostname or parts.username or parts.password
            or parts.netloc.lower() != parts.hostname or any(c.isspace() or ord(c) < 32 for c in url)
            or "\\" in url):
        raise ValueError("INVALID_URL")
    source_for(url, sources)
    return urlunsplit(("https", parts.hostname, parts.path or "/", parts.query, ""))


def load_urls(path, sources, limit=80):
    urls = []
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        value = line.split("#", 1)[0].strip()
        if value:
            url = validate_url(value.split()[0], sources)
            if url not in urls:
                urls.append(url)
    if not urls or len(urls) > limit:
        raise ValueError(f"URL_COUNT_MUST_BE_1_TO_{limit}")
    return urls
