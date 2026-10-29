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
