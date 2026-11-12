# M1 Safety Gate & quy tắc cảnh báo

Use case: **UC-07, UC-16 (+ phần M1 của UC-17)**. Phụ trách: Bảo Quỳnh (`DontMadWhenBad`).

## Vị trí

- Backend (Spring Boot): `backend/src/main/java/vn/edu/uit/se214/health/safety/`, bắt đầu từ `SafetyController.java`
- Frontend: `apps/web/src/modules/safety/`
- Migration: `database/migrations/` (V001__safety.sql, V007__safety_drafts.sql)
- Kiểu dữ liệu cho frontend: `packages/contracts/src/index.ts`
- Kiến trúc và công nghệ: `docs/TECH_STACK.md`, `docs/MODULES.md`

## Giao diện công khai cho module khác

`api/SafetyEvaluator` (M4 gọi trước mọi câu trả lời AI), `api/SafetyDecision`, `api/CareLevel`

## Hiện trạng và ranh giới

Bộ đánh giá Safety Gate thật đặt trong `services/ai` (FastAPI); module này giữ quy tắc, nháp UC-16 (`RuleDraftService`, `RuleRepository`) và lớp gọi sang FastAPI. Chưa có quy tắc được duyệt thì trả `UNAVAILABLE`, không bao giờ tự trả mức thấp.

Điểm nạp quy tắc duy nhất cho Safety Gate là `RuleRepository.listEvaluable()` (UC-07): chỉ quy tắc `APPROVED`, đang bật, đã xác minh nguồn và không phải dữ liệu giả lập. Nháp UC-16 không bao giờ tự vào tập này: tạo, sửa, gửi duyệt chỉ đổi sang `DRAFT`/`PENDING_REVIEW`, không đụng `is_active`. Bộ quy tắc mẫu cho test (đủ 5 mức, đủ 5 trạng thái) ở `backend/src/test/resources/safety/sample-rules.sql`; seed dev không chứa quy tắc đã duyệt.

Phần M1 của UC-17 (kiểm duyệt đầu vào): cài `knowledge.review.ReviewSubjectHandler` cho `RULE` và chunk M7, cắm vào hàng đợi duyệt chung của M2. Không làm hàng đợi hay màn hình duyệt riêng.

## Cách mở rộng

- `*Controller` chỉ đọc HTTP và trả `ApiResponse`; nghiệp vụ trong `*Service`; SQL trong `*Repository` (dùng `JdbcClient`, luôn truyền tham số, không nối chuỗi giá trị người dùng).
- Module khác chỉ dùng kiểu trong package `api` (hoặc interface công khai) của module này, không gọi repository hay bảng của nhau.
- Route riêng tư đánh dấu `@RequiresSession`. Lỗi trả qua `ApiException` (mã và thông điệp tiếng Việt an toàn); không ghi body, hồ sơ, prompt hay token vào log.
- Thay đổi cơ sở dữ liệu: thêm migration mới `VNNN__<module>_<mô-tả>.sql`, không sửa migration đã áp dụng.
- Test đặt ở `backend/src/test/java/.../safety/`; test cần cơ sở dữ liệu dùng `support/TestDatabase` (PostgreSQL nhúng, không cần Docker). Chạy `mvn -f backend/pom.xml verify`.
