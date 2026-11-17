-- Tóm tắt completion của lộ trình đã kết thúc (SCRUM-163 #159, SCRUM-164 #160).
-- Job dọn dữ liệu ghi các cột này rồi mới xóa completion chi tiết, nên API lịch sử vẫn trả đúng số liệu.
-- Khi chưa tóm tắt, cả ba cột đều NULL và API tính từ lifestyle.completions.
ALTER TABLE lifestyle.plans
  ADD COLUMN completion_count integer CHECK (completion_count >= 0),
  ADD COLUMN completed_days integer CHECK (completed_days >= 0),
  ADD COLUMN completions_summarized_at timestamptz,
  ADD CONSTRAINT plans_completion_summary CHECK (
    (completions_summarized_at IS NULL AND completion_count IS NULL AND completed_days IS NULL)
    OR (completions_summarized_at IS NOT NULL AND completion_count IS NOT NULL
        AND completed_days IS NOT NULL AND ended_on IS NOT NULL)
  );

CREATE INDEX plans_user_ended_idx ON lifestyle.plans (user_id, ended_on DESC) WHERE ended_on IS NOT NULL;
