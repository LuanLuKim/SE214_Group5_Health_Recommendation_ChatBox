CREATE TABLE accounts.roles (
  role_key text PRIMARY KEY CHECK (role_key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  name text NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
  description text NOT NULL DEFAULT '',
  built_in boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts.permissions (
  permission_key text PRIMARY KEY CHECK (permission_key ~ '^[A-Z][A-Z0-9_.:-]{1,79}$'),
  name text NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
  description text NOT NULL DEFAULT '',
  built_in boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO accounts.roles (role_key, name, description, built_in) VALUES
  ('GUEST', 'Khách', 'Tra cứu nội dung công khai; không cần tài khoản.', true),
  ('USER', 'Người dùng', 'Tài khoản người dùng đã đăng ký.', true),
  ('CONTENT_EXPERT', 'Chuyên gia nội dung', 'Quản lý nội dung và quy tắc an toàn.', true),
  ('ADMIN', 'Quản trị viên', 'Quản trị tài khoản, vai trò, quyền và thống kê.', true);

INSERT INTO accounts.permissions (permission_key, name, description, built_in) VALUES
  ('KNOWLEDGE_SEARCH', 'Tra cứu kiến thức', 'Tra cứu kiến thức công khai.', true),
  ('ACCOUNT_REGISTER', 'Đăng ký tài khoản', 'Đăng ký tài khoản người dùng.', true),
  ('ACCOUNT_LOGIN', 'Đăng nhập', 'Đăng nhập tài khoản đã đăng ký.', true),
  ('ACCOUNT_SELF_MANAGE', 'Quản lý tài khoản cá nhân', 'Quản lý thông tin và dữ liệu sức khỏe của chính mình.', true),
  ('CONTENT_MANAGE', 'Quản lý nội dung', 'Quản lý bài viết và mẫu kế hoạch.', true),
  ('SAFETY_RULE_MANAGE', 'Quản lý quy tắc an toàn', 'Quản lý quy tắc dấu hiệu cảnh báo.', true),
  ('USER_MANAGE', 'Quản lý người dùng', 'Tìm tài khoản, khóa/mở khóa và cấp/thu hồi vai trò.', true),
  ('SYSTEM_STATS_VIEW', 'Xem thống kê hệ thống', 'Xem thống kê tổng hợp của hệ thống.', true),
  ('RBAC_MANAGE', 'Quản lý vai trò và quyền', 'Quản lý vai trò, permission và quan hệ phân quyền.', true);

CREATE TABLE accounts.role_permissions (
  role_key text NOT NULL REFERENCES accounts.roles(role_key) ON DELETE CASCADE,
  permission_key text NOT NULL REFERENCES accounts.permissions(permission_key) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (role_key, permission_key)
);

INSERT INTO accounts.role_permissions (role_key, permission_key) VALUES
  ('GUEST', 'KNOWLEDGE_SEARCH'),
  ('GUEST', 'ACCOUNT_REGISTER'),
  ('USER', 'KNOWLEDGE_SEARCH'),
  ('USER', 'ACCOUNT_LOGIN'),
  ('USER', 'ACCOUNT_SELF_MANAGE'),
  ('CONTENT_EXPERT', 'KNOWLEDGE_SEARCH'),
  ('CONTENT_EXPERT', 'ACCOUNT_LOGIN'),
  ('CONTENT_EXPERT', 'ACCOUNT_SELF_MANAGE'),
  ('CONTENT_EXPERT', 'CONTENT_MANAGE'),
  ('CONTENT_EXPERT', 'SAFETY_RULE_MANAGE'),
  ('ADMIN', 'KNOWLEDGE_SEARCH'),
  ('ADMIN', 'ACCOUNT_LOGIN'),
  ('ADMIN', 'ACCOUNT_SELF_MANAGE'),
  ('ADMIN', 'CONTENT_MANAGE'),
  ('ADMIN', 'USER_MANAGE'),
  ('ADMIN', 'SYSTEM_STATS_VIEW'),
  ('ADMIN', 'RBAC_MANAGE');

ALTER TABLE accounts.user_roles DROP CONSTRAINT user_roles_role_check;
ALTER TABLE accounts.user_roles
  ADD CONSTRAINT user_roles_role_fkey FOREIGN KEY (role) REFERENCES accounts.roles(role_key);

CREATE INDEX user_roles_role_idx ON accounts.user_roles (role, user_id);

CREATE TABLE accounts.admin_audit_logs (
  id uuid PRIMARY KEY,
  actor_user_id uuid NOT NULL,
  action text NOT NULL CHECK (length(action) BETWEEN 1 AND 80),
  target_type text NOT NULL CHECK (length(target_type) BETWEEN 1 AND 40),
  target_id text NOT NULL CHECK (length(target_id) BETWEEN 1 AND 200),
  details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX admin_audit_logs_created_at_idx
  ON accounts.admin_audit_logs (created_at DESC, id DESC);
CREATE INDEX admin_audit_logs_target_idx
  ON accounts.admin_audit_logs (target_type, target_id, created_at DESC);
