-- ============================================================
-- Migration V14: Add Original_Dr_Cr_Flag to Interest Tables
-- ============================================================
-- This migration adds the Original_Dr_Cr_Flag column to store
-- the original transaction's Dr/Cr flag for value date interest.
-- This is used to correctly calculate balance impact in acct_bal_accrual.
-- ============================================================

-- Add Original_Dr_Cr_Flag to Value_Date_Intt_Accr table
ALTER TABLE Value_Date_Intt_Accr
ADD COLUMN Original_Dr_Cr_Flag ENUM('D', 'C') NULL
COMMENT 'Original transaction Dr/Cr flag from tran_table (for balance impact calculation)'
AFTER Dr_Cr_Flag;

-- Add Original_Dr_Cr_Flag to Intt_Accr_Tran table
ALTER TABLE Intt_Accr_Tran
ADD COLUMN Original_Dr_Cr_Flag ENUM('D', 'C') NULL
COMMENT 'Original transaction Dr/Cr flag from tran_table (for value date interest only)'
AFTER Dr_Cr_Flag;

-- Add index for performance on value date interest queries
CREATE INDEX idx_intt_accr_tran_original_flag
ON Intt_Accr_Tran(Account_No, Accrual_Date, Original_Dr_Cr_Flag);
