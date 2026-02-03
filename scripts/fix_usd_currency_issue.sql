-- =====================================================
-- FIX SCRIPT: USD Currency Correction
-- Purpose: Update currency from BDT to USD for all USD-based products and accounts
-- 
-- IMPORTANT: Run the diagnostic script first to understand the issues!
-- File: diagnose_usd_currency_issue.sql
-- 
-- BACKUP YOUR DATABASE BEFORE RUNNING THIS SCRIPT!
-- =====================================================

-- =====================================================
-- STEP 1: Fix Product Master Currency for USD Products
-- =====================================================
-- Update all products with USD in their name to have USD currency
-- Adjust the WHERE clause based on your diagnostic results

-- Option A: Update by Product Name pattern
UPDATE Prod_Master
SET Currency = 'USD'
WHERE 
    (Product_Name LIKE '%USD%' OR Product_Name LIKE '%NOSTRO%')
    AND (Currency IS NULL OR Currency != 'USD');

-- Option B: Update by specific Product ID (safer if you know the exact IDs)
-- Uncomment and modify based on diagnostic results
-- UPDATE Prod_Master
-- SET Currency = 'USD'
-- WHERE Product_Id IN (36);  -- Add your USD Product IDs here

-- Verify the update
SELECT 
    Product_Id,
    Product_Code,
    Product_Name,
    Currency,
    '✅ UPDATED TO USD' AS Status
FROM Prod_Master
WHERE Currency = 'USD';

-- =====================================================
-- STEP 2: Fix Customer Account Master Currency
-- =====================================================
-- Update customer accounts to match their Product's currency
UPDATE Cust_Acct_Master cam
SET cam.Account_Ccy = pm.Currency
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    pm.Currency = 'USD'
    AND (cam.Account_Ccy IS NULL OR cam.Account_Ccy != 'USD');

-- Verify customer accounts
SELECT 
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy,
    spm.Sub_Product_Code,
    pm.Product_Name,
    '✅ UPDATED TO USD' AS Status
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD';

-- =====================================================
-- STEP 3: Fix Office Account Master Currency (if any)
-- =====================================================
-- Update office accounts to match their Product's currency
UPDATE OF_Acct_Master oam
SET oam.Account_Ccy = pm.Currency
FROM OF_Acct_Master oam
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    pm.Currency = 'USD'
    AND (oam.Account_Ccy IS NULL OR oam.Account_Ccy != 'USD');

-- Verify office accounts
SELECT 
    oam.Account_No,
    oam.Acct_Name,
    oam.Account_Ccy,
    spm.Sub_Product_Code,
    pm.Product_Name,
    '✅ UPDATED TO USD' AS Status
FROM OF_Acct_Master oam
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD';

-- =====================================================
-- STEP 4: Fix Account Balance Currency
-- =====================================================
-- Update account balances to match their Product's currency
UPDATE Acct_Bal ab
SET ab.Account_Ccy = pm.Currency
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    pm.Currency = 'USD'
    AND (ab.Account_Ccy IS NULL OR ab.Account_Ccy != 'USD');

-- Also update office account balances (if any)
UPDATE Acct_Bal ab
SET ab.Account_Ccy = pm.Currency
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    pm.Currency = 'USD'
    AND (ab.Account_Ccy IS NULL OR ab.Account_Ccy != 'USD');

-- Verify balances
SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy,
    ab.Current_Balance,
    ab.Available_Balance,
    pm.Product_Name,
    '✅ UPDATED TO USD' AS Status
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD'
ORDER BY ab.Account_No, ab.Tran_Date DESC;

-- =====================================================
-- STEP 5: SPECIFIC FIX for NOSTRO USD Account (922030200101)
-- =====================================================
-- If the specific account exists, force update it
UPDATE Cust_Acct_Master
SET Account_Ccy = 'USD'
WHERE Account_No = '922030200101';

UPDATE Acct_Bal
SET Account_Ccy = 'USD'
WHERE Account_No = '922030200101';

-- Verify the specific account
SELECT 
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy AS Account_Currency,
    ab.Account_Ccy AS Balance_Currency,
    ab.Current_Balance,
    ab.Available_Balance,
    spm.Sub_Product_Code,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    '✅ NOSTRO USD ACCOUNT FIXED' AS Status
FROM Cust_Acct_Master cam
LEFT JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE cam.Account_No = '922030200101';

-- =====================================================
-- STEP 6: Final Verification Summary
-- =====================================================
SELECT '✅ FINAL VERIFICATION: USD Products' AS Summary;

SELECT 
    Product_Id,
    Product_Code,
    Product_Name,
    Currency
FROM Prod_Master
WHERE Currency = 'USD';

SELECT '✅ FINAL VERIFICATION: USD Customer Accounts' AS Summary;

SELECT 
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy,
    spm.Sub_Product_Code,
    pm.Product_Name
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD';

SELECT '✅ FINAL VERIFICATION: USD Account Balances' AS Summary;

SELECT 
    ab.Account_No,
    ab.Account_Ccy,
    ab.Current_Balance,
    ab.Available_Balance,
    COUNT(*) AS Balance_Records
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE pm.Currency = 'USD'
GROUP BY ab.Account_No, ab.Account_Ccy, ab.Current_Balance, ab.Available_Balance;

-- =====================================================
-- END OF FIX SCRIPT
-- =====================================================
-- 
-- After running this script:
-- 1. All USD Products should have Currency = 'USD'
-- 2. All USD Accounts should have Account_Ccy = 'USD'
-- 3. All USD Account Balances should have Account_Ccy = 'USD'
-- 4. The NOSTRO USD account (922030200101) should be fully corrected
-- 
-- Next Steps:
-- 1. Test creating a new transaction on the NOSTRO USD account
-- 2. The balance validation will now allow positive balances (code already fixed)
-- 3. All calculations will use USD amounts correctly
-- =====================================================
