# Ghi chú cho Quỳnh: duyệt mẫu cuối lần chạy 5 (M1)

| Mục          | Nội dung                                                                                                                             |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------ |
| Việc         | Duyệt 40 chunk ngẫu nhiên của lần chạy 5, lần duyệt cuối trước khi nạp dữ liệu                                                       |
| File duyệt   | [SCRUM-148_Duyet-mau-40-chunk-lan-chay-5.xlsx](SCRUM-148_Duyet-mau-40-chunk-lan-chay-5.xlsx) (mở link rồi bấm **Download raw file**) |
| Issue GitHub | #130                                                                                                                                 |
| Jira         | SCRUM-148 (subtask của SCRUM-138, UC-17 phần M1)                                                                                     |
| Hạn          | **3/12**                                                                                                                             |

## Kết quả file so sánh A/B

Cảm ơn Quỳnh đã duyệt lại file `SCRUM-148_So-sanh-ban-dich-A-B-40-chunk.xlsx`. Giải mã A/B:

|                     | luna | sol    |
| ------------------- | ---- | ------ |
| Bản tốt hơn (chunk) | 10   | **21** |
| RETURNED            | 6    | **2**  |
| Lỗi Nghiêm trọng    | 0    | 0      |

9 chunk còn lại hai bản ngang nhau. Vì vậy lần chạy 5 dùng LLM **sol**.

## Lần chạy 5 đã làm gì

- Dịch lại toàn bộ 947 chunk tiếng Anh. 3 model dịch miễn phí tạo bản nháp, sau đó LLM sol đối chiếu bản gốc tiếng Anh, gộp và sửa thành bản cuối.
- Prompt có đủ chính sách duyệt (ba mức lỗi, các quy ước đã chốt) và mọi dòng Quỳnh đã sửa kèm lý do. Cả 117 dòng Quỳnh sửa được áp thẳng vào dữ liệu, không dòng nào bị lệch.
- Sau khi dịch, bản dịch qua cổng luật cố định (số, đơn vị, phủ định, hướng, thuật ngữ), rồi LLM đọc lại nghĩa, sửa một lần và đọc lại lần nữa. Chunk không qua được thì gắn cờ chờ người duyệt, không tự đạt.
- Lỗi "Nghị quyết 74,8" ở file A/B đã được sửa: ký hiệu nghị quyết giữ nguyên dạng "WHA74.8".

Kết quả trên toàn bộ dữ liệu, so với lần chạy 4:

|                        | Lần chạy 4 | Lần chạy 5  |
| ---------------------- | ---------- | ----------- |
| Qua cổng kiểm định     | 944/947    | **947/947** |
| Tự đạt (auto_verified) | 695        | **781**     |
| Tự đạt kèm cảnh báo    | 99         | 141         |
| Gắn cờ chờ duyệt       | 150        | **25**      |
| Cần người duyệt (tổng) | 298        | **235**     |

Cảnh báo nhiều nhất là còn tham chiếu kiểu Anh (NHS 111, GP...), chủ yếu ở bài NHS. 78 chunk cấp cứu vẫn luôn cần người duyệt như trước.

## File duyệt có gì

- 40 chunk lấy ngẫu nhiên trong 609 chunk tự đạt của lần chạy 5: WHO 30, NHS 10, tổng 463 dòng.
- Không trùng các bộ Quỳnh đã duyệt: bộ 79 chunk cấp cứu, mẫu 82, mẫu 40 của lần chạy 3, và 40 chunk của file A/B.
- Bố cục giống file lần chạy 3:
  - Tab **Duyệt chunk**: chọn APPROVED hoặc RETURNED cho từng chunk.
  - Tab **Đối chiếu từng dòng**: dòng nào sai thì chọn Loại lỗi, Mức độ và ghi Bản Việt đề xuất.
  - Tab **Tiêu chí và bước tiếp**: tiêu chí đạt, ba mức lỗi, việc làm tiếp nếu đạt hoặc không đạt.
  - Tab **Quyết định đã chốt**: hai câu cần Quỳnh xác nhận (mục dưới).

**Tiêu chí đạt:** RETURNED tối đa 4/40 **và** không có dòng nào ở mức Nghiêm trọng. Ô Kết luận ở tab Duyệt chunk tự tính.

## Hai quyết định cần Quỳnh xác nhận

Hai câu này Quỳnh chưa kịp trả lời nên Scrum Master đã chốt để chạy lần 5. Ở tab **Quyết định đã chốt**, chọn **Đồng ý** hoặc **Không đồng ý** và ghi lý do nếu không đồng ý.

1. **Mẫu 15, dòng 2 (lần chạy 3)**: "…until you've not been sick **or** had diarrhoea for at least 2 days" bị dịch thành "…không còn nôn **hoặc** bị tiêu chảy". Đã chốt mức **Nghiêm trọng** (trước ghi Vừa), vì đây là lỗi đảo điều kiện: nguồn yêu cầu hết **cả** hai.
2. **"neonatal tetanus"**: đã chốt dịch là **"uốn ván sơ sinh"**. Đây là tên bệnh nên không áp quy ước "newborn → trẻ dưới 28 ngày tuổi".

## Cách nộp (hạn 3/12)

1. Tải file Excel, điền các ô màu vàng.
2. Comment trên issue #130, kéo thả file `.xlsx` đã điền vào ô comment để đính kèm, kèm tóm tắt theo mẫu:

```text
Nộp duyệt lần chạy 5
- File: (đính kèm .xlsx)
- APPROVED / RETURNED: .../...
- Dòng Nghiêm trọng: ...
- Hai quyết định đã chốt: Đồng ý / Không đồng ý (lý do)
- Ghi chú khác: ...
```

Gửi qua Zalo cũng được; Scrum Master sẽ đính kèm lên Jira SCRUM-148 và ghi nguồn.

## Sau khi duyệt

- **Đạt:** giữ cách dịch hiện tại. Câu Quỳnh sửa trong file được áp vào dữ liệu. Dữ liệu được nạp vào `knowledge.*` (PR #176) ở trạng thái DRAFT và chỉ vào RAG sau khi được duyệt ở UC-17.
- **Không đạt:** câu Quỳnh sửa vẫn được áp. Chunk có lỗi và các chunk cùng loại lỗi bị gắn cờ chờ duyệt, không vào RAG. Luân tổng hợp lỗi và báo nhóm quyết định trước khi nạp.
