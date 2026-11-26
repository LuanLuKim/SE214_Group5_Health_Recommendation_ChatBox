-- UC-15 (M2) Quản lý bài viết và mẫu kế hoạch: soạn nháp, sửa, xóa, gửi duyệt sang UC-17.
-- 1. Metadata nguồn và bản quyền, người tạo/sửa, thời điểm tạo.
-- 2. revision tăng ở mỗi lần ghi; gửi duyệt chỉ thành công khi revision không đổi kể từ lúc kiểm tra,
--    nên UC-17 duyệt đúng bản đã được kiểm tra.
-- 3. Mẫu kế hoạch (lifestyle.templates, M6 đọc mẫu APPROVED) có thêm nguồn, điều kiện áp dụng, lời nhắc
--    hỏi ý kiến nhân viên y tế và ngày xác minh. Dòng cũ giữ nguyên giá trị mặc định rỗng; phải bổ sung
--    đủ trước khi sửa hoặc gửi duyệt (kiểm tra ở backend, không thêm CHECK để không chặn dữ liệu cũ
--    và dữ liệu crawler của M7).
-- Người tạo/sửa/gửi bị xóa tài khoản thì cột tương ứng thành NULL, nội dung vẫn giữ.
ALTER TABLE knowledge.articles
  ADD COLUMN source_updated_at timestamptz,
  ADD COLUMN copyright_notes text NOT NULL DEFAULT '',
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL,
  ADD COLUMN updated_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL,
  ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN submitted_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL;

ALTER TABLE lifestyle.templates
  ADD COLUMN source_name text NOT NULL DEFAULT '',
  ADD COLUMN source_updated_at timestamptz,
  ADD COLUMN copyright_notes text NOT NULL DEFAULT '',
  ADD COLUMN verified_at timestamptz,
  ADD COLUMN applicability text NOT NULL DEFAULT '',
  ADD COLUMN advisory text NOT NULL DEFAULT '',
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL,
  ADD COLUMN updated_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL,
  ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN submitted_by uuid REFERENCES accounts.users(id) ON DELETE SET NULL;

CREATE INDEX articles_created_idx ON knowledge.articles (created_at DESC, id);
CREATE INDEX templates_created_idx ON lifestyle.templates (created_at DESC, id);
