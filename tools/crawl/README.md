# Crawler HTML theo nguồn

Chạy từ thư mục gốc repo trong Miniconda Prompt hoặc PowerShell đã khởi tạo Conda. Phiên bản đã kiểm thử: Python 3.12.10. Crawler đọc URL đang bật trong `docs/urls.txt`, dùng HTTP trực tiếp, lưu raw và cấu trúc riêng. NHS/WHO có adapter và template riêng; không có logic theo bài bệnh trong runner.

## Cài và chạy

```powershell
conda env create -f tools/crawl/environment.yml
conda activate se214-crawler
python -m pip install -r tools/crawl/requirements.txt
python -B tools/crawl/run.py
python -B tools/crawl/run.py --source nhs --refresh
python -B tools/crawl/run.py --reextract
python -B tools/crawl/run.py --source who --inspect-metadata
python -B tools/crawl/run.py --status
```

Chạy thường dùng lại raw đã xác minh hash, tự trích lại khi adapter/cấu hình đổi. `--refresh` tải phiên bản mới; lỗi không thay bản hợp lệ trước. `--reextract` chỉ dùng raw, thiếu raw báo lỗi; không dùng cùng refresh. `--source` lọc URL đã chọn, không tìm thêm bài. Mã thoát: 0 thành công/skip theo cấu hình, 1 có lỗi hoặc hết giới hạn, 2 cấu hình/khởi động lỗi.

Môi trường được Conda quản lý ngoài repo; `environment.yml` cố định Python 3.12.10, còn `requirements.txt` cố định các thư viện Python. Sau này mở terminal mới, chạy `conda activate se214-crawler` trước khi dùng crawler.

## Chạy nền

```powershell
& tools/crawl/start-background.ps1 -Refresh -InspectMetadata
```

Launcher dùng Python của môi trường đang activate; có thể truyền `-Python "C:/duong-dan-env/python.exe"` nếu cần chọn rõ interpreter. Lệnh trả PID, run ID và đường dẫn receipt ngay; tiến trình chạy cửa sổ ẩn, stdout/stderr vào `.data/collection/runs/<run-id>.*.log`. Receipt đúng run ID có `state=FINISHED`, `exit_code`, counts và từng URL khi xong; `RUNNING` không có nghĩa thành công. Nếu process đã mất nhưng receipt vẫn RUNNING hoặc chưa có receipt, kiểm tra stderr: có thể bị ngắt/không khởi động được. Không dùng receipt của lần cũ để kết luận lần mới. Launcher không tạo lịch kiểm tra LLM định kỳ; người dùng/agent đọc receipt khi job hoàn tất.

## Đầu ra

- `.data/collection/raw/<sha256>.html`: byte gốc theo hash.
- `fetches/`: metadata từng lần tải, URL gốc/cuối, redirect, thời điểm UTC và quyền sử dụng.
- `extracted/`: phiên bản cấu trúc với adapter/template/config version, title, metadata nguồn và blocks có DOM path, link, list depth, vị trí bảng.
- `pages/`: con trỏ cache theo hash toàn URL (query được giữ); `runs/`: log/receipt từng lượt; `latest.json`: tổng kết gần nhất.
- `.data/crawl/state.json`, `lock.sqlite`, `events.jsonl`: ngân sách, khóa và chặn host dùng chung với công cụ duyệt lô cũ. Không xóa checkpoint để chạy lại.

`review.py` dùng parser lịch sử trong `review_legacy.py` để duyệt ba bản nháp pilot cũ.

## Chuẩn hóa dữ liệu

Chạy từ thư mục gốc repo sau khi crawler đã tạo JSON trích xuất.

```powershell
python -B tools/normalize/run.py
python -B tools/normalize/run.py --status
```

Bộ chuẩn hóa (`normalize-v2`) đọc bản trích xuất **hiện hành** của từng URL đang bật trong `urls.txt` qua con trỏ `pages/<sha256(url)>.json`; các phiên bản trích xuất cũ vẫn nằm trong `extracted/` nhưng bị bỏ qua. Đầu ra trong `.data/normalized/`:

- `articles/<id>.json`: bài đã chuẩn hóa, section có `heading_path`, `dropped_sections` (mục điều hướng bị bỏ, có lý do), chunk và `db_row`.
- `chunks.jsonl`: chunk cho RAG — id là uuid5 tất định, `chunk_key`, `article_uuid`, `heading_path`, dòng đầu văn bản là `Tiêu đề › mục › mục con`, cờ `scope_tags` tính trên từng chunk, `stale_source` khi nguồn quá hạn rà soát.
- `db_rows.json`: hàng cho `knowledge.articles` (có `id` uuid5 từ URL cấu hình, `source_url` là URL cuối sau chuyển hướng).
- `summary.json`: thống kê kích thước chunk, cờ, ngôn ngữ, mục/chunk bị bỏ và lý do, lỗi đầu vào.

Quy tắc chunk: không quá `--max-chunk-words` (mặc định 400); chunk dưới `--min-chunk-words` (mặc định 120) được gộp với chunk kề nếu vừa trần, mỗi phần giữ dòng tiêu đề riêng. Thẻ cảnh báo NHS ("Immediate action required: …") luôn thuộc mục cấp cao chứa nó và không bao giờ bị bỏ cùng mục "Find out more". Trang hub (chỉ có danh sách liên kết) không sinh chunk. `--as-of YYYY-MM-DD` cố định ngày dùng cho `stale_source`. Chạy hai lần trên cùng dữ liệu cho cùng byte. Mọi chunk vẫn `DRAFT`, `rag_eligible=false`.

### Thử với JSON mẫu

Trên bản clone mới, chạy các lệnh sau để thử ngoại tuyến với [mẫu tổng hợp](../normalize/examples/extracted.example.json).

```powershell
New-Item -ItemType Directory -Force .data/collection/extracted | Out-Null
Copy-Item tools/normalize/examples/extracted.example.json .data/collection/extracted/extracted.example.json
python -B tools/normalize/run.py --all-extracted
python -B tools/normalize/run.py --status
```

Dữ liệu thật nằm trong `.data/` và được Git bỏ qua. Khi nhận được ZIP dữ liệu từ Google Drive của nhóm, giải nén vào thư mục gốc của bản clone mới để giữ đúng cấu trúc `.data/`.

## Thêm nguồn hoặc mẫu trang

1. Khai báo host và mode trong `source_policies.json`; nguồn chưa rõ quyền dùng để metadata-only.
2. Thêm nguồn/template vào `templates.json`: `adapter`, `hosts`, `templates` với `id`, CSS selector `root`, `title`, `remove`. Title được chọn độc lập với root. Dùng adapter `generic` cho mẫu HTML thông thường.
3. Nếu có metadata/cấu trúc đặc biệt, thêm lớp trong `crawler/adapters/`, đăng ký trong `adapters/__init__.py`. Adapter chỉ nhận HTML; không gọi mạng/lưu file.
4. Thêm fixture synthetic và chạy kiểm thử. Selector phải áp dụng cho mẫu trang, không dựa vào tên bệnh/slug. Không khớp hoặc khớp nhiều template thì báo lỗi; không fallback toàn body.

Mặc định giữ heading, cảnh báo, aside, Q&A, nhãn ngoài p/li, danh sách lồng và bảng. Chỉ bỏ UI theo template. Coverage xác nhận văn bản trong root đã chọn được giữ; nó không tự chứng minh selector chọn đúng bài. Các block vẫn nguyên văn; chưa có phân loại y khoa hoặc chunking.

## Giới hạn vận hành

Một request mỗi lần, tối thiểu 3 giây, timeout 20 giây, 2 MiB/phản hồi. Lô tích lũy tối đa 200 request / 150 MiB; thư mục crawl+collection+review tối đa 250 MiB. Mỗi lượt tối đa 20 request / 240 giây; danh sách URL tối đa 80 dòng đang bật. Robots và redirect được tính vào ngân sách; tối đa hai redirect HTTPS cùng host và kiểm tra robots trước đích. Retry lỗi mạng/500/502/504 tối đa hai lần, có checkpoint. 401/403/429/503 chặn host qua lần chạy sau. Quy tắc robots chưa hỗ trợ được báo lỗi bảo thủ.

Chế độ thu thập được cấu hình theo nguồn trong `source_policies.json`. Với nguồn metadata-only, `--inspect-metadata` tải HTML trong RAM và chỉ lưu metadata. Mọi kết quả ở trạng thái `DRAFT` và có `rag_eligible=false`.

## Kiểm tra

```powershell
python -B -m unittest discover -s tools/crawl -p "test_*.py"
python -B tools/normalize/test_normalize.py
```

Bộ thử crawler dùng HTML tổng hợp và mạng giả. Bộ thử chuẩn hóa bỏ qua các bài tích hợp khi chưa có bộ dữ liệu thật.
