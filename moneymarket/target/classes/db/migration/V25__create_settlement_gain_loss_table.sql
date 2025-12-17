-- ===================================================================
-- Migration V25: Create Settlement Gain/Loss Tracking Table
-- Purpose: Track all settlement gain/loss calculations for audit and reporting
-- Author: MCT Verification System
-- Date: 2025-11-27
-- ===================================================================

-- Create settlement_gain_loss table to track all settlement calculations
CREATE TABLE IF NOT EXISTS settlement_gain_loss (
    Settlement_Id BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- Transaction identification
    Tran_Id VARCHAR(20) NOT NULL COMMENT 'Base transaction ID (without -1, -2 suffix)',
    Tran_Date DATE NOT NULL COMMENT 'Transaction date',
    Value_Date DATE NOT NULL COMMENT 'Value date',

    -- Account and currency details
    Account_No VARCHAR(20) NOT NULL COMMENT 'Customer account number',
    Account_Name VARCHAR(100) COMMENT 'Customer account name',
    Currency VARCHAR(3) NOT NULL COMMENT 'Foreign currency code (USD, EUR, etc.)',

    -- Amount and rate details
    FCY_Amt DECIMAL(20, 2) NOT NULL COMMENT 'Foreign currency amount sold',
    Deal_Rate DECIMAL(10, 4) NOT NULL COMMENT 'Deal rate (customer rate)',
    WAE_Rate DECIMAL(10, 4) NOT NULL COMMENT 'WAE rate (weighted average exchange rate)',

    -- Settlement calculation
    Settlement_Amt DECIMAL(20, 2) NOT NULL COMMENT 'Settlement gain (+) or loss (-) amount in BDT',
    Settlement_Type VARCHAR(4) NOT NULL COMMENT 'GAIN or LOSS',

    -- GL account used for posting
    Settlement_GL VARCHAR(20) NOT NULL COMMENT 'GL account used (140203001 for GAIN, 240203001 for LOSS)',
    Position_GL VARCHAR(20) NOT NULL COMMENT 'Position GL account (920101001, 920102001, etc.)',

    -- Posting details
    Entry5_Tran_Id VARCHAR(20) COMMENT 'Transaction ID of entry 5',
    Entry6_Tran_Id VARCHAR(20) COMMENT 'Transaction ID of entry 6',
    Posted_By VARCHAR(20) NOT NULL COMMENT 'User who posted the transaction',
    Posted_On DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp when posted',

    -- Status and audit
    Status VARCHAR(20) NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED, REVERSED, or CANCELLED',
    Reversal_Id BIGINT COMMENT 'Reference to reversal settlement record if reversed',
    Reversed_On DATETIME COMMENT 'Timestamp when reversed',

    -- Additional info
    Narration VARCHAR(500) COMMENT 'Transaction narration',
    Created_On DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Last_Updated DATETIME ON UPDATE CURRENT_TIMESTAMP,

    -- Indexes for performance
    INDEX idx_settlement_tran_id (Tran_Id),
    INDEX idx_settlement_account (Account_No),
    INDEX idx_settlement_date (Tran_Date),
    INDEX idx_settlement_currency (Currency),
    INDEX idx_settlement_status (Status),
    INDEX idx_settlement_type (Settlement_Type),

    -- Constraints
    CONSTRAINT chk_settlement_type CHECK (Settlement_Type IN ('GAIN', 'LOSS')),
    CONSTRAINT chk_settlement_status CHECK (Status IN ('POSTED', 'REVERSED', 'CANCELLED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Tracks settlement gain/loss calculations for MCT SELL transactions';

-- Create revaluation summary table for daily revaluation tracking
CREATE TABLE IF NOT EXISTS reval_summary (
    Summary_Id BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- Revaluation date
    Reval_Date DATE NOT NULL UNIQUE COMMENT 'Revaluation date',

    -- Accounts revalued
    Total_Accounts INT NOT NULL DEFAULT 0 COMMENT 'Total number of accounts revalued',
    GL_Accounts INT NOT NULL DEFAULT 0 COMMENT 'Number of GL accounts (Nostro) revalued',
    Customer_Accounts INT NOT NULL DEFAULT 0 COMMENT 'Number of customer accounts revalued',

    -- Gain/Loss summary
    Total_Gain DECIMAL(20, 2) NOT NULL DEFAULT 0.00 COMMENT 'Total unrealized gain',
    Total_Loss DECIMAL(20, 2) NOT NULL DEFAULT 0.00 COMMENT 'Total unrealized loss (positive)',
    Net_Reval DECIMAL(20, 2) NOT NULL DEFAULT 0.00 COMMENT 'Net revaluation (gain - loss)',

    -- Processing details
    Start_Time DATETIME NOT NULL COMMENT 'When revaluation started',
    End_Time DATETIME COMMENT 'When revaluation completed',
    Duration_Seconds INT COMMENT 'Processing duration in seconds',

    -- Status
    Status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' COMMENT 'RUNNING, COMPLETED, FAILED, REVERSED',
    Error_Message TEXT COMMENT 'Error message if failed',

    -- BOD reversal tracking
    Reversed_On DATETIME COMMENT 'When BOD reversal was performed',
    Reversal_Duration_Seconds INT COMMENT 'BOD reversal duration',

    -- Audit
    Executed_By VARCHAR(20) NOT NULL COMMENT 'User/system who executed',
    Created_On DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Last_Updated DATETIME ON UPDATE CURRENT_TIMESTAMP,

    -- Indexes
    INDEX idx_reval_summary_date (Reval_Date),
    INDEX idx_reval_summary_status (Status),

    -- Constraints
    CONSTRAINT chk_reval_summary_status CHECK (Status IN ('RUNNING', 'COMPLETED', 'FAILED', 'REVERSED'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Daily summary of EOD revaluation process';

-- ===================================================================
-- End of Migration V25
-- ===================================================================
