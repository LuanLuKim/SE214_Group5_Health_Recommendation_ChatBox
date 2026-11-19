CREATE TABLE accounts.registration_challenges (
  email text PRIMARY KEY CHECK (email = lower(btrim(email))),
  display_name text NOT NULL,
  password_hash text NOT NULL,
  terms_version text NOT NULL,
  adult_confirmed boolean NOT NULL CHECK (adult_confirmed),
  health_data_processing_accepted boolean NOT NULL CHECK (health_data_processing_accepted),
  non_diagnostic_acknowledged boolean NOT NULL CHECK (non_diagnostic_acknowledged),
  otp_salt uuid NOT NULL,
  otp_hash text NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
  expires_at timestamptz NOT NULL,
  resend_after timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX registration_challenges_expires_at_idx
  ON accounts.registration_challenges (expires_at);
