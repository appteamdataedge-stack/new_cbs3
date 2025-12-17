-- ============================================================================
-- Script: Verify Value Dating Setup
-- Purpose: Verify that all tables and parameters are correctly set up
-- Database: moneymarketdb
-- Date: 2025-11-09
-- ============================================================================

USE moneymarketdb;

-- ============================================================================
-- 1. Check if Tran_Value_Date_Log table exists
-- ============================================================================
SELECT
    TABLE_NAME,
    ENGINE,
    TABLE_ROWS,
    CREATE_TIME,
    TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'moneymarketdb'
AND TABLE_NAME = 'Tran_Value_Date_Log';

-- ============================================================================
-- 2. Show table structure
-- ============================================================================
DESCRIBE Tran_Value_Date_Log;

-- ============================================================================
-- 3. Show table indexes
-- ============================================================================
SHOW INDEXES FROM Tran_Value_Date_Log;

-- ============================================================================
-- 4. Check row count (should be 0 initially)
-- ============================================================================
SELECT COUNT(*) AS Total_Records FROM Tran_Value_Date_Log;

-- ============================================================================
-- 5. Verify value dating parameters
-- ============================================================================
SELECT
    Parameter_Name,
    Parameter_Value,
    Parameter_Description,
    Last_Updated,
    Updated_By
FROM Parameter_Table
WHERE Parameter_Name IN (
    'Past_Value_Date_Limit_Days',
    'Future_Value_Date_Limit_Days',
    'Interest_Default_Divisor',
    'Last_EOM_Date'
)
ORDER BY Parameter_Name;

-- ============================================================================
-- 6. Summary Report
-- ============================================================================
SELECT '========================================' AS '';
SELECT 'VALUE DATING SETUP VERIFICATION COMPLETE' AS '';
SELECT '========================================' AS '';
SELECT '' AS '';
SELECT 'Expected Results:' AS '';
SELECT '1. Tran_Value_Date_Log table should exist' AS '';
SELECT '2. Table should have 7 columns' AS '';
SELECT '3. Table should have 5 indexes (1 primary + 4 secondary)' AS '';
SELECT '4. Table should have 0 records initially' AS '';
SELECT '5. 4 parameters should be present in Parameter_Table' AS '';
SELECT '' AS '';
SELECT '========================================' AS '';
