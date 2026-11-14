# Môi trường phát triển

## Yêu cầu

- Node.js 24 LTS, npm 11 (frontend).
- JDK 21 và Maven 3.9 (backend Spring Boot). Kiểm tra: `java -version`, `mvn -v`.
- Python 3.12 (dịch vụ AI, thống kê, công cụ dữ liệu M7). Nên dùng một virtualenv riêng cho mỗi service.
- Git. Docker Desktop chỉ cần cho PostgreSQL local hoặc chạy cả stack bằng `docker compose --profile full`.
- Không cần GPU, mô hình local, tài khoản AI hoặc crawl dữ liệu để chạy bản khung.

## Các lệnh

| Lệnh                                         | Chạy ở            | Công dụng                                                 |
| -------------------------------------------- | ----------------- | --------------------------------------------------------- |
| `npm ci` rồi `npm run dev`                   | thư mục gốc       | Build contracts rồi chạy frontend (port 5173)             |
| `npm run check`                              | thư mục gốc       | Typecheck, test và build frontend + contracts             |
| `mvn spring-boot:run`                        | `backend/`        | Chạy backend (port 4000)                                  |
| `mvn verify`                                 | `backend/`        | Test (gồm PostgreSQL nhúng) và đóng gói jar               |
| `python -m uvicorn app.main:app --port 8001` | `services/ai/`    | Chạy dịch vụ AI                                           |
| `python -m pytest`                           | `services/ai/`    | Test dịch vụ AI                                           |
| `python manage.py runserver 127.0.0.1:8002`  | `services/stats/` | Chạy dịch vụ thống kê                                     |
| `python manage.py test`                      | `services/stats/` | Test dịch vụ thống kê                                     |
| `npm run db:up` / `npm run db:down`          | thư mục gốc       | Bật/tắt PostgreSQL local bằng Docker (giữ volume dữ liệu) |
| `npm run format`                             | thư mục gốc       | Định dạng mã frontend và tài liệu                         |

Frontend dev proxy chuyển `/api` và `/healthz` tới port 4000. Đặt `VITE_API_ORIGIN` chỉ khi frontend cần gọi API ở địa chỉ khác; đây là biến công khai trong bundle, không được chứa bí mật. Trên Windows, dùng `npm.cmd` nếu PowerShell chặn `npm.ps1`.

Swagger UI mở tại `http://127.0.0.1:4000/swagger-ui.html`; OpenAPI JSON ở `/v3/api-docs`. Tài liệu được bật mặc định để phát triển; đặt `SWAGGER_ENABLED=false` trong môi trường triển khai nếu không muốn công khai giao diện/tài liệu API.

## Cấu hình

Mỗi service đọc biến môi trường, hoặc file `.env` cục bộ trong thư mục của nó (Git bỏ qua). Chép từ `.env.example` một lần rồi điền giá trị của bạn.

| File                  | Biến chính                                                                                                                                                                                                                                                                                                                                         |
| --------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend/.env`        | `DATA_MODE` (`mock`/`postgres`), `DATABASE_URL` (dạng `jdbc:postgresql://…`), `DATABASE_USER`, `DATABASE_PASSWORD`, `SEED_SYNTHETIC`, `TERMS_VERSION`, `GOOGLE_CLIENT_ID`, `FACEBOOK_APP_ID`, `FACEBOOK_APP_SECRET`, `SMTP_*`, `EMAIL_OTP_HASH_SECRET`, `AUTH_TOKEN_SECRET`, `WEB_ORIGIN`, `AI_SERVICE_URL`, `AI_SERVICE_KEY`, `STATS_SERVICE_URL`, `STATS_SERVICE_KEY` |

`AUTH_TOKEN_SECRET` và `EMAIL_OTP_HASH_SECRET` cần ít nhất 32 byte ngẫu nhiên, khác nhau, được tạo riêng cho từng môi trường và không commit. Có thể tạo từng giá trị cục bộ bằng `node -e "console.log(require('node:crypto').randomBytes(48).toString('base64'))"`.
| `services/ai/.env`    | `AI_SERVICE_KEY`, `LLM_ENABLED`, `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `LLM_TIMEOUT_MS`                                                                                                                                                                                                                                                      |
| `services/stats/.env` | `STATS_SERVICE_KEY`, `DJANGO_SECRET_KEY`, `DJANGO_DEBUG`, `DJANGO_ALLOWED_HOSTS`                                                                                                                                                                                                                                                                   |
| `apps/web/.env`       | `VITE_API_ORIGIN` (thường để trống)                                                                                                                                                                                                                                                                                                                |

`AI_SERVICE_KEY` và `STATS_SERVICE_KEY` phải giống nhau giữa backend và service tương ứng. Service Python để trống khóa thì mọi route `/v1` trả 503, không mở cho ai gọi.

Nếu bạn còn file `apps/api/.env` từ khung Express cũ: chuyển `DATA_MODE`, `HOST`, `PORT`, `WEB_ORIGIN` sang `backend/.env` (đổi `DATABASE_URL` sang dạng JDBC, user và mật khẩu tách riêng), chuyển các biến `LLM_*` sang `services/ai/.env`, rồi xóa thư mục `apps/api`.

## PostgreSQL

`DATA_MODE=mock` là mặc định, dùng fixture công khai nhỏ trong bộ nhớ. `DATA_MODE=postgres` bật DataSource và Flyway: khi khởi động, backend áp dụng các migration chưa chạy trong `database/migrations`; `SEED_SYNTHETIC=true` nạp thêm dữ liệu tổng hợp. Compose chỉ mở cổng database ở `127.0.0.1`.

Mật khẩu trong ví dụ chỉ dành cho máy phát triển. Không dùng `compose.yaml` ở thư mục gốc hay mật khẩu này để triển khai công khai; bản triển khai dùng `deploy/compose.yaml`, xem [DEPLOY.md](DEPLOY.md). Không dùng lệnh xóa volume để reset database của thành viên khác.

Docker phải chạy trước `npm run db:up`; đợi trạng thái healthy bằng `docker compose ps`. Nếu dùng PostgreSQL có sẵn, bỏ qua Docker và sửa `DATABASE_URL`. Test backend dùng PostgreSQL nhúng (thư viện zonky), không cần Docker; job CI `infra-smoke` chạy trên PostgreSQL 17 thật.

## LLM

Adapter tương thích OpenAI nằm ở `services/ai/app/llm.py`. Điền trong `services/ai/.env`:

```dotenv
LLM_ENABLED=false
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=
LLM_MODEL=
LLM_TIMEOUT_MS=20000
```

Adapter gọi `POST /chat/completions` với Bearer authentication, theo [OpenAI API reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create). Model không bị hardcode. Để dùng Gemini qua giao thức tương thích OpenAI, đổi `LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai` và dùng key/model Gemini, theo [Google OpenAI compatibility documentation](https://ai.google.dev/gemini-api/docs/openai/). URL phải là `https`, không chứa user/mật khẩu, query hay fragment.

Adapter từ chối redirect và không đưa lỗi hay body từ nhà cung cấp ra ngoài (`LLM_REQUEST_FAILED`). Chưa có route HTTP nào gọi trực tiếp adapter: M4 phải tích hợp Safety Gate (M1), nguồn đã duyệt (M2), tối thiểu hóa dữ liệu và kiểm tra đầu ra trước khi bật chat. Test dùng transport giả, không gọi mạng và không tốn tiền. Không gửi key thật qua chat, commit hoặc ticket.

## Lỗi thường gặp

- Frontend báo API offline: kiểm tra backend đang chạy và <http://127.0.0.1:4000/healthz> trả `ok`.
- `mvn` không có: cài JDK 21 và Maven, hoặc mở dự án `backend/` bằng IntelliJ IDEA (có Maven sẵn).
- Backend báo lỗi kết nối database với `DATA_MODE=postgres`: bật Docker (`npm run db:up`) hoặc quay về `DATA_MODE=mock`.
- Flyway báo checksum khác: không sửa migration đã áp dụng; thêm migration mới.
- Port 5173 hoặc 4000 đã có ứng dụng khác: dừng đúng ứng dụng đó hoặc đổi port và cấu hình origin/proxy tương ứng.
- Không tìm thấy `@health/contracts/dist`: chạy `npm run build -w @health/contracts` hoặc dùng các lệnh ở thư mục gốc.
- Sau khi đổi `.env`: khởi động lại service tương ứng. Sau khi đổi biến frontend trong bản build: build lại web.
