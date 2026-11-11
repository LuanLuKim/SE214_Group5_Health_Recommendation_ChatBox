CREATE SCHEMA IF NOT EXISTS lifestyle;
CREATE TABLE lifestyle.templates (
  id uuid PRIMARY KEY,
  title text NOT NULL,
  goal text NOT NULL,
  activities jsonb NOT NULL,
  excluded_allergens text[] NOT NULL DEFAULT '{}',
  review_status text NOT NULL DEFAULT 'DRAFT' CHECK (review_status IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED','RETIRED')),
  source_url text NOT NULL,
  is_synthetic boolean NOT NULL DEFAULT false
);
CREATE TABLE lifestyle.plans (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL UNIQUE REFERENCES accounts.users(id) ON DELETE CASCADE,
  template_id uuid NOT NULL REFERENCES lifestyle.templates(id),
  started_on date NOT NULL
);
CREATE TABLE lifestyle.completions (
  plan_id uuid NOT NULL REFERENCES lifestyle.plans(id) ON DELETE CASCADE,
  activity_id text NOT NULL,
  completed_on date NOT NULL,
  PRIMARY KEY (plan_id, activity_id, completed_on)
);
CREATE TABLE lifestyle.reminders (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES accounts.users(id) ON DELETE CASCADE,
  content text NOT NULL,
  local_time time NOT NULL,
  timezone text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  channel text NOT NULL CHECK (channel IN ('EMAIL','WEB_PUSH')),
  enabled boolean NOT NULL DEFAULT false
);
