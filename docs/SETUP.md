# Môi trường phát triển

## Yêu cầu

- Node.js 24 LTS, npm 11. Có thể dùng `.nvmrc` nếu có trình quản lý phiên bản.
- Git. Docker Desktop chỉ cần cho PostgreSQL local, không cần cho chế độ mock.
- Không cần GPU, mô hình local, tài khoản AI hoặc crawl dữ liệu để chạy baseline.

Từ root, chạy `npm ci`, sao chép hai `.env.example` sang `.env` tương ứng một lần, rồi `npm run dev`. Trên macOS/Linux dùng `cp` thay `Copy-Item`. Dùng `npm.cmd` nếu PowerShell chặn script `npm.ps1`.

## Các lệnh

| Lệnh tại root        | Công dụng                                             |
| -------------------- | ----------------------------------------------------- |
| `npm run dev`        | Build contract rồi chạy frontend và backend cùng nhau |
| `npm run check`      | Typecheck, test và production build                   |
| `npm run build`      | Build contract, API, web                              |
| `npm test`           | Test cả hai workspace                                 |
| `npm run format`     | Định dạng mã/tài liệu                                 |
| `npm run db:up`      | Chạy PostgreSQL local bằng Docker                     |
| `npm run db:migrate` | Áp dụng migration chưa chạy                           |
| `npm run db:seed`    | Nạp synthetic data, có thể chạy lặp                   |
| `npm run db:down`    | Dừng container, giữ volume dữ liệu                    |

Frontend dev proxy chuyển `/api` và `/healthz` tới port 4000. Đặt `VITE_API_ORIGIN` chỉ khi frontend cần truy cập API ở địa chỉ khác. Đây là biến công khai trong bundle, không được chứa bí mật.

Build API nằm trong `apps/api/dist`, web trong `apps/web/dist`. Sau `npm run build`, chạy API bằng `npm start -w @health/api`, và xem web build bằng `npm run preview -w @health/web`. Vite preview không dùng dev proxy; cấu hình `VITE_API_ORIGIN=http://127.0.0.1:4000` trước build và đặt `WEB_ORIGIN=http://127.0.0.1:4173` cho API nếu kiểm tra hai server preview này.

## PostgreSQL

`DATA_MODE=mock` là mặc định, dùng public fixture nhỏ trong bộ nhớ. `DATA_MODE=postgres` đọc các bảng đã seed thông qua connection pool tối đa ba kết nối. Đặt `DATABASE_URL` trước khi migrate/seed. Compose chỉ mở cổng database ở loopback `127.0.0.1`.

Password trong ví dụ chỉ dành cho local development. Không dùng compose/password này để triển khai public. Không dùng lệnh xóa volume để reset database của thành viên khác.

Docker phải chạy trước `npm run db:up`; đợi trạng thái healthy bằng `docker compose ps`. Nếu dùng PostgreSQL có sẵn, bỏ qua Docker và sửa `DATABASE_URL`. Test SQL dùng PGlite trong bộ nhớ, không thay thế việc thử kết nối Docker/PostgreSQL thật của máy bạn.

## LLM

File cục bộ: `apps/api/.env`. File mẫu được commit: `apps/api/.env.example`.

```dotenv
LLM_ENABLED=false
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=
LLM_MODEL=
LLM_TIMEOUT_MS=20000
```

Điền key và model ID của tài khoản bạn vào file cục bộ. Adapter dùng `POST /chat/completions` với Bearer authentication, theo [OpenAI API reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create). Model không bị hardcode. ChatGPT trong trình duyệt và API là hai cách truy cập khác nhau; dùng API credential cho backend.

Để dùng Gemini qua giao thức tương thích OpenAI, đổi `LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai` và dùng key/model Gemini của bạn, theo [Google OpenAI compatibility documentation](https://ai.google.dev/gemini-api/docs/openai/).

Adapter ở `apps/api/src/integrations/llm/client.ts`. Nó nhận model, key, URL, timeout từ server config; từ chối redirect và không ghi lỗi/body từ nhà cung cấp ra log. HTTP backend không cung cấp endpoint gọi trực tiếp adapter. M4 cần tích hợp Safety Gate M1, nguồn đã duyệt M2, tối thiểu hóa dữ liệu và kiểm tra đầu ra trước khi bật chat. `LLM_ENABLED=true` chỉ cho phép adapter nội bộ hoạt động; không tự hoàn thiện chatbot.

Baseline dùng phản hồi đầy đủ (`stream: false`) để chuẩn bị bước kiểm tra đầu ra. Streaming đã kiểm tra, retry/rate-limit theo provider và đánh giá chất lượng tiếng Việt là công việc tiếp theo của M4. Các test provider đều dùng transport giả, không gọi mạng và không tốn tiền. Không gửi key thật qua chat, commit hoặc ticket.

## Lỗi thường gặp

- API báo offline: chạy cả hai ứng dụng bằng root `npm run dev`; kiểm tra `/healthz`.
- Port 5173 đã có ứng dụng khác: dừng đúng ứng dụng đó hoặc thay port và cấu hình origin/proxy tương ứng.
- Không tìm thấy `@health/contracts/dist`: chạy `npm run build -w @health/contracts` hoặc dùng root scripts.
- Docker engine unavailable: mở Docker Desktop hoặc dùng mock mode; không cần cài PostgreSQL chỉ để chạy test.
- Sau khi đổi `.env`: khởi động lại API. Sau khi đổi frontend env trong bản build: build lại web.
