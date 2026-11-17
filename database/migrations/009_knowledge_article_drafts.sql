ALTER TABLE knowledge.articles
  ADD COLUMN source_updated_at timestamptz,
  ADD COLUMN copyright_notes text NOT NULL DEFAULT '',
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL,
  ADD COLUMN updated_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL;

-- Existing crawler db_row inserts remain valid; fetched_at is not verified_at.
ALTER TABLE knowledge.articles ADD CONSTRAINT article_required_content
  CHECK (btrim(title) <> '' AND btrim(topic) <> '' AND btrim(content) <> ''
    AND btrim(source_name) <> '' AND btrim(source_url) <> '') NOT VALID;
