CREATE SCHEMA IF NOT EXISTS accounts;
CREATE TABLE accounts.users (
  id uuid PRIMARY KEY,
  email text NOT NULL UNIQUE,
  display_name text NOT NULL,
  password_hash text,
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED')),
  is_synthetic boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts.user_roles (
  user_id uuid NOT NULL REFERENCES accounts.users(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('USER','CONTENT_EXPERT','ADMIN')),
  PRIMARY KEY (user_id, role)
);
CREATE TABLE accounts.consents (
  user_id uuid NOT NULL REFERENCES accounts.users(id) ON DELETE CASCADE,
  terms_version text NOT NULL,
  adult_confirmed boolean NOT NULL CHECK (adult_confirmed),
  accepted_at timestamptz NOT NULL,
  PRIMARY KEY (user_id, terms_version)
);
ALTER TABLE health.profiles ADD FOREIGN KEY (user_id) REFERENCES accounts.users(id) ON DELETE CASCADE;
ALTER TABLE health.daily_metrics ADD FOREIGN KEY (user_id) REFERENCES accounts.users(id) ON DELETE CASCADE;
ALTER TABLE chat.sessions ADD FOREIGN KEY (user_id) REFERENCES accounts.users(id) ON DELETE CASCADE;
ALTER TABLE knowledge.review_events ADD FOREIGN KEY (reviewer_id) REFERENCES accounts.users(id);
