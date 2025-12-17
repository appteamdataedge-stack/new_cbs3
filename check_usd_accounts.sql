-- Check existing USD accounts in the database

-- Check Customer Accounts with USD GL
SELECT 'Customer USD Accounts:' AS Type;
SELECT Account_No, GL_Num, Cust_Name, Acct_Name, Account_Ccy, Account_Status
FROM Cust_Acct_Master
WHERE GL_Num LIKE '1102%' OR Account_Ccy = 'USD'
ORDER BY Account_No;

-- Check Office Accounts (Nostro)
SELECT 'Office USD Accounts (Nostro):' AS Type;
SELECT Account_No, GL_Num, Acct_Name, Account_Status
FROM OF_Acct_Master
WHERE GL_Num LIKE '2203%'
ORDER BY Account_No;

-- Check Position GL
SELECT 'Position GL Accounts:' AS Type;
SELECT Account_No, GL_Num, Acct_Name, Account_Status
FROM OF_Acct_Master
WHERE GL_Num LIKE '9201%'
ORDER BY Account_No;

-- Check GLs related to USD
SELECT 'USD-related GL Setup:' AS Type;
SELECT GL_Num, GL_Name, Layer_Id, Parent_GL_Num
FROM GL_setup
WHERE GL_Num IN ('110203001', '220302001', '920101001', '140203001', '140203002', '240203001', '240203002')
ORDER BY GL_Num;
