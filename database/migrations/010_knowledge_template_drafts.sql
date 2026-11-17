ALTER TABLE knowledge.articles
  ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN submitted_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL;

ALTER TABLE lifestyle.templates
  ADD COLUMN source_name text NOT NULL DEFAULT '',
  ADD COLUMN source_updated_at timestamptz,
  ADD COLUMN copyright_notes text NOT NULL DEFAULT '',
  ADD COLUMN verified_at timestamptz,
  ADD COLUMN duration_days integer,
  ADD COLUMN applicability text NOT NULL DEFAULT '',
  ADD COLUMN contraindications text[] NOT NULL DEFAULT '{}',
  ADD COLUMN advisory text NOT NULL DEFAULT '',
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL,
  ADD COLUMN updated_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL,
  ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN submitted_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL;

-- Legacy rows stay inert until completed and reviewed; no verification is invented.
ALTER TABLE lifestyle.templates ADD CONSTRAINT template_required_content CHECK (
  btrim(title) <> '' AND btrim(source_name) <> '' AND btrim(source_url) <> ''
  AND duration_days IS NOT NULL AND duration_days BETWEEN 1 AND 365
  AND btrim(applicability) <> '' AND btrim(advisory) <> ''
  AND goal IN ('WEIGHT_GAIN','WEIGHT_LOSS','MAINTENANCE','SLEEP')
  AND CASE WHEN jsonb_typeof(activities) = 'array'
    THEN jsonb_array_length(activities) > 0 ELSE false END
) NOT VALID;
