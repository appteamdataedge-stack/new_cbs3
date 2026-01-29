-- ============================================================================
-- Interest Capitalization Database Diagnostic Script
-- Purpose: Diagnose and fix "Account balance record not found" error
-- Date: 2026-01-29
-- ============================================================================

-- ============================================================================
-- STEP 1: Check System Date Configuration
-- ============================================================================
SELECT 
    'SYSTEM DATE CHECK' AS Check_Type,
    Param_Name,
    Param_Value AS System_Date,
    Last_Updated_By,
    Last_Updated_Timestamp
FROM Parameter_Table
WHERE Param_Name = 'System_Date';

-- Expected: Should show the current business date (e.g., '2026-01-29')
-- If empty or wrong date, update it:
-- UPDATE Parameter_Table 
-- SET Param_Value = '2026-01-29', 
--     Last_Updated_By = 'ADMIN', 
--     Last_Updated_Timestamp = NOW()
-- WHERE Param_Name = 'System_Date';

-- ============================================================================
-- STEP 2: Find the Account Having Issues
-- ============================================================================
-- Replace 'YOUR_ACCOUNT_NUMBER' with the actual account number from the error
SET @account_number = 'YOUR_ACCOUNT_NUMBER';  -- UPDATE THIS!

SELECT 
    'ACCOUNT DETAILS' AS Check_Type,
    Account_No,
    Acct_Name,
    Date_Opening,
    Account_Status,
    GL_Num,
    Last_Interest_Payment_Date
FROM Cust_Acct_Master
WHERE Account_No = @account_number;

-- ============================================================================
-- STEP 3: Check Balance Records for This Account
-- ============================================================================
SELECT 
    'BALANCE RECORDS' AS Check_Type,
    Account_No,
    Tran_Date,
    Current_Balance,
    Available_Balance,
    Opening_Bal,
    Closing_Bal,
    DR_Summation,
    CR_Summation,
    Last_Updated
FROM Acct_Bal
WHERE Account_No = @account_number
ORDER BY Tran_Date DESC
LIMIT 20;

-- Expected: Should show balance records for various dates
-- If EMPTY: Account has NO balance records → This is the problem!
-- If has records but not for system date: Will use latest record (fallback)

-- ============================================================================
-- STEP 4: Check Accrued Interest for This Account
-- ============================================================================
SELECT 
    'ACCRUED INTEREST' AS Check_Type,
    Account_No,
    Interest_Amount,
    Accrual_Date,
    Last_Updated
FROM Acct_Bal_Accrual
WHERE Account_No = @account_number
ORDER BY Accrual_Date DESC
LIMIT 10;

-- Expected: Should show accrued interest amount > 0
-- If 0 or missing: "There is no accrued interest" error will occur

-- ============================================================================
-- STEP 5: Check ALL Accounts with Missing Balance Records
-- ============================================================================
SELECT 
    'ACCOUNTS WITHOUT BALANCE' AS Check_Type,
    cam.Account_No,
    cam.Acct_Name,
    cam.Date_Opening,
    cam.Account_Status,
    COUNT(ab.Account_No) AS Balance_Record_Count
FROM Cust_Acct_Master cam
LEFT JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No
WHERE cam.Account_Status = 'Active'
GROUP BY cam.Account_No, cam.Acct_Name, cam.Date_Opening, cam.Account_Status
HAVING COUNT(ab.Account_No) = 0
ORDER BY cam.Date_Opening DESC;

-- Expected: Should be EMPTY (all active accounts should have balance records)
-- If shows accounts: These accounts were not properly initialized

-- ============================================================================
-- STEP 6: Check Balance Records for System Date
-- ============================================================================
-- Get system date from Parameter_Table
SET @system_date = (SELECT Param_Value FROM Parameter_Table WHERE Param_Name = 'System_Date');

SELECT 
    'BALANCES FOR SYSTEM DATE' AS Check_Type,
    @system_date AS System_Date,
    COUNT(*) AS Total_Accounts_With_Balance
FROM Acct_Bal
WHERE Tran_Date = @system_date;

-- Expected: Should show count of accounts with balance for system date
-- If 0: EOD process hasn't run for this date yet

-- ============================================================================
-- STEP 7: Find Latest Balance Date in System
-- ============================================================================
SELECT 
    'LATEST BALANCE DATE' AS Check_Type,
    MAX(Tran_Date) AS Latest_Balance_Date,
    COUNT(DISTINCT Account_No) AS Total_Accounts,
    @system_date AS System_Date,
    CASE 
        WHEN MAX(Tran_Date) = @system_date THEN 'UP TO DATE'
        WHEN MAX(Tran_Date) < @system_date THEN 'EOD NOT RUN FOR SYSTEM DATE'
        ELSE 'SYSTEM DATE IS BEHIND'
    END AS Status
FROM Acct_Bal;

-- ============================================================================
-- STEP 8: Check Transaction History for Account
-- ============================================================================
SELECT 
    'TRANSACTION HISTORY' AS Check_Type,
    Tran_Id,
    Tran_Date,
    Value_Date,
    DR_CR_Flag,
    Debit_Amount,
    Credit_Amount,
    Tran_Status,
    Narration
FROM Tran_Table
WHERE Account_No = @account_number
ORDER BY Tran_Date DESC, Tran_Id DESC
LIMIT 20;

-- ============================================================================
-- STEP 9: Check Interest Accrual Transactions
-- ============================================================================
SELECT 
    'INTEREST ACCRUAL TRANS' AS Check_Type,
    Accr_Tran_Id,
    Account_No,
    Accrual_Date,
    Tran_Date,
    DR_CR_Flag,
    Amount,
    Interest_Rate,
    Status,
    Narration
FROM Intt_Accr_Tran
WHERE Account_No = @account_number
   OR GL_Account_No IN (
       SELECT Interest_Income_Payable_GL_Num 
       FROM Sub_Prod_Master 
       WHERE Sub_Product_Id = (
           SELECT Sub_Product_Id 
           FROM Cust_Acct_Master 
           WHERE Account_No = @account_number
       )
   )
ORDER BY Accrual_Date DESC, Tran_Date DESC
LIMIT 20;

-- ============================================================================
-- FIX OPTION 1: Create Missing Balance Record for System Date
-- ============================================================================
-- Use this if the account exists but has NO balance record for system date

-- First, check if record already exists
SELECT 
    'CHECK BEFORE INSERT' AS Action,
    COUNT(*) AS Existing_Records
FROM Acct_Bal
WHERE Account_No = @account_number
  AND Tran_Date = @system_date;

-- If count = 0, insert a new record:
/*
INSERT INTO Acct_Bal (
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
    @system_date AS Tran_Date,
    @account_number AS Account_No,
    COALESCE(cam.Account_Ccy, 'BDT') AS Account_Ccy,
    COALESCE(latest.Current_Balance, 0.00) AS Opening_Bal,
    0.00 AS DR_Summation,
    0.00 AS CR_Summation,
    COALESCE(latest.Current_Balance, 0.00) AS Closing_Bal,
    COALESCE(latest.Current_Balance, 0.00) AS Current_Balance,
    COALESCE(latest.Current_Balance, 0.00) AS Available_Balance,
    NOW() AS Last_Updated
FROM Cust_Acct_Master cam
LEFT JOIN (
    SELECT Account_No, Current_Balance
    FROM Acct_Bal
    WHERE Account_No = @account_number
    ORDER BY Tran_Date DESC
    LIMIT 1
) latest ON cam.Account_No = latest.Account_No
WHERE cam.Account_No = @account_number;
*/

-- Verify the insert:
/*
SELECT 
    'VERIFY INSERT' AS Action,
    Account_No,
    Tran_Date,
    Current_Balance,
    Available_Balance
FROM Acct_Bal
WHERE Account_No = @account_number
  AND Tran_Date = @system_date;
*/

-- ============================================================================
-- FIX OPTION 2: Create Balance Records for ALL Accounts (EOD Simulation)
-- ============================================================================
-- Use this to create balance records for ALL accounts for system date
-- WARNING: This simulates EOD process. Use with caution!

/*
INSERT INTO Acct_Bal (
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
    @system_date AS Tran_Date,
    cam.Account_No,
    COALESCE(cam.Account_Ccy, 'BDT') AS Account_Ccy,
    COALESCE(latest.Current_Balance, 0.00) AS Opening_Bal,
    0.00 AS DR_Summation,
    0.00 AS CR_Summation,
    COALESCE(latest.Current_Balance, 0.00) AS Closing_Bal,
    COALESCE(latest.Current_Balance, 0.00) AS Current_Balance,
    COALESCE(latest.Current_Balance, 0.00) AS Available_Balance,
    NOW() AS Last_Updated
FROM Cust_Acct_Master cam
LEFT JOIN (
    SELECT ab.Account_No, ab.Current_Balance
    FROM Acct_Bal ab
    INNER JOIN (
        SELECT Account_No, MAX(Tran_Date) AS Max_Date
        FROM Acct_Bal
        GROUP BY Account_No
    ) latest ON ab.Account_No = latest.Account_No 
           AND ab.Tran_Date = latest.Max_Date
) latest ON cam.Account_No = latest.Account_No
WHERE cam.Account_Status = 'Active'
  AND NOT EXISTS (
      SELECT 1 
      FROM Acct_Bal ab2 
      WHERE ab2.Account_No = cam.Account_No 
        AND ab2.Tran_Date = @system_date
  );
*/

-- Verify the bulk insert:
/*
SELECT 
    'VERIFY BULK INSERT' AS Action,
    COUNT(*) AS Records_Created
FROM Acct_Bal
WHERE Tran_Date = @system_date;
*/

-- ============================================================================
-- FIX OPTION 3: Initialize Balance for Newly Created Account
-- ============================================================================
-- Use this if account was just created but balance record is missing

/*
INSERT INTO Acct_Bal (
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
    @system_date AS Tran_Date,
    @account_number AS Account_No,
    COALESCE(Account_Ccy, 'BDT') AS Account_Ccy,
    0.00 AS Opening_Bal,
    0.00 AS DR_Summation,
    0.00 AS CR_Summation,
    0.00 AS Closing_Bal,
    0.00 AS Current_Balance,
    0.00 AS Available_Balance,
    NOW() AS Last_Updated
FROM Cust_Acct_Master
WHERE Account_No = @account_number
  AND NOT EXISTS (
      SELECT 1 
      FROM Acct_Bal 
      WHERE Account_No = @account_number
  );
*/

-- ============================================================================
-- VERIFICATION QUERIES (Run After Fix)
-- ============================================================================

-- 1. Verify balance record exists for system date
SELECT 
    'FINAL VERIFICATION' AS Check_Type,
    Account_No,
    Tran_Date,
    Current_Balance,
    Available_Balance,
    Last_Updated
FROM Acct_Bal
WHERE Account_No = @account_number
  AND Tran_Date = @system_date;

-- 2. Verify latest balance record
SELECT 
    'LATEST BALANCE' AS Check_Type,
    Account_No,
    Tran_Date,
    Current_Balance,
    Available_Balance,
    Last_Updated
FROM Acct_Bal
WHERE Account_No = @account_number
ORDER BY Tran_Date DESC
LIMIT 1;

-- 3. Count total balance records for account
SELECT 
    'TOTAL BALANCE RECORDS' AS Check_Type,
    Account_No,
    COUNT(*) AS Total_Records,
    MIN(Tran_Date) AS Earliest_Date,
    MAX(Tran_Date) AS Latest_Date
FROM Acct_Bal
WHERE Account_No = @account_number
GROUP BY Account_No;

-- ============================================================================
-- SUMMARY REPORT
-- ============================================================================
SELECT 
    'SUMMARY' AS Report_Type,
    (SELECT Param_Value FROM Parameter_Table WHERE Param_Name = 'System_Date') AS System_Date,
    (SELECT COUNT(*) FROM Cust_Acct_Master WHERE Account_Status = 'Active') AS Total_Active_Accounts,
    (SELECT COUNT(DISTINCT Account_No) FROM Acct_Bal WHERE Tran_Date = @system_date) AS Accounts_With_Balance_Today,
    (SELECT COUNT(DISTINCT cam.Account_No) 
     FROM Cust_Acct_Master cam 
     LEFT JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No 
     WHERE cam.Account_Status = 'Active' AND ab.Account_No IS NULL) AS Accounts_Without_Any_Balance,
    CASE 
        WHEN EXISTS (
            SELECT 1 FROM Acct_Bal 
            WHERE Account_No = @account_number 
              AND Tran_Date = @system_date
        ) THEN 'READY FOR INTEREST CAPITALIZATION'
        WHEN EXISTS (
            SELECT 1 FROM Acct_Bal 
            WHERE Account_No = @account_number
        ) THEN 'HAS BALANCE BUT NOT FOR SYSTEM DATE (FALLBACK WILL WORK)'
        ELSE 'NO BALANCE RECORDS - NEEDS FIX'
    END AS Account_Status;

-- ============================================================================
-- END OF DIAGNOSTIC SCRIPT
-- ============================================================================

-- INSTRUCTIONS:
-- 1. Update @account_number variable with the failing account number
-- 2. Run all SELECT queries to diagnose the issue
-- 3. Based on results, uncomment and run appropriate FIX OPTION
-- 4. Run VERIFICATION QUERIES to confirm fix
-- 5. Test Interest Capitalization again in the application
