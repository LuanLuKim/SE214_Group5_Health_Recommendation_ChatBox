ALTER TABLE safety.rules
  ADD COLUMN source_name text NOT NULL DEFAULT '',
  ADD COLUMN required_fields text[] NOT NULL DEFAULT '{}',
  ADD COLUMN specialty_ids text[] NOT NULL DEFAULT '{}',
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

UPDATE safety.rules
SET source_name = 'Synthetic fixture',
    conditions = '{"all":["__SYNTHETIC_ONLY__"],"any":[]}'::jsonb
WHERE is_synthetic = true AND source_name = '';
