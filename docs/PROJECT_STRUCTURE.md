# Bố cục dự án

Tài liệu này giúp người mới biết **mỗi thứ nằm ở đâu** và **cần sửa ở đâu**.
Phân công module xem [MODULES.md](MODULES.md), công nghệ xem [TECH_STACK.md](TECH_STACK.md),
cài đặt máy xem [SETUP.md](SETUP.md).

## Đọc nhanh

1. Dự án có **sáu module nghiệp vụ**, mỗi module dùng **cùng một tên thư mục** ở mọi nơi:

   | Mã | Thư mục | Nội dung |
   | --- | --- | --- |
   | M1 | `safety` | Safety Gate, quy tắc cảnh báo |
   | M2 | `knowledge` | Tra cứu kiến thức, soạn và duyệt nội dung |
   | M3 | `health` | Hồ sơ, chỉ số sức khỏe, thống kê |
   | M4 | `chat` | Hội thoại với trợ lý AI |
   | M5 | `accounts` | Tài khoản, đăng nhập, phân quyền |
   | M6 | `lifestyle` | Lộ trình sinh hoạt, nhắc nhở |

2. Bốn nơi chứa mã chạy: `apps/web` (giao diện), `backend` (Java), `services` (Python),
   `tools` (công cụ dữ liệu offline). **Mọi test** nằm ở `tests/`, cây con giống nơi chứa mã.
3. `.data/` chỉ chứa dữ liệu sinh ra khi chạy (Git-ignore). `.env` ở gốc repo chứa bí mật (Git-ignore).
4. Đường dẫn và cấu hình đọc từ `config/paths.yaml` và `.env`, **không viết cứng trong code**.

## Cây thư mục

```text
.
├── apps/web/                         Frontend React + Vite
│   └── src/
│       ├── App.tsx, main.tsx         Điểm vào giao diện
│       ├── components/               Component dùng chung
│       ├── lib/api.ts                Client gọi backend
│       └── modules/<module>/         Page.tsx + README.md của từng module
│
├── packages/contracts/src/           Kiểu JSON dùng chung cho frontend (khớp record Java)
│
├── backend/                          Spring Boot modular monolith
│   ├── pom.xml, Dockerfile
│   └── src/
│       ├── main/                     Mã Java; tên thư mục = tên package
│       │   ├── safety/               M1  (+ api/)
│       │   ├── knowledge/            M2  (+ api/, authoring/, review/)
│       │   ├── health/               M3  (+ api/)
│       │   ├── chat/                 M4
│       │   ├── accounts/             M5  (+ api/, auth/, config/, oauth/, persistence/,
│       │   │                                rbac/, registration/, web/)
│       │   ├── lifestyle/            M6
│       │   ├── shared/               Dùng chung: domain/, web/ (ApiResponse, lỗi, request ID,
│       │   │                                Spring Security)
│       │   ├── integrations/         Client gọi services/ai và services/stats
│       │   └── system/               Entry point, /healthz, danh sách module, OpenAPI, seed
│       └── resources/                application.yml, application-mock.yml, application-postgres.yml
│
├── services/                         Service Python, chỉ backend gọi tới
│   ├── ai/app/                       FastAPI: Safety Gate, chat AI, client LLM, kiểm tra đầu ra
│   └── stats/                        Django: stats_service/ (cấu hình), stats_api/ (thống kê)
│
├── database/
│   ├── migrations/                   Flyway V001__… → V024__…
│   └── seeds/                        Dữ liệu tổng hợp, chạy lặp được
│
├── tools/                            Công cụ offline (M7) và tự động hóa
│   ├── crawl/                        Thu thập trang: run.py, discover.py, review.py, crawler/
│   ├── normalize/                    Chuẩn hóa, chia chunk: run.py, normalizer/
│   ├── translate/                    Dịch EN→VI: run.py, config.json, translator/
│   ├── github/                       Ruleset, đồng bộ issue, cài git hooks
│   ├── project_config.py             Đọc config/paths.yaml và .env
│   └── migrate_data.py               Chuyển dữ liệu cũ sang bố cục .data/ mới
│
├── tests/                            Toàn bộ test
│   ├── apps/web/                     Test frontend
│   ├── backend/java/                 Test Java, cùng package với main (+ audit/, support/)
│   ├── backend/resources/            Tài nguyên chỉ dùng cho test Java
│   ├── services/ai/                  pytest (+ pytest.ini)
│   ├── services/stats/               Test Django
│   ├── tools/                        crawl/, normalize/, translate/, test_project_config.py
│   ├── eval/                         review_compare.py; local/ = runner riêng trên máy (Git-ignore)
│   └── results/                      Báo cáo test (Git-ignore)
│
├── config/paths.yaml                 Đường dẫn dữ liệu và danh sách URL, tính từ gốc repo
├── docs/                             Tài liệu; urls*.txt = danh sách URL cho crawl
│   ├── requirements/                 Đặc tả use case, mapping UC–FR
│   ├── frontend/                     Bản nháp giao diện
│   └── reviews/                      Góp ý review theo task
├── deploy/                           compose.yaml + .env.example chạy từ image ghcr.io
├── .github/                          workflows/ (ci, cd, issue-flow), scripts/, ISSUE_TEMPLATE/
├── .githooks/                        commit-msg, pre-commit, pre-push
├── compose.yaml                      Chạy local: db + backend + services
├── .env.example                      Mẫu cho .env duy nhất ở gốc repo
├── package.json                      Workspace npm: apps/*, packages/*
└── pyrightconfig.json                Đường dẫn import Python cho Pylance
```

Dữ liệu sinh ra khi chạy công cụ:

```text
.data/                                Git-ignore, không đặt mã ở đây
├── crawl/collection/                 HTML, extraction, con trỏ trang, receipt
├── crawl/checkpoint/                 Ngân sách, state, khóa SQLite, event log
├── normalize/rag/                    Corpus cho chatbot
├── normalize/expert_reference/       Corpus tham khảo chuyên gia, không đưa vào RAG
├── translate/                        Bản dịch, cache, báo cáo theo lần chạy
├── review/                           Verdict và kết quả review
└── eval/                             Kết quả eval lịch sử
```

## Cần sửa gì thì vào đâu

| Việc | Sửa ở | Test ở |
| --- | --- | --- |
| Màn hình của module | `apps/web/src/modules/<module>/` | `tests/apps/web/` |
| API, nghiệp vụ Java | `backend/src/main/<module>/` | `tests/backend/java/<module>/` |
| Kiểu dữ liệu cho module khác dùng | `backend/src/main/<module>/api/` | như trên |
| Phản hồi chuẩn, lỗi, bảo mật | `backend/src/main/shared/` | `tests/backend/java/system/` |
| Bảng hoặc cột mới | thêm `database/migrations/V0NN__…sql` (không sửa file cũ) | `tests/backend/java/system/` |
| Kiểu JSON cho frontend | `packages/contracts/src/index.ts` + record Java | cả hai phía |
| Safety Gate, gọi LLM | `services/ai/app/` | `tests/services/ai/` |
| Thống kê | `services/stats/stats_api/` | `tests/services/stats/` |
| Cấu hình Spring | `backend/src/resources/application*.yml` | |
| Biến môi trường mới | `.env.example` (và `deploy/.env.example` nếu deploy cần) | |
| Đường dẫn dữ liệu, danh sách URL | `config/paths.yaml` | `tests/tools/` |
| Crawl, chuẩn hóa, dịch | `tools/<crawl\|normalize\|translate>/` | `tests/tools/<cùng tên>/` |

Ví dụ module `lifestyle`: giao diện ở `apps/web/src/modules/lifestyle/`, backend ở
`backend/src/main/lifestyle/` (package `lifestyle`), test ở `tests/backend/java/lifestyle/`.

## Quy tắc

- Không thêm layer chỉ để đủ mẫu kiến trúc; không tạo thư mục rỗng cho đủ cây.
- Module khác chỉ dùng package `api/` hoặc interface service công khai, không đọc bảng của nhau.
- Không viết cứng đường dẫn hay endpoint:
  - Python offline lấy đường dẫn bằng `data_path("<tên>")` trong `tools/project_config.py`.
  - Backend lấy từ `application*.yml`, giá trị thật qua biến môi trường (ví dụ `DATABASE_DIR`).
  - Endpoint, model, key LLM chỉ ở `.env`.
- Không đặt mã trong `.data/`; không commit `.env`, `.data/`, `tests/results/`, `tests/eval/local/`.

## Đường dẫn trong `config/paths.yaml`

| Tên | Trỏ tới | Dùng bởi |
| --- | --- | --- |
| `collection`, `crawl_checkpoint` | `.data/crawl/…` | crawl, normalize, translate |
| `normalized`, `normalized_reference` | `.data/normalize/…` | normalize, translate |
| `translated`, `review`, `eval` | `.data/…` | translate, review, eval |
| `url_lists` | `docs/` (mọi `urls*.txt`, mỗi file là một nhóm crawl) | crawl |
| `urls`, `urls_lifestyle`, `urls_who`, `urls_reference` | từng danh sách URL | crawl, normalize |
| `medicine_index_urls` | `docs/urls_discovery.txt` | normalize, translate |

File viết bằng cú pháp JSON hợp lệ trong YAML 1.2, để Python đọc bằng thư viện chuẩn.
Khi sửa, giữ dấu ngoặc và dấu nháy. Đường dẫn tương đối tính từ gốc repo, không phụ thuộc
thư mục đang đứng; CLI vẫn nhận đường dẫn riêng qua tham số. Model và ngưỡng chất lượng dịch
nằm ở `tools/translate/config.json`, không chứa bí mật.

## Cấu hình `.env`

Dùng một `.env` ở gốc repo cho máy local; biến môi trường của tiến trình thắng giá trị trong file.
Chỉ ghi `KEY=VALUE`, không dấu nháy, không comment cuối dòng. Không đặt key vào biến `VITE_*`
(biến này lộ ra bundle frontend). Deploy dùng `deploy/.env` riêng; Docker chỉ truyền biến
mỗi service cần.

Mỗi vai trò LLM dùng một nhóm biến riêng, không tự động dùng chung key:

| Vai trò | Prefix | Ghi chú |
| --- | --- | --- |
| Chatbot AI (`services/ai`) | `AI_LLM_*` | Router LLM local (OpenAI-compatible), key riêng cho chatbox |
| Dịch offline | `TRANSLATE_LLM_*` | Không dùng cho chatbot |
| Review corpus offline | `REVIEW_LLM_*` | Không dùng key dịch hoặc key chatbox |

## Backend: package và build

- Không có package cha chung: package Java bắt đầu bằng tên module (`accounts.auth`, `health`,
  `knowledge.authoring`), các nhánh con vẫn giữ.
- Entry point `system.HealthAssistantApplication` khai báo rõ component scanning và
  configuration-properties scanning cho chín package top-level. Thêm package top-level mới
  thì cập nhật cả hai danh sách và test hồi quy tương ứng.
- `backend/pom.xml` khai báo source root `src/main`, resource root `src/resources`, test
  `../tests/backend/java` và `../tests/backend/resources`. `groupId` Maven giữ nguyên.
- Test giữ cùng package với main để truy cập được thành phần package-private; test tích hợp
  ghi `@SpringBootTest(classes = HealthAssistantApplication.class)`.

Chạy từ gốc repo với Java 21 và Maven:

```powershell
mvn -B -f backend/pom.xml clean verify
java -jar backend/target/health-assistant-backend-0.1.0.jar --spring.profiles.active=mock --app.data-mode=mock
```

JAR phải chứa ba file YAML trong `BOOT-INF/classes/` và manifest
`Start-Class: system.HealthAssistantApplication` (CI kiểm tra). Docker build chạy từ gốc repo.

## Python và Pylance

`pyrightconfig.json` khai báo đường dẫn import cho từng nhóm test/công cụ. Crawl, normalize,
translate có execution environment riêng để không nhầm các file cùng tên `run.py`.
Không cần đặt `PYTHONPATH` toàn máy hoặc tắt `reportMissingImports`.

## Kiểm thử

Chạy từ gốc repo, mỗi suite một lệnh (tránh các module cùng tên `run` che nhau):

```powershell
npm run check                                                     # frontend
mvn -B -f backend/pom.xml verify                                  # backend
python -B -m pytest -c tests/services/ai/pytest.ini tests/services/ai --junitxml=tests/results/ai.xml
python -B tests/tools/test_project_config.py
python -B -m unittest discover -s tests/tools/crawl -p "test_*.py"
python -B tests/tools/normalize/test_normalize.py
python -B tests/tools/translate/test_translate.py
python -B tests/eval/review_compare.py --help
```

Test thống kê chạy từ `services/stats`: `python -B manage.py test ../../tests/services/stats`.
Test tự động không gọi LLM thật. Báo cáo test mới ghi vào `tests/results/`.

## Chuyển dữ liệu cũ

Dừng crawler, dịch và review trước khi chuyển. Chạy từ gốc repo:

```powershell
python -B tools/migrate_data.py          # xem trước, không thay đổi
python -B tools/migrate_data.py --apply  # di chuyển, không ghi đè hoặc gộp thư mục
```

Migration giữ nguyên byte dữ liệu, cache, ngân sách crawl và checkpoint; chạy lại không di
chuyển gì thêm. Nếu đích đã tồn tại thì dừng, không xóa dữ liệu. Receipt và báo cáo lịch sử
có thể còn đường dẫn cũ: giữ làm bằng chứng, không sửa hàng loạt. Runner riêng (kể cả
`run6.sh`) nằm ở `tests/eval/local/`, không chạy tự động vì có thể gọi LLM tốn phí.
