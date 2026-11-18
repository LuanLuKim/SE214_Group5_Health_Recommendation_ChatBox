-- Entirely fictional records. No login passwords, real medical advice, or real facilities.
-- Idempotent: use only fixed synthetic IDs, never delete existing records.
INSERT INTO accounts.users (id,email,display_name,is_synthetic) VALUES
 ('00000000-0000-4000-8000-000000000001','user@example.invalid','Người dùng kiểm thử',true),
 ('00000000-0000-4000-8000-000000000002','expert@example.invalid','Chuyên gia kiểm thử',true),
 ('00000000-0000-4000-8000-000000000003','admin@example.invalid','Quản trị kiểm thử',true)
ON CONFLICT DO NOTHING;
INSERT INTO accounts.user_roles VALUES
 ('00000000-0000-4000-8000-000000000001','USER'),
 ('00000000-0000-4000-8000-000000000002','USER'),
 ('00000000-0000-4000-8000-000000000002','CONTENT_EXPERT'),
 ('00000000-0000-4000-8000-000000000003','USER'),
 ('00000000-0000-4000-8000-000000000003','ADMIN')
ON CONFLICT DO NOTHING;
INSERT INTO accounts.consents VALUES ('00000000-0000-4000-8000-000000000001','synthetic-v1',true,'2026-10-29T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO health.profiles VALUES ('00000000-0000-4000-8000-000000000001',170,24,'UNSPECIFIED','{}') ON CONFLICT DO NOTHING;
INSERT INTO health.daily_metrics VALUES
 ('00000000-0000-4000-8000-000000000001','2026-10-26',65,7),
 ('00000000-0000-4000-8000-000000000001','2026-10-27',65.2,7.5),
 ('00000000-0000-4000-8000-000000000001','2026-10-28',65.1,6.5) ON CONFLICT DO NOTHING;
INSERT INTO knowledge.articles (id,title,topic,content,source_name,source_url,verified_at,review_status,is_synthetic) VALUES
 ('00000000-0000-4000-8000-000000000010','[TEST] Kiểm thử bài viết về giấc ngủ','Giấc ngủ','Nội dung giả lập để kiểm tra giao diện. Không có hướng dẫn y tế.','Synthetic fixture','https://example.invalid/articles/sleep','2026-10-29T00:00:00Z','APPROVED',true),
 ('00000000-0000-4000-8000-000000000011','[TEST] Bản nháp không được công khai','Kiểm thử','Bản nháp thử bộ lọc trạng thái.','Synthetic fixture','https://example.invalid/articles/draft',NULL,'DRAFT',true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge.facilities VALUES
 ('00000000-0000-4000-8000-000000000020','[TEST] Cơ sở giả lập A',ARRAY['Chuyên khoa thử nghiệm'],'Tỉnh kiểm thử','Địa chỉ giả lập, không có thật','https://example.invalid/facility','Synthetic fixture','https://example.invalid/facility','2026-10-29T00:00:00Z',true)
ON CONFLICT DO NOTHING;
INSERT INTO safety.rules (id,name,conditions,care_level,source_url,review_status,is_synthetic) VALUES
 ('00000000-0000-4000-8000-000000000030','[TEST] Quy tắc nháp không kích hoạt','{"test_only":true,"token":"__SYNTHETIC_ONLY__"}','INSUFFICIENT_INFO','https://example.invalid/rule','DRAFT',true)
ON CONFLICT DO NOTHING;
INSERT INTO chat.sessions VALUES ('00000000-0000-4000-8000-000000000040','00000000-0000-4000-8000-000000000001','2026-10-29T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO chat.messages VALUES
 ('00000000-0000-4000-8000-000000000041','00000000-0000-4000-8000-000000000040','user','Tin nhắn giả lập kiểm tra lịch sử.','2026-10-29T00:00:00Z'),
 ('00000000-0000-4000-8000-000000000042','00000000-0000-4000-8000-000000000040','assistant','Phản hồi giả lập, không phải tư vấn sức khỏe.','2026-10-29T00:00:01Z') ON CONFLICT DO NOTHING;
INSERT INTO chat.feedback VALUES ('00000000-0000-4000-8000-000000000043','UNCLEAR','Ghi chú giả lập.',NULL,NULL,'2026-10-29T00:00:02Z') ON CONFLICT DO NOTHING;
INSERT INTO lifestyle.templates VALUES ('00000000-0000-4000-8000-000000000050','[TEST] Mẫu lịch sinh hoạt','SLEEP','[{"id":"demo","title":"Hoạt động giả lập"}]','{}','DRAFT','https://example.invalid/template',true) ON CONFLICT DO NOTHING;
INSERT INTO lifestyle.plans VALUES ('00000000-0000-4000-8000-000000000051','00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000050','2026-10-29') ON CONFLICT DO NOTHING;
INSERT INTO lifestyle.completions VALUES ('00000000-0000-4000-8000-000000000051','demo','2026-10-29') ON CONFLICT DO NOTHING;
INSERT INTO lifestyle.reminders VALUES ('00000000-0000-4000-8000-000000000052','00000000-0000-4000-8000-000000000001','Nhắc nhở giả lập, chưa gửi.','20:00','Asia/Ho_Chi_Minh','EMAIL',false) ON CONFLICT DO NOTHING;
INSERT INTO health.usage_events VALUES ('00000000-0000-4000-8000-000000000060','VISIT','2026-10-29T00:00:00Z') ON CONFLICT DO NOTHING;
