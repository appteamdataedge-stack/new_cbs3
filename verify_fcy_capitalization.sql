-- ══════════════════════════════════════════════════════════════════
-- FCY INTEREST CAPITALIZATION VERIFICATION QUERIES
-- ══════════════════════════════════════════════════════════════════
-- Purpose: Verify the new 3-leg capitalization transaction structure
-- with proper WAE/MID rate handling and gain/loss accounting
-- ══════════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────────
-- STEP 1: Find Recent Capitalizations
-- ──────────────────────────────────────────────────────────────────

SELECT 
    SUBSTRING(tran_id, 1, 18) as transaction_group,
    MIN(tran_date) as cap_date,
    COUNT(*) as num_legs,
    MAX(CASE WHEN account_no IS NOT NULL THEN account_no END) as account_no
FROM tran_table
WHERE tran_id LIKE 'C%'
  AND tran_date >= CURDATE() - INTERVAL 7 DAY
GROUP BY SUBSTRING(tran_id, 1, 18)
ORDER BY MIN(tran_date) DESC
LIMIT 20;

-- Expected: Each capitalization should have 2-3 legs (2 if WAE=MID, 3 if WAE≠MID)


-- ──────────────────────────────────────────────────────────────────
-- STEP 2: Detailed Transaction Structure Check
-- ──────────────────────────────────────────────────────────────────
-- Replace <CAP_TRAN_ID> with actual capitalization ID from Step 1

SET @cap_tran_id = 'C20260315000001'; -- Replace with actual ID

SELECT 
    tran_id,
    account_no,
    gl_num,
    dr_cr_flag,
    tran_ccy,
    ROUND(fcy_amt, 2) as fcy,
    ROUND(exchange_rate, 4) as rate,
    ROUND(lcy_amt, 2) as lcy,
    ROUND(debit_amount, 2) as dr_amt,
    ROUND(credit_amount, 2) as cr_amt,
    narration
FROM tran_table
WHERE tran_id LIKE CONCAT(@cap_tran_id, '%')
ORDER BY tran_id;

-- Expected Structure:
-- -1: DR  <account>  <ccy>  <fcy>  <WAE>    <lcy_wae>   <lcy_wae>  0          Accrual Release
-- -2: CR  <account>  <ccy>  <fcy>  <MID>    <lcy_mid>   0          <lcy_mid>  Credit to Account
-- -3: DR/CR  NULL  140203002/240203002  BDT  0  1.0  <diff>  <diff>  0/gain  FX Gain/Loss


-- ──────────────────────────────────────────────────────────────────
-- STEP 3: LCY Balance Verification
-- ──────────────────────────────────────────────────────────────────

SELECT 
    @cap_tran_id as transaction_id,
    SUM(CASE WHEN dr_cr_flag='D' THEN lcy_amt ELSE 0 END) as total_dr_lcy,
    SUM(CASE WHEN dr_cr_flag='C' THEN lcy_amt ELSE 0 END) as total_cr_lcy,
    ROUND(
        SUM(CASE WHEN dr_cr_flag='D' THEN lcy_amt ELSE 0 END) -
        SUM(CASE WHEN dr_cr_flag='C' THEN lcy_amt ELSE 0 END),
        2
    ) as difference,
    CASE 
        WHEN ABS(
            SUM(CASE WHEN dr_cr_flag='D' THEN lcy_amt ELSE 0 END) -
            SUM(CASE WHEN dr_cr_flag='C' THEN lcy_amt ELSE 0 END)
        ) <= 0.01 THEN '✓ BALANCED'
        ELSE '✗ IMBALANCED'
    END as status
FROM tran_table
WHERE tran_id LIKE CONCAT(@cap_tran_id, '%');

-- Expected: difference = 0.00 (or within 0.01 tolerance), status = '✓ BALANCED'


-- ──────────────────────────────────────────────────────────────────
-- STEP 4: WAE vs MID Rate Comparison
-- ──────────────────────────────────────────────────────────────────

WITH cap_data AS (
    SELECT 
        account_no,
        tran_date,
        MAX(CASE WHEN tran_id LIKE '%-1' THEN exchange_rate END) as wae_rate,
        MAX(CASE WHEN tran_id LIKE '%-2' THEN exchange_rate END) as mid_rate,
        MAX(CASE WHEN tran_id LIKE '%-1' THEN fcy_amt END) as fcy_amt,
        MAX(CASE WHEN tran_id LIKE '%-1' THEN lcy_amt END) as accrual_lcy,
        MAX(CASE WHEN tran_id LIKE '%-2' THEN lcy_amt END) as account_lcy
    FROM tran_table
    WHERE tran_id LIKE CONCAT(@cap_tran_id, '%')
    GROUP BY account_no, tran_date
)
SELECT 
    account_no,
    tran_date,
    fcy_amt,
    ROUND(wae_rate, 4) as wae,
    ROUND(mid_rate, 4) as mid,
    ROUND(accrual_lcy, 2) as accrual_lcy,
    ROUND(account_lcy, 2) as account_lcy,
    ROUND(ABS(accrual_lcy - account_lcy), 2) as gain_loss_amt,
    CASE 
        WHEN wae_rate > mid_rate THEN 'GAIN (WAE > MID)'
        WHEN wae_rate < mid_rate THEN 'LOSS (WAE < MID)'
        ELSE 'NONE (WAE = MID)'
    END as expected_type
FROM cap_data;

-- Expected: gain_loss_amt should match LEG -3 lcy_amt if it exists


-- ──────────────────────────────────────────────────────────────────
-- STEP 5: Verify Gain/Loss GL Entry Direction
-- ──────────────────────────────────────────────────────────────────

WITH cap_rates AS (
    SELECT 
        SUBSTRING(tran_id, 1, 18) as trans_group,
        MAX(CASE WHEN tran_id LIKE '%-1' THEN exchange_rate END) as wae,
        MAX(CASE WHEN tran_id LIKE '%-2' THEN exchange_rate END) as mid
    FROM tran_table
    WHERE tran_id LIKE CONCAT(@cap_tran_id, '%')
    GROUP BY SUBSTRING(tran_id, 1, 18)
),
gain_loss AS (
    SELECT 
        tt.tran_id,
        tt.gl_num,
        tt.dr_cr_flag,
        tt.lcy_amt,
        cr.wae,
        cr.mid
    FROM tran_table tt
    JOIN cap_rates cr ON SUBSTRING(tt.tran_id, 1, 18) = cr.trans_group
    WHERE tt.tran_id LIKE CONCAT(@cap_tran_id, '%-3')
)
SELECT 
    tran_id,
    gl_num,
    dr_cr_flag,
    ROUND(lcy_amt, 2) as amount,
    ROUND(wae, 4) as wae_rate,
    ROUND(mid, 4) as mid_rate,
    CASE 
        WHEN wae < mid THEN 'Expected: LOSS (DR 240203002)'
        WHEN wae > mid THEN 'Expected: GAIN (CR 140203002)'
        ELSE 'Expected: NONE (no -3 leg)'
    END as expected,
    CASE 
        WHEN wae < mid AND dr_cr_flag = 'D' AND gl_num = '240203002' THEN '✓ CORRECT'
        WHEN wae > mid AND dr_cr_flag = 'C' AND gl_num = '140203002' THEN '✓ CORRECT'
        WHEN wae = mid THEN '✓ NO GAIN/LOSS NEEDED'
        ELSE '✗ WRONG DIRECTION/GL'
    END as validation
FROM gain_loss;

-- Expected: 
-- - If WAE < MID: DR to GL 240203002 (Loss)
-- - If WAE > MID: CR to GL 140203002 (Gain)
-- - If WAE = MID: No -3 leg exists


-- ──────────────────────────────────────────────────────────────────
-- STEP 6: Verify Accrual Balance Zeroed Out
-- ──────────────────────────────────────────────────────────────────

SET @account_no = (
    SELECT account_no 
    FROM tran_table 
    WHERE tran_id LIKE CONCAT(@cap_tran_id, '%-1')
    LIMIT 1
);

SELECT 
    account_no,
    tran_date,
    ROUND(closing_bal, 2) as closing_bal,
    ROUND(lcy_amt, 2) as lcy_amt,
    ROUND(cr_summation, 2) as cr_summation,
    ROUND(dr_summation, 2) as dr_summation,
    ROUND(interest_amount, 2) as interest_amount,
    CASE 
        WHEN closing_bal = 0 AND lcy_amt = 0 THEN '✓ ZEROED'
        ELSE '✗ NOT ZEROED'
    END as status
FROM acct_bal_accrual
WHERE account_no = @account_no
ORDER BY tran_date DESC
LIMIT 1;

-- Expected: All values = 0.00, status = '✓ ZEROED'


-- ──────────────────────────────────────────────────────────────────
-- STEP 7: Verify S-Prefix Entries Marked as Posted
-- ──────────────────────────────────────────────────────────────────

SELECT 
    accr_tran_id,
    status,
    accrual_date,
    ROUND(fcy_amt, 2) as fcy,
    ROUND(lcy_amt, 2) as lcy,
    narration
FROM intt_accr_tran
WHERE account_no = @account_no
  AND accr_tran_id LIKE 'S%'
ORDER BY accrual_date DESC
LIMIT 10;

-- Expected: All S-prefix entries should have status = 'Posted' after capitalization


-- ──────────────────────────────────────────────────────────────────
-- STEP 8: Verify Account Balance Updated
-- ──────────────────────────────────────────────────────────────────

SELECT 
    ab.account_no,
    ab.tran_date,
    ab.account_ccy,
    ROUND(ab.current_balance, 2) as current_balance,
    ROUND(ab.available_balance, 2) as available_balance,
    ROUND(ab.wae_rate, 4) as wae_rate,
    ROUND(tt.fcy_amt, 2) as capitalized_fcy
FROM acct_bal ab
LEFT JOIN tran_table tt ON tt.account_no = ab.account_no 
    AND tt.tran_id LIKE CONCAT(@cap_tran_id, '%-2')
WHERE ab.account_no = @account_no
ORDER BY ab.tran_date DESC
LIMIT 2;

-- Expected: current_balance increased by capitalized_fcy amount


-- ──────────────────────────────────────────────────────────────────
-- STEP 9: Compare Before/After Balances
-- ──────────────────────────────────────────────────────────────────

WITH cap_info AS (
    SELECT 
        account_no,
        tran_date as cap_date,
        fcy_amt as cap_amount
    FROM tran_table
    WHERE tran_id LIKE CONCAT(@cap_tran_id, '%-2')
),
balances AS (
    SELECT 
        ab.account_no,
        ab.tran_date,
        ab.current_balance,
        ci.cap_date,
        ci.cap_amount,
        CASE 
            WHEN ab.tran_date < ci.cap_date THEN 'BEFORE'
            WHEN ab.tran_date = ci.cap_date THEN 'AFTER'
            ELSE 'LATER'
        END as timing
    FROM acct_bal ab
    CROSS JOIN cap_info ci
    WHERE ab.account_no = ci.account_no
      AND ab.tran_date >= ci.cap_date - INTERVAL 1 DAY
      AND ab.tran_date <= ci.cap_date + INTERVAL 1 DAY
)
SELECT 
    account_no,
    tran_date,
    timing,
    ROUND(current_balance, 2) as balance,
    ROUND(cap_amount, 2) as cap_amt,
    ROUND(
        current_balance - LAG(current_balance) OVER (ORDER BY tran_date),
        2
    ) as change,
    CASE 
        WHEN timing = 'AFTER' AND 
             ABS((current_balance - LAG(current_balance) OVER (ORDER BY tran_date)) - cap_amount) < 0.01
        THEN '✓ CORRECT INCREASE'
        WHEN timing = 'BEFORE' THEN '—'
        ELSE '✗ MISMATCH'
    END as validation
FROM balances
ORDER BY tran_date;

-- Expected: Balance should increase by exactly the capitalized amount


-- ──────────────────────────────────────────────────────────────────
-- STEP 10: Full Capitalization Summary
-- ──────────────────────────────────────────────────────────────────

WITH cap_summary AS (
    SELECT 
        SUBSTRING(tt.tran_id, 1, 18) as transaction_id,
        MAX(tt.tran_date) as cap_date,
        MAX(CASE WHEN tt.account_no IS NOT NULL THEN tt.account_no END) as account_no,
        MAX(CASE WHEN tt.account_no IS NOT NULL THEN tt.tran_ccy END) as currency,
        MAX(CASE WHEN tt.tran_id LIKE '%-1' THEN tt.fcy_amt END) as fcy_amount,
        MAX(CASE WHEN tt.tran_id LIKE '%-1' THEN tt.exchange_rate END) as wae_rate,
        MAX(CASE WHEN tt.tran_id LIKE '%-2' THEN tt.exchange_rate END) as mid_rate,
        MAX(CASE WHEN tt.tran_id LIKE '%-1' THEN tt.lcy_amt END) as accrual_lcy,
        MAX(CASE WHEN tt.tran_id LIKE '%-2' THEN tt.lcy_amt END) as account_lcy,
        MAX(CASE WHEN tt.tran_id LIKE '%-3' THEN tt.lcy_amt END) as gain_loss_lcy,
        MAX(CASE WHEN tt.tran_id LIKE '%-3' THEN tt.dr_cr_flag END) as gain_loss_type,
        COUNT(DISTINCT tt.tran_id) as num_legs
    FROM tran_table tt
    WHERE tt.tran_id LIKE CONCAT(@cap_tran_id, '%')
    GROUP BY SUBSTRING(tt.tran_id, 1, 18)
)
SELECT 
    transaction_id,
    cap_date,
    account_no,
    currency,
    ROUND(fcy_amount, 2) as fcy,
    ROUND(wae_rate, 4) as wae,
    ROUND(mid_rate, 4) as mid,
    ROUND(accrual_lcy, 2) as accrual_lcy,
    ROUND(account_lcy, 2) as account_lcy,
    ROUND(COALESCE(gain_loss_lcy, 0), 2) as gain_loss,
    CASE 
        WHEN gain_loss_type = 'D' THEN 'LOSS'
        WHEN gain_loss_type = 'C' THEN 'GAIN'
        ELSE 'NONE'
    END as type,
    num_legs,
    -- Validations
    CASE 
        WHEN num_legs = 2 AND wae_rate = mid_rate THEN '✓ NO GAIN/LOSS NEEDED'
        WHEN num_legs = 3 AND wae_rate != mid_rate THEN '✓ GAIN/LOSS PRESENT'
        ELSE '✗ UNEXPECTED LEG COUNT'
    END as leg_validation,
    CASE 
        WHEN ABS(accrual_lcy - account_lcy) - COALESCE(gain_loss_lcy, 0) <= 0.01 THEN '✓ AMOUNT MATCHES'
        ELSE '✗ AMOUNT MISMATCH'
    END as amount_validation,
    CASE 
        WHEN wae_rate < mid_rate AND gain_loss_type = 'D' THEN '✓ LOSS CORRECT'
        WHEN wae_rate > mid_rate AND gain_loss_type = 'C' THEN '✓ GAIN CORRECT'
        WHEN wae_rate = mid_rate AND gain_loss_type IS NULL THEN '✓ NO GAIN/LOSS'
        ELSE '✗ DIRECTION WRONG'
    END as direction_validation
FROM cap_summary;

-- Expected: All validation columns show '✓'


-- ══════════════════════════════════════════════════════════════════
-- BATCH VERIFICATION: Check Last 10 Capitalizations
-- ══════════════════════════════════════════════════════════════════

SELECT 
    SUBSTRING(tt.tran_id, 1, 18) as transaction_id,
    MAX(tt.tran_date) as date,
    MAX(CASE WHEN tt.account_no IS NOT NULL THEN tt.account_no END) as account,
    MAX(CASE WHEN tt.account_no IS NOT NULL THEN tt.tran_ccy END) as ccy,
    ROUND(MAX(CASE WHEN tt.tran_id LIKE '%-1' THEN tt.fcy_amt END), 2) as fcy,
    ROUND(MAX(CASE WHEN tt.tran_id LIKE '%-1' THEN tt.exchange_rate END), 4) as wae,
    ROUND(MAX(CASE WHEN tt.tran_id LIKE '%-2' THEN tt.exchange_rate END), 4) as mid,
    COUNT(DISTINCT tt.tran_id) as legs,
    -- LCY Balance Check
    ROUND(
        SUM(CASE WHEN tt.dr_cr_flag='D' THEN tt.lcy_amt ELSE 0 END) -
        SUM(CASE WHEN tt.dr_cr_flag='C' THEN tt.lcy_amt ELSE 0 END),
        2
    ) as lcy_diff,
    CASE 
        WHEN ABS(
            SUM(CASE WHEN tt.dr_cr_flag='D' THEN tt.lcy_amt ELSE 0 END) -
            SUM(CASE WHEN tt.dr_cr_flag='C' THEN tt.lcy_amt ELSE 0 END)
        ) <= 0.01 THEN '✓'
        ELSE '✗'
    END as balanced
FROM tran_table tt
WHERE tt.tran_id LIKE 'C%'
  AND tt.tran_date >= CURDATE() - INTERVAL 30 DAY
GROUP BY SUBSTRING(tt.tran_id, 1, 18)
ORDER BY MAX(tt.tran_date) DESC
LIMIT 10;

-- Expected: All rows show balanced = '✓', legs = 2 or 3


-- ══════════════════════════════════════════════════════════════════
-- ERROR DETECTION: Find Potential Issues
-- ══════════════════════════════════════════════════════════════════

-- Check 1: Capitalizations with LCY imbalance
SELECT 
    SUBSTRING(tran_id, 1, 18) as transaction_id,
    'LCY IMBALANCE' as issue,
    ROUND(
        SUM(CASE WHEN dr_cr_flag='D' THEN lcy_amt ELSE 0 END) -
        SUM(CASE WHEN dr_cr_flag='C' THEN lcy_amt ELSE 0 END),
        2
    ) as difference
FROM tran_table
WHERE tran_id LIKE 'C%'
  AND tran_date >= CURDATE() - INTERVAL 7 DAY
GROUP BY SUBSTRING(tran_id, 1, 18)
HAVING ABS(
    SUM(CASE WHEN dr_cr_flag='D' THEN lcy_amt ELSE 0 END) -
    SUM(CASE WHEN dr_cr_flag='C' THEN lcy_amt ELSE 0 END)
) > 0.01

UNION ALL

-- Check 2: Missing gain/loss leg when WAE != MID
SELECT 
    SUBSTRING(tt.tran_id, 1, 18) as transaction_id,
    'MISSING GAIN/LOSS LEG' as issue,
    NULL as difference
FROM tran_table tt
WHERE tt.tran_id LIKE 'C%-1'
  AND tt.tran_date >= CURDATE() - INTERVAL 7 DAY
  AND tt.exchange_rate != (
      SELECT exchange_rate 
      FROM tran_table 
      WHERE tran_id = CONCAT(SUBSTRING(tt.tran_id, 1, 18), '-2')
  )
  AND NOT EXISTS (
      SELECT 1 
      FROM tran_table 
      WHERE tran_id = CONCAT(SUBSTRING(tt.tran_id, 1, 18), '-3')
  )

UNION ALL

-- Check 3: Wrong GL number on gain/loss
SELECT 
    tran_id as transaction_id,
    'WRONG GAIN/LOSS GL' as issue,
    NULL as difference
FROM tran_table
WHERE tran_id LIKE 'C%-3'
  AND tran_date >= CURDATE() - INTERVAL 7 DAY
  AND gl_num NOT IN ('140203002', '240203002')

UNION ALL

-- Check 4: Accrual not zeroed after capitalization
SELECT 
    CONCAT('Account ', aba.account_no) as transaction_id,
    'ACCRUAL NOT ZEROED' as issue,
    ROUND(aba.closing_bal, 2) as difference
FROM acct_bal_accrual aba
WHERE aba.closing_bal != 0
  AND EXISTS (
      SELECT 1 
      FROM tran_table tt 
      WHERE tt.account_no = aba.account_no 
        AND tt.tran_id LIKE 'C%' 
        AND tt.tran_date = aba.tran_date
  );

-- Expected: No rows returned (no issues found)


-- ══════════════════════════════════════════════════════════════════
-- END OF VERIFICATION QUERIES
-- ══════════════════════════════════════════════════════════════════
