-- =====================================================
-- Multi-Currency Transaction (MCT) Verification Script
-- Date: 2025-11-24
-- Purpose: Verify MCT is working properly after backend restart
-- =====================================================

USE moneymarketdb;

-- =====================================================
-- STEP 1: Verify MCT Schema is in place
-- =====================================================
SHOW TABLES LIKE 'wae_master';
SHOW TABLES LIKE 'fx_rate_master';
SHOW TABLES LIKE 'revaluation%';

-- =====================================================
-- STEP 2: Check FX Rates are available
-- =====================================================
SELECT
    Ccy_Pair,
    Mid_Rate,
    Buying_Rate,
    Selling_Rate,
    Rate_Date
FROM fx_rate_master
ORDER BY Rate_Date DESC
LIMIT 10;

-- =====================================================
-- STEP 3: Check WAE Master initial state
-- =====================================================
SELECT
    WAE_Id,
    Ccy_Pair,
    WAE_Rate,
    FCY_Balance,
    LCY_Balance,
    Source_GL,
    Updated_On
FROM wae_master
ORDER BY Ccy_Pair;

-- =====================================================
-- STEP 4: Check Position GL accounts exist
-- =====================================================
SELECT
    GL_Num,
    GL_Name,
    Layer_No,
    Parent_GL
FROM gl_setup
WHERE GL_Num IN ('920101001', '920102001', '920103001', '920104001')
ORDER BY GL_Num;

-- =====================================================
-- STEP 5: Check FX Gain/Loss GL accounts exist
-- =====================================================
SELECT
    GL_Num,
    GL_Name,
    Layer_No
FROM gl_setup
WHERE GL_Num IN (
    '140203001', -- Realized FX Gain
    '140203002', -- Unrealized FX Gain
    '240203001', -- Realized FX Loss
    '240203002'  -- Unrealized FX Loss
)
ORDER BY GL_Num;

-- =====================================================
-- STEP 6: Check for existing Position GL movements (should be empty before testing)
-- =====================================================
SELECT
    Movement_ID,
    GL_Num,
    Tran_Date,
    Tran_Ccy,
    DR_CR_Flag,
    FCY_Amt,
    LCY_Amt
FROM gl_movement
WHERE GL_Num IN ('920101001', '920102001', '920103001', '920104001')
ORDER BY Movement_ID DESC
LIMIT 10;

-- =====================================================
-- STEP 7: Check test customer and accounts are available
-- =====================================================
SELECT
    Account_No,
    Account_Ccy,
    Cust_Name,
    Acct_Name,
    Account_Status
FROM cust_acct_master
WHERE Account_Ccy != 'BDT'
ORDER BY Account_No;

-- =====================================================
-- STEP 8: Check account balances for FCY accounts
-- =====================================================
SELECT
    Account_No,
    Tran_Ccy,
    Opening_Bal,
    Current_Balance,
    Available_Balance,
    Tran_Date
FROM acct_bal
WHERE Tran_Ccy != 'BDT'
ORDER BY Account_No;

-- =====================================================
-- NOTE: After creating a test USD transaction, re-run this script
-- to verify:
-- 1. Position GL entries appear in gl_movement for GL 920101001
-- 2. WAE Master FCY_Balance and LCY_Balance are updated
-- 3. WAE_Rate is recalculated based on the transaction
-- =====================================================
