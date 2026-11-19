-- UC-03 C. Yêu cầu xóa tài khoản: bước 4–6 gửi mã xác nhận qua email đã đăng ký rồi kiểm tra mã.
-- Cùng khuôn với accounts.password_recovery_challenges (V012): chỉ lưu mã đã băm kèm salt, tối đa
-- 5 lần thử, có hạn dùng và mốc được gửi lại. Mỗi tài khoản có tối đa một yêu cầu đang chờ xác nhận.
-- A3 (hủy) và A4 (mã sai, hết hạn): service xóa dòng hoặc từ chối, tài khoản giữ nguyên.
-- Xác nhận đúng: service xóa accounts.users trong một transaction. ON DELETE CASCADE dọn dòng này cùng
-- vai trò, đồng ý, OAuth, refresh token, hồ sơ, chỉ số, phiên chat, lộ trình và nhắc nhở.
-- Phản hồi trong chat.feedback không gắn danh tính nên không bị xóa theo.
CREATE TABLE accounts.account_deletion_challenges (
  user_id uuid PRIMARY KEY REFERENCES accounts.users(id) ON DELETE CASCADE,
  otp_salt uuid NOT NULL,
  otp_hash text NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
  expires_at timestamptz NOT NULL,
  resend_after timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX account_deletion_challenges_expires_at_idx
  ON accounts.account_deletion_challenges (expires_at);
