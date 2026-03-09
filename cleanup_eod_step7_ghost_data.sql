-- ══════════════════════════════════════════════════════════════════════════════
-- EOD Step 7 MCT Revaluation - Ghost Data Cleanup Script
-- Purpose: Remove all previously created revaluation entries from tran_table,
--          gl_movement, and reval_tran tables
-- ══════════════════════════════════════════════════════════════════════════════

-- IMPORTANT: Run this script AFTER bypassing the revaluation logic in code
-- This will clean up any ghost entries that were created before the bypass

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 1: View existing revaluation data (for backup/verification)
-- ══════════════════════════════════════════════════════════════════════════════

SELECT 'GL_MOVEMENT - Revaluation Entries' AS table_name, COUNT(*) AS count
FROM gl_movement
WHERE Tran_Id LIKE 'REVAL-%'
   OR Description LIKE '%EOD Revaluation%'
UNION ALL
SELECT 'TRAN_TABLE - Revaluation Entries', COUNT(*)
FROM tran_table
WHERE Tran_Id LIKE 'REVAL-%'
   OR Narration LIKE '%EOD Revaluation%'
UNION ALL
SELECT 'REVAL_TRAN - All Entries', COUNT(*)
FROM reval_tran;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 2: Delete revaluation entries from gl_movement
-- ══════════════════════════════════════════════════════════════════════════════

DELETE FROM gl_movement
WHERE Tran_Id LIKE 'REVAL-%'
   OR Description LIKE '%EOD Revaluation%';

SELECT ROW_COUNT() AS gl_movement_deleted;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 3: Delete revaluation entries from tran_table
-- ══════════════════════════════════════════════════════════════════════════════

DELETE FROM tran_table
WHERE Tran_Id LIKE 'REVAL-%'
   OR Narration LIKE '%EOD Revaluation%';

SELECT ROW_COUNT() AS tran_table_deleted;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 4: Delete ALL entries from reval_tran table
-- ══════════════════════════════════════════════════════════════════════════════

DELETE FROM reval_tran;

SELECT ROW_COUNT() AS reval_tran_deleted;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 5: Delete reversal entries (BOD reversals) if they exist
-- ══════════════════════════════════════════════════════════════════════════════

DELETE FROM gl_movement
WHERE Tran_Id LIKE 'REV-REVAL-%'
   OR Description LIKE '%BOD Reversal%EOD Revaluation%';

SELECT ROW_COUNT() AS gl_movement_reversals_deleted;

DELETE FROM tran_table
WHERE Tran_Id LIKE 'REV-REVAL-%'
   OR Narration LIKE '%BOD Reversal%EOD Revaluation%';

SELECT ROW_COUNT() AS tran_table_reversals_deleted;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 6: Reset EOD log for Step 7 (if using eod_log_table)
-- ══════════════════════════════════════════════════════════════════════════════
-- Adjust table and column names to match your actual EOD log table structure

-- Option A: If using eod_log_table
DELETE FROM eod_log_table
WHERE Job_Name = 'MCT Revaluation'
   OR Job_Name LIKE '%Revaluation%';

-- Option B: If using a different EOD log table structure, adjust accordingly:
-- UPDATE eod_job_log
-- SET Status = 'PENDING', Error_Message = NULL, Records_Processed = 0
-- WHERE Job_Number = 7;

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 7: Verify cleanup - All counts should be 0
-- ══════════════════════════════════════════════════════════════════════════════

SELECT 'GL_MOVEMENT - Remaining Revaluation Entries' AS verification, COUNT(*) AS count
FROM gl_movement
WHERE Tran_Id LIKE 'REVAL-%'
   OR Tran_Id LIKE 'REV-REVAL-%'
   OR Description LIKE '%EOD Revaluation%'
UNION ALL
SELECT 'TRAN_TABLE - Remaining Revaluation Entries', COUNT(*)
FROM tran_table
WHERE Tran_Id LIKE 'REVAL-%'
   OR Tran_Id LIKE 'REV-REVAL-%'
   OR Narration LIKE '%EOD Revaluation%'
UNION ALL
SELECT 'REVAL_TRAN - Remaining Entries', COUNT(*)
FROM reval_tran;

-- Expected output: All counts should be 0

-- ══════════════════════════════════════════════════════════════════════════════
-- STEP 8: Check GL balances that may have been affected by revaluation
-- ══════════════════════════════════════════════════════════════════════════════
-- These GL accounts may have incorrect balances from the ghost revaluation entries
-- You may need to manually adjust or recalculate these balances

SELECT 
    GL_Num,
    GL_Name,
    Dr_Balance,
    Cr_Balance,
    Net_Balance
FROM gl_balance
WHERE GL_Num IN (
    '220302001',  -- Nostro USD
    '220303001',  -- Nostro EUR
    '220304001',  -- Nostro GBP
    '220305001',  -- Nostro JPY
    '140203002',  -- Unrealised FX Gain GL
    '240203002'   -- Unrealised FX Loss GL
)
ORDER BY GL_Num;

-- ══════════════════════════════════════════════════════════════════════════════
-- OPTIONAL: Backup query before deletion (run this BEFORE the DELETE statements)
-- ══════════════════════════════════════════════════════════════════════════════

-- Uncomment and run this to create backup tables before deletion:

/*
CREATE TABLE gl_movement_backup_reval AS
SELECT * FROM gl_movement
WHERE Tran_Id LIKE 'REVAL-%' OR Description LIKE '%EOD Revaluation%';

CREATE TABLE tran_table_backup_reval AS
SELECT * FROM tran_table
WHERE Tran_Id LIKE 'REVAL-%' OR Narration LIKE '%EOD Revaluation%';

CREATE TABLE reval_tran_backup AS
SELECT * FROM reval_tran;
*/

-- ══════════════════════════════════════════════════════════════════════════════
-- END OF CLEANUP SCRIPT
-- ══════════════════════════════════════════════════════════════════════════════
