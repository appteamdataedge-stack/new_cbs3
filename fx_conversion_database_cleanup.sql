-- ═══════════════════════════════════════════════════════════
-- FX CONVERSION - DATABASE CLEANUP AND VERIFICATION SCRIPT
-- ═══════════════════════════════════════════════════════════
-- Date: March 29, 2026
-- Purpose: Remove wrong tables and verify correct structure exists
-- ═══════════════════════════════════════════════════════════

-- STEP 1: DROP INCORRECT TABLES
-- ═══════════════════════════════════════════════════════════

-- These tables were created incorrectly and should not exist
-- The system uses fx_rate_master instead

DROP TABLE IF EXISTS fx_transaction_entries;
DROP TABLE IF EXISTS fx_transactions;
DROP TABLE IF EXISTS fx_rates;
DROP TABLE IF EXISTS fx_position;

-- ✓ CORRECT TABLES TO KEEP:
--   - fx_rate_master (for exchange rates)
--   - cust_acct_master (customer accounts)
--   - of_acct_master (office/NOSTRO accounts)
--   - acc_bal (account FCY balances)
--   - acct_bal_lcy (account LCY balances)


-- STEP 2: VERIFY fx_rate_master STRUCTURE
-- ═══════════════════════════════════════════════════════════

-- Check if fx_rate_master table exists
SELECT 
    'fx_rate_master structure check' AS Step,
    CASE 
        WHEN COUNT(*) > 0 THEN '✓ Table exists'
        ELSE '✗ Table MISSING - needs to be created'
    END AS Status
FROM information_schema.tables 
WHERE table_schema = DATABASE() 
AND table_name = 'fx_rate_master';

-- View structure (run separately if needed)
-- DESCRIBE fx_rate_master;

-- Expected columns:
-- Rate_Id (PK)
-- Rate_Date (datetime/timestamp)
-- Ccy_Pair (varchar - format: "USD/BDT", "EUR/BDT")
-- Mid_Rate (decimal)
-- Buying_Rate (decimal)
-- Selling_Rate (decimal)
-- Source (varchar)
-- Uploaded_By (varchar)
-- Created_At (datetime)
-- Last_Updated (datetime)


-- STEP 3: CHECK fx_rate_master HAS DATA
-- ═══════════════════════════════════════════════════════════

SELECT 
    'fx_rate_master data check' AS Step,
    COUNT(*) AS total_rates,
    CASE 
        WHEN COUNT(*) > 0 THEN '✓ Has data'
        ELSE '✗ EMPTY - needs test data'
    END AS Status
FROM fx_rate_master;

-- View current rates
SELECT 
    Rate_Id,
    Ccy_Pair,
    Mid_Rate,
    Buying_Rate,
    Selling_Rate,
    Rate_Date,
    Source
FROM fx_rate_master 
ORDER BY Rate_Date DESC, Ccy_Pair
LIMIT 10;


-- STEP 4: INSERT TEST DATA IF MISSING
-- ═══════════════════════════════════════════════════════════

-- Insert test exchange rates for USD, EUR, GBP, JPY
-- Use ON DUPLICATE KEY UPDATE in case data already exists

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
    -- USD rates
    (NOW(), 'USD/BDT', 110.2500, 109.5000, 111.0000, 'MANUAL', 'SYSTEM', NOW(), NOW()),
    -- EUR rates
    (NOW(), 'EUR/BDT', 120.5000, 119.7500, 121.2500, 'MANUAL', 'SYSTEM', NOW(), NOW()),
    -- GBP rates
    (NOW(), 'GBP/BDT', 138.7500, 137.9000, 139.6000, 'MANUAL', 'SYSTEM', NOW(), NOW()),
    -- JPY rates (per 100 JPY)
    (NOW(), 'JPY/BDT', 0.8200, 0.8100, 0.8300, 'MANUAL', 'SYSTEM', NOW(), NOW())
ON DUPLICATE KEY UPDATE 
    Mid_Rate = VALUES(Mid_Rate),
    Buying_Rate = VALUES(Buying_Rate),
    Selling_Rate = VALUES(Selling_Rate),
    Last_Updated = NOW();


-- STEP 5: VERIFY NOSTRO ACCOUNTS EXIST
-- ═══════════════════════════════════════════════════════════

SELECT 
    'NOSTRO accounts check' AS Step,
    COUNT(*) AS total_nostro_accounts,
    CASE 
        WHEN COUNT(*) > 0 THEN '✓ NOSTRO accounts exist'
        ELSE '✗ NO NOSTRO accounts - check GL pattern'
    END AS Status
FROM of_acct_master 
WHERE GL_Num LIKE '22030%' 
AND Account_Status = 'Active';

-- View NOSTRO accounts by currency
SELECT 
    Account_No,
    Acct_Name,
    Account_Ccy,
    GL_Num,
    Account_Status
FROM of_acct_master
WHERE GL_Num LIKE '22030%'
ORDER BY Account_Ccy, Account_No;


-- STEP 6: CHECK NOSTRO BALANCES
-- ═══════════════════════════════════════════════════════════

-- Check if NOSTRO accounts have FCY balances in acc_bal
SELECT 
    o.Account_No,
    o.Acct_Name,
    o.Account_Ccy,
    ab.Tran_Date,
    ab.Closing_Bal AS FCY_Balance,
    abl.Closing_Bal_lcy AS LCY_Balance,
    CASE 
        WHEN ab.Closing_Bal IS NOT NULL AND ab.Closing_Bal > 0 THEN
            ROUND(abl.Closing_Bal_lcy / ab.Closing_Bal, 4)
        ELSE 0
    END AS Calculated_WAE
FROM of_acct_master o
LEFT JOIN acc_bal ab ON o.Account_No = ab.Account_No
LEFT JOIN acct_bal_lcy abl ON o.Account_No = abl.Account_No AND ab.Tran_Date = abl.Tran_Date
WHERE o.GL_Num LIKE '22030%' 
AND o.Account_Status = 'Active'
ORDER BY o.Account_Ccy, o.Account_No;


-- STEP 7: INSERT TEST BALANCES FOR NOSTRO ACCOUNTS (IF MISSING)
-- ═══════════════════════════════════════════════════════════

-- Get system date first
SET @system_date = (SELECT System_Date FROM system_date LIMIT 1);

-- Insert FCY balances for USD NOSTRO accounts
INSERT INTO acc_bal (
    Tran_Date, 
    Account_No, 
    Account_Ccy,
    Opening_Bal, 
    DR_Summation,
    CR_Summation,
    Closing_Bal,
    Current_Balance,
    Available_Balance,
    Last_Updated
)
SELECT 
    @system_date,
    Account_No,
    Account_Ccy,
    10000.00 AS Opening_Bal,
    0.00 AS DR_Summation,
    0.00 AS CR_Summation,
    10000.00 AS Closing_Bal,
    10000.00 AS Current_Balance,
    10000.00 AS Available_Balance,
    NOW() AS Last_Updated
FROM of_acct_master
WHERE GL_Num LIKE '22030%'
AND Account_Ccy = 'USD'
AND Account_Status = 'Active'
AND Account_No NOT IN (SELECT Account_No FROM acc_bal WHERE Tran_Date = @system_date);

-- Insert LCY balances for USD NOSTRO accounts
-- LCY = FCY * Mid Rate (10000 * 110.25 = 1,102,500)
INSERT INTO acct_bal_lcy (
    Tran_Date,
    Account_No,
    Opening_Bal_lcy,
    DR_Summation_lcy,
    CR_Summation_lcy,
    Closing_Bal_lcy,
    Available_Balance_lcy,
    Last_Updated
)
SELECT 
    @system_date,
    Account_No,
    1102500.00 AS Opening_Bal_lcy,
    0.00 AS DR_Summation_lcy,
    0.00 AS CR_Summation_lcy,
    1102500.00 AS Closing_Bal_lcy,
    1102500.00 AS Available_Balance_lcy,
    NOW() AS Last_Updated
FROM of_acct_master
WHERE GL_Num LIKE '22030%'
AND Account_Ccy = 'USD'
AND Account_Status = 'Active'
AND Account_No NOT IN (SELECT Account_No FROM acct_bal_lcy WHERE Tran_Date = @system_date);


-- STEP 8: VERIFY CUSTOMER ACCOUNTS EXIST
-- ═══════════════════════════════════════════════════════════

SELECT 
    'Customer accounts check' AS Step,
    COUNT(*) AS total_customer_accounts,
    CASE 
        WHEN COUNT(*) > 0 THEN '✓ Customer accounts exist'
        ELSE '✗ NO customer accounts found'
    END AS Status
FROM cust_acct_master
WHERE Account_Ccy = 'BDT'
AND Account_Status = 'Active';

-- View customer accounts by sub-product
SELECT 
    sp.Sub_Product_Code,
    sp.Sub_Product_Name,
    COUNT(*) AS Account_Count
FROM cust_acct_master cam
JOIN sub_prod_master sp ON cam.Sub_Product_Id = sp.Sub_Product_Id
WHERE cam.Account_Ccy = 'BDT'
AND cam.Account_Status = 'Active'
AND (sp.Sub_Product_Code LIKE 'CA%' OR sp.Sub_Product_Code LIKE 'SB%')
GROUP BY sp.Sub_Product_Code, sp.Sub_Product_Name
ORDER BY sp.Sub_Product_Code;


-- STEP 9: TEST WAE CALCULATION QUERY
-- ═══════════════════════════════════════════════════════════

-- This simulates what the backend calculateWAE() method does

SELECT 
    'USD' AS Currency,
    SUM(ab.Closing_Bal) AS Total_FCY_Balance,
    SUM(abl.Closing_Bal_lcy) AS Total_LCY_Balance,
    CASE 
        WHEN SUM(ab.Closing_Bal) > 0 THEN
            ROUND(SUM(abl.Closing_Bal_lcy) / SUM(ab.Closing_Bal), 6)
        ELSE 0
    END AS Calculated_WAE_Rate
FROM of_acct_master o
JOIN acc_bal ab ON o.Account_No = ab.Account_No
JOIN acct_bal_lcy abl ON o.Account_No = abl.Account_No AND ab.Tran_Date = abl.Tran_Date
WHERE o.GL_Num LIKE '22030%'
AND o.Account_Ccy = 'USD'
AND o.Account_Status = 'Active';


-- STEP 10: VERIFICATION SUMMARY
-- ═══════════════════════════════════════════════════════════

SELECT '═══════════════════════════════════════' AS Separator;
SELECT 'DATABASE VERIFICATION SUMMARY' AS Title;
SELECT '═══════════════════════════════════════' AS Separator;

-- Check 1: Wrong tables dropped
SELECT 
    'Wrong tables cleanup' AS Check_Name,
    CASE 
        WHEN COUNT(*) = 0 THEN '✓ PASS - No wrong tables exist'
        ELSE CONCAT('✗ FAIL - ', COUNT(*), ' wrong tables still exist')
    END AS Result
FROM information_schema.tables 
WHERE table_schema = DATABASE() 
AND table_name IN ('fx_rates', 'fx_position', 'fx_transactions', 'fx_transaction_entries');

-- Check 2: fx_rate_master exists and has data
SELECT 
    'fx_rate_master table' AS Check_Name,
    CASE 
        WHEN (SELECT COUNT(*) FROM fx_rate_master) > 0 THEN 
            CONCAT('✓ PASS - ', (SELECT COUNT(*) FROM fx_rate_master), ' rates found')
        ELSE '✗ FAIL - No exchange rates in fx_rate_master'
    END AS Result;

-- Check 3: NOSTRO accounts exist
SELECT 
    'NOSTRO accounts' AS Check_Name,
    CASE 
        WHEN (SELECT COUNT(*) FROM of_acct_master WHERE GL_Num LIKE '22030%' AND Account_Status = 'Active') > 0 THEN 
            CONCAT('✓ PASS - ', (SELECT COUNT(*) FROM of_acct_master WHERE GL_Num LIKE '22030%' AND Account_Status = 'Active'), ' NOSTRO accounts found')
        ELSE '✗ FAIL - No active NOSTRO accounts found'
    END AS Result;

-- Check 4: NOSTRO accounts have balances
SELECT 
    'NOSTRO balances' AS Check_Name,
    CASE 
        WHEN EXISTS (
            SELECT 1 FROM of_acct_master o
            JOIN acc_bal ab ON o.Account_No = ab.Account_No
            JOIN acct_bal_lcy abl ON o.Account_No = abl.Account_No
            WHERE o.GL_Num LIKE '22030%' AND o.Account_Status = 'Active'
            AND ab.Closing_Bal > 0 AND abl.Closing_Bal_lcy > 0
        ) THEN '✓ PASS - NOSTRO accounts have balances'
        ELSE '✗ FAIL - NOSTRO accounts missing balances'
    END AS Result;

-- Check 5: Customer accounts exist
SELECT 
    'Customer accounts' AS Check_Name,
    CASE 
        WHEN (SELECT COUNT(*) FROM cust_acct_master WHERE Account_Ccy = 'BDT' AND Account_Status = 'Active') > 0 THEN 
            CONCAT('✓ PASS - ', (SELECT COUNT(*) FROM cust_acct_master WHERE Account_Ccy = 'BDT' AND Account_Status = 'Active'), ' customer accounts found')
        ELSE '✗ FAIL - No active BDT customer accounts'
    END AS Result;

SELECT '═══════════════════════════════════════' AS Separator;
SELECT 'Run backend to test endpoints' AS Next_Step;
SELECT '═══════════════════════════════════════' AS Separator;

-- ═══════════════════════════════════════════════════════════
-- END OF SCRIPT
-- ═══════════════════════════════════════════════════════════
