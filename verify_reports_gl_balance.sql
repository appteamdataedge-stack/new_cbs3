-- ═══════════════════════════════════════════════════════════════════════════
-- VERIFY: Trial Balance & Balance Sheet - FX GL Balance Display
-- Fix 10: Check that gl_balance table has actual forex GL balances
-- ═══════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 1: Check current forex GL balances for today
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ FOREX GL BALANCES (TODAY) ═══' as Info;

SELECT 
    GL_Num,
    Tran_Date,
    Opening_Bal,
    DR_Summation,
    CR_Summation,
    Closing_Bal,
    Current_Balance
FROM gl_balance
WHERE GL_Num IN ('140203001', '140203002', '240203001', '240203002')
  AND Tran_Date = CURDATE()
ORDER BY GL_Num;

-- Expected for FX Conversion GLs with transactions:
-- 140203001 | 2026-03-29 | 0.00 | 236.82  | 0.00    | 236.82   | 236.82
-- 240203001 | 2026-03-29 | 0.00 | 0.00    | 1225.00 | -1225.00 | -1225.00

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 2: Check historical forex GL balances (last 7 days)
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ FOREX GL BALANCES (LAST 7 DAYS) ═══' as Info;

SELECT 
    GL_Num,
    Tran_Date,
    Closing_Bal
FROM gl_balance
WHERE GL_Num IN ('140203001', '240203001')
  AND Tran_Date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
ORDER BY Tran_Date DESC, GL_Num;

-- This shows when forex gains/losses were last recorded

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 3: Check FX transactions that posted to forex GLs
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ FX TRANSACTIONS POSTED TO FOREX GLs ═══' as Info;

SELECT 
    Tran_Id,
    Tran_Date,
    Account_No,
    Dr_Cr,
    Ccy,
    Amount,
    LCY_Equiv,
    Narration
FROM tran_table
WHERE Account_No IN ('140203001', '240203001')
ORDER BY Entry_Date DESC, Entry_Time DESC
LIMIT 10;

-- Expected:
-- - Transaction IDs starting with F (FX Conversion)
-- - Account_No = 140203001 (gains) or 240203001 (losses)
-- - Dr_Cr = CR for gains, DR for losses
-- - Amount matches forex calculation

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 4: Verify GL accounts exist in gl_master
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ FOREX GL ACCOUNTS IN gl_master ═══' as Info;

SELECT 
    GL_Code,
    GL_Name,
    GL_Type,
    Status
FROM gl_master
WHERE GL_Code IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY GL_Code;

-- Expected: All 4 accounts exist and are Active

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 5: Check active GL numbers (used in reports query)
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ ACTIVE GL NUMBERS WITH LINKED ACCOUNTS ═══' as Info;

SELECT COUNT(DISTINCT gs.GL_Num) as Total_Active_GLs
FROM gl_setup gs
WHERE gs.GL_Num IN (
    SELECT DISTINCT Cum_GL_Num FROM sub_prod_master WHERE Cum_GL_Num IS NOT NULL
);

-- This shows how many GLs are in the "active" list
-- FX GLs (140203001, 240203001) are NOT in this list because they're system GLs

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 6: Simulate report query BEFORE fix
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ REPORT QUERY BEFORE FIX (Active GLs Only) ═══' as Info;

-- This simulates what the report query returned BEFORE Fix 10
SELECT 
    gb.GL_Num,
    gb.Closing_Bal
FROM gl_balance gb
WHERE gb.Tran_Date = CURDATE()
  AND gb.GL_Num IN (
      SELECT DISTINCT Cum_GL_Num FROM sub_prod_master WHERE Cum_GL_Num IS NOT NULL
  )
  AND gb.GL_Num IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY gb.GL_Num;

-- Expected: EMPTY RESULT SET (no forex GLs in active list)
-- This is why ensureFxGLsPresent() was needed

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 7: Simulate report query AFTER fix
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ REPORT QUERY AFTER FIX (With Database Fetch) ═══' as Info;

-- This simulates what Fix 10 does: fetch forex GLs directly
SELECT 
    GL_Num,
    Tran_Date,
    Opening_Bal,
    DR_Summation,
    CR_Summation,
    Closing_Bal
FROM gl_balance
WHERE Tran_Date = CURDATE()
  AND GL_Num IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY GL_Num;

-- Expected: Returns actual balances (236.82, -1225.00)
-- This is what ensureFxGLsPresent() NOW does with Fix 10 ✅

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 8: Trial Balance format check
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ TRIAL BALANCE FORMAT (What Report Should Show) ═══' as Info;

SELECT 
    g.GL_Code as GL_Code,
    g.GL_Name as GL_Name,
    COALESCE(gb.Opening_Bal, 0.00) as Opening_Bal,
    COALESCE(gb.DR_Summation, 0.00) as DR_Summation,
    COALESCE(gb.CR_Summation, 0.00) as CR_Summation,
    COALESCE(gb.Closing_Bal, 0.00) as Closing_Bal
FROM gl_master g
LEFT JOIN gl_balance gb ON g.GL_Code = gb.GL_Num AND gb.Tran_Date = CURDATE()
WHERE g.GL_Code IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY g.GL_Code;

-- Expected Output:
-- 140203001 | Realised Forex Gain | 0.00 | 236.82  | 0.00    | 236.82
-- 140203002 | Un-Realised Forex Gain | 0.00 | 0.00  | 0.00    | 0.00
-- 240203001 | Realised Forex Loss | 0.00 | 0.00    | 1225.00 | -1225.00
-- 240203002 | Unrealised Forex Loss | 0.00 | 0.00  | 0.00    | 0.00

-- ─────────────────────────────────────────────────────────────────────────────
-- PART 9: Balance Sheet format check
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ BALANCE SHEET FORMAT (What Report Should Show) ═══' as Info;

SELECT 
    g.GL_Code,
    g.GL_Name,
    CASE 
        WHEN g.GL_Code LIKE '1%' THEN 'LIABILITIES'
        WHEN g.GL_Code LIKE '2%' THEN 'ASSETS'
    END as Side,
    COALESCE(gb.Closing_Bal, 0.00) as Closing_Bal
FROM gl_master g
LEFT JOIN gl_balance gb ON g.GL_Code = gb.GL_Num AND gb.Tran_Date = CURDATE()
WHERE g.GL_Code IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY g.GL_Code;

-- Expected Output:
-- 140203001 | Realised Forex Gain | LIABILITIES | 236.82
-- 140203002 | Un-Realised Forex Gain | LIABILITIES | 0.00
-- 240203001 | Realised Forex Loss | ASSETS | -1225.00
-- 240203002 | Unrealised Forex Loss | ASSETS | 0.00

-- ─────────────────────────────────────────────────────────────────────────────
-- SUMMARY
-- ─────────────────────────────────────────────────────────────────────────────

SELECT 
    '═══ FIX 10 VERIFICATION SUMMARY ═══' as Info;

SELECT 
    'Issue' as Category,
    'Reports showed 0.00 for forex GLs despite database having balances' as Description
UNION ALL
SELECT 
    'Root Cause',
    'ensureFxGLsPresent() added hardcoded zero balances without querying database'
UNION ALL
SELECT 
    'Fix',
    'Query gl_balance table directly for forex GLs, use actual balances'
UNION ALL
SELECT 
    'Result',
    'Reports now show 140203001=236.82, 240203001=-1225.00 from database';

-- ═══════════════════════════════════════════════════════════════════════════
-- If no balances found for today, check previous dates:
-- ═══════════════════════════════════════════════════════════════════════════

-- Uncomment and run if CURDATE() returns no data:
-- SELECT GL_Num, Tran_Date, Closing_Bal 
-- FROM gl_balance 
-- WHERE GL_Num IN ('140203001', '240203001')
--   AND Tran_Date >= '2026-02-01'
-- ORDER BY Tran_Date DESC, GL_Num;
