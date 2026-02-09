# Sub-Product GL Balance Reconciliation - SQL Queries Reference

## Overview
This document contains SQL queries useful for troubleshooting, testing, and understanding the Sub-Product GL Balance Reconciliation process.

---

## 1. Basic Reconciliation Query

Get reconciliation data for all active sub-products on a specific date:

```sql
-- Replace @reportDate with actual date (e.g., '2024-01-15')
DECLARE @reportDate DATE = '2024-01-15';

SELECT 
    sp.Sub_Product_Code,
    sp.Sub_Product_Name,
    sp.Cum_GL_Num AS GL_Number,
    g.GL_Name,
    
    -- Count of accounts
    (SELECT COUNT(*) 
     FROM Cust_Acct_Master cam 
     WHERE cam.Sub_Product_Id = sp.Sub_Product_Id) 
    + (SELECT COUNT(*) 
       FROM OF_Acct_Master oam 
       WHERE oam.Sub_Product_Id = sp.Sub_Product_Id) AS Account_Count,
    
    -- Total account balance (LCY)
    ISNULL((SELECT SUM(ab.Current_Balance) 
            FROM Cust_Acct_Master cam 
            JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
            WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
            AND ab.Tran_Date = @reportDate), 0) 
    + ISNULL((SELECT SUM(ab.Current_Balance) 
              FROM OF_Acct_Master oam 
              JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
              WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
              AND ab.Tran_Date = @reportDate), 0) AS Total_Account_Balance,
    
    -- GL balance
    ISNULL((SELECT gb.Current_Balance 
            FROM GL_Balance gb 
            WHERE gb.GL_Num = sp.Cum_GL_Num 
            AND gb.Tran_date = @reportDate), 0) AS GL_Balance,
    
    -- Difference
    (ISNULL((SELECT SUM(ab.Current_Balance) 
             FROM Cust_Acct_Master cam 
             JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
             WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
             AND ab.Tran_Date = @reportDate), 0) 
    + ISNULL((SELECT SUM(ab.Current_Balance) 
              FROM OF_Acct_Master oam 
              JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
              WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
              AND ab.Tran_Date = @reportDate), 0))
    - ISNULL((SELECT gb.Current_Balance 
              FROM GL_Balance gb 
              WHERE gb.GL_Num = sp.Cum_GL_Num 
              AND gb.Tran_date = @reportDate), 0) AS Difference,
    
    -- Status
    CASE 
        WHEN (ISNULL((SELECT SUM(ab.Current_Balance) 
                      FROM Cust_Acct_Master cam 
                      JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
                      WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
                      AND ab.Tran_Date = @reportDate), 0) 
             + ISNULL((SELECT SUM(ab.Current_Balance) 
                       FROM OF_Acct_Master oam 
                       JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
                       WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
                       AND ab.Tran_Date = @reportDate), 0))
             - ISNULL((SELECT gb.Current_Balance 
                       FROM GL_Balance gb 
                       WHERE gb.GL_Num = sp.Cum_GL_Num 
                       AND gb.Tran_date = @reportDate), 0) = 0 
        THEN 'Matched'
        ELSE 'Unmatched'
    END AS Status

FROM Sub_Prod_Master sp
LEFT JOIN GL_Setup g ON sp.Cum_GL_Num = g.GL_Num
WHERE sp.Sub_Product_Status = 'Active'
ORDER BY 
    CASE 
        WHEN (ISNULL((SELECT SUM(ab.Current_Balance) 
                      FROM Cust_Acct_Master cam 
                      JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
                      WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
                      AND ab.Tran_Date = @reportDate), 0) 
             + ISNULL((SELECT SUM(ab.Current_Balance) 
                       FROM OF_Acct_Master oam 
                       JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
                       WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
                       AND ab.Tran_Date = @reportDate), 0))
             - ISNULL((SELECT gb.Current_Balance 
                       FROM GL_Balance gb 
                       WHERE gb.GL_Num = sp.Cum_GL_Num 
                       AND gb.Tran_date = @reportDate), 0) = 0 
        THEN 1 
        ELSE 0 
    END,
    ABS((ISNULL((SELECT SUM(ab.Current_Balance) 
                 FROM Cust_Acct_Master cam 
                 JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
                 WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
                 AND ab.Tran_Date = @reportDate), 0) 
        + ISNULL((SELECT SUM(ab.Current_Balance) 
                  FROM OF_Acct_Master oam 
                  JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
                  WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
                  AND ab.Tran_Date = @reportDate), 0))
        - ISNULL((SELECT gb.Current_Balance 
                  FROM GL_Balance gb 
                  WHERE gb.GL_Num = sp.Cum_GL_Num 
                  AND gb.Tran_date = @reportDate), 0)) DESC;
```

---

## 2. Find Only Unmatched Sub-Products

Quickly identify sub-products with reconciliation issues:

```sql
DECLARE @reportDate DATE = '2024-01-15';

WITH Reconciliation AS (
    SELECT 
        sp.Sub_Product_Code,
        sp.Sub_Product_Name,
        sp.Cum_GL_Num,
        
        -- Total account balance
        ISNULL((SELECT SUM(ab.Current_Balance) 
                FROM Cust_Acct_Master cam 
                JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
                WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
                AND ab.Tran_Date = @reportDate), 0) 
        + ISNULL((SELECT SUM(ab.Current_Balance) 
                  FROM OF_Acct_Master oam 
                  JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
                  WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
                  AND ab.Tran_Date = @reportDate), 0) AS Total_Account_Balance,
        
        -- GL balance
        ISNULL((SELECT gb.Current_Balance 
                FROM GL_Balance gb 
                WHERE gb.GL_Num = sp.Cum_GL_Num 
                AND gb.Tran_date = @reportDate), 0) AS GL_Balance
        
    FROM Sub_Prod_Master sp
    WHERE sp.Sub_Product_Status = 'Active'
)
SELECT 
    Sub_Product_Code,
    Sub_Product_Name,
    Cum_GL_Num AS GL_Number,
    Total_Account_Balance,
    GL_Balance,
    (Total_Account_Balance - GL_Balance) AS Difference
FROM Reconciliation
WHERE (Total_Account_Balance - GL_Balance) != 0
ORDER BY ABS(Total_Account_Balance - GL_Balance) DESC;
```

---

## 3. Drill-Down: Account Balances for a Sub-Product

Get all accounts under a specific sub-product with their balances:

```sql
DECLARE @subProductCode VARCHAR(10) = 'SP001';
DECLARE @reportDate DATE = '2024-01-15';

-- Customer Accounts
SELECT 
    'Customer' AS Account_Type,
    cam.Account_No,
    cam.Acct_Name AS Account_Name,
    ab.Account_Ccy AS Currency,
    ISNULL(ab.Current_Balance, 0) AS LCY_Balance
FROM Cust_Acct_Master cam
LEFT JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No AND ab.Tran_Date = @reportDate
WHERE cam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)

UNION ALL

-- Office Accounts
SELECT 
    'Office' AS Account_Type,
    oam.Account_No,
    oam.Acct_Name AS Account_Name,
    ab.Account_Ccy AS Currency,
    ISNULL(ab.Current_Balance, 0) AS LCY_Balance
FROM OF_Acct_Master oam
LEFT JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No AND ab.Tran_Date = @reportDate
WHERE oam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)

ORDER BY Account_Type, Account_No;
```

---

## 4. FCY Account Breakdown for a Sub-Product

See which accounts are in foreign currency:

```sql
DECLARE @subProductCode VARCHAR(10) = 'SP001';
DECLARE @reportDate DATE = '2024-01-15';

SELECT 
    ab.Account_Ccy AS Currency,
    COUNT(*) AS Account_Count,
    SUM(ab.Current_Balance) AS Total_LCY_Balance
FROM (
    -- Customer accounts
    SELECT cam.Account_No
    FROM Cust_Acct_Master cam
    WHERE cam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)
    
    UNION ALL
    
    -- Office accounts
    SELECT oam.Account_No
    FROM OF_Acct_Master oam
    WHERE oam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)
) accounts
JOIN Acct_Bal ab ON accounts.Account_No = ab.Account_No
WHERE ab.Tran_Date = @reportDate
  AND ab.Account_Ccy != 'BDT'  -- Only FCY accounts
GROUP BY ab.Account_Ccy
ORDER BY ab.Account_Ccy;
```

---

## 5. GL Postings for a Sub-Product

See all GL movements affecting the sub-product's GL:

```sql
DECLARE @subProductCode VARCHAR(10) = 'SP001';
DECLARE @reportDate DATE = '2024-01-15';

DECLARE @glNum VARCHAR(9);
SELECT @glNum = Cum_GL_Num 
FROM Sub_Prod_Master 
WHERE Sub_Product_Code = @subProductCode;

-- GL Movements
SELECT 
    'GL Movement' AS Source,
    gm.Tran_Date AS Date,
    gm.Tran_Id AS Transaction_ID,
    ISNULL(gm.Narration, 'Transaction') AS Description,
    CASE WHEN gm.Dr_Cr_Flag = 'D' THEN gm.Amount ELSE 0 END AS Debit,
    CASE WHEN gm.Dr_Cr_Flag = 'C' THEN gm.Amount ELSE 0 END AS Credit
FROM GL_Movement gm
JOIN GL_Setup gs ON gm.GL_Num = gs.GL_Num
WHERE gs.GL_Num = @glNum
  AND gm.Tran_Date = @reportDate

UNION ALL

-- GL Movement Accruals
SELECT 
    'Interest Accrual' AS Source,
    gma.Accrual_Date AS Date,
    ISNULL(gma.Tran_Id, gma.Accr_Tran_Id) AS Transaction_ID,
    ISNULL(gma.Narration, 'Interest Accrual') AS Description,
    CASE WHEN gma.Dr_Cr_Flag = 'D' THEN gma.Amount ELSE 0 END AS Debit,
    CASE WHEN gma.Dr_Cr_Flag = 'C' THEN gma.Amount ELSE 0 END AS Credit
FROM GL_Movement_Accrual gma
WHERE gma.GL_Num = @glNum
  AND gma.Accrual_Date = @reportDate

ORDER BY Date, Transaction_ID;
```

---

## 6. Check for Missing GL Balances

Find sub-products that don't have GL balance records for a date:

```sql
DECLARE @reportDate DATE = '2024-01-15';

SELECT 
    sp.Sub_Product_Code,
    sp.Sub_Product_Name,
    sp.Cum_GL_Num AS Missing_GL_Number
FROM Sub_Prod_Master sp
WHERE sp.Sub_Product_Status = 'Active'
  AND NOT EXISTS (
      SELECT 1 
      FROM GL_Balance gb 
      WHERE gb.GL_Num = sp.Cum_GL_Num 
        AND gb.Tran_date = @reportDate
  )
ORDER BY sp.Sub_Product_Code;
```

---

## 7. Check for Missing Account Balances

Find accounts under active sub-products without balance records:

```sql
DECLARE @reportDate DATE = '2024-01-15';

-- Customer accounts missing balances
SELECT 
    'Customer' AS Account_Type,
    sp.Sub_Product_Code,
    cam.Account_No,
    cam.Acct_Name
FROM Cust_Acct_Master cam
JOIN Sub_Prod_Master sp ON cam.Sub_Product_Id = sp.Sub_Product_Id
WHERE sp.Sub_Product_Status = 'Active'
  AND cam.Account_Status = 'Active'
  AND NOT EXISTS (
      SELECT 1 
      FROM Acct_Bal ab 
      WHERE ab.Account_No = cam.Account_No 
        AND ab.Tran_Date = @reportDate
  )

UNION ALL

-- Office accounts missing balances
SELECT 
    'Office' AS Account_Type,
    sp.Sub_Product_Code,
    oam.Account_No,
    oam.Acct_Name
FROM OF_Acct_Master oam
JOIN Sub_Prod_Master sp ON oam.Sub_Product_Id = sp.Sub_Product_Id
WHERE sp.Sub_Product_Status = 'Active'
  AND oam.Account_Status = 'Active'
  AND NOT EXISTS (
      SELECT 1 
      FROM Acct_Bal ab 
      WHERE ab.Account_No = oam.Account_No 
        AND ab.Tran_Date = @reportDate
  )
ORDER BY Sub_Product_Code, Account_Type, Account_No;
```

---

## 8. Summary Statistics

Get overall reconciliation statistics:

```sql
DECLARE @reportDate DATE = '2024-01-15';

WITH Reconciliation AS (
    SELECT 
        sp.Sub_Product_Code,
        ISNULL((SELECT SUM(ab.Current_Balance) 
                FROM Cust_Acct_Master cam 
                JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
                WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
                AND ab.Tran_Date = @reportDate), 0) 
        + ISNULL((SELECT SUM(ab.Current_Balance) 
                  FROM OF_Acct_Master oam 
                  JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
                  WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
                  AND ab.Tran_Date = @reportDate), 0) AS Total_Account_Balance,
        
        ISNULL((SELECT gb.Current_Balance 
                FROM GL_Balance gb 
                WHERE gb.GL_Num = sp.Cum_GL_Num 
                AND gb.Tran_date = @reportDate), 0) AS GL_Balance
        
    FROM Sub_Prod_Master sp
    WHERE sp.Sub_Product_Status = 'Active'
)
SELECT 
    COUNT(*) AS Total_SubProducts,
    SUM(CASE WHEN (Total_Account_Balance - GL_Balance) = 0 THEN 1 ELSE 0 END) AS Matched_Count,
    SUM(CASE WHEN (Total_Account_Balance - GL_Balance) != 0 THEN 1 ELSE 0 END) AS Unmatched_Count,
    SUM(Total_Account_Balance) AS Grand_Total_Account_Balance,
    SUM(GL_Balance) AS Grand_Total_GL_Balance,
    SUM(Total_Account_Balance - GL_Balance) AS Grand_Total_Difference,
    MIN(ABS(Total_Account_Balance - GL_Balance)) AS Min_Difference,
    MAX(ABS(Total_Account_Balance - GL_Balance)) AS Max_Difference,
    AVG(ABS(Total_Account_Balance - GL_Balance)) AS Avg_Difference
FROM Reconciliation;
```

---

## 9. Historical Comparison

Compare reconciliation across multiple dates:

```sql
DECLARE @startDate DATE = '2024-01-01';
DECLARE @endDate DATE = '2024-01-31';

WITH DateRange AS (
    SELECT @startDate AS ReportDate
    UNION ALL
    SELECT DATEADD(DAY, 1, ReportDate)
    FROM DateRange
    WHERE ReportDate < @endDate
),
Reconciliation AS (
    SELECT 
        dr.ReportDate,
        sp.Sub_Product_Code,
        ISNULL((SELECT SUM(ab.Current_Balance) 
                FROM Cust_Acct_Master cam 
                JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
                WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
                AND ab.Tran_Date = dr.ReportDate), 0) 
        + ISNULL((SELECT SUM(ab.Current_Balance) 
                  FROM OF_Acct_Master oam 
                  JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
                  WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
                  AND ab.Tran_Date = dr.ReportDate), 0) AS Total_Account_Balance,
        
        ISNULL((SELECT gb.Current_Balance 
                FROM GL_Balance gb 
                WHERE gb.GL_Num = sp.Cum_GL_Num 
                AND gb.Tran_date = dr.ReportDate), 0) AS GL_Balance
        
    FROM DateRange dr
    CROSS JOIN Sub_Prod_Master sp
    WHERE sp.Sub_Product_Status = 'Active'
)
SELECT 
    ReportDate,
    COUNT(*) AS Total_SubProducts,
    SUM(CASE WHEN (Total_Account_Balance - GL_Balance) = 0 THEN 1 ELSE 0 END) AS Matched_Count,
    SUM(CASE WHEN (Total_Account_Balance - GL_Balance) != 0 THEN 1 ELSE 0 END) AS Unmatched_Count,
    SUM(ABS(Total_Account_Balance - GL_Balance)) AS Total_Difference
FROM Reconciliation
GROUP BY ReportDate
ORDER BY ReportDate
OPTION (MAXRECURSION 365);
```

---

## 10. Top Unmatched Sub-Products by Difference

Find the worst offenders:

```sql
DECLARE @reportDate DATE = '2024-01-15';
DECLARE @topN INT = 10;

WITH Reconciliation AS (
    SELECT 
        sp.Sub_Product_Code,
        sp.Sub_Product_Name,
        sp.Cum_GL_Num,
        
        ISNULL((SELECT SUM(ab.Current_Balance) 
                FROM Cust_Acct_Master cam 
                JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
                WHERE cam.Sub_Product_Id = sp.Sub_Product_Id 
                AND ab.Tran_Date = @reportDate), 0) 
        + ISNULL((SELECT SUM(ab.Current_Balance) 
                  FROM OF_Acct_Master oam 
                  JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No 
                  WHERE oam.Sub_Product_Id = sp.Sub_Product_Id 
                  AND ab.Tran_Date = @reportDate), 0) AS Total_Account_Balance,
        
        ISNULL((SELECT gb.Current_Balance 
                FROM GL_Balance gb 
                WHERE gb.GL_Num = sp.Cum_GL_Num 
                AND gb.Tran_date = @reportDate), 0) AS GL_Balance
        
    FROM Sub_Prod_Master sp
    WHERE sp.Sub_Product_Status = 'Active'
)
SELECT TOP (@topN)
    Sub_Product_Code,
    Sub_Product_Name,
    Cum_GL_Num AS GL_Number,
    Total_Account_Balance,
    GL_Balance,
    (Total_Account_Balance - GL_Balance) AS Difference,
    ABS(Total_Account_Balance - GL_Balance) AS Abs_Difference
FROM Reconciliation
WHERE (Total_Account_Balance - GL_Balance) != 0
ORDER BY ABS(Total_Account_Balance - GL_Balance) DESC;
```

---

## 11. Check Data Availability for Date Range

Ensure all required data exists before running reconciliation:

```sql
DECLARE @reportDate DATE = '2024-01-15';

SELECT 
    'Sub-Products (Active)' AS Data_Type,
    COUNT(*) AS Count
FROM Sub_Prod_Master
WHERE Sub_Product_Status = 'Active'

UNION ALL

SELECT 
    'Customer Accounts (Active)',
    COUNT(*)
FROM Cust_Acct_Master cam
JOIN Sub_Prod_Master sp ON cam.Sub_Product_Id = sp.Sub_Product_Id
WHERE sp.Sub_Product_Status = 'Active'
  AND cam.Account_Status = 'Active'

UNION ALL

SELECT 
    'Office Accounts (Active)',
    COUNT(*)
FROM OF_Acct_Master oam
JOIN Sub_Prod_Master sp ON oam.Sub_Product_Id = sp.Sub_Product_Id
WHERE sp.Sub_Product_Status = 'Active'
  AND oam.Account_Status = 'Active'

UNION ALL

SELECT 
    'Account Balances for Date',
    COUNT(*)
FROM Acct_Bal
WHERE Tran_Date = @reportDate

UNION ALL

SELECT 
    'GL Balances for Date',
    COUNT(*)
FROM GL_Balance
WHERE Tran_date = @reportDate

UNION ALL

SELECT 
    'GL Movements for Date',
    COUNT(*)
FROM GL_Movement
WHERE Tran_Date = @reportDate

UNION ALL

SELECT 
    'GL Accruals for Date',
    COUNT(*)
FROM GL_Movement_Accrual
WHERE Accrual_Date = @reportDate;
```

---

## 12. Find Accounts Contributing to Specific Difference

When investigating an unmatched sub-product, find which accounts might be causing the issue:

```sql
DECLARE @subProductCode VARCHAR(10) = 'SP001';
DECLARE @reportDate DATE = '2024-01-15';

-- Get expected total from accounts
DECLARE @accountTotal DECIMAL(20,2);
SELECT @accountTotal = ISNULL(SUM(ab.Current_Balance), 0)
FROM (
    SELECT cam.Account_No FROM Cust_Acct_Master cam 
    WHERE cam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)
    UNION ALL
    SELECT oam.Account_No FROM OF_Acct_Master oam 
    WHERE oam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)
) accounts
JOIN Acct_Bal ab ON accounts.Account_No = ab.Account_No
WHERE ab.Tran_Date = @reportDate;

-- Get GL total
DECLARE @glTotal DECIMAL(20,2);
SELECT @glTotal = ISNULL(gb.Current_Balance, 0)
FROM GL_Balance gb
WHERE gb.GL_Num = (SELECT Cum_GL_Num FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)
  AND gb.Tran_date = @reportDate;

-- Show difference
SELECT 
    @accountTotal AS Total_Account_Balance,
    @glTotal AS Total_GL_Balance,
    (@accountTotal - @glTotal) AS Difference;

-- List all accounts with their balances
SELECT 
    CASE 
        WHEN cam.Account_No IS NOT NULL THEN 'Customer'
        ELSE 'Office'
    END AS Account_Type,
    ISNULL(cam.Account_No, oam.Account_No) AS Account_No,
    ISNULL(cam.Acct_Name, oam.Acct_Name) AS Account_Name,
    ab.Account_Ccy AS Currency,
    ab.Current_Balance AS Balance
FROM (
    SELECT cam.Account_No FROM Cust_Acct_Master cam 
    WHERE cam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)
    UNION ALL
    SELECT oam.Account_No FROM OF_Acct_Master oam 
    WHERE oam.Sub_Product_Id = (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = @subProductCode)
) accounts
LEFT JOIN Cust_Acct_Master cam ON accounts.Account_No = cam.Account_No
LEFT JOIN OF_Acct_Master oam ON accounts.Account_No = oam.Account_No
LEFT JOIN Acct_Bal ab ON accounts.Account_No = ab.Account_No AND ab.Tran_Date = @reportDate
ORDER BY Account_Type, Account_No;
```

---

## Notes

### Performance Tips
1. Create indexes on frequently queried columns:
   - `Acct_Bal (Account_No, Tran_Date)`
   - `GL_Balance (GL_Num, Tran_date)`
   - `Sub_Prod_Master (Sub_Product_Status, Sub_Product_Code)`

2. Use variables to store report date instead of inline values for better query plan caching

3. For large datasets, consider batch processing by sub-product ranges

### Common Issues
- **NULL Balances**: Use `ISNULL()` or `COALESCE()` to default to 0
- **Missing Records**: Check if EOD process completed for the date
- **Performance**: Add appropriate indexes on join columns
- **Date Formats**: Ensure consistent date format across queries

### Best Practices
- Always specify the report date explicitly
- Use transactions for complex updates
- Log reconciliation results for audit trail
- Schedule queries during off-peak hours for large data sets
