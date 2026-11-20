-- Chỉ dùng cho test của loader SCRUM-48, KHÔNG phải migration.
-- Bản sao bảng knowledge.chunks đã đề xuất trên #50 (thêm translation_status, câu 2 gửi M2).
-- Migration thật do M2 viết (V025). Khi V025 lên main: xóa file này và bỏ bước nạp nó trong
-- KnowledgeIngestTest, test sẽ chạy trên migration thật.
CREATE TABLE knowledge.chunks (
  id uuid PRIMARY KEY,
  article_id uuid NOT NULL REFERENCES knowledge.articles(id) ON DELETE CASCADE,
  chunk_key text NOT NULL UNIQUE,
  heading text,
  heading_path text[] NOT NULL DEFAULT '{}',
  text text NOT NULL,
  original_text text,
  language text NOT NULL,
  word_count int NOT NULL DEFAULT 0,
  text_sha256 char(64) NOT NULL,
  scope_tags text[] NOT NULL DEFAULT '{}',
  rule_tags text[] NOT NULL DEFAULT '{}',
  review_status text NOT NULL DEFAULT 'DRAFT'
    CHECK (review_status IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED','RETIRED')),
  rag_eligible boolean NOT NULL DEFAULT false,
  source_url text NOT NULL,
  fetched_at timestamptz NOT NULL,
  normalizer_version text,
  translation_model text,
  translation_status text
    CHECK (translation_status IN ('pending','auto_verified','flagged','reviewer_corrected')),
  search tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(heading, '') || ' ' || text)) STORED,
  CHECK (NOT rag_eligible OR review_status = 'APPROVED')
);
CREATE INDEX chunks_search_idx ON knowledge.chunks USING gin (search);
-- Loại CHUNK trong hàng đợi duyệt chung (M2 thêm trong V025).
ALTER TABLE knowledge.review_events DROP CONSTRAINT review_events_subject_type_check;
ALTER TABLE knowledge.review_events ADD CONSTRAINT review_events_subject_type_check
  CHECK (subject_type IN ('ARTICLE','TEMPLATE','RULE','FEEDBACK','CHUNK'));
