-- Comprehensive MCT Verification Tests
-- =====================================

-- Test Setup: Show current state
SELECT '=== CURRENT DATABASE STATE ===' AS Info;
SELECT COUNT(*) AS 'GL Accounts Created' FROM gl_setup WHERE GL_Num IN ('140203001', '140203002', '240203001', '240203002', '920101001', '920102001', '920103001', '920104001');
SELECT COUNT(*) AS 'WAE Master Records' FROM wae_master;
SELECT COUNT(*) AS 'Reval Tran Records' FROM reval_tran;

-- Test MCT GL Balances
SELECT '\n=== MCT GL BALANCES ===' AS Info;
SELECT g.GL_Num, g.GL_Name, COALESCE(b.Current_Balance, 0) AS Balance
FROM gl_setup g
LEFT JOIN gl_balance b ON g.GL_Num = b.GL_Num
WHERE g.GL_Num IN ('140203001', '140203002', '240203001', '240203002', '920101001', '920102001', '920103001', '920104001')
ORDER BY g.GL_Num;

-- Check if any USD accounts exist
SELECT '\n=== USD ACCOUNTS CHECK ===' AS Info;
SELECT COUNT(*) AS 'Customer USD Accounts' 
FROM Cust_Acct_Master 
WHERE Account_No LIKE '%USD%' OR GL_Num LIKE '%USD%';

-- Show Position GL hierarchy
SELECT '\n=== POSITION GL HIERARCHY ===' AS Info;
SELECT GL_Num, GL_Name, Layer_Id, Parent_GL_Num
FROM gl_setup
WHERE GL_Num LIKE '9201%'
ORDER BY GL_Num;

-- Verify account number format/patterns
SELECT '\n=== SAMPLE ACCOUNTS ===' AS Info;
SELECT Account_No, Acct_Name, GL_Num, Account_Status
FROM Cust_Acct_Master
LIMIT 5;
