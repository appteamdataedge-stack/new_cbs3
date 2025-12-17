-- ============================================================
-- Test Script for Entry Transaction Deletion Logic
-- ============================================================
-- This script helps verify the Entry transaction deletion
-- logic implemented in Batch Job 1 (EOD Process)
-- ============================================================

-- STEP 1: Check current state before EOD
-- ============================================================

-- 1.1 Get system date
SELECT Parameter_Name, Parameter_Value
FROM Parameter_Table
WHERE Parameter_Name = 'System_Date';

-- 1.2 Count all transactions by status for current system date
SELECT
    Tran_Status,
    COUNT(*) as Transaction_Count,
    SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) as Total_Debits,
    SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) as Total_Credits
FROM tran_table
WHERE Tran_Date = (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date')
GROUP BY Tran_Status
ORDER BY Tran_Status;

-- 1.3 List all Entry status transactions for current system date
SELECT
    Tran_ID,
    Account_No,
    Tran_Date,
    Value_Date,
    Dr_Cr_Flag,
    LCY_Amt,
    Tran_Status,
    Tran_Desc
FROM tran_table
WHERE Tran_Date = (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date')
  AND Tran_Status = 'Entry'
ORDER BY Tran_ID;


-- ============================================================
-- STEP 2: Create test data (if needed)
-- ============================================================

-- 2.1 Create sample Entry status transactions for testing
-- Uncomment and modify these as needed for your test

/*
-- Sample Entry transaction 1: Debit customer account
INSERT INTO tran_table (
    Tran_ID, Account_No, Tran_Date, Value_Date,
    Dr_Cr_Flag, LCY_Amt, FCY_Amt, Tran_Ccy,
    Tran_Status, Tran_Desc, Created_By, Creation_Date
) VALUES (
    'T20251111000000001-1',
    '100000071001',
    (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date'),
    (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date'),
    'D',
    10000.00,
    10000.00,
    'BDT',
    'Entry',
    'Test Entry Transaction - Debit',
    'TEST_USER',
    GETDATE()
);

-- Sample Entry transaction 2: Credit customer account
INSERT INTO tran_table (
    Tran_ID, Account_No, Tran_Date, Value_Date,
    Dr_Cr_Flag, LCY_Amt, FCY_Amt, Tran_Ccy,
    Tran_Status, Tran_Desc, Created_By, Creation_Date
) VALUES (
    'T20251111000000002-1',
    '100000071001',
    (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date'),
    (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date'),
    'C',
    5000.00,
    5000.00,
    'BDT',
    'Entry',
    'Test Entry Transaction - Credit',
    'TEST_USER',
    GETDATE()
);
*/


-- ============================================================
-- STEP 3: After running Batch Job 1 - Verify Entry transactions deleted
-- ============================================================

-- 3.1 Verify NO Entry status transactions exist for the EOD date
SELECT
    COUNT(*) as Remaining_Entry_Count
FROM tran_table
WHERE Tran_Date = (
    -- Use Last_EOD_Date as the date that just completed EOD
    SELECT Parameter_Value
    FROM Parameter_Table
    WHERE Parameter_Name = 'Last_EOD_Date'
)
AND Tran_Status = 'Entry';
-- Expected: 0 (all Entry transactions should be deleted)

-- 3.2 Verify only Verified/Posted/Future transactions remain
SELECT
    Tran_Status,
    COUNT(*) as Transaction_Count
FROM tran_table
WHERE Tran_Date = (
    SELECT Parameter_Value
    FROM Parameter_Table
    WHERE Parameter_Name = 'Last_EOD_Date'
)
GROUP BY Tran_Status
ORDER BY Tran_Status;
-- Expected: Only Verified, Posted, or Future status transactions

-- 3.3 Check all Entry transactions across all dates (to ensure only current date was affected)
SELECT
    Tran_Date,
    COUNT(*) as Entry_Count
FROM tran_table
WHERE Tran_Status = 'Entry'
GROUP BY Tran_Date
ORDER BY Tran_Date DESC;
-- Expected: Entry transactions from other dates should still exist


-- ============================================================
-- STEP 4: Verify EOD Log Table for success
-- ============================================================

-- 4.1 Check latest Batch Job 1 execution log
SELECT TOP 5
    EOD_Date,
    Job_Name,
    Start_Timestamp,
    End_Timestamp,
    System_Date,
    User_ID,
    Records_Processed,
    Status,
    Error_Message,
    Failed_At_Step
FROM EOD_Log_Table
WHERE Job_Name = 'Account Balance Update'
ORDER BY Start_Timestamp DESC;
-- Expected: Status = 'Success', no Error_Message


-- ============================================================
-- STEP 5: Additional verification queries
-- ============================================================

-- 5.1 Get count of transactions by date and status (last 7 days)
SELECT
    Tran_Date,
    Tran_Status,
    COUNT(*) as Count,
    SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) as Total_Debits,
    SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) as Total_Credits
FROM tran_table
WHERE Tran_Date >= DATEADD(DAY, -7, (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date'))
GROUP BY Tran_Date, Tran_Status
ORDER BY Tran_Date DESC, Tran_Status;

-- 5.2 Verify transaction integrity (debits = credits for each date)
SELECT
    Tran_Date,
    SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) as Total_Debits,
    SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) as Total_Credits,
    SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) -
    SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) as Difference
FROM tran_table
WHERE Tran_Date >= DATEADD(DAY, -7, (SELECT Parameter_Value FROM Parameter_Table WHERE Parameter_Name = 'System_Date'))
GROUP BY Tran_Date
ORDER BY Tran_Date DESC;
-- Expected: Difference should be 0 for all dates


-- ============================================================
-- TEST CASE SCENARIOS
-- ============================================================

/*
Test Case 1: Single Entry Transaction - Verify Deletion
-------------------------------------------------------
Setup:
1. Create one Entry transaction for current system date
2. Run Batch Job 1 (EOD)

Expected Result:
- Entry transaction should be deleted
- No error in EOD_Log_Table
- Batch Job 1 completes successfully


Test Case 2: Multiple Entry Transactions - Verify All Deleted
-------------------------------------------------------------
Setup:
1. Create 5 Entry transactions for current system date
2. Create 3 Verified transactions for current system date
3. Run Batch Job 1 (EOD)

Expected Result:
- All 5 Entry transactions deleted
- 3 Verified transactions remain
- Count query returns 0 Entry transactions


Test Case 3: No Entry Transactions - Verify No Error
----------------------------------------------------
Setup:
1. Ensure no Entry transactions exist for current system date
2. Run Batch Job 1 (EOD)

Expected Result:
- Log shows "No Entry status transactions to delete"
- Batch Job 1 continues normally
- No errors


Test Case 4: Entry Transactions on Different Dates - Verify Only Current Date Deleted
-------------------------------------------------------------------------------------
Setup:
1. Create Entry transactions for yesterday
2. Create Entry transactions for current system date
3. Run Batch Job 1 (EOD)

Expected Result:
- Entry transactions for current date deleted
- Entry transactions for yesterday remain
- EOD only affects current system date


Test Case 5: Mixed Status Transactions - Verify Selective Deletion
------------------------------------------------------------------
Setup:
1. Create transactions with statuses: Entry, Verified, Posted, Future
2. Run Batch Job 1 (EOD)

Expected Result:
- Only Entry status transactions deleted
- Verified, Posted, Future transactions remain unchanged
*/
