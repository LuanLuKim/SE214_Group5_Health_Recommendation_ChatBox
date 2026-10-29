# Bản đồ sáu module

Đây là phân chia do nhóm cung cấp. Chủ module chưa được gán tên trong repository. Mỗi thành viên nhận một hàng và phụ trách cả frontend, backend và thay đổi dữ liệu thuộc module đó.

| Mã  | Thư mục     | Use case                   | Phạm vi sở hữu                                                                      | Người phụ trách |
| --- | ----------- | -------------------------- | ----------------------------------------------------------------------------------- | --------------- |
| M1  | `safety`    | UC-07, UC-16               | Bộ đánh giá theo quy tắc, soạn quy tắc cảnh báo                                     | Chưa gán        |
| M2  | `knowledge` | UC-10, UC-11, UC-15, UC-17 | Tra cứu kiến thức/cơ sở, soạn nội dung, phê duyệt bài/mẫu/quy tắc, rà soát feedback | Chưa gán        |
| M3  | `health`    | UC-04, UC-05, UC-19        | Hồ sơ, chỉ số theo ngày, biểu đồ và thống kê hệ thống                               | Chưa gán        |
| M4  | `chat`      | UC-06, UC-08, UC-09        | Điều phối hội thoại, lịch sử, ghi nhận phản hồi                                     | Chưa gán        |
| M5  | `accounts`  | UC-01, UC-02, UC-03, UC-18 | Tài khoản, consent, phiên, phân quyền, khóa/mở khóa người dùng                      | Chưa gán        |
| M6  | `lifestyle` | UC-12, UC-13, UC-14        | Chọn lộ trình, hoàn thành hoạt động, streak, nhắc nhở                               | Chưa gán        |

## Cấu trúc giống nhau ở hai phía

```text
apps/web/src/modules/<module>/
  README.md        Phạm vi và phụ thuộc
  Page.tsx         Màn hình vào module
  components/      Thêm khi cần
  hooks/           Thêm khi cần

apps/api/src/modules/<module>/
  README.md        Phạm vi và phụ thuộc
  router.ts        HTTP parsing, validation, access checks
  service.ts       Nghiệp vụ
  repository.ts    Thêm khi module cần persistence
```

Không tạo thư mục rỗng cho đủ cây. Thêm file khi có nhiệm vụ cụ thể. `knowledge/service.ts` hiện chứa truy vấn read-only nhỏ để kết nối bản khung; khi phát triển đầy đủ hãy tách SQL sang `repository.ts`.

## Hợp đồng tích hợp

Các kiểu chung nằm tại `packages/contracts/src/index.ts`. Metadata module là nguồn chung cho menu frontend và danh sách backend.

| Bên cung cấp | Bên dùng | Hợp đồng cần giữ                                                                                              |
| ------------ | -------- | ------------------------------------------------------------------------------------------------------------- |
| M5           | Tất cả   | Phiên đã xác thực, quyền và quyền sở hữu; consent trước chức năng sức khỏe. Không tin vai trò do client gửi.  |
| M1           | M4       | `SafetyEvaluator` trả `SafetyDecision`; không có quy tắc thì `UNAVAILABLE` + `careLevel: null`.               |
| M2           | M4       | `KnowledgeRetriever` chỉ trả nguồn đã duyệt, còn hiệu lực.                                                    |
| M4           | M2       | `AnonymousFeedback` chỉ có cặp người dùng chủ động chia sẻ; không có user ID hoặc toàn bộ lịch sử.            |
| M2           | M1, M6   | Quyết định duyệt quy tắc/mẫu. Soạn quy tắc ở M1, soạn mẫu ở luồng quản lý nội dung M2; chọn mẫu cá nhân ở M6. |
| M3           | M6       | Hồ sơ/dị ứng của chính người dùng; truy cập qua service với kiểm tra quyền.                                   |
| M4, M5       | M3       | Sự kiện tổng hợp cho UC-19, không truyền nội dung hội thoại/hồ sơ vào thống kê.                               |

UC-18 quản lý tài khoản thuộc M5. UC-19 thống kê hệ thống thuộc M3. UC-08 ghi nhận phản hồi thuộc M4; phần chuyên gia xem/xử lý nằm trong UC-17 của M2.

## Thứ tự tích hợp

1. M5 hoàn thiện xác thực/consent và thay guard từ chối mặc định.
2. M1 và M2 hoàn thiện quy tắc đã duyệt, truy xuất và kiểm soát nguồn.
3. M4 nối Safety Gate trước LLM, kiểm tra đầu ra trước hiển thị.
4. M3/M6 triển khai dữ liệu cá nhân và nhắc nhở trên ranh giới M5.

Mỗi module vẫn có thể phát triển bằng fixture tổng hợp trong lúc chờ hợp đồng. Không bật bypass xác thực trên API thật để thử giao diện.
