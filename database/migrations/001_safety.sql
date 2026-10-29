CREATE SCHEMA IF NOT EXISTS safety;
CREATE TABLE safety.rules (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  conditions jsonb NOT NULL,
  care_level text NOT NULL CHECK (care_level IN ('EMERGENCY_NOW','SEEK_CARE_SOON','ROUTINE_CONSULT','GENERAL_SELF_CARE_INFO','INSUFFICIENT_INFO')),
  source_url text NOT NULL,
  review_status text NOT NULL DEFAULT 'DRAFT' CHECK (review_status IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED','RETIRED')),
  is_synthetic boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
