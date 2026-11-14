# Crawler HTML theo nguồn

Chạy từ thư mục gốc repo trong Miniconda Prompt hoặc PowerShell đã khởi tạo Conda. Phiên bản đã kiểm thử: Python 3.12.10. Crawler đọc URL đang bật trong một danh sách `docs/urls*.txt` (mặc định `docs/urls.txt`; mỗi danh sách là một **nhóm** có ngân sách riêng), dùng HTTP trực tiếp, lưu raw và cấu trúc riêng. NHS/WHO có adapter và template riêng; không có logic theo bài bệnh trong runner.

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
python -B tools/crawl/run.py --urls docs/urls_lifestyle.txt   # nhóm "urls_lifestyle"
python -B tools/crawl/run.py --budget                         # request/byte đã dùng theo nhóm
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
- `.data/crawl/state.json`, `lock.sqlite`, `events.jsonl`: ngân sách (tổng và `groups.<nhóm>`), khóa và chặn host dùng chung với công cụ duyệt lô cũ. Mỗi dòng `request` trong `events.jsonl` ghi `group` và `group_number`. Không xóa checkpoint để chạy lại.

`review.py` dùng parser lịch sử trong `review_legacy.py` để duyệt ba bản nháp pilot cũ.

## Chuẩn hóa dữ liệu

Chạy từ thư mục gốc repo sau khi crawler đã tạo JSON trích xuất.

```powershell
python -B tools/normalize/run.py
python -B tools/normalize/run.py --status
```

Bộ chuẩn hóa (`normalize-v2`) đọc bản trích xuất **hiện hành** của từng URL đang bật trong các danh sách của corpus (`rag`: `urls.txt` + `urls_lifestyle.txt`; `expert_reference`: `urls_reference.txt`; `--urls a.txt b.txt` ghi đè) qua con trỏ `pages/<sha256(url)>.json`; các phiên bản trích xuất cũ vẫn nằm trong `extracted/` nhưng bị bỏ qua. Đầu ra trong `.data/normalized/`:

- `articles/<id>.json`: bài đã chuẩn hóa, section có `heading_path`, `dropped_sections` (mục điều hướng bị bỏ, có lý do), chunk và `db_row`.
- `chunks.jsonl`: chunk cho RAG — id là uuid5 tất định, `chunk_key`, `article_uuid`, `heading_path`, dòng đầu văn bản là `Tiêu đề › mục › mục con`, cờ `scope_tags` tính trên từng chunk, `stale_source` khi nguồn quá hạn rà soát.
- `db_rows.json`: hàng cho `knowledge.articles` (có `id` uuid5 từ URL cấu hình, `source_url` là URL cuối sau chuyển hướng).
- `summary.json`: thống kê kích thước chunk, cờ, ngôn ngữ, mục/chunk bị bỏ và lý do, lỗi đầu vào.

Quy tắc chunk: không quá `--max-chunk-words` (mặc định 400); chunk dưới `--min-chunk-words` (mặc định 120) được gộp với chunk kề nếu vừa trần, mỗi phần giữ dòng tiêu đề riêng. Thẻ cảnh báo NHS ("Immediate action required: …") luôn thuộc mục cấp cao chứa nó và không bao giờ bị bỏ cùng mục "Find out more". Trang hub (chỉ có danh sách liên kết) không sinh chunk. `--as-of YYYY-MM-DD` cố định ngày dùng cho `stale_source`. Chạy hai lần trên cùng dữ liệu cho cùng byte. Mọi chunk vẫn `DRAFT`, `rag_eligible=false`.

### Rà soát thẻ bằng LLM (ngoại tuyến, tùy chọn)

Luật từ khóa gắn thẻ trước. Hai script riêng gọi một endpoint tương thích OpenAI **do bạn tự cấu hình** — không có giá trị mặc định nào. Cần đặt `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` (khóa của bạn, không bao giờ commit), tùy chọn `LLM_REASONING` (gửi làm `reasoning_effort`). Ví dụ (giá trị giả):

```powershell
$env:LLM_BASE_URL = "http://localhost:8000/v1"
$env:LLM_MODEL = "your-model-name"
$env:LLM_API_KEY = "your-key"
```

Chạy:

```powershell
python -B tools/normalize/review_interactions.py   # câu "chất + thuốc" → có phải tương tác không
python -B tools/normalize/run.py                   # cập nhật rule_tags trước khi rà chunk
python -B tools/normalize/review_chunks.py         # mọi chunk của hai corpus: thẻ + cờ chất lượng
python -B tools/normalize/run.py; python -B tools/normalize/run.py --corpus expert_reference
```

- Gửi ít request nhưng mỗi request nhiều mục (`--batch`, mặc định 80 câu / 20 chunk) để hợp với endpoint giới hạn tốc độ. Lưu sau mỗi lô; gặp 401/402/403/429 hay lỗi lặp lại thì lưu và dừng (mã thoát 3) — chạy lại sẽ tiếp tục. `--dry-run` chỉ đếm việc. Kèm mỗi request là một ghi chú công việc ngắn (bối cảnh dự án, định nghĩa thẻ).
- Kết quả cache trong `.data/review/interaction_verdicts.json` (theo id câu) và `chunk_verdicts.json` (theo `text_sha256` của chunk; chữ đổi thì rà lại). Normalizer chỉ **đọc** hai file này như dữ liệu nên đầu ra vẫn tất định; không import mã LLM.
- **Giải quyết xung đột** (`normalizer.tags.resolve` / `resolve_tags`): người duyệt thêm `"human": {...}` thì luôn thắng; không có đánh giá LLM thì theo luật; LLM đồng ý với luật thì giữ; bất đồng thì lượt *resolve* (LLM thứ hai thấy cả hai ý kiến) quyết định; còn bất đồng chưa giải quyết thì **giữ thẻ** (an toàn) và ghi vào `needs_review`.
- Chunk có thêm `rule_tags` (thẻ của luật), `tag_review` (`by`, `conflicts`, `needs_review`), `review_flags` (`ui_leftover`, `not_standalone`, `out_of_scope`, `unsafe_or_wrong`), `interaction_terms` (đã xác nhận), `interaction_mentions` (chỉ nhắc), `interaction_evidence` (câu, ai quyết định). `summary.json` có mục `interaction_review` và `tag_review`.
- Luật tương tác theo **câu**: cần chất + thuốc (trong câu, tiêu đề mục hoặc trang thuốc) + cụm từ liên kết ("with", "at the same time", "stop … working", "tránh", "cách xa"…). Người kê đơn/tư vấn (GP, dược sĩ) không phải là thuốc: "GP có thể kê canxi" là thông tin dinh dưỡng, không phải tương tác.

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
   - **Template theo loại trang:** khóa tùy chọn `paths` (tiền tố đường dẫn của URL cuối, vd. `["/live-well/"]`). Template có `paths` khớp thắng template không khai `paths`; sau khi lọc vẫn phải đúng một template, nếu không báo `AMBIGUOUS_TEMPLATE`/`UNSUPPORTED_TEMPLATE`. NHS hiện có `nhs-main-v1` (mặc định) và `nhs-livewell-v1` (`/live-well/`).
   - **Trang mục lục:** khóa `paths_exact` (đường dẫn đầy đủ, bỏ qua dấu `/` cuối) thắng cả `paths`. Dùng khi trang con có đường dẫn bắt đầu bằng đường dẫn của mục lục, để template mục lục không áp lên trang con. WHO: `who-hub-vn-topics-v1` (`/vietnam/vi/health-topics`), `who-hub-factsheets-v1` (`/news-room/fact-sheets`); trang con dùng `who-topic-v1` và `who-factsheet-v1` (`/news-room/fact-sheets/detail/`).
   - `drop_signpost_sections` (`{"scope": "section", "keep": [...]}`): bỏ các con trực tiếp của một mục chỉ trỏ sang trang khác — mở đầu bằng heading, mọi phần còn lại là đoạn/danh sách bắt đầu bằng link, hoặc đoạn mô tả không link nằm giữa tiêu đề phụ (h3–h6) và link đó. Phần tử khớp `keep` (vd. `.nhsuk-inset-text`) luôn giữ.
   - `drop_link_only_paragraphs: true`: bỏ đoạn mà toàn bộ chữ là một link duy nhất ("Find out more about…"). Đoạn có chữ thường + link vẫn giữ.
   - Trước khi crawl loại trang mới: tải một trang mẫu, xem DOM, chỉnh template, in phần chữ bị bỏ trên mọi trang đã lưu, rồi mới tải cả lô.
3. Nếu có metadata/cấu trúc đặc biệt, thêm lớp trong `crawler/adapters/`, đăng ký trong `adapters/__init__.py`. Adapter chỉ nhận HTML; không gọi mạng/lưu file.
4. Thêm fixture synthetic và chạy kiểm thử. Selector phải áp dụng cho mẫu trang, không dựa vào tên bệnh/slug. Không khớp hoặc khớp nhiều template thì báo lỗi; không fallback toàn body.

Mặc định giữ heading, cảnh báo, aside, Q&A, nhãn ngoài p/li, danh sách lồng và bảng. Chỉ bỏ UI theo template. Coverage xác nhận văn bản trong root đã chọn được giữ; nó không tự chứng minh selector chọn đúng bài. Các block vẫn nguyên văn; chưa có phân loại y khoa hoặc chunking.

## Giới hạn vận hành

Một request mỗi lần, tối thiểu 3 giây, timeout 20 giây, 2 MiB/phản hồi. **Mỗi nhóm** (một danh sách `docs/urls*.txt`, tên nhóm = tên file, đổi bằng `--group`) tích lũy tối đa 200 request / 150 MiB và tối đa 200 URL đang bật. Dùng chung cho mọi nhóm: thư mục crawl+collection+review tối đa 250 MiB; mỗi lượt tối đa 20 request / 240 giây; 3 giây/request. Lịch sử request trước khi có nhóm đã được chia một lần bằng `run.py --allocate-history` (URL thuộc danh sách nào tính cho nhóm đó, `robots.txt` tính cho trang ngay sau, đích chuyển hướng tính cho request ngay trước; byte chia theo tỉ lệ request; bản cũ lưu ở `.data/crawl/state.before-groups.json`). Checkpoint cũ chưa chia nhóm thì crawler từ chối tải. Robots và redirect được tính vào ngân sách; tối đa hai redirect HTTPS cùng host và kiểm tra robots trước đích. Retry lỗi mạng/500/502/504 tối đa hai lần, có checkpoint. 401/403/429/503 chặn host qua lần chạy sau. Quy tắc robots chưa hỗ trợ được báo lỗi bảo thủ.

Chế độ thu thập được cấu hình theo nguồn trong `source_policies.json`. Với nguồn metadata-only, `--inspect-metadata` tải HTML trong RAM và chỉ lưu metadata. Mọi kết quả ở trạng thái `DRAFT` và có `rag_eligible=false`.

## Kiểm tra

```powershell
python -B -m unittest discover -s tools/crawl -p "test_*.py"
python -B tools/normalize/test_normalize.py
```

Bộ thử crawler dùng HTML tổng hợp và mạng giả. Bộ thử chuẩn hóa bỏ qua các bài tích hợp khi chưa có bộ dữ liệu thật.
