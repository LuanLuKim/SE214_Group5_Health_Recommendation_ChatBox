# API baseline

Base path: `/api/v1`. Contract dùng chung: `packages/contracts/src/index.ts`. Đây là API nền tảng, chưa phải bản hoàn thiện của các use case. Backend không lưu request body/prompt vào log.

Swagger UI: `/swagger-ui.html`; OpenAPI JSON: `/v3/api-docs`. Có thể tắt cả hai bằng `SWAGGER_ENABLED=false`.

Thành công: `{ "data": ..., "meta": { "status": "...", "total": 0 } }` (`meta` tùy route). Lỗi: `{ "error": { "code": "...", "message": "...", "requestId": "..." } }`. `X-Request-ID` được server tạo. Dữ liệu JSON tối đa 32 KiB; giới hạn baseline 120 request/phút/IP.

Mã lỗi chung, mọi phản hồi lỗi đều là JSON dù client gửi `Accept` nào:

| HTTP | `code`                              | Khi nào                                        |
| ---- | ----------------------------------- | ---------------------------------------------- |
| 400  | `INVALID_REQUEST`                   | JSON hỏng, sai kiểu dữ liệu, thiếu tham số     |
| 400  | `INVALID_QUERY`                     | Từ khóa tìm kiếm không hợp lệ                  |
| 404  | `NOT_FOUND`                         | Không có route (kể cả `/error`)                |
| 405  | `METHOD_NOT_ALLOWED`                | Sai phương thức                                |
| 406  | `NOT_ACCEPTABLE`                    | Client chỉ nhận định dạng khác JSON            |
| 413  | `PAYLOAD_TOO_LARGE`                 | Body vượt 32 KiB                               |
| 429  | `RATE_LIMITED`                      | Quá 120 request/phút từ một IP                 |
| 403  | `CSRF_INVALID`                      | Thiếu/sai CSRF token cho request thay đổi dữ liệu |
| 403  | `ACCESS_DENIED`                     | Request bị từ chối quyền                        |
| 500  | `INTERNAL_ERROR`                    | Lỗi chưa lường trước; tra log theo `requestId` |
| 501  | `NOT_IMPLEMENTED`                   | Route đã dành chỗ, chưa có nghiệp vụ           |
| 401  | `SESSION_INVALID`                   | Access JWT thiếu/không hợp lệ/hết hạn          |
| 401  | `REFRESH_TOKEN_INVALID`             | Refresh token không hợp lệ/hết hạn/đã thu hồi    |
| 503  | `AUTH_NOT_CONFIGURED`               | Chế độ mock không có authentication provider     |
| 503  | `DATABASE_UNAVAILABLE`              | Không kết nối được PostgreSQL; thử lại sau     |
| 409  | `EMAIL_ALREADY_REGISTERED`          | Email đã được đăng ký                          |
| 409  | `OAUTH_IDENTITY_ALREADY_REGISTERED` | Tài khoản OAuth đã được đăng ký                |

Lỗi 500 và 503 `DATABASE_UNAVAILABLE` được ghi log một dòng gồm `requestId`, phương thức, đường dẫn (không có query string), tên lớp ngoại lệ và vị trí trong code. Thông điệp của ngoại lệ không được ghi vì có thể chứa dữ liệu người dùng.

Hai trường hợp không qua khuôn lỗi trên vì bị chặn trước khi vào ứng dụng: URL mã hóa sai hoặc header quá dài (Tomcat trả trang 400 tối giản) và request có `Origin` không được phép (bộ lọc CORS trả 403 dạng văn bản).

## Các route đã chạy

| Method | Route                                                       | Kết quả                                                                       |
| ------ | ----------------------------------------------------------- | ----------------------------------------------------------------------------- |
| GET    | `/healthz`                                                  | Trạng thái tiến trình, không phải kiểm tra readiness của DB/LLM               |
| GET    | `/api/v1/modules`                                           | Sáu module và 19 UC                                                           |
| GET    | `/api/v1/{safety,knowledge,health,chat,accounts,lifestyle}` | Metadata module                                                               |
| GET    | `/api/v1/knowledge/articles?q=`                             | Public: fixture synthetic hoặc tối đa 20 bài đã duyệt từ PostgreSQL           |
| GET    | `/api/v1/knowledge/facilities?q=`                           | Public: fixture synthetic hoặc tối đa 20 cơ sở có ngày xác minh từ PostgreSQL |

`q` là một chuỗi tối đa 200 ký tự, không chứa ký tự điều khiển (tab, xuống dòng, NUL…); sai thì trả 400 `INVALID_QUERY`. Danh sách và search metadata không chứa private user data. Fixture `[TEST]` chỉ dành kiểm thử; không trộn fixture vào kho kiến thức y tế thật.

## Các route đã dành chỗ

`POST /accounts/register` (email/mật khẩu) bắt đầu quy trình đăng ký và gửi OTP qua SMTP; trả 202, chưa tạo account. `POST /accounts/register/verify` nhận email và OTP, chỉ tạo account/role/consent sau khi xác minh; mã có hiệu lực 10 phút, tối đa 5 lần nhập sai. `POST /accounts/register/resend` gửi mã mới sau cooldown 60 giây. OTP chỉ dùng cho email/mật khẩu, được lưu dạng HMAC hash và secret `EMAIL_OTP_HASH_SECRET` phải có ít nhất 32 byte. SMTP dùng `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `MAIL_FROM`.

`POST /accounts/login` chỉ nhận email/password local. `POST /accounts/oauth` nhận provider/token OAuth; identity đã tồn tại thì đăng nhập, còn identity mới phải kèm consent hợp lệ, được tạo thành tài khoản OAuth riêng và đăng nhập ngay. OAuth và local là hai phương thức độc lập, không có API liên kết tài khoản. Nếu email của OAuth đã thuộc local account, trả 409 `EMAIL_ALREADY_REGISTERED`, không tự liên kết.

Đăng nhập thành công đặt access JWT (tối đa 15 phút) và refresh token (tối đa 8 giờ) vào cookie HttpOnly; `POST /accounts/refresh` xoay refresh token. Thời hạn refresh token là 8 giờ tuyệt đối; access JWT không sống quá refresh token. `POST /accounts/logout` thu hồi refresh token và xóa cookie, nhưng access JWT đã cấp còn hiệu lực đến khi hết hạn (tối đa 15 phút). Cần `AUTH_TOKEN_SECRET` tối thiểu 32 byte. Cấu hình OAuth qua `GOOGLE_CLIENT_ID`, `FACEBOOK_APP_ID`, `FACEBOOK_APP_SECRET`; nếu thiếu, provider tương ứng không khả dụng.

Authentication dùng Spring Security stateless Resource Server: access JWT được xác minh từ cookie `HA_ACCESS`, không truy vấn database cho mỗi request. Ở `DATA_MODE=postgres`, do trình duyệt tự gửi cookie, trước các request thay đổi dữ liệu hãy gọi `GET /api/v1/csrf`, rồi gửi giá trị `data.token` trong header `X-XSRF-TOKEN`; server cũng đặt cookie `XSRF-TOKEN`. Quy tắc này áp dụng cả đăng nhập, đăng ký, refresh và logout. Trong `DATA_MODE=mock`, route cần đăng nhập trả 503 `AUTH_NOT_CONFIGURED`.

`POST /accounts/forgot-password` trả cùng thông báo dù email không tồn tại, là tài khoản OAuth-only hay tài khoản local; chỉ local account có mật khẩu mới được gửi mã khôi phục. `POST /accounts/reset-password` nhận email, code và newPassword; mã 6 số hết hạn sau 10 phút, tối đa 5 lần thử, chỉ dùng một lần. Login local sai 5 lần trong 15 phút tạm khóa tài khoản 15 phút.

`POST /accounts/confirm-deletion` vẫn trả **501 NOT_IMPLEMENTED**.

Các route riêng tư yêu cầu `HA_ACCESS`, xác minh chữ ký, issuer và hạn JWT qua Spring Security; thiếu/không hợp lệ trả 401 `SESSION_INVALID`. Refresh/logout kiểm tra trạng thái refresh token trong database. Header `Authorization`, `X-User-ID` và `X-Role` không thay thế cookie xác thực. Hiện mới có authentication, chưa có phân quyền role như ADMIN/CONTENT_EXPERT; các handler nghiệp vụ hồ sơ, đổi mật khẩu, xóa tài khoản và quản trị vẫn trả 501.

| Module | Routes sau `/api/v1`                                                                                                                                                                             | Quyền cần thực hiện                                                   |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| M1     | POST `/safety/evaluate`; GET/POST `/safety/rules`; PATCH `/safety/rules/:id`                                                                                                                     | Yêu cầu authentication; consent và CONTENT_EXPERT chưa được kiểm tra   |
| M2     | POST `/knowledge/articles`; PATCH/DELETE `/knowledge/articles/:id`; GET `/knowledge/reviews`; POST `/knowledge/reviews/:id/decision`; GET `/knowledge/feedback`; PATCH `/knowledge/feedback/:id` | CONTENT_EXPERT; quyền soạn bài của ADMIN còn phải thống nhất theo SRS |
| M3     | GET/PUT/DELETE `/health/profile`; GET `/health/metrics`; PUT/DELETE `/health/metrics/:date`                                                                                                      | Dữ liệu của chính người dùng, consent                                 |
| M3     | GET `/health/statistics`                                                                                                                                                                         | ADMIN, chỉ số tổng hợp                                                |
| M4     | POST `/chat/messages`; GET `/chat/sessions`; GET `/chat/sessions/:id/messages`; DELETE `/chat/sessions/:id`; POST `/chat/messages/:id/feedback`                                                  | Chính chủ, consent; attachment feedback là opt-in                     |
| M5     | POST `/accounts/logout`; GET/PUT `/accounts/me`; POST `/accounts/me/change-password`; POST `/accounts/me/deletion-request`                                                                       | Chính chủ; xóa cần xác nhận email                                     |
| M5     | GET `/accounts/users`; PATCH `/accounts/users/:id/status`; PUT `/accounts/users/:id/roles`                                                                                                       | ADMIN; không lộ hồ sơ/hội thoại                                       |
| M6     | GET `/lifestyle/templates`; GET/PUT `/lifestyle/plan`; PUT `/lifestyle/completions/:activityId/:date`; GET/POST `/lifestyle/reminders`; PATCH/DELETE `/lifestyle/reminders/:id`                  | Chính chủ, consent; template đã duyệt                                 |

Đây là route ban đầu để chia việc, không phải danh sách API cuối. Chi tiết bài viết, form mẫu kế hoạch, email/OAuth, hàng đợi duyệt và các nhánh UC cần được chủ module bổ sung, cập nhật tài liệu và contract. Không đánh dấu một UC hoàn thành chỉ vì route đã tồn tại.
