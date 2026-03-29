-- ═══════════════════════════════════════════════════════════════════════════
-- FX CONVERSION - GL Balance Setup & Verification
-- ═══════════════════════════════════════════════════════════════════════════

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 1: Check GL_Balance Table Structure
-- ═══════════════════════════════════════════════════════════════════════════

DESCRIBE GL_Balance;

-- Expected columns:
-- - Id (auto-increment PK)
-- - GL_Num (varchar)
-- - Tran_date (date)
-- - Current_Balance (decimal)
-- - Opening_Bal (decimal)
-- - DR_Summation (decimal)
-- - CR_Summation (decimal)
-- - Last_Updated (datetime)

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 2: Check Position FCY Balance (920101002 - PSUSD EQIV)
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'Position FCY Balance (920101002 - PSUSD EQIV)' AS description;

SELECT 
  Id,
  GL_Num,
  Tran_date,
  Current_Balance,
  Opening_Bal,
  DR_Summation,
  CR_Summation,
  Last_Updated
FROM GL_Balance
WHERE GL_Num = '920101002'
ORDER BY Tran_date DESC
LIMIT 1;

-- Expected: Current_Balance should be > 0 for SELLING to work
-- If empty, SELLING will fail with "Insufficient Position USD balance"

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 3: Check Position BDT Balance (920101001 - PSBDT or PSUSD)
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'Position BDT Balance (920101001)' AS description;

SELECT 
  Id,
  GL_Num,
  Tran_date,
  Current_Balance,
  Opening_Bal,
  DR_Summation,
  CR_Summation,
  Last_Updated
FROM GL_Balance
WHERE GL_Num = '920101001'
ORDER BY Tran_date DESC
LIMIT 1;

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 4: Check What GL Accounts Exist
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'All GL Balances in System' AS description;

SELECT 
  GL_Num,
  Tran_date,
  Current_Balance,
  COUNT(*) as record_count
FROM GL_Balance
GROUP BY GL_Num, Tran_date
ORDER BY GL_Num, Tran_date DESC
LIMIT 20;

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 5: Check GL Setup for Position Accounts
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'GL Setup for Position Accounts' AS description;

SELECT 
  GL_Num,
  GL_Desc,
  GL_Type,
  Ccy_Code
FROM gl_setup
WHERE GL_Desc LIKE '%POSITION%' 
   OR GL_Desc LIKE '%PSUSD%'
   OR GL_Desc LIKE '%PSBDT%'
   OR GL_Num IN ('920101001', '920101002');

-- This shows what Position GL accounts exist and their descriptions

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 6: Insert Test GL Balance for Position FCY (920101002)
-- ═══════════════════════════════════════════════════════════════════════════

-- If Step 2 showed empty result, insert test balance:

INSERT INTO GL_Balance (
  GL_Num,
  Tran_date,
  Current_Balance,
  Opening_Bal,
  DR_Summation,
  CR_Summation,
  Last_Updated
)
VALUES (
  '920101002',        -- Position FCY account (PSUSD EQIV)
  CURDATE(),
  50000.00,           -- USD 50,000 available
  50000.00,
  0.00,
  50000.00,
  NOW()
)
ON DUPLICATE KEY UPDATE 
  Current_Balance = 50000.00,
  Opening_Bal = 50000.00,
  CR_Summation = 50000.00,
  Last_Updated = NOW();

-- Verify insertion
SELECT * FROM GL_Balance WHERE GL_Num = '920101002';

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 7: Insert Test GL Balance for Position BDT (920101001)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO GL_Balance (
  GL_Num,
  Tran_date,
  Current_Balance,
  Opening_Bal,
  DR_Summation,
  CR_Summation,
  Last_Updated
)
VALUES (
  '920101001',        -- Position BDT account
  CURDATE(),
  5000000.00,         -- BDT 5,000,000 available
  5000000.00,
  0.00,
  5000000.00,
  NOW()
)
ON DUPLICATE KEY UPDATE 
  Current_Balance = 5000000.00,
  Opening_Bal = 5000000.00,
  CR_Summation = 5000000.00,
  Last_Updated = NOW();

-- Verify insertion
SELECT * FROM GL_Balance WHERE GL_Num = '920101001';

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 8: Verify All Required GL Balances for FX Conversion
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'Summary: GL Balances Required for FX Conversion' AS summary;

SELECT 
  GL_Num,
  CASE 
    WHEN GL_Num = '920101002' THEN 'Position FCY (PSUSD EQIV) - Required for SELLING'
    WHEN GL_Num = '920101001' THEN 'Position BDT (PSUSD) - Required for BUYING'
    ELSE 'Other GL Account'
  END AS account_description,
  Tran_date,
  Current_Balance,
  CASE 
    WHEN Current_Balance IS NULL THEN '❌ NULL - Will fail'
    WHEN Current_Balance <= 0 THEN '⚠️ Zero/Negative - May fail'
    WHEN Current_Balance > 0 THEN '✅ OK - Has balance'
  END AS status
FROM GL_Balance
WHERE GL_Num IN ('920101001', '920101002')
ORDER BY GL_Num, Tran_date DESC;

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 9: Check NOSTRO Account Balances (For WAE Calculation)
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'NOSTRO Account Balances (For WAE Calculation)' AS description;

SELECT 
  o.Account_No,
  o.Acct_Name,
  o.Account_Ccy,
  o.GL_Num,
  o.Account_Status,
  b.Closing_Bal AS fcy_balance,
  l.Closing_Bal_Lcy AS lcy_balance,
  CASE 
    WHEN b.Closing_Bal IS NOT NULL AND l.Closing_Bal_Lcy IS NOT NULL 
      THEN CONCAT('WAE = ', ROUND(l.Closing_Bal_Lcy / NULLIF(b.Closing_Bal, 0), 6))
    ELSE 'WAE Cannot Calculate (Missing Balances)'
  END AS wae_info
FROM of_acct_master o
LEFT JOIN acc_bal b ON o.Account_No = b.Account_No
LEFT JOIN acct_bal_lcy l ON o.Account_No = l.Account_No
WHERE o.GL_Num LIKE '22030%'
  AND o.Account_Ccy = 'USD'
  AND o.Account_Status = 'Active'
ORDER BY o.Account_No;

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 10: Complete Test Data Setup
-- ═══════════════════════════════════════════════════════════════════════════

-- Run this if you need to set up ALL required balances:

START TRANSACTION;

-- Position FCY (USD)
INSERT INTO GL_Balance (GL_Num, Tran_date, Current_Balance, Opening_Bal, DR_Summation, CR_Summation, Last_Updated)
VALUES ('920101002', CURDATE(), 50000.00, 50000.00, 0.00, 50000.00, NOW())
ON DUPLICATE KEY UPDATE Current_Balance = 50000.00, Opening_Bal = 50000.00, CR_Summation = 50000.00, Last_Updated = NOW();

-- Position BDT
INSERT INTO GL_Balance (GL_Num, Tran_date, Current_Balance, Opening_Bal, DR_Summation, CR_Summation, Last_Updated)
VALUES ('920101001', CURDATE(), 5000000.00, 5000000.00, 0.00, 5000000.00, NOW())
ON DUPLICATE KEY UPDATE Current_Balance = 5000000.00, Opening_Bal = 5000000.00, CR_Summation = 5000000.00, Last_Updated = NOW();

-- NOSTRO USD balances (for WAE calculation)
-- Assuming NOSTRO account is 922030200102
INSERT INTO acc_bal (Account_No, Account_Ccy, Opening_Bal, Closing_Bal, Available_Balance, Dr_Summation, Cr_Summation, Current_Balance, Tran_Date, Last_Updated)
SELECT Account_No, 'USD', 10000.00, 10000.00, 10000.00, 0.00, 10000.00, 10000.00, CURDATE(), NOW()
FROM of_acct_master
WHERE GL_Num LIKE '22030%' AND Account_Ccy = 'USD' AND Account_Status = 'Active'
ON DUPLICATE KEY UPDATE Closing_Bal = 10000.00, Available_Balance = 10000.00, Current_Balance = 10000.00, Last_Updated = NOW();

INSERT INTO acct_bal_lcy (Account_No, Opening_Bal_Lcy, Closing_Bal_Lcy, Available_Balance_Lcy, Dr_Summation_Lcy, Cr_Summation_Lcy, Tran_Date, Last_Updated)
SELECT Account_No, 1102500.00, 1102500.00, 1102500.00, 0.00, 1102500.00, CURDATE(), NOW()
FROM of_acct_master
WHERE GL_Num LIKE '22030%' AND Account_Ccy = 'USD' AND Account_Status = 'Active'
ON DUPLICATE KEY UPDATE Closing_Bal_Lcy = 1102500.00, Available_Balance_Lcy = 1102500.00, Last_Updated = NOW();

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 11: Verify All Balances After Insert
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 'VERIFICATION COMPLETE' AS status;

SELECT 
  'Position FCY (920101002)' AS account_type,
  GL_Num,
  Current_Balance,
  '✅ Ready for SELLING' AS ready_status
FROM GL_Balance
WHERE GL_Num = '920101002' AND Tran_date = CURDATE()

UNION ALL

SELECT 
  'Position BDT (920101001)' AS account_type,
  GL_Num,
  Current_Balance,
  '✅ Ready for BUYING' AS ready_status
FROM GL_Balance
WHERE GL_Num = '920101001' AND Tran_date = CURDATE();

-- Expected output:
-- Position FCY: 50000.00 ✅
-- Position BDT: 5000000.00 ✅

-- ═══════════════════════════════════════════════════════════════════════════
-- NOTES
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. Position accounts (920101001, 920101002) are GL accounts, stored in GL_Balance table
-- 2. Customer accounts (100000082001, etc.) are stored in cust_acct_master with balances in acc_bal
-- 3. NOSTRO accounts (922030200102, etc.) are stored in of_acct_master with balances in acc_bal
-- 4. Backend uses:
--    - balanceService.getGLBalance(glNum) for GL accounts (Position)
--    - balanceService.getComputedAccountBalance(accountNo) for customer/office accounts
-- 5. SELLING validates Position FCY (920101002) has sufficient USD
-- 6. BUYING validates Position BDT (920101001) has sufficient BDT (optional)
