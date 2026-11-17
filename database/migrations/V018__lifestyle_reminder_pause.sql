-- UC-14: lý do một nhắc đang tắt (SCRUM-162, #158).
-- NULL: nhắc đang bật, hoặc người dùng tự tắt.
-- PLAN_SWITCH: đổi lộ trình làm nhắc tạm tắt vì hoạt động của nó không còn trong lộ trình mới.
-- Nhắc tạm tắt không tính vào giới hạn REMINDERS_MAX_PER_USER; bật lại thì lý do về NULL.
ALTER TABLE lifestyle.reminders
  ADD COLUMN disabled_reason text,
  ADD CONSTRAINT reminders_disabled_reason CHECK (disabled_reason IN ('PLAN_SWITCH')),
  ADD CONSTRAINT reminders_disabled_reason_off CHECK (disabled_reason IS NULL OR NOT enabled);
