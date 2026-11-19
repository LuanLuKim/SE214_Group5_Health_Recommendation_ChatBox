-- UC-14 Thiết lập nhắc nhở (FR-2.7, NFR-20, NFR-22).
-- Bổ sung ngày áp dụng, liên kết hoạt động và nhật ký gửi chống trùng cho lifestyle.reminders.

-- days_of_week theo ISO: 1 = thứ Hai ... 7 = Chủ nhật, khớp EXTRACT(ISODOW ...).
-- activity_id tham chiếu id hoạt động trong templates.activities (jsonb) nên không có FK; service phải kiểm tra.
ALTER TABLE lifestyle.reminders
  ADD COLUMN activity_id text,
  ADD COLUMN days_of_week smallint[] NOT NULL DEFAULT '{1,2,3,4,5,6,7}',
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT reminders_content_length CHECK (char_length(btrim(content)) BETWEEN 1 AND 200),
  ADD CONSTRAINT reminders_days_of_week CHECK (
    cardinality(days_of_week) BETWEEN 1 AND 7
    AND days_of_week <@ '{1,2,3,4,5,6,7}'::smallint[]
  );

CREATE INDEX reminders_user_idx ON lifestyle.reminders (user_id);
CREATE INDEX reminders_due_idx ON lifestyle.reminders (local_time) WHERE enabled;

-- Mỗi lần gửi dự kiến có đúng một dòng: worker INSERT ... ON CONFLICT DO NOTHING để "giữ chỗ"
-- trước khi gửi, nên chạy lại, thử lại hoặc nhiều worker cùng lúc không gửi trùng.
-- Chỉ lưu mã lỗi, không lưu nội dung lỗi, địa chỉ nhận hay payload của nhà cung cấp.
CREATE TABLE lifestyle.reminder_deliveries (
  id uuid PRIMARY KEY,
  reminder_id uuid NOT NULL REFERENCES lifestyle.reminders(id) ON DELETE CASCADE,
  scheduled_for timestamptz NOT NULL,
  channel text NOT NULL CHECK (channel IN ('EMAIL','WEB_PUSH')),
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
  attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  last_error_code text,
  sent_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reminder_deliveries_once UNIQUE (reminder_id, scheduled_for),
  CONSTRAINT reminder_deliveries_sent_at CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX reminder_deliveries_pending_idx
  ON lifestyle.reminder_deliveries (scheduled_for)
  WHERE status IN ('PENDING','FAILED');
