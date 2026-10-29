CREATE SCHEMA IF NOT EXISTS chat;
CREATE TABLE chat.sessions (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE chat.messages (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES chat.sessions(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('user','assistant')),
  content text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Anonymous review snapshot only. No user/session/message ID is exposed by this table.
-- M4/M5 must define deletion linkage separately before enabling real feedback.
CREATE TABLE chat.feedback (
  id uuid PRIMARY KEY,
  reason text NOT NULL,
  note text NOT NULL DEFAULT '',
  shared_question text,
  shared_answer text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((shared_question IS NULL) = (shared_answer IS NULL))
);
