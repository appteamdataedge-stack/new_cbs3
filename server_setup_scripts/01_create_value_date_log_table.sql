-- ============================================================================
-- Script: Create Tran_Value_Date_Log Table
-- Purpose: Stores value dating information for transactions
-- Database: moneymarketdb
-- Date: 2025-11-09
-- ============================================================================

USE moneymarketdb;

-- Drop table if exists (optional - comment out if you want to preserve existing data)
-- DROP TABLE IF EXISTS Tran_Value_Date_Log;

-- Create Tran_Value_Date_Log table
CREATE TABLE IF NOT EXISTS Tran_Value_Date_Log (
    Log_Id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Unique identifier for each log entry',
    Tran_Id VARCHAR(20) NOT NULL COMMENT 'Transaction ID from Tran_Table',
    Value_Date DATE NOT NULL COMMENT 'The value date of the transaction',
    Days_Difference INT NOT NULL COMMENT 'Difference between value date and transaction date',
    Delta_Interest_Amt DECIMAL(20,4) DEFAULT 0.0000 COMMENT 'Interest adjustment amount for backdated transactions',
    Adjustment_Posted_Flag VARCHAR(1) DEFAULT 'N' COMMENT 'Y if adjustment has been posted, N otherwise',
    Created_Timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp when log entry was created',

    -- Indexes for performance
    INDEX idx_tran_id (Tran_Id) COMMENT 'Index on Transaction ID for quick lookup',
    INDEX idx_value_date (Value_Date) COMMENT 'Index on Value Date for date-range queries',
    INDEX idx_posted_flag (Adjustment_Posted_Flag) COMMENT 'Index for filtering posted/pending adjustments',
    INDEX idx_value_date_flag (Value_Date, Adjustment_Posted_Flag) COMMENT 'Composite index for BOD processing'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Logs all value dating operations and interest adjustments';

-- Verify table creation
SELECT 'Table Tran_Value_Date_Log created successfully!' AS Status;
DESCRIBE Tran_Value_Date_Log;
