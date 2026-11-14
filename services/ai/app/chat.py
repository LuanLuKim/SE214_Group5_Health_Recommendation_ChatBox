"""Chat orchestration pipeline (M4 / UC-06).

Flow:
1. Validate input (length, content, scope refusal)
2. Normalize & extract facts from user message
3. Evaluate Safety Gate (UC-07)
   - A1: EMERGENCY_NOW -> immediate emergency instructions, skip LLM
   - A2: INSUFFICIENT_INFO -> clarification request, skip LLM
4. RAG retrieval (approved knowledge chunks)
   - A3: No matching docs -> explicit limitation instruction
5. Compose minimal LLM context (no PII, data minimization)
6. Call LLM (stream/buffer)
   - A5: LLM failure/timeout -> retain Safety Gate result, emit safe error with retry flag
7. Output Checker validation
   - A4: Output violation -> replace with safe guidance
8. Emit checked SSE stream (safety, tokens, sources, done)
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from typing import Iterator, Literal, Sequence

from pydantic import BaseModel, Field

from .config import Settings
from .llm import LlmClient, LlmError, LlmMessage
from .output_checker import DEFAULT_DISCLAIMER, SAFE_FALLBACK_VIOLATION, check_output
from .safety import (
    CareLevel,
    SafetyDecision,
    SourceReference,
    WarningRule,
    evaluate_facts,
    unavailable,
)

MAX_MESSAGE_LENGTH = 4000
MIN_MESSAGE_LENGTH = 1

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

# Symptom vocabulary for Vietnamese fact extraction
_SYMPTOM_KEYWORD_MAP: dict[str, str] = {
    "sot": "sot",
    "sốt": "sot",
    "sot cao": "sot_cao",
    "sốt cao": "sot_cao",
    "kho tho": "kho_tho",
    "khó thở": "kho_tho",
    "tho gap": "kho_tho",
    "thở gấp": "kho_tho",
    "dau nguc": "dau_nguc",
    "đau ngực": "dau_nguc",
    "tuc nguc": "dau_nguc",
    "tức ngực": "dau_nguc",
    "co giat": "co_giat",
    "co giật": "co_giat",
    "hon me": "hon_me",
    "hôn mê": "hon_me",
    "bat tinh": "hon_me",
    "bất tỉnh": "hon_me",
    "non mua": "non_mua",
    "nôn mửa": "non_mua",
    "non": "non_mua",
    "nôn": "non_mua",
    "dau bung": "dau_bung",
    "đau bụng": "dau_bung",
    "tieu chay": "tieu_chay",
    "tiêu chảy": "tieu_chay",
    "chay mau": "chay_mau",
    "chảy máu": "chay_mau",
    "xuat huyet": "chay_mau",
    "xuất huyết": "chay_mau",
    "phat ban": "phat_ban",
    "phát ban": "phat_ban",
    "dau dau": "dau_dau",
    "đau đầu": "dau_dau",
    "nhuc dau": "dau_dau",
    "nhức đầu": "dau_dau",
    "chong mat": "chong_mat",
    "chóng mặt": "chong_mat",
    "ho": "ho",
    "met moi": "met_moi",
    "mệt mỏi": "met_moi",
    "dau hong": "dau_hong",
    "đau họng": "dau_hong",
    "ngat mui": "ngat_mui",
    "nghẹt mũi": "ngat_mui",
    "so mui": "ngat_mui",
    "sổ mũi": "ngat_mui",
}

# Explicit out-of-scope question patterns
_SCOPE_REFUSAL_PATTERNS = [
    re.compile(r"\b(?:hãy\s+)?(?:kê\s+đơn|cho\s+đơn\s+thuốc|kê\s+toa)\b", re.IGNORECASE),
    re.compile(r"\b(?:tôi|mình|em)\s+(?:nên\s+)?uống\s+thuốc\s+(?:gì|nào)\b", re.IGNORECASE),
    re.compile(r"\bcho\s+tôi\s+biết\s+liều\s+(?:dùng|lượng)\b", re.IGNORECASE),
    re.compile(r"\bchẩn\s+đoán\s+(?:giúp\s+tôi|chính\s+xác|cho\s+tôi|bệnh\s+gì)\b", re.IGNORECASE),
]


class ChatAnswerRequest(BaseModel):
    model_config = {"extra": "forbid"}
    session_id: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=4000)
    facts: list[str] = Field(default_factory=list, max_length=100)
    rules: list[WarningRule] = Field(default_factory=list, max_length=100)


@dataclass
class RetrievedChunk:
    id: str
    title: str
    text: str
    source: SourceReference


def _normalize_text(text: str) -> str:
    """Normalize unicode and whitespace."""
    text = unicodedata.normalize("NFC", text.strip())
    return re.sub(r"\s+", " ", text)


def is_out_of_scope(text: str) -> bool:
    """Check if user explicitly asks for prescriptions, drug names/doses, or definitive diagnosis."""
    for pattern in _SCOPE_REFUSAL_PATTERNS:
        if pattern.search(text):
            return True
    return False


def extract_facts(text: str) -> set[str]:
    """Extract explicit symptom tokens from input text.

    BR-01: An unmentioned sign is unknown, not absent. Only explicitly mentioned facts are returned.
    """
    normalized = _normalize_text(text).lower()
    facts: set[str] = set()

    for keyword, token in _SYMPTOM_KEYWORD_MAP.items():
        # Match word boundaries or substring with boundary check
        pattern = r"(?<!\w)" + re.escape(keyword) + r"(?!\w)"
        if re.search(pattern, normalized):
            facts.add(token)

    return facts


def retrieve_relevant_chunks(query: str) -> list[RetrievedChunk]:
    """RAG chunk retrieval stub.

    Returns synthetic verified chunks matching query keywords.
    When M2 provides the chunk retrieval API, this calls M2.
    """
    normalized = _normalize_text(query).lower()
    chunks: list[RetrievedChunk] = []

    if any(k in normalized for k in ["sot", "sốt", "nhiet do", "nhiệt độ"]):
        chunks.append(
            RetrievedChunk(
                id="chunk-fever-01",
                title="Hướng dẫn xử trí sốt tại nhà",
                text="Khi bị sốt, cần nghỉ ngơi ở nơi thoáng mát, uống nhiều nước hoặc dung dịch oresol để bù điện giải. Theo dõi thân nhiệt thường xuyên và lau mát bằng nước ấm.",
                source=SourceReference(
                    id="src-moh-fever",
                    name="Bộ Y tế Việt Nam",
                    url="https://moh.gov.vn/huong-dan-xu-tri-sot",
                    verifiedAt="2026-11-01",
                ),
            )
        )

    if any(k in normalized for k in ["cam", "cảm", "cum", "cúm", "ho", "hong", "họng"]):
        chunks.append(
            RetrievedChunk(
                id="chunk-flu-01",
                title="Chăm sóc và phục hồi khi cảm cúm",
                text="Cảm cúm thông thường do virus gây ra, đa số tự hồi phục sau 5-7 ngày. Giữ ấm cơ thể, súc họng bằng nước muối sinh lý, ăn uống đủ chất và tránh làm việc quá sức.",
                source=SourceReference(
                    id="src-who-flu",
                    name="WHO Viet Nam",
                    url="https://who.int/vietnam/influenza",
                    verifiedAt="2026-11-01",
                ),
            )
        )

    return chunks


def _format_sse(event_type: str, data: dict | str) -> str:
    """Format SSE payload."""
    payload = {"type": event_type, "data": data}
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def orchestrate_chat(
    req: ChatAnswerRequest,
    settings: Settings,
    llm_client: LlmClient | None = None,
) -> Iterator[str]:
    """Execute the full UC-06 chat orchestration pipeline, yielding SSE events."""
    raw_message = req.message

    # 1. Validation (length and content)
    if not raw_message or not raw_message.strip():
        yield _format_sse("error", {"code": "INVALID_REQUEST", "message": "Tin nhắn không được để trống."})
        yield _format_sse("done", {"status": "FAILED"})
        return

    if len(raw_message) > MAX_MESSAGE_LENGTH:
        yield _format_sse("error", {"code": "PAYLOAD_TOO_LARGE", "message": "Tin nhắn vượt quá độ dài cho phép (4000 ký tự)."})
        yield _format_sse("done", {"status": "FAILED"})
        return

    # Check for direct out-of-scope request (prescription, dosage, diagnosis demand)
    if is_out_of_scope(raw_message):
        yield _format_sse("safety", unavailable().model_dump())
        yield _format_sse("token", OUT_OF_SCOPE_REFUSAL)
        yield _format_sse("sources", [])
        yield _format_sse("done", {"careLevel": None, "status": "OUT_OF_SCOPE"})
        return

    # 2. Extract facts from text and combine with any supplied facts
    extracted = extract_facts(raw_message)
    all_facts = extracted.union(set(req.facts))

    # 3. Safety Gate evaluation (UC-07)
    decision: SafetyDecision
    if req.rules and all_facts:
        decision = evaluate_facts(all_facts, req.rules)
    else:
        decision = unavailable()

    # Flow A1: EMERGENCY_NOW -> immediate instructions, no LLM call
    if decision.status == "EVALUATED" and decision.careLevel == "EMERGENCY_NOW":
        yield _format_sse("safety", decision.model_dump())
        yield _format_sse("token", EMERGENCY_INSTRUCTIONS)
        yield _format_sse("sources", [s.model_dump() for s in decision.sources])
        yield _format_sse("done", {
            "careLevel": decision.careLevel,
            "specialties": decision.specialtyIds,
            "status": "EMERGENCY_HANDLED",
        })
        return

    # Flow A2: INSUFFICIENT_INFO -> clarify missing fields, no LLM call
    if decision.status == "EVALUATED" and decision.careLevel == "INSUFFICIENT_INFO":
        yield _format_sse("safety", decision.model_dump())
        fields_str = ", ".join(decision.missingFields) if decision.missingFields else "thời gian khởi phát và triệu chứng kèm theo"
        clarify_msg = f"{CLARIFICATION_TEMPLATE}- {fields_str}"
        yield _format_sse("token", clarify_msg)
        yield _format_sse("sources", [])
        yield _format_sse("done", {
            "careLevel": decision.careLevel,
            "missingFields": decision.missingFields,
            "status": "CLARIFICATION_NEEDED",
        })
        return

    # 4. RAG retrieval
    chunks = retrieve_relevant_chunks(raw_message)
    has_documents = len(chunks) > 0

    # Collect sources from rules and RAG chunks
    combined_sources: list[SourceReference] = list(decision.sources)
    seen_source_ids = {s.id for s in combined_sources}
    for chunk in chunks:
        if chunk.source.id not in seen_source_ids:
            combined_sources.append(chunk.source)
            seen_source_ids.add(chunk.source.id)

    # 5. Build minimal context for LLM
    care_level_context = f"Mức độ cần chú ý: {decision.careLevel}" if decision.careLevel else "Mức độ cần chú ý: Chưa xác định"

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

    # 6. Call LLM (stream and buffer)
    if llm_client is None:
        llm_client = LlmClient(settings)

    full_response = ""
    try:
        # Buffer entire response for pre-display safety check (ambiguity A2, NFR-02)
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
        yield _format_sse("done", {
            "careLevel": decision.careLevel,
            "status": "FALLBACK_SAFETY_RETAINED",
        })
        return
    except Exception:
        yield _format_sse("safety", decision.model_dump())
        yield _format_sse("error", {"code": "INTERNAL_ERROR", "message": "Đã xảy ra lỗi khi tạo câu trả lời. Vui lòng thử lại.", "canRetry": True})
        yield _format_sse("done", {"status": "FAILED"})
        return

    # 7. Output Checker validation
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

    # 8. Emit response
    yield _format_sse("safety", decision.model_dump())

    # Stream out the validated text in progressive chunks for UI rendering
    # Break into natural sentence or paragraph tokens
    tokens = re.split(r"(\s+)", final_text)
    for tok in tokens:
        if tok:
            yield _format_sse("token", tok)

    yield _format_sse("sources", [s.model_dump() for s in combined_sources])
    yield _format_sse("done", {
        "careLevel": decision.careLevel,
        "specialties": decision.specialtyIds,
        "status": "COMPLETED",
    })
