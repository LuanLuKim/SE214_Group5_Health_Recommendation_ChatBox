-- UC-16 development rules for the authoring screens. Entirely synthetic, never APPROVED:
-- nothing here can reach the Safety Gate. Approved fixtures live only in backend test resources.
-- Idempotent: fixed IDs, ON CONFLICT DO NOTHING, existing rows are never changed.
INSERT INTO safety.rules
  (id, name, conditions, required_fields, care_level, specialty_ids, source_name, source_url, review_status, is_synthetic)
VALUES
 ('00000000-0000-4000-8000-000000000031','[TEST] Quy tắc nháp có nguồn',
  '{"all":["__SYNTHETIC_ONLY__draft"],"any":[]}',
  '{"__SYNTHETIC_ONLY__onset"}','INSUFFICIENT_INFO','{}','Synthetic fixture','https://example.invalid/rules/draft','DRAFT',true),
 ('00000000-0000-4000-8000-000000000032','[TEST] Quy tắc đang chờ duyệt',
  '{"all":["__SYNTHETIC_ONLY__pending"],"any":["__SYNTHETIC_ONLY__pending_a"]}',
  '{}','SEEK_CARE_SOON','{}','Synthetic fixture','https://example.invalid/rules/pending','PENDING_REVIEW',true),
 ('00000000-0000-4000-8000-000000000033','[TEST] Quy tắc bị trả lại',
  '{"all":["__SYNTHETIC_ONLY__returned"],"any":[]}',
  '{}','ROUTINE_CONSULT','{}','Synthetic fixture','https://example.invalid/rules/returned','RETURNED',true)
ON CONFLICT DO NOTHING;
