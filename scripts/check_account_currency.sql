-- =====================================================
-- Quick Currency Check Script
-- Purpose: Quickly check currency configuration for specific accounts
-- Usage: Replace account numbers with your actual accounts
-- =====================================================

-- =====================================================
-- Check specific NOSTRO USD account (922030200101)
-- =====================================================
SELECT 
    '=== NOSTRO USD ACCOUNT CHECK ===' AS Section,
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy AS Account_Currency,
    cam.GL_Num,
    spm.Sub_Product_Id,
    spm.Sub_Product_Code,
    spm.Sub_Product_Name,
    pm.Product_Id,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN pm.Currency = 'USD' AND cam.Account_Ccy = 'USD' THEN '✅ CORRECT - USD configured'
        WHEN pm.Currency = 'BDT' OR cam.Account_Ccy = 'BDT' THEN '❌ WRONG - Should be USD'
        WHEN pm.Currency IS NULL OR cam.Account_Ccy IS NULL THEN '❌ NULL - Needs configuration'
        ELSE '⚠️ CHECK'
    END AS Status
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE cam.Account_No = '922030200101';

-- =====================================================
-- Check account balance for NOSTRO USD
-- =====================================================
SELECT 
    '=== NOSTRO USD BALANCE CHECK ===' AS Section,
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy AS Balance_Currency,
    ab.Current_Balance,
    ab.Available_Balance,
    CASE 
        WHEN ab.Account_Ccy = 'USD' THEN '✅ CORRECT - USD balance'
        WHEN ab.Account_Ccy = 'BDT' THEN '❌ WRONG - Should be USD'
        WHEN ab.Account_Ccy IS NULL THEN '❌ NULL - Needs configuration'
        ELSE '⚠️ CHECK'
    END AS Status,
    CASE 
        WHEN ab.Current_Balance > 0 THEN '✅ Positive balance (allowed for NOSTRO)'
        WHEN ab.Current_Balance < 0 THEN '✅ Negative balance (allowed for NOSTRO)'
        ELSE '✅ Zero balance'
    END AS Balance_Status
FROM Acct_Bal ab
WHERE ab.Account_No = '922030200101'
ORDER BY ab.Tran_Date DESC;

-- =====================================================
-- Check recent transactions for NOSTRO USD
-- =====================================================
SELECT TOP 10
    '=== NOSTRO USD RECENT TRANSACTIONS ===' AS Section,
    tt.Tran_Id,
    tt.Tran_Date,
    tt.Value_Date,
    tt.DR_CR_Flag,
    tt.Tran_Ccy AS Transaction_Currency,
    tt.FCY_Amt AS USD_Amount,
    tt.Exchange_Rate,
    tt.LCY_Amt AS BDT_Equivalent,
    tt.Tran_Status,
    tt.Narration,
    CASE 
        WHEN tt.Tran_Ccy = 'USD' THEN '✅ CORRECT - USD transaction'
        WHEN tt.Tran_Ccy = 'BDT' THEN '⚠️ BDT transaction on USD account'
        ELSE '⚠️ CHECK'
    END AS Status
FROM Tran_Table tt
WHERE tt.Account_No = '922030200101'
ORDER BY tt.Tran_Date DESC, tt.Tran_Id DESC;

-- =====================================================
-- Check all USD products
-- =====================================================
SELECT 
    '=== ALL USD PRODUCTS ===' AS Section,
    Product_Id,
    Product_Code,
    Product_Name,
    Currency,
    Cum_GL_Num,
    CASE 
        WHEN Currency = 'USD' THEN '✅ CORRECT'
        WHEN Currency = 'BDT' THEN '❌ WRONG - Should be USD'
        WHEN Currency IS NULL THEN '❌ NULL - Needs configuration'
        ELSE '⚠️ CHECK'
    END AS Status
FROM Prod_Master
WHERE 
    Product_Name LIKE '%USD%'
    OR Product_Code LIKE '%USD%'
    OR Product_Id = 36
ORDER BY Product_Id;

-- =====================================================
-- Check all accounts under USD products
-- =====================================================
SELECT 
    '=== ALL ACCOUNTS UNDER USD PRODUCTS ===' AS Section,
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy,
    cam.GL_Num,
    spm.Sub_Product_Code,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    ab.Current_Balance,
    ab.Account_Ccy AS Balance_Currency,
    CASE 
        WHEN pm.Currency = 'USD' AND cam.Account_Ccy = 'USD' AND ab.Account_Ccy = 'USD' 
            THEN '✅ ALL CORRECT'
        ELSE '❌ MISMATCH DETECTED'
    END AS Status
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
LEFT JOIN (
    SELECT Account_No, Account_Ccy, Current_Balance, 
           ROW_NUMBER() OVER (PARTITION BY Account_No ORDER BY Tran_Date DESC) AS rn
    FROM Acct_Bal
) ab ON cam.Account_No = ab.Account_No AND ab.rn = 1
WHERE 
    pm.Currency = 'USD'
    OR pm.Product_Name LIKE '%USD%'
ORDER BY cam.Account_No;

-- =====================================================
-- Check specific account by account number (customizable)
-- =====================================================
-- Replace 'YOUR_ACCOUNT_NUMBER' with actual account number
DECLARE @AccountNo VARCHAR(13) = '922030200101';  -- Change this

SELECT 
    '=== ACCOUNT DETAILS ===' AS Section,
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy AS Account_Currency,
    cam.GL_Num,
    cam.Account_Status,
    spm.Sub_Product_Code,
    spm.Sub_Product_Name,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN pm.Currency = cam.Account_Ccy THEN '✅ Currency matches'
        ELSE '❌ Currency mismatch'
    END AS Currency_Status
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE cam.Account_No = @AccountNo

UNION ALL

SELECT 
    '=== ACCOUNT DETAILS ===' AS Section,
    oam.Account_No,
    oam.Acct_Name,
    oam.Account_Ccy AS Account_Currency,
    oam.GL_Num,
    oam.Account_Status,
    spm.Sub_Product_Code,
    spm.Sub_Product_Name,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN pm.Currency = oam.Account_Ccy THEN '✅ Currency matches'
        ELSE '❌ Currency mismatch'
    END AS Currency_Status
FROM OF_Acct_Master oam
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE oam.Account_No = @AccountNo;

-- =====================================================
-- Get latest balance for specific account
-- =====================================================
SELECT 
    '=== LATEST BALANCE ===' AS Section,
    Account_No,
    Tran_Date,
    Account_Ccy AS Currency,
    Opening_Bal,
    DR_Summation AS Debits,
    CR_Summation AS Credits,
    Closing_Bal,
    Current_Balance,
    Available_Balance,
    Last_Updated
FROM Acct_Bal
WHERE Account_No = @AccountNo
ORDER BY Tran_Date DESC;

-- =====================================================
-- Summary Report
-- =====================================================
SELECT 
    '=== SUMMARY ===' AS Section,
    COUNT(DISTINCT pm.Product_Id) AS Total_USD_Products,
    COUNT(DISTINCT cam.Account_No) AS Total_USD_Accounts,
    SUM(CASE WHEN cam.Account_Ccy = 'USD' THEN 1 ELSE 0 END) AS Accounts_With_Correct_Currency,
    SUM(CASE WHEN cam.Account_Ccy != 'USD' OR cam.Account_Ccy IS NULL THEN 1 ELSE 0 END) AS Accounts_Needing_Fix
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD' OR pm.Product_Name LIKE '%USD%';

-- =====================================================
-- END OF CHECK SCRIPT
-- =====================================================
