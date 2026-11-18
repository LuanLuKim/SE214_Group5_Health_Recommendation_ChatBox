# Điều khoản sử dụng nguồn: Bộ Y tế (moh.gov.vn) và Báo Sức khỏe & Đời sống

Ghi chú phục vụ quyết định có thêm nguồn Bộ Y tế vào dữ liệu M7 (issue #106, mục 6 của phạm vi) và cho phần "Nguồn dữ liệu và quyền sử dụng" của báo cáo. Ngày truy cập các trang dưới đây: 15/11/2026.

## 1. Cổng thông tin điện tử Bộ Y tế (https://moh.gov.vn)

**robots.txt** (https://moh.gov.vn/robots.txt) chỉ có một dòng:

```
User-agent: *
```

Không có `Disallow`, `Crawl-delay` hay `Sitemap`: robots.txt không chặn trang nào.

**Quy định về sử dụng thông tin.** Cổng không có trang "Điều khoản sử dụng" hay "Quy chế" riêng; các link ở chân trang chỉ gồm Giới thiệu, Thông tin liên hệ và Sơ đồ trang. Quy định duy nhất nằm ở chân trang (khối `main_footer`), nguyên văn:

> CỔNG THÔNG TIN ĐIỆN TỬ BỘ Y TẾ (MOH)
> Bản quyền thuộc Bộ Y Tế
> Số 138A Giảng Võ, Giảng Võ, Hà Nội
> ĐT: 0246.273.2.273 | Fax: 0243.8464.051
> Email: banbientap@moh.gov.vn
> Ghi rõ nguồn Cổng thông tin điện tử Bộ Y tế hoặc http://moh.gov.vn khi phát hành lại thông tin

Cách hiểu: nội dung thuộc bản quyền của Bộ Y tế; được phát hành lại với điều kiện ghi rõ nguồn. Câu này không nói gì về truy cập tự động, lưu trữ hay dùng cho hệ thống AI.

**Cấu trúc kỹ thuật.** Trang là ứng dụng JavaScript: HTML tải về chỉ có khung (tiêu đề "Cổng thông tin" và các thẻ `<script>`), nội dung được nạp sau bằng script. URL có dạng `/index.jsp?pageId=…&cid=…` (chuyên mục) và `/index.jsp?pageId=…&aid=…&cid=…` (bài viết), không có slug. Phải dựng trang bằng trình duyệt không giao diện (headless) mới đọc được nội dung và chân trang. Crawler hiện tại (`tools/crawl`) chỉ đọc HTML tĩnh theo template nên **không crawl được** nguồn này nếu không thêm bước dựng trang.

**Loại nội dung.** Trang chủ và các chuyên mục chủ yếu là tin hoạt động của Bộ, văn bản, chế độ chính sách ngành y tế và báo cáo thống kê. Ít bài hướng dẫn sức khỏe cho người dân theo kiểu NHS hay fact sheet WHO.

## 2. Báo điện tử Sức khỏe & Đời sống (https://suckhoedoisong.vn), cơ quan ngôn luận của Bộ Y tế

**robots.txt** (https://suckhoedoisong.vn/robots.txt):

```
User-agent: *
Allow: /
Disallow: /tim-kiem.htm
Sitemap: https://suckhoedoisong.vn/sitemaps/index.rss
Sitemap: https://suckhoedoisong.vn/sitemaps/googlenews.ashx
```

Chỉ chặn trang tìm kiếm; không có `Crawl-delay`.

**Quy định về sử dụng thông tin.** Chân trang ghi nguyên văn:

> © Bản quyền thuộc Báo điện tử Sức khỏe và Đời sống - Cơ quan ngôn luận của Bộ Y tế.
> Giấy phép hoạt động báo chí số 390/GP-BTTTT do Bộ Thông tin và Truyền thông cấp ngày 25/06/2021

Không có trang điều khoản, cũng không có câu cho phép phát hành lại hay yêu cầu ghi nguồn. Đây là báo chí có bản quyền; chưa có căn cứ để sao chép nội dung vào kho dữ liệu.

## 3. So sánh với các nguồn đang dùng

| Nguồn | robots.txt | Quy định sử dụng | Cách dùng trong dự án |
| --- | --- | --- | --- |
| NHS (nhs.uk) | crawler tuân thủ robots.txt | [Terms and conditions](https://www.nhs.uk/our-policies/terms-and-conditions/); thông tin khu vực công theo Open Government Licence v3.0, bản chuyển thể/dịch cần rà riêng mục 3 | `collect_text`, `internal_review_only`, không phát hành công khai |
| WHO (who.int) | crawler tuân thủ robots.txt | [Terms of use](https://www.who.int/about/policies/terms-of-use) | `collect_text`, `internal_review_only`, không phát hành công khai |
| Bộ Y tế (moh.gov.vn) | không chặn trang nào | "Bản quyền thuộc Bộ Y Tế"; phát hành lại phải ghi rõ nguồn | chưa thêm |
| Sức khỏe & Đời sống | chỉ chặn trang tìm kiếm | "© Bản quyền thuộc Báo điện tử Sức khỏe và Đời sống"; không có điều khoản cho phép dùng lại | không dùng |

## 4. Đánh giá và đề xuất

1. **Về quyền sử dụng:** Bộ Y tế cho phép phát hành lại thông tin nếu ghi rõ nguồn. Cách dùng của dự án phù hợp với điều kiện này: dữ liệu chỉ dùng nội bộ (`internal_review_only`), mỗi chunk giữ `source_name` và `source_url`, câu trả lời của chatbot trích nguồn. Nếu thêm, chính sách nguồn trong `tools/crawl/source_policies.json` nên ghi `attribution_required` với chuỗi "Cổng thông tin điện tử Bộ Y tế (moh.gov.vn)".
2. **Về kỹ thuật:** cần bước dựng trang bằng trình duyệt không giao diện, khác với thiết kế hiện tại của crawler (tải HTML tĩnh, 3 giây/request, không chạy JavaScript). Đây là một thay đổi đáng kể, cần test và chính sách riêng (thời gian chờ dựng trang, giới hạn tài nguyên).
3. **Về nội dung:** cổng chủ yếu là tin hoạt động và văn bản chính sách, ít bài hướng dẫn tự chăm sóc. Giá trị cho RAG thấp hơn chi phí thêm bước dựng trang.
4. **Đề xuất:** chưa crawl moh.gov.vn cho RAG. Chỉ dùng Bộ Y tế làm nguồn trích dẫn thủ công cho các thông tin riêng của Việt Nam mà chuyên gia nội dung (UC-15) cần kiểm chứng, ví dụ số cấp cứu 115 hay lịch tiêm chủng mở rộng, kèm ghi nguồn như yêu cầu ở chân trang. Không dùng Báo Sức khỏe & Đời sống vì không có điều khoản cho phép dùng lại.
5. **Quyết định cuối cùng** thuộc về nhóm. Nếu nhóm chọn crawl, cần mở issue riêng cho bước dựng trang và hỏi ý kiến giảng viên về phạm vi sử dụng.
