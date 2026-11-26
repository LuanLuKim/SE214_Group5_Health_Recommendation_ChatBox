# M3 Hồ sơ, chỉ số & thống kê

Use case: **UC-04, UC-05, UC-19**. Phụ trách: Vũ Quang Dũng (`Dungdayyy`) cho UC-04, UC-05; Lữ Kim Luân (`LuanLuKim`) cho UC-19 và ghi sự kiện thống kê (SCRUM-51).

## Vị trí

- Backend (Spring Boot): `backend/src/main/health/`, bắt đầu từ `HealthController.java`
- Frontend: `apps/web/src/modules/health/`
- Migration: `database/migrations/` (V003__health.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

- `api.UsageEventRecorder` và `api.UsageEventType` (UC-19): ghi một sự kiện tổng hợp (chỉ loại và thời điểm). Thường không cần gọi: `UsageEventFilter` tự đếm từ phản hồi thành công của các route trong `app.health.statistics.event-routes`, nên code M4, M5 không phải sửa. Gọi trực tiếp khi route không cho biết được sự kiện, ví dụ M5 tạo tài khoản mới trong lúc đăng nhập OAuth: `recorder.record(UsageEventType.REGISTRATION)`. Lỗi ghi không bao giờ làm hỏng luồng của bên gọi.

## Hiện trạng và ranh giới

`BmiCalculator` (UC-04) đã có kèm test. Hồ sơ và chỉ số theo ngày ở đây; xu hướng tuần/tháng (UC-05) và thống kê hệ thống (UC-19) tính ở `services/stats` (Django), gọi qua `PythonServiceClient` có `@Qualifier("statsService")`. Thống kê cho Quản trị viên chỉ có số liệu tổng hợp, không có dữ liệu sức khỏe cá nhân.

UC-19 (`SystemStatisticsController`, `SystemStatisticsService`, `UsageEventRepository`, `UsageEventFilter`, cấu hình `UsageStatisticsConfig`): Spring Boot đếm `health.usage_events` theo ngày giờ Việt Nam, gửi ngày và số đếm cho Django để điền ngày trống và cộng tổng. Đặc tả route và lỗi ở `docs/API.md`, mục UC-19.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư được bảo vệ bởi Spring Security filter chain. Lỗi trả cùng JSON envelope và request ID; không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `tests/backend/java/.../health/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
