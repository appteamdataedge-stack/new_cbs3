-- ═══════════════════════════════════════════════════════════════════════════
-- FX CONVERSION SELLING - Balance Setup & Verification
-- ═══════════════════════════════════════════════════════════════════════════

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 1: Check Position FCY Balance (Critical for SELLING)
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'Checking Position FCY Balance (920101002 - PSUSD EQIV)' AS check_description;

SELECT 
  Account_No,
  Account_Ccy,
  Opening_Bal,
  Closing_Bal,
  Available_Balance,
  Dr_Summation,
  Cr_Summation,
  Tran_Date
FROM acc_bal
WHERE Account_No = '920101002'
ORDER BY Tran_Date DESC
LIMIT 1;

-- Expected: Available_Balance should be > 0 (e.g., 10000 USD)
-- If NULL or 0, SELLING will fail with "Insufficient Position USD balance"

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 2: Check Nostro Balance (Should NOT block SELLING even if negative)
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'Checking Nostro USD Balance (922030200102)' AS check_description;

SELECT 
  Account_No,
  Account_Ccy,
  Opening_Bal,
  Closing_Bal,
  Available_Balance,
  Tran_Date
FROM acc_bal
WHERE Account_No = '922030200102'
ORDER BY Tran_Date DESC
LIMIT 1;

-- Note: This balance can be negative (-1000). 
-- In SELLING, Nostro is CREDITED (Line 1 CR), so this should NOT block the transaction.

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 3: Insert Test Balance for Position FCY (If Missing)
-- ═══════════════════════════════════════════════════════════════════════════

-- If Step 1 showed NULL or insufficient balance, run this:

INSERT INTO acc_bal (
  Account_No,
  Account_Ccy,
  Opening_Bal,
  Closing_Bal,
  Available_Balance,
  Dr_Summation,
  Cr_Summation,
  Current_Balance,
  Tran_Date,
  Last_Updated
)
VALUES (
  '920101002',        -- Position FCY account (PSUSD EQIV)
  'USD',
  50000.00,
  50000.00,
  50000.00,
  0.00,
  50000.00,
  50000.00,
  CURDATE(),
  NOW()
)
ON DUPLICATE KEY UPDATE 
  Closing_Bal = 50000.00,
  Available_Balance = 50000.00,
  Current_Balance = 50000.00,
  Last_Updated = NOW();

-- Verify insertion
SELECT 
  Account_No,
  Account_Ccy,
  Available_Balance,
  Tran_Date
FROM acc_bal
WHERE Account_No = '920101002';

-- Expected: Available_Balance = 50000.00 USD

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 4: Insert LCY Balance for Position FCY (For WAE calculation)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO acct_bal_lcy (
  Account_No,
  Opening_Bal_Lcy,
  Closing_Bal_Lcy,
  Available_Balance_Lcy,
  Dr_Summation_Lcy,
  Cr_Summation_Lcy,
  Tran_Date,
  Last_Updated
)
VALUES (
  '920101002',
  5730000.00,      -- 50000 USD * 114.6 WAE
  5730000.00,
  5730000.00,
  0.00,
  5730000.00,
  CURDATE(),
  NOW()
)
ON DUPLICATE KEY UPDATE 
  Closing_Bal_Lcy = 5730000.00,
  Available_Balance_Lcy = 5730000.00,
  Last_Updated = NOW();

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 5: Verify All Required Balances Exist
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'Summary of Required Balances for SELLING Transaction' AS summary;

SELECT 
  'Position FCY (920101002 - Must have balance)' AS account_type,
  Account_No,
  Account_Ccy,
  Available_Balance,
  CASE 
    WHEN Available_Balance IS NULL THEN '❌ NULL - Will fail'
    WHEN Available_Balance <= 0 THEN '❌ Zero/Negative - Will fail'
    WHEN Available_Balance > 0 THEN '✅ OK - Has balance'
  END AS status
FROM acc_bal
WHERE Account_No = '920101002'

UNION ALL

SELECT 
  'Nostro USD (922030200102 - Can be negative)' AS account_type,
  Account_No,
  Account_Ccy,
  Available_Balance,
  CASE 
    WHEN Available_Balance IS NULL THEN '⚠️ NULL - But OK (will be credited)'
    WHEN Available_Balance < 0 THEN '✅ OK - Negative is fine (will be credited)'
    ELSE '✅ OK - Has balance'
  END AS status
FROM acc_bal
WHERE Account_No = '922030200102';

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 6: Test SELLING Transaction After Backend Restart
-- ═══════════════════════════════════════════════════════════════════════════

-- Expected transaction entries in tran_table after successful SELLING:
/*
Tran_Id             Dr_Cr_Flag  Account_No      Tran_Ccy  FCY_Amt   LCY_Amt
FXC-20260329-001-1  C           922030200102    USD       500.00    57300.00
FXC-20260329-001-2  D           920101002       USD       500.00    57300.00
FXC-20260329-001-3  C           920101001       BDT       57300.00  57300.00
FXC-20260329-001-4  D           <LOSS GL>       BDT       2250.00   2250.00
FXC-20260329-001-5  D           100000082001    BDT       55050.00  55050.00
*/

-- ═══════════════════════════════════════════════════════════════════════════
-- NOTES
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. Position FCY (920101002) MUST have sufficient USD balance for SELLING
-- 2. Nostro USD (922030200102) can be negative - it will be CREDITED in SELLING
-- 3. Customer BDT balance is NOT validated - customer RECEIVES BDT in SELLING
-- 4. All transactions start with status "Entry" (pending maker-checker approval)
-- 5. WAE calculation uses Nostro balances (GL pattern 22030%)
