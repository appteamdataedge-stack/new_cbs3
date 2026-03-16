-- V37: Add lcy_amt column to acct_bal_accrual table
-- This column stores the sum of LCY amounts from all pending accrual entries (S-prefix)
-- Used during capitalization to calculate the correct WAE rate

ALTER TABLE acct_bal_accrual
ADD COLUMN lcy_amt DECIMAL(20,2) DEFAULT 0.00 NOT NULL
AFTER closing_bal;

-- Update existing records to populate lcy_amt from intt_accr_tran
-- This is a one-time backfill for existing records
UPDATE acct_bal_accrual aba
LEFT JOIN (
    SELECT 
        account_no,
        tran_date,
        SUM(lcy_amt) as total_lcy
    FROM intt_accr_tran
    WHERE accr_tran_id LIKE 'S%'
    AND original_dr_cr_flag IS NULL
    GROUP BY account_no, tran_date
) iat ON aba.account_no = iat.account_no AND aba.tran_date = iat.tran_date
SET aba.lcy_amt = COALESCE(iat.total_lcy, 0.00);
