-- V27__create_acct_bal_lcy_table.sql
-- Create Acct_Bal_LCY table - stores all balances in BDT (LCY) by converting foreign currency amounts

-- Purpose: Mirror of acct_bal table but ALL amounts in BDT
-- For BDT accounts: values same as acct_bal
-- For USD/FCY accounts: convert amounts to BDT using exchange rates from tran table
-- Use same conversion logic as tran table's lcy_amt column

CREATE TABLE Acct_Bal_LCY (
  Tran_Date DATE NOT NULL,
  Account_No VARCHAR(13) NOT NULL,
  Opening_Bal_lcy DECIMAL(20, 2) DEFAULT 0.00,
  DR_Summation_lcy DECIMAL(20, 2) DEFAULT 0.00,
  CR_Summation_lcy DECIMAL(20, 2) DEFAULT 0.00,
  Closing_Bal_lcy DECIMAL(20, 2) DEFAULT 0.00,
  Available_Balance_lcy DECIMAL(20, 2) NOT NULL DEFAULT 0.00,
  Last_Updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (Tran_Date, Account_No)
  -- Note: Foreign key constraint removed to avoid charset/collation compatibility issues
  -- Application-level referential integrity maintained by AccountBalanceUpdateService
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Index for efficient querying by account
CREATE INDEX idx_acct_bal_lcy_account ON Acct_Bal_LCY(Account_No, Tran_Date DESC);

-- Comments
ALTER TABLE Acct_Bal_LCY COMMENT = 'Account balances in Local Currency (BDT) - all FCY amounts converted to BDT';
