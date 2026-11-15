-- UC-12 development templates. Entirely synthetic ([TEST], is_synthetic = true, example.invalid
-- sources): no real health advice. They are APPROVED so the plan screens can be tried, and the API
-- offers synthetic templates only while synthetic seeding is on (never in production).
-- Idempotent: fixed IDs, ON CONFLICT DO NOTHING, existing rows are never changed.
INSERT INTO lifestyle.templates
  (id, title, goal, activities, excluded_allergens, review_status, source_url, is_synthetic,
   summary, duration_days, not_suitable_for, requires_profile)
VALUES
 ('00000000-0000-4000-8000-000000000070', '[TEST] Ngủ đúng giờ', 'SLEEP',
  '[{"id":"sleep-time","title":"[TEST] Đi ngủ trước 23:00","description":"[TEST] Hoạt động giả lập."},{"id":"no-screen","title":"[TEST] Tắt màn hình trước khi ngủ 30 phút"}]',
  '{}', 'APPROVED', 'https://example.invalid/lifestyle/sleep', true,
  '[TEST] Mẫu giả lập cho mục tiêu giấc ngủ.', 14, '{"[TEST] Người làm ca đêm"}', false),
 ('00000000-0000-4000-8000-000000000071', '[TEST] Duy trì vận động', 'MAINTAIN',
  '[{"id":"walk","title":"[TEST] Đi bộ 30 phút"},{"id":"water","title":"[TEST] Uống đủ nước"}]',
  '{}', 'APPROVED', 'https://example.invalid/lifestyle/maintain', true,
  '[TEST] Mẫu giả lập cho mục tiêu duy trì vóc dáng.', 28, '{}', false),
 ('00000000-0000-4000-8000-000000000072', '[TEST] Bữa phụ có đậu phộng', 'GAIN',
  '[{"id":"snack","title":"[TEST] Bữa phụ buổi chiều"}]',
  '{"đậu phộng"}', 'APPROVED', 'https://example.invalid/lifestyle/gain', true,
  '[TEST] Mẫu giả lập có tác nhân dị ứng, để thử luồng A2.', 21, '{}', false),
 ('00000000-0000-4000-8000-000000000073', '[TEST] Giảm cân theo hồ sơ', 'LOSE',
  '[{"id":"steps","title":"[TEST] Đi bộ 8 000 bước"}]',
  '{}', 'APPROVED', 'https://example.invalid/lifestyle/lose', true,
  '[TEST] Mẫu giả lập cần hồ sơ sức khỏe, để thử luồng A1.', 30, '{"[TEST] Phụ nữ mang thai"}', true),
 ('00000000-0000-4000-8000-000000000074', '[TEST] Mẫu nháp không được hiển thị', 'SLEEP',
  '[{"id":"draft","title":"[TEST] Hoạt động nháp"}]',
  '{}', 'DRAFT', 'https://example.invalid/lifestyle/draft', true,
  '[TEST] Mẫu nháp: UC-12 không bao giờ trả mẫu này.', 7, '{}', false)
ON CONFLICT DO NOTHING;

-- The synthetic user's first consent predates the health-data flag (V010). A later consent row
-- accepts it, so UC-12 can be tried with the seeded account.
INSERT INTO accounts.consents
  (user_id, terms_version, adult_confirmed, accepted_at, health_data_processing_accepted, non_diagnostic_acknowledged)
VALUES ('00000000-0000-4000-8000-000000000001', 'synthetic-v2', true, '2026-11-15T00:00:00Z', true, true)
ON CONFLICT DO NOTHING;
