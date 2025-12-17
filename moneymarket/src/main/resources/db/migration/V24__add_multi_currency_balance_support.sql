-- V24: Add Multi-Currency Balance Support
-- Date: 2025-11-24
-- Description: Updates test accounts to USD currency
-- IMPORTANT: GL_Balance stays in BDT only. Only Acct_Bal supports multi-currency.
-- NOTE: Account_Ccy column already exists in both Acct_Bal and OF_Acct_Master tables

-- =====================================================
-- STEP 1: Update Account Currency for Specific Accounts
-- =====================================================

-- Update customer USD account in master table
UPDATE Cust_Acct_Master SET Account_Ccy = 'USD' WHERE Account_No = '100000008001';

-- Update ALL existing Acct_Bal records for customer USD account
UPDATE Acct_Bal SET Account_Ccy = 'USD' WHERE Account_No = '100000008001';

-- Update Nostro/Cash USD office account in master table
UPDATE OF_Acct_Master SET Account_Ccy = 'USD' WHERE Account_No = '922030200101';

-- Update ALL existing Acct_Bal records for Nostro USD account
UPDATE Acct_Bal SET Account_Ccy = 'USD' WHERE Account_No = '922030200101';

-- Create Acct_Bal record for office account if it doesn't exist for today
INSERT IGNORE INTO Acct_Bal (Tran_Date, Account_No, Account_Ccy, Current_Balance, Available_Balance, Last_Updated)
SELECT CURDATE(), '922030200101', 'USD', 0.00, 0.00, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM Acct_Bal WHERE Account_No = '922030200101' AND Tran_Date = CURDATE());

-- =====================================================
-- STEP 2: Ensure GL Balances Exist for USD GLs
-- =====================================================

-- GL_Balance always stores balances in BDT (LCY)
-- Ensure GL balance records exist for all USD-related GLs
INSERT IGNORE INTO GL_Balance (GL_Num, Current_Balance, Last_Updated)
SELECT GL_Num, 0.00, NOW()
FROM GL_setup
WHERE GL_Num IN (
    '110203001',  -- TD FCY USD (Customer Liability)
    '130103001',  -- Int Payable TD PIP FCY USD
    '220302001',  -- NOSTRO USD (Asset)
    '240103001',  -- Int Exp USD TD (Expense)
    '920101001',  -- Position USD
    '922030200',  -- Cash USD (if exists as GL)
    '140203001',  -- Realised Forex Gain
    '140203002',  -- Unrealised Forex Gain
    '240203001',  -- Realised Forex Loss
    '240203002'   -- Unrealised Forex Loss
);

-- Migration completed successfully
SELECT 'Multi-Currency Account Currency Updates Applied Successfully!' as Status;
SELECT 'GL_Balance remains in BDT only' as Note;
