# Ca kiểm thử backend đợt 1

Gói này chứa mã test của các ca bổ sung trong [Test đợt 1](../../../../docs/reviews/backend-testing-2026-11-16.md). Mục đích: thành viên chạy lại được từng ca để tái hiện lỗi, và giữ các ca đã đạt làm test hồi quy.

- Đợt 1 có 40 ca. 26 ca M6 (12-xx, 13-xx, 14-xx) đã có test tương ứng trong `lifestyle` (bảng ở mục cuối), nên không chép lại vào đây.
- 14 ca còn lại nằm trong gói này, viết lại để chạy trên `main` hiện tại.
- Mỗi test có `@DisplayName` bắt đầu bằng mã ca, trùng với mã trong báo cáo và trường `junit_test` của file JSON kết quả.
- Dữ liệu dùng seed giả lập (`app.seed-synthetic=true`) và PostgreSQL nhúng, có chạy migration Flyway. Không cần dịch vụ ngoài.

## Ca của lỗi còn mở

Ca nào có `@KnownBug("#…")` thì đang thất bại vì lỗi chưa sửa. Mặc định các ca này **bị bỏ qua**, nên `mvn verify` và CI vẫn xanh.

| Ca | Lỗi | Chủ module | Hiện tượng khi chạy |
|---|---|---|---|
| 08-01 | #152 (SCRUM-156) | M4 | `ChatService.submitFeedback` nhận tin nhắn role `user`, không ném `ApiException` |
| 08-02 | #152 (SCRUM-156) | M4 | API trả 200 và ghi 1 dòng `chat.feedback` cho tin nhắn role `user` |
| 08-07 | #153 (SCRUM-157) | M4 | API trả 500 khi chia sẻ câu trả lời không có câu hỏi liền trước (ràng buộc `feedback_check`) |

Người sửa lỗi xóa `@KnownBug` ở ca tương ứng, chạy lại thấy đạt, rồi commit cùng bản sửa. Từ đó ca này là test hồi quy chạy trong CI.

## Lệnh chạy

Chạy từ thư mục gốc của repo:

```bash
# Các ca trong gói này; ca @KnownBug bị bỏ qua
mvn -B -f backend/pom.xml test -Dtest='*Audit*'

# Tái hiện lỗi còn mở: bật cả ca @KnownBug (sẽ thất bại cho đến khi sửa xong)
mvn -B -f backend/pom.xml test -Dtest='*Audit*' -Daudit.knownBugs=true

# Một ca cụ thể
mvn -B -f backend/pom.xml test -Dtest='BackendAuditRound1Test#optInForAnswerWithoutPrecedingQuestionReturnsClientError' -Daudit.knownBugs=true

# Toàn bộ backend
mvn -B -f backend/pom.xml verify
```

Khi chạy trên `main` hiện tại: 14 ca, 11 đạt, 3 bỏ qua. Nếu bật `-Daudit.knownBugs=true`: 11 đạt, 3 thất bại (08-01, 08-02, 08-07).

## Các ca trong gói

| Ca | UC | Test | Kiểm tra |
|---|---|---|---|
| 06-01 | UC06 | `BackendAuditRound1Test#chatWithoutConfiguredAiStreamsFailureAndKeepsOnlyUserTurn` | AI service chưa cấu hình: SSE trả `AI_SERVICE_UNAVAILABLE` rồi `FAILED`, chỉ lưu tin nhắn người dùng |
| 08-01 | UC08 | `ChatFeedbackServiceAuditTest#feedbackOnlyAcceptsAssistantAnswers` | Service từ chối đánh giá tin nhắn role `user` (#152) |
| 08-02 | UC08 | `BackendAuditRound1Test#feedbackApiRejectsUserMessageWithoutSaving` | API trả 4xx và không lưu khi đánh giá tin nhắn role `user` (#152) |
| 08-03 | UC08 | `BackendAuditRound1Test#feedbackOnAnswerWithOptInSavesSnapshot` | Đồng ý chia sẻ thì lưu đúng cặp hỏi–đáp |
| 08-04 | UC08 | `BackendAuditRound1Test#feedbackWithoutOptInDoesNotStoreConversation` | Không chia sẻ thì hai cột hội thoại là NULL |
| 08-05 | UC08 | `BackendAuditRound1Test#feedbackFromAnotherAccountDoesNotWrite` | Tài khoản khác nhận 404, không ghi |
| 08-06 | UC08 | `BackendAuditRound1Test#feedbackForEarlierAnswerExcludesLaterTurns` | Đánh giá câu trả lời cũ không lấy lượt hỏi–đáp sau |
| 08-07 | UC08 | `BackendAuditRound1Test#optInForAnswerWithoutPrecedingQuestionReturnsClientError` | Thiếu câu hỏi liền trước thì trả 4xx, không phải 500 (#153) |
| 09-01 | UC08–09 | `BackendAuditRound1Test#deletingSessionCascadesMessagesAndKeepsAnonymousFeedback` | Xóa phiên xóa tin nhắn, giữ phản hồi ẩn danh đã chia sẻ |
| 10-01 | UC10 | `BackendAuditRound1Test#retiringAnArticleRemovesItFromListAndDetail` | Bài `RETIRED` biến mất khỏi danh sách và chi tiết |
| 10-02 | UC10 | `BackendAuditRound1Test#approvingArticleAloneDoesNotPublishUntilSourceIsVerified` | Bài `APPROVED` chỉ công khai khi có `verified_at` |
| 11-01 | UC11 | `BackendAuditRound1Test#revokingFacilityVerificationImmediatelyHidesIt` | Bỏ `verified_at` thì cơ sở y tế bị ẩn ngay |
| 11-02 | UC11 | `BackendAuditRound1Test#facilityLimitIsAppliedAfterVerificationFilter` | Giới hạn 20 kết quả áp dụng sau khi lọc cơ sở đã xác minh |
| 18-01 | UC18 | `BackendAuditRound1Test#roleRevocationTakesEffectWithSameAccessCookie` | Thu hồi role có hiệu lực ngay với cùng access cookie |

## Ca M6 đã có trên `main`

Tất cả đạt từ Test M6 đợt 2 ([báo cáo](../../../../docs/reviews/m6-test-round-2.md)). Test nằm trong `tests/backend/java/lifestyle/`.

| Ca | Test |
|---|---|
| 12-01 | `LifestylePlanApiTest#consentIsCheckedAgainAtConfirmation` |
| 12-02 | `LifestylePlanApiTest#aTemplateRetiredAfterPreviewCannotBeApplied` |
| 12-03 | `LifestylePlanApiTest#anAllergyAddedAfterPreviewBlocksConfirmation` |
| 12-04 | `LifestylePlanApiTest#twoConfirmationsAtOnceLeaveOneActivePlan` |
| 12-05 | `LifestylePlanApiTest#removingARequiredProfileAfterPreviewBlocksConfirmation` |
| 12-06 | `LifestylePlanApiTest#confirmingTheActivePlanAgainKeepsItAndItsProgress` |
| 13-01 | `LifestylePlanApiTest#afterASwitchAnOldActivityCannotBeMarked` |
| 13-02 | `LifestylePlanApiTest#completionsDoNotCarryOverToAPlanWithTheSameActivityId` |
| 13-03 | `LifestyleApiTest#yesterdayCanBeMarkedButNotUnmarked` |
| 13-04 | `LifestyleApiTest#twoMarksAtOnceStoreOneRow` |
| 14-01 | `ReminderDispatcherTest#aFailedSendIsNotRetriedAfterTheTimeIsChanged` |
| 14-02 | `ReminderDispatcherTest#aRetryWriteFailureDoesNotStopOtherReminders` |
| 14-03 | `LifestyleReminderApiTest#aReminderOfAnActivityTheNewPlanLacksIsNotSent` |
| 14-04 | `LifestyleReminderApiTest#turningOffAfterAFailedSendStopsTheRetry` |
| 14-05 | `ReminderDispatcherTest#twoWorkersClaimTheSameRetryOnce` |
| 14-06 | `LifestyleReminderApiTest#deletingAfterAFailedSendRemovesTheRetry` |
| 14-07 | `ReminderDispatcherTest#aFailedSendIsNotRetriedAfterTheWindow` |
| 14-08 | `ReminderDispatcherTest#turningOffBetweenReadingAndClaimingStopsTheRetry` |
| 14-09 | `ReminderDispatcherTest#aRepeatedLocalTimeAtDstFallbackIsSentOnce` |
| 14-10 | `LifestyleReminderApiTest#aReminderWithoutActivityIsStillSentAfterASwitch` |
| 14-11 | `LifestyleReminderApiTest#unlinkingTheActivityKeepsContentAndState` |
| 14-12 | `LifestyleReminderApiTest#theLimitIsPerUserAndCountsRemindersTheUserTurnedOff` |
| 14-13 | `ReminderDispatcherTest#aFailedSendIsNotRetriedAfterTurningOffAndOnAgain` |
| 14-14 | `ReminderDispatcherTest#yesterdaysFailureDoesNotBlockTodaysSend` |
| 14-15 | `ReminderDispatcherTest#aFailedSentWriteIsNotSentTwice` |
| 14-16 | `ReminderDispatcherTest#aSkippedLocalTimeAtDstSpringForwardIsSentOnceLater` |

## Thêm ca mới

- Đặt test vào gói này, `@DisplayName` bắt đầu bằng mã ca (ví dụ `08-08 · UC08 · …`).
- Ca PostgreSQL dùng chung `BackendAuditRound1Test` (một Spring context, một database nhúng riêng `backend_audit_round1`). Ca thay đổi dữ liệu thì để `@Transactional` để tự rollback. Ca cần lỗi xảy ra bên trong request thì dọn dữ liệu trong `finally`, như 08-07.
- Ca phát hiện lỗi mới: mở issue, gắn `@KnownBug("#số issue")`, ghi vào bảng trên và vào báo cáo trong `docs/reviews/`.
