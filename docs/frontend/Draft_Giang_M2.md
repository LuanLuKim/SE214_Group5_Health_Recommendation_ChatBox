# Ghi chú cho Giang: draft layout giao diện (M2)

| Mục          | Nội dung                                                                                                                                                                                          |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Việc         | Draft layout phần hướng dẫn giao diện và các màn hình bên dưới                                                                                                                                    |
| Issue GitHub | [#185](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/185) (issue cha [#183](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/183)) |
| Jira         | SCRUM-169 (task cha SCRUM-167, epic SCRUM-147)                                                                                                                                                    |
| Hạn nộp      | **30/11**                                                                                                                                                                                         |
| Làm cùng     | Tài ([#184](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/184), file [Draft_Tai_M5.md](Draft_Tai_M5.md))                                                         |

## Lịch giao diện

| Giai đoạn                                | Thời gian                  |
| ---------------------------------------- | -------------------------- |
| Draft layout (Giang, Tài)                | 22/11 – **30/11**          |
| Các thành viên code giao diện theo draft | 1/12 – 15/12               |
| Kiểm thử chung                           | 13/12 – 14/12 (giữ nguyên) |
| Bàn giao                                 | 16/12 – 17/12 (giữ nguyên) |

Lịch kiểm thử và bàn giao không dời, nên màn hình nào code xong thì đưa vào `main` sớm để kịp kiểm thử 13–14/12; 15/12 là hạn cuối sửa giao diện.

## Phần chung: hướng dẫn giao diện

- Bảng màu, cỡ chữ, khoảng cách; nút, ô nhập, bảng, thẻ, thông báo thành công/lỗi, hộp xác nhận.
- Nhãn trạng thái nội dung: nháp, chờ duyệt, đã duyệt, trả lại.
- **Chốt với Tài trước 26/11**: Giang làm hướng dẫn giao diện, Tài làm khung ứng dụng (header, menu theo vai trò). Hai bên dùng chung để màn hình đồng bộ.

## Màn hình

| UC    | Màn hình                                                                            | Issue                                                                                      |
| ----- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| UC-06 | Chat streaming, nguồn, gợi ý chuyên khoa, lời miễn trừ (màn hình chính của chatbox) | [#117](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/117) |
| UC-08 | Nút Hữu ích / Chưa hữu ích, biểu mẫu lý do                                          | [#118](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/118) |
| UC-09 | Lịch sử hội thoại                                                                   | [#119](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/119) |
| UC-10 | Trang kiến thức và chi tiết (nguồn, ngày cập nhật)                                  | [#121](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/121) |
| UC-11 | Tra cứu cơ sở y tế và chi tiết (nguồn, ngày xác minh)                               | [#122](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/122) |
| UC-15 | Quản lý bài viết và mẫu kế hoạch                                                    | [#123](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/123) |
| UC-16 | Soạn và xem quy tắc theo trạng thái                                                 | [#120](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/120) |
| UC-17 | Quản trị nội dung dùng chung: hàng đợi duyệt, kiểm định agent, phản hồi AI          | [#124](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/124) |
| UC-17 | Phần quy tắc và tài liệu M7 (kèm cờ cảnh báo) trong màn hình trên                   | [#128](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/128) |

Màn hình chat cần thể hiện: câu trả lời đang stream, nguồn trích dẫn, gợi ý chuyên khoa, lời miễn trừ, và khung cảnh báo khẩn cấp khi Safety Gate chặn câu hỏi.

Giao diện UC-16 đã có bản code ở commit `9679652` (nhánh `42-m1-uc16-warning-rules`), có thể tham khảo bố cục.

Có thể đổi màn hình với Tài, chỉ cần báo lại trên issue #183.

## Yêu cầu draft

- [ ] Công cụ: Figma (hoặc Penpot, draw.io). Bản thiết kế tĩnh, **không cần code**, không cần PR.
- [ ] Mỗi frame ghi mã UC và số issue, ví dụ `UC-06 · #117`.
- [ ] Bố cục desktop 1280px. Màn hình chat (UC-06, 08, 09) có thêm bản điện thoại 360px.
- [ ] Mỗi màn hình có trạng thái: đang tải, rỗng, lỗi. Với thao tác ghi (lưu, xóa, gửi), chỉ báo "đã lưu" sau khi server trả thành công.
- [ ] Chữ tiếng Việt, ngày `dd/mm/yyyy`, giờ 24h, ghi rõ đơn vị đo (NFR-22).
- [ ] Đúng phân quyền: Chuyên gia nội dung **không** xem được toàn bộ hội thoại hay hồ sơ sức khỏe của người dùng; phản hồi AI hiển thị ẩn danh ([PROJECT_CONTEXT.md mục 3](../requirements/PROJECT_CONTEXT.md#3-roles-and-privacy-boundaries)).
- [ ] Trường dữ liệu lấy theo [docs/API.md](../API.md) (tiền tố `/api/v1/...`). Màn hình nào backend chưa có API thì vẽ theo đặc tả UC: [docs/requirements/Dac_ta_Use_Case_chuan_hoa_19_UC.docx](../requirements/Dac_ta_Use_Case_chuan_hoa_19_UC.docx).
- [ ] Chia màn hình theo module như code: `apps/web` (React 19, TypeScript, Vite), mỗi module một trang `apps/web/src/modules/<module>/Page.tsx`. Như vậy 1/12 chuyển sang code được ngay.

## Cách nộp (hạn 30/11)

1. Mở quyền xem Figma cho "ai có link".
2. Xuất các frame ra PNG hoặc PDF.
3. Comment trên issue của mình theo mẫu dưới, kéo thả file vào ô comment để đính kèm.

```text
Nộp draft
- Figma: <link>
- File: (đính kèm PNG/PDF)
- Màn hình đã có: UC-..., UC-...
- Còn thiếu / câu hỏi: ...
```

Nộp nhiều lần cũng được (bản sơ bộ trước, bản đủ sau): mỗi lần một comment. Scrum Master sẽ phản hồi ngay trên issue.

Nhánh GitHub tự tạo khi gán issue có thể để trống: draft không cần push code.

## Sau khi nộp

- Scrum Master duyệt draft, ghi nhận xét trên issue.
- Trước 1/12 phân công 19 issue giao diện (#110 – #128) cho các thành viên; mọi người code theo draft đã duyệt.
