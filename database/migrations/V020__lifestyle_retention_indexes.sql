-- Index cho job dọn dữ liệu M6 (SCRUM-164, #160): mỗi lượt xóa theo lô nên cần tìm nhanh dòng quá hạn.
CREATE INDEX reminder_deliveries_status_scheduled_idx
  ON lifestyle.reminder_deliveries (status, scheduled_for);

CREATE INDEX reminders_paused_idx
  ON lifestyle.reminders (updated_at)
  WHERE disabled_reason = 'PLAN_SWITCH';

CREATE INDEX plans_unsummarized_ended_idx
  ON lifestyle.plans (ended_on)
  WHERE ended_on IS NOT NULL AND completions_summarized_at IS NULL;
