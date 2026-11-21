# tools/translate — dịch chunk y tế Anh → Việt

Công cụ ngoại tuyến, chạy bằng CLI, đọc `.data/normalized/chunks.jsonl` và ghi bản tiếng Việt vào `.data/translated/`. Không import mã backend/frontend. Thiết kế đầy đủ: [docs/TRANSLATION_MODULE_DESIGN.md](../../docs/TRANSLATION_MODULE_DESIGN.md).

## Cách hoạt động

```
chunk tiếng Anh
  → tách từng dòng, để riêng ký hiệu gạch đầu dòng           (translator/segments.py)
  → đổi dịch vụ của Anh sang tiếng Anh trung tính             (localisation.json)
  → 3 model dịch từng dòng, mỗi model một bản nháp            (translator/engines.py, có cache)
  → LLM hợp nhất các bản nháp thành dòng cuối (tùy chọn)      (translator/refine.py)
  → LLM đọc lại như người duyệt, liệt kê dòng đổi nghĩa;
    sửa một lần, đọc lại một lần                              (translator/verify.py)
  → câu người duyệt đã sửa thay cho câu máy                   (config.json: paths.human_corrections)
  → cổng kiểm định từng dòng; dòng lỗi lấy bản đồng thuận     (translator/quality.py, consensus.py)
  → chunks_vi.jsonl, db_rows_vi.json, review_queue.md …
```

Có hai chế độ, chọn bằng `--mode` (mặc định lấy `llm.mode` trong `config.json`, hiện là `direct`):

| Chế độ | Ai dịch | Khi nào dùng |
| --- | --- | --- |
| `direct` | LLM dịch trọn từng chunk từ bản tiếng Anh, không dùng model dịch miễn phí, không nạp GPU | Mặc định từ lần chạy 4. Lần duyệt mẫu lần chạy 3 không đạt (19/40 chunk bị trả lại, ngưỡng 4/40), nên chuyển sang chế độ này theo chính sách ghi trong file duyệt SCRUM-148 |
| `fusion` | 3 model dịch nháp, LLM biên tập bản nháp | Lần chạy 1–3; dùng khi không muốn gửi toàn bộ văn bản cho LLM |

Ở cả hai chế độ, các lớp kiểm vẫn giữ nguyên: cổng luật cố định, LLM đọc lại nghĩa (sửa một lần rồi đọc lại), câu người duyệt đã sửa luôn thay cho câu máy. Chunk không qua được thì gắn cờ chờ người duyệt, không tự đạt. Ở `direct` không có bản đồng thuận để thay dòng lỗi, nên dòng lỗi giữ nguyên và chunk chuyển sang người duyệt. Mọi tên thuốc tìm thấy trong nguồn đều phải giữ (không có bản nháp để bỏ phiếu). Cache LLM của `direct` nằm riêng ở `cache/llm_direct.jsonl`. Chỉ văn bản công khai của WHO và NHS được gửi cho LLM.

- **Dịch theo dòng.** Model dịch câu sẽ cắt cụt khi nhận cả chunk 240 từ. Bản tiếng Việt luôn có đúng số dòng của bản gốc, nhờ đó kiểm được từng dòng.
- **Ba bản nháp.** `nllb` (NLLB-200), `vinai` (VinAI Translate v2), `medcrab` (MedCrab-1.5B). Model nào thêm hoặc bớt nội dung sẽ lệch khỏi hai model còn lại.
- **LLM chỉ biên tập, không dịch một mình.** LLM nhận dòng gốc cùng các bản nháp và phải trả đúng một dòng cho mỗi dòng. Dòng không qua cổng được gửi lại một lần kèm lỗi; nếu vẫn lỗi thì dùng bản đồng thuận của ba model.
- **Không có LLM vẫn chạy được:** `--refiner consensus` chọn, cho từng dòng, bản nháp gần hai bản còn lại nhất (chrF).
- **Đọc lại nghĩa (`llm.verify` trong `config.json`, `--no-verify` để bỏ qua).** Ở hai đợt duyệt SCRUM-148, mọi chunk bị trả lại đều đã qua cổng. Vì vậy LLM được hỏi thêm một lần với vai người duyệt: dòng nào đổi nghĩa y khoa (phủ định, phạm vi, mức chắc chắn, thời điểm, đường dùng, liều, thuật ngữ, thêm/bớt ý, bối cảnh tại Anh). Dòng bị nêu được gửi lại người biên tập một lần rồi đọc lại. Lỗi còn lại gắn vào chunk dưới dạng `meaning_check`, chunk chuyển sang `flagged_for_review`.
- **Câu người duyệt đã sửa** (`paths.human_corrections`) thay cho câu máy khi dòng tiếng Anh còn khớp. Tệp nạp sau thay tệp nạp trước. `candidates.jsonl` vẫn giữ câu máy ở trường `machine` để đo lần chạy. `--no-corrections` bỏ qua các tệp này.

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
python -B tools/translate/run.py --mode fusion --refiner consensus   # không dùng LLM, chỉ 3 model
python -B tools/translate/run.py --mode fusion            # 3 model dịch nháp + LLM biên tập
python -B tools/translate/run.py                          # toàn bộ theo llm.mode (fusion); tự chạy tiếp từ cache
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

Prompt của LLM có thêm chính sách duyệt (`reviewer_policy.md`) và mọi dòng người duyệt đã sửa, kèm loại lỗi và lý do, lấy từ các tệp `paths.human_corrections` (config `llm.reviewer_notes`). Bản sửa trái với quyết định đã chốt (chữ "nhũ nhi") bị bỏ. `--no-notes` tắt phần này. `--notes-holdout` dùng khi đo trên các chunk đã duyệt: bỏ ghi chú của chính các chunk đang dịch, để điểm cho thấy model áp quy tắc vào câu nó chưa thấy bản sửa.

## Cấu hình (không sửa mã)

| Tệp | Nội dung |
| --- | --- |
| `config.json` | Model của từng engine, tham số sinh, cỡ lô, ngưỡng của cổng kiểm định, đường dẫn, số chunk mỗi request LLM |
| `reviewer_policy.md` | Chính sách duyệt SCRUM-148: ba mức lỗi, các quyết định đã chốt về tuổi, sốt, dinh dưỡng; đưa vào prompt LLM cùng ghi chú của người duyệt |
| `localisation.json` | Luật Anh → Việt Nam: viết lại nguồn (`999`, `111`, `GP`, `A&E`), từ cấm và từ cần rà trong bản dịch, bảng dịch vụ / độ tuổi / thuật ngữ đưa cho LLM; luật kiểm nghĩa: đơn vị liều (`units`), chiều của giới hạn (`direction_checks`), phủ định (`negation`), cách viết người duyệt đã trả lại (`meaning_checks`) |

Tên thuốc không nằm trong mã: đọc từ trang chỉ mục thuốc đã crawl (cùng nguồn với normalizer). Tên thuốc mà đa số model giữ nguyên thì bản cuối cũng phải giữ; tên nhóm thuốc (antibiotics → thuốc kháng sinh) được dịch.

## Đầu ra (`.data/translated/`)

| Tệp | Nội dung |
| --- | --- |
| `chunks_vi.jsonl` | Chunk song ngữ: `text_en`, `text_vi`, tiêu đề, `translation_metadata` (phương pháp, model, trạng thái cổng), `scope_tags` sau bản địa hóa và `scope_tags_source` |
| `db_rows_vi.json` | Hàng bài viết tiếng Việt, ghép lại từng dòng từ các chunk đã dịch |
| `candidates.jsonl` | Từng dòng: bản gốc, bản nháp của mỗi model, bản đồng thuận, câu máy (`machine`, trước khi áp câu người duyệt), bản cuối, phương pháp (`llm`, `consensus`, `consensus_fallback`, `human`, `copy`) |
| `quality_report.json` | Kết quả từng phép kiểm của từng chunk, chỉ rõ dòng có vấn đề |
| `review_queue.md` | Bảng Anh – Việt cạnh nhau của các chunk cần người rà (chunk không qua cổng và chunk cấp cứu đứng trước) |
| `summary.json` | Thống kê lần chạy, so sánh ba model với bản cuối |
| `cache/` | Bản dịch đã có của từng model và của LLM (xóa để dịch lại từ đầu) |

Mọi chunk vẫn `DRAFT`, `rag_eligible=false`. Chunk có thẻ `emergency` luôn được đánh dấu `needs_human_review` dù qua cổng.

## Cổng kiểm định

Chặn (chunk `failed_quality_gate`): rỗng, lệch số dòng hoặc số gạch đầu dòng, tỷ lệ độ dài, **thiếu một con số của dòng gốc** (liều, tuổi, nhiệt độ, thời gian), **mất đơn vị của liều** (`5 mg` → `5 g`), còn `999`/`111`/`GP`/`A&E`, còn token của model, dòng chưa dịch, lặp vô hạn.

Nghi đổi nghĩa (qua cổng nhưng không bao giờ `auto_verified`: trạng thái `flagged_for_review`; được gửi vào lượt sửa của LLM, không bị thay bằng bản đồng thuận):
- `direction_kept`: chiều của giới hạn bị đảo (`under 5` → `trên 5`)
- `negation_kept`: dòng gốc có phủ định, bản Việt không có
- `wording`: cách viết người duyệt đã trả lại, ví dụ `sốt cao` cho *a high temperature*, `trẻ sơ sinh` cho *babies*, `nhũ nhi`, `tiêm` cho vắc-xin đường uống, `đau tim` cho *heart attack*
- `meaning_check`: lỗi do LLM đọc lại tìm ra

Cảnh báo (qua cổng nhưng cần người rà): có số không nằm trong bản gốc, còn nhắc `NHS` hay số điện thoại của Anh, tên thuốc quốc tế bị đổi, độ dài một dòng bất thường, dòng cuối không giống bản nháp nào (`low_support` — dấu hiệu thêm nội dung).

So một lần chạy với kết quả duyệt: `python -B tools/translate/review_compare.py docs/reviews/scrum148/mau_82` (xem bên dưới).

## Giấy phép model

NLLB-200 và MedCrab-1.5B: CC BY-NC 4.0 (phi thương mại). VinAI Translate v2: AGPL-3.0. Phù hợp đồ án học thuật; cần xem lại nếu dùng thương mại.
