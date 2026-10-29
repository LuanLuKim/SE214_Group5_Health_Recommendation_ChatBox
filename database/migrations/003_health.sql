CREATE SCHEMA IF NOT EXISTS health;
-- Cross-module account ownership FKs are added after M5's schema is created.
CREATE TABLE health.profiles (
  user_id uuid PRIMARY KEY,
  height_cm numeric CHECK (height_cm > 0),
  age integer CHECK (age >= 18),
  sex text,
  food_allergies text[] NOT NULL DEFAULT '{}'
);
CREATE TABLE health.daily_metrics (
  user_id uuid NOT NULL,
  recorded_on date NOT NULL,
  weight_kg numeric CHECK (weight_kg > 0),
  sleep_hours numeric CHECK (sleep_hours >= 0 AND sleep_hours <= 24),
  PRIMARY KEY (user_id, recorded_on)
);
CREATE TABLE health.usage_events (
  id uuid PRIMARY KEY,
  event_type text NOT NULL CHECK (event_type IN ('REGISTRATION','VISIT','CHAT_INTERACTION')),
  occurred_at timestamptz NOT NULL DEFAULT now()
  -- Aggregate event metadata only. No health/chat content.
);
