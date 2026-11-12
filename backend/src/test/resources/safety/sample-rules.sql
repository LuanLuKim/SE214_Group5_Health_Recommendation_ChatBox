-- UC-16 test-only rule fixture. Loaded only by tests, never by database/seeds.
-- Entirely synthetic: conditions are __SYNTHETIC_ONLY__ tokens, not medical guidance, and the
-- example.invalid sources do not exist. Covers all five care levels and all five review states.
INSERT INTO safety.rules
  (id, name, conditions, required_fields, care_level, specialty_ids, source_name, source_url, review_status, is_synthetic, created_at)
VALUES
 ('00000000-0000-4000-8000-000000000300','[TEST] Đã duyệt – khẩn cấp',
  '{"all":["__SYNTHETIC_ONLY__emergency"],"any":[]}',
  '{}','EMERGENCY_NOW','{}','Synthetic fixture','https://example.invalid/rules/300','APPROVED',true,'2026-11-01T00:00:00Z'),
 ('00000000-0000-4000-8000-000000000301','[TEST] Đã duyệt – khám sớm',
  '{"all":["__SYNTHETIC_ONLY__soon"],"any":["__SYNTHETIC_ONLY__soon_a","__SYNTHETIC_ONLY__soon_b"]}',
  '{}','SEEK_CARE_SOON','{"__SYNTHETIC_ONLY__specialty"}','Synthetic fixture','https://example.invalid/rules/301','APPROVED',true,'2026-11-01T00:00:01Z'),
 ('00000000-0000-4000-8000-000000000302','[TEST] Đã duyệt – khám định kỳ',
  '{"all":["__SYNTHETIC_ONLY__routine"],"any":[]}',
  '{}','ROUTINE_CONSULT','{}','Synthetic fixture','https://example.invalid/rules/302','APPROVED',true,'2026-11-01T00:00:02Z'),
 ('00000000-0000-4000-8000-000000000303','[TEST] Đã duyệt – tự chăm sóc',
  '{"all":["__SYNTHETIC_ONLY__self_care"],"any":[]}',
  '{}','GENERAL_SELF_CARE_INFO','{}','Synthetic fixture','https://example.invalid/rules/303','APPROVED',true,'2026-11-01T00:00:03Z'),
 ('00000000-0000-4000-8000-000000000304','[TEST] Đã duyệt – thiếu thông tin',
  '{"all":["__SYNTHETIC_ONLY__unclear"],"any":[]}',
  '{"__SYNTHETIC_ONLY__onset"}','INSUFFICIENT_INFO','{}','Synthetic fixture','https://example.invalid/rules/304','APPROVED',true,'2026-11-01T00:00:04Z'),
 -- Not loadable: every other review state.
 ('00000000-0000-4000-8000-000000000305','[TEST] Nháp',
  '{"all":["__SYNTHETIC_ONLY__draft"],"any":[]}',
  '{}','EMERGENCY_NOW','{}','Synthetic fixture','https://example.invalid/rules/305','DRAFT',true,'2026-11-01T00:00:05Z'),
 ('00000000-0000-4000-8000-000000000306','[TEST] Chờ duyệt',
  '{"all":["__SYNTHETIC_ONLY__pending"],"any":[]}',
  '{}','SEEK_CARE_SOON','{}','Synthetic fixture','https://example.invalid/rules/306','PENDING_REVIEW',true,'2026-11-01T00:00:06Z'),
 ('00000000-0000-4000-8000-000000000307','[TEST] Bị trả lại',
  '{"all":["__SYNTHETIC_ONLY__returned"],"any":[]}',
  '{}','ROUTINE_CONSULT','{}','Synthetic fixture','https://example.invalid/rules/307','RETURNED',true,'2026-11-01T00:00:07Z'),
 ('00000000-0000-4000-8000-000000000308','[TEST] Đã ngừng dùng',
  '{"all":["__SYNTHETIC_ONLY__retired"],"any":[]}',
  '{}','EMERGENCY_NOW','{}','Synthetic fixture','https://example.invalid/rules/308','RETIRED',true,'2026-11-01T00:00:08Z'),
 -- Not loadable: approved but without required conditions (would otherwise match everything).
 ('00000000-0000-4000-8000-000000000309','[TEST] Đã duyệt nhưng không có điều kiện',
  '{"all":[],"any":["__SYNTHETIC_ONLY__any_only"]}',
  '{}','GENERAL_SELF_CARE_INFO','{}','Synthetic fixture','https://example.invalid/rules/309','APPROVED',true,'2026-11-01T00:00:09Z'),
 ('00000000-0000-4000-8000-000000000310','[TEST] Đã duyệt với điều kiện kiểu cũ',
  '{"test_only":true,"token":"__SYNTHETIC_ONLY__"}',
  '{}','GENERAL_SELF_CARE_INFO','{}','Synthetic fixture','https://example.invalid/rules/310','APPROVED',true,'2026-11-01T00:00:10Z')
ON CONFLICT DO NOTHING;
