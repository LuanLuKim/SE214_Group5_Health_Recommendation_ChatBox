CREATE UNIQUE INDEX users_email_normalized_uq ON accounts.users (lower(btrim(email)));

ALTER TABLE accounts.consents
  ADD COLUMN health_data_processing_accepted boolean NOT NULL DEFAULT false,
  ADD COLUMN non_diagnostic_acknowledged boolean NOT NULL DEFAULT false;

CREATE TABLE accounts.oauth_identities (
  provider text NOT NULL CHECK (provider IN ('GOOGLE', 'FACEBOOK')),
  provider_subject text NOT NULL,
  user_id uuid NOT NULL REFERENCES accounts.users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, provider_subject),
  UNIQUE (user_id, provider)
);
