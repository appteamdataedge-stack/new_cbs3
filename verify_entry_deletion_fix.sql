-- ============================================================
-- Verification Script: Entry Transaction Deletion Fix
-- ============================================================
-- This script verifies the fix for Entry transaction deletion in Batch Job 1
-- Before the fix: Only Entry transactions for the current system date were deleted
-- After the fix: ALL Entry transactions (regardless of date) are deleted
-- ============================================================

-- 1. Show current system date
-- ============================================================
SELECT
    Parameter_Name,
    Parameter_Value as Current_System_Date,
    Last_Updated,
    Updated_By
FROM Parameter_Table
WHERE Parameter_Name = 'System_Date';

-- 2. Count ALL Entry transactions in the system
-- ============================================================
SELECT
    COUNT(*) as Total_Entry_Transactions
FROM Tran_Table
WHERE Tran_Status = 'Entry';

-- 3. Show Entry transactions grouped by date
-- ============================================================
SELECT
    Tran_Date,
    COUNT(*) as Entry_Count,
    SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END) as Total_Debit,
    SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END) as Total_Credit
FROM Tran_Table
WHERE Tran_Status = 'Entry'
GROUP BY Tran_Date
ORDER BY Tran_Date;

-- 4. Show all Entry transaction details
-- ============================================================
SELECT
    Tran_ID,
    Tran_Date,
    Account_No,
    Dr_Cr_Flag,
    LCY_Amt,
    Tran_Status,
    Narration
FROM Tran_Table
WHERE Tran_Status = 'Entry'
ORDER BY Tran_Date, Tran_ID;

-- ============================================================
-- Expected Behavior After Running EOD Batch Job 1:
-- ============================================================
-- BEFORE FIX: Only Entry transactions for system date (2025-03-23) would be deleted
--             8 transactions from older dates would remain
--
-- AFTER FIX: ALL 10 Entry transactions will be deleted
--            This includes transactions from:
--            - 2025-02-19 (4 transactions)
--            - 2025-02-22 (2 transactions)
--            - 2025-03-13 (2 transactions)
--            - 2025-03-23 (2 transactions)
-- ============================================================

-- 5. Verification query to run AFTER EOD Batch Job 1
-- ============================================================
-- Run this query after executing EOD to verify all Entry transactions are deleted
-- Expected result: 0 rows
SELECT
    COUNT(*) as Remaining_Entry_Transactions
FROM Tran_Table
WHERE Tran_Status = 'Entry';

-- If the above query returns 0, the fix is working correctly!
