-- V20: Add Tran_Ccy column to acct_bal and acct_bal_accrual tables for multi-currency support
-- Date: 2025-11-20
-- Description: Adds transaction currency tracking to balance tables

-- =====================================================
-- STEP 1: Add Tran_Ccy column to acct_bal table
-- =====================================================

ALTER TABLE acct_bal
ADD COLUMN Tran_Ccy VARCHAR(3) DEFAULT 'BDT' AFTER Account_No;

-- =====================================================
-- STEP 2: Add Tran_Ccy column to acct_bal_accrual table
-- =====================================================

ALTER TABLE acct_bal_accrual
ADD COLUMN Tran_Ccy VARCHAR(3) DEFAULT 'BDT' AFTER Account_No;

-- =====================================================
-- STEP 3: Update acct_bal with currency from tran_table
-- =====================================================
-- For each account, get the most recent transaction currency
-- This assumes transactions for the same account use the same currency

UPDATE acct_bal ab
LEFT JOIN (
    SELECT
        Account_No,
        Tran_Ccy,
        Tran_Date,
        ROW_NUMBER() OVER (PARTITION BY Account_No ORDER BY Tran_Date DESC, Tran_Id DESC) as rn
    FROM tran_table
    WHERE Tran_Ccy IS NOT NULL
) tt ON ab.Account_No = tt.Account_No AND tt.rn = 1
SET ab.Tran_Ccy = COALESCE(tt.Tran_Ccy, 'BDT');

-- =====================================================
-- STEP 4: Update acct_bal_accrual with currency from intt_accr_tran
-- =====================================================
-- For each account, get the most recent accrual transaction currency

UPDATE acct_bal_accrual aba
LEFT JOIN (
    SELECT
        Account_No,
        Tran_Ccy,
        Tran_Date,
        ROW_NUMBER() OVER (PARTITION BY Account_No ORDER BY Tran_Date DESC, Accr_Tran_Id DESC) as rn
    FROM intt_accr_tran
    WHERE Tran_Ccy IS NOT NULL
) iat ON aba.Account_No = iat.Account_No AND iat.rn = 1
SET aba.Tran_Ccy = COALESCE(iat.Tran_Ccy, 'BDT');

-- =====================================================
-- STEP 5: Add NOT NULL constraint after populating data
-- =====================================================

ALTER TABLE acct_bal
MODIFY COLUMN Tran_Ccy VARCHAR(3) NOT NULL DEFAULT 'BDT';

ALTER TABLE acct_bal_accrual
MODIFY COLUMN Tran_Ccy VARCHAR(3) NOT NULL DEFAULT 'BDT';

-- =====================================================
-- STEP 6: Add index for performance
-- =====================================================

CREATE INDEX idx_acct_bal_tran_ccy ON acct_bal(Tran_Ccy);
CREATE INDEX idx_acct_bal_accrual_tran_ccy ON acct_bal_accrual(Tran_Ccy);

-- Migration completed successfully
