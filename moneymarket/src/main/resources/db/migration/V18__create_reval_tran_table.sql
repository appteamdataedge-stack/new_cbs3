-- V18: Create Revaluation Transaction table
-- This table tracks EOD revaluation entries for FCY accounts
-- Entries are posted at EOD and reversed at BOD next day

CREATE TABLE `reval_tran` (
  `Reval_Id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `Reval_Date` DATE NOT NULL COMMENT 'Date of revaluation (EOD date)',
  `Acct_Num` VARCHAR(20) NOT NULL COMMENT 'FCY account being revalued',
  `Ccy_Code` VARCHAR(3) NOT NULL COMMENT 'Foreign currency code',
  `FCY_Balance` DECIMAL(20,2) NOT NULL COMMENT 'FCY balance at EOD',
  `Mid_Rate` DECIMAL(10,4) NOT NULL COMMENT 'Mid rate used for revaluation',
  `Booked_LCY` DECIMAL(20,2) NOT NULL COMMENT 'Booked LCY equivalent (cumulative)',
  `MTM_LCY` DECIMAL(20,2) NOT NULL COMMENT 'Mark-to-market LCY value',
  `Reval_Diff` DECIMAL(20,2) NOT NULL COMMENT 'Revaluation difference (MTM - Booked)',
  `Reval_GL` VARCHAR(20) NOT NULL COMMENT 'Revaluation GL account used',
  `Tran_Id` VARCHAR(20) NULL COMMENT 'Transaction ID in tran_table',
  `Reversal_Tran_Id` VARCHAR(20) NULL COMMENT 'BOD reversal transaction ID',
  `Status` VARCHAR(20) NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED or REVERSED',
  `Created_On` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `Reversed_On` DATETIME NULL COMMENT 'BOD reversal timestamp',
  INDEX `idx_reval_date` (`Reval_Date`),
  INDEX `idx_acct_num` (`Acct_Num`),
  INDEX `idx_status` (`Status`),
  INDEX `idx_tran_id` (`Tran_Id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Revaluation transactions for FCY accounts - EOD mark-to-market entries';
