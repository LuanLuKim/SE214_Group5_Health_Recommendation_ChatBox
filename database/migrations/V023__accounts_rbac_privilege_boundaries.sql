INSERT INTO accounts.permissions (permission_key, name, description, built_in) VALUES
  ('ADMIN_ROLE_MANAGE', 'Cấp và thu hồi quyền quản trị', 'Cấp hoặc thu hồi vai trò ADMIN.', true),
  ('CONTENT_EXPERT_ROLE_MANAGE', 'Cấp và thu hồi vai trò chuyên gia', 'Cấp hoặc thu hồi vai trò CONTENT_EXPERT.', true),
  ('CONTENT_REVIEW', 'Duyệt nội dung và xử lý phản hồi', 'Xem hàng đợi duyệt và xử lý phản hồi ẩn danh.', true);

INSERT INTO accounts.role_permissions (role_key, permission_key) VALUES
  ('ADMIN', 'ADMIN_ROLE_MANAGE'),
  ('ADMIN', 'CONTENT_EXPERT_ROLE_MANAGE'),
  ('CONTENT_EXPERT', 'CONTENT_REVIEW');
