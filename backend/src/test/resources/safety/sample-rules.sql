-- UC-16 test-only rule fixture. Loaded only by tests into a throwaway database, never by
-- database/seeds. Conditions are __SYNTHETIC_ONLY__ tokens, not medical guidance, and the
-- example.invalid sources do not exist.
-- Rows 300-304 are the evaluable set (one per care level): APPROVED, active, source verified and
-- is_synthetic = false, because listEvaluable() rightly ignores synthetic rows. Every other row
-- differs from them in exactly one way and must never reach the Safety Gate.
INSERT INTO safety.rules
  (id, name, conditions, required_fields, care_level, specialty_ids, source_name, source_url,
   review_status, is_active, source_verified_at, is_synthetic, created_at)
VALUES
 ('00000000-0000-4000-8000-000000000300','[TEST] Đã duyệt – khẩn cấp',
  '{"all":["__SYNTHETIC_ONLY__emergency"],"any":[]}',
  '{}','EMERGENCY_NOW','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/300',
  'APPROVED',true,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:00Z'),
 ('00000000-0000-4000-8000-000000000301','[TEST] Đã duyệt – khám sớm',
  '{"all":["__SYNTHETIC_ONLY__soon"],"any":["__SYNTHETIC_ONLY__soon_a","__SYNTHETIC_ONLY__soon_b"]}',
  '{}','SEEK_CARE_SOON','{"__SYNTHETIC_ONLY__specialty"}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/301',
  'APPROVED',true,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:01Z'),
 ('00000000-0000-4000-8000-000000000302','[TEST] Đã duyệt – khám định kỳ',
  '{"all":["__SYNTHETIC_ONLY__routine"],"any":[]}',
  '{}','ROUTINE_CONSULT','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/302',
  'APPROVED',true,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:02Z'),
 ('00000000-0000-4000-8000-000000000303','[TEST] Đã duyệt – tự chăm sóc',
  '{"all":["__SYNTHETIC_ONLY__self_care"],"any":[]}',
  '{}','GENERAL_SELF_CARE_INFO','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/303',
  'APPROVED',true,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:03Z'),
 ('00000000-0000-4000-8000-000000000304','[TEST] Đã duyệt – thiếu thông tin',
  '{"all":["__SYNTHETIC_ONLY__unclear"],"any":[]}',
  '{"__SYNTHETIC_ONLY__onset"}','INSUFFICIENT_INFO','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/304',
  'APPROVED',true,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:04Z'),
 -- UC-16 authoring states: never evaluable.
 ('00000000-0000-4000-8000-000000000305','[TEST] Nháp',
  '{"all":["__SYNTHETIC_ONLY__draft"],"any":[]}',
  '{}','EMERGENCY_NOW','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/305',
  'DRAFT',false,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:05Z'),
 ('00000000-0000-4000-8000-000000000306','[TEST] Chờ duyệt',
  '{"all":["__SYNTHETIC_ONLY__pending"],"any":[]}',
  '{}','SEEK_CARE_SOON','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/306',
  'PENDING_REVIEW',false,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:06Z'),
 ('00000000-0000-4000-8000-000000000307','[TEST] Bị trả lại',
  '{"all":["__SYNTHETIC_ONLY__returned"],"any":[]}',
  '{}','ROUTINE_CONSULT','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/307',
  'RETURNED',false,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:07Z'),
 ('00000000-0000-4000-8000-000000000308','[TEST] Đã ngừng dùng',
  '{"all":["__SYNTHETIC_ONLY__retired"],"any":[]}',
  '{}','EMERGENCY_NOW','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/308',
  'RETIRED',false,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:08Z'),
 -- Approved but not evaluable: inactive, or synthetic.
 ('00000000-0000-4000-8000-000000000309','[TEST] Đã duyệt nhưng chưa bật',
  '{"all":["__SYNTHETIC_ONLY__inactive"],"any":[]}',
  '{}','GENERAL_SELF_CARE_INFO','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/309',
  'APPROVED',false,'2026-11-01T00:00:00Z',false,'2026-11-01T00:00:09Z'),
 ('00000000-0000-4000-8000-000000000310','[TEST] Đã duyệt nhưng là dữ liệu giả lập',
  '{"all":["__SYNTHETIC_ONLY__synthetic"],"any":[]}',
  '{}','GENERAL_SELF_CARE_INFO','{}','[TEST] Nguồn kiểm thử','https://example.invalid/rules/310',
  'APPROVED',true,'2026-11-01T00:00:00Z',true,'2026-11-01T00:00:10Z')
ON CONFLICT DO NOTHING;
