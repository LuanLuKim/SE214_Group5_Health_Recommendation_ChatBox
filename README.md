# SE214 Group 5 AI Health Assistant

Nền tảng ban đầu để sáu thành viên phát triển song song frontend và backend theo SRS. Giao diện tiếng Việt, React + TypeScript, Express + TypeScript, PostgreSQL và hợp đồng API dùng chung.

**Đây là bản khung phát triển.** Có điều hướng sáu module, API metadata, tìm kiếm dữ liệu tổng hợp, schema/migration/seed, adapter LLM và kiểm thử. Đăng nhập, dữ liệu cá nhân, Safety Gate y tế, RAG, phê duyệt và nhắc nhở chưa được triển khai. API riêng tư chủ động từ chối truy cập; không có tài khoản đăng nhập giả hoặc phân quyền bằng header.

## Chạy nhanh

Cài Node.js 24 LTS và npm 11. Mở terminal tại thư mục gốc repository:

```powershell
npm ci
Copy-Item apps/api/.env.example apps/api/.env
Copy-Item apps/web/.env.example apps/web/.env
npm run dev
```

Chỉ sao chép `.env` ở lần cài đặt đầu tiên. Không ghi đè nếu đã điền key. Máy tạo bản khung đã có sẵn hai file `.env` cục bộ; chúng không được đưa lên GitHub.

- Frontend: <http://127.0.0.1:5173>
- Backend: <http://127.0.0.1:4000/healthz>
- Danh sách module: <http://127.0.0.1:4000/api/v1/modules>

Mặc định `DATA_MODE=mock`: dữ liệu synthetic rất nhỏ, không cần Docker hoặc API key. Mục **Tra cứu & quản lý nội dung** đã gọi backend và tìm kiếm fixture có nhãn `[TEST]`. Các cơ sở, địa chỉ, bài viết thử nghiệm đều không có thật. Đây không phải tài liệu y tế được thẩm định.

## Tìm đúng module của bạn

| Module | Phạm vi                                                 | Frontend / Backend cùng tên                                     |
| ------ | ------------------------------------------------------- | --------------------------------------------------------------- |
| M1     | Safety Gate & quy tắc cảnh báo — UC-07, UC-16           | `apps/web/src/modules/safety/` / `apps/api/src/modules/safety/` |
| M2     | Tra cứu & quản lý nội dung — UC-10, UC-11, UC-15, UC-17 | `.../modules/knowledge/`                                        |
| M3     | Hồ sơ, chỉ số & thống kê — UC-04, UC-05, UC-19          | `.../modules/health/`                                           |
| M4     | Hội thoại với trợ lý AI — UC-06, UC-08, UC-09           | `.../modules/chat/`                                             |
| M5     | Tài khoản & phân quyền — UC-01, UC-02, UC-03, UC-18     | `.../modules/accounts/`                                         |
| M6     | Lộ trình sinh hoạt & nhắc nhở — UC-12, UC-13, UC-14     | `.../modules/lifestyle/`                                        |

Mỗi thư mục module có `README.md`. Bắt đầu với [bản đồ module](docs/MODULES.md), rồi đọc [hướng dẫn đóng góp](CONTRIBUTING.md).

```text
apps/
  web/                         Giao diện React
    src/components/            Thành phần giao diện dùng chung
    src/lib/                   HTTP client và tiện ích chung
    src/modules/<module>/      Page.tsx + README.md của từng module
    tests/                     Kiểm thử giao diện/module
  api/                         Backend Express
    src/modules/<module>/      router.ts + service.ts + README.md
    src/shared/                Ranh giới xác thực, xử lý lỗi và request ID
    src/integrations/llm/      Adapter API tương thích OpenAI
    scripts/                   Chạy migration/seed
    tests/                     API, database và adapter LLM
packages/contracts/            Kiểu dữ liệu và schema chung frontend/backend
database/
  migrations/                  Một migration nền tảng cho mỗi module
  seeds/                       Dữ liệu tổng hợp, có thể chạy lặp
docs/
  MODULES.md                   Phân chia, luồng phụ thuộc, điểm mở rộng
  API.md                       Route, trạng thái và quyền cần triển khai
  SETUP.md                     Hướng dẫn môi trường, PostgreSQL, LLM
  requirements/                Handoff tổng hợp; SRS gốc vẫn là ưu tiên
.github/                       Mẫu pull request
```

## PostgreSQL với dữ liệu tổng hợp

Khởi động Docker Desktop nếu dùng Docker:

```powershell
npm run db:up
# Đợi container healthy trước bước tiếp theo:
docker compose ps
npm run db:migrate
npm run db:seed
```

Sau đó đổi `DATA_MODE=postgres` trong `apps/api/.env` và khởi động lại API. Seed gồm ba tài khoản **không có mật khẩu đăng nhập**, hồ sơ/chỉ số giả, một bài viết thử được công khai và một bản nháp, một cơ sở giả, một quy tắc nháp, hội thoại/feedback giả và lộ trình/nhắc nhở tắt. Chưa có y lệnh, quy tắc y tế được kích hoạt hoặc gửi email thật.

Xem [database/README.md](database/README.md) để hiểu thứ tự migration và hạn chế schema ban đầu.

## Điền API key

Mở **`apps/api/.env`**, điền `LLM_API_KEY`, `LLM_MODEL` và `LLM_BASE_URL`. File này đã được Git bỏ qua. Không đặt key trong frontend hoặc biến `VITE_*`.

Adapter tương thích OpenAI đã có, hỗ trợ thay base URL cho OpenAI hoặc Gemini. Mặc định `LLM_ENABLED=false`. Chat công khai chưa gọi adapter; M4 sẽ kết nối sau khi có Safety Gate và kiểm tra đầu ra. [SETUP.md](docs/SETUP.md#llm) có cấu hình cụ thể.

## Kiểm tra trước khi push

```powershell
npm run check
```

Lệnh chạy kiểm tra kiểu, kiểm thử API/LLM/database/frontend và build hai ứng dụng. Database test dùng PostgreSQL nhúng trong bộ nhớ, không cần Docker. [Mẫu GitHub Actions](docs/ci/baseline-checks.yml.example) chạy cùng lệnh trên Node 24; hiện chưa kích hoạt do credential push không có quyền `workflow`. Thành viên có quyền phù hợp có thể chép mẫu vào `.github/workflows/ci.yml` rồi commit để bật CI.

`npm run format` định dạng mã; `npm run format:check` kiểm tra định dạng. Dùng branch `feat/m1-...` đến `feat/m6-...` và tạo PR vào `main`. Đừng push `node_modules`, `.env`, dữ liệu sức khỏe thật hoặc kho crawl lớn.

## Tài liệu yêu cầu

[PROJECT_CONTEXT.md](docs/requirements/PROJECT_CONTEXT.md) tổng hợp ý tưởng và các điểm còn mở. SRS Word gốc của nhóm là tài liệu ưu tiên khi có xung đột. Các file Word/Excel gốc không được sao chép vào bản khung này; nhóm tiếp tục quản lý bản chính riêng. Phân chia sáu module mới nhất được ghi trong `docs/MODULES.md`.
