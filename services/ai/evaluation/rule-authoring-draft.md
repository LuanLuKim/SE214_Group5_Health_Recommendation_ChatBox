# Đề xuất soạn quy tắc UC-16 — DRAFT, chưa dùng trong đánh giá

Tài liệu này không phải bộ quy tắc y khoa đã duyệt. Không nạp vào production, không tự chuyển APPROVED/active. Chưa kiểm chứng nguồn trực tuyến trong lần triển khai thử này.

## Nhóm quy tắc ưu tiên chuyên gia soạn

| Nhóm | Fact đề xuất cần thống nhất | Nhãn đề xuất |
|---|---|---|
| Không đáp ứng | unresponsive | EMERGENCY_NOW |
| Ngừng thở / thở bất thường sau ngã quỵ | not_breathing_normally, collapsed | EMERGENCY_NOW |
| Khó thở nặng | severe_breathlessness, unable_to_speak_full_sentences | EMERGENCY_NOW |
| Dấu hiệu thần kinh xuất hiện đột ngột | sudden_facial_droop, sudden_arm_weakness; hoặc sudden_speech_difficulty, sudden_one_sided_weakness | EMERGENCY_NOW |
| Co giật kéo dài / không hồi phục giữa cơn | seizure_over_five_minutes; hoặc repeated_seizures, no_recovery_between_seizures | EMERGENCY_NOW |
| Chảy máu nhiều không cầm | heavy_bleeding, bleeding_not_stopped_by_pressure | EMERGENCY_NOW |
| Phản ứng dị ứng có tổn thương đường thở | tongue_swelling, breathlessness, recent_allergen_exposure | EMERGENCY_NOW |
| Nguy cơ tự sát tức thời | imminent_suicidal_intent, means_available | EMERGENCY_NOW |

Các nhóm còn lại và các nhãn thấp hơn cần chuyên gia xác định điều kiện đầy đủ, thời gian, tuổi, yếu tố nguy cơ và trường hợp loại trừ. Không suy ra tự chăm sóc chỉ vì thiếu dấu hiệu nguy hiểm.

## Nguồn cần tìm và kiểm chứng

Ưu tiên tài liệu chính thức của Bộ Y tế, WHO, cơ quan cấp cứu/hồi sức và hướng dẫn sơ cứu có thẩm quyền. Người soạn phải ghi URL trang cụ thể, tiêu đề, phiên bản/ngày xuất bản, đoạn làm căn cứ và giới hạn áp dụng. Không dùng URL tự đoán hoặc trang chủ thay cho bằng chứng.

## Nhập qua UC-16

Mỗi bản nháp sử dụng các trường:
- `name`: tên quy tắc, có thể ghi rõ “[DRAFT — cần rà soát y khoa]”.
- `conditions.all`: ít nhất một fact quan sát tường minh; `conditions.any`: các fact thay thế nếu có.
- `requiredFields`: thông tin phải hỏi thêm; không giả định fact không được nhắc là âm tính.
- `careLevel`: nhãn đã được chuyên gia xác nhận.
- `specialtyIds`: mã thực từ M2; chưa thống nhất thì để rỗng, không bịa mã.
- `sourceName`, `sourceUrl`: nguồn cụ thể đã được người soạn kiểm chứng.

Không thêm `reviewStatus`, `active`, `verifiedAt` giả vào payload soạn thảo. Gửi duyệt UC-17 bằng tài khoản được cấp quyền, chờ người duyệt độc lập ghi nhận và kích hoạt phiên bản.

## Trước khi gửi duyệt

1. Thống nhất token với M4; hiện bộ ca nháp dùng tiếng Anh còn extractor dùng token tiếng Việt.
2. Kiểm tra phủ định, giả định, tiền sử và triệu chứng của người khác; extractor hiện chỉ dò từ khóa nên chưa đủ chứng minh fact thực sự hiện diện.
3. Người duyệt rà soát độc lập nhãn bộ ca, không sao chép điều kiện từng ca thành quy tắc chỉ để đạt điểm.
4. Có ca ngoài bộ dùng soạn quy tắc để hạn chế đánh giá trên dữ liệu đã dùng thiết kế.
5. Chạy lại M-01/M-02 với artifact đã duyệt và lưu phiên bản quy tắc, bộ ca, cấu hình, thời điểm, kết quả.