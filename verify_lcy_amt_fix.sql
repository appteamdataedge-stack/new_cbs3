-- ══════════════════════════════════════════════════════════════════
-- Verification Script: lcy_amt Double-Counting Fix
-- ══════════════════════════════════════════════════════════════════
-- Date: March 15, 2026
-- Purpose: Verify that lcy_amt is no longer double-counted
-- ══════════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────────
-- QUERY 1: Check Raw Transactions (Both DR and CR Legs)
-- ──────────────────────────────────────────────────────────────────
SELECT 
    accr_tran_id,
    dr_cr_flag,
    lcy_amt,
    fcy_amt,
    account_no,
    gl_account_no,
    accrual_date
FROM intt_accr_tran
WHERE account_no = '100000008001'
  AND accr_tran_id LIKE 'S%'
  AND original_dr_cr_flag IS NULL
ORDER BY accr_tran_id, dr_cr_flag;

-- Expected Result:
-- Each S-type accrual should have TWO rows (DR and CR) with SAME lcy_amt
-- Example:
--   S20260315001 | D | 5.81 | 0.05 | NULL          | 5132299001 | 2026-03-15
--   S20260315001 | C | 5.81 | 0.05 | 100000008001  | NULL       | 2026-03-15


-- ──────────────────────────────────────────────────────────────────
-- QUERY 2: Old Query Logic (Would Be WRONG - Sums Both Legs)
-- ──────────────────────────────────────────────────────────────────
SELECT 
    'OLD QUERY (WRONG)' as query_type,
    SUM(lcy_amt) as total_lcy,
    COUNT(*) as entry_count
FROM intt_accr_tran
WHERE account_no = '100000008001'
  AND accr_tran_id LIKE 'S%'
  AND original_dr_cr_flag IS NULL;

-- Expected Result (if data exists):
--   query_type         | total_lcy | entry_count
--   OLD QUERY (WRONG)  | 11.62     | 2
--
-- This sums BOTH DR (5.81) + CR (5.81) = 11.62 ❌


-- ──────────────────────────────────────────────────────────────────
-- QUERY 3: New Query Logic (CORRECT - Sums Credit Leg Only)
-- ──────────────────────────────────────────────────────────────────
SELECT 
    'NEW QUERY (CORRECT)' as query_type,
    SUM(lcy_amt) as total_lcy,
    COUNT(*) as entry_count
FROM intt_accr_tran
WHERE account_no = '100000008001'
  AND accr_tran_id LIKE 'S%'
  AND dr_cr_flag = 'C'
  AND original_dr_cr_flag IS NULL;

-- Expected Result (if data exists):
--   query_type           | total_lcy | entry_count
--   NEW QUERY (CORRECT)  | 5.81      | 1
--
-- This sums ONLY CR leg (5.81) = 5.81 ✅


-- ──────────────────────────────────────────────────────────────────
-- QUERY 4: Compare OLD vs NEW (Before EOD Re-run)
-- ──────────────────────────────────────────────────────────────────
SELECT 
    a.account_no,
    a.tran_date,
    a.closing_bal as fcy_total,
    a.lcy_amt as stored_lcy_in_acct_bal_accrual,
    
    -- What OLD query summed (both legs)
    (SELECT SUM(lcy_amt) 
     FROM intt_accr_tran 
     WHERE account_no = a.account_no 
     AND accrual_date = a.tran_date
     AND accr_tran_id LIKE 'S%' 
     AND original_dr_cr_flag IS NULL) as old_query_sum_both_legs,
    
    -- What NEW query sums (credit leg only)
    (SELECT SUM(lcy_amt) 
     FROM intt_accr_tran 
     WHERE account_no = a.account_no 
     AND accrual_date = a.tran_date
     AND accr_tran_id LIKE 'S%' 
     AND dr_cr_flag = 'C'
     AND original_dr_cr_flag IS NULL) as new_query_sum_credit_only,
    
    -- Calculate WAE using stored lcy_amt
    ROUND(a.lcy_amt / NULLIF(a.closing_bal, 0), 4) as stored_wae,
    
    -- Calculate CORRECT WAE using new query
    ROUND((SELECT SUM(lcy_amt) 
           FROM intt_accr_tran 
           WHERE account_no = a.account_no 
           AND accrual_date = a.tran_date
           AND accr_tran_id LIKE 'S%' 
           AND dr_cr_flag = 'C'
           AND original_dr_cr_flag IS NULL) / NULLIF(a.closing_bal, 0), 4) as correct_wae
FROM acct_bal_accrual a
WHERE a.account_no = '100000008001'
ORDER BY a.tran_date DESC
LIMIT 1;

-- Expected Result (BEFORE next EOD run):
--   account_no    | closing_bal | stored_lcy | old_query | new_query | stored_wae | correct_wae
--   100000008001  | 0.05        | 11.62      | 11.62     | 5.81      | 232.40     | 116.20
--
-- Analysis:
--   - stored_lcy (11.62) matches old_query (double-counting bug)
--   - new_query (5.81) is half of stored_lcy (correct value)
--   - correct_wae (116.20) is half of stored_wae (232.40)
--
-- After next EOD run, stored_lcy should become 5.81 and stored_wae should become 116.20


-- ──────────────────────────────────────────────────────────────────
-- QUERY 5: Verify After EOD Re-run (Run This After EOD)
-- ──────────────────────────────────────────────────────────────────
SELECT 
    a.account_no,
    a.tran_date,
    a.closing_bal as fcy_total,
    a.lcy_amt as stored_lcy,
    
    -- What NEW query sums (should match stored_lcy now)
    (SELECT SUM(lcy_amt) 
     FROM intt_accr_tran 
     WHERE account_no = a.account_no 
     AND accrual_date = a.tran_date
     AND accr_tran_id LIKE 'S%' 
     AND dr_cr_flag = 'C'
     AND original_dr_cr_flag IS NULL) as correct_lcy_from_query,
    
    -- Calculate WAE
    ROUND(a.lcy_amt / NULLIF(a.closing_bal, 0), 4) as wae_rate,
    
    -- Check if they match
    CASE 
        WHEN a.lcy_amt = (SELECT SUM(lcy_amt) 
                          FROM intt_accr_tran 
                          WHERE account_no = a.account_no 
                          AND accrual_date = a.tran_date
                          AND accr_tran_id LIKE 'S%' 
                          AND dr_cr_flag = 'C'
                          AND original_dr_cr_flag IS NULL)
        THEN '✅ CORRECT'
        ELSE '❌ MISMATCH'
    END as validation_status
FROM acct_bal_accrual a
WHERE a.account_no = '100000008001'
ORDER BY a.tran_date DESC
LIMIT 1;

-- Expected Result (AFTER next EOD run with fix deployed):
--   account_no    | closing_bal | stored_lcy | correct_lcy | wae_rate | validation_status
--   100000008001  | 0.05        | 5.81       | 5.81        | 116.20   | ✅ CORRECT


-- ──────────────────────────────────────────────────────────────────
-- QUERY 6: Check Multiple Accounts (If Applicable)
-- ──────────────────────────────────────────────────────────────────
SELECT 
    a.account_no,
    cam.account_ccy,
    a.closing_bal as fcy_total,
    a.lcy_amt as stored_lcy,
    
    -- New query sum
    (SELECT SUM(lcy_amt) 
     FROM intt_accr_tran 
     WHERE account_no = a.account_no 
     AND accrual_date = a.tran_date
     AND accr_tran_id LIKE 'S%' 
     AND dr_cr_flag = 'C'
     AND original_dr_cr_flag IS NULL) as correct_lcy,
    
    ROUND(a.lcy_amt / NULLIF(a.closing_bal, 0), 4) as stored_wae,
    
    ROUND((SELECT SUM(lcy_amt) 
           FROM intt_accr_tran 
           WHERE account_no = a.account_no 
           AND accrual_date = a.tran_date
           AND accr_tran_id LIKE 'S%' 
           AND dr_cr_flag = 'C'
           AND original_dr_cr_flag IS NULL) / NULLIF(a.closing_bal, 0), 4) as correct_wae,
    
    CASE 
        WHEN a.lcy_amt = (SELECT SUM(lcy_amt) 
                          FROM intt_accr_tran 
                          WHERE account_no = a.account_no 
                          AND accrual_date = a.tran_date
                          AND accr_tran_id LIKE 'S%' 
                          AND dr_cr_flag = 'C'
                          AND original_dr_cr_flag IS NULL)
        THEN '✅ OK'
        WHEN a.lcy_amt = 2 * (SELECT SUM(lcy_amt) 
                              FROM intt_accr_tran 
                              WHERE account_no = a.account_no 
                              AND accrual_date = a.tran_date
                              AND accr_tran_id LIKE 'S%' 
                              AND dr_cr_flag = 'C'
                              AND original_dr_cr_flag IS NULL)
        THEN '❌ DOUBLE (old bug)'
        ELSE '⚠️ OTHER ISSUE'
    END as status
FROM acct_bal_accrual a
JOIN cust_acct_master cam ON a.account_no = cam.account_no
WHERE cam.account_ccy != 'BDT'  -- Only FCY accounts affected
  AND a.lcy_amt > 0
ORDER BY a.tran_date DESC, a.account_no
LIMIT 10;

-- This query checks all FCY accounts to see which ones have the double-counting bug


-- ══════════════════════════════════════════════════════════════════
-- SUCCESS CRITERIA
-- ══════════════════════════════════════════════════════════════════
-- 
-- BEFORE FIX (Current Data):
--   ✓ QUERY 2 (old logic) returns DOUBLE the correct value
--   ✓ QUERY 3 (new logic) returns CORRECT value (half of QUERY 2)
--   ✓ stored_lcy in acct_bal_accrual matches QUERY 2 (wrong)
--
-- AFTER FIX (After Next EOD Run):
--   ✓ QUERY 3 (new logic) still returns CORRECT value
--   ✓ stored_lcy in acct_bal_accrual now matches QUERY 3 (correct)
--   ✓ WAE rate is correct (not double)
--   ✓ UI displays correct LCY and WAE
-- ══════════════════════════════════════════════════════════════════
