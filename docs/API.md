# API baseline

Base path: `/api/v1`. Contract dùng chung: `packages/contracts/src/index.ts`. Đây là API nền tảng, chưa phải bản hoàn thiện của các use case. Backend không lưu request body/prompt vào log.

Thành công: `{ "data": ..., "meta": { "status": "...", "total": 0 } }` (`meta` tùy route). Lỗi: `{ "error": { "code": "...", "message": "...", "requestId": "..." } }`. `X-Request-ID` được server tạo. Dữ liệu JSON tối đa 32 KiB; giới hạn baseline 120 request/phút/IP.

## Các route đã chạy

| Method | Route                                                       | Kết quả                                                             |
| ------ | ----------------------------------------------------------- | ------------------------------------------------------------------- |
| GET    | `/healthz`                                                  | Trạng thái tiến trình, không phải kiểm tra readiness của DB/LLM     |
| GET    | `/api/v1/modules`                                           | Sáu module và 19 UC                                                 |
| GET    | `/api/v1/{safety,knowledge,health,chat,accounts,lifestyle}` | Metadata module                                                     |
| GET    | `/api/v1/knowledge/articles?q=`                             | Public: fixture synthetic hoặc tối đa 20 bài đã duyệt từ PostgreSQL |
| GET    | `/api/v1/knowledge/facilities?q=&province=&specialty=`      | Public: tối đa 20 cơ sở khớp bộ lọc, có nguồn và ngày xác minh      |
| GET    | `/api/v1/knowledge/facilities/:id`                          | Public: chi tiết một cơ sở có nguồn và ngày xác minh                |

`q` là một chuỗi tối đa 200 ký tự. Danh sách và search metadata không chứa private user data. Fixture `[TEST]` chỉ dành kiểm thử; không trộn fixture vào kho kiến thức y tế thật.

## Tra cứu cơ sở y tế (UC-11)

`GET /api/v1/knowledge/facilities` nhận các tham số tùy chọn:

- `q`: tìm chuỗi con trong tên cơ sở, không phân biệt chữ hoa/thường.
- `province`: khớp chính xác tên tỉnh/thành, không phân biệt chữ hoa/thường.
- `specialty`: khớp chính xác một tên trong danh sách chuyên khoa của cơ sở, không phân biệt chữ hoa/thường.

Mỗi tham số dài tối đa 200 ký tự, được bỏ khoảng trắng đầu/cuối; `province` và `specialty` không được rỗng khi truyền vào. Các bộ lọc kết hợp bằng AND. Tham số sai trả `400 INVALID_QUERY`. Danh sách sắp theo tên và ID, tối đa 20 kết quả; `meta.total` là số bản ghi được trả về, không phải tổng của toàn bộ danh mục.

`GET /api/v1/knowledge/facilities/:id` nhận UUID cơ sở. ID sai định dạng, cơ sở không tồn tại, thiếu nguồn hoặc chưa có ngày xác minh đều trả `404 NOT_FOUND` cùng thông báo, không lộ dữ liệu chưa xác minh.

Ví dụ response chi tiết trong mock mode:

```json
{
  "data": {
    "id": "00000000-0000-4000-8000-000000000020",
    "name": "[TEST] Cơ sở giả lập A",
    "specialties": ["Chuyên khoa thử nghiệm"],
    "province": "Tỉnh kiểm thử",
    "address": "Địa chỉ giả lập, không có thật",
    "officialUrl": "https://example.invalid/facility",
    "source": {
      "id": "00000000-0000-4000-8000-000000000020",
      "name": "Synthetic fixture",
      "url": "https://example.invalid/facility",
      "verifiedAt": "2026-10-29T00:00:00.000Z"
    }
  },
  "meta": { "status": "SYNTHETIC_DATA" }
}
```

Danh sách dùng cùng kiểu `FacilitySummary` nhưng `data` là mảng, thêm `meta.total`. PostgreSQL trả `meta.status = DATABASE_RESULTS`. Cả hai API loại cơ sở có tên nguồn hoặc URL nguồn rỗng, hoặc thiếu ngày xác minh; dữ liệu không được tạo bởi LLM. Thông tin chi tiết đã nằm trong `FacilitySummary`, nên không cần kiểu chi tiết riêng hoặc migration mới.

Safety Gate có thể nhận dependency `FacilityLookup` từ `@health/contracts`. `createKnowledgeService(config, pool)` triển khai contract này: `listFacilities(filters?)` trả tối đa 20 cơ sở và `getFacility(id)` trả một cơ sở hoặc `null` khi ID sai/không có cơ sở đã xác minh. Hai phương thức dùng cùng kiểm tra nguồn/ngày như API công khai. Lỗi database được truyền lên bên gọi, không chuyển thành danh sách rỗng. Contract dùng tên chuyên khoa trong danh mục; nếu M1 dùng `SafetyDecision.specialtyIds`, M1 cần ánh xạ ID sang tên danh mục trước khi tra cứu. Kết nối luồng Safety Gate thuộc M1.

## Các route đã dành chỗ

Các public account action sau trả **501 NOT_IMPLEMENTED**: POST `/accounts/register`, `/accounts/login`, `/accounts/forgot-password`, `/accounts/reset-password`, `/accounts/confirm-deletion`.

Tất cả route dưới đây hiện trả **503 AUTH_NOT_CONFIGURED**, kể cả khi client giả `Authorization`, `X-User-ID` hoặc `X-Role`. Guard không nhận những header này làm phiên thật. Sau M5, thay guard bằng xác thực, rồi bổ sung role/consent/ownership trước khi thay handler 501 bằng nghiệp vụ. Thứ tự phải được giữ.

| Module | Routes sau `/api/v1`                                                                                                                                                                             | Quyền cần thực hiện                                                   |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| M1     | POST `/safety/evaluate`; GET/POST `/safety/rules`; PATCH `/safety/rules/:id`                                                                                                                     | Evaluate: nội bộ/phiên và consent; quy tắc: CONTENT_EXPERT            |
| M2     | POST `/knowledge/articles`; PATCH/DELETE `/knowledge/articles/:id`; GET `/knowledge/reviews`; POST `/knowledge/reviews/:id/decision`; GET `/knowledge/feedback`; PATCH `/knowledge/feedback/:id` | CONTENT_EXPERT; quyền soạn bài của ADMIN còn phải thống nhất theo SRS |
| M3     | GET/PUT/DELETE `/health/profile`; GET `/health/metrics`; PUT/DELETE `/health/metrics/:date`                                                                                                      | Dữ liệu của chính người dùng, consent                                 |
| M3     | GET `/health/statistics`                                                                                                                                                                         | ADMIN, chỉ số tổng hợp                                                |
| M4     | POST `/chat/messages`; GET `/chat/sessions`; GET `/chat/sessions/:id/messages`; DELETE `/chat/sessions/:id`; POST `/chat/messages/:id/feedback`                                                  | Chính chủ, consent; attachment feedback là opt-in                     |
| M5     | POST `/accounts/logout`; GET/PUT `/accounts/me`; POST `/accounts/me/change-password`; POST `/accounts/me/deletion-request`                                                                       | Chính chủ; xóa cần xác nhận email                                     |
| M5     | GET `/accounts/users`; PATCH `/accounts/users/:id/status`; PUT `/accounts/users/:id/roles`                                                                                                       | ADMIN; không lộ hồ sơ/hội thoại                                       |
| M6     | GET `/lifestyle/templates`; GET/PUT `/lifestyle/plan`; PUT `/lifestyle/completions/:activityId/:date`; GET/POST `/lifestyle/reminders`; PATCH/DELETE `/lifestyle/reminders/:id`                  | Chính chủ, consent; template đã duyệt                                 |

Đây là route ban đầu để chia việc, không phải danh sách API cuối. Chi tiết bài viết, form mẫu kế hoạch, email/OAuth, hàng đợi duyệt và các nhánh UC cần được chủ module bổ sung, cập nhật tài liệu và contract. Không đánh dấu một UC hoàn thành chỉ vì route đã tồn tại.
