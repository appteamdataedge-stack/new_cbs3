-- =====================================================
-- Diagnostic Script: USD Currency Issue Analysis
-- Purpose: Identify all USD-related products and accounts with incorrect currency
-- Run this first to understand the current state
-- =====================================================

-- =====================================================
-- 1. Check Product Master for USD Products
-- =====================================================
SELECT 
    Product_Id,
    Product_Code,
    Product_Name,
    Currency,
    Cum_GL_Num,
    CASE 
        WHEN Currency IS NULL THEN '❌ NULL - NEEDS FIX'
        WHEN Currency = 'BDT' AND Product_Name LIKE '%USD%' THEN '❌ BDT (should be USD) - NEEDS FIX'
        WHEN Currency = 'USD' THEN '✅ CORRECT'
        ELSE '⚠️ CHECK'
    END AS Status
FROM Prod_Master
WHERE 
    Product_Name LIKE '%USD%' 
    OR Product_Name LIKE '%NOSTRO%'
    OR Product_Name LIKE '%TERM DEPOSIT%'
    OR Product_Id = 36
ORDER BY Product_Id;

-- =====================================================
-- 2. Check Sub Product Master for USD Sub-Products
-- =====================================================
SELECT 
    spm.Sub_Product_Id,
    spm.Sub_Product_Code,
    spm.Sub_Product_Name,
    pm.Product_Id,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN pm.Currency IS NULL THEN '❌ Product has NULL currency - NEEDS FIX'
        WHEN pm.Currency = 'BDT' AND (spm.Sub_Product_Name LIKE '%USD%' OR spm.Sub_Product_Code LIKE '%USD%') 
            THEN '❌ Product has BDT (should be USD) - NEEDS FIX'
        WHEN pm.Currency = 'USD' THEN '✅ CORRECT'
        ELSE '⚠️ CHECK'
    END AS Status
FROM Sub_Prod_Master spm
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    spm.Sub_Product_Name LIKE '%USD%'
    OR spm.Sub_Product_Code LIKE '%USD%'
    OR spm.Sub_Product_Id = 51
    OR pm.Product_Id = 36
ORDER BY spm.Sub_Product_Id;

-- =====================================================
-- 3. Check Customer Accounts with USD Sub-Products
-- =====================================================
SELECT 
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy AS Account_Currency,
    spm.Sub_Product_Code,
    spm.Sub_Product_Name,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    cam.GL_Num,
    CASE 
        WHEN cam.Account_Ccy IS NULL THEN '❌ Account_Ccy is NULL - NEEDS FIX'
        WHEN cam.Account_Ccy = 'BDT' AND pm.Currency = 'USD' THEN '❌ Account has BDT but Product is USD - NEEDS FIX'
        WHEN cam.Account_Ccy != pm.Currency THEN '❌ Account currency mismatch - NEEDS FIX'
        WHEN cam.Account_Ccy = pm.Currency THEN '✅ CORRECT'
        ELSE '⚠️ CHECK'
    END AS Status
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    spm.Sub_Product_Name LIKE '%USD%'
    OR spm.Sub_Product_Code LIKE '%USD%'
    OR spm.Sub_Product_Id = 51
    OR pm.Product_Id = 36
    OR cam.Account_No = '922030200101'
ORDER BY cam.Account_No;

-- =====================================================
-- 4. Check Office Accounts with USD Sub-Products (if any)
-- =====================================================
SELECT 
    oam.Account_No,
    oam.Acct_Name,
    oam.Account_Ccy AS Account_Currency,
    spm.Sub_Product_Code,
    spm.Sub_Product_Name,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    oam.GL_Num,
    CASE 
        WHEN oam.Account_Ccy IS NULL THEN '❌ Account_Ccy is NULL - NEEDS FIX'
        WHEN oam.Account_Ccy = 'BDT' AND pm.Currency = 'USD' THEN '❌ Account has BDT but Product is USD - NEEDS FIX'
        WHEN oam.Account_Ccy != pm.Currency THEN '❌ Account currency mismatch - NEEDS FIX'
        WHEN oam.Account_Ccy = pm.Currency THEN '✅ CORRECT'
        ELSE '⚠️ CHECK'
    END AS Status
FROM OF_Acct_Master oam
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    spm.Sub_Product_Name LIKE '%USD%'
    OR spm.Sub_Product_Code LIKE '%USD%'
    OR spm.Sub_Product_Id = 51
    OR pm.Product_Id = 36
    OR oam.Account_No = '922030200101'
ORDER BY oam.Account_No;

-- =====================================================
-- 5. Check Account Balances for USD Accounts
-- =====================================================
SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy AS Balance_Currency,
    ab.Current_Balance,
    ab.Available_Balance,
    cam.Account_Ccy AS Account_Currency,
    pm.Currency AS Product_Currency,
    CASE 
        WHEN ab.Account_Ccy IS NULL THEN '❌ Balance Account_Ccy is NULL - NEEDS FIX'
        WHEN ab.Account_Ccy = 'BDT' AND pm.Currency = 'USD' THEN '❌ Balance has BDT but Product is USD - NEEDS FIX'
        WHEN ab.Account_Ccy != pm.Currency THEN '❌ Balance currency mismatch - NEEDS FIX'
        WHEN ab.Current_Balance < 0 AND pm.Currency = 'USD' THEN '⚠️ Negative balance for USD account'
        WHEN ab.Account_Ccy = pm.Currency THEN '✅ CORRECT'
        ELSE '⚠️ CHECK'
    END AS Status
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE 
    spm.Sub_Product_Name LIKE '%USD%'
    OR spm.Sub_Product_Code LIKE '%USD%'
    OR spm.Sub_Product_Id = 51
    OR pm.Product_Id = 36
    OR ab.Account_No = '922030200101'
ORDER BY ab.Account_No, ab.Tran_Date DESC;

-- =====================================================
-- 6. Summary: Count of Issues by Category
-- =====================================================
SELECT 'Products with Wrong Currency' AS Issue_Category, 
       COUNT(*) AS Issue_Count
FROM Prod_Master
WHERE (Product_Name LIKE '%USD%' OR Product_Id = 36)
  AND (Currency IS NULL OR Currency != 'USD')

UNION ALL

SELECT 'Accounts with Wrong Currency' AS Issue_Category,
       COUNT(*) AS Issue_Count
FROM Cust_Acct_Master cam
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE (spm.Sub_Product_Name LIKE '%USD%' OR pm.Product_Id = 36)
  AND (cam.Account_Ccy IS NULL OR cam.Account_Ccy != pm.Currency)

UNION ALL

SELECT 'Balances with Wrong Currency' AS Issue_Category,
       COUNT(*) AS Issue_Count
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
INNER JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE (spm.Sub_Product_Name LIKE '%USD%' OR pm.Product_Id = 36)
  AND (ab.Account_Ccy IS NULL OR ab.Account_Ccy != pm.Currency);

-- =====================================================
-- END OF DIAGNOSTIC SCRIPT
-- =====================================================
