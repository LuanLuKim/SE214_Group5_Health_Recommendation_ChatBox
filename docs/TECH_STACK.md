# Công nghệ và kiến trúc backend (khuyến nghị)

Tài liệu này ghi **khuyến nghị** của nhóm về ngôn ngữ, framework và kiến trúc để mọi thành viên biết module của mình nên viết bằng gì. Bản tóm tắt trên Jira: **SCRUM-13**. Nhật ký thay đổi tài liệu: **SCRUM-125**.

> **Khuyến nghị, không bắt buộc.** Nhóm ưu tiên các lựa chọn dưới đây; nếu module của bạn cần lựa chọn khác, trao đổi với Scrum Master và ghi vào SCRUM-125.
>
> **Đây là kiến trúc sơ bộ.** Nhóm đã có hướng đi nhưng **chưa triển khai và chưa kiểm thử hoàn tất toàn bộ module**, vì nhiều module khó (Safety Gate, trợ lý AI có RAG, thống kê, nhắc nhở theo lịch). Chi tiết từng module có thể còn thay đổi khi làm; mọi thay đổi ghi vào SCRUM-125 và cập nhật lại file này.

## Tóm tắt

| Phần                  | Công nghệ khuyến nghị                    | Vai trò                                                                               |
| --------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------- |
| Frontend              | React, TypeScript, Vite                  | Giao diện tiếng Việt, chỉ gọi API của Spring Boot                                     |
| Backend chính         | **Java, Spring Boot — modular monolith** | Phần lớn nghiệp vụ M1–M6, cổng API duy nhất, xác thực và phân quyền                   |
| Dịch vụ AI            | **Python, FastAPI**                      | Safety Gate, RAG, gọi mô hình ngôn ngữ, kiểm tra đầu ra, trả lời dạng streaming (SSE) |
| Dịch vụ thống kê      | **Python, Django**                       | Xu hướng chỉ số sức khỏe (UC-05), thống kê hệ thống (UC-19)                           |
| Cơ sở dữ liệu         | PostgreSQL                               | Mỗi module một schema                                                                 |
| Dữ liệu tri thức (M7) | Python (script chạy ngoài ứng dụng)      | Crawl, chuẩn hóa, chia chunk, nạp vào kho tri thức                                    |

**Nguyên tắc ưu tiên:** phần lớn module nên viết bằng **Java (Spring Boot)**. Những module hoặc chức năng đặc thù về **AI hay thống kê** nên viết bằng **Python**. Một module có thể có cả hai phần: phần quản lý dữ liệu ở Spring Boot, phần AI hoặc thống kê ở service Python.

## Kiến trúc modular monolith (khuyến nghị)

```mermaid
flowchart LR
  W["Frontend React"] -->|"HTTPS · REST/JSON"| SB
  subgraph JAVA["Spring Boot — modular monolith (1 ứng dụng)"]
    SB["Cổng API · xác thực (M5)"] --> JM["Module M1 … M6"]
  end
  subgraph PY["Service Python đi kèm"]
    FA["FastAPI — AI"]
    DJ["Django — thống kê"]
  end
  JM -->|"REST/JSON · SSE"| FA
  JM -->|"REST/JSON"| DJ
  FA --> LLM["Mô hình ngôn ngữ"]
  JM --> DB[("PostgreSQL")]
  FA --> DB
  DJ --> DB
  M7["M7 — script Python"] --> DB
  classDef java fill:#FFEBEE,stroke:#C62828,color:#B71C1C
  classDef fast fill:#E0F2F1,stroke:#00897B,color:#004D40
  classDef dj fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20
  classDef web fill:#E3F2FD,stroke:#1565C0,color:#0D47A1
  classDef db fill:#F3E5F5,stroke:#6A1B9A,color:#4A148C
  classDef data fill:#FFFDE7,stroke:#F9A825,color:#5D4037
  class SB,JM java
  class FA fast
  class DJ dj
  class W web
  class DB db
  class M7 data
```

- **Một ứng dụng Spring Boot, triển khai một lần.** M1–M6 là các module bên trong, không tách thành microservice.
- **Mỗi module có ranh giới riêng:** package riêng (controller, service, repository) và schema CSDL riêng. Module khác chỉ gọi qua interface công khai của module đó, **không truy cập thẳng bảng của module khác**.
- **Service Python chỉ là service đi kèm** cho phần AI và thống kê. Chúng không chứa nghiệp vụ chính và chỉ nhận lời gọi từ Spring Boot.

## Ngôn ngữ theo module (khuyến nghị)

| Module                     | Người phụ trách          | Ưu tiên Java – Spring Boot                                                                                                                     | Khuyến nghị Python                                             |
| -------------------------- | ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| M1 Safety Gate             | Tăng Nguyễn Bảo Quỳnh    | Soạn, gửi duyệt quy tắc (UC-16); phần M1 của UC-17, kiểm duyệt đầu vào: duyệt quy tắc, duyệt tài liệu trong tiền xử lý trước khi đưa vào agent | FastAPI: bộ đánh giá Safety Gate (UC-07)                       |
| M2 Tra cứu và nội dung     | Nguyễn Xuân Trường Giang | UC-10, 11, 15, 17; hàng đợi duyệt dùng chung; kiểm duyệt đầu ra: duyệt bài viết, mẫu để công khai, kiểm định kết quả của agent                 | —                                                              |
| M3 Hồ sơ, chỉ số, thống kê | Vũ Quang Dũng            | Hồ sơ, ghi chỉ số theo ngày (UC-04, UC-05)                                                                                                     | Django: xu hướng chỉ số, thống kê hệ thống (UC-19)             |
| M4 Hội thoại với trợ lý AI | Đặng Việt Thành          | Lịch sử hội thoại, đánh giá câu trả lời (UC-08, UC-09)                                                                                         | FastAPI: điều phối chat, RAG, gọi LLM, kiểm tra đầu ra (UC-06) |
| M5 Tài khoản, phân quyền   | Võ Ngọc Tài              | Toàn bộ (UC-01, 02, 03, 18), xác thực dùng chung                                                                                               | —                                                              |
| M6 Lộ trình, nhắc nhở      | Lữ Kim Luân              | Toàn bộ (UC-12, 13, 14), bộ lập lịch gửi nhắc                                                                                                  | —                                                              |
| M7 Tiền xử lý dữ liệu      | Lữ Kim Luân              | —                                                                                                                                              | Script Python ngoài ứng dụng (không gắn FR/UC)                 |

**UC-17 (rà soát và phê duyệt) vẫn là một use case thuộc M2; chỉ phần việc được chia cho hai người theo giai đoạn:**

- **Phần M1 – kiểm duyệt đầu vào (trước agent, trong tiền xử lý; Jira SCRUM-138, issue #91):** phê duyệt quy tắc cảnh báo; rà soát và duyệt tài liệu, chunk do M7 tiền xử lý trước khi được đưa vào kho RAG cho agent (từ `DRAFT` sang được dùng cho RAG).
- **Phần M2 – kiểm duyệt đầu ra (sau tiền xử lý, sau agent; Jira SCRUM-29, issue #58):** duyệt bài viết và mẫu kế hoạch đã chuẩn hóa để công khai cho tra cứu; kiểm định kết quả của agent qua bộ câu hỏi mẫu, mẫu câu trả lời và phản hồi chưa hữu ích; lỗi do nội dung thì sửa bài viết, do quy tắc thì chuyển lại M1, do cách trả lời thì chuyển M4.
- **Code dùng chung:** một hàng đợi, một bản ghi quyết định (`knowledge.review_events`), một API `/api/v1/knowledge/reviews` và một màn hình Quản trị nội dung do M2 sở hữu; M1 cắm phần xử lý cho quy tắc và chunk M7 qua interface `ReviewSubjectHandler`, không làm hệ thống duyệt riêng; mỗi người xử lý phần của mình. Bộ kiểm tra đầu ra tự động (không chẩn đoán, không tên thuốc) vẫn thuộc dịch vụ AI của M4.

## Gợi ý giao tiếp giữa Spring Boot và service Python

1. **Frontend chỉ gọi Spring Boot.** Spring Boot xác thực phiên, kiểm tra quyền và sự đồng ý xử lý dữ liệu sức khỏe rồi mới gọi Python.
2. **Spring Boot gọi FastAPI và Django bằng REST/JSON** (RestClient/WebClient) trong mạng nội bộ, kèm khóa dịch vụ lấy từ biến môi trường. Service Python không mở ra Internet.
3. **Streaming câu trả lời chat:** FastAPI trả SSE, Spring Boot chuyển tiếp xuống trình duyệt.
4. **Chỉ gửi dữ liệu tối thiểu sang dịch vụ AI:** không gửi họ tên, email. Log không ghi nội dung hội thoại hay hồ sơ sức khỏe.
5. **Khi service Python lỗi,** Spring Boot vẫn chạy các chức năng không phụ thuộc và trả thông báo phù hợp.

Trước khi code phần gọi nhau giữa hai module hoặc hai service, hai người phụ trách thống nhất **hợp đồng API dạng JSON** (mô tả bằng OpenAPI) và ghi vào issue liên quan.

## Tình trạng mã nguồn hiện tại

- Khung Express + TypeScript cũ (`apps/api`, dựng ngày 29/10) đã được thay bằng `backend/` (Spring Boot modular monolith), `services/ai` (FastAPI), `services/stats` (Django) trong issue #92 (Jira SCRUM-142). Logic đã có được port sang kèm test: BMI (UC-04), tiến độ và chuỗi ngày (UC-13), nháp quy tắc (UC-16), tìm kiếm M2.
- Migration SQL giữ nguyên nội dung, đổi tên theo Flyway (`V001__…`) và chạy tự động khi backend khởi động với `DATA_MODE=postgres`.
- CI chạy `check` (frontend), `backend` (Maven), `services` (pytest, Django), `data-tools`, `infra-smoke`; CD build image Docker lên GitHub Container Registry khi merge vào `main`.
- Quy trình làm việc không đổi: issue → nhánh `<số-issue>-<mô-tả>` → PR, xem [CONTRIBUTING.md](../CONTRIBUTING.md).
