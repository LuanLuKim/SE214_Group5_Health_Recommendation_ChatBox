# Ghi chú cho Tài: draft layout giao diện (M5)

| Mục          | Nội dung                                                                                                                                                                                          |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Việc         | Draft layout phần khung ứng dụng và các màn hình bên dưới                                                                                                                                         |
| Issue GitHub | [#184](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/184) (issue cha [#183](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/183)) |
| Jira         | SCRUM-168 (task cha SCRUM-167, epic SCRUM-147)                                                                                                                                                    |
| Hạn nộp      | **30/11**                                                                                                                                                                                         |
| Làm cùng     | Giang ([#185](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/185), file [Draft_Giang_M2.md](Draft_Giang_M2.md))                                                   |

## Lịch giao diện

| Giai đoạn                                | Thời gian                  |
| ---------------------------------------- | -------------------------- |
| Draft layout (Tài, Giang)                | 22/11 – **30/11**          |
| Các thành viên code giao diện theo draft | 1/12 – 15/12               |
| Kiểm thử chung                           | 13/12 – 14/12 (giữ nguyên) |
| Bàn giao                                 | 16/12 – 17/12 (giữ nguyên) |

Lịch kiểm thử và bàn giao không dời, nên màn hình nào code xong thì đưa vào `main` sớm để kịp kiểm thử 13–14/12; 15/12 là hạn cuối sửa giao diện.

## Phần chung: khung ứng dụng

- Header, menu (sidebar hoặc thanh trên) **theo vai trò**: Khách, Người dùng, Chuyên gia nội dung, Quản trị viên. Chuyên gia và Quản trị viên vẫn dùng được các tính năng cá nhân của chính mình.
- Điều hướng sau đăng nhập theo vai trò; trang 403, 404, lỗi chung; trạng thái đang tải.
- **Chốt với Giang trước 26/11**: Tài làm khung, Giang làm hướng dẫn giao diện (màu, chữ, nút, ô nhập). Hai bên dùng chung để màn hình đồng bộ.

## Màn hình

| UC    | Màn hình                                          | Issue                                                                                      |
| ----- | ------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| UC-01 | Đăng ký, điều khoản, xác nhận đủ 18 tuổi          | [#110](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/110) |
| UC-02 | Đăng nhập, quên mật khẩu, điều hướng theo vai trò | [#111](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/111) |
| UC-03 | Tài khoản, đổi mật khẩu, xóa tài khoản            | [#112](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/112) |
| UC-04 | Hồ sơ sức khỏe và BMI                             | [#114](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/114) |
| UC-05 | Nhập chỉ số, biểu đồ tuần/tháng                   | [#115](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/115) |
| UC-12 | Chọn và xem lộ trình sinh hoạt                    | [#125](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/125) |
| UC-13 | Theo dõi thói quen hằng ngày                      | [#126](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/126) |
| UC-14 | Thiết lập nhắc nhở                                | [#127](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/127) |
| UC-18 | Quản trị người dùng và phân quyền                 | [#113](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/113) |
| UC-19 | Thống kê hệ thống, bộ lọc ngày                    | [#116](https://github.com/LuanLuKim/SE214_Group5_Health_Recommendation_ChatBox/issues/116) |

Có thể đổi màn hình với Giang, chỉ cần báo lại trên issue #183.

## Yêu cầu draft

- [ ] Công cụ: Figma (hoặc Penpot, draw.io). Bản thiết kế tĩnh, **không cần code**, không cần PR.
- [ ] Mỗi frame ghi mã UC và số issue, ví dụ `UC-01 · #110`.
- [ ] Bố cục desktop 1280px. Màn hình của Người dùng (UC-03, 04, 05, 12, 13, 14) có thêm bản điện thoại 360px.
- [ ] Mỗi màn hình có trạng thái: đang tải, rỗng, lỗi. Với thao tác ghi (lưu, xóa, gửi), chỉ báo "đã lưu" sau khi server trả thành công.
- [ ] Chữ tiếng Việt, ngày `dd/mm/yyyy`, giờ 24h, ghi rõ đơn vị đo (NFR-22).
- [ ] Đúng phân quyền: Quản trị viên **không** thấy hồ sơ sức khỏe, hội thoại hay nội dung phản hồi của người khác ([PROJECT_CONTEXT.md mục 3](../requirements/PROJECT_CONTEXT.md#3-roles-and-privacy-boundaries)).
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
