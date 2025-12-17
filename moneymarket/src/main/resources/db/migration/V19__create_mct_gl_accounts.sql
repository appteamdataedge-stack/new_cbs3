-- =====================================================
-- V19: Create GL Account Hierarchy for Multi-Currency Transactions
-- =====================================================
-- Note: Position USD accounts (920101000, 920101001) already exist - NOT re-inserting
-- Adapted to existing gl_setup table structure (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
-- FX Gain/Loss accounts based on correct structure: Realised/Un-Realised under Forex Gain/Loss

-- =====================================================
-- STEP 1: POSITION GL ACCOUNTS (Foreign Currency Position tracking)
-- =====================================================

-- Position EUR (Parent: 920102000, Child: 920102001)
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
VALUES
  ('Position EUR', 3, '02000', '920100000', '920102000'),
  ('PSEUR', 4, '001', '920102000', '920102001');

-- Position GBP (Parent: 920103000, Child: 920103001)
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
VALUES
  ('Position GBP', 3, '03000', '920100000', '920103000'),
  ('PSGBP', 4, '001', '920103000', '920103001');

-- Position JPY (Parent: 920104000, Child: 920104001)
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
VALUES
  ('Position JPY', 3, '04000', '920100000', '920104000'),
  ('PSJPY', 4, '001', '920104000', '920104001');

-- =====================================================
-- STEP 2: FOREX GAIN ACCOUNTS (Under Other Income - 140200000)
-- =====================================================
-- Note: 140200000 already exists, 140201000 (Mis Charges) exists, 140202000 next available
-- Using 140203000 for Forex Gain to avoid conflicts

-- Forex Gain as Layer 3 under 140200000
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
VALUES ('Forex Gain', 3, '03000', '140200000', '140203000');

-- Realised and Un-Realised as Layer 4
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
VALUES
  ('Realised Forex Gain', 4, '001', '140203000', '140203001'),
  ('Un-Realised Forex Gain', 4, '002', '140203000', '140203002');

-- =====================================================
-- STEP 3: FOREX LOSS ACCOUNTS (Under Other Expenditure - 240200000)
-- =====================================================
-- Note: 240200000 exists, 240201000 (Rent) exists, 240202000 (Misc Exp) exists
-- Using 240203000 for Forex Loss

-- Forex Loss as Layer 3 under 240200000
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
VALUES ('Forex Loss', 3, '03000', '240200000', '240203000');

-- Realised and Unrealised as Layer 4
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num)
VALUES
  ('Realised Forex Loss', 4, '001', '240203000', '240203001'),
  ('Unrealised Forex Loss', 4, '002', '240203000', '240203002');

-- =====================================================
-- STEP 5: Initialize GL Balances for new accounts
-- =====================================================

-- Position GL accounts (EUR, GBP, JPY)
INSERT INTO gl_balance (GL_Num, Tran_date, Opening_Bal, Closing_Bal, Current_Balance, Last_Updated)
VALUES
  ('920102001', CURDATE(), 0.00, 0.00, 0.00, NOW()),
  ('920103001', CURDATE(), 0.00, 0.00, 0.00, NOW()),
  ('920104001', CURDATE(), 0.00, 0.00, 0.00, NOW());

-- FX Gain/Loss accounts
INSERT INTO gl_balance (GL_Num, Tran_date, Opening_Bal, Closing_Bal, Current_Balance, Last_Updated)
VALUES
  ('140203001', CURDATE(), 0.00, 0.00, 0.00, NOW()),
  ('140203002', CURDATE(), 0.00, 0.00, 0.00, NOW()),
  ('240203001', CURDATE(), 0.00, 0.00, 0.00, NOW()),
  ('240203002', CURDATE(), 0.00, 0.00, 0.00, NOW());

-- =====================================================
-- STEP 4: Verification
-- =====================================================

-- Verify all FX accounts created
SELECT 'FX GL Accounts Created:' AS Status;
SELECT GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num
FROM gl_setup
WHERE GL_Num IN (
  '140203000', '140203001', '140203002',
  '240203000', '240203001', '240203002',
  '920102000', '920102001', '920103000', '920103001', '920104000', '920104001'
)
ORDER BY GL_Num;

SELECT 'Migration V19 completed successfully - Using FX GL Accounts: 140203xxx and 240203xxx' AS Status, NOW() AS Completion_Time;
