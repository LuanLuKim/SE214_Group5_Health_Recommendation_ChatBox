-- UC-12 Chọn lộ trình sinh hoạt.
-- 1. Mẫu lộ trình có thêm thông tin để xem trước (mô tả, thời lượng, đối tượng không phù hợp) và
--    cờ requires_profile (UC-12 A1: mẫu chỉ áp dụng khi người dùng đã có hồ sơ sức khỏe).
-- 2. Mục tiêu chỉ nhận 4 giá trị của FR-2.6.
-- 3. Giữ lịch sử lộ trình: đổi lộ trình đặt ended_on cho dòng cũ và thêm dòng mới; mỗi người chỉ có
--    một lộ trình đang dùng (ended_on IS NULL). Completion của lộ trình cũ vẫn giữ nguyên.
ALTER TABLE lifestyle.templates
  ADD COLUMN summary text NOT NULL DEFAULT '',
  ADD COLUMN duration_days integer CHECK (duration_days > 0),
  ADD COLUMN not_suitable_for text[] NOT NULL DEFAULT '{}',
  ADD COLUMN requires_profile boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT templates_goal_check CHECK (goal IN ('SLEEP', 'MAINTAIN', 'GAIN', 'LOSE'));

ALTER TABLE lifestyle.plans DROP CONSTRAINT plans_user_id_key;
ALTER TABLE lifestyle.plans
  ADD COLUMN ended_on date,
  ADD CONSTRAINT plans_ended_after_start CHECK (ended_on IS NULL OR ended_on >= started_on);
CREATE UNIQUE INDEX plans_one_active_per_user ON lifestyle.plans (user_id) WHERE ended_on IS NULL;
