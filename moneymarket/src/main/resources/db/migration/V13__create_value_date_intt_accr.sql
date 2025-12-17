-- =====================================================
-- Value Date Interest Accrual Implementation
-- Migration: V13__create_value_date_intt_accr.sql
-- Description: Create table to capture interest on value date gaps
-- =====================================================

-- Create Value_Date_Intt_Accr table (same structure as Intt_Accr_Tran)
-- This table stores interest accrued when Tran_Date > Value_Date
CREATE TABLE IF NOT EXISTS Value_Date_Intt_Accr (
  Accr_Tran_Id VARCHAR(20) PRIMARY KEY,
  Account_No VARCHAR(13) NOT NULL,
  Accrual_Date DATE NOT NULL,
  Interest_Rate DECIMAL(10, 4) NOT NULL,
  Amount DECIMAL(20, 2) NOT NULL,
  Status ENUM('Pending', 'Posted', 'Verified') NOT NULL DEFAULT 'Pending',
  Tran_Date DATE NULL,
  Value_Date DATE NULL,
  Dr_Cr_Flag ENUM('D', 'C') NULL,
  Tran_Status ENUM('Entry', 'Posted', 'Verified', 'Future') NULL,
  GL_Account_No VARCHAR(20) NULL,
  Tran_Ccy VARCHAR(3) NULL,
  FCY_Amt DECIMAL(20, 2) NULL,
  Exchange_Rate DECIMAL(10, 4) NULL,
  LCY_Amt DECIMAL(20, 2) NULL,
  Narration VARCHAR(100) NULL,
  UDF1 VARCHAR(50) NULL,
  Tran_Id VARCHAR(20) NULL COMMENT 'Source transaction ID that triggered value date interest',
  Day_Gap INT NULL COMMENT 'Number of days between Value_Date and Tran_Date',
  FOREIGN KEY (Account_No) REFERENCES Cust_Acct_Master(Account_No)
);

-- Create indices for performance
CREATE INDEX idx_value_date_intt_accr_tran_date ON Value_Date_Intt_Accr(Tran_Date);
CREATE INDEX idx_value_date_intt_accr_account_date ON Value_Date_Intt_Accr(Account_No, Tran_Date);
CREATE INDEX idx_value_date_intt_accr_tran_id ON Value_Date_Intt_Accr(Tran_Id);
CREATE INDEX idx_value_date_intt_accr_accrual_date ON Value_Date_Intt_Accr(Accrual_Date);

-- =====================================================
-- Comments for documentation
-- =====================================================

-- Purpose: This table captures interest that should be accrued on transactions
-- where the Transaction Date is after the Value Date.
--
-- Example: If Value_Date = 2025-01-10 and Tran_Date = 2025-01-15 (5 days gap),
-- interest should be calculated on the transaction amount for those 5 days.
--
-- Processing:
-- - Batch Job 1: Inserts records when processing transactions where Tran_Date > Value_Date
-- - Batch Job 2: Aggregates these records and adds to regular interest accrual
--
-- Key Fields:
-- - Tran_Id: Links back to the source transaction that triggered this interest
-- - Day_Gap: Number of days for which interest is calculated
-- - Amount: The calculated interest amount for the gap period
-- - LCY_Amt: Original transaction amount used for calculation
-- =====================================================
