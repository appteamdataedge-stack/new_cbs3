-- V23: Multi-Currency Transaction Test Data
-- Date: 2025-11-23
-- Description: Insert test data for MCT functionality testing

-- =====================================================
-- STEP 1: Insert FX Rates for Testing
-- =====================================================

INSERT INTO fx_rate_master (Rate_Date, Ccy_Pair, Mid_Rate, Buying_Rate, Selling_Rate, Source, Uploaded_By)
VALUES
-- USD rates
(NOW(), 'USD/BDT', 110.5000, 110.0000, 111.0000, 'SYSTEM', 'ADMIN'),
-- EUR rates
(NOW(), 'EUR/BDT', 120.2500, 119.7500, 120.7500, 'SYSTEM', 'ADMIN'),
-- GBP rates
(NOW(), 'GBP/BDT', 135.7500, 135.2500, 136.2500, 'SYSTEM', 'ADMIN'),
-- JPY rates
(NOW(), 'JPY/BDT', 0.7800, 0.7750, 0.7850, 'SYSTEM', 'ADMIN')
ON DUPLICATE KEY UPDATE
    Mid_Rate = VALUES(Mid_Rate),
    Buying_Rate = VALUES(Buying_Rate),
    Selling_Rate = VALUES(Selling_Rate);

-- =====================================================
-- STEP 2: Initialize WAE Master Records
-- =====================================================

INSERT INTO wae_master (Ccy_Pair, WAE_Rate, FCY_Balance, LCY_Balance, Source_GL, Updated_On)
VALUES
('USD/BDT', 110.0000, 0.00, 0.00, '920101001', NOW()),
('EUR/BDT', 120.0000, 0.00, 0.00, '920102001', NOW()),
('GBP/BDT', 135.0000, 0.00, 0.00, '920103001', NOW()),
('JPY/BDT', 0.7500, 0.00, 0.00, '920104001', NOW())
ON DUPLICATE KEY UPDATE
    WAE_Rate = VALUES(WAE_Rate),
    Updated_On = NOW();

-- =====================================================
-- STEP 3: Create Test Customer for FCY Accounts
-- =====================================================

-- Check if test customer exists, if not create one
INSERT INTO cust_master (Cust_Id, Ext_Cust_Id, Cust_Type, First_Name, Last_Name, Trade_Name, Branch_Code, Maker_Id, Entry_Date, Entry_Time)
VALUES
(9999, 'FCY9999', 'Individual', 'FCY', 'Test Customer', 'FCY Test Customer', '001', 'SYSTEM', CURDATE(), CURTIME())
ON DUPLICATE KEY UPDATE
    Trade_Name = VALUES(Trade_Name);

-- =====================================================
-- STEP 4: Create FCY Customer Accounts
-- =====================================================

-- USD Account
INSERT INTO cust_acct_master
(Account_No, Sub_Product_Id, GL_Num, Account_Ccy, Cust_Id, Cust_Name, Acct_Name,
 Date_Opening, Branch_Code, Account_Status, Loan_Limit)
VALUES
('1000000099001', 41, '140101001', 'USD', 9999, 'FCY Test Customer', 'USD Savings Account',
 CURDATE(), '001', 'Active', 0.00)
ON DUPLICATE KEY UPDATE
    Account_Ccy = 'USD',
    Account_Status = 'Active';

-- EUR Account
INSERT INTO cust_acct_master
(Account_No, Sub_Product_Id, GL_Num, Account_Ccy, Cust_Id, Cust_Name, Acct_Name,
 Date_Opening, Branch_Code, Account_Status, Loan_Limit)
VALUES
('1000000099002', 41, '140101001', 'EUR', 9999, 'FCY Test Customer', 'EUR Savings Account',
 CURDATE(), '001', 'Active', 0.00)
ON DUPLICATE KEY UPDATE
    Account_Ccy = 'EUR',
    Account_Status = 'Active';

-- GBP Account
INSERT INTO cust_acct_master
(Account_No, Sub_Product_Id, GL_Num, Account_Ccy, Cust_Id, Cust_Name, Acct_Name,
 Date_Opening, Branch_Code, Account_Status, Loan_Limit)
VALUES
('1000000099003', 41, '140101001', 'GBP', 9999, 'FCY Test Customer', 'GBP Savings Account',
 CURDATE(), '001', 'Active', 0.00)
ON DUPLICATE KEY UPDATE
    Account_Ccy = 'GBP',
    Account_Status = 'Active';

-- =====================================================
-- STEP 5: Initialize Account Balances
-- =====================================================

-- USD Account Balance
INSERT INTO acct_bal
(Tran_Date, Account_No, Tran_Ccy, Opening_Bal, DR_Summation, CR_Summation,
 Closing_Bal, Current_Balance, Available_Balance, Last_Updated)
VALUES
(CURDATE(), '1000000099001', 'USD', 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, NOW())
ON DUPLICATE KEY UPDATE
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- EUR Account Balance
INSERT INTO acct_bal
(Tran_Date, Account_No, Tran_Ccy, Opening_Bal, DR_Summation, CR_Summation,
 Closing_Bal, Current_Balance, Available_Balance, Last_Updated)
VALUES
(CURDATE(), '1000000099002', 'EUR', 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, NOW())
ON DUPLICATE KEY UPDATE
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- GBP Account Balance
INSERT INTO acct_bal
(Tran_Date, Account_No, Tran_Ccy, Opening_Bal, DR_Summation, CR_Summation,
 Closing_Bal, Current_Balance, Available_Balance, Last_Updated)
VALUES
(CURDATE(), '1000000099003', 'GBP', 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, NOW())
ON DUPLICATE KEY UPDATE
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- =====================================================
-- STEP 6: Initialize Position GL Balances
-- =====================================================

-- Position USD GL Balance
INSERT INTO gl_balance
(GL_Num, Tran_date, Current_Balance, Opening_Bal, DR_Summation, CR_Summation,
 Closing_Bal, Last_Updated)
VALUES
('920101001', CURDATE(), 0.00, 0.00, 0.00, 0.00, 0.00, NOW())
ON DUPLICATE KEY UPDATE
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- Position EUR GL Balance
INSERT INTO gl_balance
(GL_Num, Tran_date, Current_Balance, Opening_Bal, DR_Summation, CR_Summation,
 Closing_Bal, Last_Updated)
VALUES
('920102001', CURDATE(), 0.00, 0.00, 0.00, 0.00, 0.00, NOW())
ON DUPLICATE KEY UPDATE
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- Position GBP GL Balance
INSERT INTO gl_balance
(GL_Num, Tran_date, Current_Balance, Opening_Bal, DR_Summation, CR_Summation,
 Closing_Bal, Last_Updated)
VALUES
('920103001', CURDATE(), 0.00, 0.00, 0.00, 0.00, 0.00, NOW())
ON DUPLICATE KEY UPDATE
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- Position JPY GL Balance
INSERT INTO gl_balance
(GL_Num, Tran_date, Current_Balance, Opening_Bal, DR_Summation, CR_Summation,
 Closing_Bal, Last_Updated)
VALUES
('920104001', CURDATE(), 0.00, 0.00, 0.00, 0.00, 0.00, NOW())
ON DUPLICATE KEY UPDATE
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- =====================================================
-- NOTE: Nostro and FX Gain/Loss GL accounts will be created
-- as needed during actual transaction processing.
-- They are not essential for basic MCT testing.
-- =====================================================

-- Test data setup completed
SELECT 'MCT Test Data Migration Completed Successfully' AS Status;
