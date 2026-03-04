-- EOD performance: add indexes for account balance lookups and parameter lookups.
-- Acct_Bal_LCY: V27 already has idx_acct_bal_lcy_account on (Account_No, Tran_Date DESC) — no change.

-- Acct_Bal: composite index for account + date (EOD Step 1 queries; V5 had only Tran_Date)
CREATE INDEX idx_acct_bal_account_tran_date ON Acct_Bal(Account_No, Tran_Date);

-- Parameter_Table: named index for Parameter_Name lookups (System_Date etc.)
CREATE INDEX idx_parameter_table_name ON Parameter_Table(Parameter_Name);
