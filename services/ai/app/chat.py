"""Chat orchestration pipeline (M4 / UC-06).

Flow:
1. Validate input (length, content)
2. Accept authoritative SafetyDecision from M1 backend (RuleBasedSafetyEvaluator)
   - A1: EMERGENCY_NOW -> immediate emergency instructions, skip LLM
   - Out-of-scope demand (prescription, dosage, diagnosis) -> refusal guidance, skip LLM
   - A2: INSUFFICIENT_INFO -> clarification request, skip LLM
3. RAG chunks (supplied by backend M2/M7, or explicit synthetic mock/test stub when enabled)
   - A3: No matching docs -> explicit limitation instruction, never fabricate sources
4. Compose minimal LLM context (no PII, data minimization, UNAVAILABLE never treated as low risk)
5. Call LLM (stream/buffer)
   - A5: LLM failure/timeout -> retain Safety Gate result, emit safe error with retry flag
6. Output Checker validation
   - A4: Output violation -> replace with safe guidance
7. Emit checked SSE stream (safety, tokens, sources, done)
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Iterator

from pydantic import BaseModel, Field

from .config import Settings
from .llm import LlmClient, LlmError, LlmMessage
from .output_checker import SAFE_FALLBACK_VIOLATION, check_output
from .safety import SafetyDecision, SourceReference, unavailable

MAX_MESSAGE_LENGTH = 4000
MIN_MESSAGE_LENGTH = 1
MAX_RAG_CHUNKS = 5
MAX_RAG_WORDS = 1500
LEXICON_PATH = Path(__file__).with_name("chat_lexicon.json")

OUT_OF_SCOPE_REFUSAL = (
    "Trợ lý AI không được phép kê đơn thuốc, đề xuất liều dùng hoặc đưa ra kết luận chẩn đoán y khoa. "
    "Mọi thông tin chỉ mang tính chất tham khảo giáo dục sức khỏe chung. "
    "Vui lòng tham khảo ý kiến của bác sĩ hoặc đến cơ sở y tế gần nhất để được thăm khám và điều trị."
)

EMERGENCY_INSTRUCTIONS = (
    "CẢNH BÁO KHẨN CẤP: Triệu chứng hoặc dấu hiệu bạn mô tả cho thấy tình trạng nguy hiểm "
    "cần được can thiệp y tế ngay lập tức.\n\n"
    "HÃY THỰC HIỆN NGAY:\n"
    "1. Gọi số Cấp cứu 115 hoặc dịch vụ cấp cứu y tế địa phương ngay lập tức.\n"
    "2. Đến phòng Cấp cứu của bệnh viện hoặc cơ sở y tế gần nhất.\n"
    "3. Không tự ý dùng thuốc hoặc trì hoãn việc đến cơ sở y tế."
)

CLARIFICATION_TEMPLATE = (
    "Để có thể hỗ trợ và đưa ra định hướng chăm sóc phù hợp hơn, "
    "bạn vui lòng cung cấp thêm một số thông tin còn thiếu sau:\n"
)


@dataclass(frozen=True)
class SyntheticRagStub:
    id: str
    title: str
    text: str
    keywords: tuple[str, ...]
    source: SourceReference


@dataclass(frozen=True)
class ChatLexicon:
    symptom_keywords: dict[str, str]
    scope_refusal_patterns: tuple[re.Pattern[str], ...]
    synthetic_rag_chunks: tuple[SyntheticRagStub, ...]


@lru_cache(maxsize=1)
def load_chat_lexicon(path: Path = LEXICON_PATH) -> ChatLexicon:
    """Load symptom vocabulary, refusal patterns, and synthetic test chunks from JSON."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    symptom_keywords = {
        str(k).strip().lower(): str(v).strip()
        for k, v in raw.get("symptom_keywords", {}).items()
        if str(k).strip() and str(v).strip()
    }
    patterns = tuple(
        re.compile(pattern, re.IGNORECASE)
        for pattern in raw.get("scope_refusal_patterns", [])
    )
    stubs = tuple(
        SyntheticRagStub(
            id=item["id"],
            title=item["title"],
            text=item["text"],
            keywords=tuple(str(kw).strip().lower() for kw in item.get("keywords", []) if str(kw).strip()),
            source=SourceReference.model_validate(item["source"]),
        )
        for item in raw.get("synthetic_rag_chunks", [])
    )
    return ChatLexicon(
        symptom_keywords=symptom_keywords,
        scope_refusal_patterns=patterns,
        synthetic_rag_chunks=stubs,
    )


class RagChunk(BaseModel):
    id: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=300)
    text: str = Field(min_length=1, max_length=10000)
    source: SourceReference


class ChatAnswerRequest(BaseModel):
    model_config = {"extra": "forbid"}
    session_id: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=4000)
    facts: list[str] = Field(default_factory=list, max_length=100)
    safety_decision: SafetyDecision | None = None
    chunks: list[RagChunk] = Field(default_factory=list, max_length=20)


def _normalize_text(text: str) -> str:
    """Normalize unicode and whitespace."""
    text = unicodedata.normalize("NFC", text.strip())
    return re.sub(r"\s+", " ", text)


def _matches_keyword(normalized_lower: str, keyword: str) -> bool:
    """Match whole word/phrase tokens only so 'ho' never matches 'cho' or 'nhỏ'."""
    pattern = r"(?<!\w)" + re.escape(keyword.lower()) + r"(?!\w)"
    return bool(re.search(pattern, normalized_lower))


def is_out_of_scope(text: str) -> bool:
    """Check if user explicitly asks for prescriptions, drug names/doses, or definitive diagnosis."""
    normalized = _normalize_text(text)
    for pattern in load_chat_lexicon().scope_refusal_patterns:
        if pattern.search(normalized):
            return True
    return False


def extract_facts(text: str) -> set[str]:
    """Extract explicit M1-aligned symptom tokens from input text using word boundaries.

    BR-01: An unmentioned sign is unknown, not absent. Only explicitly mentioned facts are returned.
    """
    normalized = _normalize_text(text).lower()
    facts: set[str] = set()
    for keyword, token in load_chat_lexicon().symptom_keywords.items():
        if _matches_keyword(normalized, keyword):
            facts.add(token)
    return facts


def retrieve_relevant_chunks(query: str, *, enable_stub: bool = False) -> list[RagChunk]:
    """Retrieve synthetic test chunks only when explicitly enabled in mock/test mode.

    Uses token/word-boundary matching so substrings like 'cho'/'nhỏ' do not trigger 'ho'.
    In production (enable_stub=False), returns [] when backend does not supply approved chunks.
    """
    if not enable_stub:
        return []
    normalized = _normalize_text(query).lower()
    matched: list[RagChunk] = []
    for stub in load_chat_lexicon().synthetic_rag_chunks:
        if any(_matches_keyword(normalized, kw) for kw in stub.keywords):
            matched.append(
                RagChunk(
                    id=stub.id,
                    title=stub.title,
                    text=stub.text,
                    source=stub.source,
                )
            )
    return matched


def _apply_rag_budget(chunks: list[RagChunk]) -> list[RagChunk]:
    """Cap RAG context to at most 5 chunks and 1500 cumulative words (always keeping at least 1)."""
    selected: list[RagChunk] = []
    total_words = 0
    for chunk in chunks:
        if len(selected) >= MAX_RAG_CHUNKS:
            break
        words = len(chunk.text.split())
        if selected and total_words + words > MAX_RAG_WORDS:
            break
        selected.append(chunk)
        total_words += words
    return selected


def _format_sse(event_type: str, data: dict | list | str) -> str:
    """Format SSE payload."""
    payload = {"type": event_type, "data": data}
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def orchestrate_chat(
    req: ChatAnswerRequest,
    settings: Settings,
    llm_client: LlmClient | None = None,
) -> Iterator[str]:
    """Execute the UC-06 chat orchestration pipeline, yielding SSE events."""
    raw_message = req.message

    # 1. Validation (length and content)
    if not raw_message or not raw_message.strip():
        yield _format_sse("error", {"code": "INVALID_REQUEST", "message": "Tin nhắn không được để trống."})
        yield _format_sse("done", {"status": "FAILED"})
        return

    if len(raw_message) > MAX_MESSAGE_LENGTH:
        yield _format_sse(
            "error",
            {"code": "PAYLOAD_TOO_LARGE", "message": "Tin nhắn vượt quá độ dài cho phép (4000 ký tự)."},
        )
        yield _format_sse("done", {"status": "FAILED"})
        return

    # 2. Authoritative Safety Decision supplied by M1 (Spring Boot RuleBasedSafetyEvaluator).
    # FastAPI /v1/chat/answer does NOT re-evaluate safety rules to keep a single source of truth.
    decision: SafetyDecision = req.safety_decision if req.safety_decision is not None else unavailable()

    # Flow A1: EMERGENCY_NOW -> immediate instructions, no LLM call (supersedes other refusals)
    if decision.status == "EVALUATED" and decision.careLevel == "EMERGENCY_NOW":
        yield _format_sse("safety", decision.model_dump())
        yield _format_sse("token", EMERGENCY_INSTRUCTIONS)
        yield _format_sse("sources", [s.model_dump() for s in decision.sources])
        yield _format_sse(
            "done",
            {
                "careLevel": decision.careLevel,
                "specialties": decision.specialtyIds,
                "status": "EMERGENCY_HANDLED",
            },
        )
        return

    # Check for direct out-of-scope request (prescription, dosage, diagnosis demand)
    if is_out_of_scope(raw_message):
        yield _format_sse("safety", decision.model_dump())
        yield _format_sse("token", OUT_OF_SCOPE_REFUSAL)
        yield _format_sse("sources", [])
        yield _format_sse("done", {"careLevel": decision.careLevel, "status": "OUT_OF_SCOPE"})
        return

    # Flow A2: INSUFFICIENT_INFO -> clarify missing fields, no LLM call
    if decision.status == "EVALUATED" and decision.careLevel == "INSUFFICIENT_INFO":
        yield _format_sse("safety", decision.model_dump())
        fields_str = (
            ", ".join(decision.missingFields)
            if decision.missingFields
            else "thời gian khởi phát và triệu chứng kèm theo"
        )
        clarify_msg = f"{CLARIFICATION_TEMPLATE}- {fields_str}"
        yield _format_sse("token", clarify_msg)
        yield _format_sse("sources", [])
        yield _format_sse(
            "done",
            {
                "careLevel": decision.careLevel,
                "missingFields": decision.missingFields,
                "status": "CLARIFICATION_NEEDED",
            },
        )
        return

    # 3. RAG chunks (prefer caller-supplied verified chunks; otherwise synthetic stub only when enabled)
    raw_chunks = (
        list(req.chunks)
        if req.chunks
        else retrieve_relevant_chunks(raw_message, enable_stub=settings.enable_synthetic_rag_stub)
    )
    chunks = _apply_rag_budget(raw_chunks)
    has_documents = len(chunks) > 0

    # Collect sources from Safety Gate rules and RAG chunks
    combined_sources: list[SourceReference] = list(decision.sources)
    seen_source_ids = {s.id for s in combined_sources}
    for chunk in chunks:
        if chunk.source.id not in seen_source_ids:
            combined_sources.append(chunk.source)
            seen_source_ids.add(chunk.source.id)

    # 4. Build minimal context for LLM (never treat UNAVAILABLE as low risk)
    if decision.status == "EVALUATED" and decision.careLevel:
        care_level_context = f"Mức độ cần chú ý: {decision.careLevel}"
    else:
        care_level_context = (
            "Mức độ cần chú ý: UNAVAILABLE (Chưa đánh giá được mức độ rủi ro lâm sàng; "
            "KHÔNG được tự kết luận an toàn hoặc nguy cơ thấp, luôn nhắc người dùng đi khám nếu triệu chứng nặng hoặc kéo dài)"
        )

    system_instructions = [
        "Bạn là Trợ lý Sức khỏe AI tiếng Việt, cung cấp thông tin giáo dục sức khỏe và hướng dẫn chăm sóc chung.",
        "Quy tắc an toàn bắt buộc:",
        "- KHÔNG đưa ra kết luận chẩn đoán xác định bệnh.",
        "- KHÔNG kê đơn thuốc, không chỉ định liều lượng cụ thể và không bảo người dùng dừng thuốc đang điều trị.",
        f"- {care_level_context}. Nội dung tư vấn phải phù hợp với mức độ này và không được hạ thấp mức độ nguy hiểm.",
    ]

    if has_documents:
        system_instructions.append("Tài liệu tham khảo đã duyệt:")
        for c in chunks:
            system_instructions.append(f"[{c.title}] ({c.source.name}): {c.text}")
        system_instructions.append("Chỉ sử dụng thông tin từ các tài liệu trên, trích dẫn nguồn khi nêu thông tin.")
    else:
        # Flow A3: no matching documents
        system_instructions.append(
            "Hiện tại hệ thống không tìm thấy tài liệu y khoa chính thức nào khớp với câu hỏi. "
            "Hãy thông báo rõ ràng về giới hạn này cho người dùng, không được tự bịa đặt kiến thức y khoa hoặc nguồn tham khảo."
        )

    system_instructions.append("Luôn giữ giọng điệu đồng cảm, khoa học, khách quan và dễ hiểu bằng tiếng Việt.")

    messages: list[LlmMessage] = [
        {"role": "system", "content": "\n\n".join(system_instructions)},
        {"role": "user", "content": raw_message},
    ]

    # 5. Call LLM (stream and buffer)
    if llm_client is None:
        llm_client = LlmClient(settings)

    full_response = ""
    try:
        # Buffer entire response for pre-display safety check (A4, NFR-02)
        for token in llm_client.stream(messages):
            full_response += token
    except LlmError:
        # Flow A5: LLM failure or timeout -> keep Safety Gate result, report safe error, allow retry
        yield _format_sse("safety", decision.model_dump())
        error_msg = (
            "Dịch vụ trợ lý AI tạm thời không phản hồi. "
            "Kết quả đánh giá an toàn ban đầu vẫn được lưu giữ. "
            "Vui lòng nhấn nút thử lại sau giây lát."
        )
        yield _format_sse("error", {"code": "LLM_SERVICE_UNAVAILABLE", "message": error_msg, "canRetry": True})
        yield _format_sse("sources", [s.model_dump() for s in combined_sources])
        yield _format_sse(
            "done",
            {
                "careLevel": decision.careLevel,
                "status": "FALLBACK_SAFETY_RETAINED",
            },
        )
        return
    except Exception:
        yield _format_sse("safety", decision.model_dump())
        yield _format_sse(
            "error",
            {
                "code": "INTERNAL_ERROR",
                "message": "Đã xảy ra lỗi khi tạo câu trả lời. Vui lòng thử lại.",
                "canRetry": True,
            },
        )
        yield _format_sse("done", {"status": "FAILED"})
        return

    # 6. Output Checker validation
    check_res = check_output(
        full_response,
        care_level=decision.careLevel,
        provided_sources=combined_sources,
    )

    final_text: str
    if not check_res.passed:
        # Flow A4: Output Checker detected violation -> replace with safe guidance
        final_text = check_res.safe_replacement or SAFE_FALLBACK_VIOLATION
    else:
        final_text = check_res.sanitized_text or full_response

    # 7. Emit response
    yield _format_sse("safety", decision.model_dump())

    tokens = re.split(r"(\s+)", final_text)
    for tok in tokens:
        if tok:
            yield _format_sse("token", tok)

    yield _format_sse("sources", [s.model_dump() for s in combined_sources])
    yield _format_sse(
        "done",
        {
            "careLevel": decision.careLevel,
            "specialties": decision.specialtyIds,
            "status": "COMPLETED",
        },
    )
