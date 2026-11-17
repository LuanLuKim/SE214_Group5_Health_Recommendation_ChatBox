# M5 Tài khoản & phân quyền

Use case: **UC-01, UC-02, UC-03, UC-18**. Phụ trách: Võ Ngọc Tài (`vongoctaijk-sketch`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/accounts/` (`web/`, `auth/`, `oauth/`, `registration/`, `persistence/`, `config/`)
- Frontend: `apps/web/src/modules/accounts/`
- Migration: `database/migrations/` (V005__accounts.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

Spring Security Resource Server xác thực cookie access JWT và đặt `Jwt` trong `SecurityContext`. Các module vẫn phải tự kiểm tra đồng ý và quyền sở hữu trong nghiệp vụ; phân quyền trên các API nghiệp vụ ngoài UC-18 hiện chưa được bật.

Interface trong package `accounts.api`, hiện có bản đọc tạm trong `accounts/persistence` do M6 thêm (M5 tiếp quản):

- `ConsentQuery`: consent mới nhất có đồng ý xử lý dữ liệu sức khỏe không (UC-12).
- `AccountContactQuery`: email của tài khoản đang hoạt động, rỗng khi không có hoặc bị khóa (UC-14 gửi nhắc nhở).

## UC-01: đăng ký tài khoản

- `POST /api/v1/accounts/register`: bắt đầu đăng ký email/mật khẩu; gửi OTP 6 số qua SMTP, trả 202 và thời hạn xác nhận. Chưa tạo account ở bước này.
- `POST /api/v1/accounts/register/verify`: xác nhận `{ "email": "...", "code": "..." }`; OTP hợp lệ mới tạo account, role và consent trong cùng transaction.
- `POST /api/v1/accounts/register/resend`: gửi lại `{ "email": "..." }`; có cooldown 60 giây.
- Cả hai yêu cầu `termsAccepted`, `healthDataProcessingAccepted`, `nonDiagnosticAcknowledged`, `adultConfirmed` đều phải là `true`, và `termsVersion` phải khớp cấu hình `TERMS_VERSION` (mặc định local là `v1`).
- Email được trim, chuyển chữ thường và kiểm tra định dạng. Mật khẩu được băm BCrypt; role mới luôn `USER`. Account và consent được lưu cùng một transaction.
- OTP email/password có hiệu lực 10 phút, tối đa 5 lần nhập sai; mã không lưu dạng rõ và challenge hết hạn được dọn định kỳ. Cấu hình `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `MAIL_FROM` và `EMAIL_OTP_HASH_SECRET` trong backend environment. Secret HMAC phải có ít nhất 32 byte; không dùng giá trị mẫu ở môi trường thật.
- Chế độ `DATA_MODE=mock` không có persistence nên đăng ký trả `503 REGISTRATION_UNAVAILABLE`. Chạy PostgreSQL mode để dùng chức năng.

## UC-02: đăng nhập và khôi phục

- `POST /api/v1/accounts/login`: chỉ đăng nhập local bằng `{ "email", "password" }`.
- `POST /api/v1/accounts/oauth`: xác minh Google ID token/Facebook token. Identity đã tồn tại thì đăng nhập; identity mới thì yêu cầu consent/đủ tuổi, tạo tài khoản OAuth riêng rồi đăng nhập luôn. Không liên kết tài khoản local theo email. Nếu email đã được dùng bởi local account, trả xung đột và yêu cầu dùng tài khoản local.
- Đăng nhập thành công cấp access JWT tối đa 15 phút và refresh token tối đa 8 giờ qua cookie `HttpOnly`, `SameSite=Lax`; access JWT không sống quá thời hạn tuyệt đối của refresh token. Refresh token chỉ lưu dạng hash và được xoay ở `POST /api/v1/accounts/refresh`.
- Spring Security Resource Server xác minh chữ ký, issuer và hạn JWT từ cookie `HA_ACCESS`, không đọc database theo từng request. Route riêng tư được bảo vệ ở filter chain; `POST /api/v1/accounts/logout` chỉ cần refresh token và CSRF token, thu hồi refresh token hiện tại và xóa cookie. Access JWT đã cấp vẫn dùng được đến khi hết hạn (tối đa 15 phút).
- Với request thay đổi dữ liệu từ trình duyệt, gọi `GET /api/v1/csrf`, rồi gửi `data.token` trong header `X-XSRF-TOKEN`; server đặt cookie `XSRF-TOKEN`. CSRF protection áp dụng cả các endpoint đăng nhập, đăng ký, refresh và logout.
- Sau 5 lần đăng nhập sai trong 15 phút, tài khoản bị khóa đăng nhập tạm 15 phút. Lỗi thông tin đăng nhập không tiết lộ tài khoản có tồn tại hay đang khóa.
- `POST /api/v1/accounts/forgot-password` chỉ tạo challenge/gửi mã cho tài khoản local có mật khẩu; response vẫn chung cả khi email không tồn tại hoặc là tài khoản OAuth-only. Mã hết hạn sau 10 phút, tối đa 5 lần nhập sai, cooldown 60 giây và chỉ dùng một lần.
- `POST /api/v1/accounts/reset-password` nhận email, code và newPassword; chỉ đặt lại mật khẩu local, xóa challenge và thu hồi mọi refresh token của tài khoản. Access JWT đã cấp hết hiệu lực tự nhiên, tối đa sau 15 phút.
- Cần `AUTH_TOKEN_SECRET` có tối thiểu 32 byte; không đưa giá trị thật vào Git. OAuth và khôi phục email chỉ dùng được ở `DATA_MODE=postgres`.

## UC-03: quản lý tài khoản cá nhân

- `GET/PUT /api/v1/accounts/me` đọc/cập nhật `displayName`, `phoneNumber` và `avatarUrl` của chính chủ. URL ảnh chỉ nhận HTTPS. ID chủ tài khoản lấy từ subject JWT, không nhận từ request.
- `POST /api/v1/accounts/me/change-password` cần mật khẩu hiện tại; mật khẩu mới tuân theo giới hạn 8–72 byte UTF-8. Tài khoản OAuth-only không có mật khẩu local để xác minh.
- `POST /api/v1/accounts/me/deletion-request` gửi OTP email 6 số (10 phút, tối đa 5 lần thử, cooldown 60 giây); `POST /api/v1/accounts/confirm-deletion` xác nhận OTP, xóa tài khoản và dữ liệu liên quan theo transaction, đồng thời xóa cookie phiên.
- OTP chỉ lưu hash HMAC. Audit và lịch sử rà soát được giữ nhưng xóa liên kết định danh của người đã xóa tài khoản. Access JWT của tài khoản đã xóa bị từ chối ngay; JWT còn hạn của tài khoản bị khóa có thể tiếp tục gọi API chỉ yêu cầu authentication đến khi hết hạn (UC-18 follow-up).

Đăng ký local không đăng nhập người dùng hay cấp token. OAuth sign-in tự tạo tài khoản mới nếu cần và cấp token ngay.

## UC-18: người dùng, vai trò và permission

- `GET /api/v1/accounts/users?q=&status=&role=&page=&size=` tìm/lọc tài khoản; chỉ trả thông tin tài khoản cơ bản và vai trò.
- `POST /api/v1/accounts/users` cho người có `PERM_USER_MANAGE` tạo local account `{email,displayName,password}`; mật khẩu được băm, tài khoản nhận role `USER`, không tự ghi nhận consent.
- `PATCH /api/v1/accounts/users/{id}/status` khóa/mở khóa bằng `{"status":"LOCKED"}` hoặc `ACTIVE`; khóa chặn đăng nhập/refresh và thu hồi refresh token, access JWT đã cấp còn hiệu lực tối đa 15 phút. `PUT /api/v1/accounts/users/{id}/roles` thay danh sách role.
- `GET/POST /api/v1/accounts/roles`, `PATCH/DELETE /roles/{key}` và `PUT /roles/{key}/permissions` quản lý role và permission được gán.
- `GET/POST /api/v1/accounts/permissions`, `PATCH/DELETE /permissions/{key}` quản lý permission; permission đang gán không thể xóa.
- `GET /api/v1/accounts/audit-logs` xem nhật ký UC-18 có lọc và phân trang. Log không chứa password, token, body, hồ sơ sức khỏe hoặc hội thoại.
- Ma trận hệ thống: `GUEST` tra cứu kiến thức công khai; `USER` quản lý tài khoản/dữ liệu của mình; `CONTENT_EXPERT` quản lý nội dung và quy tắc an toàn; `ADMIN` quản lý người dùng/RBAC/thống kê và nội dung. Role `GUEST` chỉ là vai trò mô tả quyền truy cập công khai, không cần tạo tài khoản để tra cứu.
- Permission seed gồm: `ACCOUNT_REGISTER` (GUEST), `ACCOUNT_LOGIN` (USER/CONTENT_EXPERT/ADMIN), `KNOWLEDGE_SEARCH` (mọi vai trò), `ACCOUNT_SELF_MANAGE` (USER/CONTENT_EXPERT/ADMIN), `CONTENT_MANAGE` (CONTENT_EXPERT/ADMIN), `SAFETY_RULE_MANAGE` (CONTENT_EXPERT), `USER_MANAGE`, `SYSTEM_STATS_VIEW`, `RBAC_MANAGE` (ADMIN).
- Đăng ký email và OAuth chỉ gán `USER`; người dùng không thể tự chọn quyền cao. Vai trò bổ sung do admin tạo và cấp.
- Với mỗi request JWT, `DatabaseJwtAuthenticationConverter` nạp authorities hiện hành từ database vào `SecurityContextHolder`: `ROLE_<key>` và `PERM_<key>`. Filter chain yêu cầu `PERM_USER_MANAGE` cho API user và `PERM_RBAC_MANAGE` cho API roles/permissions/audit; mặc định các permission này chỉ được cấp cho ADMIN. Thu hồi role ảnh hưởng API yêu cầu authority ở request kế tiếp. Khóa tài khoản chặn login/refresh và thu hồi refresh token, nhưng chưa vô hiệu hóa JWT còn hạn trên các API chỉ yêu cầu authentication; đây là phần việc UC-18 follow-up. Các route nghiệp vụ khác chưa áp dụng permission; giữ nguyên authentication như trước.
- Không thể tự thu hồi role ADMIN, xóa/khóa admin cuối cùng, sửa quyền của role ADMIN, xóa role đang được gán, hay xóa permission đang sử dụng. Role/permission có sẵn của hệ thống không thể xóa; permission built-in không thể sửa.
- Admin bootstrap mặc định tắt. Khi muốn khởi tạo admin ở `DATA_MODE=postgres`, bật `ADMIN_BOOTSTRAP_ENABLED=true` và cấu hình `ADMIN_EMAIL`, `ADMIN_PASSWORD` dài 12–72 byte UTF-8. Không có thông tin đăng nhập mặc định trong source. Nếu email đã thuộc tài khoản không phải admin đang hoạt động, startup thất bại để tránh nâng quyền nhầm.

## Hiện trạng và ranh giới

M5 sở hữu ranh giới xác thực chung qua Spring Security Resource Server và filter chain; access JWT thông thường không truy vấn database theo từng request. Refresh/logout kiểm tra trạng thái refresh token trong database. Trong `DATA_MODE=mock`, route cần authentication trả 503 do không có provider. UC-19 thuộc M3.

## Cách mở rộng

- `web/` chứa controller; `auth/` chứa đăng nhập, token và khôi phục mật khẩu; `oauth/` chứa tích hợp OAuth; `registration/` chứa đăng ký và OTP email; `persistence/` chứa repository; `config/` chứa cấu hình. Controller chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong service; SQL trong repository (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư được bảo vệ tập trung trong `shared/web/SpringSecurityConfiguration`. Lỗi trả cùng JSON envelope với `requestId`; không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../accounts/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
