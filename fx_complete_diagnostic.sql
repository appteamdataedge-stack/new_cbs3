-- ══════════════════════════════════════════════════════════
-- STEP 0: DROP WRONG TABLES IMMEDIATELY
-- ══════════════════════════════════════════════════════════

DROP TABLE IF EXISTS fx_rates;
DROP TABLE IF EXISTS fx_position;
DROP TABLE IF EXISTS fx_transaction_entries;
DROP TABLE IF EXISTS fx_transactions;

-- Verify they're gone
SHOW TABLES LIKE 'fx_%';
-- Expected result: Only fx_rate_master should remain

-- ══════════════════════════════════════════════════════════
-- STEP 1: COMPLETE DATABASE STRUCTURE AUDIT
-- ══════════════════════════════════════════════════════════

-- 1A. Check fx_rate_master
SELECT '========== fx_rate_master STRUCTURE ==========' AS '';
DESCRIBE fx_rate_master;

SELECT '========== fx_rate_master SAMPLE DATA ==========' AS '';
SELECT * FROM fx_rate_master LIMIT 3;

-- 1B. Check cust_acct_master
SELECT '========== cust_acct_master STRUCTURE ==========' AS '';
DESCRIBE cust_acct_master;

SELECT '========== cust_acct_master SAMPLE DATA ==========' AS '';
SELECT * FROM cust_acct_master LIMIT 3;

-- 1C. Check of_acct_master
SELECT '========== of_acct_master STRUCTURE ==========' AS '';
DESCRIBE of_acct_master;

SELECT '========== of_acct_master SAMPLE DATA ==========' AS '';
SELECT * FROM of_acct_master LIMIT 5;

-- Check NOSTRO accounts specifically
SELECT '========== NOSTRO ACCOUNTS (GL starts with 22030) ==========' AS '';
SELECT Account_No, Acct_Name, Account_Ccy, GL_Num, Account_Status 
FROM of_acct_master 
WHERE GL_Num LIKE '22030%'
LIMIT 10;

-- 1D. Check acc_bal
SELECT '========== acc_bal STRUCTURE ==========' AS '';
DESCRIBE acc_bal;

SELECT '========== acc_bal SAMPLE DATA ==========' AS '';
SELECT * FROM acc_bal LIMIT 3;

-- 1E. Check acct_bal_lcy
SELECT '========== acct_bal_lcy STRUCTURE ==========' AS '';
DESCRIBE acct_bal_lcy;

SELECT '========== acct_bal_lcy SAMPLE DATA ==========' AS '';
SELECT * FROM acct_bal_lcy LIMIT 3;

-- 1F. Verify relationships
SELECT '========== VERIFICATION COUNTS ==========' AS '';

SELECT 
    (SELECT COUNT(*) FROM of_acct_master WHERE GL_Num LIKE '22030%') as nostro_count,
    (SELECT COUNT(*) FROM cust_acct_master WHERE Account_Status = 'Active') as active_customer_count,
    (SELECT COUNT(*) FROM fx_rate_master) as fx_rate_count,
    (SELECT COUNT(*) FROM acc_bal) as acc_bal_count,
    (SELECT COUNT(*) FROM acct_bal_lcy) as acct_bal_lcy_count;

-- Check customer accounts by product
SELECT '========== CUSTOMER ACCOUNTS BY PRODUCT ==========' AS '';
SELECT 
    sp.Sub_Product_Code,
    sp.Sub_Product_Name,
    COUNT(*) as account_count
FROM cust_acct_master ca
LEFT JOIN sub_prod_master sp ON ca.Sub_Product_Id = sp.Sub_Product_Id
WHERE ca.Account_Status = 'Active'
GROUP BY sp.Sub_Product_Code, sp.Sub_Product_Name
ORDER BY account_count DESC
LIMIT 10;

-- Show recent FX rates
SELECT '========== RECENT FX RATES ==========' AS '';
SELECT * FROM fx_rate_master 
ORDER BY Rate_Date DESC 
LIMIT 10;

-- ══════════════════════════════════════════════════════════
-- STEP 8: INSERT TEST DATA (IF MISSING)
-- ══════════════════════════════════════════════════════════

-- Insert test exchange rates for common currencies
SELECT '========== INSERTING TEST FX RATES ==========' AS '';

INSERT INTO fx_rate_master (
    Rate_Date, 
    Ccy_Pair, 
    Mid_Rate, 
    Buying_Rate, 
    Selling_Rate, 
    Source, 
    Uploaded_By, 
    Created_At, 
    Last_Updated
)
VALUES 
  (NOW(), 'USD/BDT', 110.25, 109.75, 110.75, 'MANUAL', 'SYSTEM', NOW(), NOW()),
  (NOW(), 'EUR/BDT', 120.50, 120.00, 121.00, 'MANUAL', 'SYSTEM', NOW(), NOW()),
  (NOW(), 'GBP/BDT', 138.75, 138.00, 139.50, 'MANUAL', 'SYSTEM', NOW(), NOW()),
  (NOW(), 'JPY/BDT', 0.95, 0.94, 0.96, 'MANUAL', 'SYSTEM', NOW(), NOW())
ON DUPLICATE KEY UPDATE 
    Mid_Rate = VALUES(Mid_Rate),
    Buying_Rate = VALUES(Buying_Rate),
    Selling_Rate = VALUES(Selling_Rate),
    Last_Updated = NOW();

-- Verify insertion
SELECT '========== VERIFY FX RATES INSERTED ==========' AS '';
SELECT Ccy_Pair, Mid_Rate, Buying_Rate, Selling_Rate, Rate_Date 
FROM fx_rate_master 
WHERE Ccy_Pair IN ('USD/BDT', 'EUR/BDT', 'GBP/BDT', 'JPY/BDT')
ORDER BY Rate_Date DESC;

-- Check if NOSTRO accounts exist
SELECT '========== CHECKING NOSTRO ACCOUNTS ==========' AS '';
SELECT Account_No, Acct_Name, Account_Ccy, GL_Num, Account_Status 
FROM of_acct_master 
WHERE GL_Num LIKE '22030%';

-- Note: If no NOSTRO accounts exist, you need to create them using your application's
-- account creation process or INSERT them manually with proper sequence/formatting.

-- Check balances for NOSTRO accounts
SELECT '========== NOSTRO ACCOUNT BALANCES (acc_bal) ==========' AS '';
SELECT ab.Account_No, ab.Tran_Date, ab.Opening_Bal, ab.Closing_Bal, ab.Dr, ab.Cr
FROM acc_bal ab
WHERE ab.Account_No IN (
    SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%'
)
ORDER BY ab.Account_No, ab.Tran_Date DESC;

SELECT '========== NOSTRO ACCOUNT BALANCES (acct_bal_lcy) ==========' AS '';
SELECT abl.Account_No, abl.Tran_Date, abl.Opening_Bal_Lcy, abl.Closing_Bal_Lcy, abl.Dr_Lcy, abl.Cr_Lcy
FROM acct_bal_lcy abl
WHERE abl.Account_No IN (
    SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%'
)
ORDER BY abl.Account_No, abl.Tran_Date DESC;

-- ══════════════════════════════════════════════════════════
-- FINAL VERIFICATION SUMMARY
-- ══════════════════════════════════════════════════════════

SELECT '========== FINAL VERIFICATION SUMMARY ==========' AS '';

SELECT 
    'fx_rate_master has data' as check_name,
    CASE WHEN COUNT(*) > 0 THEN 'PASS ✓' ELSE 'FAIL ✗' END as status,
    COUNT(*) as record_count
FROM fx_rate_master
WHERE Ccy_Pair IN ('USD/BDT', 'EUR/BDT', 'GBP/BDT')

UNION ALL

SELECT 
    'NOSTRO accounts exist' as check_name,
    CASE WHEN COUNT(*) > 0 THEN 'PASS ✓' ELSE 'FAIL ✗' END as status,
    COUNT(*) as record_count
FROM of_acct_master
WHERE GL_Num LIKE '22030%' AND Account_Status = 'Active'

UNION ALL

SELECT 
    'NOSTRO balances in acc_bal' as check_name,
    CASE WHEN COUNT(*) > 0 THEN 'PASS ✓' ELSE 'FAIL ✗' END as status,
    COUNT(*) as record_count
FROM acc_bal
WHERE Account_No IN (SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%')

UNION ALL

SELECT 
    'NOSTRO balances in acct_bal_lcy' as check_name,
    CASE WHEN COUNT(*) > 0 THEN 'PASS ✓' ELSE 'FAIL ✗' END as status,
    COUNT(*) as record_count
FROM acct_bal_lcy
WHERE Account_No IN (SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%')

UNION ALL

SELECT 
    'Active customer accounts' as check_name,
    CASE WHEN COUNT(*) > 0 THEN 'PASS ✓' ELSE 'FAIL ✗' END as status,
    COUNT(*) as record_count
FROM cust_acct_master
WHERE Account_Status = 'Active';

-- ══════════════════════════════════════════════════════════
-- INSTRUCTIONS FOR MISSING DATA
-- ══════════════════════════════════════════════════════════

/*
IF ANY CHECKS FAIL:

1. For missing fx_rate_master data:
   - The INSERT above should have added test rates
   - If still failing, check if column names match exactly

2. For missing NOSTRO accounts:
   - NOSTRO accounts must exist in your system
   - They should have GL_Num starting with '22030'
   - Account_Ccy should match the foreign currency (USD, EUR, etc.)
   - You may need to create them through your application's account creation process

3. For missing NOSTRO balances:
   - Once NOSTRO accounts exist, balances should be created by EOD processing
   - For testing, you can manually insert balance records:

   INSERT INTO acc_bal (Account_No, Tran_Date, Opening_Bal, Closing_Bal, Dr, Cr)
   SELECT Account_No, CURDATE(), 100000.00, 100000.00, 100000.00, 0.00
   FROM of_acct_master 
   WHERE GL_Num LIKE '22030%' AND Account_Ccy = 'USD'
   ON DUPLICATE KEY UPDATE Closing_Bal = 100000.00;

   INSERT INTO acct_bal_lcy (Account_No, Tran_Date, Opening_Bal_Lcy, Closing_Bal_Lcy, Dr_Lcy, Cr_Lcy)
   SELECT Account_No, CURDATE(), 11025000.00, 11025000.00, 11025000.00, 0.00
   FROM of_acct_master 
   WHERE GL_Num LIKE '22030%' AND Account_Ccy = 'USD'
   ON DUPLICATE KEY UPDATE Closing_Bal_Lcy = 11025000.00;

4. For missing customer accounts:
   - Customer accounts should already exist in your system
   - FX conversion uses BDT customer accounts (Current or Savings)
   - Verify they exist and are Active status
*/
