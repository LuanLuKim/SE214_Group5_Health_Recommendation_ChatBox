ALTER TABLE accounts.users
  ADD COLUMN phone_number text,
  ADD COLUMN avatar_url text,
  ADD CONSTRAINT users_phone_number_format
    CHECK (phone_number IS NULL OR phone_number ~ '^\+?[0-9]{7,15}$'),
  ADD CONSTRAINT users_avatar_url_length
    CHECK (avatar_url IS NULL OR length(avatar_url) <= 2048);

ALTER TABLE knowledge.review_events
  ALTER COLUMN reviewer_id DROP NOT NULL,
  DROP CONSTRAINT review_events_reviewer_id_fkey,
  ADD CONSTRAINT review_events_reviewer_id_fkey
    FOREIGN KEY (reviewer_id) REFERENCES accounts.users(id) ON DELETE SET NULL;

-- Bỏ NOT NULL trước, rồi mới gỡ actor không còn tồn tại, cuối cùng mới thêm FK.
ALTER TABLE accounts.admin_audit_logs
  ALTER COLUMN actor_user_id DROP NOT NULL;

UPDATE accounts.admin_audit_logs
SET actor_user_id = NULL
WHERE NOT EXISTS (
  SELECT 1 FROM accounts.users WHERE accounts.users.id = accounts.admin_audit_logs.actor_user_id
);

ALTER TABLE accounts.admin_audit_logs
  ADD CONSTRAINT admin_audit_logs_actor_user_id_fkey
    FOREIGN KEY (actor_user_id) REFERENCES accounts.users(id) ON DELETE SET NULL;
