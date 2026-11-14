# M5 Tài khoản & phân quyền

Use case: **UC-01, UC-02, UC-03, UC-18**. Phụ trách: Võ Ngọc Tài (`vongoctaijk-sketch`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/accounts/` (`web/`, `auth/`, `oauth/`, `registration/`, `persistence/`, `config/`)
- Frontend: `apps/web/src/modules/accounts/`
- Migration: `database/migrations/` (V005__accounts.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

Spring Security Resource Server xác thực cookie access JWT và đặt `Jwt` trong `SecurityContext`. Các module vẫn phải tự kiểm tra vai trò, đồng ý và quyền sở hữu trong nghiệp vụ; vai trò quản trị chưa được triển khai.

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

Đăng ký local không đăng nhập người dùng hay cấp token. OAuth sign-in tự tạo tài khoản mới nếu cần và cấp token ngay. Xác nhận xóa tài khoản, quản lý hồ sơ và quản trị vai trò vẫn là phần việc riêng.

## Hiện trạng và ranh giới

M5 sở hữu ranh giới xác thực chung qua Spring Security Resource Server và filter chain; không đọc database theo từng request để xác minh access JWT. Refresh/logout kiểm tra trạng thái refresh token trong database. Trong `DATA_MODE=mock`, route cần authentication trả 503 do không có provider. Hiện chưa có phân quyền role; đăng ký mới không được tự chọn vai trò ADMIN hay CONTENT_EXPERT. UC-18 thuộc M5, UC-19 thuộc M3.

## Cách mở rộng

- `web/` chứa controller; `auth/` chứa đăng nhập, token và khôi phục mật khẩu; `oauth/` chứa tích hợp OAuth; `registration/` chứa đăng ký và OTP email; `persistence/` chứa repository; `config/` chứa cấu hình. Controller chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong service; SQL trong repository (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư được bảo vệ tập trung trong `shared/web/SpringSecurityConfiguration`. Lỗi trả cùng JSON envelope với `requestId`; không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../accounts/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
