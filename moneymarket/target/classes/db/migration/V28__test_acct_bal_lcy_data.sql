-- V28__test_acct_bal_lcy_data.sql
-- Test data for Acct_Bal_LCY table verification
-- This script demonstrates the relationship between acct_bal and acct_bal_lcy

-- NOTE: This is a test data script. Actual data will be populated by EOD Batch Job 1
-- The AccountBalanceUpdateService will automatically populate both tables during EOD

-- Example 1: BDT Account (200000023001)
-- For BDT accounts, acct_bal and acct_bal_lcy should have identical values

-- Example 2: USD Account (200000023003) 
-- For USD accounts:
-- acct_bal: amounts in USD
-- acct_bal_lcy: same amounts converted to BDT using exchange rate
--
-- Example:
-- If acct_bal shows: Opening=100.00 USD, CR=100.00 USD, Closing=200.00 USD
-- And exchange rate USD/BDT = 111.5
-- Then acct_bal_lcy shows: Opening=11150.00 BDT, CR=11150.00 BDT, Closing=22300.00 BDT

-- The conversion uses the same logic as tran table's lcy_amt column
-- Formula: LCY_Amt = FCY_Amt * Exchange_Rate

-- No test data inserted here - data will be populated automatically during EOD processing
SELECT 'Acct_Bal_LCY table created successfully. Data will be populated during EOD Batch Job 1.' AS Status;
