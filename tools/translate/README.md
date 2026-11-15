# tools/translate — dịch chunk y tế Anh → Việt

Công cụ ngoại tuyến, chạy bằng CLI, đọc `.data/normalized/chunks.jsonl` và ghi bản tiếng Việt vào `.data/translated/`. Không import mã backend/frontend. Thiết kế đầy đủ: [docs/TRANSLATION_MODULE_DESIGN.md](../../docs/TRANSLATION_MODULE_DESIGN.md).

## Cách hoạt động

```
chunk tiếng Anh
  → tách từng dòng, để riêng ký hiệu gạch đầu dòng           (translator/segments.py)
  → đổi dịch vụ của Anh sang tiếng Anh trung tính             (localisation.json)
  → 3 model dịch từng dòng, mỗi model một bản nháp            (translator/engines.py, có cache)
  → LLM hợp nhất các bản nháp thành dòng cuối (tùy chọn)      (translator/refine.py)
  → cổng kiểm định từng dòng; dòng lỗi lấy bản đồng thuận     (translator/quality.py, consensus.py)
  → chunks_vi.jsonl, db_rows_vi.json, review_queue.md …
```

- **Dịch theo dòng.** Model dịch câu sẽ cắt cụt khi nhận cả chunk 240 từ. Bản tiếng Việt luôn có đúng số dòng của bản gốc, nhờ đó kiểm được từng dòng.
- **Ba bản nháp.** `nllb` (NLLB-200), `vinai` (VinAI Translate v2), `medcrab` (MedCrab-1.5B). Model nào thêm hoặc bớt nội dung sẽ lệch khỏi hai model còn lại.
- **LLM chỉ biên tập, không dịch một mình.** LLM nhận dòng gốc cùng các bản nháp và phải trả đúng một dòng cho mỗi dòng. Dòng không qua cổng được gửi lại một lần kèm lỗi; nếu vẫn lỗi thì dùng bản đồng thuận của ba model.
- **Không có LLM vẫn chạy được:** `--refiner consensus` chọn, cho từng dòng, bản nháp gần hai bản còn lại nhất (chrF).

## Cài đặt

```powershell
conda env create -f tools/translate/environment.yml
conda activate se214-translate
python -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cu124   # máy không có GPU: .../whl/cpu
python -m pip install -r tools/translate/requirements.txt
```

Lần chạy đầu tải ba model về cache của Hugging Face (khoảng 8 GB). GPU 6 GB là đủ: mỗi lúc chỉ nạp một model.

## Chạy

```powershell
python -B tools/translate/run.py --dry-run                # đếm việc, không nạp model
python -B tools/translate/run.py --max-chunks 10          # thử lô nhỏ
python -B tools/translate/run.py --only-tag emergency     # chỉ các chunk cấp cứu
python -B tools/translate/run.py --engines nllb,vinai     # chọn model
python -B tools/translate/run.py --refiner consensus      # không dùng LLM
python -B tools/translate/run.py                          # toàn bộ; tự chạy tiếp từ cache
python -B tools/translate/run.py --status                 # tóm tắt lần chạy gần nhất
python -B tools/translate/test_translate.py               # kiểm thử (không cần model, không cần mạng)
```

Mã thoát: `0` mọi chunk qua cổng, `1` có chunk không qua, `2` lỗi cấu hình, `3` LLM dừng giữa chừng (hết lượt/giới hạn tốc độ) — chạy lại để tiếp tục.

### LLM biên tập (tùy chọn)

Dùng chung quy ước với các script rà soát của `tools/normalize`: một endpoint tương thích OpenAI **do bạn tự cấu hình**, không có giá trị mặc định, khóa không bao giờ ghi vào tệp hay commit.

```powershell
$env:LLM_BASE_URL = "http://localhost:8000/v1"
$env:LLM_MODEL = "your-model-name"
$env:LLM_API_KEY = "your-key"
$env:LLM_REASONING = "low"        # tùy chọn, gửi làm reasoning_effort
```

Có đủ ba biến thì `--refiner auto` (mặc định) dùng LLM; thiếu thì tự chuyển sang đồng thuận. Kết quả LLM được cache theo nội dung gửi đi, nên chạy lại không gọi lại.

## Cấu hình (không sửa mã)

| Tệp | Nội dung |
| --- | --- |
| `config.json` | Model của từng engine, tham số sinh, cỡ lô, ngưỡng của cổng kiểm định, đường dẫn, số chunk mỗi request LLM |
| `localisation.json` | Luật Anh → Việt Nam: viết lại nguồn (`999`, `111`, `GP`, `A&E`), từ cấm và từ cần rà trong bản dịch, bảng dịch vụ / độ tuổi / thuật ngữ đưa cho LLM |

Tên thuốc không nằm trong mã: đọc từ trang chỉ mục thuốc đã crawl (cùng nguồn với normalizer). Tên thuốc mà đa số model giữ nguyên thì bản cuối cũng phải giữ; tên nhóm thuốc (antibiotics → thuốc kháng sinh) được dịch.

## Đầu ra (`.data/translated/`)

| Tệp | Nội dung |
| --- | --- |
| `chunks_vi.jsonl` | Chunk song ngữ: `text_en`, `text_vi`, tiêu đề, `translation_metadata` (phương pháp, model, trạng thái cổng), `scope_tags` sau bản địa hóa và `scope_tags_source` |
| `db_rows_vi.json` | Hàng bài viết tiếng Việt, ghép lại từng dòng từ các chunk đã dịch |
| `candidates.jsonl` | Từng dòng: bản gốc, bản nháp của mỗi model, bản đồng thuận, bản cuối, phương pháp |
| `quality_report.json` | Kết quả từng phép kiểm của từng chunk, chỉ rõ dòng có vấn đề |
| `review_queue.md` | Bảng Anh – Việt cạnh nhau của các chunk cần người rà (chunk không qua cổng và chunk cấp cứu đứng trước) |
| `summary.json` | Thống kê lần chạy, so sánh ba model với bản cuối |
| `cache/` | Bản dịch đã có của từng model và của LLM (xóa để dịch lại từ đầu) |

Mọi chunk vẫn `DRAFT`, `rag_eligible=false`. Chunk có thẻ `emergency` luôn được đánh dấu `needs_human_review` dù qua cổng.

## Cổng kiểm định

Chặn (chunk `failed_quality_gate`): rỗng, lệch số dòng hoặc số gạch đầu dòng, tỷ lệ độ dài, **thiếu một con số của dòng gốc** (liều, tuổi, nhiệt độ, thời gian), còn `999`/`111`/`GP`/`A&E`, còn token của model, dòng chưa dịch, lặp vô hạn.

Cảnh báo (qua cổng nhưng cần người rà): có số không nằm trong bản gốc, còn nhắc `NHS` hay số điện thoại của Anh, tên thuốc quốc tế bị đổi, độ dài một dòng bất thường, dòng cuối không giống bản nháp nào (`low_support` — dấu hiệu thêm nội dung).

## Giấy phép model

NLLB-200 và MedCrab-1.5B: CC BY-NC 4.0 (phi thương mại). VinAI Translate v2: AGPL-3.0. Phù hợp đồ án học thuật; cần xem lại nếu dùng thương mại.
