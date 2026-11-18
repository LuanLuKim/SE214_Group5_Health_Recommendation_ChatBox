CREATE SCHEMA IF NOT EXISTS knowledge;
CREATE TABLE knowledge.articles (
  id uuid PRIMARY KEY,
  title text NOT NULL,
  topic text NOT NULL,
  content text NOT NULL,
  source_name text NOT NULL,
  source_url text NOT NULL,
  verified_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  review_status text NOT NULL DEFAULT 'DRAFT' CHECK (review_status IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED','RETIRED')),
  is_synthetic boolean NOT NULL DEFAULT false
);
CREATE TABLE knowledge.facilities (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  specialties text[] NOT NULL DEFAULT '{}',
  province text NOT NULL,
  address text NOT NULL,
  official_url text NOT NULL,
  source_name text NOT NULL,
  source_url text NOT NULL,
  verified_at timestamptz,
  is_synthetic boolean NOT NULL DEFAULT false
);
CREATE TABLE knowledge.review_events (
  id uuid PRIMARY KEY,
  subject_type text NOT NULL CHECK (subject_type IN ('ARTICLE','TEMPLATE','RULE','FEEDBACK')),
  subject_id uuid NOT NULL,
  reviewer_id uuid NOT NULL,
  decision text NOT NULL,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now()
);
