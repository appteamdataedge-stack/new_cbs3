-- =====================================================
-- Test Transactions with Value Date Gaps
-- Purpose: Test value date interest accrual functionality
-- =====================================================

USE moneymarketdb;

-- Test Transaction 1: 5-day gap
-- Account: 100000011001 (Liability account - 110101001)
-- Tran_Date: 2025-03-13, Value_Date: 2025-03-08 (5 days gap)
-- Amount: 100,000 BDT (Credit - deposit)
INSERT INTO Tran_Table (
  Tran_Id, Tran_Date, Value_Date, Dr_Cr_Flag, Tran_Status,
  Account_No, Tran_Ccy, FCY_Amt, Exchange_Rate, LCY_Amt,
  Narration, Pointing_Id
) VALUES (
  'T2025031300001', '2025-03-13', '2025-03-08', 'C', 'Verified',
  '100000011001', 'BDT', 100000.00, 1.0000, 100000.00,
  'Test deposit with 5-day value date gap', 1
);

-- Test Transaction 2: 3-day gap
-- Account: 100000013001 (Liability account - 110201002)
-- Tran_Date: 2025-03-13, Value_Date: 2025-03-10 (3 days gap)
-- Amount: 50,000 BDT (Credit - deposit)
INSERT INTO Tran_Table (
  Tran_Id, Tran_Date, Value_Date, Dr_Cr_Flag, Tran_Status,
  Account_No, Tran_Ccy, FCY_Amt, Exchange_Rate, LCY_Amt,
  Narration, Pointing_Id
) VALUES (
  'T2025031300002', '2025-03-13', '2025-03-10', 'C', 'Verified',
  '100000013001', 'BDT', 50000.00, 1.0000, 50000.00,
  'Test deposit with 3-day value date gap', 1
);

-- Test Transaction 3: No gap (control - should NOT generate value date interest)
-- Account: 100000011001
-- Tran_Date: 2025-03-13, Value_Date: 2025-03-13 (0 days gap)
-- Amount: 25,000 BDT (Debit - withdrawal)
INSERT INTO Tran_Table (
  Tran_Id, Tran_Date, Value_Date, Dr_Cr_Flag, Tran_Status,
  Account_No, Tran_Ccy, FCY_Amt, Exchange_Rate, LCY_Amt,
  Narration, Pointing_Id
) VALUES (
  'T2025031300003', '2025-03-13', '2025-03-13', 'D', 'Verified',
  '100000011001', 'BDT', 25000.00, 1.0000, 25000.00,
  'Test withdrawal with no value date gap', 1
);

-- Verify test transactions were created
SELECT
  Tran_Id,
  Account_No,
  Tran_Date,
  Value_Date,
  DATEDIFF(Tran_Date, Value_Date) AS Day_Gap,
  Dr_Cr_Flag,
  LCY_Amt,
  Tran_Status,
  Narration
FROM Tran_Table
WHERE Tran_Date = '2025-03-13'
ORDER BY Tran_Id;
