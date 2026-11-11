# M3 Hồ sơ, chỉ số & thống kê

Use case: **UC-04, UC-05, UC-19**. Phụ trách: Vũ Quang Dũng (`Dungdayyy`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/health/`, bắt đầu từ `HealthController.java`
- Frontend: `apps/web/src/modules/health/`
- Migration: `database/migrations/` (V003__health.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

(chưa có)

## Hiện trạng và ranh giới

`BmiCalculator` (UC-04) đã có kèm test. Hồ sơ và chỉ số theo ngày ở đây; xu hướng tuần/tháng (UC-05) và thống kê hệ thống (UC-19) tính ở `services/stats` (Django), gọi qua `PythonServiceClient` có `@Qualifier("statsService")`. Thống kê cho Quản trị viên chỉ có số liệu tổng hợp, không có dữ liệu sức khỏe cá nhân.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư đánh dấu `@RequiresSession`. Lỗi trả qua `ApiException` (mã và thông điệp tiếng Việt an toàn); không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../health/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
