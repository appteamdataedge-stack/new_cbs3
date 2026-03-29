-- ═══════════════════════════════════════════════════════════════════════════
-- FX CONVERSION GL ACCOUNTS - Verification & Setup
-- ═══════════════════════════════════════════════════════════════════════════

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 1: Verify All 4 Forex GL Accounts Exist in gl_setup
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'GL Setup - Forex Gain/Loss Accounts' AS description;

SELECT 
  GL_Num,
  GL_Name,
  CASE 
    WHEN GL_Num IN ('140203001', '240203001') THEN 'FX Conversion'
    WHEN GL_Num IN ('140203002', '240203002') THEN 'MCT'
    ELSE 'Other'
  END AS module,
  CASE 
    WHEN GL_Num LIKE '1402%' THEN 'Income (Gain)'
    WHEN GL_Num LIKE '2402%' THEN 'Expense (Loss)'
    ELSE 'Other'
  END AS type
FROM gl_setup
WHERE GL_Num IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY GL_Num;

-- Expected: 4 rows
-- 140203001 - Realised Forex Gain (FX Conversion)
-- 140203002 - Un-Realised Forex Gain (MCT)
-- 240203001 - Realised Forex Loss (FX Conversion)
-- 240203002 - Unrealised Forex Loss (MCT)

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 2: Check GL_Balance Records for Report Date
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'GL Balance - Current Balances for Forex Accounts' AS description;

SELECT 
  GL_Num,
  Tran_date,
  Opening_Bal,
  DR_Summation,
  CR_Summation,
  Current_Balance,
  CASE 
    WHEN GL_Num IN ('140203001', '240203001') THEN 'FX Conversion'
    WHEN GL_Num IN ('140203002', '240203002') THEN 'MCT'
  END AS module
FROM GL_Balance
WHERE GL_Num IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY Tran_date DESC, GL_Num
LIMIT 20;

-- If no records found, the ensureFxGLsPresent() method will add zero-balance entries
-- when generating reports

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 3: Verify FX Conversion Transactions Use Correct GL Accounts
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'FX Conversion Transactions - Gain/Loss Entries' AS description;

SELECT 
  Tran_Id,
  Tran_Type,
  Tran_Sub_Type,
  GL_Num,
  Dr_Cr_Flag,
  Tran_Ccy,
  FCY_Amt,
  LCY_Amt,
  Narration,
  Entry_Date
FROM tran_table
WHERE Tran_Type = 'FXC'
  AND GL_Num IN ('140203001', '240203001')  -- FX Conversion Gain/Loss
ORDER BY Entry_Date DESC, Entry_Time DESC
LIMIT 10;

-- Expected: Transactions with GL 140203001 (Gain CR) or 240203001 (Loss DR)
-- These are Line 4 entries in SELLING transactions

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 4: Compare MCT vs FX Conversion GL Usage
-- ═══════════════════════════════════════════════════════════════════════════

SELECT 
  'Transaction Count by Forex GL Account' AS description;

SELECT 
  GL_Num,
  CASE 
    WHEN GL_Num = '140203001' THEN 'Realised Forex Gain (FX Conversion)'
    WHEN GL_Num = '140203002' THEN 'Un-Realised Forex Gain (MCT)'
    WHEN GL_Num = '240203001' THEN 'Realised Forex Loss (FX Conversion)'
    WHEN GL_Num = '240203002' THEN 'Unrealised Forex Loss (MCT)'
  END AS account_description,
  COUNT(*) AS transaction_count,
  SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) AS total_debit,
  SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) AS total_credit,
  SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE -LCY_Amt END) AS net_balance
FROM tran_table
WHERE GL_Num IN ('140203001', '140203002', '240203001', '240203002')
GROUP BY GL_Num
ORDER BY GL_Num;

-- This shows which modules are using which GL accounts

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 5: Test Report Generation Query
-- ═══════════════════════════════════════════════════════════════════════════

-- Simulate what the report query will return for forex accounts

SELECT 
  'Trial Balance - Forex GL Accounts' AS report_name;

SELECT 
  g.GL_Num,
  g.GL_Name,
  COALESCE(b.Opening_Bal, 0.00) AS opening_balance,
  COALESCE(b.DR_Summation, 0.00) AS dr_summation,
  COALESCE(b.CR_Summation, 0.00) AS cr_summation,
  COALESCE(b.Current_Balance, 0.00) AS closing_balance,
  CASE 
    WHEN g.GL_Num IN ('140203001', '240203001') THEN '✅ FX Conversion'
    WHEN g.GL_Num IN ('140203002', '240203002') THEN '✅ MCT'
  END AS module_tag
FROM gl_setup g
LEFT JOIN GL_Balance b ON g.GL_Num = b.GL_Num AND b.Tran_date = CURDATE()
WHERE g.GL_Num IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY g.GL_Num;

-- Expected: 4 rows, all with ✅ tags showing they're properly categorized

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 6: Initialize GL Balance Records (If Missing)
-- ═══════════════════════════════════════════════════════════════════════════

-- If Step 2 showed no GL_Balance records, initialize them:

INSERT INTO GL_Balance (
  GL_Num,
  Tran_date,
  Opening_Bal,
  DR_Summation,
  CR_Summation,
  Current_Balance,
  Last_Updated
)
SELECT 
  GL_Num,
  CURDATE(),
  0.00,
  0.00,
  0.00,
  0.00,
  NOW()
FROM gl_setup
WHERE GL_Num IN ('140203001', '240203001')
  AND GL_Num NOT IN (
    SELECT GL_Num FROM GL_Balance WHERE Tran_date = CURDATE()
  );

-- Verify insertion
SELECT GL_Num, Tran_date, Current_Balance 
FROM GL_Balance 
WHERE GL_Num IN ('140203001', '240203001')
  AND Tran_date = CURDATE();

-- ═══════════════════════════════════════════════════════════════════════════
-- NOTES
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. FX Conversion module uses 140203001 (Gain) and 240203001 (Loss)
-- 2. MCT module uses 140203002 (Unrealised Gain) and 240203002 (Unrealised Loss)
-- 3. Both sets of accounts must appear in all financial reports
-- 4. The ensureFxGLsPresent() method automatically adds zero-balance entries if missing
-- 5. GL accounts are looked up by GL_Name using glSetupRepository.findByGlName()
-- 6. Transaction Service (MCT) uses constants FX_GAIN_GL and FX_LOSS_GL (140203002, 240203002)
-- 7. FX Conversion Service uses dynamic lookup via getGlNumByName() (140203001, 240203001)
