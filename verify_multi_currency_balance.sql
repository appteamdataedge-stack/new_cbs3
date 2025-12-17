-- =====================================================
-- Multi-Currency Balance Tables Verification Script
-- Date: 2025-11-23
-- Verifies V20 and V21 migrations
-- =====================================================

USE moneymarketdb;

-- =====================================================
-- STEP 1: Verify schema changes in acct_bal table
-- =====================================================
SELECT 'Schema Check: acct_bal table structure' AS verification_step;
DESCRIBE acct_bal;

-- Check if Tran_Ccy column exists and has proper constraints
SELECT
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE,
    COLUMN_DEFAULT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'moneymarketdb'
    AND TABLE_NAME = 'acct_bal'
    AND COLUMN_NAME = 'Tran_Ccy';

-- =====================================================
-- STEP 2: Verify schema changes in acct_bal_accrual table
-- =====================================================
SELECT 'Schema Check: acct_bal_accrual table structure' AS verification_step;
DESCRIBE acct_bal_accrual;

-- Check if Tran_Ccy column exists and has proper constraints
SELECT
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE,
    COLUMN_DEFAULT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'moneymarketdb'
    AND TABLE_NAME = 'acct_bal_accrual'
    AND COLUMN_NAME = 'Tran_Ccy';

-- =====================================================
-- STEP 3: Verify indexes were created
-- =====================================================
SELECT 'Index Check: Verify Tran_Ccy indexes' AS verification_step;
SELECT
    TABLE_NAME,
    INDEX_NAME,
    COLUMN_NAME
FROM INFORMATION_SCHEMA.STATISTICS
WHERE TABLE_SCHEMA = 'moneymarketdb'
    AND INDEX_NAME IN ('idx_acct_bal_tran_ccy', 'idx_acct_bal_accrual_tran_ccy')
ORDER BY TABLE_NAME, INDEX_NAME;

-- =====================================================
-- STEP 4: Verify acct_bal data population
-- =====================================================
SELECT 'Data Check: acct_bal currency distribution' AS verification_step;
SELECT
    Tran_Ccy,
    COUNT(*) AS record_count,
    COUNT(DISTINCT Account_No) AS unique_accounts
FROM acct_bal
GROUP BY Tran_Ccy
ORDER BY Tran_Ccy;

-- Sample records from acct_bal
SELECT 'Sample: acct_bal records' AS verification_step;
SELECT
    Tran_Date,
    Account_No,
    Tran_Ccy,
    Opening_Bal,
    DR_Summation,
    CR_Summation,
    Closing_Bal
FROM acct_bal
ORDER BY Tran_Date DESC
LIMIT 10;

-- =====================================================
-- STEP 5: Verify acct_bal_accrual data population
-- =====================================================
SELECT 'Data Check: acct_bal_accrual currency distribution' AS verification_step;
SELECT
    Tran_Ccy,
    COUNT(*) AS record_count,
    COUNT(DISTINCT Account_No) AS unique_accounts
FROM acct_bal_accrual
GROUP BY Tran_Ccy
ORDER BY Tran_Ccy;

-- Sample records from acct_bal_accrual
SELECT 'Sample: acct_bal_accrual records' AS verification_step;
SELECT
    Accr_Bal_Id,
    Account_No,
    Tran_Ccy,
    Accrual_Date,
    Interest_Amount,
    Opening_Bal,
    Closing_Bal
FROM acct_bal_accrual
ORDER BY Accrual_Date DESC
LIMIT 10;

-- =====================================================
-- STEP 6: Verify multi-currency accrual calculations (intt_accr_tran)
-- =====================================================
SELECT 'Data Check: intt_accr_tran currency distribution' AS verification_step;
SELECT
    Tran_Ccy,
    COUNT(*) AS record_count,
    COUNT(DISTINCT Account_No) AS unique_accounts
FROM intt_accr_tran
GROUP BY Tran_Ccy
ORDER BY Tran_Ccy;

-- Check BDT transactions have correct values
SELECT 'Validation: BDT transactions in intt_accr_tran' AS verification_step;
SELECT
    Accr_Tran_Id,
    Account_No,
    Tran_Ccy,
    Amount,
    FCY_Amt,
    Exchange_Rate,
    LCY_Amt,
    CASE
        WHEN FCY_Amt = Amount THEN 'PASS'
        ELSE 'FAIL - FCY_Amt should equal Amount'
    END AS fcy_check,
    CASE
        WHEN Exchange_Rate = 1.0000 THEN 'PASS'
        ELSE 'FAIL - Exchange_Rate should be 1.0000'
    END AS rate_check,
    CASE
        WHEN LCY_Amt = Amount THEN 'PASS'
        ELSE 'FAIL - LCY_Amt should equal Amount'
    END AS lcy_check
FROM intt_accr_tran
WHERE Tran_Ccy = 'BDT'
ORDER BY Tran_Date DESC
LIMIT 10;

-- Check USD transactions have correct calculations
SELECT 'Validation: USD transactions in intt_accr_tran' AS verification_step;
SELECT
    Accr_Tran_Id,
    Account_No,
    Tran_Ccy,
    Amount,
    FCY_Amt,
    Exchange_Rate,
    LCY_Amt,
    ROUND(FCY_Amt * Exchange_Rate, 2) AS calculated_lcy,
    CASE
        WHEN FCY_Amt = Amount THEN 'PASS'
        ELSE 'FAIL - FCY_Amt should equal Amount'
    END AS fcy_check,
    CASE
        WHEN Exchange_Rate > 1 THEN 'PASS'
        ELSE 'FAIL - Exchange_Rate should be > 1'
    END AS rate_check,
    CASE
        WHEN ABS(LCY_Amt - ROUND(FCY_Amt * Exchange_Rate, 2)) < 0.01 THEN 'PASS'
        ELSE 'FAIL - LCY_Amt calculation mismatch'
    END AS lcy_check
FROM intt_accr_tran
WHERE Tran_Ccy = 'USD'
ORDER BY Tran_Date DESC
LIMIT 10;

-- =====================================================
-- STEP 7: Cross-validation between tables
-- =====================================================
SELECT 'Cross-validation: acct_bal vs tran_table currencies' AS verification_step;
SELECT
    ab.Account_No,
    ab.Tran_Ccy AS acct_bal_currency,
    tt.Tran_Ccy AS tran_table_currency,
    CASE
        WHEN ab.Tran_Ccy = tt.Tran_Ccy THEN 'MATCH'
        WHEN ab.Tran_Ccy = 'BDT' AND tt.Tran_Ccy IS NULL THEN 'DEFAULT OK'
        ELSE 'MISMATCH'
    END AS validation_status
FROM acct_bal ab
LEFT JOIN (
    SELECT
        Account_No,
        Tran_Ccy,
        ROW_NUMBER() OVER (PARTITION BY Account_No ORDER BY Tran_Date DESC, Tran_Id DESC) as rn
    FROM tran_table
) tt ON ab.Account_No = tt.Account_No AND tt.rn = 1
WHERE ab.Tran_Date = (SELECT MAX(Tran_Date) FROM acct_bal WHERE Account_No = ab.Account_No)
ORDER BY validation_status DESC, ab.Account_No
LIMIT 20;

SELECT 'Cross-validation: acct_bal_accrual vs intt_accr_tran currencies' AS verification_step;
SELECT
    aba.Account_No,
    aba.Tran_Ccy AS acct_bal_accrual_currency,
    iat.Tran_Ccy AS intt_accr_tran_currency,
    CASE
        WHEN aba.Tran_Ccy = iat.Tran_Ccy THEN 'MATCH'
        WHEN aba.Tran_Ccy = 'BDT' AND iat.Tran_Ccy IS NULL THEN 'DEFAULT OK'
        ELSE 'MISMATCH'
    END AS validation_status
FROM acct_bal_accrual aba
LEFT JOIN (
    SELECT
        Account_No,
        Tran_Ccy,
        ROW_NUMBER() OVER (PARTITION BY Account_No ORDER BY Tran_Date DESC, Accr_Tran_Id DESC) as rn
    FROM intt_accr_tran
) iat ON aba.Account_No = iat.Account_No AND iat.rn = 1
ORDER BY validation_status DESC, aba.Account_No
LIMIT 20;

-- =====================================================
-- STEP 8: Summary statistics
-- =====================================================
SELECT 'Summary: Overall statistics' AS verification_step;
SELECT
    'acct_bal' AS table_name,
    COUNT(*) AS total_records,
    COUNT(DISTINCT Account_No) AS unique_accounts,
    SUM(CASE WHEN Tran_Ccy = 'BDT' THEN 1 ELSE 0 END) AS bdt_records,
    SUM(CASE WHEN Tran_Ccy = 'USD' THEN 1 ELSE 0 END) AS usd_records,
    SUM(CASE WHEN Tran_Ccy NOT IN ('BDT', 'USD') THEN 1 ELSE 0 END) AS other_currency_records
FROM acct_bal
UNION ALL
SELECT
    'acct_bal_accrual',
    COUNT(*),
    COUNT(DISTINCT Account_No),
    SUM(CASE WHEN Tran_Ccy = 'BDT' THEN 1 ELSE 0 END),
    SUM(CASE WHEN Tran_Ccy = 'USD' THEN 1 ELSE 0 END),
    SUM(CASE WHEN Tran_Ccy NOT IN ('BDT', 'USD') THEN 1 ELSE 0 END)
FROM acct_bal_accrual
UNION ALL
SELECT
    'intt_accr_tran',
    COUNT(*),
    COUNT(DISTINCT Account_No),
    SUM(CASE WHEN Tran_Ccy = 'BDT' THEN 1 ELSE 0 END),
    SUM(CASE WHEN Tran_Ccy = 'USD' THEN 1 ELSE 0 END),
    SUM(CASE WHEN Tran_Ccy NOT IN ('BDT', 'USD') THEN 1 ELSE 0 END)
FROM intt_accr_tran;

-- =====================================================
-- END OF VERIFICATION SCRIPT
-- =====================================================
