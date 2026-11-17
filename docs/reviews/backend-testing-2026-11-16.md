# Test đợt 1 · Kết quả kiểm thử backend SE214 ngày 16/11/2026

Kiểm thử Spring Boot tại commit `f6751fe7a99166349cf4f1d24fa4aba6b4b0c188`. Các ca bổ sung sử dụng dữ liệu giả lập, MockMvc, PostgreSQL nhúng với migration Flyway và kênh gửi nhắc giả lập. Các ca chạy được lưu trong worktree kiểm thử cục bộ. [Kết quả JSON](backend-testing-results-2026-11-16.json)

## Kết quả chạy

| Phạm vi | Tổng | Đạt | Thất bại | Lỗi chạy | Bỏ qua |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ca bổ sung từ lượt trước | 23 | 17 | 6 | 0 | 0 |
| Ca mới ở lượt này | 17 | 15 | 2 | 0 | 0 |
| Tổng các ca bổ sung | 40 | 32 | 8 | 0 | 0 |
| Toàn bộ Spring Boot qua Maven verify | 186 | 178 | 8 | 0 | 0 |

`mvn verify` trả mã thoát 1 tại Surefire sau khi chạy đủ 186 ca. Cả 146 ca có sẵn đều đạt. Tám ca bổ sung thất bại gồm các phép kiểm trùng một nguyên nhân ở nhiều lớp hoặc theo nhiều cách tái hiện, và một ca có kỳ vọng nghiệp vụ đang chờ chốt. Phạm vi thực thi và số liệu trong báo cáo này là Spring Boot cục bộ.

## Ghi chú lỗi

- **08-01, 08-02** · UC08 chấp nhận tin nhắn role `user` ở cả service và API. API ghi phản hồi vào PostgreSQL.
- **08-07** · UC08 trả HTTP 500 khi người dùng chọn chia sẻ một câu trả lời thiếu câu hỏi liền trước. PostgreSQL từ chối bản ghi vì chỉ có một nửa cặp hỏi–đáp.
- **14-01, 14-13** · UC14 có thể retry lượt theo cấu hình cũ sau khi sửa giờ hoặc tắt rồi bật lại nhắc.
- **14-02** · Khi ghi `SENT` cho một lượt retry gặp lỗi PostgreSQL, ngoại lệ thoát khỏi vòng retry và làm gián đoạn cả chu kỳ.
- **14-08** · Tắt nhắc giữa bước đọc danh sách retry và bước giữ lượt gửi vẫn dẫn đến một lần gửi.
- **14-03** · Worker vẫn gửi nhắc gắn với hoạt động cũ sau khi đổi lộ trình. Ca này báo FAIL theo giả định cần dừng nhắc khi lộ trình cũ kết thúc. Quy tắc nghiệp vụ còn chờ chốt. Ca 14-10 xác nhận nhắc độc lập vẫn gửi sau khi đổi lộ trình.

## Theo dõi lỗi

Mỗi lỗi có một task trên Jira (subtask của SCRUM-59) và một issue GitHub, hạn 27/11/2026. Ca 14-03 đã chốt quy tắc: khi đổi lộ trình, nhắc gắn hoạt động không còn trong lộ trình mới thì không gửi nữa, còn lần đã gửi trước khi đổi giữ nguyên.

| Lỗi | Ca | Module | Phụ trách | GitHub | Jira | Trạng thái |
| --- | --- | --- | --- | --- | --- | --- |
| Đánh giá được tin nhắn role `user` | 08-01, 08-02 | M4 | Thành Đặng Việt | #152 | SCRUM-156 | Mở |
| HTTP 500 khi chia sẻ câu trả lời không có câu hỏi liền trước | 08-07 | M4 | Thành Đặng Việt | #153 | SCRUM-157 | Mở |
| Retry gửi lượt theo cấu hình cũ sau khi sửa giờ hoặc tắt rồi bật lại | 14-01, 14-13 | M6 | Lữ Kim Luân | #154 | SCRUM-158 | Mở |
| Lỗi ở một lượt retry làm dừng cả chu kỳ gửi | 14-02 | M6 | Lữ Kim Luân | PR #151 | SCRUM-160 | Đã sửa |
| Tắt nhắc giữa bước đọc và bước giữ chỗ retry vẫn gửi | 14-08 | M6 | Lữ Kim Luân | #155 | SCRUM-159 | Mở |
| Nhắc gắn hoạt động của lộ trình cũ vẫn gửi sau khi đổi lộ trình | 14-03 | M6 | Lữ Kim Luân | #156 | SCRUM-161 | Mở |

Cột **Theo dõi lỗi** trong file Excel và trường `tracking` trong file JSON ghi cùng thông tin này.

## Chi tiết từng ca

| Ca và use case | Lượt bổ sung | Cách tái hiện | Mong đợi | Kết quả thực tế | Trạng thái và ghi chú |
| --- | ---: | --- | --- | --- | --- |
| 06-01 · UC06 | 2 | Để URL dịch vụ AI trống. Gửi tin nhắn mới vào phiên mẫu qua API SSE. | HTTP 200 mở SSE. Nhận `AI_SERVICE_UNAVAILABLE` rồi `FAILED`. PostgreSQL chỉ thêm tin nhắn của người dùng. | HTTP 200 SSE trả `AI_SERVICE_UNAVAILABLE` và `FAILED`. Phiên có thêm đúng một tin nhắn role `user`. | **PASS** |
| 08-01 · UC08 | 1 | Gọi `ChatService.submitFeedback` bằng ID của tin nhắn role `user` trong phiên chính chủ. | Service từ chối và ném `ApiException`. | Service hoàn tất mà không ném ngoại lệ. | **FAIL** · Cùng lỗi với 08-02. Service kiểm tra quyền sở hữu phiên nhưng chưa kiểm tra role tin nhắn. |
| 08-02 · UC08 | 1 | Gửi `POST /api/v1/chat/messages/{id}/feedback` cho tin nhắn role `user` trong phiên chính chủ. | HTTP 4xx và 0 bản ghi phản hồi cho yêu cầu. | API trả HTTP 200. PostgreSQL có 1 dòng phản hồi theo ghi chú kiểm thử. | **FAIL** · Tái hiện lỗi UC08 qua cả API lẫn PostgreSQL. Hợp đồng hiện chưa quy định mã 4xx cụ thể cho role sai. |
| 08-03 · UC08 | 1 | Đánh giá tin nhắn role `assistant` với `shareConversation=true`. | HTTP 200. Phản hồi API và đúng một bản ghi PostgreSQL chứa cặp hỏi–đáp đã đồng ý chia sẻ. | HTTP 200. API và đúng một bản ghi PostgreSQL chứa cùng câu hỏi và câu trả lời giả lập. | **PASS** |
| 08-04 · UC08 | 1 | Đánh giá câu trả lời với `shareConversation=false`. | HTTP 200. Lưu ghi chú và để hai cột hội thoại là NULL. | HTTP 200, API không có nội dung hội thoại và cả hai cột PostgreSQL đều NULL. | **PASS** |
| 08-05 · UC08 | 1 | Dùng tài khoản khác gửi phản hồi cho câu trả lời trong phiên của người dùng mẫu. | HTTP 404 `NOT_FOUND` và 0 bản ghi phản hồi cho yêu cầu. | HTTP 404 `NOT_FOUND`. PostgreSQL có 0 bản ghi theo ghi chú kiểm thử. | **PASS** |
| 08-06 · UC08 | 2 | Thêm lượt hỏi–đáp mới vào phiên rồi đánh giá câu trả lời ở lượt trước với tùy chọn chia sẻ. | HTTP 200. API và PostgreSQL lưu đúng cặp hỏi–đáp của lượt được đánh giá. | HTTP 200. API và PostgreSQL chứa đúng câu hỏi và câu trả lời của lượt trước. | **PASS** |
| 08-07 · UC08 | 2 | Thêm một câu trả lời lịch sử trước tin nhắn đầu tiên của người dùng rồi gửi phản hồi với `shareConversation=true`. | API xử lý dữ liệu thiếu bằng 4xx có kiểm soát. PostgreSQL ghi 0 phản hồi. | HTTP 500 `INTERNAL_ERROR`. PostgreSQL có 0 phản hồi mới vì từ chối thao tác ghi. | **FAIL** · Ứng dụng thử lưu câu trả lời với `shared_question=NULL`. Ràng buộc `feedback_check` yêu cầu cả hai trường cùng có giá trị hoặc cùng NULL. Mã 4xx là kỳ vọng kiểm thử cho trường hợp đính kèm không thực hiện được. |
| 09-01 · UC08–09 | 1 | Đánh giá có đính kèm, xóa phiên rồi thử đọc lại và đánh giá lại câu trả lời. | Xóa trả 200. Đọc lại và đánh giá lại trả 404. Không còn phiên/tin nhắn, bản chụp phản hồi ẩn danh cũ vẫn còn. | Xóa trả 200, đọc và đánh giá lại trả 404. Hai bảng phiên/tin nhắn có 0 dòng tương ứng. Bản chụp cũ còn và không có phản hồi mới. | **PASS** |
| 10-01 · UC10 | 1 | Xem bài đang công khai, đổi trạng thái sang `RETIRED`, gọi lại danh sách và chi tiết. | Chi tiết trả 404 `NOT_FOUND`. Danh sách trả 200 và có tổng 0. | Chi tiết trả 404 `NOT_FOUND`. Danh sách trả 200 với tổng 0. | **PASS** |
| 10-02 · UC10 | 2 | Tạo bài nháp chưa xác minh. Đổi trạng thái sang `APPROVED`, sau đó mới đặt `verified_at`. | Phê duyệt khi nguồn chưa xác minh vẫn cho danh sách rỗng và chi tiết 404. Sau xác minh, danh sách và chi tiết thấy bài, nguồn có ngày xác minh. | Sau phê duyệt, danh sách vẫn có tổng 0 và chi tiết trả 404. Sau xác minh, danh sách có 1 bài, chi tiết trả 200 và `verifiedAt=2026-11-16T10:00:00.000Z`. | **PASS** |
| 11-01 · UC11 | 1 | Tìm cơ sở giả lập theo tên, bỏ `verified_at` rồi tìm lại cùng truy vấn. | Trước khi bỏ xác minh thấy đúng cơ sở. Sau khi bỏ, HTTP 200 với danh sách rỗng. | Tìm thấy cơ sở trước khi bỏ xác minh. Truy vấn sau trả HTTP 200 và danh sách rỗng. | **PASS** |
| 11-02 · UC11 | 2 | Tạo 22 cơ sở cùng khớp từ khóa, trong đó bản ghi sắp xếp đầu tiên chưa xác minh. | HTTP 200. Trả 20 cơ sở đã xác minh theo thứ tự tên, từ `01` đến `20`. | HTTP 200 với 20 bản ghi đã xác minh. Bản ghi đầu là `01`, bản ghi cuối là `20`. | **PASS** |
| 12-01 · UC12 | 1 | Xem trước mẫu B khi đang dùng A, thu hồi đồng ý xử lý dữ liệu sức khỏe trước khi xác nhận B. | HTTP 403 `HEALTH_CONSENT_REQUIRED`. PostgreSQL giữ A đang hoạt động. | HTTP 403 `HEALTH_CONSENT_REQUIRED`. PostgreSQL giữ A đang hoạt động. | **PASS** |
| 12-02 · UC12 | 1 | Xem trước mẫu B rồi chuyển B sang `RETIRED` trước khi xác nhận. | HTTP 404 `LIFESTYLE_TEMPLATE_NOT_FOUND`. API đọc lại vẫn trả lộ trình A. | HTTP 404 `LIFESTYLE_TEMPLATE_NOT_FOUND`. GET lộ trình vẫn trả A. | **PASS** |
| 12-03 · UC12 | 1 | Xem trước mẫu có tác nhân đậu phộng rồi cập nhật hồ sơ dị ứng đậu phộng trước khi xác nhận. | HTTP 409 `TEMPLATE_HAS_ALLERGEN`. Giữ A đang hoạt động và không tạo lộ trình từ mẫu bị chặn. | HTTP 409 `TEMPLATE_HAS_ALLERGEN`. PostgreSQL giữ A và có 0 lộ trình từ mẫu bị chặn. | **PASS** |
| 12-04 · UC12 | 1 | Gửi hai `PUT /api/v1/lifestyle/plan` đổi từ A sang cùng mẫu B. Giữ khóa A đến lúc PostgreSQL báo cả hai truy vấn đang chờ. | Một HTTP 200 `changed=true` với ID lộ trình đúng, một HTTP 409 `LIFESTYLE_PLAN_CONFLICT`. Cơ sở dữ liệu có B đang hoạt động và A trong lịch sử. | Cả hai câu SQL cùng chờ khóa A. Sau khi mở khóa, một request trả 200 với ID B và ID A trước đó, request còn lại trả 409 đúng mã. PostgreSQL có đúng hai dòng, một đang hoạt động. | **PASS** |
| 12-05 · UC12 | 2 | Xem trước mẫu yêu cầu hồ sơ sức khỏe rồi xóa hồ sơ trước khi xác nhận. | HTTP 409 `HEALTH_PROFILE_REQUIRED`. Giữ lộ trình đang dùng và không tạo lộ trình từ mẫu bị chặn. | HTTP 409 `HEALTH_PROFILE_REQUIRED`. PostgreSQL giữ lộ trình đang dùng và có 0 lộ trình từ mẫu bị chặn. | **PASS** |
| 12-06 · UC12–13 | 2 | Đánh dấu một hoạt động hoàn thành rồi xác nhận lại mẫu đang dùng. | HTTP 200 với `changed=false`, giữ ID lộ trình và tiến độ. Không thêm dòng lộ trình hoặc dòng hoàn thành. | HTTP 200, `changed=false`, giữ nguyên ID. Trạng thái hoạt động vẫn hoàn thành, tổng dòng lộ trình không tăng và có đúng 1 dòng hoàn thành. | **PASS** |
| 13-01 · UC12–13 | 1 | Đánh dấu hoạt động của A, chuyển sang B rồi thử đánh dấu lại hoạt động chỉ có trong A. | HTTP 404 `ACTIVITY_NOT_IN_PLAN`. Bản ghi hoàn thành cũ còn đúng một dòng. | HTTP 404 `ACTIVITY_NOT_IN_PLAN`. Dòng hoàn thành của A vẫn còn đúng một. | **PASS** |
| 13-02 · UC12–13 | 1 | Cho A và B cùng có mã `sleep-time`. Hoàn thành trên A, chuyển sang B rồi đánh dấu lại cùng ngày. | B báo chưa hoàn thành trước khi đánh dấu. Sau đó PostgreSQL có hai dòng, mỗi lộ trình một dòng. | API của B trả `completed=false` trước khi đánh dấu. Sau đó có đúng hai dòng hoàn thành, ứng với hai lộ trình. | **PASS** |
| 13-03 · UC13 | 2 | Đánh dấu hoàn thành cho ngày hôm qua. Đọc tiến độ hôm nay rồi yêu cầu bỏ đánh dấu hôm qua. | PUT trả 200 cho ngày hôm qua. Hôm nay vẫn chưa làm, chuỗi từ hôm qua là 1. DELETE trả 409 `COMPLETION_LOCKED`, dòng hôm qua còn. | PUT trả 200 cho ngày hôm qua. Hôm nay vẫn `completed=false`, `planStreak=1`. DELETE trả 409 và ngày trong PostgreSQL vẫn là hôm qua. | **PASS** |
| 13-04 · UC13 | 2 | Khởi chạy hai yêu cầu PUT đánh dấu cùng hoạt động và cùng ngày từ hai luồng bắt đầu qua cùng một điểm đồng bộ. | Cả hai trả 200 với trạng thái hoàn thành. PostgreSQL chỉ có một dòng. | Cả hai HTTP 200 với một hoạt động hoàn thành. PostgreSQL có đúng 1 dòng. | **PASS** |
| 14-01 · UC14 | 1 | Cho lượt 07:00 thất bại, sửa lịch thành 08:00 lúc 07:00:30, chạy worker lúc 07:01. | Số lượt gửi theo lịch cũ bằng 0. | Worker báo đã gửi 1 lượt lúc 07:01. | **FAIL** · Bản ghi retry theo lịch cũ còn được chọn sau khi cấu hình giờ đổi. |
| 14-02 · UC14 | 1 | Dùng trigger gây lỗi khi ghi `SENT` cho lượt retry. Chuẩn bị một nhắc khác đến hạn cùng chu kỳ. | `runOnce()` hoàn tất và nhắc khác vẫn được gửi. | `UncategorizedSQLException` thoát khỏi `runOnce()` tại câu UPDATE `SENT` do trigger. Worker dừng trước vòng lặp gửi các nhắc mới. | **FAIL** · Vòng lặp retry thiếu xử lý lỗi theo từng lượt. Trace chỉ ra lỗi PostgreSQL giả lập tại `markSent`. |
| 14-03 · UC12–14 | 1 | Tạo nhắc cho hoạt động trong A, đổi sang B rồi chạy worker ở giờ nhắc. | Theo giả định bàn giao, 0 lượt gửi cho nhắc gắn hoạt động cũ. | Worker gửi 1 lượt. JUnit báo fail với giả định 0 lượt. | **FAIL** · Chờ chốt · Đặc tả API chưa quy định nhắc gắn hoạt động sẽ tự dừng khi đổi lộ trình. Cần chốt kỳ vọng nghiệp vụ. |
| 14-04 · UC14 | 1 | Sau một lần gửi giả lập thất bại, gọi `PATCH` với `enabled=false` rồi chạy worker sau một phút. | HTTP 200, `enabled=false`, không có `nextSendAt`. Không gửi lại và dòng `FAILED` vẫn giữ 1 lần thử. | PATCH trả 200 với `enabled=false` và không có `nextSendAt`. Worker trả 0 gửi, 0 lỗi. PostgreSQL lưu nhắc đã tắt và dòng giao nhận không đổi. | **PASS** |
| 14-05 · UC14 | 1 | Hai worker trên hai luồng cùng đọc dòng `FAILED` rồi đợi nhau ngay trước câu SQL claim. | Kết quả worker là một lượt gửi và một lượt bỏ qua. Kênh giả chỉ nhận một lần. Cùng ID dòng chuyển sang `SENT`, 2 lần thử. | Hai worker trả lần lượt theo kết quả là 1 gửi và 0 gửi. Kênh giả ghi đúng một lần. Dòng cũ thành `SENT`, 2 lần thử, có `sent_at` và đã xóa mã lỗi. | **PASS** |
| 14-06 · UC14 | 1 | Xóa nhắc qua API sau lần gửi đầu thất bại, chạy lại worker trong cửa sổ retry. | HTTP 200. Nhắc và dòng giao nhận bị xóa, kênh giả không ghi nhận gửi lại. | DELETE trả 200. PostgreSQL còn 0 dòng nhắc và giao nhận tương ứng. Worker trả 0 gửi, 0 lỗi. | **PASS** |
| 14-07 · UC14 | 1 | Cho lần gửi đầu thất bại. Chạy worker sau đúng 10 phút 1 giây. | Không retry ngoài cửa sổ 10 phút. Dòng vẫn `FAILED`, 1 lần thử và giữ mã lỗi. | Worker trả 0 gửi, 0 lỗi. Dòng còn `FAILED`, 1 lần thử, mã `TEST_FAILURE`. | **PASS** |
| 14-08 · UC14 | 1 | Tắt nhắc trong PostgreSQL ngay sau khi worker đã đọc danh sách retry và trước khi nó claim. | Sau khi đã tắt, claim không gửi lại và dòng vẫn `FAILED`, 1 lần thử. | Nhắc đã lưu `enabled=false` nhưng worker gửi 1 lượt. PostgreSQL chuyển dòng cũ sang `SENT`, 2 lần thử. | **FAIL** · Phát hiện mới. `retryable` lọc cờ bật ở bước đọc, còn `claimRetry` chỉ kiểm tra trạng thái dòng và số lần thử. |
| 14-09 · UC14 | 1 | Hẹn 01:30 Chủ nhật tại `America/New_York`. Chạy ở cả hai thời điểm UTC cùng ánh xạ tới 01:30 ngày đổi giờ. | Chỉ gửi ở lần 01:30 sớm hơn. Có đúng một dòng PostgreSQL với `scheduled_for=05:30Z`. | Kênh giả ghi một lần tại 05:30Z và không ghi tại 06:30Z. PostgreSQL có đúng một dòng theo giờ 05:30Z. | **PASS** · Quy ước kiểm thử chọn lần xuất hiện sớm hơn của cùng giờ địa phương. |
| 14-10 · UC12–14 | 2 | Tạo nhắc độc lập rồi chuyển sang mẫu lộ trình khác. Chạy worker đến giờ nhắc. | Nhắc vẫn gửi đúng một lần. PostgreSQL có một dòng `SENT`, số lần thử là 1. | API xác nhận mẫu mới. Nhắc độc lập vẫn gửi đúng 1 lần, PostgreSQL ghi `SENT` với số lần thử là 1. | **PASS** |
| 14-11 · UC14 | 2 | Tạo nhắc gắn hoạt động rồi PATCH `activityId=null`. | HTTP 200. Xóa liên kết hoạt động, giữ nội dung và cờ bật. API vẫn có giờ gửi kế tiếp. | HTTP 200. `activity_id` là NULL. Nội dung và cờ bật giữ nguyên, API vẫn có giờ gửi kế tiếp. | **PASS** |
| 14-12 · UC14 | 2 | Đưa tài khoản A lên 20 nhắc, gồm các nhắc đã tắt. Yêu cầu tạo thêm cho A và cho B. | A nhận 409 `REMINDER_LIMIT_REACHED` và giữ nguyên 20 nhắc. B nhận 201 và chỉ B tăng một dòng. | A nhận 409 đúng mã và vẫn có 20 nhắc. B nhận 201 và tăng đúng 1 dòng. | **PASS** |
| 14-13 · UC14 | 2 | Lượt 07:00 thất bại. Tắt nhắc lúc 07:00:15, bật lại lúc 07:00:45 và chạy worker lúc 07:01. | Không gửi lượt có giờ trước lần lưu cấu hình mới. Dòng cũ vẫn `FAILED`, số lần thử là 1. | Worker gửi 1 lượt lúc 07:01. Dòng cũ chuyển sang `SENT`, số lần thử là 2. | **FAIL** · Truy vấn retry dùng cờ bật hiện tại và tuổi lượt gửi, thiếu đối chiếu `scheduled_for` với thời điểm lưu cấu hình. Cùng nhóm lỗi với 14-01. |
| 14-14 · UC14 | 2 | Lượt 07:00 ngày đầu thất bại. Chạy lại worker vào 07:00 ngày kế tiếp. | Gửi lượt mới đúng một lần. Lượt ngày đầu giữ `FAILED`, lượt ngày sau là `SENT`. | Worker chỉ gửi lượt ngày sau. PostgreSQL có hai dòng theo hai giờ hẹn, trạng thái lần lượt `FAILED`, `SENT`, mỗi dòng 1 lần thử. | **PASS** |
| 14-15 · UC14 | 2 | Dùng trigger gây lỗi ghi `SENT` cho lượt gửi đầu của A sau khi kênh giả lập đã nhận. B đến hạn cùng chu kỳ. Chạy thêm một chu kỳ. | Worker tiếp tục gửi B. Chu kỳ sau không gửi trùng A. PostgreSQL giữ A `PENDING`, B `SENT`. | Chu kỳ đầu ghi nhận 1 thành công và 1 lỗi, kênh giả lập nhận mỗi người đúng một lượt. Chu kỳ sau trả 0 gửi, 0 lỗi. Dòng A `PENDING`, B `SENT`. | **PASS** · Trong ca giả lập này, dòng `PENDING` còn lại sau khi kênh đã nhận lượt A và thao tác ghi `SENT` thất bại. |
| 14-16 · UC14 | 2 | Hẹn 02:30 tại `America/New_York` ngày đồng hồ nhảy qua giờ đó. Chạy trước, đúng và sau 07:30Z. | Lượt được dời đến 03:30 địa phương, tương ứng 07:30Z, và chỉ ghi một dòng gửi. | Không gửi trước 07:30Z. Gửi một lượt đúng 07:30Z, không gửi lại sau 1 phút. PostgreSQL có một dòng với `scheduled_for=07:30Z`. | **PASS** |
| 18-01 · UC18 | 2 | Dùng cùng access cookie gọi API quản trị trước khi cấp quyền, sau khi cấp role và sau khi thu hồi role. | HTTP lần lượt 403, 200, 403 `ACCESS_DENIED`. PostgreSQL chỉ giữ role gốc `CONTENT_EXPERT`. | Cùng cookie nhận 403, 200, rồi 403 `ACCESS_DENIED`. PostgreSQL chỉ còn `CONTENT_EXPERT`. | **PASS** |

