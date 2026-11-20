# Nạp dữ liệu M7 vào `knowledge.*` (SCRUM-48, #50)

Phụ trách: M7 (LuanLu). Bảng `knowledge.chunks` và hàng đợi duyệt thuộc M2 (Giang).

## Làm gì

Đọc đầu ra của bộ chuẩn hóa và của `tools/translate`, rồi ghi vào `knowledge.articles` và `knowledge.chunks` trong **một transaction**. Lỗi ở bất kỳ dòng nào thì không ghi gì.

| Đầu vào | Lấy |
| --- | --- |
| `<translated-dir>/db_rows_vi.json` | bài: tiêu đề, nội dung tiếng Việt, chủ đề, nguồn |
| `<translated-dir>/chunks_vi.jsonl` | chunk: bản Việt, bản Anh để đối chiếu, nhãn phạm vi, kết quả cổng kiểm định bản dịch |
| `<normalized-dir>/chunks.jsonl` | thời điểm crawl thật của từng chunk (`fetched_at`), `rule_tags`, `normalizer_version` |
| `<normalized-dir>/articles/*.json` | `source_updated_at` của bài |

## Quy tắc

- Mọi dòng mới đều là `DRAFT`, `rag_eligible = false`, `verified_at = NULL`. Loader không bao giờ duyệt. Chỉ UC-17 mới đặt `APPROVED`.
- Chỉ nạp kho `rag`. Trang thuốc (`expert_reference`) không bao giờ được nạp.
- Chạy lại với cùng file thì không đổi gì: không tăng `revision`, giữ nguyên quyết định duyệt.
- Văn bản đổi:
  - Dòng quay về `DRAFT`. Chunk về `rag_eligible = false`; bài bị xóa `verified_at` và thông tin gửi duyệt, `revision` tăng 1.
  - Lý do: quyết định duyệt cũ không áp dụng cho văn bản mới.
  - Chỉ đổi nhãn hoặc metadata thì vẫn giữ quyết định duyệt.
- Chunk của một bài đã nạp nhưng không còn trong file thì chuyển `RETIRED`:
  - Thường gặp khi văn bản nguồn đổi, vì id chunk sinh từ nội dung.
  - Dòng được giữ lại để lưu lịch sử duyệt.
  - Nếu chunk xuất hiện lại thì về `DRAFT`.
- Không ghi đè bài do người viết (UC-15, `created_by` khác NULL) hoặc dữ liệu tổng hợp (`is_synthetic`).
- `translation_status` theo cổng kiểm định bản dịch:

  | Giá trị | Khi nào |
  | --- | --- |
  | `auto_verified` | Qua cổng, không còn cảnh báo |
  | `flagged` | Còn cảnh báo hoặc không qua cổng; chờ người duyệt |
  | `reviewer_corrected` | Qua cổng và có câu người duyệt đã sửa |
  | `NULL` | Chunk gốc tiếng Việt, không dịch; khi đó `original_text` và `translation_model` cũng NULL |

- Log chỉ ghi số đếm, không ghi nội dung.

## Chạy

Chỉ chạy được với `DATA_MODE=postgres`. Loader chạy một lần sau migration Flyway:

```
KNOWLEDGE_INGEST_ENABLED=true
KNOWLEDGE_INGEST_NORMALIZED_DIR=<thư mục chuẩn hóa>
KNOWLEDGE_INGEST_TRANSLATED_DIR=<thư mục bản dịch>
```

Sau đó đặt `KNOWLEDGE_INGEST_ENABLED=false`.

## Còn chờ

- Bảng `knowledge.chunks` thật là migration V025 của M2 (V024 là UC-04 của M3). Hiện test chạy trên bản sao bảng đề xuất ở `backend/src/test/resources/knowledge-ingest/proposed_knowledge_chunks.sql`. Khi V025 lên `main`:
  1. xóa file đó;
  2. bỏ bước nạp file đó trong `KnowledgeIngestTest`;
  3. sửa tên cột trong `KnowledgeIngestRepository` nếu M2 đặt tên khác.
- Nạp 79 quyết định SCRUM-148 vào `knowledge.review_events` (loại `CHUNK`). Phần này cần V025 và câu trả lời của M2 về chunk thuộc bài chưa duyệt. Lệnh nạp nhận `reviewer_id` qua tham số và kiểm tài khoản đó là `CONTENT_EXPERT` đang hoạt động.
