# Bản đồ sáu module

> Ngôn ngữ và framework của từng module (Spring Boot hay Python FastAPI/Django) xem [TECH_STACK.md](TECH_STACK.md). Bảng người phụ trách mới nhất nằm ở đó và trên Jira.

Đây là phân chia do nhóm cung cấp. Mỗi thành viên nhận một hàng và phụ trách cả frontend, backend và thay đổi dữ liệu thuộc module đó. Người phụ trách được gán issue trên GitHub và Jira; bảng người phụ trách khớp với epic M1–M6 trên Jira.

| Mã  | Thư mục     | Use case                           | Phạm vi sở hữu                                                                                                                                | Người phụ trách                    |
| --- | ----------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| M1  | `safety`    | UC-07, UC-16 (+ phần M1 của UC-17) | Bộ đánh giá theo quy tắc, soạn quy tắc cảnh báo; kiểm duyệt đầu vào: duyệt quy tắc, duyệt tài liệu trong tiền xử lý trước khi đưa vào agent   | Bảo Quỳnh (`DontMadWhenBad`)       |
| M2  | `knowledge` | UC-10, UC-11, UC-15, UC-17         | Tra cứu kiến thức/cơ sở, soạn nội dung; hàng đợi duyệt dùng chung; kiểm duyệt đầu ra: duyệt bài/mẫu để công khai, kiểm định kết quả của agent | Trường Giang (`Ziang05`)           |
| M3  | `health`    | UC-04, UC-05, UC-19                | Hồ sơ, chỉ số theo ngày, biểu đồ và thống kê hệ thống                                                                                         | Vũ Quang Dũng (`Dungdayyy`)        |
| M4  | `chat`      | UC-06, UC-08, UC-09                | Điều phối hội thoại, lịch sử, ghi nhận phản hồi                                                                                               | Đặng Việt Thành (`Vncnt-uit`)      |
| M5  | `accounts`  | UC-01, UC-02, UC-03, UC-18         | Tài khoản, consent, phiên, phân quyền, khóa/mở khóa người dùng                                                                                | Võ Ngọc Tài (`vongoctaijk-sketch`) |
| M6  | `lifestyle` | UC-12, UC-13, UC-14                | Chọn lộ trình, hoàn thành hoạt động, streak, nhắc nhở                                                                                         | Lữ Kim Luân (`LuanLuKim`)          |

## Cấu trúc giống nhau ở hai phía

```text
apps/web/src/modules/<module>/
  README.md        Phạm vi và phụ thuộc
  Page.tsx         Màn hình vào module
  components/      Thêm khi cần
  hooks/           Thêm khi cần

backend/src/main/java/vn/edu/uit/se214/health/<module>/
  README.md             Phạm vi, phụ thuộc, cách mở rộng
  <Module>Controller    HTTP: đọc request, trả ApiResponse, @RequiresSession cho route riêng tư
  <...>Service          Nghiệp vụ
  <...>Repository       SQL bằng JdbcClient, chỉ trên schema của module
  api/                  Kiểu và interface công khai cho module khác
```

Không tạo thư mục rỗng cho đủ cây. Thêm file khi có nhiệm vụ cụ thể. Phần AI (M1 Safety Gate, M4 RAG/LLM) viết trong `services/ai`, phần thống kê (M3) trong `services/stats`; Spring Boot gọi sang qua `integrations/PythonServiceClient`.

## Hợp đồng tích hợp

Các kiểu JSON chung cho frontend nằm tại `packages/contracts/src/index.ts`; backend có record Java tương ứng (ví dụ `system/ModuleRegistry`, `knowledge/api/*`). Đổi một bên thì đổi cả bên kia trong cùng PR.

| Bên cung cấp | Bên dùng | Hợp đồng cần giữ                                                                                                                                                                   |
| ------------ | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M5           | Tất cả   | Phiên đã xác thực, quyền và quyền sở hữu; consent trước chức năng sức khỏe. Không tin vai trò do client gửi.                                                                       |
| M1           | M4       | `SafetyEvaluator` trả `SafetyDecision`; không có quy tắc thì `UNAVAILABLE` + `careLevel: null`.                                                                                    |
| M2           | M4       | `KnowledgeRetriever` chỉ trả nguồn đã duyệt, còn hiệu lực.                                                                                                                         |
| M4           | M2       | `AnonymousFeedback` chỉ có cặp người dùng chủ động chia sẻ; không có user ID hoặc toàn bộ lịch sử.                                                                                 |
| M1, M2       | M1, M6   | Quyết định duyệt (UC-17, hàng đợi chung của M2): phần M1 duyệt quy tắc và tài liệu được đưa vào agent; phần M2 duyệt bài viết, mẫu kế hoạch được công khai; chọn mẫu cá nhân ở M6. |
| M3           | M6       | Hồ sơ/dị ứng của chính người dùng; truy cập qua service với kiểm tra quyền.                                                                                                        |
| M4, M5       | M3       | Sự kiện tổng hợp cho UC-19, không truyền nội dung hội thoại/hồ sơ vào thống kê.                                                                                                    |

UC-18 quản lý tài khoản thuộc M5. UC-19 thống kê hệ thống thuộc M3. UC-08 ghi nhận phản hồi thuộc M4; phần chuyên gia xem/xử lý nằm trong UC-17 của M2. UC-17 là một use case, chỉ phần việc được chia theo giai đoạn: phần M1 kiểm duyệt đầu vào (quy tắc cảnh báo, tài liệu trong tiền xử lý trước khi đưa vào agent); phần M2 kiểm duyệt đầu ra (bài viết, mẫu kế hoạch được công khai, kết quả của agent qua feedback). Hai bên dùng chung code duyệt (hàng đợi, bản ghi, màn hình Quản trị nội dung) do M2 sở hữu; M1 cắm phần xử lý qua interface `ReviewSubjectHandler`.

## Thứ tự tích hợp

1. M5 hoàn thiện xác thực/consent và thay guard từ chối mặc định.
2. M1 và M2 hoàn thiện quy tắc đã duyệt, truy xuất và kiểm soát nguồn.
3. M4 nối Safety Gate trước LLM, kiểm tra đầu ra trước hiển thị.
4. M3/M6 triển khai dữ liệu cá nhân và nhắc nhở trên ranh giới M5.

Mỗi module vẫn có thể phát triển bằng fixture tổng hợp trong lúc chờ hợp đồng. Không bật bypass xác thực trên API thật để thử giao diện.
