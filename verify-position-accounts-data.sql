-- ========================================
-- Verify and Insert Position Account Data
-- For Trial Balance Report
-- ========================================

-- STEP 1: Check if Position accounts exist in gl_balance
SELECT 
    'Checking gl_balance for Position accounts...' as status;

SELECT 
    GL_Num, 
    Tran_date, 
    Opening_Bal, 
    DR_Summation, 
    CR_Summation, 
    Closing_Bal,
    Current_Balance
FROM gl_balance 
WHERE GL_Num IN ('920101001', '920101002')
ORDER BY GL_Num, Tran_date DESC;

-- If no rows returned above, Position accounts are MISSING from gl_balance

-- ========================================
-- STEP 2: Insert Position Accounts into gl_balance
-- ========================================

-- Insert Position BDT account (920101001)
INSERT INTO gl_balance (
    GL_Num, 
    Tran_date, 
    Opening_Bal, 
    DR_Summation, 
    CR_Summation, 
    Closing_Bal, 
    Current_Balance, 
    Last_Updated
)
VALUES (
    '920101001',           -- PSBDT EQIV (Position BDT)
    '2026-03-30',          -- Today's date (adjust as needed)
    112000.00,             -- Opening Balance
    0.00,                  -- DR Summation (will be updated by EOD)
    0.00,                  -- CR Summation (will be updated by EOD)
    112000.00,             -- Closing Balance
    112000.00,             -- Current Balance
    NOW()                  -- Last Updated
)
ON DUPLICATE KEY UPDATE 
    Opening_Bal = VALUES(Opening_Bal),
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- Insert Position USD account (920101002)
INSERT INTO gl_balance (
    GL_Num, 
    Tran_date, 
    Opening_Bal, 
    DR_Summation, 
    CR_Summation, 
    Closing_Bal, 
    Current_Balance, 
    Last_Updated
)
VALUES (
    '920101002',           -- PSUSD EQIV (Position FCY)
    '2026-03-30',          -- Today's date (adjust as needed)
    1000.00,               -- Opening Balance (in USD)
    0.00,                  -- DR Summation
    0.00,                  -- CR Summation
    1000.00,               -- Closing Balance
    1000.00,               -- Current Balance
    NOW()                  -- Last Updated
)
ON DUPLICATE KEY UPDATE 
    Opening_Bal = VALUES(Opening_Bal),
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- Verify inserted
SELECT 
    'Position accounts inserted/updated in gl_balance' as status;

SELECT 
    GL_Num, 
    Tran_date, 
    Opening_Bal, 
    Closing_Bal,
    Current_Balance
FROM gl_balance 
WHERE GL_Num IN ('920101001', '920101002')
ORDER BY GL_Num;

-- ========================================
-- STEP 3: Check if Position accounts exist in gl_setup
-- ========================================

SELECT 
    'Checking gl_setup for Position accounts...' as status;

SELECT 
    GL_Code, 
    GL_Name, 
    GL_Type, 
    status
FROM gl_setup 
WHERE GL_Code IN ('920101001', '920101002')
ORDER BY GL_Code;

-- If no rows returned above, Position accounts are MISSING from gl_setup

-- ========================================
-- STEP 4: Insert Position Accounts into gl_setup
-- ========================================

-- Insert Position BDT account setup
INSERT INTO gl_setup (
    GL_Code, 
    GL_Name, 
    GL_Type, 
    status
)
VALUES (
    '920101001',           -- GL Code
    'PSBDT EQIV',          -- GL Name (Position BDT Equivalent)
    'ASSET',               -- GL Type (Asset account)
    'ACTIVE'               -- Status
)
ON DUPLICATE KEY UPDATE 
    GL_Name = VALUES(GL_Name),
    GL_Type = VALUES(GL_Type),
    status = VALUES(status);

-- Insert Position FCY account setup
INSERT INTO gl_setup (
    GL_Code, 
    GL_Name, 
    GL_Type, 
    status
)
VALUES (
    '920101002',           -- GL Code
    'PSUSD EQIV',          -- GL Name (Position FCY Equivalent)
    'ASSET',               -- GL Type (Asset account)
    'ACTIVE'               -- Status
)
ON DUPLICATE KEY UPDATE 
    GL_Name = VALUES(GL_Name),
    GL_Type = VALUES(GL_Type),
    status = VALUES(status);

-- Verify inserted
SELECT 
    'Position accounts inserted/updated in gl_setup' as status;

SELECT 
    GL_Code, 
    GL_Name, 
    GL_Type, 
    status
FROM gl_setup 
WHERE GL_Code IN ('920101001', '920101002')
ORDER BY GL_Code;

-- ========================================
-- STEP 5: Final Verification
-- ========================================

-- Check complete setup (gl_setup + gl_balance)
SELECT 
    'Final verification - Position accounts in both tables:' as status;

SELECT 
    gs.GL_Code, 
    gs.GL_Name, 
    gs.GL_Type,
    gb.Tran_date,
    gb.Opening_Bal, 
    gb.Closing_Bal,
    'OK' as verification_status
FROM gl_setup gs
INNER JOIN gl_balance gb ON gs.GL_Code = gb.GL_Num
WHERE gs.GL_Code IN ('920101001', '920101002')
ORDER BY gs.GL_Code;

-- Expected output:
-- 920101001 | PSBDT EQIV  | ASSET | 2026-03-30 | 112000.00 | 112000.00 | OK
-- 920101002 | PSUSD EQIV  | ASSET | 2026-03-30 | 1000.00   | 1000.00   | OK

-- ========================================
-- STEP 6: Check FX Conversion Accounts (Should Also Exist)
-- ========================================

SELECT 
    'Checking FX Conversion accounts...' as status;

-- Check gl_balance
SELECT GL_Num, Tran_date, Opening_Bal, Closing_Bal 
FROM gl_balance 
WHERE GL_Num IN ('140203001', '240203001')
ORDER BY GL_Num;

-- Check gl_setup
SELECT GL_Code, GL_Name, GL_Type 
FROM gl_setup 
WHERE GL_Code IN ('140203001', '240203001')
ORDER BY GL_Code;

-- If FX accounts are also missing, insert them
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, status)
VALUES 
    ('140203001', 'Realised Forex Gain - FX Conversion', 'INCOME', 'ACTIVE'),
    ('240203001', 'Realised Forex Loss - FX Conversion', 'EXPENSE', 'ACTIVE')
ON DUPLICATE KEY UPDATE GL_Name = VALUES(GL_Name);

INSERT INTO gl_balance (GL_Num, Tran_date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, Current_Balance, Last_Updated)
VALUES 
    ('140203001', '2026-03-30', 0.00, 0.00, 1165.00, 1165.00, 1165.00, NOW()),
    ('240203001', '2026-03-30', 0.00, 1225.00, 0.00, 1225.00, 1225.00, NOW())
ON DUPLICATE KEY UPDATE Last_Updated = NOW();

-- ========================================
-- STEP 7: Final Count Check
-- ========================================

SELECT 
    'Total GL accounts in gl_balance for 2026-03-30:' as info,
    COUNT(DISTINCT GL_Num) as total_gl_accounts
FROM gl_balance 
WHERE Tran_date = '2026-03-30';

-- List all GL accounts
SELECT 
    GB.GL_Num as gl_code,
    GS.GL_Name as gl_name,
    GB.Opening_Bal,
    GB.Closing_Bal,
    CASE 
        WHEN GB.GL_Num IN ('920101001', '920101002') THEN '✓ Position Account'
        WHEN GB.GL_Num IN ('140203001', '240203001') THEN '✓ FX Conversion Account'
        WHEN GB.GL_Num LIKE '922030%' THEN '✓ Nostro Account'
        ELSE 'Regular GL Account'
    END as account_type
FROM gl_balance GB
LEFT JOIN gl_setup GS ON GB.GL_Num = GS.GL_Code
WHERE GB.Tran_date = '2026-03-30'
ORDER BY GB.GL_Num;

-- ========================================
-- SCRIPT COMPLETE
-- ========================================

SELECT 
    '✅ Verification complete!' as status,
    'Run Trial Balance report to verify Position accounts appear' as next_step;
