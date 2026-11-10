# Trạng thái dữ liệu — 10/11/2026

Thay cho bản 03/11 (sau đợt dọn dẹp). Số liệu lấy từ `.data/` sau khi tái trích, crawl bổ sung và chạy `normalize-v2` trên nhánh issue #79.

## Tóm tắt

| Chỉ số | 03/11 → 10/11 (trước sửa) | 10/11 (sau sửa) |
| --- | --- | --- |
| URL đang bật trong `docs/urls.txt` | 51 | 61 (4 trang hub bị tắt, thêm 14 trang con) |
| Bài chuẩn hóa | 51 (42 NHS, 9 WHO) | 61 (52 NHS, 9 WHO) |
| Chunk | 496 | 200 |
| Số từ/chunk (min · trung vị · max) | 9 · 64 · 497 | 26 · 240 · 398 |
| Chunk < 120 từ | ~70% | 13 (6,5%) |
| Chunk vượt trần | 1 bài lệch id giữa hai file | 0 (trần 400 từ, id trùng khớp) |
| Chunk nhắc 999 / A&E / ambulance / cấp cứu mà thiếu cờ `emergency` | 17 | 0 |
| `db_rows.json` có `id` uuid | 0/51 | 61/61 |
| Ngôn ngữ chunk | 456 en · 40 vi | 180 en · 20 vi |
| Trạng thái | DRAFT, `rag_eligible=false` | không đổi |

Ngân sách crawl tích lũy: **118/200 request**, 13 MiB trên đĩa (giới hạn 250 MiB). Tái trích từ raw không cần mạng và cho cùng văn bản.

## Kho dữ liệu

| Đường dẫn | Vai trò |
| --- | --- |
| `.data/collection/raw/` | HTML byte gốc theo SHA-256. |
| `.data/collection/fetches/` | Metadata từng lần tải: URL gốc/cuối, redirect, thời điểm, chính sách nguồn. |
| `.data/collection/pages/` | Con trỏ tới **bản trích xuất hiện hành** của từng URL. Normalizer chỉ đọc qua đây. |
| `.data/collection/extracted/` | Mọi phiên bản trích xuất (112 file cho 65 URL); bản cũ giữ để truy vết, không được đọc. |
| `.data/normalized/` | `articles/`, `chunks.jsonl`, `db_rows.json`, `summary.json` — xem [README crawler](../tools/crawl/README.md#chuẩn-hóa-dữ-liệu). |
| `.data/crawl/` | Ngân sách, host bị chặn (CDC 403), log và khóa. Không xóa. |

Chính sách nguồn (`tools/crawl/source_policies.json`): NHS và WHO đều `collect_text`, `usage_scope = internal_review_only`, `public_release_allowed = false`. CDC bị chặn (metadata-only). Bản 03/11 ghi WHO "metadata-only" là đã cũ.

## Ghi nhận về dữ liệu và cách đã sửa

1. **WHO Q&A mất cấu trúc.** Câu hỏi là nút accordion, không phải heading, nên cả bài sốt xuất huyết thành một khối. Template nay khai báo `headings` để biến nút thành `h2`. Ngày đăng ("2 October 2024 | Questions and answers") được tách thành `source_published_at`.
2. **Rác giao diện lẫn vào nội dung.** Sidebar "Technical links" tiếng Anh và nhãn tiêu đề trùng của WHO; nút "Find a pharmacy" / "Find your nearest A&E", thẻ video của NHS. Đã bỏ bằng selector template. Nhãn ẩn "Information:" được ghép vào câu sau; dòng "Page last reviewed" được bỏ vì đã có trong metadata.
3. **Bốn trang NHS là hub** (stroke, COVID-19, depression in adults, type 2 diabetes) chỉ chứa liên kết sang trang con. Đã tắt chúng và crawl 14 trang con có nội dung. Normalizer còn tự nhận diện hub và không sinh chunk.
4. **9 URL bị chuyển hướng** (vd. `/conditions/headache/` → `/symptoms/headaches/`). `source_url` dùng URL cuối để trích dẫn; uuid vẫn tính từ URL cấu hình để ổn định.
5. **Normalizer đọc trùng phiên bản.** Khi cấu hình template đổi, mỗi URL có thêm một file extracted; bản cũ đọc `extracted/*.json` sẽ nhân đôi bài. Nay đọc qua con trỏ `pages/`.
6. **Chunk quá vụn và mất ngữ cảnh.** Mục "Do", "Don't", "Important" đứng riêng không biết thuộc mục nào. Nay mỗi chunk có `heading_path` và dòng đầu `Tiêu đề › mục › mục con`; mục nhỏ được gộp.
7. **Thẻ cảnh báo có thể bị xóa nhầm.** Ở bản sửa đầu, thẻ "Immediate action required: Call 999" đứng sau danh sách "Find out more" bị lồng vào mục đó và bị bỏ theo. Đã sửa (thẻ cảnh báo luôn thuộc mục cấp cao) và có test riêng.
8. **Cờ sai.** `emergency` chỉ quét 200 ký tự đầu → bỏ sót; `bé` khớp `béo phì`, `thuốc` khớp `hút thuốc lá`. Cờ nay tính trên toàn chunk với ranh giới từ.
9. **Bảng bị làm phẳng.** Bảng cúm/cảm lạnh nay thành dòng `Cold: …; Flu: …`.

## Vấn đề còn mở

- **`stale_source` gắn cho 13 bài (50 chunk)** vì ngày "Next review due" của NHS đã qua, kể cả các trang COVID-19 và trầm cảm vừa tải hôm nay. Đây là ngày rà soát của chính NHS, không phải bản sao cũ. Kế hoạch RAG ghi "chunk `stale_source` không được dùng"; nếu giữ quy tắc này thì các chủ đề trên mất khỏi RAG. Đề xuất: coi là cảnh báo cho người duyệt UC-17, không loại tự động.
- **9 bài WHO không có ngày rà soát** nên không tính được `stale_source`.
- **90% chunk là tiếng Anh**, người dùng hỏi tiếng Việt → cần dịch (`tools/translate`, đang có lỗi ghi trong [M7_DATA_RAG_PLAN.md](M7_DATA_RAG_PLAN.md#p4-dịch-toolstranslate--lỗi-đã-thấy-khi-đọc-code)) hoặc embedding đa ngữ.
- **Cờ `medication` và `paediatric` rộng** (125 và 85 chunk) vì NHS nhắc "medicine", "child" ở nhiều nơi. Cố ý nghiêng về an toàn; người duyệt có thể thu hẹp sau.
- **Tiêu đề trang thuốc NHS dài** ("Ibuprofen for adults (Nurofen) - Other common brands: …") được giữ nguyên văn.
- Chưa có thông tin tương tác thuốc – thức ăn; xem việc crawl bổ sung trang thuốc NHS.

## Kiểm thử

- Crawler: 48 test (`python -B -m unittest discover -s tools/crawl -p "test_*.py"`).
- Normalizer: 22 test (`python -B tools/normalize/test_normalize.py`), hai test chạy trên dữ liệu thật và tự bỏ qua trên bản clone mới.
- Translator: 27 test, vẫn đạt với định dạng chunk mới.
