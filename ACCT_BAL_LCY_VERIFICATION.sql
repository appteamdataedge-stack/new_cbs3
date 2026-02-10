-- ACCT_BAL_LCY VERIFICATION SCRIPT
-- This script verifies that the Acct_Bal_LCY table is properly populated and working correctly

-- ===================================================
-- STEP 1: Verify Table Structure
-- ===================================================
SELECT 'Step 1: Verifying Table Structure...' AS Status;

DESCRIBE Acct_Bal_LCY;

-- ===================================================
-- STEP 2: Verify Data Population
-- ===================================================
SELECT 'Step 2: Checking if data is populated...' AS Status;

SELECT 
    COUNT(*) AS Total_Records,
    MIN(Tran_Date) AS Earliest_Date,
    MAX(Tran_Date) AS Latest_Date,
    COUNT(DISTINCT Account_No) AS Unique_Accounts
FROM Acct_Bal_LCY;

-- ===================================================
-- STEP 3: Compare Record Counts (acct_bal vs acct_bal_lcy)
-- ===================================================
SELECT 'Step 3: Comparing record counts between tables...' AS Status;

SELECT 
    'Acct_Bal' AS Table_Name,
    COUNT(*) AS Record_Count
FROM Acct_Bal
UNION ALL
SELECT 
    'Acct_Bal_LCY' AS Table_Name,
    COUNT(*) AS Record_Count
FROM Acct_Bal_LCY;

-- ===================================================
-- STEP 4: Verify BDT Accounts (amounts should match exactly)
-- ===================================================
SELECT 'Step 4: Verifying BDT accounts (amounts should match)...' AS Status;

SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy,
    ab.Opening_Bal AS Opening_Bal_Original,
    abl.Opening_Bal_lcy AS Opening_Bal_LCY,
    ab.Closing_Bal AS Closing_Bal_Original,
    abl.Closing_Bal_lcy AS Closing_Bal_LCY,
    CASE 
        WHEN ABS(ab.Opening_Bal - abl.Opening_Bal_lcy) < 0.01 
         AND ABS(ab.Closing_Bal - abl.Closing_Bal_lcy) < 0.01 
        THEN 'MATCH ✓'
        ELSE 'MISMATCH ✗'
    END AS Verification_Status
FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_Ccy = 'BDT'
ORDER BY ab.Tran_Date DESC, ab.Account_No
LIMIT 20;

-- ===================================================
-- STEP 5: Verify USD/FCY Accounts (conversion applied)
-- ===================================================
SELECT 'Step 5: Verifying USD/FCY accounts (conversion rates)...' AS Status;

SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy,
    ab.Closing_Bal AS FCY_Amount,
    abl.Closing_Bal_lcy AS BDT_Amount,
    CASE 
        WHEN ab.Closing_Bal = 0 THEN NULL
        ELSE ROUND(abl.Closing_Bal_lcy / ab.Closing_Bal, 4)
    END AS Effective_Exchange_Rate
FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_Ccy != 'BDT'
    AND ab.Closing_Bal != 0
ORDER BY ab.Tran_Date DESC, ab.Account_No
LIMIT 20;

-- ===================================================
-- STEP 6: Verify Exchange Rates Match Transaction Data
-- ===================================================
SELECT 'Step 6: Comparing exchange rates with transaction data...' AS Status;

SELECT 
    t.Tran_Date,
    t.Account_No,
    t.Tran_Ccy,
    t.FCY_Amt,
    t.LCY_Amt,
    t.Exchange_Rate AS Tran_Exchange_Rate,
    ab.Closing_Bal AS Account_FCY_Balance,
    abl.Closing_Bal_lcy AS Account_BDT_Balance
FROM Tran_Table t
JOIN Acct_Bal ab 
    ON t.Account_No = ab.Account_No 
    AND t.Tran_Date = ab.Tran_Date
JOIN Acct_Bal_LCY abl 
    ON t.Account_No = abl.Account_No 
    AND t.Tran_Date = abl.Tran_Date
WHERE t.Tran_Ccy != 'BDT'
    AND t.Tran_Status = 'Posted'
ORDER BY t.Tran_Date DESC, t.Tran_Id
LIMIT 20;

-- ===================================================
-- STEP 7: Check for Missing Records
-- ===================================================
SELECT 'Step 7: Checking for missing records in acct_bal_lcy...' AS Status;

SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy,
    ab.Closing_Bal,
    'Missing in Acct_Bal_LCY' AS Issue
FROM Acct_Bal ab
LEFT JOIN Acct_Bal_LCY abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE abl.Account_No IS NULL
ORDER BY ab.Tran_Date DESC
LIMIT 20;

-- ===================================================
-- STEP 8: Aggregate Totals Verification
-- ===================================================
SELECT 'Step 8: Verifying aggregate totals by date...' AS Status;

SELECT 
    Tran_Date,
    COUNT(*) AS Total_Accounts,
    SUM(Opening_Bal_lcy) AS Total_Opening_BDT,
    SUM(DR_Summation_lcy) AS Total_DR_BDT,
    SUM(CR_Summation_lcy) AS Total_CR_BDT,
    SUM(Closing_Bal_lcy) AS Total_Closing_BDT
FROM Acct_Bal_LCY
GROUP BY Tran_Date
ORDER BY Tran_Date DESC
LIMIT 10;

-- ===================================================
-- STEP 9: Sample Data Comparison (Side-by-Side)
-- ===================================================
SELECT 'Step 9: Sample data comparison (Original vs LCY)...' AS Status;

SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy,
    ab.Opening_Bal AS Opening_Original,
    abl.Opening_Bal_lcy AS Opening_LCY,
    ab.DR_Summation AS DR_Original,
    abl.DR_Summation_lcy AS DR_LCY,
    ab.CR_Summation AS CR_Original,
    abl.CR_Summation_lcy AS CR_LCY,
    ab.Closing_Bal AS Closing_Original,
    abl.Closing_Bal_lcy AS Closing_LCY
FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
ORDER BY ab.Tran_Date DESC, ab.Account_No
LIMIT 10;

-- ===================================================
-- STEP 10: Data Quality Check
-- ===================================================
SELECT 'Step 10: Data quality checks...' AS Status;

SELECT 
    'Negative Balances' AS Check_Type,
    COUNT(*) AS Count
FROM Acct_Bal_LCY
WHERE Closing_Bal_lcy < 0

UNION ALL

SELECT 
    'NULL Balances' AS Check_Type,
    COUNT(*) AS Count
FROM Acct_Bal_LCY
WHERE Closing_Bal_lcy IS NULL

UNION ALL

SELECT 
    'Zero Balances' AS Check_Type,
    COUNT(*) AS Count
FROM Acct_Bal_LCY
WHERE Closing_Bal_lcy = 0

UNION ALL

SELECT 
    'Positive Balances' AS Check_Type,
    COUNT(*) AS Count
FROM Acct_Bal_LCY
WHERE Closing_Bal_lcy > 0;

-- ===================================================
-- SUMMARY
-- ===================================================
SELECT 
    '========================================' AS Summary,
    'VERIFICATION COMPLETE' AS Status,
    '========================================' AS End_Line;

SELECT 
    'If all checks passed:' AS Note,
    '1. BDT accounts have matching amounts' AS Check_1,
    '2. FCY accounts show converted amounts' AS Check_2,
    '3. No missing records' AS Check_3,
    '4. Exchange rates match transaction data' AS Check_4;
