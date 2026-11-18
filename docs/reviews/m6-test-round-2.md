# Test M6 đợt 2 · Lộ trình sinh hoạt và nhắc nhở (UC-12, UC-13, UC-14)

Jira SCRUM-136, GitHub #166. Kiểm thử Spring Boot tại commit `4bc08a85ca224db3864692051e7d9b4b91fa010f` của `main`, sau các bản sửa lỗi của đợt 1 (#151, #157, #161, #162). Dữ liệu giả lập, MockMvc, PostgreSQL nhúng với migration Flyway, kênh gửi nhắc giả lập; worker gửi nhắc và job dọn dữ liệu được chạy tay với đồng hồ cố định. [Kết quả JSON](m6-test-round-2.json). Đợt 1: [backend-testing-2026-11-16.md](backend-testing-2026-11-16.md).

## Kết quả chạy

| Phạm vi | Tổng | Đạt | Thất bại |
| --- | ---: | ---: | ---: |
| Ca M6 của đợt 1, chạy lại làm test hồi quy | 26 | 26 | 0 |
| Ca mới của đợt 2 | 7 | 6 | 1 |
| Toàn bộ test M6 (`vn.edu.uit.se214.health.lifestyle`) | 114 | 114 | 0 |
| Toàn bộ backend qua `mvn verify` | 220 | 220 | 0 |

Năm ca thất bại ở đợt 1 (14-01, 14-02, 14-03, 14-08, 14-13) đều đạt. Ca 14-01, 14-08 và 14-13 đã có test tương ứng trên `main` từ #162, nên bảng dưới dẫn tới test đó thay vì thêm bản trùng. Ca R2-07 là bản thử nằm ngoài repo (chưa commit) vì đang thất bại; test hồi quy sẽ vào cùng bản sửa #167. Số `mvn verify` không gồm R2-07.

## Lỗi phát hiện

| Lỗi | Ca | Mức | GitHub | Jira | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| Lịch sử lộ trình không đúng thứ tự mới nhất trước khi đổi lộ trình nhiều lần trong một ngày. `lifestyle.plans` chỉ có cột ngày nên các lộ trình cùng ngày được xếp theo UUID; khi vượt `LIFESTYLE_HISTORY_LIMIT`, lộ trình vừa kết thúc có thể bị cắt. | R2-07 | Thấp | #167 | SCRUM-165 | Mở |

## Ghi chú

- Nhắc đang tạm tắt do đổi lộ trình mà người dùng tắt lại bằng tay vẫn giữ lý do `PLAN_SWITCH`, nên job dọn dữ liệu xóa nó sau 90 ngày như các nhắc tạm tắt khác (R2-04). Đây là quy tắc đã chốt trên #60; giao diện (epic SCRUM-147) nên báo cho người dùng biết nhắc tạm tắt sẽ bị xóa sau thời hạn này.
- Bật lại một nhắc tạm tắt mà hoạt động không còn trong lộ trình mới trả 404 `ACTIVITY_NOT_IN_PLAN` trước khi xét giới hạn 20 (R2-02). Người dùng phải chọn hoạt động mới hoặc bỏ liên kết.
- Các mục trong mô tả Jira đã có test: chọn lộ trình (12-01…12-06, `LifestylePlanServiceTest`), ghi nhận trùng ngày (13-04, `markingTheSameActivityTwiceKeepsOneRow`), chuỗi ngày (`streaksCountConsecutiveDaysPerActivityAndForTheWholePlan`, `aGapBreaksTheStreakAndAnUnfinishedTodayCountsFromYesterday`, R2-06), nhắc đúng giờ (`ReminderScheduleTest`, `vietnamEveningIsAfternoonUtc`, 14-09, 14-16).

## Số test M6 theo lớp

| Lớp | Tổng | Thất bại |
| --- | ---: | ---: |
| `LifestyleApiTest` | 8 | 0 |
| `LifestylePlanApiTest` | 13 | 0 |
| `LifestylePlanServiceTest` | 14 | 0 |
| `LifestyleReminderApiTest` | 18 | 0 |
| `LifestyleRetentionTest` | 1 | 0 |
| `LifestyleServiceTest` | 11 | 0 |
| `ProgressCalculatorTest` | 18 | 0 |
| `ReminderDispatcherTest` | 25 | 0 |
| `ReminderScheduleTest` | 6 | 0 |

## Chi tiết từng ca

| Ca | Use case | Test | Cách thử | Mong đợi | Đợt 1 | Đợt 2 |
| --- | --- | --- | --- | --- | --- | --- |
| 12-01 | UC12 | `LifestylePlanApiTest#consentIsCheckedAgainAtConfirmation` | Xem trước mẫu B khi đang dùng A, rút đồng ý xử lý dữ liệu sức khỏe rồi xác nhận B. | 403 `HEALTH_CONSENT_REQUIRED`, giữ A đang hoạt động. | PASS | **PASS** |
| 12-02 | UC12 | `LifestylePlanApiTest#aTemplateRetiredAfterPreviewCannotBeApplied` | Xem trước B, chuyển B sang `RETIRED` rồi xác nhận. | 404 `LIFESTYLE_TEMPLATE_NOT_FOUND`, vẫn là A. | PASS | **PASS** |
| 12-03 | UC12 | `LifestylePlanApiTest#anAllergyAddedAfterPreviewBlocksConfirmation` | Xem trước mẫu có đậu phộng, thêm dị ứng đậu phộng vào hồ sơ rồi xác nhận. | 409 `TEMPLATE_HAS_ALLERGEN`, không tạo lộ trình từ mẫu bị chặn. | PASS | **PASS** |
| 12-04 | UC12 | `LifestylePlanApiTest#twoConfirmationsAtOnceLeaveOneActivePlan` | Hai `PUT /lifestyle/plan` cùng đổi A sang B, chờ cùng một khóa. | Một 200 `changed=true`, một 409 `LIFESTYLE_PLAN_CONFLICT`; một lộ trình đang dùng, A trong lịch sử. | PASS | **PASS** |
| 12-05 | UC12 | `LifestylePlanApiTest#removingARequiredProfileAfterPreviewBlocksConfirmation` | Xem trước mẫu cần hồ sơ, xóa hồ sơ rồi xác nhận. | 409 `HEALTH_PROFILE_REQUIRED`, giữ lộ trình đang dùng. | PASS | **PASS** |
| 12-06 | UC12–13 | `LifestylePlanApiTest#confirmingTheActivePlanAgainKeepsItAndItsProgress` | Đánh dấu một hoạt động rồi xác nhận lại mẫu đang dùng. | 200 `changed=false`, giữ ID lộ trình và tiến độ. | PASS | **PASS** |
| 13-01 | UC12–13 | `LifestylePlanApiTest#afterASwitchAnOldActivityCannotBeMarked` | Hoàn thành hoạt động của A, đổi sang B, đánh dấu lại hoạt động chỉ có trong A. | 404 `ACTIVITY_NOT_IN_PLAN`, dòng hoàn thành cũ còn nguyên. | PASS | **PASS** |
| 13-02 | UC12–13 | `LifestylePlanApiTest#completionsDoNotCarryOverToAPlanWithTheSameActivityId` | A và B cùng có mã `sleep-time`. Hoàn thành trên A, đổi sang B. | B báo chưa hoàn thành; mỗi lộ trình một dòng. | PASS | **PASS** |
| 13-03 | UC13 | `LifestyleApiTest#yesterdayCanBeMarkedButNotUnmarked` | Đánh dấu hôm qua rồi bỏ đánh dấu hôm qua. | PUT 200, chuỗi ngày 1; DELETE 409 `COMPLETION_LOCKED`. | PASS | **PASS** |
| 13-04 | UC13 | `LifestyleApiTest#twoMarksAtOnceStoreOneRow` | Hai PUT đánh dấu cùng hoạt động, cùng ngày, chạy song song. | Cả hai 200; PostgreSQL có một dòng. | PASS | **PASS** |
| 14-01 | UC14 | `ReminderDispatcherTest#aFailedSendIsNotRetriedAfterTheTimeIsChanged` | Lượt 07:00 lỗi, đổi giờ sang 08:00, worker chạy 07:01. | Không gửi lại lượt theo giờ cũ. | FAIL (sửa ở #162) | **PASS** |
| 14-02 | UC14 | `ReminderDispatcherTest#aRetryWriteFailureDoesNotStopOtherReminders` | Trigger làm lỗi lúc ghi `SENT` cho lượt thử lại; một nhắc khác đến hạn cùng chu kỳ. | Chu kỳ không dừng, nhắc kia vẫn được gửi. | FAIL (sửa ở #151) | **PASS** |
| 14-03 | UC12–14 | `LifestyleReminderApiTest#aReminderOfAnActivityTheNewPlanLacksIsNotSent` | Nhắc gắn hoạt động của A, đổi sang B, chạy worker ở giờ nhắc. | Đổi lộ trình báo `remindersTurnedOff=1`, nhắc có `disabledReason=PLAN_SWITCH`, worker gửi 0. | FAIL (chờ chốt) (sửa ở #157) | **PASS** |
| 14-04 | UC14 | `LifestyleReminderApiTest#turningOffAfterAFailedSendStopsTheRetry` | Lượt đầu lỗi, PATCH `enabled=false`, chạy worker sau một phút. | Không gửi lại, dòng `FAILED` giữ 1 lần thử. | PASS | **PASS** |
| 14-05 | UC14 | `ReminderDispatcherTest#twoWorkersClaimTheSameRetryOnce` | Hai worker cùng đọc một dòng `FAILED` rồi cùng giữ chỗ. | Chỉ một lần gửi; dòng thành `SENT`, 2 lần thử. | PASS | **PASS** |
| 14-06 | UC14 | `LifestyleReminderApiTest#deletingAfterAFailedSendRemovesTheRetry` | Lượt đầu lỗi, xóa nhắc qua API, chạy worker trong cửa sổ thử lại. | Không gửi, không còn dòng giao nhận. | PASS | **PASS** |
| 14-07 | UC14 | `ReminderDispatcherTest#aFailedSendIsNotRetriedAfterTheWindow` | Lượt đầu lỗi, chạy worker sau 10 phút 1 giây. | Không thử lại ngoài cửa sổ. | PASS | **PASS** |
| 14-08 | UC14 | `ReminderDispatcherTest#turningOffBetweenReadingAndClaimingStopsTheRetry` | Tắt nhắc sau khi worker đọc danh sách thử lại, trước khi giữ chỗ. | Không gửi, dòng vẫn `FAILED`. | FAIL (sửa ở #162) | **PASS** |
| 14-09 | UC14 | `ReminderDispatcherTest#aRepeatedLocalTimeAtDstFallbackIsSentOnce` | 01:30 `America/New_York` ngày lùi giờ, chạy ở cả hai thời điểm UTC. | Gửi đúng một lần, ở lần sớm hơn. | PASS | **PASS** |
| 14-10 | UC12–14 | `LifestyleReminderApiTest#aReminderWithoutActivityIsStillSentAfterASwitch` | Nhắc độc lập, đổi lộ trình, chạy worker ở giờ nhắc. | Vẫn gửi đúng một lần. | PASS | **PASS** |
| 14-11 | UC14 | `LifestyleReminderApiTest#unlinkingTheActivityKeepsContentAndState` | Nhắc gắn hoạt động, PATCH `activityId=null`. | 200, bỏ liên kết, giữ nội dung và trạng thái bật. | PASS | **PASS** |
| 14-12 | UC14 | `LifestyleReminderApiTest#theLimitIsPerUserAndCountsRemindersTheUserTurnedOff` | Đưa A lên 20 nhắc (kể cả nhắc tự tắt), tạo thêm cho A và cho B. | A 409 `REMINDER_LIMIT_REACHED`, B 201. | PASS | **PASS** |
| 14-13 | UC14 | `ReminderDispatcherTest#aFailedSendIsNotRetriedAfterTurningOffAndOnAgain` | Lượt 07:00 lỗi, tắt rồi bật lại trước 07:01. | Không gửi lại lượt trước lần lưu mới. | FAIL (sửa ở #162) | **PASS** |
| 14-14 | UC14 | `ReminderDispatcherTest#yesterdaysFailureDoesNotBlockTodaysSend` | Lượt hôm trước lỗi, chạy worker 07:00 hôm sau. | Gửi lượt mới một lần; lượt cũ giữ `FAILED`. | PASS | **PASS** |
| 14-15 | UC14 | `ReminderDispatcherTest#aFailedSentWriteIsNotSentTwice` | Lỗi ghi `SENT` sau khi kênh đã nhận; chạy thêm một chu kỳ. | Không gửi trùng; nhắc khác vẫn được gửi. | PASS | **PASS** |
| 14-16 | UC14 | `ReminderDispatcherTest#aSkippedLocalTimeAtDstSpringForwardIsSentOnceLater` | 02:30 `America/New_York` ngày nhảy giờ. | Dời sang 03:30 địa phương, gửi một lần. | PASS | **PASS** |
| R2-01 | UC12–14 | `LifestyleReminderApiTest#aPausedReminderIsOfferedBackAndSentOnlyOnceTheUserTurnsItOn` | Nhắc của A bị tạm tắt khi đổi sang B; đổi lại A; chạy worker; bật lại; chạy worker ngày sau. | Đổi lại trả nhắc trong `restorableReminderIds` nhưng không tự bật, worker gửi 0; sau khi bật gửi đúng một lần. | — | **PASS** |
| R2-02 | UC14 | `LifestyleReminderApiTest#turningOnAPausedReminderAtTheLimitIsRefusedAndNothingChanges` | Có nhắc tạm tắt và 20 nhắc được tính; bật nhắc tạm tắt (giữ và bỏ hoạt động). | Giữ hoạt động không còn trong lộ trình: 404 `ACTIVITY_NOT_IN_PLAN`. Bỏ hoạt động: 409 `REMINDER_LIMIT_REACHED`. Nhắc giữ nguyên. | — | **PASS** |
| R2-03 | UC14 | `LifestyleReminderApiTest#editingAPausedReminderKeepsItPaused` | Sửa nội dung và giờ của nhắc tạm tắt, chạy worker ở giờ mới. | 200, vẫn tắt, `disabledReason=PLAN_SWITCH`, không có `nextSendAt`, worker gửi 0. | — | **PASS** |
| R2-04 | UC14 | `LifestyleReminderApiTest#theCleanupJobRemovesOnlyRemindersPausedByASwitch` | Hai nhắc tạm tắt (một bị tắt lại bằng tay) và một nhắc người dùng tự tắt; chạy job trước và sau 90 ngày. | Trước hạn không xóa. Sau hạn xóa hai nhắc tạm tắt, giữ nhắc tự tắt; đổi lại lộ trình không còn gì để gợi ý. | — | **PASS** |
| R2-05 | UC12 | `LifestylePlanApiTest#historyShowsTheUsersEndedPlansUpToTheLimitAndNeedsConsent` | Đổi lộ trình nhiều hơn `LIFESTYLE_HISTORY_LIMIT`, đọc lịch sử, rồi rút đồng ý. | Trả đúng số theo giới hạn, chỉ lộ trình đã kết thúc; sau khi rút đồng ý: 403 `HEALTH_CONSENT_REQUIRED`. | — | **PASS** |
| R2-06 | UC12–13 | `LifestylePlanApiTest#choosingATemplateAgainStartsANewPlanWithoutTheOldProgress` | Hoàn thành mọi hoạt động hôm nay (chuỗi 1), đổi sang mẫu khác rồi chọn lại mẫu cũ. | Lộ trình mới có ID mới, chuỗi 0, chưa hoàn thành; lộ trình cũ giữ 2 dòng hoàn thành. | — | **PASS** |
| R2-07 | UC12 | `SameDayHistoryOrderProbeTest#plansEndedTheSameDayComeLatestFirst` | Hai lộ trình bắt đầu và kết thúc cùng ngày; lộ trình đổi sau có UUID lớn hơn. | Lịch sử trả lộ trình kết thúc sau ở đầu. | — | **FAIL** · #167 / SCRUM-165 |
