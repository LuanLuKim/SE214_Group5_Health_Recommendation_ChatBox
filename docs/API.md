# API baseline

Base path: `/api/v1`. Contract dùng chung: `packages/contracts/src/index.ts`. Đây là API nền tảng, chưa phải bản hoàn thiện của các use case. Backend không lưu request body/prompt vào log.

Swagger UI: `/swagger-ui.html`; OpenAPI JSON: `/v3/api-docs`. Có thể tắt cả hai bằng `SWAGGER_ENABLED=false`.

Thành công: `{ "data": ..., "meta": { "status": "...", "total": 0 } }` (`meta` tùy route). Lỗi: `{ "error": { "code": "...", "message": "...", "requestId": "..." } }`. `X-Request-ID` được server tạo. Dữ liệu JSON tối đa 32 KiB; giới hạn baseline 120 request/phút/IP.

Mã lỗi chung, mọi phản hồi lỗi đều là JSON dù client gửi `Accept` nào:

| HTTP | `code`                              | Khi nào                                           |
| ---- | ----------------------------------- | ------------------------------------------------- |
| 400  | `INVALID_REQUEST`                   | JSON hỏng, sai kiểu dữ liệu, thiếu tham số        |
| 400  | `INVALID_QUERY`                     | Từ khóa tìm kiếm không hợp lệ                     |
| 404  | `NOT_FOUND`                         | Không có route (kể cả `/error`)                   |
| 405  | `METHOD_NOT_ALLOWED`                | Sai phương thức                                   |
| 406  | `NOT_ACCEPTABLE`                    | Client chỉ nhận định dạng khác JSON               |
| 413  | `PAYLOAD_TOO_LARGE`                 | Body vượt 32 KiB                                  |
| 429  | `RATE_LIMITED`                      | Quá 120 request/phút từ một IP                    |
| 403  | `CSRF_INVALID`                      | Thiếu/sai CSRF token cho request thay đổi dữ liệu |
| 403  | `ACCESS_DENIED`                     | Request bị từ chối quyền                          |
| 500  | `INTERNAL_ERROR`                    | Lỗi chưa lường trước; tra log theo `requestId`    |
| 501  | `NOT_IMPLEMENTED`                   | Route đã dành chỗ, chưa có nghiệp vụ              |
| 401  | `SESSION_INVALID`                   | Access JWT thiếu/không hợp lệ/hết hạn             |
| 401  | `REFRESH_TOKEN_INVALID`             | Refresh token không hợp lệ/hết hạn/đã thu hồi     |
| 503  | `AUTH_NOT_CONFIGURED`               | Chế độ mock không có authentication provider      |
| 503  | `DATABASE_UNAVAILABLE`              | Không kết nối được PostgreSQL; thử lại sau        |
| 409  | `EMAIL_ALREADY_REGISTERED`          | Email đã được đăng ký                             |
| 409  | `OAUTH_IDENTITY_ALREADY_REGISTERED` | Tài khoản OAuth đã được đăng ký                   |

Lỗi 500 và 503 `DATABASE_UNAVAILABLE` được ghi log một dòng gồm `requestId`, phương thức, đường dẫn (không có query string), tên lớp ngoại lệ và vị trí trong code. Thông điệp của ngoại lệ không được ghi vì có thể chứa dữ liệu người dùng.

Hai trường hợp không qua khuôn lỗi trên vì bị chặn trước khi vào ứng dụng: URL mã hóa sai hoặc header quá dài (Tomcat trả trang 400 tối giản) và request có `Origin` không được phép (bộ lọc CORS trả 403 dạng văn bản).

## Các route đã chạy

| Method | Route                                                       | Kết quả                                                                       |
| ------ | ----------------------------------------------------------- | ----------------------------------------------------------------------------- |
| GET    | `/healthz`                                                  | Trạng thái tiến trình, không phải kiểm tra readiness của DB/LLM               |
| GET    | `/api/v1/modules`                                           | Sáu module và 19 UC                                                           |
| GET    | `/api/v1/{safety,knowledge,health,chat,accounts,lifestyle}` | Metadata module                                                               |
| GET    | `/api/v1/knowledge/articles?q=&topic=&limit=&offset=`         | Công khai, tìm và phân trang bài đã duyệt và xác minh                         |
| GET    | `/api/v1/knowledge/articles/:id`                             | Công khai, nội dung bài, nguồn, ngày cập nhật và ngày xác minh                 |
| GET    | `/api/v1/knowledge/facilities?q=`                           | Public: fixture synthetic hoặc tối đa 20 cơ sở có ngày xác minh từ PostgreSQL |

`q` là một chuỗi tối đa 200 ký tự, không chứa ký tự điều khiển (tab, xuống dòng, NUL…); sai thì trả 400 `INVALID_QUERY`. Danh sách và search metadata không chứa private user data. Fixture `[TEST]` chỉ dành kiểm thử; không trộn fixture vào kho kiến thức y tế thật.

Khi tìm bài viết, `topic` khớp toàn bộ tên chủ đề và không phân biệt hoa thường. `limit` từ 1 đến 20, mặc định 20. `offset` tính từ 0, mặc định 0. `meta.total` là số bài khớp bộ lọc trước khi phân trang. `meta.totalPages` bằng 0 nếu không có bài khớp. Danh sách chỉ trả tóm tắt và metadata. API chi tiết trả 404 cho ID không hợp lệ, bài không tồn tại hoặc bài chưa đủ điều kiện công bố.

## Đăng ký và xác thực tài khoản

`POST /accounts/register` (email/mật khẩu) bắt đầu quy trình đăng ký và gửi OTP qua SMTP; trả 202, chưa tạo account. `POST /accounts/register/verify` nhận email và OTP, chỉ tạo account/role/consent sau khi xác minh; mã có hiệu lực 10 phút, tối đa 5 lần nhập sai. `POST /accounts/register/resend` gửi mã mới sau cooldown 60 giây. OTP chỉ dùng cho email/mật khẩu, được lưu dạng HMAC hash và secret `EMAIL_OTP_HASH_SECRET` phải có ít nhất 32 byte. SMTP dùng `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `MAIL_FROM`.

`POST /accounts/login` chỉ nhận email/password local. `POST /accounts/oauth` nhận provider/token OAuth; identity đã tồn tại thì đăng nhập, còn identity mới phải kèm consent hợp lệ, được tạo thành tài khoản OAuth riêng và đăng nhập ngay. OAuth và local là hai phương thức độc lập, không có API liên kết tài khoản. Nếu email của OAuth đã thuộc local account, trả 409 `EMAIL_ALREADY_REGISTERED`, không tự liên kết.

Đăng nhập thành công đặt access JWT (tối đa 15 phút) và refresh token (tối đa 8 giờ) vào cookie HttpOnly; `POST /accounts/refresh` xoay refresh token. Thời hạn refresh token là 8 giờ tuyệt đối; access JWT không sống quá refresh token. `POST /accounts/logout` thu hồi refresh token và xóa cookie, nhưng access JWT đã cấp còn hiệu lực đến khi hết hạn (tối đa 15 phút). Cần `AUTH_TOKEN_SECRET` tối thiểu 32 byte. Cấu hình OAuth qua `GOOGLE_CLIENT_ID`, `FACEBOOK_APP_ID`, `FACEBOOK_APP_SECRET`; nếu thiếu, provider tương ứng không khả dụng.

Authentication dùng Spring Security stateless Resource Server: access JWT được xác minh từ cookie `HA_ACCESS`; ở `DATA_MODE=postgres`, mỗi request riêng tư kiểm tra tài khoản đang hoạt động và nạp quyền hiện hành từ database. Do trình duyệt tự gửi cookie, trước các request thay đổi dữ liệu hãy gọi `GET /api/v1/csrf`, rồi gửi giá trị `data.token` trong header `X-XSRF-TOKEN`; server cũng đặt cookie `XSRF-TOKEN`. Quy tắc này áp dụng cả đăng nhập, đăng ký, refresh và logout. Trong `DATA_MODE=mock`, route cần đăng nhập trả 503 `AUTH_NOT_CONFIGURED`.

`POST /accounts/forgot-password` trả cùng thông báo dù email không tồn tại, là tài khoản OAuth-only hay tài khoản local; chỉ local account có mật khẩu mới được gửi mã khôi phục. `POST /accounts/reset-password` nhận email, code và newPassword; mã 6 số hết hạn sau 10 phút, tối đa 5 lần thử, chỉ dùng một lần. Login local sai 5 lần trong 15 phút tạm khóa tài khoản 15 phút.

### UC-03 – Quản lý tài khoản cá nhân

- `GET /accounts/me` trả email, họ tên hiển thị, số điện thoại và URL ảnh đại diện của tài khoản hiện tại. `PUT /accounts/me` nhận `{ "displayName", "phoneNumber", "avatarUrl" }`; gửi `null` hoặc chuỗi rỗng cho số điện thoại/ảnh để xóa thông tin đó. URL ảnh chỉ chấp nhận HTTPS.
- `POST /accounts/me/change-password` nhận `{ "currentPassword", "newPassword" }`; yêu cầu mật khẩu hiện tại, mật khẩu mới từ 8 đến 72 byte UTF-8. Tài khoản OAuth-only chưa có mật khẩu không thể đổi qua API này. Khi đổi thành công, mọi refresh token bị thu hồi.
- `POST /accounts/me/deletion-request` yêu cầu mã xác nhận qua email hiện đăng ký. Mã 6 số có hiệu lực 10 phút, tối đa 5 lần nhập sai, cooldown gửi lại 60 giây; mã chỉ lưu dưới dạng HMAC hash.
- `POST /accounts/confirm-deletion` nhận `{ "code" }`, bắt buộc access JWT và CSRF. Mã hợp lệ xóa user cùng dữ liệu cá nhân liên quan trong một transaction và xóa cookie phiên. Reviewer ID và actor ID trong audit được ẩn danh để giữ lịch sử; mã sai/hết hạn không xóa tài khoản.
- ID tài khoản luôn lấy từ `sub` của JWT, không nhận từ request. JWT của tài khoản đã xóa bị từ chối ngay; tài khoản bị khóa vẫn có thể dùng access JWT còn hạn cho đến khi JWT hết hạn, trừ các API yêu cầu authority.

- `POST /accounts/me/consent` yêu cầu phiên đăng nhập và CSRF; ghi nhận consent cho chính chủ JWT. Body cần `termsAccepted`, `healthDataProcessingAccepted`, `nonDiagnosticAcknowledged`, `adultConfirmed` đều `true` và `termsVersion` hiện hành. API idempotent; tài khoản do admin tạo không được tự động cấp consent.

Các route riêng tư yêu cầu `HA_ACCESS`, xác minh chữ ký, issuer và hạn JWT qua Spring Security; thiếu/không hợp lệ trả 401 `SESSION_INVALID`. Refresh/logout kiểm tra trạng thái refresh token trong database. Header `Authorization`, `X-User-ID` và `X-Role` không thay thế cookie xác thực.

### UC-18 – Quản lý người dùng và phân quyền

Vai trò/permission hệ thống khởi tạo theo ma trận:

| Vai trò | Permission |
| ------- | ---------- |
| `GUEST` | `KNOWLEDGE_SEARCH`, `ACCOUNT_REGISTER` |
| `USER` | `KNOWLEDGE_SEARCH`, `ACCOUNT_LOGIN`, `ACCOUNT_SELF_MANAGE` |
| `CONTENT_EXPERT` | `KNOWLEDGE_SEARCH`, `ACCOUNT_LOGIN`, `ACCOUNT_SELF_MANAGE`, `CONTENT_MANAGE`, `SAFETY_RULE_MANAGE` |
| `ADMIN` | `KNOWLEDGE_SEARCH`, `ACCOUNT_LOGIN`, `ACCOUNT_SELF_MANAGE`, `CONTENT_MANAGE`, `USER_MANAGE`, `SYSTEM_STATS_VIEW`, `RBAC_MANAGE` |

Khách không cần tài khoản để tra cứu; đăng ký luôn tạo `USER`. Role/permission hệ thống có thể xem, role/permission do admin tạo có thể quản lý qua API. UC-18 kiểm tra admin đang hoạt động trực tiếp từ database; các API nghiệp vụ khác hiện giữ nguyên quy tắc authentication, chưa bật `@PreAuthorize`.

Admin bootstrap mặc định tắt; đặt `ADMIN_BOOTSTRAP_ENABLED=true` để khởi tạo admin từ `ADMIN_EMAIL` và `ADMIN_PASSWORD` (12–72 byte UTF-8); không có mật khẩu mặc định trong source. Nếu bật bootstrap và email đã thuộc tài khoản không phải admin đang hoạt động, ứng dụng dừng thay vì tự cấp quyền. Mỗi request JWT nạp authorities hiện hành từ database vào `SecurityContextHolder`: role có dạng `ROLE_<key>`, permission có dạng `PERM_<key>`. Thu hồi role ảnh hưởng ngay đến API yêu cầu authority. Khóa tài khoản chặn đăng nhập/refresh và thu hồi refresh token, nhưng access JWT còn hạn vẫn có thể gọi API riêng tư chỉ yêu cầu authentication; từ chối JWT cũ trên toàn bộ API sau khi khóa là phần việc UC-18 tiếp theo. Các route quản lý user yêu cầu `PERM_USER_MANAGE`; quản lý role/permission và audit yêu cầu `PERM_RBAC_MANAGE`. Permission của các API nghiệp vụ khác xem bảng route theo module bên dưới. Role không thể tự thu hồi vai trò ADMIN của chính mình hoặc xóa/khóa admin cuối cùng. Admin không thể xóa role đang được gán, permission đang được dùng, các role/permission hệ thống, hoặc sửa permission quản trị của role ADMIN.

| Method | Route | Body / kết quả |
| ------ | ----- | -------------- |
| GET | `/accounts/users?q=&status=&role=&page=&size=` | Tìm theo email/tên, lọc trạng thái/vai trò, phân trang tối đa 100 |
| POST | `/accounts/users` | Tạo tài khoản local `{email,displayName,password}`; tự gán `USER`, trả 201 |
| PATCH | `/accounts/users/{id}/status` | `{"status":"LOCKED"}` hoặc `ACTIVE`; khóa sẽ thu hồi refresh token |
| PUT | `/accounts/users/{id}/roles` | `{"roles":["USER","CONTENT_EXPERT"]}` thay toàn bộ vai trò |
| GET/POST | `/accounts/roles` | Liệt kê hoặc tạo role `{key,name,description,permissions}` |
| PATCH/DELETE | `/accounts/roles/{key}` | Cập nhật tên/mô tả hoặc xóa role không còn được gán |
| PUT | `/accounts/roles/{key}/permissions` | `{"permissions":["CONTENT_MANAGE"]}` thay toàn bộ permission |
| GET/POST | `/accounts/permissions` | Liệt kê hoặc tạo permission `{key,name,description}` |
| PATCH/DELETE | `/accounts/permissions/{key}` | Cập nhật hoặc xóa permission chưa được gán |
| GET | `/accounts/audit-logs?action=&targetType=&targetId=&page=&size=` | Nhật ký thao tác quản trị, lọc và phân trang |

Tất cả route UC-18 yêu cầu authority tương ứng (mặc định chỉ ADMIN có các permission quản trị); request ghi cần CSRF như các route cookie-auth khác. Tài khoản do admin tạo nhận trạng thái `ACTIVE` và role `USER`; không tự ghi nhận consent, mật khẩu được băm BCrypt và không xuất hiện trong response/log. `GET /accounts/users` chỉ trả email, tên hiển thị, trạng thái, vai trò và ngày tạo, không trả hồ sơ sức khỏe/hội thoại. Audit log ghi actor, thao tác, đối tượng, thay đổi trước/sau và thời điểm; không lưu mật khẩu, token hay body request. Lỗi đầu vào trả 400, tài khoản/role/permission không có trả 404, thiếu quyền trả `403 ACCESS_DENIED`; xung đột bảo vệ dữ liệu trả 409.

| Module | Routes sau `/api/v1`                                                                                                                                                                             | Quyền cần thực hiện                                                   |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| M1     | POST `/safety/evaluate`; GET/POST `/safety/rules`; GET/PATCH `/safety/rules/:id`; POST `/safety/rules/:id/submit`                                                                                | Authentication; quản lý quy tắc cần `PERM_SAFETY_RULE_MANAGE` (CONTENT_EXPERT) |
| M2     | POST `/knowledge/articles`; PATCH/DELETE `/knowledge/articles/:id`; GET `/knowledge/reviews`; POST `/knowledge/reviews/:id/decision`; GET `/knowledge/feedback`; PATCH `/knowledge/feedback/:id` | `PERM_CONTENT_MANAGE` (CONTENT_EXPERT và ADMIN)                 |
| M3     | GET/PUT/DELETE `/health/profile`; GET `/health/metrics`; PUT/DELETE `/health/metrics/:date`                                                                                                      | Dữ liệu của chính người dùng, consent                                 |
| M3     | GET `/health/statistics`                                                                                                                                                                         | `PERM_SYSTEM_STATS_VIEW` (ADMIN)                                      |
| M4     | POST `/chat/messages`; GET `/chat/sessions`; GET `/chat/sessions/:id/messages`; DELETE `/chat/sessions/:id`; POST `/chat/messages/:id/feedback`                                                  | `PERM_ACCOUNT_SELF_MANAGE`; dữ liệu theo chủ phiên; gửi chat cần consent, chia sẻ feedback là opt-in |
| M5     | POST `/accounts/logout`; GET/PUT `/accounts/me`; POST `/accounts/me/change-password`; POST `/accounts/me/deletion-request`; POST `/accounts/me/consent`; POST `/accounts/confirm-deletion`     | `PERM_ACCOUNT_SELF_MANAGE`; consent do chính chủ xác nhận; xóa cần email |
| M5     | GET/POST/PATCH `/accounts/users`; PUT `/accounts/users/:id/roles`; CRUD `/accounts/roles`, `/accounts/permissions`; GET `/accounts/audit-logs`                                                    | `PERM_USER_MANAGE` hoặc `PERM_RBAC_MANAGE`; không lộ hồ sơ/hội thoại  |
| M6     | GET `/lifestyle/templates`; GET/PUT `/lifestyle/plan`; PUT/DELETE `/lifestyle/completions/:activityId/:date`; GET/POST `/lifestyle/reminders`; PATCH/DELETE `/lifestyle/reminders/:id`           | `PERM_ACCOUNT_SELF_MANAGE`; dữ liệu theo chủ tài khoản, bắt buộc consent (trừ xem, tắt và xóa nhắc); template đã duyệt |

### M6 – UC-13 Theo dõi thói quen

Đã chạy ở `DATA_MODE=postgres` (mock trả 503 `AUTH_NOT_CONFIGURED`). User là chủ của access token; "hôm nay" là ngày theo múi giờ `LIFESTYLE_ZONE` (mặc định `Asia/Ho_Chi_Minh`), qua nửa đêm giờ đó là sang ngày mới. Kiểu dữ liệu: `TodayPlan`, `CompletionResult` trong `packages/contracts`.

| Route                                             | Kết quả                                                                                                                                        |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| GET `/lifestyle/plan`                             | Hoạt động hôm nay của lộ trình đang dùng, đã hoàn thành chưa, streak từng hoạt động, `planStreak` (số ngày liên tiếp hoàn thành mọi hoạt động) |
| PUT `/lifestyle/completions/:activityId/:date`    | Đánh dấu hoàn thành ngày `yyyy-mm-dd`, từ ngày bắt đầu lộ trình tới hôm nay. Gửi lặp không tạo bản ghi mới                                     |
| DELETE `/lifestyle/completions/:activityId/:date` | Bỏ đánh dấu, chỉ cho ngày hôm nay                                                                                                              |

Kết quả PUT/DELETE được đọc lại từ database sau khi ghi. Lỗi: 404 `LIFESTYLE_PLAN_NOT_FOUND` (chưa có lộ trình, chuyển sang UC-12), 404 `ACTIVITY_NOT_IN_PLAN`, 400 `INVALID_COMPLETION_DATE` (sai định dạng, tương lai, trước ngày bắt đầu), 409 `COMPLETION_LOCKED` (bỏ đánh dấu ngày cũ), 403 `CSRF_INVALID` khi thiếu `X-XSRF-TOKEN`.

### M6 – UC-12 Chọn lộ trình sinh hoạt

Cùng điều kiện như UC-13, thêm tiên điều kiện: consent mới nhất của người dùng phải đồng ý xử lý dữ liệu sức khỏe (đọc qua `accounts.api.ConsentQuery`). Dị ứng đọc qua `health.api.HealthProfileQuery`. Chỉ trả mẫu `APPROVED`. Mẫu `is_synthetic` chỉ hiện khi bật `SEED_SYNTHETIC`, và production luôn chặn seed. Kiểu dữ liệu: `TemplateCatalog`, `TemplatePreview`, `AppliedPlan` trong `packages/contracts`.

| Route                                | Kết quả                                                                                                                                                                                                                                                                         |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET `/lifestyle/templates?goal=`     | Mẫu đã duyệt mà người dùng được áp dụng; `goal` là `SLEEP`, `MAINTAIN`, `GAIN`, `LOSE` hoặc bỏ trống (mọi mục tiêu). Loại mẫu chứa dị ứng đã khai (A2). Chưa có hồ sơ: `profileMissing = true`, ẩn mẫu cần hồ sơ (A1). Danh sách rỗng kèm `message`, không tự tạo kế hoạch (A3) |
| GET `/lifestyle/templates/:id`       | Xem trước: mô tả, thời lượng, lịch hoạt động, đối tượng không phù hợp, nguồn, khuyến nghị hỏi nhân viên y tế, `current` (đang là lộ trình hiện tại). Không thay đổi gì (A4)                                                                                                     |
| GET `/lifestyle/plans/history` | Các lộ trình đã kết thúc của người dùng, mới nhất trước, tối đa `LIFESTYLE_HISTORY_LIMIT` (mặc định 100). Mỗi lộ trình: `templateTitle`, `startedOn`, `endedOn`, `days` (tính cả hai đầu), `activityCount`, `completedDays`, `completionCount`, `completionRate` (= completionCount / (days × activityCount), làm tròn 2 chữ số, null khi mẫu không có hoạt động). Chỉ đọc, cần consent. Số liệu vẫn đúng sau khi job dọn dữ liệu đã tóm tắt completion cũ |
| PUT `/lifestyle/plan` `{templateId}` | Áp dụng hoặc đổi lộ trình. Server kiểm tra lại mọi điều kiện. Đổi lộ trình đặt `ended_on` cho lộ trình cũ (giữ lịch sử và completion), mỗi người chỉ một lộ trình đang dùng. Gửi lại mẫu đang dùng thì `changed = false`. Trong cùng transaction, nhắc UC-14 đang bật gắn với hoạt động mà lộ trình mới không có sẽ bị tạm tắt (`disabledReason = PLAN_SWITCH`); `remindersTurnedOff` cho biết số nhắc đã tắt. `restorableReminderIds` liệt kê các nhắc bị tạm tắt ở lần đổi trước mà hoạt động có lại trong lộ trình này; server không tự bật lại, giao diện hỏi người dùng rồi gửi PATCH `{"enabled": true}`                                                        |

Lỗi: 403 `HEALTH_CONSENT_REQUIRED`, 400 `INVALID_GOAL`, 400 `INVALID_TEMPLATE_ID`, 404 `LIFESTYLE_TEMPLATE_NOT_FOUND` (không tồn tại, chưa duyệt hoặc không được hiển thị: cùng một câu trả lời để không lộ bản nháp), 409 `TEMPLATE_HAS_ALLERGEN` (A2), 409 `HEALTH_PROFILE_REQUIRED` (A1), 409 `LIFESTYLE_PLAN_CONFLICT` (hai yêu cầu đổi lộ trình cùng lúc).

### M6 – UC-14 Thiết lập nhắc nhở

Cùng điều kiện đăng nhập như UC-13; mỗi người chỉ thấy và sửa nhắc của mình. Tạo nhắc, bật lại hay sửa nhắc cần consent xử lý dữ liệu sức khỏe (403 `HEALTH_CONSENT_REQUIRED`). Khi đã rút consent, chủ nhắc vẫn xem danh sách, tắt bằng body chỉ có `{"enabled": false}` và xóa được; worker không gửi nhắc nào cho người không còn consent. Khi đổi lộ trình, nhắc gắn với hoạt động không còn trong lộ trình mới bị tắt; lần gửi trước đó giữ nguyên trong lịch sử. Nhắc độc lập, hoặc gắn hoạt động mà lộ trình mới cũng có (cùng mã), vẫn gửi bình thường. Muốn bật lại nhắc đã bị tắt vì lý do này thì gửi kèm `activityId` của lộ trình đang dùng hoặc `null`, nếu không sẽ nhận 404 `ACTIVITY_NOT_IN_PLAN` (trừ khi lộ trình đang dùng lại có hoạt động đó). Nhắc tạm tắt có `disabledReason = PLAN_SWITCH` và không tính vào `REMINDERS_MAX_PER_USER`; bật lại thì lý do về `null` và nhắc được tính lại vào giới hạn, nên có thể nhận 409 `REMINDER_LIMIT_REACHED`. Tắt bằng tay một nhắc đang tạm tắt thì nhắc vẫn giữ lý do `PLAN_SWITCH`. Kiểu dữ liệu: `ReminderList`, `Reminder`, `ReminderInput` trong `packages/contracts`.

| Route                             | Kết quả                                                                                                                                                                              |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| GET `/lifestyle/reminders`        | Nhắc của người dùng, lần gửi kế tiếp `nextSendAt` (null khi tắt), lần gửi gần nhất `lastDelivery` (`PENDING`, `SENT`, `FAILED`) và `availableChannels` (kênh bật được ngay, A2)      |
| POST `/lifestyle/reminders`       | Tạo nhắc, trả 201. Bắt buộc `localTime` và `content` (hoặc `activityId`, khi đó tiêu đề hoạt động là nội dung). Mặc định: mọi ngày, múi giờ `LIFESTYLE_ZONE`, kênh `EMAIL`, đang bật |
| PATCH `/lifestyle/reminders/:id`  | Chỉ đổi các trường gửi lên. `{"enabled": false}` dừng mọi lần gửi kế tiếp. Lần gửi có giờ trước thời điểm lưu không được gửi                                                         |
| DELETE `/lifestyle/reminders/:id` | Xóa nhắc cùng nhật ký gửi                                                                                                                                                            |

Trường: `content` 1–200 ký tự, không có ký tự điều khiển; `localTime` dạng 24 giờ `HH:mm`; `daysOfWeek` danh sách khác rỗng các số 1 (thứ Hai) đến 7 (Chủ nhật); `timezone` mã múi giờ IANA; `channel` `EMAIL` hoặc `WEB_PUSH`; `activityId` mã hoạt động của lộ trình đang dùng hoặc `null`; `enabled`. Thời điểm trong kết quả là ISO-8601 UTC.

Gửi nhắc: worker trong backend chạy mỗi `REMINDERS_POLL` (mặc định 1 phút), giữ chỗ từng lần gửi trong `lifestyle.reminder_deliveries` (UNIQUE `reminder_id, scheduled_for`) rồi mới gửi, nên chạy lại, thử lại hay nhiều backend cùng lúc không gửi trùng. Lần gửi lỗi ghi `FAILED` kèm mã lỗi và được thử lại trên cùng dòng, tối đa `REMINDERS_MAX_ATTEMPTS` lần (A3). Nhắc trễ quá `REMINDERS_WINDOW` (mặc định 10 phút) thì bỏ qua. Trước khi giữ chỗ, worker đọc consent của người dùng; không còn consent thì bỏ qua cả lần gửi mới lẫn lần thử lại và không ghi dòng gửi. Email dùng `SMTP_*` và `MAIL_FROM` như M5; địa chỉ đọc qua `accounts.api.AccountContactQuery`. `REMINDERS_ENABLED=false` chỉ dừng việc gửi.

Lỗi: 403 `HEALTH_CONSENT_REQUIRED`, 400 `INVALID_REMINDER` (A1, `message` nói trường nào sai; trường lạ cũng bị từ chối), 404 `REMINDER_NOT_FOUND` (không tồn tại hoặc của người khác), 404 `ACTIVITY_NOT_IN_PLAN`, 409 `REMINDER_CHANNEL_UNAVAILABLE` (A2: kênh chưa cấu hình hoặc chưa được phép; Web Push chưa có nên luôn trả lỗi này khi bật), 409 `REMINDER_LIMIT_REACHED` (quá `REMINDERS_MAX_PER_USER`, mặc định 20, không tính nhắc tạm tắt do đổi lộ trình).

Đây là route ban đầu để chia việc, không phải danh sách API cuối. Chi tiết bài viết, form mẫu kế hoạch, email/OAuth, hàng đợi duyệt và các nhánh UC cần được chủ module bổ sung, cập nhật tài liệu và contract. Không đánh dấu một UC hoàn thành chỉ vì route đã tồn tại.
