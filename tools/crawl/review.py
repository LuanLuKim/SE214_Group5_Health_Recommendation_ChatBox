"""Revalidate the bounded pilot and produce an offline, auditable review pack.

This is preparation/technical QA, not medical approval or a production RAG index.
Only the three explicitly profiled NHS documents are supported. Standard library only.
"""
import argparse
from contextlib import closing
from datetime import date, datetime, timedelta, timezone
from hashlib import sha256
from html import escape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sqlite3
import sys
from urllib.error import HTTPError
from urllib.parse import unquote, urlsplit
from urllib.request import build_opener

from crawler import http as collect
from review_legacy import MainText

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / ".data" / "crawl"
OUTPUT = ROOT / ".data" / "review"
VERSION = "pilot-review-v1"


def digest(text):
    return sha256(text.encode("utf-8")).hexdigest()


def write_json(path, value):
    collect.save(path, value)


def refresh_sources(raw_dir=RAW, output=OUTPUT):
    """Use the collector's lock, persistent budget, timing and host blocks."""
    output.mkdir(parents=True, exist_ok=True)
    snapshots = output / "source-snapshots"
    snapshots.mkdir(exist_ok=True)
    records = []
    for key in ("hives", "allergies", "eczema"):
        record = json.loads((raw_dir / f"{key}.json").read_text(encoding="utf-8"))
        if record["url"] != URLS[key]:
            raise ValueError(f"unexpected URL for {key}")
        records.append(record)
    with closing(sqlite3.connect(raw_dir / "lock.sqlite", timeout=0)) as lock:
        lock.execute("BEGIN EXCLUSIVE")
        batch = collect.Batch(raw_dir, build_opener(collect.NoRedirect()))
        host = "www.nhs.uk"
        if host in batch.state["blocked"]:
            raise ValueError("NHS is blocked in the persistent checkpoint; no requests sent")
        try:
            rules = collect.robots_denials(batch.fetch(f"https://{host}/robots.txt", "text/plain"))
            for record in records:
                key, url = record["id"], record["url"]
                if any(rule.match(unquote(urlsplit(url).path)) for rule in rules):
                    raise ValueError(f"robots denied: {url}")
                html = batch.fetch(url, "text/html")
                parser = MainText()
                parser.feed(html)
                parser.close()
                # A single current snapshot per source; original crawl JSON is untouched.
                (snapshots / f"{key}.html").write_text(html, encoding="utf-8")
                write_json(snapshots / f"{key}.json", {
                    "url": url, "checked_at": collect.now(), "html_sha256": digest(html),
                    "extracted_text_sha256": digest(parser.text()),
                    "crawl_text_sha256": record["text_sha256"],
                    "matches_crawl": parser.text() == record["text"],
                })
                batch.log("source_revalidated", id=key, url=url,
                          matches_crawl=parser.text() == record["text"])
        except HTTPError as exc:
            if exc.code in {401, 403, 429, 503}:
                batch.state["blocked"][host] = {
                    "at": collect.now(), "status": exc.code,
                    "retry_after": exc.headers.get("Retry-After"),
                }
                batch.persist()
            batch.log("review_source_error", reason=str(exc))
            raise
        except (OSError, ValueError, collect.Limit) as exc:
            batch.log("review_source_error", reason=str(exc))
            raise


URLS = {
    "hives": "https://www.nhs.uk/conditions/hives/",
    "allergies": "https://www.nhs.uk/conditions/allergies/",
    "eczema": "https://www.nhs.uk/conditions/contact-dermatitis/",
}


PROFILES = {
    "hives": {
        "title": "Hives", "title_vi": "Mề đay", "title_lines": ["Hives"],
        "headings": [
            "Symptoms of hives", "If you're not sure it's hives",
            "A pharmacist can help with hives",
            "Non-urgent advice: Ask for an urgent GP appointment or get help from NHS 111 if:",
            "Immediate action required: Call 999 if:",
            "Treatment for hives from a GP", "You cannot always prevent hives",
        ],
    },
    "allergies": {
        "title": "Allergies", "title_vi": "Dị ứng", "title_lines": ["Allergies"],
        "headings": [
            "Causes of allergies", "Symptoms of an allergic reaction",
            "Immediate action required: Call 999 if:", "What to do if you have anaphylaxis",
            "How to use an adrenaline auto-injector", "Non-urgent advice: See a GP if:",
            "What happens at your appointment", "Treatments for allergies", "Find out more",
        ],
    },
    "eczema": {
        "title": "Overview - Contact dermatitis", "title_vi": "Viêm da tiếp xúc — tổng quan",
        "title_lines": ["Overview", "-", "Contact dermatitis"],
        "headings": [
            "Symptoms of contact dermatitis", "When to see a pharmacist", "When to see a GP",
            "Causes of contact dermatitis", "Treating contact dermatitis",
            "Preventing contact dermatitis", "Other types of eczema",
        ],
    },
}

GALLERY_INTRO = ("Here is an image gallery with images and detailed descriptions. Select an image "
                 "tab to get the bigger version of the image and to access the description.")
UI_LINES = {
    "Find a pharmacy", "Find your nearest A&E", "Information:",
    "Read about symptoms of contact dermatitis", "Read about diagnosing contact dermatitis",
    "Read about causes of contact dermatitis", "Read about treating contact dermatitis",
}
TAG_LABELS = {
    "medication": "Có thuốc/hướng dẫn điều trị: ngoài phạm vi trả lời về thuốc",
    "paediatric": "Có nội dung trẻ em: cần biên tập cho người lớn",
    "uk_service": "Có dịch vụ/chỉ dẫn tại Anh: cần bản địa hóa có nguồn",
    "emergency": "Cảnh báo cấp cứu: nguồn tham khảo cho Safety Gate, chưa phải quy tắc đã duyệt",
    "source_label_conflict": "Nhãn Non-urgent đi với yêu cầu urgent: cần người duyệt quyết định",
    "external_navigation": "Mục chỉ dẫn sang trang khác: không phải nội dung đầy đủ",
    "overdue_review": "Ngày dự kiến rà soát nguồn đã qua: cần chuyên gia xác minh phiên bản",
}
MONTHS = {name: number for number, name in enumerate(
    "January February March April May June July August September October November December".split(), 1)}


def normal_space(text):
    return " ".join(text.split())


def parse_source_date(value):
    day, month, year = value.split()
    return date(int(year), MONTHS[month], int(day)).isoformat()


class SourceAudit(HTMLParser):
    """Independent DOM audit: keep aside text; omit media and navigation explicitly.

    Unlike MainText, this uses a full element stack and whitespace-normalized tokens.
    It detects readable text accidentally omitted by the crawler (including an aside).
    """
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
    HIDDEN = {"script", "style", "nav", "figure", "button", "svg"}

    def __init__(self):
        super().__init__()
        self.stack = []
        self.parts = []
        self.headings = []
        self.active_heading = None
        self.jsonld_parts = None
        self.jsonld = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and attrs.get("type") == "application/ld+json":
            self.jsonld_parts = []
        in_main = tag == "main" or bool(self.stack and self.stack[-1][1])
        hidden = bool(self.stack and self.stack[-1][2]) or tag in self.HIDDEN
        # The gallery contains image descriptions, third-party credits and controls.
        hidden = hidden or any(c.startswith("nhsuk-gallery") for c in attrs.get("class", "").split())
        if in_main and not hidden and tag in {"h1", "h2", "h3", "h4"}:
            self.active_heading = (tag, [])
        if in_main and not hidden and tag in {"p", "li", "div", "section", "h1", "h2", "h3", "h4", "br"}:
            self.parts.append(" ")
        if tag not in self.VOID:
            self.stack.append((tag, in_main, hidden))

    def handle_endtag(self, tag):
        if tag == "script" and self.jsonld_parts is not None:
            try:
                self.jsonld.append(json.loads("".join(self.jsonld_parts)))
            except ValueError:
                pass
            self.jsonld_parts = None
        if self.active_heading and self.active_heading[0] == tag:
            self.headings.append(normal_space("".join(self.active_heading[1])))
            self.active_heading = None
        if self.stack and self.stack[-1][1] and not self.stack[-1][2]:
            if tag in {"p", "li", "div", "section", "h1", "h2", "h3", "h4"}:
                self.parts.append(" ")
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                break

    def handle_data(self, data):
        if self.jsonld_parts is not None:
            self.jsonld_parts.append(data)
        if self.stack and self.stack[-1][1] and not self.stack[-1][2]:
            self.parts.append(data)
            if self.active_heading:
                self.active_heading[1].append(data)

    def text(self):
        return normal_space("".join(self.parts))

    def modified(self):
        for obj in self.jsonld:
            if isinstance(obj, dict) and obj.get("@type") == "MedicalWebPage":
                return obj.get("dateModified")
        return None


def scope_tags(heading, text):
    combined = heading + "\n" + text
    tags = []
    if re.search(r"antihistamin|corticosteroid|steroid|adrenaline|epipen|jext|menthol|emollient|moisturis", combined, re.I):
        tags.append("medication")
    if re.search(r"\b(child|children|baby|babies)\b", combined, re.I):
        tags.append("paediatric")
    if re.search(r"\b(999|111|GP)\b|A&E", combined):
        tags.append("uk_service")
    if heading.startswith("Immediate action required:"):
        tags.append("emergency")
    if "Non-urgent advice:" in heading and "urgent GP appointment" in heading:
        tags.append("source_label_conflict")
    if heading in {"Find out more", "If you're not sure it's hives"}:
        tags.append("external_navigation")
    return tags


def normalize(record, as_of):
    key = record["id"]
    profile = PROFILES[key]  # Unknown documents fail rather than guessing headings.
    raw_lines = record["text"].splitlines()
    if raw_lines[:len(profile["title_lines"])] != profile["title_lines"]:
        raise ValueError(f"{key}: unexpected title layout")
    sections = [{"heading": "Overview", "heading_raw_line": None, "lines": []}]
    audit = []
    removed = []
    dates = {}
    gallery = False
    found_headings = []
    for number, line in enumerate(raw_lines, 1):
        disposition = "body"
        if number <= len(profile["title_lines"]):
            disposition = "title"
        elif line in profile["headings"]:
            gallery = False
            found_headings.append(line)
            sections.append({"heading": line, "heading_raw_line": number, "lines": []})
            disposition = "heading"
        elif line.startswith(("Page last reviewed: ", "Next review due: ")):
            field = "source_last_reviewed" if line.startswith("Page last") else "source_next_review_due"
            dates[field] = parse_source_date(line.partition(": ")[2])
            disposition = "metadata"
        elif line == GALLERY_INTRO:
            gallery = True
            disposition = "remove:gallery_control"
        elif gallery and (re.fullmatch(r"[1-6]", line) or re.fullmatch(r"[1-6]: .+ \(thumbnail\)\.", line)):
            disposition = "remove:gallery_control"
        elif line in UI_LINES:
            disposition = "remove:navigation_or_label"
        else:
            # Never silently drop an unrecognized gallery line or a standalone number.
            gallery = False
            sections[-1]["lines"].append({"raw_line": number, "text": line})
        audit.append({"raw_line": number, "text": line, "disposition": disposition})
        if disposition.startswith("remove:"):
            removed.append(audit[-1])
    if found_headings != profile["headings"]:
        raise ValueError(f"{key}: missing, duplicate or reordered section headings")
    if set(dates) != {"source_last_reviewed", "source_next_review_due"}:
        raise ValueError(f"{key}: missing review dates")
    overdue = dates["source_next_review_due"] < as_of.isoformat()
    parent_hash = digest(record["text"])
    chunks = []
    for index, section in enumerate(sections):
        body = "\n".join(line["text"] for line in section["lines"])
        section["text"] = body
        section["id"] = f"{key}-s{index:02d}"
        tags = scope_tags(section["heading"], body)
        if overdue:
            tags.append("overdue_review")
        section["scope_tags"] = tags
        section["scope_status"] = "HOLD" if tags else "REVIEW_CANDIDATE"
        if not body:
            continue
        chunk_text = f"{profile['title']}\n{section['heading']}\n{body}"
        chunks.append({
            "id": f"{section['id']}-{digest(chunk_text)[:12]}",
            "document_id": key, "document_text_sha256": parent_hash,
            "section_id": section["id"], "heading": section["heading"],
            "text": chunk_text, "text_sha256": digest(chunk_text),
            "raw_line_numbers": [line["raw_line"] for line in section["lines"]],
            "heading_raw_line": section["heading_raw_line"], "source_url": record["url"],
            "fetched_at": record["fetched_at"], "language": "en",
            "review_status": "DRAFT", "verified_at": None,
            "scope_tags": tags, "scope_status": section["scope_status"],
            "rights_reviewed": False, "rag_eligible": False,
            "purpose": "OFFLINE_REVIEW_ONLY", "normalizer_version": VERSION,
        })
    return {
        "schema_version": 1, "normalizer_version": VERSION, "id": key,
        "title": profile["title"], "title_vi_label": profile["title_vi"],
        "topic": record["topic"], "language": "en", "source_name": "NHS",
        "source_url": record["url"], "fetched_at": record["fetched_at"],
        "source_text_sha256": parent_hash, "source_updated_at": None, **dates,
        "as_of": as_of.isoformat(), "source_review_overdue": overdue,
        "review_status": "DRAFT", "verified_at": None, "rights_reviewed": False,
        "rag_eligible": False, "license_note": record["note"],
        "sections": sections, "removed_lines": removed, "line_audit": audit,
        "chunks": chunks,
    }


def eligible(chunk):
    """Fail closed; local preparation cannot supply human approval/rights decisions."""
    return (chunk.get("review_status") == "APPROVED"
            and bool(chunk.get("verified_at"))
            and chunk.get("rights_reviewed") is True
            and chunk.get("scope_status") == "APPROVED_FOR_VN_ADULTS"
            and chunk.get("scope_tags") == []
            and chunk.get("technical_status") == "PASS"
            and bool(chunk.get("source_checked_at"))
            and chunk.get("purpose") == "PRODUCTION"
            and chunk.get("rag_eligible") is True)


def check_document(record, document, snapshot_dir):
    checks = []

    def check(name, passed, detail):
        checks.append({"name": name, "passed": bool(passed), "detail": detail})

    check("raw_hash", digest(record["text"]) == record["text_sha256"], "SHA-256 bản crawl")
    check("source_url", record["url"] == URLS[record["id"]], "Đúng URL đã chọn")
    html_path = snapshot_dir / f"{record['id']}.html"
    metadata_path = snapshot_dir / f"{record['id']}.json"
    try:
        html = html_path.read_text(encoding="utf-8")
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        check("snapshot_hash", digest(html) == metadata["html_sha256"]
              and metadata["url"] == record["url"], "Hash và URL bản HTML đối chiếu")
        current = MainText()
        current.feed(html)
        current.close()
        check("source_text_match", current.text() == record["text"]
              and digest(current.text()) == metadata["extracted_text_sha256"],
              "Toàn bộ văn bản main hiện tại khớp bản crawl theo cùng extractor")
        independent = SourceAudit()
        independent.feed(html)
        independent.close()
        raw_without_gallery = " ".join(row["text"] for row in document["line_audit"]
                                       if row["disposition"] != "remove:gallery_control")
        check("independent_dom_coverage", independent.text() == normal_space(raw_without_gallery),
              "Parser độc lập: toàn bộ chữ trong main trừ media/gallery/nav, có kiểm tra aside")
        expected = [PROFILES[record["id"]]["title"], *PROFILES[record["id"]]["headings"]]
        # NHS uses one extra visual 'Information:' heading around information boxes.
        actual = [heading for heading in independent.headings if heading != "Information:"]
        check("source_heading_structure", actual == expected,
              "Tiêu đề và thứ tự mục HTML khớp cấu trúc được chuẩn hóa")
        document["source_updated_at"] = independent.modified()
        document["source_checked_at"] = metadata["checked_at"]
        document["source_snapshot_sha256"] = metadata["html_sha256"]
        if independent.modified():
            datetime.fromisoformat(independent.modified())
        check("source_modified_metadata", bool(independent.modified()),
              "dateModified lấy từ JSON-LD; tách riêng ngày rà soát và ngày tải")
    except (OSError, ValueError, KeyError) as exc:
        check("source_snapshot_available", False, f"Thiếu/hỏng bằng chứng nguồn: {exc}")

    lines = record["text"].splitlines()
    audit = document["line_audit"]
    check("all_raw_lines_accounted", len(audit) == len(lines)
          and [row["raw_line"] for row in audit] == list(range(1, len(lines) + 1))
          and [row["text"] for row in audit] == lines,
          "Mỗi dòng gốc được giữ, chuyển metadata hoặc loại có lý do")
    body_refs = [line for section in document["sections"] for line in section["lines"]]
    check("body_text_unchanged", all(lines[row["raw_line"] - 1] == row["text"] for row in body_refs)
          and [row["raw_line"] for row in body_refs]
          == [row["raw_line"] for row in audit if row["disposition"] == "body"],
          "Không viết lại hay mất dòng nội dung khi làm sạch")
    expected_sections = [section for section in document["sections"] if section["text"]]
    chunks = document["chunks"]
    check("one_atomic_chunk_per_section", len(chunks) == len(expected_sections)
          and all(chunk["text"] == f"{document['title']}\n{section['heading']}\n{section['text']}"
                  and chunk["raw_line_numbers"] == [row["raw_line"] for row in section["lines"]]
                  for chunk, section in zip(chunks, expected_sections)),
          "Mỗi mục thành một đoạn nguyên vẹn, không cắt danh sách/điều kiện/cảnh báo")
    check("chunk_provenance", all(chunk["source_url"] == record["url"]
          and chunk["document_text_sha256"] == record["text_sha256"]
          and chunk["text_sha256"] == digest(chunk["text"]) for chunk in chunks),
          "Mọi đoạn truy ngược được URL, hash tài liệu và dòng gốc")
    check("review_only_gate", all(chunk["review_status"] == "DRAFT" and not eligible(chunk)
          and not chunk["rag_eligible"] for chunk in chunks),
          "Bản chuẩn hóa và đoạn thử không đủ điều kiện dùng chính thức")
    check("chunk_size_bound", all(len(chunk["text"].split()) <= 650 for chunk in chunks),
          "Mỗi đoạn ≤650 từ tiếng Anh; đây là giới hạn từ, không phải token")
    return checks


def findings(document):
    items = [
        "Giữ DRAFT; cần chuyên gia duyệt, xác minh quyền dùng cho bản chuyển thể và phạm vi người lớn Việt Nam.",
        "Nội dung vẫn bằng tiếng Anh; chưa dịch và chưa kiểm thử chatbot/RAG đang chạy.",
    ]
    if document["removed_lines"]:
        items.append(f"Đã loại {len(document['removed_lines'])} dòng điều hướng/điều khiển ảnh; có nhật ký từng dòng.")
    tags = {tag for section in document["sections"] for tag in section["scope_tags"]}
    items.extend(TAG_LABELS[tag] for tag in TAG_LABELS if tag in tags)
    if document["id"] == "eczema":
        items.append("ID lịch sử eczema được giữ để truy vết; tên bài chuẩn là Contact dermatitis, không đại diện toàn bộ bệnh eczema.")
    return items


def render_html(documents, summary, output):
    e = escape
    nav = "".join(f'<a href="#{doc["id"]}">{e(doc["title_vi_label"])}</a>' for doc in documents)
    articles = []
    for doc in documents:
        checks = "".join(
            f'<li class="{"ok" if c["passed"] else "fail"}"><b>{"ĐẠT" if c["passed"] else "LỖI"}</b> '
            f'{e(c["detail"])} <code>{e(c["name"])}</code></li>' for c in doc["technical_checks"])
        notes = "".join(f"<li>{e(item)}</li>" for item in findings(doc))
        raw = "".join(
            f'<div class="raw-line {"removed" if row["disposition"].startswith("remove:") else ""}">'
            f'<span class="line-no">{row["raw_line"]}</span><span>{e(row["text"])}</span></div>'
            for row in doc["line_audit"])
        clean = []
        for section in doc["sections"]:
            badges = "".join(f'<span class="badge hold">{e(TAG_LABELS[tag])}</span>' for tag in section["scope_tags"])
            body = "".join(f'<p><span class="line-no">L{row["raw_line"]}</span> {e(row["text"])}</p>' for row in section["lines"])
            clean.append(f'<section class="section"><h4>{e(section["heading"])}</h4>'
                         f'<div class="badges">{badges or "<span class=badge>Ứng viên chờ duyệt</span>"}</div>'
                         f'{body or "<p class=muted>Mục chỉ có tiêu đề sau khi bỏ điều hướng.</p>"}</section>')
        removed = "".join(f'<li>L{row["raw_line"]}: {e(row["text"])} '
                          f'<code>{e(row["disposition"])}</code></li>' for row in doc["removed_lines"])
        chunk_cards = "".join(
            f'<details><summary>{e(chunk["id"])} · {len(chunk["text"].split())} từ · '
            f'{e(chunk["scope_status"])}</summary><p>DRAFT · không dùng chính thức · '
            f'dòng {", ".join(map(str, chunk["raw_line_numbers"]))}</p>'
            f'<pre>{e(chunk["text"])}</pre></details>' for chunk in doc["chunks"])
        articles.append(f'''
<article id="{doc['id']}"><h2>{e(doc['title_vi_label'])} <span class="badge">{doc['technical_status']}</span></h2>
<p class="source"><a href="{e(doc['source_url'], quote=True)}" target="_blank" rel="noreferrer">{e(doc['title'])} — NHS ↗</a></p>
<dl><div><dt>Rà soát nguồn</dt><dd>{doc['source_last_reviewed']}</dd></div>
<div><dt>Dự kiến rà soát tiếp</dt><dd>{doc['source_next_review_due']}</dd></div>
<div><dt>Sửa trang (JSON-LD)</dt><dd>{e(doc.get('source_updated_at') or 'Không xác định')}</dd></div>
<div><dt>Tải bản crawl (UTC)</dt><dd>{e(doc['fetched_at'])}</dd></div>
<div><dt>Đối chiếu nguồn (UTC)</dt><dd>{e(doc.get('source_checked_at') or 'Chưa có')}</dd></div>
<div><dt>Trạng thái</dt><dd>DRAFT · Chưa duyệt chuyên môn</dd></div></dl>
<div class="notice"><ul>{notes}</ul></div>
<details><summary>Kết quả {len(doc['technical_checks'])} kiểm tra kỹ thuật</summary><ul class="checks">{checks}</ul></details>
<div class="comparison"><section class="panel"><h3>Bản crawl gốc — đọc theo dòng</h3>
<p class="muted">Chữ đỏ: chỉ loại khỏi bản xử lý. Tệp gốc vẫn nguyên vẹn.</p><div lang="en">{raw}</div></section>
<section class="panel"><h3>Bản chuẩn hóa — chia theo mục</h3><p class="muted">Giữ tiếng Anh của nguồn. L = dòng trong bản gốc.</p>
<div lang="en">{''.join(clean)}</div></section></div>
<details><summary>Các dòng đã loại ({len(doc['removed_lines'])})</summary><ul>{removed}</ul></details>
<details><summary>Các đoạn thử ({len(doc['chunks'])}) — chỉ để rà soát</summary>{chunk_cards}</details>
<p class="muted license">{e(doc['license_note'])}</p></article>''')
    html = f'''<!doctype html><html lang="vi"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Kiểm tra dữ liệu NHS — SE214</title>
<style>
:root{{font-family:Segoe UI,Arial,sans-serif;color:#1c2d3c;background:#f3f6f8;font-size:16px;line-height:1.6}}
*{{box-sizing:border-box}}body{{margin:0}}main{{max-width:1460px;margin:auto;padding:28px}}
h1{{font-size:32px;margin:0 0 8px}}h2{{font-size:26px}}h3{{font-size:20px}}h4{{font-size:18px;margin:0 0 10px}}
a{{color:#075e98;text-underline-offset:3px}}nav{{position:sticky;top:0;background:#fff;z-index:2;padding:12px 28px;border-bottom:1px solid #cdd9e2;display:flex;gap:24px;flex-wrap:wrap}}
article{{scroll-margin-top:70px;background:white;border:1px solid #d9e2e9;border-radius:12px;padding:24px;margin:28px 0}}
.intro,.notice{{background:#eaf3fa;padding:16px 22px;border-radius:8px}}.notice{{background:#fff7e8;border-left:4px solid #b46c00;margin:20px 0}}
.stats{{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin:22px 0}}.stat{{padding:18px;background:white;border:1px solid #d9e2e9;border-radius:10px}}
.stat b{{display:block;font-size:30px;color:#075e98}}.comparison{{display:grid;grid-template-columns:1fr 1fr;gap:22px;margin:22px 0;align-items:start}}
.panel{{min-width:0;border:1px solid #d9e2e9;border-radius:8px;padding:18px}}.panel h3{{margin-top:0}}
.raw-line{{display:flex;gap:12px;border-bottom:1px solid #eef1f4;padding:7px 0;overflow-wrap:anywhere}}
.line-no{{font-size:12px;color:#647889;min-width:28px;flex-shrink:0;font-variant-numeric:tabular-nums}}
.removed{{color:#a32929;background:#fff1f1}}.section{{border-bottom:1px solid #d9e2e9;padding:18px 0}}.section p{{margin:10px 0;overflow-wrap:anywhere}}
.badge{{display:inline-block;font-size:12px;line-height:1.5;border-radius:4px;background:#eaf3fa;padding:3px 7px;vertical-align:middle}}.badge.hold{{background:#fff0d6;color:#784500;margin:2px}}
dl{{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;font-size:14px}}dt{{color:#617487}}dd{{margin:2px 0;overflow-wrap:anywhere}}
details{{border:1px solid #d9e2e9;padding:12px 16px;margin:12px 0;border-radius:6px}}summary{{cursor:pointer;font-weight:600;overflow-wrap:anywhere}}
li{{margin:8px 0}}.ok b{{color:#126345}}.fail b{{color:#ae2828}}.muted{{color:#617487;font-size:14px}}.license{{margin-top:24px}}
pre{{white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.7 Consolas,monospace;background:#f5f7f9;padding:14px}}code{{font-size:12px;overflow-wrap:anywhere}}
@media(max-width:850px){{main{{padding:16px}}.comparison,dl{{grid-template-columns:1fr}}article{{padding:16px}}.stats{{gap:8px}}.stat{{padding:12px}}nav{{padding:10px 16px;gap:14px}}}}
@media print{{nav{{position:static}}details{{display:block}}article{{break-before:page}}.comparison{{display:block}}}}
</style></head><body><nav><a href="#top">Tổng quan</a>{nav}</nav><main id="top">
<h1>Kiểm tra dữ liệu NHS</h1><p>Lô thử SE214 · ngày đối chiếu {summary['as_of']} · báo cáo ngoại tuyến</p>
<div class="intro">Đọc từ trái sang phải: văn bản crawl gốc → bản chuẩn hóa theo mục. Mở phần kiểm tra kỹ thuật hoặc các đoạn thử để xem bằng chứng.
<strong>ĐẠT kỹ thuật không có nghĩa đã được duyệt y khoa.</strong> Bản nguồn vẫn bằng tiếng Anh.</div>
<div class="stats"><div class="stat"><b>{summary['documents_passed']}/{summary['documents']}</b>bài đạt kiểm tra kỹ thuật</div>
<div class="stat"><b>{summary['candidate_chunks']}</b>đoạn thử có nguồn và dòng gốc</div>
<div class="stat"><b>{summary['eligible_chunks']}</b>đoạn đủ điều kiện dùng chính thức</div></div>
<p>Kiểm tra {summary['checks_passed']}/{summary['checks_total']} điều kiện trên dữ liệu thật.
Không gọi LLM, không tạo embedding, không thay đổi cơ sở dữ liệu ứng dụng.
Đoạn có thuốc, trẻ em hoặc dịch vụ tại Anh được giữ nguyên để người duyệt xem, đồng thời đánh dấu HOLD.</p>
{''.join(articles)}<footer class="muted">{VERSION} · Không phải giao diện phê duyệt. Quyết định duyệt phải được ghi bởi người có thẩm quyền trong luồng quản lý nội dung.</footer>
</main></body></html>'''
    # Only generated escaped strings enter the document; source HTML is never embedded.
    (output / "index.html").write_text(html, encoding="utf-8")


def build(raw_dir=RAW, output=OUTPUT, as_of=None):
    as_of = as_of or datetime.now(timezone(timedelta(hours=7))).date()
    output.mkdir(parents=True, exist_ok=True)
    normalized_dir = output / "normalized"
    normalized_dir.mkdir(exist_ok=True)
    documents = []
    raw_hashes = {}
    for key in ("hives", "allergies", "eczema"):
        raw_path = raw_dir / f"{key}.json"
        raw_hashes[key] = sha256(raw_path.read_bytes()).hexdigest()
        record = json.loads(raw_path.read_text(encoding="utf-8"))
        if record["id"] != key:
            raise ValueError(f"unexpected record id: {key}")
        document = normalize(record, as_of)
        document["technical_checks"] = check_document(record, document, output / "source-snapshots")
        document["technical_status"] = "PASS" if all(c["passed"] for c in document["technical_checks"]) else "FAIL"
        for chunk in document["chunks"]:
            chunk["source_updated_at"] = document.get("source_updated_at")
            chunk["source_last_reviewed"] = document["source_last_reviewed"]
            chunk["source_next_review_due"] = document["source_next_review_due"]
            chunk["source_checked_at"] = document.get("source_checked_at")
            chunk["technical_status"] = document["technical_status"]
        write_json(normalized_dir / f"{key}.json", document)
        documents.append(document)
    chunks = [chunk for doc in documents for chunk in doc["chunks"]]
    (output / "candidate-chunks.jsonl").write_text(
        "".join(json.dumps(chunk, ensure_ascii=False) + "\n" for chunk in chunks), encoding="utf-8")
    (output / "eligible-chunks.jsonl").write_text(
        "".join(json.dumps(chunk, ensure_ascii=False) + "\n" for chunk in chunks if eligible(chunk)), encoding="utf-8")
    unchanged = all(sha256((raw_dir / f"{key}.json").read_bytes()).hexdigest() == value for key, value in raw_hashes.items())
    if not unchanged:
        raise ValueError("raw files changed during review")
    summary = {
        "normalizer_version": VERSION, "as_of": as_of.isoformat(), "documents": len(documents),
        "documents_passed": sum(doc["technical_status"] == "PASS" for doc in documents),
        "checks_total": sum(len(doc["technical_checks"]) for doc in documents),
        "checks_passed": sum(c["passed"] for doc in documents for c in doc["technical_checks"]),
        "candidate_chunks": len(chunks), "eligible_chunks": sum(eligible(chunk) for chunk in chunks),
        "held_chunks": sum(chunk["scope_status"] == "HOLD" for chunk in chunks),
        "removed_lines": sum(len(doc["removed_lines"]) for doc in documents),
        "raw_files_unchanged": unchanged, "raw_file_sha256": raw_hashes,
        "documents_with_overdue_source_review": [doc["id"] for doc in documents if doc["source_review_overdue"]],
        "scope": "TECHNICAL_QA_ONLY; no medical approval, no live retrieval/LLM evaluation",
    }
    write_json(output / "summary.json", summary)
    write_json(output / "checks.json", {doc["id"]: doc["technical_checks"] for doc in documents})
    render_html(documents, summary, output)
    rows = ["# Báo cáo kiểm tra lô NHS", "", f"Ngày: {as_of.isoformat()} (Việt Nam).", "",
            f"Đạt kỹ thuật: {summary['documents_passed']}/3 bài; {summary['checks_passed']}/{summary['checks_total']} điều kiện.",
            f"Có {len(chunks)} đoạn thử, {summary['held_chunks']} đoạn HOLD, {summary['eligible_chunks']} đoạn đủ điều kiện dùng chính thức.",
            "", "Bản crawl gốc giữ nguyên. Đạt kỹ thuật không thay thế duyệt chuyên môn.", "",
            "Đây là kiểm tra chuẩn hóa và chia đoạn ngoại tuyến. Chưa kiểm thử truy xuất tiếng Việt, Safety Gate hoặc câu trả lời LLM.", ""]
    for doc in documents:
        rows.extend([f"## {doc['title_vi_label']} — {doc['technical_status']}", "", f"Nguồn: {doc['source_url']}",
                     f"Ngày sửa trang: {doc.get('source_updated_at')}; ngày rà soát: {doc['source_last_reviewed']}; dự kiến: {doc['source_next_review_due']}.", ""])
        rows.extend(f"- {item}" for item in findings(doc))
        rows.append("")
    rows.extend(["## Việc còn lại để dùng trong sản phẩm", "",
                 "- Người phụ trách nội dung xác minh bản viêm da tiếp xúc đã quá ngày dự kiến rà soát.",
                 "- Biên tập và đối chiếu bản tiếng Việt dành cho người lớn; xử lý riêng thuốc, nội dung trẻ em và dịch vụ Anh.",
                 "- Xác minh quyền dùng cho bản chuyển thể; chuyên gia ghi nhận quyết định duyệt và thời điểm xác minh.",
                 "- Sau duyệt mới tích hợp kho truy xuất và kiểm thử câu hỏi tiếng Việt trên ứng dụng thật.", ""])
    (output / "report.md").write_text("\n".join(rows), encoding="utf-8")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh-source", action="store_true", help="Revalidate only the three existing NHS pages (network)")
    parser.add_argument("--as-of", type=date.fromisoformat, help="Review date; defaults to current Vietnam date")
    args = parser.parse_args()
    try:
        if args.refresh_source:
            refresh_sources()
        summary = build(as_of=args.as_of)
        print(json.dumps(summary, ensure_ascii=True, indent=2))
        return int(summary["documents_passed"] != summary["documents"])
    except (OSError, ValueError, KeyError, collect.Limit, sqlite3.Error) as exc:
        print(f"Review failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
