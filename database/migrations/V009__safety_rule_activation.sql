ALTER TABLE safety.rules
  ADD COLUMN is_active boolean NOT NULL DEFAULT false,
  ADD COLUMN source_verified_at timestamptz;

ALTER TABLE safety.rules
  ADD CONSTRAINT active_rule_requires_review
  CHECK (NOT is_active OR (review_status = 'APPROVED' AND source_verified_at IS NOT NULL));

CREATE INDEX safety_active_rules_idx ON safety.rules (id)
  WHERE review_status = 'APPROVED' AND is_active;
