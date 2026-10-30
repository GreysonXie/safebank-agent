-- T017 授权/展示样本批次 t017-batch1（客户持续授权的常规模拟样本；可重复执行，不覆盖客户改动）
-- 业务日期 2026-10-30：D+3=2026-11-02（有效月付临近）、D+40=2026-12-09（有效季度远期）、周期未知但有效
INSERT INTO mandates (id,user_id,merchant,amount_minor,renewal_date,status,contract_status,billing_interval)
SELECT 't017-near-monthly','u1','云备份会员',2500,'2026-11-02','ACTIVE','UNKNOWN','MONTHLY'
WHERE NOT EXISTS (SELECT 1 FROM mandates WHERE id='t017-near-monthly');
INSERT INTO mandates (id,user_id,merchant,amount_minor,renewal_date,status,contract_status,billing_interval)
SELECT 't017-far-quarterly','u1','企业安全季度包',30000,'2026-12-09','ACTIVE','UNKNOWN','QUARTERLY'
WHERE NOT EXISTS (SELECT 1 FROM mandates WHERE id='t017-far-quarterly');
INSERT INTO mandates (id,user_id,merchant,amount_minor,renewal_date,status,contract_status,billing_interval)
SELECT 't017-unknown-cycle','u1','家庭网盘扩容',1300,NULL,'ACTIVE','UNKNOWN','UNKNOWN'
WHERE NOT EXISTS (SELECT 1 FROM mandates WHERE id='t017-unknown-cycle');
