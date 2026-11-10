# M7 — Kế hoạch sửa crawler, chuẩn hóa, dịch và RAG

Đối chiếu ngày **10/11/2026** trên dữ liệu thật trong `.data/` (51 bài: 42 NHS tiếng Anh, 9 WHO VN tiếng Việt; 496 chunk).
Issue chính: **#79** (nhánh `79-chunk-rag-giu-duong-dan-tieu-de-gan-co-k`). Nạp DB: **#50** (SCRUM-48, hạn 14/11).

Mọi kết quả vẫn phải giữ `review_status = DRAFT`, `rag_eligible = false` cho tới khi có người duyệt (UC-17).

---

## 0. Bắt đầu tối nay

```powershell
git fetch origin
git switch 79-chunk-rag-giu-duong-dan-tieu-de-gan-co-k
conda activate se214-crawler
python -B tools/normalize/test_normalize.py      # mốc trước khi sửa
python -B tools/normalize/run.py                 # tạo lại .data/normalized
```

> **Cập nhật 10/11 tối:** P1 và P2 đã xong trên nhánh #79 (2 commit). Kết quả và các ghi nhận về dữ liệu: [DATA_STATUS.md](DATA_STATUS.md). Ghi chú cách làm khác kế hoạch: bảng, `summary` và nhãn được xử lý ở normalizer từ thông tin bảng/DOM crawler đã lưu, không cần đổi định dạng block; crawler sửa phần WHO accordion, UI thừa và trang hub. Gộp chunk nhỏ được phép vượt ranh giới `h2` (mỗi phần giữ dòng tiêu đề riêng) vì nhiều bài NHS có `h2` rất ngắn. `--max-requests` chưa làm: ngân sách hiện đủ (118/200).

Thứ tự đề xuất: **P1 → P2 → P3** (đủ cho #79 và #50), P4 nếu còn thời gian, P5 cần chốt hướng trước khi code.

---

## 1. Số liệu đo được

| Chỉ số | Giá trị | Nhận xét |
| --- | --- | --- |
| Số từ mỗi chunk | min 9 · trung vị 64 · TB 81 · max 497 | Quá nhỏ cho RAG; mục tiêu 120–400 |
| Chunk < 30 từ / < 50 từ | 77 / 181 (37%) | Phần lớn là mục "Do", "Don't", "Important", "Find out more" |
| Section cấp h3+ mất tiêu đề cha | 198/499 | Chunk tên `Do` (20), `Don't` (16), `Important` (8) không biết thuộc mục nào |
| Chunk tên "Overview" | 50 | Phần mở đầu bài, không có tiêu đề thật |
| Có "Call 999 / A&E / life-threatening / ambulance" nhưng **không** có cờ `emergency` | 17 (đang gắn 25) | Nguy hiểm nhất: Safety Gate bỏ sót |
| Cờ `paediatric` sai (`bé` khớp `béo phì`) | 1 | Regex không có ranh giới từ cho tiếng Việt |
| Cờ `medication` sai (`thuốc` khớp `hút thuốc lá`) | 5 | Như trên |
| Nội dung chunk trùng lặp | 7 cặp | NHS lặp khối cảnh báo ở nhiều mục |
| Chunk tách "(phần N)" | 4 | Cờ gắn theo section, không theo chunk |
| `articles/*.json` vs `chunks.jsonl` | 1 bài lệch số chunk | `to_dict()` chia 650 từ, `run.py` chia 500 từ → id khác nhau |
| `db_rows.json` thiếu `id` | 51/51 | `knowledge.articles.id` là uuid bắt buộc → chưa nạp được |
| Ngôn ngữ chunk | 456 en · 40 vi | Người dùng hỏi tiếng Việt → cần dịch hoặc embedding đa ngữ |

---

## P1. Crawler (`tools/crawl`) — nhỏ, làm trước

- [x] **Bảng bị làm phẳng.** Bảng NHS (ví dụ "Differences between cold and flu") ra thành chuỗi `th`, `p` xen kẽ, mất quan hệ hàng–cột. Xuất bảng thành block `table` có `rows: [[cell,...]]`; normalizer render thành dòng `Flu: …; Cold: …`.
- [x] **`summary` (khối mở rộng `<details>`) không được coi là tiêu đề** (21 block) → nội dung bị dồn vào mục trước. Đánh dấu là heading cấp con của heading gần nhất.
- [x] **Block `text` là nhãn giao diện** ("Information:", "Find a pharmacy") — 144 block. Gắn `role: callout_label | nav_link` để normalizer bỏ hoặc ghép vào câu sau.
- [ ] `--max-requests N` để mở ngân sách có kiểm soát (hiện cố định 20/lượt).
- [x] Ghi `final_url` sau chuyển hướng và gợi ý cập nhật `docs/urls.txt` (không tự sửa file).
- [x] Đồng bộ tài liệu: `DATA_STATUS.md` nói WHO metadata-only nhưng `source_policies.json` đã `collect_text`.

**Test thêm:** HTML tổng hợp có `<table>`, `<details><summary>`, callout NHS → block đúng loại.

## P2. Chuẩn hóa (`tools/normalize`) — trọng tâm #79

- [x] **`heading_path`**: giữ ngăn xếp tiêu đề theo cấp (`h2 > h3 > summary`). Mỗi chunk có `heading_path: ["Flu", "How to treat flu yourself", "Do"]`, và phần đầu văn bản chunk ghi đủ đường dẫn: `Flu › How to treat flu yourself › Do`.
- [x] **Gộp section nhỏ**: section con < 60 từ gộp vào section anh em/cha cùng `h2` cho tới ngưỡng; không bao giờ gộp qua ranh giới `h2`. Mục tiêu: không còn chunk < 30 từ trừ khi cả `h2` chỉ có vậy.
- [x] **Cờ theo từng chunk**, tính lại sau khi chia/gộp, không kế thừa từ section.
- [x] **Cờ `emergency`** quét toàn bộ văn bản chunk, thêm mẫu: `call 999`, `go to A&E`, `ambulance`, `life-threatening`, `medical emergency`, `immediate action required`, `gọi 115`, `cấp cứu`. Giữ nguyên các mẫu tiếng Việt hiện có.
- [x] **Sửa regex tiếng Việt**: dùng ranh giới chữ Unicode (`(?<![\wÀ-ỹ])bé(?![\wÀ-ỹ])`), loại `hút thuốc`, `thuốc lá` khỏi `medication`.
- [x] **Bỏ chunk rác**: chỉ gồm link "Find out more"/tên tổ chức, hoặc chỉ lặp lại tiêu đề. Ghi `dropped: [{id, reason}]` vào `summary.json`, không xóa im lặng.
- [x] **Khử trùng lặp** trong cùng bài theo `text_sha256` của phần thân; giữ bản đầu, ghi `duplicate_of`.
- [x] **Một ngưỡng duy nhất**: `to_dict()` nhận `max_words` từ `run.py`; id chunk trong `articles/*.json` và `chunks.jsonl` phải trùng.
- [x] **Metadata đủ cho RAG và Safety Gate**: `title`, `topic`, `language`, `source_last_reviewed`, `source_next_review_due`, `fetched_at`, `heading_path`, `scope_tags`.
- [x] **`db_rows.json` có `id`**: uuid5 tất định từ `source_url` (namespace cố định trong code). Chunk có `article_uuid` cùng giá trị.
- [x] Đánh dấu nội dung quá hạn rà soát nguồn (`source_next_review_due` < hôm nay) bằng cờ `stale_source`.

**Test thêm** (đã liệt kê trong #79): heading_path có tiêu đề cha; "Call 999" giữa đoạn có `emergency`; "béo phì"/"hút thuốc lá" không gắn cờ; chunk "Find out more" bị loại và được đếm; chạy hai lần cho cùng id; vẫn `DRAFT`.

## P3. Nạp dữ liệu (#50, hạn 14/11)

- [ ] Migration `009_knowledge_chunks.sql` (số tiếp theo sau `008_lifestyle_reminders.sql`): bảng `knowledge.chunks` — `id`, `article_id` FK, `heading_path text[]`, `text`, `language`, `scope_tags text[]`, `text_sha256`, `word_count`, `review_status`, `rag_eligible bool default false`, `source_next_review_due date`. Bàn với chủ M2/M4 trước khi merge vì là schema của M2.
- [ ] `tools/load/run.py`: đọc `db_rows.json` + `chunks.jsonl`, upsert theo uuid, `--dry-run`, in số dòng thêm/sửa/bỏ qua. Không import code từ `apps/`.
- [ ] Test với PGlite/Postgres: chạy hai lần không đổi số dòng; không dòng nào `APPROVED`.

## P4. Dịch (`tools/translate`) — lỗi đã thấy khi đọc code

- [ ] **Lỗi nghiêm trọng: `db_rows_vi.json` chỉ chép nguyên nội dung tiếng Anh rồi gắn `language: "vi"`.** Phải ghép lại từ các chunk đã dịch, hoặc không xuất file này cho tới khi có bản dịch bài.
- [ ] **Bản địa hóa chạy trước khi dịch**: câu tiếng Anh bị chèn sẵn cụm tiếng Việt ("Gọi cấp cứu 115 ngay…", "trẻ dưới 5 tuổi") rồi mới đưa vào mô hình → đầu vào lai hai ngôn ngữ, mô hình dễ dịch sai hoặc lặp. Đổi sang: thay bằng placeholder (`«EMERG_000»`, `«AGE_000»`) trước khi dịch, khôi phục bằng cụm tiếng Việt sau khi dịch.
- [ ] **Placeholder `«DRUG_000»` có thể bị mô hình làm hỏng** (đổi ký tự, thêm dấu cách). Khôi phục chịu được biến thể khoảng trắng/dấu; nếu mất placeholder → chunk `failed_quality_gate`.
- [ ] **Quality gate thiếu kiểm tra an toàn**, thêm:
  - số và đơn vị giữ nguyên (`mg`, `ml`, `°C`, giờ, tuổi) giữa nguồn và bản dịch;
  - nguồn có `999`/`A&E` thì bản dịch phải có `115`;
  - số câu phủ định ("do not", "don't", "never") ≈ số "không"/"đừng" — mất phủ định là đảo nghĩa;
  - không còn từ tiếng Anh dài bất thường (dấu hiệu không dịch).
- [ ] `title_vi`, `heading_path_vi` đang để trống → dịch tiêu đề theo lô riêng, dùng cache theo chuỗi.
- [ ] **Kiểm lại cách gọi MedEV/VinAI.** Code thêm tiền tố `"en: "`; theo hướng dẫn của mô hình `vinai-translate-en2vi-v2` cần đặt `src_lang="en_XX"` cho tokenizer và token bắt đầu giải mã `vi_VN`. Đọc model card trước khi chạy. Tài liệu ghi mô hình này "fine-tune trên MedEV" — cần kiểm chứng, có thể là nhầm với mô hình trong bài báo MedEV.
- [ ] Chạy thử 20 chunk có cờ `emergency`/`medication` bằng cả ba engine, lưu kết quả cạnh nhau để chọn engine; chưa chạy toàn bộ.

## P5. RAG — cần chốt hướng trước khi code

### Hiện trạng
- `KnowledgeRetriever.retrieve()` trong `packages/contracts` chỉ trả `ArticleSummary` (tiêu đề + nguồn), **không có nội dung đoạn** → M4 không có gì để đưa vào prompt.
- Chưa có embedding, chưa có chỉ mục, `chat/service.ts` rỗng, LLM client cố ý chưa nối vào route.
- 92% chunk là tiếng Anh, người dùng hỏi tiếng Việt.

### Đề xuất
1. **Contract mới** (bàn với M2/M4): `retrieve(query, { language, limit }) → RetrievedChunk[]` gồm `chunkId`, `articleId`, `headingPath`, `text`, `scopeTags`, `source`, `score`. Chỉ trả chunk `APPROVED` + `rag_eligible = true`.
2. **Tìm kiếm lai**: `tsvector` (khớp chính xác tên thuốc, triệu chứng) + vector (`pgvector`) với embedding đa ngữ `BAAI/bge-m3` (hỏi tiếng Việt vẫn tìm được đoạn tiếng Anh). Gộp điểm bằng RRF.
3. **Dịch vụ truy xuất bằng Python (FastAPI)**: embedding và mô hình dịch đều là Python, nên tách `services/rag` thành dịch vụ HTTP riêng (`POST /retrieve`, `POST /embed`, `GET /healthz`). API Node gọi qua HTTP theo contract ở bước 1. Đây cũng là bước đầu của hướng "mọi module giao tiếp qua API" nhóm đang cân nhắc — cần chốt với nhóm trước khi làm rộng hơn.
4. **Ràng buộc an toàn khi ghép vào chat**: chunk có `emergency` → không trả hướng dẫn tự chăm sóc, để Safety Gate (M1) quyết định; chunk `foreign_service` chỉ dùng sau khi đã bản địa hóa; chunk `stale_source` không được dùng.
5. **Đánh giá (M-03, M-04)**: bộ 50–100 câu hỏi tiếng Việt có đáp án là `chunkId`; đo Hit@5 và MRR; so phương án chỉ-từ-khóa vs lai.

### Cần chốt
- [ ] RAG chạy trong API Node hay tách dịch vụ Python (FastAPI)?
- [ ] Dùng `pgvector` (cần image Postgres có extension) hay chỉ mục ngoài?
- [ ] Ai duyệt chunk để bật `rag_eligible` cho demo, và phạm vi nguồn NHS/WHO `internal_review_only` được hiển thị thế nào?

---

## Việc lặt vặt

- [ ] Workflow `Issue flow` khi tạo #79: **nhánh đã được tạo** nhưng job `issue-branch` báo **failure** (run `42785673430`) — nhiều khả năng ở bước comment. Xem log: `gh run view 42785673430 --log-failed`.
- [ ] Commit các tài liệu dữ liệu đang untracked (`docs/DATA_STATUS.md`, `PROJECT_DATA_PIPELINE_HANDOFF.md`, `TRANSLATION_MODULE_DESIGN.md`, …) và `tools/translate/` lên nhánh issue phù hợp sau khi sửa các chỗ mâu thuẫn ở trên.
- [ ] Thêm `tools/translate` vào job `data-tools` trong CI (chỉ chạy test không tải mô hình).
