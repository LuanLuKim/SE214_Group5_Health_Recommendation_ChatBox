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

## Hiện trạng và ranh giới

M5 sở hữu ranh giới xác thực chung: thay `shared/web/SessionGuard` (hiện luôn trả 503 cho route có `@RequiresSession`) bằng kiểm tra phiên thật, ví dụ Spring Security. Đăng ký mới không được tự chọn vai trò ADMIN hay CONTENT_EXPERT. UC-18 thuộc M5, UC-19 thuộc M3.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư đánh dấu `@RequiresSession`. Lỗi trả qua `ApiException` (mã và thông điệp tiếng Việt an toàn); không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../accounts/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
