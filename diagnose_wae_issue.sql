-- ══════════════════════════════════════════════════════════════════════════════
-- WAE Rate Issue Diagnostic Script
-- Purpose: Identify why WAE shows "N/A" after EOD Step 7 bypass
-- ══════════════════════════════════════════════════════════════════════════════

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 1: Check wae_master table (used for Position GL tracking)
-- ══════════════════════════════════════════════════════════════════════════════
SELECT 'WAE_MASTER' AS table_name, COUNT(*) AS row_count
FROM wae_master;

SELECT 
    Ccy_Pair,
    WAE_Rate,
    FCY_Balance,
    LCY_Balance,
    Source_GL,
    Updated_On
FROM wae_master
ORDER BY Updated_On DESC
LIMIT 10;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 2: Check acct_bal table (EOD balance snapshot - source for WAE calculation)
-- ══════════════════════════════════════════════════════════════════════════════
SELECT 'ACCT_BAL' AS table_name, COUNT(*) AS row_count
FROM acct_bal
WHERE Ccy_Code != 'BDT';

-- Check FCY account balances
SELECT 
    Account_No,
    Ccy_Code,
    Tran_Date,
    Opening_Bal,
    CR_Summation,
    DR_Summation,
    Closing_Bal,
    Current_Balance
FROM acct_bal
WHERE Ccy_Code != 'BDT'
ORDER BY Tran_Date DESC, Account_No
LIMIT 10;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 3: Check acct_bal_lcy table (LCY equivalent balances - needed for WAE)
-- ══════════════════════════════════════════════════════════════════════════════
SELECT 'ACCT_BAL_LCY' AS table_name, COUNT(*) AS row_count
FROM acct_bal_lcy
WHERE Ccy_Code != 'BDT';

-- Check FCY account LCY balances
SELECT 
    Account_No,
    Ccy_Code,
    Tran_Date,
    Opening_Bal_lcy,
    CR_Summation_lcy,
    DR_Summation_lcy,
    Closing_Bal_lcy
FROM acct_bal_lcy
WHERE Ccy_Code != 'BDT'
ORDER BY Tran_Date DESC, Account_No
LIMIT 10;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 4: Check if today's EOD Step 1 (acc_bal snapshot) has run
-- ══════════════════════════════════════════════════════════════════════════════
SELECT 
    'ACCT_BAL - Today' AS check_type,
    COUNT(*) AS record_count
FROM acct_bal
WHERE Tran_Date = CURDATE();

SELECT 
    'ACCT_BAL_LCY - Today' AS check_type,
    COUNT(*) AS record_count
FROM acct_bal_lcy
WHERE Tran_Date = CURDATE();

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 5: Test WAE calculation for a specific FCY account
-- ══════════════════════════════════════════════════════════════════════════════
-- Replace <FCY_ACCOUNT_NO> with an actual USD account number

-- Example: 120101011 (replace with your test account)
SET @test_account = '120101011';
SET @test_date = CURDATE();

SELECT 
    ab.Account_No,
    ab.Ccy_Code,
    ab.Tran_Date,
    ab.Opening_Bal AS fcy_opening,
    ab.CR_Summation AS fcy_cr,
    ab.DR_Summation AS fcy_dr,
    (ab.Opening_Bal + ab.CR_Summation - ab.DR_Summation) AS fcy_computed,
    abl.Opening_Bal_lcy AS lcy_opening,
    abl.CR_Summation_lcy AS lcy_cr,
    abl.DR_Summation_lcy AS lcy_dr,
    (abl.Opening_Bal_lcy + abl.CR_Summation_lcy - abl.DR_Summation_lcy) AS lcy_computed,
    CASE 
        WHEN (ab.Opening_Bal + ab.CR_Summation - ab.DR_Summation) = 0 THEN NULL
        ELSE ROUND((abl.Opening_Bal_lcy + abl.CR_Summation_lcy - abl.DR_Summation_lcy) / 
                   (ab.Opening_Bal + ab.CR_Summation - ab.DR_Summation), 4)
    END AS calculated_wae
FROM acct_bal ab
LEFT JOIN acct_bal_lcy abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_No = @test_account
ORDER BY ab.Tran_Date DESC
LIMIT 5;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 6: Check if EOD Step 1 log exists
-- ══════════════════════════════════════════════════════════════════════════════
SELECT 
    Job_Name,
    Job_Status,
    Records_Processed,
    Start_Time,
    End_Time,
    Error_Message
FROM eod_log_table
WHERE Job_Name LIKE '%Balance%' OR Job_Name LIKE '%EOD Step 1%'
ORDER BY Start_Time DESC
LIMIT 10;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 7: Check today's transactions for FCY accounts
-- ══════════════════════════════════════════════════════════════════════════════
SELECT 
    Account_No,
    Tran_Id,
    Tran_Date,
    Tran_Ccy,
    Dr_Cr_Flag,
    FCY_Amt,
    LCY_Amt,
    Exchange_Rate,
    Narration
FROM tran_table
WHERE Tran_Date = CURDATE()
AND Tran_Ccy != 'BDT'
ORDER BY Tran_Id;

-- ══════════════════════════════════════════════════════════════════════════════
-- ROOT CAUSE ANALYSIS
-- ══════════════════════════════════════════════════════════════════════════════

/*
EXPECTED RESULTS:

1. wae_master table: Should have rows for USD/BDT, EUR/BDT, etc.
   - This table tracks Position GL balances and is updated by BUY transactions
   - NOT used for customer account WAE display

2. acct_bal table: Should have records for today (CURDATE()) for FCY accounts
   - Created by EOD Step 1 (Balance Snapshot)
   - Contains Opening_Bal, CR_Summation, DR_Summation, Closing_Bal in FCY

3. acct_bal_lcy table: Should have matching records for today for FCY accounts
   - Created by EOD Step 1 (Balance Snapshot)
   - Contains Opening_Bal_lcy, CR_Summation_lcy, DR_Summation_lcy, Closing_Bal_lcy in BDT

4. WAE Calculation:
   - WAE = (Opening_Bal_lcy + CR_Summation_lcy - DR_Summation_lcy) / 
           (Opening_Bal + CR_Summation - DR_Summation)
   - If today's acct_bal record doesn't exist, falls back to latest record

POSSIBLE ROOT CAUSES:

A) EOD Step 1 has not run today
   - Fix: Run EOD Step 1 to create today's acct_bal/acct_bal_lcy records

B) acct_bal_lcy records are missing or have NULL values
   - Fix: Check EOD Step 1 logic to ensure it creates acct_bal_lcy records

C) Both Opening_Bal_lcy and computed balance are zero
   - Fix: Ensure first transaction creates proper opening balance

D) EOD Step 7 bypass removed something that was creating initial acct_bal records
   - Fix: Restore that specific logic (not the full revaluation)

*/

-- ══════════════════════════════════════════════════════════════════════════════
-- RECOMMENDED FIXES (based on findings)
-- ══════════════════════════════════════════════════════════════════════════════

/*
IF acct_bal/acct_bal_lcy records exist and have values:
  → WAE calculation should work
  → Check frontend/backend API call for errors

IF acct_bal/acct_bal_lcy records don't exist for today:
  → Run EOD Step 1 (Balance Snapshot)
  → Verify EOD Step 1 creates records for FCY accounts

IF acct_bal exists but acct_bal_lcy is missing:
  → Check EOD Step 1 logic for acct_bal_lcy creation
  → Ensure AccountBalanceUpdateService creates both records

IF all tables are empty:
  → System may be in initial state (no EOD run yet)
  → First transaction should trigger balance creation
  → Check AccountBalanceUpdateService.updateAccountBalance()
*/
