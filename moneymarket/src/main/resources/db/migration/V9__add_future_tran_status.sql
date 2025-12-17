-- ========================================
-- Migration V9: Add 'Future' to Tran_Status Enum
-- ========================================
-- Purpose: Support future-dated transactions in Value Date feature
-- PTTP05: Value Dated Transactions
-- Date: 2025-11-10
-- Author: System Migration
-- ========================================

-- Add 'Future' status to Tran_Status enum
ALTER TABLE Tran_Table
MODIFY COLUMN Tran_Status ENUM('Entry', 'Posted', 'Verified', 'Future') NOT NULL
DEFAULT 'Entry'
COMMENT 'Transaction status: Entry=draft, Posted=active, Verified=confirmed, Future=scheduled';

-- Add composite index for BOD queries (find future transactions by date)
-- Check if index exists first
SET @index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'moneymarketdb'
    AND TABLE_NAME = 'Tran_Table'
    AND INDEX_NAME = 'idx_tran_status_value_date'
);

SET @create_index_sql = IF(@index_exists > 0,
    'SELECT "Index idx_tran_status_value_date already exists, skipping creation" AS Info',
    'CREATE INDEX idx_tran_status_value_date ON Tran_Table(Tran_Status, Value_Date) COMMENT ''Optimize BOD queries for future-dated transactions'''
);

PREPARE stmt FROM @create_index_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Verify the enum was updated correctly
SELECT
    'Migration V9 Verification' AS Check_Type,
    COLUMN_TYPE AS Current_Enum,
    CASE
        WHEN COLUMN_TYPE LIKE '%Future%' THEN '✓ SUCCESS'
        ELSE '✗ FAILED'
    END AS Status
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'Tran_Table'
  AND COLUMN_NAME = 'Tran_Status';

-- Log completion
SELECT 'Migration V9 completed successfully' AS Status,
       NOW() AS Completion_Time;
