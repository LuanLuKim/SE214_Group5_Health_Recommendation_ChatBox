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
| `review-decisions` (tùy chọn) | quyết định duyệt SCRUM-148 (`review_decisions.json`): mã SHA256 bản chuyên gia đã duyệt, thời điểm ghi nhận |

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
- `translation_status` theo cổng kiểm định bản dịch, đã chốt với M2 ngày 20/11:

  | Giá trị | Khi nào |
  | --- | --- |
  | `pending` | Chưa có kết quả cổng kiểm định |
  | `auto_verified` | Qua cổng, không còn cảnh báo |
  | `flagged` | Còn cảnh báo hoặc không qua cổng; chờ người duyệt |
  | `reviewer_corrected` | Qua cổng, có câu người duyệt sửa, **và** chuyên gia đã duyệt lại đúng bản đang nạp: mã SHA256 của bản Việt (các dòng nối bằng LF) khớp một quyết định `APPROVED` trong file quyết định |
  | `NULL` | Chunk gốc tiếng Việt, không dịch; khi đó `original_text` và `translation_model` cũng NULL |

  Chunk có câu được sửa nhưng chưa được duyệt lại cả chunk thì ghi theo kết quả cổng chạy lại trên bản đã sửa.
  Khi duyệt bài (UC-17), điều kiện bản dịch để bật chunk là:

  ```sql
  translation_status IS NULL OR translation_status IN ('auto_verified', 'reviewer_corrected')
  ```

  `NULL` nghĩa là "không cần dịch": 37 chunk viết sẵn bằng tiếng Việt không qua bộ dịch. Chúng vẫn phải đạt duyệt nội dung như mọi chunk khác. `pending` và `flagged` không bao giờ được bật.

- Quyết định duyệt có thời điểm ghi nhận (`approval_recorded_at`, ví dụ bộ 79 chunk cấp cứu) được ghi vào `knowledge.review_events`:
  - loại `CHUNK`, đúng thời điểm trong file, dưới tài khoản `reviewer-id`;
  - tài khoản đó phải là `CONTENT_EXPERT` đang hoạt động, nếu không thì loader dừng và không ghi gì;
  - không có `reviewer-id` thì không ghi quyết định nào, báo cáo ghi số quyết định bị bỏ;
  - nạp lại cùng file không thêm dòng (id sinh từ nội dung quyết định);
  - ghi quyết định **không** bật chunk: chunk chỉ vào RAG khi bài cha được duyệt qua UC-17.

- Log chỉ ghi số đếm, không ghi nội dung.

## Chạy

Chỉ chạy được với `DATA_MODE=postgres`. Loader chạy một lần sau migration Flyway:

```
KNOWLEDGE_INGEST_ENABLED=true
KNOWLEDGE_INGEST_NORMALIZED_DIR=<thư mục chuẩn hóa>
KNOWLEDGE_INGEST_TRANSLATED_DIR=<thư mục bản dịch>
KNOWLEDGE_INGEST_REVIEW_DECISIONS=<các file review_decisions.json, cách nhau dấu phẩy>   # tùy chọn
KNOWLEDGE_INGEST_REVIEWER_ID=<id tài khoản CONTENT_EXPERT thật>                       # tùy chọn
```

Sau đó đặt `KNOWLEDGE_INGEST_ENABLED=false`.

## Còn chờ

- Bảng `knowledge.chunks` thật là migration V025 của M2 (V024 là UC-04 của M3), M2 mở PR ngày 22/11. Hiện test chạy trên bản sao bảng đề xuất ở `backend/src/test/resources/knowledge-ingest/proposed_knowledge_chunks.sql`. Bản sao này đã có giá trị `pending` và loại `CHUNK` cho `review_events`. Khi V025 lên `main`:
  1. xóa file đó;
  2. bỏ bước nạp file đó trong `KnowledgeIngestTest`;
  3. sửa tên cột trong `KnowledgeIngestRepository` nếu M2 đặt tên khác.
