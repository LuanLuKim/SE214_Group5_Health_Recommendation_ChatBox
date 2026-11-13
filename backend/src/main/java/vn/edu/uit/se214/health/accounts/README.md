# M5 Tài khoản & phân quyền

Use case: **UC-01, UC-02, UC-03, UC-18**. Phụ trách: Võ Ngọc Tài (`vongoctaijk-sketch`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/accounts/`, bắt đầu từ `AccountsController.java`
- Frontend: `apps/web/src/modules/accounts/`
- Migration: `database/migrations/` (V005__accounts.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

(sẽ có) phiên đăng nhập, vai trò, đồng ý xử lý dữ liệu cho mọi module

## UC-01: đăng ký tài khoản

- `POST /api/v1/accounts/register`: bắt đầu đăng ký email/mật khẩu; gửi OTP 6 số qua SMTP, trả 202 và thời hạn xác nhận. Chưa tạo account ở bước này.
- `POST /api/v1/accounts/register/verify`: xác nhận `{ "email": "...", "code": "..." }`; OTP hợp lệ mới tạo account, role và consent trong cùng transaction.
- `POST /api/v1/accounts/register/resend`: gửi lại `{ "email": "..." }`; có cooldown 60 giây.
- `POST /api/v1/accounts/register/oauth`: đăng ký Google hoặc Facebook bằng credential lấy từ SDK/provider; backend xác minh credential trực tiếp với provider trước khi tạo tài khoản.
- Cả hai yêu cầu `termsAccepted`, `healthDataProcessingAccepted`, `nonDiagnosticAcknowledged`, `adultConfirmed` đều phải là `true`, và `termsVersion` phải khớp cấu hình `TERMS_VERSION` (mặc định local là `v1`).
- Email được trim, chuyển chữ thường và kiểm tra định dạng. Mật khẩu được băm BCrypt; role mới luôn `USER`. Account, consent và OAuth identity được lưu cùng một transaction.
- OAuth request dùng Google ID token hoặc Facebook user access token. Cấu hình `GOOGLE_CLIENT_ID`, `FACEBOOK_APP_ID` và `FACEBOOK_APP_SECRET` trong backend environment. Nếu provider không khả dụng, endpoint trả lỗi an toàn và không tạo account.
- Khi email đã thuộc account khác, OAuth registration bị từ chối; không tự liên kết tài khoản theo email.
- OTP email/password có hiệu lực 10 phút, tối đa 5 lần nhập sai; mã không lưu dạng rõ và challenge hết hạn được dọn định kỳ. Cấu hình `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `MAIL_FROM` và `EMAIL_OTP_HASH_SECRET` trong backend environment. Secret HMAC phải có ít nhất 32 byte; không dùng giá trị mẫu ở môi trường thật.
- Chế độ `DATA_MODE=mock` không có persistence nên đăng ký trả `503 REGISTRATION_UNAVAILABLE`. Chạy PostgreSQL mode để dùng chức năng.

Đăng ký không đăng nhập người dùng hay cấp session. Login, session, password recovery, xác nhận email/xóa tài khoản và quản trị vai trò vẫn là các phần việc riêng.

## Hiện trạng và ranh giới

M5 sở hữu ranh giới xác thực chung: thay `shared/web/SessionGuard` (hiện luôn trả 503 cho route có `@RequiresSession`) bằng kiểm tra phiên thật, ví dụ Spring Security. Đăng ký mới không được tự chọn vai trò ADMIN hay CONTENT_EXPERT. UC-18 thuộc M5, UC-19 thuộc M3.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư đánh dấu `@RequiresSession`. Lỗi trả qua `ApiException` (mã và thông điệp tiếng Việt an toàn); không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../accounts/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
