-- ══════════════════════════════════════════════════════════════════
-- VERIFICATION QUERIES FOR lcy_amt IMPLEMENTATION
-- ══════════════════════════════════════════════════════════════════
-- Run these queries after EOD and capitalization to verify correct behavior
-- Replace <test_usd_account> with an actual USD account number
-- Replace <test_date> with the system date being tested
-- ══════════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────────
-- QUERY 1: Check new column exists and is populated after EOD
-- ──────────────────────────────────────────────────────────────────
-- Expected: lcy_amt = SUM of lcy_amt from intt_accr_tran for that account
-- Expected: lcy_amt > 0 for FCY accounts after EOD runs

SELECT 
    account_no,
    tran_date,
    tran_ccy,
    closing_bal AS accrued_fcy_total,
    lcy_amt AS accrued_lcy_total,
    CASE 
        WHEN closing_bal != 0 THEN ROUND(lcy_amt / closing_bal, 4)
        ELSE NULL 
    END AS calculated_wae
FROM acct_bal_accrual
WHERE account_no = '<test_usd_account>'
ORDER BY tran_date DESC
LIMIT 10;

-- Sample Expected Output:
-- account_no        | tran_date  | tran_ccy | accrued_fcy_total | accrued_lcy_total | calculated_wae
-- 200140203002001  | 2026-03-15 | USD      | 45.00             | 5067.00           | 112.6000
-- 200140203002001  | 2026-03-14 | USD      | 30.00             | 3378.00           | 112.6000


-- ──────────────────────────────────────────────────────────────────
-- QUERY 2: Cross-check against source table (intt_accr_tran)
-- ──────────────────────────────────────────────────────────────────
-- Expected: total_lcy matches acct_bal_accrual.lcy_amt for same date

SELECT 
    account_no,
    accrual_date,
    tran_ccy,
    COUNT(*) AS num_entries,
    SUM(fcy_amt) AS total_fcy,
    SUM(lcy_amt) AS total_lcy,
    ROUND(SUM(lcy_amt) / SUM(fcy_amt), 4) AS calculated_wae
FROM intt_accr_tran
WHERE account_no = '<test_usd_account>'
  AND accr_tran_id LIKE 'S%'              -- Only S-type (system accrual)
  AND original_dr_cr_flag IS NULL          -- Exclude value date interest
  AND accrual_date = '<test_date>'
GROUP BY account_no, accrual_date, tran_ccy;

-- Sample Expected Output:
-- account_no       | accrual_date | tran_ccy | num_entries | total_fcy | total_lcy | calculated_wae
-- 200140203002001 | 2026-03-15   | USD      | 1           | 15.00     | 1689.00   | 112.6000

-- Cross-check: Compare this total_lcy with acct_bal_accrual.lcy_amt for same date
-- They should match exactly!


-- ──────────────────────────────────────────────────────────────────
-- QUERY 3: Verify accumulated totals over multiple days
-- ──────────────────────────────────────────────────────────────────
-- Shows how lcy_amt accumulates day by day

WITH daily_accruals AS (
    SELECT 
        accrual_date,
        SUM(fcy_amt) AS daily_fcy,
        SUM(lcy_amt) AS daily_lcy
    FROM intt_accr_tran
    WHERE account_no = '<test_usd_account>'
      AND accr_tran_id LIKE 'S%'
      AND original_dr_cr_flag IS NULL
    GROUP BY accrual_date
    ORDER BY accrual_date
),
cumulative AS (
    SELECT 
        accrual_date,
        daily_fcy,
        daily_lcy,
        SUM(daily_fcy) OVER (ORDER BY accrual_date) AS cumulative_fcy,
        SUM(daily_lcy) OVER (ORDER BY accrual_date) AS cumulative_lcy
    FROM daily_accruals
)
SELECT 
    c.accrual_date,
    c.daily_fcy,
    c.daily_lcy,
    c.cumulative_fcy,
    c.cumulative_lcy,
    ROUND(c.cumulative_lcy / c.cumulative_fcy, 4) AS cumulative_wae,
    aba.closing_bal AS stored_fcy,
    aba.lcy_amt AS stored_lcy,
    CASE 
        WHEN aba.lcy_amt = c.cumulative_lcy THEN '✓ MATCH'
        ELSE '✗ MISMATCH'
    END AS validation
FROM cumulative c
LEFT JOIN acct_bal_accrual aba 
    ON aba.account_no = '<test_usd_account>' 
    AND aba.tran_date = c.accrual_date
ORDER BY c.accrual_date;

-- Sample Expected Output:
-- accrual_date | daily_fcy | daily_lcy | cumulative_fcy | cumulative_lcy | cumulative_wae | stored_fcy | stored_lcy | validation
-- 2026-03-13   | 15.00     | 1687.50   | 15.00          | 1687.50        | 112.5000       | 15.00      | 1687.50    | ✓ MATCH
-- 2026-03-14   | 15.00     | 1689.00   | 30.00          | 3376.50        | 112.5500       | 30.00      | 3376.50    | ✓ MATCH
-- 2026-03-15   | 15.00     | 1690.50   | 45.00          | 5067.00        | 112.6000       | 45.00      | 5067.00    | ✓ MATCH


-- ──────────────────────────────────────────────────────────────────
-- QUERY 4: Check WAE on capitalization entry (C-type)
-- ──────────────────────────────────────────────────────────────────
-- Expected: exchange_rate = WAE (e.g., 112.6135), NOT 1.0000
-- Expected: lcy_amt = fcy_amt × WAE

SELECT 
    accr_tran_id,
    account_no,
    tran_date,
    tran_ccy,
    dr_cr_flag,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    ROUND(fcy_amt * exchange_rate, 2) AS calculated_lcy,
    CASE 
        WHEN ABS(lcy_amt - (fcy_amt * exchange_rate)) < 0.01 THEN '✓ CORRECT'
        ELSE '✗ INCORRECT'
    END AS validation
FROM intt_accr_tran
WHERE accr_tran_id LIKE 'C%'                 -- Capitalization entries only
  AND account_no = '<test_usd_account>'
ORDER BY tran_date DESC, accr_tran_id
LIMIT 5;

-- Sample Expected Output (after capitalization):
-- accr_tran_id         | account_no      | tran_date  | tran_ccy | dr_cr_flag | fcy_amt | exchange_rate | lcy_amt | calculated_lcy | validation
-- C20260315000001-1   | 200140203002001 | 2026-03-15 | USD      | D          | 45.00   | 112.6000      | 5067.00 | 5067.00        | ✓ CORRECT

-- ⚠️ IMPORTANT: exchange_rate should be ~112.6000 (WAE), NOT 1.0000!
-- If exchange_rate = 1.0000, the WAE calculation is not working!


-- ──────────────────────────────────────────────────────────────────
-- QUERY 5: Verify complete capitalization transaction set
-- ──────────────────────────────────────────────────────────────────
-- Shows all legs of a capitalization transaction

SELECT 
    SUBSTRING(accr_tran_id, 1, 18) AS transaction_group,
    accr_tran_id,
    dr_cr_flag,
    gl_account_no,
    tran_ccy,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    CASE dr_cr_flag 
        WHEN 'D' THEN lcy_amt 
        ELSE 0 
    END AS dr_total,
    CASE dr_cr_flag 
        WHEN 'C' THEN lcy_amt 
        ELSE 0 
    END AS cr_total
FROM intt_accr_tran
WHERE accr_tran_id LIKE 'C20260315%'         -- Replace with actual capitalization date
  AND account_no = '<test_usd_account>'
ORDER BY accr_tran_id;

-- Sample Expected Output:
-- transaction_group   | accr_tran_id        | dr_cr_flag | gl_account_no | tran_ccy | fcy_amt | exchange_rate | lcy_amt  | dr_total | cr_total
-- C20260315000001    | C20260315000001-1   | D          | 240203001     | USD      | 45.00   | 112.6000      | 5067.00  | 5067.00  | 0.00
-- (Debit entry in intt_accr_tran - Interest Expense at WAE)

-- Then check tran_table for the customer credit and gain/loss entries:

SELECT 
    SUBSTRING(tran_id, 1, 18) AS transaction_group,
    tran_id,
    dr_cr_flag,
    account_no,
    gl_num,
    tran_ccy,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    CASE dr_cr_flag 
        WHEN 'D' THEN lcy_amt 
        ELSE 0 
    END AS dr_total,
    CASE dr_cr_flag 
        WHEN 'C' THEN lcy_amt 
        ELSE 0 
    END AS cr_total,
    narration
FROM tran_table
WHERE tran_id LIKE 'C20260315%'
  AND (account_no = '<test_usd_account>' OR gl_num IN ('140203002', '240203002'))
ORDER BY tran_id;

-- Sample Expected Output:
-- transaction_group | tran_id             | dr_cr_flag | account_no      | gl_num    | tran_ccy | fcy_amt | exchange_rate | lcy_amt | dr_total | cr_total | narration
-- C20260315000001  | C20260315000001-2   | C          | 200140203002001 | NULL      | USD      | 45.00   | 112.7000      | 5071.50 | 0.00     | 5071.50  | Interest Capitalization - Credit
-- C20260315000001  | C20260315000001-3   | D          | NULL            | 240203002 | BDT      | 4.50    | 1.0000        | 4.50    | 4.50     | 0.00     | FX Loss on Interest Capitalization

-- Balance Check:
-- DR Total = 5067.00 (expense) + 4.50 (loss) = 5071.50
-- CR Total = 5071.50 (customer account)
-- ✓ BALANCED!


-- ──────────────────────────────────────────────────────────────────
-- QUERY 6: Validate LCY balance across all legs
-- ──────────────────────────────────────────────────────────────────

WITH cap_entries AS (
    -- Get debit from intt_accr_tran
    SELECT 
        accr_tran_id AS entry_id,
        'intt_accr_tran' AS source_table,
        dr_cr_flag,
        lcy_amt
    FROM intt_accr_tran
    WHERE accr_tran_id LIKE 'C20260315%'
      AND account_no = '<test_usd_account>'
    
    UNION ALL
    
    -- Get credit and gain/loss from tran_table
    SELECT 
        tran_id AS entry_id,
        'tran_table' AS source_table,
        dr_cr_flag,
        lcy_amt
    FROM tran_table
    WHERE tran_id LIKE 'C20260315%'
      AND (account_no = '<test_usd_account>' OR gl_num IN ('140203002', '240203002'))
)
SELECT 
    SUM(CASE WHEN dr_cr_flag = 'D' THEN lcy_amt ELSE 0 END) AS total_dr_lcy,
    SUM(CASE WHEN dr_cr_flag = 'C' THEN lcy_amt ELSE 0 END) AS total_cr_lcy,
    ABS(
        SUM(CASE WHEN dr_cr_flag = 'D' THEN lcy_amt ELSE 0 END) -
        SUM(CASE WHEN dr_cr_flag = 'C' THEN lcy_amt ELSE 0 END)
    ) AS difference,
    CASE 
        WHEN ABS(
            SUM(CASE WHEN dr_cr_flag = 'D' THEN lcy_amt ELSE 0 END) -
            SUM(CASE WHEN dr_cr_flag = 'C' THEN lcy_amt ELSE 0 END)
        ) <= 0.01 THEN '✓ BALANCED'
        ELSE '✗ IMBALANCED'
    END AS validation
FROM cap_entries;

-- Sample Expected Output:
-- total_dr_lcy | total_cr_lcy | difference | validation
-- 5071.50      | 5071.50      | 0.00       | ✓ BALANCED

-- ⚠️ Difference should be <= 0.01 (1 paisa tolerance for rounding)


-- ──────────────────────────────────────────────────────────────────
-- QUERY 7: Check WAE calculation consistency
-- ──────────────────────────────────────────────────────────────────
-- Compares the WAE stored in acct_bal_accrual with the WAE used in capitalization

SELECT 
    aba.account_no,
    aba.tran_date,
    aba.closing_bal AS accrued_fcy,
    aba.lcy_amt AS accrued_lcy,
    ROUND(aba.lcy_amt / aba.closing_bal, 4) AS accrual_wae,
    iat.exchange_rate AS capitalization_wae,
    CASE 
        WHEN ABS(ROUND(aba.lcy_amt / aba.closing_bal, 4) - iat.exchange_rate) < 0.0001 THEN '✓ CONSISTENT'
        ELSE '✗ INCONSISTENT'
    END AS validation
FROM acct_bal_accrual aba
INNER JOIN intt_accr_tran iat 
    ON iat.account_no = aba.account_no
    AND iat.accr_tran_id LIKE CONCAT('C', DATE_FORMAT(aba.tran_date, '%Y%m%d'), '%')
WHERE aba.account_no = '<test_usd_account>'
  AND iat.dr_cr_flag = 'D'
ORDER BY aba.tran_date DESC
LIMIT 5;

-- Sample Expected Output:
-- account_no      | tran_date  | accrued_fcy | accrued_lcy | accrual_wae | capitalization_wae | validation
-- 200140203002001 | 2026-03-15 | 45.00       | 5067.00     | 112.6000    | 112.6000           | ✓ CONSISTENT


-- ──────────────────────────────────────────────────────────────────
-- QUERY 8: BDT Account Verification (rate should be 1.0000)
-- ──────────────────────────────────────────────────────────────────

SELECT 
    accr_tran_id,
    account_no,
    tran_ccy,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    CASE 
        WHEN tran_ccy = 'BDT' AND exchange_rate = 1.0000 THEN '✓ CORRECT'
        WHEN tran_ccy != 'BDT' AND exchange_rate != 1.0000 THEN '✓ CORRECT'
        ELSE '✗ INCORRECT'
    END AS validation
FROM intt_accr_tran
WHERE accr_tran_id LIKE 'C%'
  AND account_no IN (
      SELECT DISTINCT account_no 
      FROM cust_acct_master 
      WHERE account_ccy = 'BDT'
  )
ORDER BY tran_date DESC
LIMIT 10;

-- Expected: All BDT accounts should have exchange_rate = 1.0000


-- ──────────────────────────────────────────────────────────────────
-- QUERY 9: Value Date Interest Exclusion Test
-- ──────────────────────────────────────────────────────────────────
-- Verify that value date interest entries are NOT counted in lcy_amt

SELECT 
    'Value Date Interest Entries' AS category,
    COUNT(*) AS num_entries,
    SUM(lcy_amt) AS total_lcy
FROM intt_accr_tran
WHERE account_no = '<test_usd_account>'
  AND accrual_date = '<test_date>'
  AND original_dr_cr_flag IS NOT NULL

UNION ALL

SELECT 
    'Regular Accrual Entries (S-type)' AS category,
    COUNT(*) AS num_entries,
    SUM(lcy_amt) AS total_lcy
FROM intt_accr_tran
WHERE account_no = '<test_usd_account>'
  AND accrual_date = '<test_date>'
  AND accr_tran_id LIKE 'S%'
  AND original_dr_cr_flag IS NULL

UNION ALL

SELECT 
    'Stored in acct_bal_accrual' AS category,
    1 AS num_entries,
    lcy_amt AS total_lcy
FROM acct_bal_accrual
WHERE account_no = '<test_usd_account>'
  AND tran_date = '<test_date>';

-- Expected: The "Regular Accrual Entries" total_lcy should match "Stored in acct_bal_accrual" total_lcy
-- Value date interest should be excluded (different total)


-- ══════════════════════════════════════════════════════════════════
-- SUMMARY VALIDATION SCRIPT
-- ══════════════════════════════════════════════════════════════════
-- Run this after a full EOD + Capitalization cycle to get a complete picture

SELECT 
    'EOD Processing' AS stage,
    aba.account_no,
    aba.tran_date,
    aba.tran_ccy,
    aba.closing_bal AS accrued_fcy,
    aba.lcy_amt AS accrued_lcy,
    ROUND(aba.lcy_amt / aba.closing_bal, 4) AS calculated_wae,
    NULL AS cap_fcy,
    NULL AS cap_rate,
    NULL AS cap_lcy,
    'EOD Complete' AS status
FROM acct_bal_accrual aba
WHERE aba.account_no = '<test_usd_account>'
  AND aba.tran_date = '<test_date>'

UNION ALL

SELECT 
    'Capitalization' AS stage,
    iat.account_no,
    iat.tran_date,
    iat.tran_ccy,
    NULL AS accrued_fcy,
    NULL AS accrued_lcy,
    NULL AS calculated_wae,
    iat.fcy_amt AS cap_fcy,
    iat.exchange_rate AS cap_rate,
    iat.lcy_amt AS cap_lcy,
    'Capitalized' AS status
FROM intt_accr_tran iat
WHERE iat.account_no = '<test_usd_account>'
  AND iat.accr_tran_id LIKE 'C%'
  AND iat.dr_cr_flag = 'D'
  AND iat.tran_date = '<test_date>'
ORDER BY stage, tran_date;

-- Sample Expected Output:
-- stage           | account_no      | tran_date  | tran_ccy | accrued_fcy | accrued_lcy | calculated_wae | cap_fcy | cap_rate | cap_lcy | status
-- Capitalization | 200140203002001 | 2026-03-15 | USD      | NULL        | NULL        | NULL           | 45.00   | 112.6000 | 5067.00 | Capitalized
-- EOD Processing | 200140203002001 | 2026-03-15 | USD      | 45.00       | 5067.00     | 112.6000       | NULL    | NULL     | NULL    | EOD Complete

-- ✓ The calculated_wae from EOD should match cap_rate from Capitalization!
