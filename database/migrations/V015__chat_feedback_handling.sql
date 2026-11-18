-- UC-17 B. Xử lý phản hồi câu trả lời AI: chuyên gia nội dung lọc phản hồi chưa hữu ích, đánh dấu
-- đã xử lý và nhập ghi chú hành động.
-- Trạng thái nằm trên chat.feedback để lọc hàng đợi theo trạng thái và thời gian. Người xử lý, thời
-- điểm và ghi chú hành động ghi vào knowledge.review_events (subject_type = 'FEEDBACK'), cùng chỗ với
-- quyết định duyệt bài viết, mẫu kế hoạch và quy tắc.
-- Bảng vẫn không có cột nào trỏ tới người gửi (UC-08, UC-17 A3).
ALTER TABLE chat.feedback
  ADD COLUMN status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
  ADD COLUMN resolved_at timestamptz,
  ADD CONSTRAINT feedback_resolved_at CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL));

CREATE INDEX feedback_queue_idx ON chat.feedback (status, created_at DESC);
