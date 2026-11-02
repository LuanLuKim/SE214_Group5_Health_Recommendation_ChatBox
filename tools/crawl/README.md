# Thu thập tài liệu y khoa trên máy cá nhân

Chạy từ thư mục gốc repo bằng Python 3.12 trở lên. Chỉ dùng thư viện chuẩn. Mỗi lần chạy xử lý một lượt danh sách rồi tự thoát.

```powershell
python -B tools/crawl/collect.py
python -B tools/crawl/collect.py --status
Get-Content .data/crawl/events.jsonl -Encoding utf8 -Tail 20
```

Trong lúc chạy, thêm `-Wait` vào lệnh đọc log để theo dõi trong PowerShell khác. Có thể dừng bằng Ctrl+C và dùng lại lệnh đầu để tiếp tục. Hai tiến trình không thể đồng thời ghi vào cùng lô. Máy cần thức và có kết nối trong lúc tải.

## Kết quả

- `sources.json` ghi URL, chủ đề, chính sách sử dụng, câu hỏi dự kiến và chế độ từng nguồn. `metadata_only` chỉ lưu thông tin trong manifest. Chế độ `collect_text` chỉ áp dụng cho văn bản nguồn đã chọn. Không lần theo liên kết, chuyển hướng hay tải media.
- `.data/crawl/<id>.json` chứa văn bản nháp, URL, thời điểm tải UTC, hash và trạng thái `DRAFT`. File ghi thành công được bỏ qua ở lần chạy sau. Bản thu được có thể chứa mục dành cho trẻ em, chỉ dẫn dùng thuốc và dịch vụ ở nước ngoài. Bước duyệt sẽ quyết định đoạn nào phù hợp cho người lớn ở Việt Nam.
- `.data/crawl/events.jsonl` là log nối tiếp giữa các lần chạy, có thời gian UTC, từng URL, lỗi và tổng kết. Giờ Việt Nam bằng UTC cộng 7 giờ. Mã thoát 1 báo có nguồn bị chặn, lỗi hoặc đã chạm giới hạn. Các bản tải thành công vẫn dùng được.
- `.data/crawl/state.json` lưu ngân sách, số lần thử và host bị tạm ngừng. HTTP 401/403/429/503 ngừng cả host qua các lần chạy. Kiểm tra nguyên nhân và `retry_after` trước khi quyết định mở lại một host. Giữ checkpoint để giữ ngân sách. File `lock.sqlite` chỉ dùng làm khóa tiến trình.

## Ngân sách cho cả lô

Một request tại một thời điểm, tối thiểu 3 giây giữa hai lần bắt đầu request, timeout I/O 20 giây, mỗi phản hồi tối đa 2 MiB. Tổng cộng tối đa 200 request và 150 MiB cho các lần chạy nối tiếp. Crawler đặt trước 2 MiB cho mỗi request và hoàn lại phần chưa dùng khi tải thành công. Lỗi hoặc ngắt giữa chừng giữ phần đã đặt trước, vì vậy `charged_bytes` là ngân sách đã tính, có thể lớn hơn dữ liệu thật. Có kiểm tra dung lượng thư mục trước mỗi lần tải, với trần 250 MiB. Mỗi URL bài được thử tối đa hai lần, lần thử tiếp theo diễn ra khi chạy lại. Manifest giới hạn 60 bài.

Với CDC, chỉ thu văn bản cơ quan được phép dùng sau khi xét ngoại lệ. Với NHS, bản chụp nội bộ giữ URL, thời điểm lấy và ghi chú giấy phép. Trước khi phát hành hay dịch phải rà soát điều khoản áp dụng cho bản chuyển thể. [CDC](https://www.cdc.gov/other/agencymaterials.html) · [NHS mục 3](https://www.nhs.uk/our-policies/terms-and-conditions/) · [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/)

Kiểm thử bằng fixture ngoại tuyến:

```powershell
python -B -m unittest discover -s tools/crawl -p test_collect.py
```
