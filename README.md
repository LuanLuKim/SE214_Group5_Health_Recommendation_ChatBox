# SE214 Group 5 AI Health Assistant

Trợ lý sức khỏe tiếng Việt cho sáu thành viên phát triển song song theo SRS. Công nghệ theo khuyến nghị của nhóm ([docs/TECH_STACK.md](docs/TECH_STACK.md)):

| Phần             | Công nghệ                                                | Thư mục           |
| ---------------- | -------------------------------------------------------- | ----------------- |
| Frontend         | React, TypeScript, Vite                                  | `apps/web/`       |
| Backend chính    | Java 21, Spring Boot, **modular monolith** (M1–M6)       | `backend/`        |
| Dịch vụ AI       | Python, FastAPI (Safety Gate, RAG, LLM, kiểm tra đầu ra) | `services/ai/`    |
| Dịch vụ thống kê | Python, Django (UC-05 xu hướng, UC-19 thống kê)          | `services/stats/` |
| Cơ sở dữ liệu    | PostgreSQL, migration chạy bằng Flyway                   | `database/`       |
| Dữ liệu tri thức | Python, crawler và chuẩn hóa (M7)                        | `tools/`          |

Frontend chỉ gọi Spring Boot. Spring Boot gọi FastAPI và Django qua REST/JSON trong mạng nội bộ, kèm khóa dịch vụ. Kiến trúc còn sơ bộ: đây là **bản khung**. Đăng nhập, dữ liệu cá nhân, Safety Gate y tế, RAG, phê duyệt và nhắc nhở chưa được triển khai; API riêng tư chủ động trả 503 cho tới khi M5 làm xác thực.

## Chạy nhanh

Cần: Node.js 24 và npm 11, JDK 21 và Maven 3.9, Python 3.12. Docker chỉ cần khi dùng PostgreSQL.

```powershell
# 1. Frontend (cửa sổ 1)
npm ci
Copy-Item apps/web/.env.example apps/web/.env
npm run dev                       # http://127.0.0.1:5173

# 2. Backend Spring Boot (cửa sổ 2), mặc định DATA_MODE=mock, không cần database
cd backend
mvn spring-boot:run               # http://127.0.0.1:4000/healthz

# 3. Dịch vụ Python (chỉ cần khi làm M1, M3, M4)
cd services/ai;    python -m pip install -r requirements.txt; python -m uvicorn app.main:app --port 8001
cd services/stats; python -m pip install -r requirements.txt; python manage.py runserver 127.0.0.1:8002
```

Cấu hình cục bộ đặt trong `backend/.env`, `services/ai/.env`, `services/stats/.env` (chép từ `.env.example`, Git bỏ qua). Mặc định `DATA_MODE=mock`: dữ liệu tổng hợp rất nhỏ có nhãn `[TEST]`, không cần Docker hay API key. Đây không phải tài liệu y tế được thẩm định.

## Tìm đúng module của bạn

| Module | Phạm vi                                                 | Frontend                          | Backend (package Java)                              |
| ------ | ------------------------------------------------------- | --------------------------------- | --------------------------------------------------- |
| M1     | Safety Gate & quy tắc cảnh báo — UC-07, UC-16           | `apps/web/src/modules/safety/`    | `vn.edu.uit.se214.health.safety` + `services/ai`    |
| M2     | Tra cứu & quản lý nội dung — UC-10, UC-11, UC-15, UC-17 | `apps/web/src/modules/knowledge/` | `vn.edu.uit.se214.health.knowledge`                 |
| M3     | Hồ sơ, chỉ số & thống kê — UC-04, UC-05, UC-19          | `apps/web/src/modules/health/`    | `vn.edu.uit.se214.health.health` + `services/stats` |
| M4     | Hội thoại với trợ lý AI — UC-06, UC-08, UC-09           | `apps/web/src/modules/chat/`      | `vn.edu.uit.se214.health.chat` + `services/ai`      |
| M5     | Tài khoản & phân quyền — UC-01, UC-02, UC-03, UC-18     | `apps/web/src/modules/accounts/`  | `vn.edu.uit.se214.health.accounts`                  |
| M6     | Lộ trình sinh hoạt & nhắc nhở — UC-12, UC-13, UC-14     | `apps/web/src/modules/lifestyle/` | `vn.edu.uit.se214.health.lifestyle`                 |

UC-17 là một use case của M2; phần kiểm duyệt đầu vào do M1 làm, cắm vào hàng đợi duyệt dùng chung của M2. Mỗi package module có `README.md`. Đọc [bản đồ module](docs/MODULES.md) và [hướng dẫn đóng góp](CONTRIBUTING.md) trước khi bắt đầu.

```text
apps/web/                      Giao diện React (src/modules/<module>/Page.tsx + README.md)
packages/contracts/            Kiểu dữ liệu dùng chung cho frontend (khớp JSON của backend)
backend/                       Spring Boot modular monolith
  src/main/java/.../health/
    <module>/                  Controller, Service, Repository, package api/ công khai
    shared/                    Phản hồi chuẩn, lỗi, request ID, giới hạn request, @RequiresSession
    integrations/              Client gọi services/ai và services/stats
    system/                    /healthz, danh sách module, nạp dữ liệu giả lập
  src/test/java/               Test API, PostgreSQL nhúng, nghiệp vụ
services/ai/                   FastAPI: app/ (safety, llm, main) và tests/
services/stats/                Django: stats_api/ và tests
database/migrations/           V001__… đến V008__… (Flyway)
database/seeds/                Dữ liệu tổng hợp, chạy lặp được
tools/                         Crawler, chuẩn hóa, dịch (M7)
.github/workflows/             CI (check, backend, services, data-tools, infra-smoke), CD (image Docker)
```

## PostgreSQL với dữ liệu tổng hợp

```powershell
npm run db:up                      # cần Docker Desktop; đợi healthy: docker compose ps
cd backend
$env:DATA_MODE="postgres"; $env:SEED_SYNTHETIC="true"; mvn spring-boot:run
```

Khi chạy với `DATA_MODE=postgres`, Flyway tự áp dụng migration chưa chạy; `SEED_SYNTHETIC=true` nạp dữ liệu giả lập sau đó. Seed gồm ba tài khoản **không có mật khẩu đăng nhập**, hồ sơ/chỉ số giả, một bài viết công khai và một bản nháp, một cơ sở giả, một quy tắc nháp, hội thoại/feedback giả và nhắc nhở đang tắt. Xem [database/README.md](database/README.md).

Chạy cả stack trong Docker để demo trên máy: `docker compose --profile full up --build`.

## Điền API key

Mở **`services/ai/.env`**, điền `LLM_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL` và đặt `LLM_ENABLED=true`. Đặt cùng một `AI_SERVICE_KEY` ở `services/ai/.env` và `backend/.env`. Không đặt key trong frontend hoặc biến `VITE_*`. Chi tiết: [docs/SETUP.md](docs/SETUP.md#llm).

## Kiểm tra trước khi push

```powershell
npm run check                              # frontend + contracts
mvn -f backend/pom.xml verify              # backend (gồm test PostgreSQL nhúng, không cần Docker)
cd services/ai;    python -m pytest
cd services/stats; python manage.py test
```

CI trên GitHub Actions chạy các bước trên cho mọi nhánh được push, cùng job `infra-smoke` (PostgreSQL thật, ba service trả `/healthz`). Khi merge vào `main`, workflow CD build image Docker của backend, dịch vụ AI, dịch vụ thống kê lên GitHub Container Registry. Quy trình nhánh, PR và review: [CONTRIBUTING.md](CONTRIBUTING.md).

## Thu thập và chuẩn hóa dữ liệu

Xem [cách chạy crawler, chuẩn hóa và thử với JSON mẫu](tools/crawl/README.md). Các tệp đầu ra được lưu cục bộ trong `.data/` (Git bỏ qua); bản dữ liệu đã crawl và chuẩn hóa nằm trong [thư mục Google Drive của nhóm](https://drive.google.com/drive/folders/1BMsxyx8SK94LIRb4UEDoaK_75j9_UULl?usp=sharing).

## Tài liệu yêu cầu

[PROJECT_CONTEXT.md](docs/requirements/PROJECT_CONTEXT.md) tổng hợp ý tưởng và các điểm còn mở. SRS Word gốc của nhóm là tài liệu ưu tiên khi có xung đột. Phân chia sáu module mới nhất được ghi trong `docs/MODULES.md`.
