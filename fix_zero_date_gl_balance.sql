-- ========================================
-- FIX: Delete Invalid Zero-Date Records from gl_balance
-- Root Cause: GLs with Tran_Date = '0000-00-00' cause JDBC errors
-- ========================================

-- Check invalid records before deletion
SELECT 'Records to be deleted:' AS info;
SELECT GL_Num, CAST(Tran_Date AS CHAR) as Tran_Date, Opening_Bal, Closing_Bal, CAST(Last_Updated AS CHAR) as Last_Updated
FROM gl_balance
WHERE Tran_Date = '0000-00-00'
ORDER BY GL_Num;

-- Delete invalid zero-date records
DELETE FROM gl_balance WHERE Tran_Date = '0000-00-00';

-- Verify deletion
SELECT 'Remaining zero-date records (should be 0):' AS info;
SELECT COUNT(*) as remaining_count
FROM gl_balance
WHERE Tran_Date = '0000-00-00';

SELECT 'Fix complete!' AS status;
