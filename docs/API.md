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

## Quản lý bản nháp bài viết (UC-15)

Các route dưới đây đã có nghiệp vụ PostgreSQL; truy cập qua ứng dụng hiện vẫn trả `503 AUTH_NOT_CONFIGURED` cho đến khi M5 cung cấp xác thực. M5 truyền `ContentAuthenticator` ở tham số thứ ba của `createKnowledgeRouter`: resolver phải xác thực phiên thật, trạng thái tài khoản và trả `{ userId, roles }` từ server hoặc `null` nếu chưa đăng nhập. Không lấy vai trò từ body, header giả hay query. `CONTENT_EXPERT` và `ADMIN` được quản lý nội dung; `USER` bị từ chối với `403 FORBIDDEN`. Khi resolver đã được nối, chưa đăng nhập trả `401 UNAUTHENTICATED`. Nếu chưa có database, người đã được xác thực trả `503 DATABASE_NOT_CONFIGURED`; không lưu bản nháp vào fixture mock.

| Method | Route sau `/api/v1/knowledge` | Kết quả                                                                     |
| ------ | ----------------------------- | --------------------------------------------------------------------------- |
| GET    | `/authoring/articles`         | Danh sách quản trị tất cả trạng thái, sắp theo thời gian tạo giảm dần và ID |
| GET    | `/authoring/articles/:id`     | Nội dung và metadata quản trị của một bài                                   |
| POST   | `/articles`                   | Tạo bài `DRAFT`, trả `201`                                                  |
| PATCH  | `/articles/:id`               | Sửa các trường được truyền của bài `DRAFT` hoặc `RETURNED`                  |
| DELETE | `/articles/:id`               | Xóa bài `DRAFT` hoặc `RETURNED`, trả `204`                                  |
| POST   | `/articles/:id/submit`        | Kiểm tra nội dung và gửi sang `PENDING_REVIEW`, trả `200`                   |

POST bắt buộc `title` (1–300 ký tự), `topic` (1–200), `content` (1–30000), `sourceName` (1–200) và `sourceUrl` (URL HTTP/HTTPS tối đa 2000 ký tự, không chứa tài khoản/mật khẩu). Trường tùy chọn: `sourceUpdatedAt` (ngày ISO `YYYY-MM-DD`, timestamp ISO có timezone, hoặc `null`) và `copyrightNotes` (tối đa 3000 ký tự). PATCH dùng cùng các trường, cần ít nhất một trường. Giới hạn request JSON chung 32 KiB vẫn áp dụng. Client không được truyền `reviewStatus`, `verifiedAt`, `createdBy`, `updatedBy` hoặc `isSynthetic`; trường ngoài schema bị từ chối với `400 INVALID_INPUT`.

Ví dụ body tạo bài tổng hợp:

```json
{
  "title": "[TEST] Bài nháp thử nghiệm",
  "topic": "Kiểm thử",
  "content": "Văn bản giả lập, không có hướng dẫn y tế.",
  "sourceName": "Synthetic fixture",
  "sourceUrl": "https://example.invalid/article",
  "sourceUpdatedAt": "2026-10-29",
  "copyrightNotes": "Nội dung tổng hợp để kiểm thử."
}
```

Response thành công bọc `ArticleDraft` trong `data`, gồm các trường đầu vào cùng `id`, `verifiedAt`, `reviewStatus`, `createdBy`, `updatedBy`, `createdAt`, `updatedAt`; ngày giờ trả theo ISO. Danh sách có thêm `meta.total`. Metadata người tạo/sửa do phiên server xác định. Bài mới có `verifiedAt = null`; sửa bài được trả về sẽ đặt lại `DRAFT` và xóa ngày xác minh cũ. Bài không tồn tại/UUID sai trả `404 NOT_FOUND`; sửa/xóa bài `PENDING_REVIEW`, `APPROVED` hoặc `RETIRED` trả `409 CONTENT_NOT_EDITABLE`. Chuyển trạng thái được chặn ngay trong câu lệnh ghi database.

Migration `009_knowledge_article_drafts.sql` giữ nguyên các cột trong `NormalizedArticle.to_db_row()` của M7 và thêm metadata với giá trị mặc định. `db_row` dùng tên cột snake_case, còn API dùng camelCase (`source_name` → `sourceName`, `source_url` → `sourceUrl`). Đầu ra crawler không phải body API trực tiếp: loại các trường do server quản lý; không tin trạng thái duyệt/ngày xác minh từ dữ liệu nhập. `fetched_at` là ngày thu thập, không phải ngày xác minh. UC-15 phase 1 chưa cung cấp endpoint nhập file crawler hoặc phê duyệt; bài chưa duyệt tiếp tục bị loại khỏi tra cứu công khai.

## Mẫu kế hoạch và gửi duyệt (UC-15, phase 2)

Các route mẫu dùng cùng resolver M5, quyền CONTENT_EXPERT/ADMIN và PostgreSQL như bài viết. Chưa cấu hình xác thực vẫn trả `503 AUTH_NOT_CONFIGURED`.

| Method | Route sau `/api/v1/knowledge` | Kết quả                                      |
| ------ | ----------------------------- | -------------------------------------------- |
| GET    | `/authoring/templates`        | Danh sách quản trị mọi trạng thái            |
| GET    | `/authoring/templates/:id`    | Chi tiết và metadata quản trị                |
| POST   | `/templates`                  | Tạo mẫu `DRAFT`, trả `201`                   |
| PATCH  | `/templates/:id`              | Sửa một phần mẫu `DRAFT` hoặc `RETURNED`     |
| DELETE | `/templates/:id`              | Xóa mẫu nháp chưa được tham chiếu, trả `204` |
| POST   | `/templates/:id/submit`       | Gửi sang `PENDING_REVIEW`, trả `200`         |

Body POST mẫu gồm `title` (1–300), `goal` (`WEIGHT_GAIN`, `WEIGHT_LOSS`, `MAINTENANCE`, `SLEEP`), `activities` (1–100 hoạt động), `durationDays` (số nguyên 1–365), `applicability` (điều kiện áp dụng, 1–3000), `contraindications` (0–50 đối tượng/điều kiện không phù hợp), `excludedAllergens` (0–50 tác nhân dị ứng khiến mẫu không phù hợp), `advisory` (lời nhắc hỏi ý kiến nhân viên y tế, 1–3000), `sourceName`, `sourceUrl` và metadata nguồn tùy chọn như bài viết. Mỗi hoạt động có `id` (1–100, không trùng trong mẫu), `title` (1–300), `description` tùy chọn (tối đa 2000). Phần tử của hai danh sách điều kiện/dị ứng dài 1–200 ký tự. PATCH cần ít nhất một trường và dùng cùng schema; không được gửi trạng thái, revision, người tạo hoặc ngày xác minh.

Ví dụ body tổng hợp:

```json
{
  "title": "[TEST] Mẫu lịch sinh hoạt",
  "goal": "SLEEP",
  "activities": [{ "id": "demo", "title": "Hoạt động giả lập" }],
  "durationDays": 7,
  "applicability": "Dữ liệu giả lập dành cho kiểm thử.",
  "contraindications": [],
  "excludedAllergens": [],
  "advisory": "Hỏi ý kiến nhân viên y tế trước khi áp dụng mẫu thực tế.",
  "sourceName": "Synthetic fixture",
  "sourceUrl": "https://example.invalid/template"
}
```

Response `data` có kiểu `PlanTemplateDraft`, với các trường nội dung cùng metadata giống bài viết. Migration `010_knowledge_template_drafts.sql` thêm trường còn thiếu vào `lifestyle.templates`; mẫu cũ có thể có `durationDays = null` hoặc thiếu nguồn/điều kiện, cần bổ sung đầy đủ trước khi sửa/gửi duyệt. Không tự xác minh hay phê duyệt bản ghi cũ. Mẫu đang được `lifestyle.plans` tham chiếu không thể xóa (`409 TEMPLATE_IN_USE`); lỗi khóa ngoại do thao tác đồng thời cũng trả cùng mã này.

Migration 010 cũng thêm `revision` (bắt đầu từ 1), `submittedAt`, `submittedBy` cho bài và mẫu. Mỗi lần sửa tăng revision, đặt lại DRAFT và xóa metadata gửi duyệt cũ. Cả hai endpoint `/articles/:id/submit` và `/templates/:id/submit` nhận body rỗng hoặc `{}`. Service kiểm tra schema đầy đủ của bản đang lưu, chỉ cho `DRAFT/RETURNED → PENDING_REVIEW`, xóa ngày xác minh, lưu người/thời gian gửi và tăng revision. Gửi lại bản đang chờ duyệt, đã duyệt hoặc ngừng dùng trả `409 CONTENT_NOT_SUBMITTABLE`. Nếu nội dung bị thay đổi trong lúc kiểm tra/gửi, trả `409 CONTENT_CHANGED`; không gửi nhầm bản chưa được kiểm tra. UC-17 đọc bản PENDING_REVIEW cùng revision và metadata gửi duyệt; quyết định duyệt/trả về thuộc UC-17. Nội dung chờ duyệt không được sửa/xóa qua UC-15.

M6 có thể nhận `ApprovedTemplateProvider` từ `@health/contracts`, được triển khai bởi `createApprovedTemplateProvider(db)` trong `template-drafts.ts`. Adapter chỉ trả mẫu APPROVED, có ngày xác minh, nguồn hợp lệ và nội dung đầy đủ; không trả danh tính biên tập viên. Lọc mục tiêu/hồ sơ/dị ứng, áp dụng mẫu cho người dùng và xác thực lộ trình thuộc M6. Chưa nối adapter vào route `/lifestyle/templates` trong phase này. Lỗi database được truyền lên, không biến thành danh sách rỗng. Các enum và trường mới là hợp đồng triển khai để M6/UC-17 tích hợp.

## Các route đã dành chỗ

Các public account action sau trả **501 NOT_IMPLEMENTED**: POST `/accounts/register`, `/accounts/login`, `/accounts/forgot-password`, `/accounts/reset-password`, `/accounts/confirm-deletion`.

Tất cả route dưới đây hiện trả **503 AUTH_NOT_CONFIGURED**, kể cả khi client giả `Authorization`, `X-User-ID` hoặc `X-Role`. Guard không nhận những header này làm phiên thật. Sau M5, thay guard bằng xác thực, rồi bổ sung role/consent/ownership trước khi thay handler 501 bằng nghiệp vụ. Thứ tự phải được giữ.

| Module | Routes sau `/api/v1`                                                                                                                                                            | Quyền cần thực hiện                                                         |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| M1     | POST `/safety/evaluate`; GET/POST `/safety/rules`; PATCH `/safety/rules/:id`                                                                                                    | Evaluate: nội bộ/phiên và consent; quy tắc: CONTENT_EXPERT                  |
| M2     | Authoring bài/mẫu và gửi duyệt như trên; GET `/knowledge/reviews`; POST `/knowledge/reviews/:id/decision`; GET `/knowledge/feedback`; PATCH `/knowledge/feedback/:id`           | Authoring: CONTENT_EXPERT hoặc ADMIN; phê duyệt và feedback: CONTENT_EXPERT |
| M3     | GET/PUT/DELETE `/health/profile`; GET `/health/metrics`; PUT/DELETE `/health/metrics/:date`                                                                                     | Dữ liệu của chính người dùng, consent                                       |
| M3     | GET `/health/statistics`                                                                                                                                                        | ADMIN, chỉ số tổng hợp                                                      |
| M4     | POST `/chat/messages`; GET `/chat/sessions`; GET `/chat/sessions/:id/messages`; DELETE `/chat/sessions/:id`; POST `/chat/messages/:id/feedback`                                 | Chính chủ, consent; attachment feedback là opt-in                           |
| M5     | POST `/accounts/logout`; GET/PUT `/accounts/me`; POST `/accounts/me/change-password`; POST `/accounts/me/deletion-request`                                                      | Chính chủ; xóa cần xác nhận email                                           |
| M5     | GET `/accounts/users`; PATCH `/accounts/users/:id/status`; PUT `/accounts/users/:id/roles`                                                                                      | ADMIN; không lộ hồ sơ/hội thoại                                             |
| M6     | GET `/lifestyle/templates`; GET/PUT `/lifestyle/plan`; PUT `/lifestyle/completions/:activityId/:date`; GET/POST `/lifestyle/reminders`; PATCH/DELETE `/lifestyle/reminders/:id` | Chính chủ, consent; template đã duyệt                                       |

Đây là route ban đầu để chia việc, không phải danh sách API cuối. Chi tiết bài viết, form mẫu kế hoạch, email/OAuth, hàng đợi duyệt và các nhánh UC cần được chủ module bổ sung, cập nhật tài liệu và contract. Không đánh dấu một UC hoàn thành chỉ vì route đã tồn tại.
