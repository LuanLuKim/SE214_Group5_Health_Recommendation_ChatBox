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

## Bổ sung 10/11 tối — dinh dưỡng và tài liệu tham khảo thuốc (issue #85)

**Phạm vi.** [PROJECT_CONTEXT.md](requirements/PROJECT_CONTEXT.md) loại trừ "drug lookup": chatbot không trả lời theo tên thuốc ("đang uống thuốc X có được uống rượu không"). Vì vậy dữ liệu chia ba danh sách:

| Danh sách | Nội dung | Chuẩn hóa | RAG |
| --- | --- | --- | --- |
| `docs/urls.txt` | +10 trang Vitamin & khoáng chất NHS (topic `nutrition`) | `.data/normalized/` | Có (vẫn DRAFT) |
| `docs/urls_reference.txt` | 27 trang thuốc NHS: 20 thuốc phổ biến ở VN + trang "Antibiotics › Interactions" | `.data/normalized_reference/` (`--corpus expert_reference`) | **Không bao giờ**: `rag_eligible=false`, `corpus="expert_reference"` |
| `docs/urls_discovery.txt` | Mục lục NHS Medicines A–Z, mục lục vitamin, 6 hub chỉ có link | Không | Không |

Trang thuốc chỉ để chuyên gia nội dung (R-03) đối chiếu khi viết bài **chung** về đồ ăn/đồ uống/thực phẩm bổ sung cần lưu ý khi dùng thuốc. Câu hỏi nêu tên thuốc nên được Safety Gate chuyển sang `ROUTINE_CONSULT` (hỏi dược sĩ/bác sĩ).

**Cách tìm URL (không viết cứng).** `docs/medicine_seeds.txt` liệt kê tên thuốc; `discover.py --seeds` khớp với chữ trên link của mục lục A–Z đã crawl. NHS đang đổi giao diện: 14 thuốc là một trang dài, 6 thuốc (metronidazole, clarithromycin, amoxicillin, ramipril, alendronic acid, ferrous sulfate) là hub chỉ có link → chọn trang con theo chữ trên link của chính hub (`--link-text "Common questions" --link-text "How and when to take"`), không đoán slug.

**Cờ mới theo chunk.**
- `food_interaction` trong `scope_tags` và `interaction_terms` (khóa ổn định: `alcohol`, `grapefruit`, `dairy`, `calcium`, `iron`, `vitamin_k`, `caffeine`, `tyramine`, `potassium`, `liquorice`, `cranberry`, `soya`, `herbal`, `supplements`). Chỉ gắn khi chất nằm cùng đoạn với ngữ cảnh thuốc; với gạch đầu dòng thì cùng dòng hoặc câu dẫn của nó ("Do not take it with:"). Chỉ nhắc chất (vd. "sữa giàu canxi") không tính.
- Ngữ cảnh thuốc gồm từ chung (medicine, pharmacist, thuốc, dược sĩ…) và **300 tên thuốc đọc từ link của mục lục A–Z đã crawl** (`normalizer/vocabulary.py`), không có tên thuốc nào trong code. Trang có topic `medication` thì mọi đoạn đều là ngữ cảnh thuốc.
- `corpus` (`rag` / `expert_reference`) trên bài, chunk và `db_rows`. Loader (#50) phải giữ `expert_reference` ngoài truy xuất.

**Kết quả.** RAG: 71 bài, 236 chunk (trung vị 237 từ, tối đa 393), 7 chunk `food_interaction` (rượu với kháng sinh/thuốc giảm đau/kháng histamin, nam việt quất với warfarin, St John's wort). Tham khảo: 27 bài, 110 chunk, 40 chunk `food_interaction`; kết quả khớp nội dung từng trang (bưởi với statin/amlodipine/sertraline, vitamin K và nam việt quất với warfarin, sữa/canxi với ciprofloxacin, cà phê/canxi/sắt với levothyroxine, trà/sữa với viên sắt, kali với ramipril/losartan, cam thảo với prednisolone). 0 chunk vượt trần ở cả hai.

**Sửa kèm.**
- Các mục `<details>` liền nhau bị lồng vào nhau (trang "Common questions": mỗi câu hỏi thành con của câu trước, chunk tới 523 từ). Nay là các mục ngang hàng.
- Ngân sách chunk trừ đúng độ dài tiêu đề cấp cao (chunk Vitamin D từng 402 từ).
- Hai lỗi này cũng đổi `heading_path`, `text` và id của vài chunk RAG cũ.

**Ngân sách crawl: 168/200 request** (+50: 1 mục lục thuốc, 1 mục lục vitamin, 10 vitamin, 21 hub/trang thuốc, 12 trang con, 5 robots). Còn 32.

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
- Trang thuốc NHS trong `urls.txt` (paracetamol, ibuprofen, antihistamines) đang ở kho RAG từ trước #85 và có liều dùng. Theo quy tắc "không drug lookup", nhóm cần quyết định có chuyển chúng sang `urls_reference.txt` không.
- Từ điển chất (`SUBSTANCES` trong `tags.py`) là danh sách cố định; cần chuyên gia duyệt và bổ sung tên tiếng Việt khi có bản dịch.

## Kiểm thử

- Crawler: 53 test (`python -B -m unittest discover -s tools/crawl -p "test_*.py"`), gồm 5 test `discover.py` trên dữ liệu tổng hợp.
- Normalizer: 28 test (`python -B tools/normalize/test_normalize.py`), hai test chạy trên dữ liệu thật và tự bỏ qua trên bản clone mới.
- Translator: 27 test, vẫn đạt với định dạng chunk mới.
