-- UC-12 lịch sử lộ trình (SCRUM-165, #167): ended_on chỉ có ngày, nên các lộ trình kết thúc trong cùng
-- một ngày không xếp được theo thứ tự mới nhất trước. ended_at ghi thời điểm kết thúc khi đổi lộ trình.
-- Dòng cũ để NULL và giữ thứ tự cũ (ended_on, started_on, id).
ALTER TABLE lifestyle.plans ADD COLUMN ended_at timestamptz;
