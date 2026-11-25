# UC-07 — bản triển khai thử, Refs #41 / #133

## Trạng thái

- `uc07_cases.draft.json`: 38 ca do AI soạn nháp, gồm 30 ca khẩn cấp và 2 ca cho mỗi nhãn còn lại. Không phải dữ liệu bệnh nhân.
- Nhãn và bộ fact **chưa được chuyên gia y khoa duyệt**. Không đổi `reviewStatus` thành `APPROVED` chỉ để vượt kiểm tra.
- `no-approved-rules.json`: bộ quy tắc rỗng dùng tái hiện tình huống chưa có quy tắc được duyệt. **Không phải bản export từ DB đang chạy**.
- Các fact tiếng Anh trong bộ ca là đề xuất từ vựng chuẩn hóa. Bộ trích xuất chat hiện dùng nhiều token tiếng Việt; cần thống nhất từ vựng trước khi tích hợp quy tắc.
- Công cụ đo chỉ kiểm tra `evaluate_facts`, **không đo trích xuất văn bản hoặc end-to-end chat**.
- Chưa đạt nghiệm thu M-01/M-02. Không dùng test fixture APPROVED để chứng minh độ chính xác y khoa.

## Chạy kiểm thử

Từ thư mục gốc repository:

```text
python -m pytest services/ai -o addopts= -q
mvn -f backend/pom.xml clean verify -B
```

Chạy đánh giá từ `services/ai`:

```text
python -m app.safety_metrics --cases evaluation/uc07_cases.draft.json --rules evaluation/no-approved-rules.json
```

Lệnh đánh giá in JSON ra stdout. Exit code 1 nghĩa là chưa đủ điều kiện nghiệm thu (đây là kết quả dự kiến với baseline rỗng).

Sau khi chuyên gia duyệt nhãn và có bản export quy tắc thực sự đã duyệt/kích hoạt, thay đường dẫn `--cases` và `--rules` bằng các artifact đã duyệt. Không tự sửa trạng thái quy tắc để nâng điểm.

## Kết quả baseline chạy ngày 25/11/2026

| Chỉ số | Kết quả | Ngưỡng |
|---|---:|---:|
| Ca khẩn cấp bắt đúng | 0/30 | ít nhất 27/30 |
| M-01 Recall | 0% | ≥ 90% |
| M-02 Accuracy | 0% | ≥ 75% |
| M-02 Macro-F1 (5 nhãn) | 0 | ≥ 0,70 |

Đầu vào: 38 ca nháp và bộ quy tắc rỗng. Mọi ca trả UNAVAILABLE / `careLevel: null`. Đây là baseline kiểm tra hành vi khi chưa có quy tắc, không suy ra số đo của DB/ứng dụng đang chạy.

Công thức:
- M-01 = số ca khẩn cấp dự đoán EMERGENCY_NOW / tổng ca khẩn cấp.
- Accuracy = số nhãn dự đoán đúng / tổng số ca, gồm cả ca UNAVAILABLE.
- F1 từng nhãn = 2TP / (2TP + FP + FN); mẫu số 0 thì F1 = 0.
- Macro-F1 = trung bình F1 của **đủ 5 nhãn**.
- Không loại ca trả null khỏi mẫu số.
- Điều kiện bao phủ tối thiểu: ≥ 30 ca khẩn cấp và có ca cho đủ 5 nhãn.
- `acceptanceReady` chỉ true nếu đạt ngưỡng và bộ nhãn khai báo đã duyệt. Metadata này không thay thế hồ sơ duyệt thực tế.

## Cấu hình ưu tiên

Đặt trong environment của dịch vụ AI:

```text
SAFETY_CARE_LEVEL_PRIORITY=EMERGENCY_NOW,SEEK_CARE_SOON,ROUTINE_CONSULT,GENERAL_SELF_CARE_INFO,INSUFFICIENT_INFO
```

Phải chứa đủ 5 nhãn, mỗi nhãn đúng một lần, EMERGENCY_NOW luôn đầu tiên. Cấu hình sai làm dịch vụ từ chối khởi động. Quy tắc khẩn cấp khớp vẫn được xử lý trước yêu cầu bổ sung thông tin.

## Gợi ý cơ sở y tế

Backend chat bổ sung `facilities` trong `data` của sự kiện SSE `safety`, cùng với `careLevel` và `specialtyIds`. Không có kết quả thì `facilities: []`.

Truy vấn M2:
- Đối sánh **chính xác** specialtyIds với phần tử trong `knowledge.facilities.specialties`, không suy đoán tên chuyên khoa/bệnh.
- Chỉ lấy `verified_at IS NOT NULL`, `is_synthetic = false`, tên nguồn không rỗng và URL nguồn HTTP(S).
- Danh mục chỉ được truy vấn cho chuyên khoa thuộc quy tắc đã duyệt có ID khớp với quyết định.
- Không trả gợi ý cho UNAVAILABLE hoặc INSUFFICIENT_INFO.
- Tối đa 20 cơ sở, thứ tự tên/ID ổn định; không tuyên bố là cơ sở gần nhất vì chưa có dữ liệu khoảng cách.
- Lỗi danh mục trả danh sách rỗng, giữ nguyên quyết định và hướng dẫn an toàn.
- Cần M2 xác nhận `specialties` chứa mã hay tên; nếu là tên còn quy tắc dùng mã thì cần bảng ánh xạ có quản lý, không tự đoán.
- Chưa bổ sung hiển thị danh sách trên giao diện web; đây là tích hợp dữ liệu phía backend chat.

## Việc cần duyệt trước khi hoàn tất UC-07

1. Chuyên gia rà soát nhãn từng ca và fact chuẩn hóa, thêm các ca phủ định/ngữ cảnh/mơ hồ khi đánh giá end-to-end.
2. Soạn quy tắc với nguồn y khoa được kiểm chứng qua UC-16; xem đề xuất trong `rule-authoring-draft.md`.
3. Giang/luồng UC-17 duyệt, ghi bằng chứng nguồn và kích hoạt đúng phiên bản. Không dùng SQL để tự APPROVED.
4. M2 cung cấp cơ sở thật đã xác minh và thống nhất chuyên khoa.
5. Chạy lại chỉ số với quy tắc đã duyệt; phải đạt ngưỡng trước khi ghi hoàn tất SCRUM-101.
6. Kiểm thử SSE và UI end-to-end với dữ liệu đã duyệt.
7. Chỉ dùng `Refs #41` trong PR, không dùng `Closes #41`.

Các test kỹ thuật chỉ chứng minh bất biến lập trình và công thức tính; không chứng nhận tính đúng đắn y khoa của bộ ca/quy tắc.