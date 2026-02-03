-- =====================================================
-- FIX SCRIPT: EOD Currency Corruption Issue
-- Purpose: Fix Acct_Bal records where Account_Ccy was incorrectly set to BDT
--          This corrects the bug where office accounts (NOSTRO) got BDT instead of USD
--
-- Background:
-- - Transaction T20250614000001304 posted correctly with USD
-- - EOD batch job incorrectly overwrote Nostro Account_Ccy from USD to BDT
-- - Root cause: EOD only checked customer accounts, not office accounts
-- 
-- BACKUP YOUR DATABASE BEFORE RUNNING THIS SCRIPT!
-- =====================================================

-- =====================================================
-- STEP 1: Diagnose the Issue
-- =====================================================
SELECT '=== ACCOUNTS WITH CURRENCY MISMATCH IN ACCT_BAL ===' AS Diagnosis;

-- Check customer accounts with currency mismatch
SELECT 
    'CUSTOMER ACCOUNT' AS Account_Type,
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy AS Current_AcctBal_Currency,
    cam.Account_Ccy AS Correct_Account_Currency,
    cam.Acct_Name,
    spm.Sub_Product_Code,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN ab.Account_Ccy != cam.Account_Ccy THEN '❌ MISMATCH - NEEDS FIX'
        ELSE '✅ CORRECT'
    END AS Status
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE ab.Account_Ccy != cam.Account_Ccy
ORDER BY ab.Account_No, ab.Tran_Date DESC;

-- Check office accounts with currency mismatch
SELECT 
    'OFFICE ACCOUNT' AS Account_Type,
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy AS Current_AcctBal_Currency,
    oam.Account_Ccy AS Correct_Account_Currency,
    oam.Acct_Name,
    spm.Sub_Product_Code,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN ab.Account_Ccy != oam.Account_Ccy THEN '❌ MISMATCH - NEEDS FIX'
        ELSE '✅ CORRECT'
    END AS Status
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE ab.Account_Ccy != oam.Account_Ccy
ORDER BY ab.Account_No, ab.Tran_Date DESC;

-- =====================================================
-- STEP 2: Specific Check for NOSTRO Account (922030200101)
-- =====================================================
SELECT '=== NOSTRO ACCOUNT DIAGNOSIS ===' AS Diagnosis;

SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy AS AcctBal_Currency,
    ab.Current_Balance,
    oam.Account_Ccy AS Master_Currency,
    oam.Acct_Name,
    spm.Sub_Product_Code,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN ab.Account_Ccy = 'BDT' AND oam.Account_Ccy = 'USD' THEN '❌ BUG: EOD overwrote USD with BDT'
        WHEN ab.Account_Ccy = oam.Account_Ccy THEN '✅ CORRECT'
        ELSE '❌ MISMATCH'
    END AS Status
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE ab.Account_No = '922030200101'
ORDER BY ab.Tran_Date DESC;

-- Check transactions for this account to see correct currency
SELECT 
    '=== NOSTRO TRANSACTIONS (should be USD) ===' AS Diagnosis,
    Tran_Id,
    Tran_Date,
    Account_No,
    Tran_Ccy AS Transaction_Currency,
    DR_CR_Flag,
    FCY_Amt AS USD_Amount,
    LCY_Amt AS BDT_Equivalent,
    Tran_Status
FROM Tran_Table
WHERE Account_No = '922030200101'
ORDER BY Tran_Date DESC, Tran_Id DESC;

-- =====================================================
-- STEP 3: Count of Affected Records
-- =====================================================
SELECT '=== SUMMARY OF AFFECTED RECORDS ===' AS Summary;

SELECT 
    'Customer Accounts with Currency Mismatch' AS Category,
    COUNT(*) AS Record_Count
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
WHERE ab.Account_Ccy != cam.Account_Ccy

UNION ALL

SELECT 
    'Office Accounts with Currency Mismatch' AS Category,
    COUNT(*) AS Record_Count
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
WHERE ab.Account_Ccy != oam.Account_Ccy;

-- =====================================================
-- STEP 4: FIX - Update Acct_Bal to Match Account Master Currency
-- =====================================================
-- IMPORTANT: Review diagnostic results before running these updates!

-- Fix customer accounts
UPDATE Acct_Bal
SET Account_Ccy = cam.Account_Ccy
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
WHERE ab.Account_Ccy != cam.Account_Ccy;

PRINT 'Customer accounts fixed: ' + CAST(@@ROWCOUNT AS VARCHAR);

-- Fix office accounts (including NOSTRO)
UPDATE Acct_Bal
SET Account_Ccy = oam.Account_Ccy
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
WHERE ab.Account_Ccy != oam.Account_Ccy;

PRINT 'Office accounts fixed: ' + CAST(@@ROWCOUNT AS VARCHAR);

-- =====================================================
-- STEP 5: Specific Fix for NOSTRO Account (922030200101)
-- =====================================================
-- Force update NOSTRO account to USD if it was incorrectly set to BDT
UPDATE Acct_Bal
SET Account_Ccy = 'USD'
WHERE Account_No = '922030200101'
  AND Account_Ccy != 'USD';

PRINT 'NOSTRO account records fixed: ' + CAST(@@ROWCOUNT AS VARCHAR);

-- =====================================================
-- STEP 6: Verification - Ensure Fix Worked
-- =====================================================
SELECT '=== VERIFICATION: Customer Accounts After Fix ===' AS Verification;

SELECT 
    ab.Account_No,
    COUNT(*) AS Total_Records,
    MIN(ab.Tran_Date) AS First_Date,
    MAX(ab.Tran_Date) AS Last_Date,
    ab.Account_Ccy AS AcctBal_Currency,
    cam.Account_Ccy AS Master_Currency,
    CASE 
        WHEN ab.Account_Ccy = cam.Account_Ccy THEN '✅ FIXED'
        ELSE '❌ STILL WRONG'
    END AS Status
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD'  -- Only check USD accounts
GROUP BY ab.Account_No, ab.Account_Ccy, cam.Account_Ccy
ORDER BY ab.Account_No;

SELECT '=== VERIFICATION: Office Accounts After Fix ===' AS Verification;

SELECT 
    ab.Account_No,
    COUNT(*) AS Total_Records,
    MIN(ab.Tran_Date) AS First_Date,
    MAX(ab.Tran_Date) AS Last_Date,
    ab.Account_Ccy AS AcctBal_Currency,
    oam.Account_Ccy AS Master_Currency,
    oam.Acct_Name,
    spm.Sub_Product_Code,
    CASE 
        WHEN ab.Account_Ccy = oam.Account_Ccy THEN '✅ FIXED'
        ELSE '❌ STILL WRONG'
    END AS Status
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD'  -- Only check USD accounts
GROUP BY ab.Account_No, ab.Account_Ccy, oam.Account_Ccy, oam.Acct_Name, spm.Sub_Product_Code
ORDER BY ab.Account_No;

-- =====================================================
-- STEP 7: Verify NOSTRO Account Specifically
-- =====================================================
SELECT '=== FINAL VERIFICATION: NOSTRO Account 922030200101 ===' AS Final_Check;

SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy,
    ab.Opening_Bal,
    ab.DR_Summation,
    ab.CR_Summation,
    ab.Closing_Bal,
    ab.Current_Balance,
    oam.Account_Ccy AS Master_Currency,
    oam.Acct_Name,
    CASE 
        WHEN ab.Account_Ccy = 'USD' AND oam.Account_Ccy = 'USD' THEN '✅ CORRECT - All USD'
        ELSE '❌ ERROR'
    END AS Status
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
WHERE ab.Account_No = '922030200101'
ORDER BY ab.Tran_Date DESC;

-- =====================================================
-- STEP 8: Data Consistency Check
-- =====================================================
SELECT '=== DATA CONSISTENCY CHECK ===' AS Consistency;

-- Check if any accounts still have mismatched currency
SELECT 
    'Remaining Customer Account Mismatches' AS Issue,
    COUNT(*) AS Count
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
WHERE ab.Account_Ccy != cam.Account_Ccy

UNION ALL

SELECT 
    'Remaining Office Account Mismatches' AS Issue,
    COUNT(*) AS Count
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
WHERE ab.Account_Ccy != oam.Account_Ccy;

-- If result shows 0 for both, the fix is complete!

-- =====================================================
-- END OF FIX SCRIPT
-- =====================================================
--
-- Summary of Changes:
-- 1. Identified all Acct_Bal records with currency mismatch
-- 2. Updated customer account balances to match Cust_Acct_Master.Account_Ccy
-- 3. Updated office account balances to match OF_Acct_Master.Account_Ccy
-- 4. Specifically fixed NOSTRO account (922030200101) to use USD
-- 5. Verified all fixes were applied correctly
--
-- Code Fix (Already Applied):
-- - AccountBalanceUpdateService.java now checks BOTH customer and office accounts
-- - Currency is now preserved/corrected during EOD updates (line 161)
-- - New EOD runs will use correct currency from account master
--
-- Next Steps:
-- 1. This script fixes historical data corruption
-- 2. New EOD runs will maintain correct currency (code already fixed)
-- 3. Monitor next EOD run to ensure NOSTRO stays USD
-- =====================================================
