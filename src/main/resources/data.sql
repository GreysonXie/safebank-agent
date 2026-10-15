INSERT INTO accounts SELECT 'checking','u1','日常账户',1243000 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE id='checking');
INSERT INTO accounts SELECT 'travel','u1','旅游账户',5820000 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE id='travel');
INSERT INTO accounts SELECT 'other','u2','另一用户账户',100000 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE id='other');
INSERT INTO accounts SELECT 'external-a','external','张三 3271',0 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE id='external-a');
INSERT INTO accounts SELECT 'external-b','external','张三 8862',0 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE id='external-b');
INSERT INTO recipients SELECT 'zhang-a','u1','张三','13800003271','同学','external-a' WHERE NOT EXISTS(SELECT 1 FROM recipients WHERE id='zhang-a');
INSERT INTO recipients SELECT 'zhang-b','u1','张三','13800008862','同事','external-b' WHERE NOT EXISTS(SELECT 1 FROM recipients WHERE id='zhang-b');
INSERT INTO recipients SELECT 'travel-recipient','u1','旅游账户','','旅游基金','travel' WHERE NOT EXISTS(SELECT 1 FROM recipients WHERE id='travel-recipient');
INSERT INTO mandates(id,user_id,merchant,amount_minor,renewal_date,status,contract_status) SELECT 'music','u1','音乐服务',1500,DATE '2026-10-20','ACTIVE','UNKNOWN' WHERE NOT EXISTS(SELECT 1 FROM mandates WHERE id='music');
INSERT INTO mandates(id,user_id,merchant,amount_minor,renewal_date,status,contract_status) SELECT 'video','u1','视频服务',6800,DATE '2026-10-20','ACTIVE','UNKNOWN' WHERE NOT EXISTS(SELECT 1 FROM mandates WHERE id='video');
INSERT INTO transactions SELECT 'salary-9','u1','checking','工资','收入',850000,DATE '2026-09-05' WHERE NOT EXISTS(SELECT 1 FROM transactions WHERE id='salary-9');
INSERT INTO transactions SELECT 'food-9','u1','checking','餐饮','餐饮',-82000,DATE '2026-09-12' WHERE NOT EXISTS(SELECT 1 FROM transactions WHERE id='food-9');
INSERT INTO transactions SELECT 'rent-9','u1','checking','租金','居住',-250000,DATE '2026-09-01' WHERE NOT EXISTS(SELECT 1 FROM transactions WHERE id='rent-9');
INSERT INTO transactions SELECT 'music-8','u1','checking','音乐服务','订阅',-1500,DATE '2026-08-20' WHERE NOT EXISTS(SELECT 1 FROM transactions WHERE id='music-8');
INSERT INTO transactions SELECT 'music-9','u1','checking','音乐服务','订阅',-1500,DATE '2026-09-20' WHERE NOT EXISTS(SELECT 1 FROM transactions WHERE id='music-9');
INSERT INTO transactions SELECT 'video-8','u1','checking','视频服务','订阅',-6800,DATE '2026-08-20' WHERE NOT EXISTS(SELECT 1 FROM transactions WHERE id='video-8');
INSERT INTO transactions SELECT 'video-9','u1','checking','视频服务','订阅',-6800,DATE '2026-09-20' WHERE NOT EXISTS(SELECT 1 FROM transactions WHERE id='video-9');

