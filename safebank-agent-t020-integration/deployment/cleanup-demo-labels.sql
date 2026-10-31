-- Display labels only, for the local synthetic demo database. Back up first.
-- Preserve IDs, money, authorization payloads, hashes and historical receipts.
START TRANSACTION;
UPDATE accounts SET label=REPLACE(REPLACE(REPLACE(REPLACE(label,'（合成）',''),'(合成)',''),'（模拟）',''),'(模拟)','') WHERE label LIKE '%（合成）%' OR label LIKE '%(合成)%' OR label LIKE '%（模拟）%' OR label LIKE '%(模拟)%';
UPDATE mandates SET merchant=REPLACE(REPLACE(REPLACE(REPLACE(merchant,'（合成）',''),'(合成)',''),'（模拟）',''),'(模拟)','') WHERE merchant LIKE '%（合成）%' OR merchant LIKE '%(合成)%' OR merchant LIKE '%（模拟）%' OR merchant LIKE '%(模拟)%';
UPDATE transactions SET merchant=REPLACE(REPLACE(REPLACE(REPLACE(merchant,'（合成）',''),'(合成)',''),'（模拟）',''),'(模拟)','') WHERE merchant LIKE '%（合成）%' OR merchant LIKE '%(合成)%' OR merchant LIKE '%（模拟）%' OR merchant LIKE '%(模拟)%';
COMMIT;
