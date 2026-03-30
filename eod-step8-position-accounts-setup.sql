-- ============================================
-- EOD STEP 8 TRIAL BALANCE - POSITION ACCOUNTS
-- Data Verification and Setup Script
-- ============================================

-- ====================
-- STEP 1: VERIFY DATA
-- ====================

-- 1.1 Check Position accounts in gl_balance
SELECT 
    'gl_balance' AS source_table,
    gl_code,
    ccy,
    Opening_Bal,
    DR_Summation,
    CR_Summation,
    Closing_Bal,
    balance,
    Tran_Date,
    last_updated
FROM gl_balance
WHERE gl_code IN ('920101001', '920101002')
ORDER BY gl_code, ccy;

-- Expected: At least 2 rows
-- 920101001 | BDT | 112000.00 | 0.00 | 0.00 | 112000.00 | ...
-- 920101002 | USD | 1000.00   | 0.00 | 0.00 | 1000.00   | ...

-- 1.2 Check GL names in gl_setup
SELECT 
    'gl_setup' AS source_table,
    GL_Code,
    GL_Name,
    GL_Type,
    CCY,
    Status
FROM gl_setup
WHERE GL_Code IN ('920101001', '920101002');

-- Expected: 2 rows
-- 920101001 | PSBDT EQIV | LIABILITY | BDT | Active
-- 920101002 | PSUSD EQIV | LIABILITY | USD | Active

-- 1.3 Check if Position accounts are in active GL list
SELECT 
    'gl_setup with accounts' AS source,
    gs.GL_Code,
    gs.GL_Name,
    COUNT(DISTINCT cam.account_num) AS customer_accounts,
    COUNT(DISTINCT oam.account_num) AS office_accounts
FROM gl_setup gs
LEFT JOIN sub_prod_master spm ON gs.GL_Code = spm.gl_code
LEFT JOIN cust_acct_master cam ON spm.sub_prod_id = cam.sub_prod_id
LEFT JOIN of_acct_master oam ON spm.sub_prod_id = oam.sub_prod_id
WHERE gs.GL_Code IN ('920101001', '920101002')
GROUP BY gs.GL_Code, gs.GL_Name;

-- Expected: 0 customer accounts, 0 office accounts
-- This is why Position accounts were being filtered out!

-- ====================
-- STEP 2: INSERT DATA (IF MISSING)
-- ====================

-- 2.1 Insert/Update Position accounts in gl_setup
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, CCY, Status, Last_Updated)
VALUES 
('920101001', 'PSBDT EQIV', 'LIABILITY', 'BDT', 'Active', NOW()),
('920101002', 'PSUSD EQIV', 'LIABILITY', 'USD', 'Active', NOW())
ON DUPLICATE KEY UPDATE 
    GL_Name = VALUES(GL_Name),
    GL_Type = VALUES(GL_Type),
    Last_Updated = NOW();

-- 2.2 Insert/Update Position account balances in gl_balance
INSERT INTO gl_balance (gl_code, ccy, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, balance, Tran_Date, last_updated)
VALUES 
-- Position BDT Equivalent (sum of all FCY converted to BDT)
('920101001', 'BDT', 112000.00, 0.00, 0.00, 112000.00, 112000.00, CURDATE(), NOW()),

-- Position USD Inventory
('920101002', 'USD', 1000.00, 0.00, 0.00, 1000.00, 1000.00, CURDATE(), NOW()),

-- Position EUR Inventory
('920101002', 'EUR', 500.00, 0.00, 0.00, 500.00, 500.00, CURDATE(), NOW()),

-- Position GBP Inventory
('920101002', 'GBP', 300.00, 0.00, 0.00, 300.00, 300.00, CURDATE(), NOW())

ON DUPLICATE KEY UPDATE 
    Opening_Bal = VALUES(Opening_Bal),
    balance = VALUES(balance),
    Closing_Bal = VALUES(Closing_Bal),
    last_updated = NOW();

-- ====================
-- STEP 3: VERIFY FIX
-- ====================

-- 3.1 Verify Position accounts are now in gl_balance
SELECT 
    gl_code,
    ccy,
    Opening_Bal,
    Closing_Bal,
    balance,
    Tran_Date
FROM gl_balance
WHERE gl_code IN ('920101001', '920101002')
ORDER BY gl_code, ccy;

-- Expected output:
-- 920101001 | BDT | 112000.00 | 112000.00 | 112000.00 | 2026-03-30
-- 920101002 | USD | 1000.00   | 1000.00   | 1000.00   | 2026-03-30
-- 920101002 | EUR | 500.00    | 500.00    | 500.00    | 2026-03-30
-- 920101002 | GBP | 300.00    | 300.00    | 300.00    | 2026-03-30

-- 3.2 Verify GL names
SELECT 
    GL_Code,
    GL_Name,
    GL_Type,
    Status
FROM gl_setup
WHERE GL_Code IN ('920101001', '920101002')
ORDER BY GL_Code;

-- Expected output:
-- 920101001 | PSBDT EQIV | LIABILITY | Active
-- 920101002 | PSUSD EQIV | LIABILITY | Active

-- ====================
-- STEP 4: TEST QUERY USED BY SERVICE
-- ====================

-- This is a simplified version of what the service does
-- Test to ensure Position accounts would be fetched

SET @report_date = CURDATE();

-- Check if active GL query includes Position accounts
SELECT 
    gs.GL_Code,
    gs.GL_Name,
    COUNT(DISTINCT cam.account_num) AS linked_accounts
FROM gl_setup gs
LEFT JOIN sub_prod_master spm ON gs.GL_Code = spm.gl_code
LEFT JOIN cust_acct_master cam ON spm.sub_prod_id = cam.sub_prod_id
WHERE gs.Status = 'Active'
GROUP BY gs.GL_Code, gs.GL_Name
HAVING linked_accounts > 0
ORDER BY gs.GL_Code;

-- Position accounts (920101001, 920101002) should NOT be in this list
-- Because they have 0 linked accounts
-- That's why ensurePositionGLsPresent() is needed!

-- ====================
-- STEP 5: VERIFY FX CONVERSION ACCOUNTS
-- ====================

-- Make sure FX Conversion accounts also exist
SELECT 
    gl_code,
    ccy,
    Opening_Bal,
    Closing_Bal,
    balance
FROM gl_balance
WHERE gl_code IN ('140203001', '140203002', '240203001', '240203002')
ORDER BY gl_code;

-- Expected: At least 4 rows with BDT currency

-- ====================
-- SUCCESS CRITERIA
-- ====================

-- After running this script, verify:
-- ✓ gl_balance has 4+ rows for Position accounts (920101001-BDT, 920101002-USD/EUR/GBP)
-- ✓ gl_setup has 2 rows for Position accounts with names
-- ✓ FX Conversion accounts (140203001, 140203002, 240203001, 240203002) exist
-- ✓ Position accounts show 0 linked accounts (this is expected and correct)

-- ====================
-- TROUBLESHOOTING
-- ====================

-- If Position accounts still not showing after backend restart:

-- Check table structure (column name may be GL_Num instead of gl_code)
SHOW COLUMNS FROM gl_balance LIKE '%gl%';

-- Check if data exists with different column name
SELECT * FROM gl_balance WHERE GL_Num IN ('920101001', '920101002') LIMIT 5;

-- Check repository method is working
-- (This should be verified in backend logs after restart)
