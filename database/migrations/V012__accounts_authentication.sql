ALTER TABLE accounts.users
  ADD COLUMN failed_login_window_started_at timestamptz,
  ADD COLUMN failed_login_count integer NOT NULL DEFAULT 0
    CHECK (failed_login_count BETWEEN 0 AND 5),
  ADD COLUMN login_locked_until timestamptz;

CREATE TABLE accounts.refresh_tokens (
  refresh_token_hash text PRIMARY KEY CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
  user_id uuid NOT NULL REFERENCES accounts.users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);

CREATE INDEX refresh_tokens_user_id_idx ON accounts.refresh_tokens (user_id);
CREATE INDEX refresh_tokens_expires_at_idx ON accounts.refresh_tokens (expires_at);

CREATE TABLE accounts.password_recovery_challenges (
  user_id uuid PRIMARY KEY REFERENCES accounts.users(id) ON DELETE CASCADE,
  otp_salt uuid NOT NULL,
  otp_hash text NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
  expires_at timestamptz NOT NULL,
  resend_after timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX password_recovery_expires_at_idx
  ON accounts.password_recovery_challenges (expires_at);
