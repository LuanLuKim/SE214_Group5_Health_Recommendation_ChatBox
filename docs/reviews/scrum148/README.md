# SCRUM-148 — Bàn giao bộ 79 chunk cấp cứu

Refs #130.

Người dùng đã đọc và xác nhận toàn bộ 79 chunk của bản đã sửa trong cuộc hội thoại, gồm 78 chunk dịch và 1 chunk gốc tiếng Việt. Kết quả sau sửa: **79 APPROVED**. Đã áp dụng 27 câu sửa bắt buộc thuộc 18 chunk; 16 góp ý tùy chọn chưa được áp dụng.

## Tệp bàn giao

| Tệp | Nội dung |
| --- | --- |
| [Excel đã duyệt](duyet_79_chunk_cap_cuu_da_duyet.xlsx) | 79 quyết định tại sheet `Chunk`, cột L:M; nội dung đã sửa tại `Đối chiếu từng dòng`, cột E; nhật ký trước/sau trong cùng workbook. |
| [Markdown đã duyệt](duyet_79_chunk_cap_cuu_da_duyet.md) | Bản Anh–Việt sau khi áp dụng 27 câu sửa. |
| [Bảng quyết định CSV](review_decisions.csv) | Mỗi dòng là `chunk_id`, `APPROVED`, ghi chú, thông tin xác nhận và mã băm văn bản được duyệt. |
| [Bảng quyết định JSON](review_decisions.json) | Cùng 79 quyết định, kèm quy ước mã băm và SHA256 của hai tài liệu đã duyệt. |
| [Nhật ký sửa CSV](nhat_ky_sua.csv) | 27 dòng trước/sau sửa, lý do và nguồn kiểm chứng. |
| [Dữ liệu bản sửa](corrections_applied.json) | Các cặp `chunk_id`/số dòng cùng câu gốc và câu sửa để M7 đối chiếu khi cập nhật corpus. |
| [Biên bản xác nhận](approval_record.json) | Lời xác nhận, thời điểm ghi nhận và dấu vết phiên bản tài liệu. |

Các trường trong biên bản phản ánh thời điểm xác nhận qua hội thoại. Quyết định áp dụng cho **văn bản sau sửa**; không áp dụng nguyên trạng cho 18 chunk còn lỗi trong corpus cũ. Mã băm đầu vào trong biên bản là dấu vết các tài liệu trước bước ghi nhận xác nhận; hai tài liệu `_da_duyet` là bản bàn giao hiện tại.

## M7 cần thực hiện

1. Đối chiếu `chunk_id`, câu gốc và số dòng với `translated/chunks_vi.jsonl`. Áp dụng đúng 27 câu sửa; nếu corpus đã đổi hoặc câu gốc không khớp, dừng riêng chunk đó để đối chiếu phiên bản.
2. Kiểm tra văn bản từng chunk khớp `corrected_text_vi_sha256` trong bảng quyết định: SHA256 của UTF-8 các dòng Việt nối bằng LF theo thứ tự, không thêm LF cuối. Kiểm tra lại metadata và quality gate bằng pipeline của M7.
3. Ánh xạ người duyệt vào tài khoản dự án khi nạp quyết định vào `knowledge.review_events` theo #50. Nhãn người xác nhận trong gói này ghi nhận nguồn xác nhận qua hội thoại; không phải khóa ngoại tài khoản DB.
4. Chỉ đưa đúng bản nội dung đã được duyệt vào quy trình xét điều kiện RAG; cần kiểm tra các điều kiện còn lại của hệ thống trước khi bật `rag_eligible`.
5. Cung cấp `review_queue.md`, `chunks_vi.jsonl`, `quality_report.json` và thông tin phiên bản của `data_who_translated.zip` để M1 duyệt phần còn lại của hàng chờ 125 chunk và lấy mẫu 10% `auto_verified`, đối chiếu ID để tránh duyệt trùng.

## Kiểm tra đã thực hiện

- Đủ 79 `chunk_id` duy nhất và 1.478 dòng nội dung trong cả Markdown và Excel.
- Đủ 27 câu sửa thuộc 18 chunk; nội dung Anh và các dòng Việt khác giữ nguyên.
- Đủ 79 quyết định `APPROVED` nhất quán giữa Excel, CSV và JSON.
- SHA256 của hai tài liệu bàn giao khớp biên bản; cấu trúc ZIP của XLSX hợp lệ.

Gói này hoàn tất phần duyệt của bộ 79 chunk đã cung cấp. Chưa cập nhật corpus hoặc DB, chưa bật RAG và chưa hoàn tất toàn bộ SCRUM-148. Vì vậy PR chỉ tham chiếu `Refs #130`.

## Mẫu ngẫu nhiên 82 chunk (`mau_82/`)

Mẫu 10% gồm 82 trong số 822 chunk `auto_verified` nằm ngoài hàng chờ (NHS 34, WHO 48, seed 20261116), lấy từ bản dịch ngày 15/11 (vn-5, prompt fusion-1). Quỳnh đối chiếu từng dòng với bản gốc.

| Tệp | Nội dung |
| --- | --- |
| [Excel đã duyệt](mau_82/duyet_mau_82_chunk.xlsx) | Bản Quỳnh trả về: sheet `Duyệt chunk` có quyết định từng chunk, sheet `Đối chiếu từng dòng` có loại lỗi, câu đề xuất và nguồn kiểm tra. |
| [Quyết định JSON](mau_82/review_decisions.json) | 82 quyết định (59 APPROVED, 23 RETURNED), 43 dòng có ghi chú và SHA256 của Excel. |
| [Câu sửa JSON](mau_82/corrections.json) | 40 câu Quỳnh đề xuất, gồm `chunk_id`, số dòng, câu gốc, câu bị trả lại và câu sửa. M7 nạp tệp này qua `paths.human_corrections`. |

Cả 23 chunk bị trả lại đều đã qua cổng cũ. Từ hai đợt duyệt, M7 rút ra các luật kiểm nghĩa của `localisation.json` (vn-6) và lượt LLM đọc lại nghĩa (SCRUM-155 / #145).

## Điều chỉnh từ chỉ độ tuổi (`dieu_chinh_do_tuoi.json`)

Ngày 18/11/2026, LuanLu quyết định:
- Không dùng "nhũ nhi".
- "baby/babies/infant" dịch theo ngữ cảnh: "trẻ dưới 1 tuổi" khi độ tuổi quyết định lời khuyên, "em bé"/"trẻ nhỏ" trong câu thông thường.
- "newborn" dịch là "trẻ dưới 28 ngày tuổi".
- "a high temperature" dịch là "sốt".

Tệp này sửa 12 câu đã duyệt của hai bộ trên cho khớp quyết định đó. Mỗi câu ghi lý do; chỉ đổi từ chỉ độ tuổi hoặc sốt, phần còn lại giữ nguyên câu người duyệt đã chốt. Tệp nạp sau cùng nên thay câu cùng dòng. Hai tệp sửa gốc không bị sửa.
