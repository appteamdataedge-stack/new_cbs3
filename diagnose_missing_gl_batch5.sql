-- ========================================
-- DIAGNOSTIC QUERIES FOR MISSING GL DATA IN BATCH JOB 5
-- Date: 2025-04-05
-- Missing GLs: 130103001, 240103001, 220302001, 110203001
-- ========================================

-- 1. CHECK IF THESE GL NUMBERS EXIST IN GL_SETUP
SELECT 'CHECK 1: GL_SETUP Existence' AS test_name;
SELECT
    GL_Num,
    GL_Name,
    Layer_Id,
    Parent_GL_Num,
    CASE
        WHEN GL_Num IN ('130103001', '240103001', '220302001', '110203001') THEN 'MISSING GL ⚠️'
        ELSE 'OK'
    END AS status
FROM gl_setup
WHERE GL_Num IN ('130103001', '240103001', '220302001', '110203001');

-- Check if any are missing from gl_setup
SELECT 'Missing from gl_setup:' AS info,
       GROUP_CONCAT(missing_gl) AS missing_gls
FROM (
    SELECT '130103001' AS missing_gl WHERE NOT EXISTS (SELECT 1 FROM gl_setup WHERE GL_Num = '130103001')
    UNION ALL
    SELECT '240103001' WHERE NOT EXISTS (SELECT 1 FROM gl_setup WHERE GL_Num = '240103001')
    UNION ALL
    SELECT '220302001' WHERE NOT EXISTS (SELECT 1 FROM gl_setup WHERE GL_Num = '220302001')
    UNION ALL
    SELECT '110203001' WHERE NOT EXISTS (SELECT 1 FROM gl_setup WHERE GL_Num = '110203001')
) missing;

-- 2. CHECK IF THESE GLs HAVE TRANSACTIONS IN GL_MOVEMENT
SELECT 'CHECK 2: GL_MOVEMENT Transactions' AS test_name;
SELECT
    GL_Num,
    COUNT(*) AS transaction_count,
    SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) AS total_dr,
    SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) AS total_cr,
    MIN(Tran_Date) AS first_tran_date,
    MAX(Tran_Date) AS last_tran_date
FROM gl_movement
WHERE GL_Num IN ('130103001', '240103001', '220302001', '110203001')
  AND Tran_Date = '2025-04-05'
GROUP BY GL_Num;

-- 3. CHECK IF THESE GLs HAVE TRANSACTIONS IN GL_MOVEMENT_ACCRUAL
SELECT 'CHECK 3: GL_MOVEMENT_ACCRUAL Transactions' AS test_name;
SELECT
    GL_Num,
    COUNT(*) AS accrual_count,
    SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) AS total_dr,
    SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) AS total_cr,
    MIN(Accrual_Date) AS first_accrual_date,
    MAX(Accrual_Date) AS last_accrual_date
FROM gl_movement_accrual
WHERE GL_Num IN ('130103001', '240103001', '220302001', '110203001')
  AND Accrual_Date = '2025-04-05'
GROUP BY GL_Num;

-- 4. CHECK IF THESE GLs ARE IN GL_BALANCE
SELECT 'CHECK 4: GL_BALANCE Entries' AS test_name;
SELECT
    GL_Num,
    Tran_Date,
    Opening_Bal,
    DR_Summation,
    CR_Summation,
    Closing_Bal,
    Last_Updated
FROM gl_balance
WHERE GL_Num IN ('130103001', '240103001', '220302001', '110203001')
  AND Tran_Date = '2025-04-05';

-- Check which ones are missing from gl_balance
SELECT 'Missing from gl_balance:' AS info,
       GROUP_CONCAT(missing_gl) AS missing_gls
FROM (
    SELECT '130103001' AS missing_gl WHERE NOT EXISTS (SELECT 1 FROM gl_balance WHERE GL_Num = '130103001' AND Tran_Date = '2025-04-05')
    UNION ALL
    SELECT '240103001' WHERE NOT EXISTS (SELECT 1 FROM gl_balance WHERE GL_Num = '240103001' AND Tran_Date = '2025-04-05')
    UNION ALL
    SELECT '220302001' WHERE NOT EXISTS (SELECT 1 FROM gl_balance WHERE GL_Num = '220302001' AND Tran_Date = '2025-04-05')
    UNION ALL
    SELECT '110203001' WHERE NOT EXISTS (SELECT 1 FROM gl_balance WHERE GL_Num = '110203001' AND Tran_Date = '2025-04-05')
) missing;

-- 5. CHECK WHAT GL NUMBERS ARE RETURNED BY findActiveGLNumbersWithAccounts()
SELECT 'CHECK 5: GLs from findActiveGLNumbersWithAccounts()' AS test_name;
SELECT DISTINCT gl.GL_Num, 'From Sub-Product Accounts' AS source
FROM gl_setup gl
WHERE gl.GL_Num IN (
    -- Get GLs from sub-products that have customer accounts
    SELECT DISTINCT sp.Cum_GL_Num
    FROM sub_prod_master sp
    INNER JOIN cust_acct_master ca ON ca.Sub_Product_Id = sp.Sub_Product_Id

    UNION

    -- Get GLs from sub-products that have office accounts
    SELECT DISTINCT sp.Cum_GL_Num
    FROM sub_prod_master sp
    INNER JOIN of_acct_master oa ON oa.Sub_Product_Id = sp.Sub_Product_Id

    UNION

    -- Get interest receivable/expenditure GLs from sub-products with customer accounts
    SELECT DISTINCT sp.interest_receivable_expenditure_gl_num
    FROM sub_prod_master sp
    INNER JOIN cust_acct_master ca ON ca.Sub_Product_Id = sp.Sub_Product_Id
    WHERE sp.interest_receivable_expenditure_gl_num IS NOT NULL

    UNION

    -- Get interest income/payable GLs from sub-products with customer accounts
    SELECT DISTINCT sp.interest_income_payable_gl_num
    FROM sub_prod_master sp
    INNER JOIN cust_acct_master ca ON ca.Sub_Product_Id = sp.Sub_Product_Id
    WHERE sp.interest_income_payable_gl_num IS NOT NULL

    UNION

    -- Get interest receivable/expenditure GLs from sub-products with office accounts
    SELECT DISTINCT sp.interest_receivable_expenditure_gl_num
    FROM sub_prod_master sp
    INNER JOIN of_acct_master oa ON oa.Sub_Product_Id = sp.Sub_Product_Id
    WHERE sp.interest_receivable_expenditure_gl_num IS NOT NULL

    UNION

    -- Get interest income/payable GLs from sub-products with office accounts
    SELECT DISTINCT sp.interest_income_payable_gl_num
    FROM sub_prod_master sp
    INNER JOIN of_acct_master oa ON oa.Sub_Product_Id = sp.Sub_Product_Id
    WHERE sp.interest_income_payable_gl_num IS NOT NULL
)
AND gl.GL_Num IN ('130103001', '240103001', '220302001', '110203001')
ORDER BY gl.GL_Num;

-- 6. CHECK WHAT GL NUMBERS HAVE TRANSACTIONS ON 2025-04-05
SELECT 'CHECK 6: GLs with transactions on 2025-04-05' AS test_name;
SELECT DISTINCT GL_Num, 'From gl_movement' AS source
FROM gl_movement
WHERE Tran_Date = '2025-04-05'
  AND GL_Num IN ('130103001', '240103001', '220302001', '110203001')
UNION
SELECT DISTINCT GL_Num, 'From gl_movement_accrual' AS source
FROM gl_movement_accrual
WHERE Accrual_Date = '2025-04-05'
  AND GL_Num IN ('130103001', '240103001', '220302001', '110203001')
ORDER BY GL_Num, source;

-- 7. CHECK ALL GL NUMBERS THAT SHOULD BE PROCESSED (COMBINED LOGIC)
SELECT 'CHECK 7: All GLs that should be processed' AS test_name;
SELECT DISTINCT GL_Num, source FROM (
    -- From sub-product accounts
    SELECT DISTINCT gl.GL_Num, 'Sub-Product Accounts' AS source
    FROM gl_setup gl
    WHERE gl.GL_Num IN (
        SELECT DISTINCT sp.Cum_GL_Num FROM sub_prod_master sp
        INNER JOIN cust_acct_master ca ON ca.Sub_Product_Id = sp.Sub_Product_Id
        UNION
        SELECT DISTINCT sp.Cum_GL_Num FROM sub_prod_master sp
        INNER JOIN of_acct_master oa ON oa.Sub_Product_Id = sp.Sub_Product_Id
    )

    UNION ALL

    -- From gl_movement transactions
    SELECT DISTINCT GL_Num, 'gl_movement' AS source
    FROM gl_movement
    WHERE Tran_Date = '2025-04-05'

    UNION ALL

    -- From gl_movement_accrual transactions
    SELECT DISTINCT GL_Num, 'gl_movement_accrual' AS source
    FROM gl_movement_accrual
    WHERE Accrual_Date = '2025-04-05'
) all_gls
WHERE GL_Num IN ('130103001', '240103001', '220302001', '110203001')
ORDER BY GL_Num, source;

-- 8. COMPREHENSIVE SUMMARY FOR MISSING GLs
SELECT 'CHECK 8: Comprehensive Summary' AS test_name;
SELECT
    g.GL_Num,
    CASE WHEN gs.GL_Num IS NOT NULL THEN 'YES ✓' ELSE 'NO ✗' END AS in_gl_setup,
    COALESCE(gm.tran_count, 0) AS gl_movement_transactions,
    COALESCE(gma.accr_count, 0) AS gl_movement_accrual_transactions,
    CASE WHEN gb.GL_Num IS NOT NULL THEN 'YES ✓' ELSE 'NO ✗ MISSING!' END AS in_gl_balance,
    CASE
        WHEN gs.GL_Num IS NULL THEN '❌ GL not in gl_setup table'
        WHEN gm.tran_count IS NULL AND gma.accr_count IS NULL THEN '❌ No transactions found'
        WHEN gb.GL_Num IS NULL THEN '❌ Not processed by Batch Job 5'
        ELSE '✓ OK'
    END AS diagnosis
FROM (
    SELECT '130103001' AS GL_Num
    UNION SELECT '240103001'
    UNION SELECT '220302001'
    UNION SELECT '110203001'
) g
LEFT JOIN gl_setup gs ON gs.GL_Num = g.GL_Num
LEFT JOIN (
    SELECT GL_Num, COUNT(*) AS tran_count
    FROM gl_movement
    WHERE Tran_Date = '2025-04-05'
    GROUP BY GL_Num
) gm ON gm.GL_Num = g.GL_Num
LEFT JOIN (
    SELECT GL_Num, COUNT(*) AS accr_count
    FROM gl_movement_accrual
    WHERE Accrual_Date = '2025-04-05'
    GROUP BY GL_Num
) gma ON gma.GL_Num = g.GL_Num
LEFT JOIN gl_balance gb ON gb.GL_Num = g.GL_Num AND gb.Tran_Date = '2025-04-05'
ORDER BY g.GL_Num;

-- 9. CHECK EOD_LOG_TABLE FOR BATCH JOB 5 ERRORS
SELECT 'CHECK 9: Batch Job 5 Execution Log' AS test_name;
SELECT
    EOD_Log_Id,
    Job_Name,
    Status,
    Records_Processed,
    Error_Message,
    Failed_At_Step,
    Start_Timestamp,
    End_Timestamp
FROM EOD_Log_Table
WHERE Job_Name = 'GL Balance Update'
  AND EOD_Date = '2025-04-05'
ORDER BY Start_Timestamp DESC
LIMIT 5;
