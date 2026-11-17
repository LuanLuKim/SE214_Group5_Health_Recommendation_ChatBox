# API baseline

Base path: `/api/v1`. Contract dùng chung: `packages/contracts/src/index.ts`. Đây là API nền tảng, chưa phải bản hoàn thiện của các use case. Backend không lưu request body/prompt vào log.

Thành công: `{ "data": ..., "meta": { "status": "...", "total": 0 } }` (`meta` tùy route). Lỗi: `{ "error": { "code": "...", "message": "...", "requestId": "..." } }`. `X-Request-ID` được server tạo. Dữ liệu JSON tối đa 32 KiB; giới hạn baseline 120 request/phút/IP.

## Các route đã chạy

| Method | Route                                                       | Kết quả                                                                       |
| ------ | ----------------------------------------------------------- | ----------------------------------------------------------------------------- |
| GET    | `/healthz`                                                  | Trạng thái tiến trình, không phải kiểm tra readiness của DB/LLM               |
| GET    | `/api/v1/modules`                                           | Sáu module và 19 UC                                                           |
| GET    | `/api/v1/{safety,knowledge,health,chat,accounts,lifestyle}` | Metadata module                                                               |
| GET    | `/api/v1/knowledge/articles?q=`                             | Public: fixture synthetic hoặc tối đa 20 bài đã duyệt từ PostgreSQL           |
| GET    | `/api/v1/knowledge/facilities?q=`                           | Public: fixture synthetic hoặc tối đa 20 cơ sở có ngày xác minh từ PostgreSQL |

`q` là một chuỗi tối đa 200 ký tự. Danh sách và search metadata không chứa private user data. Fixture `[TEST]` chỉ dành kiểm thử; không trộn fixture vào kho kiến thức y tế thật.

## Quản lý bản nháp bài viết (UC-15, phase 1)

Các route dưới đây đã có nghiệp vụ PostgreSQL; truy cập qua ứng dụng hiện vẫn trả `503 AUTH_NOT_CONFIGURED` cho đến khi M5 cung cấp xác thực. M5 truyền `ContentAuthenticator` ở tham số thứ ba của `createKnowledgeRouter`: resolver phải xác thực phiên thật, trạng thái tài khoản và trả `{ userId, roles }` từ server hoặc `null` nếu chưa đăng nhập. Không lấy vai trò từ body, header giả hay query. `CONTENT_EXPERT` và `ADMIN` được quản lý nội dung; `USER` bị từ chối với `403 FORBIDDEN`. Khi resolver đã được nối, chưa đăng nhập trả `401 UNAUTHENTICATED`. Nếu chưa có database, người đã được xác thực trả `503 DATABASE_NOT_CONFIGURED`; không lưu bản nháp vào fixture mock.

| Method | Route sau `/api/v1/knowledge` | Kết quả                                                                     |
| ------ | ----------------------------- | --------------------------------------------------------------------------- |
| GET    | `/authoring/articles`         | Danh sách quản trị tất cả trạng thái, sắp theo thời gian tạo giảm dần và ID |
| GET    | `/authoring/articles/:id`     | Nội dung và metadata quản trị của một bài                                   |
| POST   | `/articles`                   | Tạo bài `DRAFT`, trả `201`                                                  |
| PATCH  | `/articles/:id`               | Sửa các trường được truyền của bài `DRAFT` hoặc `RETURNED`                  |
| DELETE | `/articles/:id`               | Xóa bài `DRAFT` hoặc `RETURNED`, trả `204`                                  |

POST bắt buộc `title` (1–300 ký tự), `topic` (1–200), `content` (1–30000), `sourceName` (1–200) và `sourceUrl` (URL HTTP/HTTPS tối đa 2000 ký tự, không chứa tài khoản/mật khẩu). Trường tùy chọn: `sourceUpdatedAt` (ngày ISO `YYYY-MM-DD`, timestamp ISO có timezone, hoặc `null`) và `copyrightNotes` (tối đa 3000 ký tự). PATCH dùng cùng các trường, cần ít nhất một trường. Giới hạn request JSON chung 32 KiB vẫn áp dụng. Client không được truyền `reviewStatus`, `verifiedAt`, `createdBy`, `updatedBy` hoặc `isSynthetic`; trường ngoài schema bị từ chối với `400 INVALID_INPUT`.

Ví dụ body tạo bài tổng hợp:

```json
{
  "title": "[TEST] Bài nháp thử nghiệm",
  "topic": "Kiểm thử",
  "content": "Văn bản giả lập, không có hướng dẫn y tế.",
  "sourceName": "Synthetic fixture",
  "sourceUrl": "https://example.invalid/article",
  "sourceUpdatedAt": "2026-10-29",
  "copyrightNotes": "Nội dung tổng hợp để kiểm thử."
}
```

Response thành công bọc `ArticleDraft` trong `data`, gồm các trường đầu vào cùng `id`, `verifiedAt`, `reviewStatus`, `createdBy`, `updatedBy`, `createdAt`, `updatedAt`; ngày giờ trả theo ISO. Danh sách có thêm `meta.total`. Metadata người tạo/sửa do phiên server xác định. Bài mới có `verifiedAt = null`; sửa bài được trả về sẽ đặt lại `DRAFT` và xóa ngày xác minh cũ. Bài không tồn tại/UUID sai trả `404 NOT_FOUND`; sửa/xóa bài `PENDING_REVIEW`, `APPROVED` hoặc `RETIRED` trả `409 CONTENT_NOT_EDITABLE`. Chuyển trạng thái được chặn ngay trong câu lệnh ghi database.

Migration `009_knowledge_article_drafts.sql` giữ nguyên các cột trong `NormalizedArticle.to_db_row()` của M7 và thêm metadata với giá trị mặc định. `db_row` dùng tên cột snake_case, còn API dùng camelCase (`source_name` → `sourceName`, `source_url` → `sourceUrl`). Đầu ra crawler không phải body API trực tiếp: loại các trường do server quản lý; không tin trạng thái duyệt/ngày xác minh từ dữ liệu nhập. `fetched_at` là ngày thu thập, không phải ngày xác minh. UC-15 phase 1 chưa cung cấp endpoint nhập file crawler hoặc phê duyệt; bài chưa duyệt tiếp tục bị loại khỏi tra cứu công khai.

## Các route đã dành chỗ

Các public account action sau trả **501 NOT_IMPLEMENTED**: POST `/accounts/register`, `/accounts/login`, `/accounts/forgot-password`, `/accounts/reset-password`, `/accounts/confirm-deletion`.

Tất cả route dưới đây hiện trả **503 AUTH_NOT_CONFIGURED**, kể cả khi client giả `Authorization`, `X-User-ID` hoặc `X-Role`. Guard không nhận những header này làm phiên thật. Sau M5, thay guard bằng xác thực, rồi bổ sung role/consent/ownership trước khi thay handler 501 bằng nghiệp vụ. Thứ tự phải được giữ.

| Module | Routes sau `/api/v1`                                                                                                                                                                             | Quyền cần thực hiện                                                   |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| M1     | POST `/safety/evaluate`; GET/POST `/safety/rules`; PATCH `/safety/rules/:id`                                                                                                                     | Evaluate: nội bộ/phiên và consent; quy tắc: CONTENT_EXPERT            |
| M2     | POST `/knowledge/articles`; PATCH/DELETE `/knowledge/articles/:id`; GET `/knowledge/reviews`; POST `/knowledge/reviews/:id/decision`; GET `/knowledge/feedback`; PATCH `/knowledge/feedback/:id` | CONTENT_EXPERT; quyền soạn bài của ADMIN còn phải thống nhất theo SRS |
| M3     | GET/PUT/DELETE `/health/profile`; GET `/health/metrics`; PUT/DELETE `/health/metrics/:date`                                                                                                      | Dữ liệu của chính người dùng, consent                                 |
| M3     | GET `/health/statistics`                                                                                                                                                                         | ADMIN, chỉ số tổng hợp                                                |
| M4     | POST `/chat/messages`; GET `/chat/sessions`; GET `/chat/sessions/:id/messages`; DELETE `/chat/sessions/:id`; POST `/chat/messages/:id/feedback`                                                  | Chính chủ, consent; attachment feedback là opt-in                     |
| M5     | POST `/accounts/logout`; GET/PUT `/accounts/me`; POST `/accounts/me/change-password`; POST `/accounts/me/deletion-request`                                                                       | Chính chủ; xóa cần xác nhận email                                     |
| M5     | GET `/accounts/users`; PATCH `/accounts/users/:id/status`; PUT `/accounts/users/:id/roles`                                                                                                       | ADMIN; không lộ hồ sơ/hội thoại                                       |
| M6     | GET `/lifestyle/templates`; GET/PUT `/lifestyle/plan`; PUT `/lifestyle/completions/:activityId/:date`; GET/POST `/lifestyle/reminders`; PATCH/DELETE `/lifestyle/reminders/:id`                  | Chính chủ, consent; template đã duyệt                                 |

Đây là route ban đầu để chia việc, không phải danh sách API cuối. Chi tiết bài viết, form mẫu kế hoạch, email/OAuth, hàng đợi duyệt và các nhánh UC cần được chủ module bổ sung, cập nhật tài liệu và contract. Không đánh dấu một UC hoàn thành chỉ vì route đã tồn tại.
