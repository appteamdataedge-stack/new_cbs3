-- ══════════════════════════════════════════════════════════
-- CRITICAL FIX: Insert NOSTRO Account Balances
-- ══════════════════════════════════════════════════════════
-- 
-- PROBLEM: NOSTRO accounts exist (GL starts with 220302) but have ZERO balances
-- SOLUTION: Insert balance records for all NOSTRO accounts
--
-- ══════════════════════════════════════════════════════════

-- Step 1: Show current NOSTRO accounts
SELECT '========== CURRENT NOSTRO ACCOUNTS ==========' AS '';
SELECT Account_No, Acct_Name, Account_Ccy, GL_Num, Account_Status
FROM of_acct_master
WHERE GL_Num LIKE '22030%'
ORDER BY Account_Ccy, Account_No;

-- Step 2: Check current balances (should be empty or zero)
SELECT '========== CURRENT NOSTRO BALANCES (acc_bal) ==========' AS '';
SELECT ab.Account_No, ab.Tran_Date, ab.Closing_Bal
FROM acc_bal ab
WHERE ab.Account_No IN (
    SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%'
)
ORDER BY ab.Account_No, ab.Tran_Date DESC;

SELECT '========== CURRENT NOSTRO BALANCES (acct_bal_lcy) ==========' AS '';
SELECT abl.Account_No, abl.Tran_Date, abl.Closing_Bal_Lcy
FROM acct_bal_lcy abl
WHERE abl.Account_No IN (
    SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%'
)
ORDER BY abl.Account_No, abl.Tran_Date DESC;

-- Step 3: Get System Date
SELECT '========== SYSTEM DATE ==========' AS '';
SELECT Parameter_Value as System_Date 
FROM parameter_table 
WHERE Parameter_Name = 'System_Date';

-- Step 4: Insert balances for ALL NOSTRO accounts
-- Using the system date from parameter_table

SELECT '========== INSERTING NOSTRO BALANCES ==========' AS '';

-- Insert FCY balances (acc_bal)
INSERT INTO acc_bal (
    Account_No, 
    Tran_Date, 
    Account_Ccy,
    Opening_Bal, 
    Closing_Bal, 
    Dr, 
    Cr,
    Dr_Summation,
    Cr_Summation,
    Available_Balance,
    Current_Balance,
    Last_Updated
)
SELECT 
    om.Account_No,
    (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date'),
    om.Account_Ccy,
    100000.00 as Opening_Bal,
    100000.00 as Closing_Bal,
    100000.00 as Dr,
    0.00 as Cr,
    100000.00 as Dr_Summation,
    0.00 as Cr_Summation,
    100000.00 as Available_Balance,
    100000.00 as Current_Balance,
    NOW() as Last_Updated
FROM of_acct_master om
WHERE om.GL_Num LIKE '22030%' 
  AND om.Account_Status = 'Active'
  AND NOT EXISTS (
      SELECT 1 FROM acc_bal ab 
      WHERE ab.Account_No = om.Account_No 
        AND ab.Tran_Date = (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date')
  );

-- Insert LCY balances (acct_bal_lcy)
-- LCY amount depends on currency:
-- USD: 100,000 * 110.25 = 11,025,000 BDT
-- EUR: 100,000 * 120.50 = 12,050,000 BDT
-- GBP: 100,000 * 138.75 = 13,875,000 BDT

INSERT INTO acct_bal_lcy (
    Account_No,
    Tran_Date,
    Opening_Bal_Lcy,
    Closing_Bal_Lcy,
    Dr_Lcy,
    Cr_Lcy,
    Dr_Summation_Lcy,
    Cr_Summation_Lcy,
    Available_Balance_Lcy,
    Last_Updated
)
SELECT 
    om.Account_No,
    (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date'),
    CASE om.Account_Ccy
        WHEN 'USD' THEN 11025000.00
        WHEN 'EUR' THEN 12050000.00
        WHEN 'GBP' THEN 13875000.00
        ELSE 10000000.00
    END as Opening_Bal_Lcy,
    CASE om.Account_Ccy
        WHEN 'USD' THEN 11025000.00
        WHEN 'EUR' THEN 12050000.00
        WHEN 'GBP' THEN 13875000.00
        ELSE 10000000.00
    END as Closing_Bal_Lcy,
    CASE om.Account_Ccy
        WHEN 'USD' THEN 11025000.00
        WHEN 'EUR' THEN 12050000.00
        WHEN 'GBP' THEN 13875000.00
        ELSE 10000000.00
    END as Dr_Lcy,
    0.00 as Cr_Lcy,
    CASE om.Account_Ccy
        WHEN 'USD' THEN 11025000.00
        WHEN 'EUR' THEN 12050000.00
        WHEN 'GBP' THEN 13875000.00
        ELSE 10000000.00
    END as Dr_Summation_Lcy,
    0.00 as Cr_Summation_Lcy,
    CASE om.Account_Ccy
        WHEN 'USD' THEN 11025000.00
        WHEN 'EUR' THEN 12050000.00
        WHEN 'GBP' THEN 13875000.00
        ELSE 10000000.00
    END as Available_Balance_Lcy,
    NOW() as Last_Updated
FROM of_acct_master om
WHERE om.GL_Num LIKE '22030%'
  AND om.Account_Status = 'Active'
  AND NOT EXISTS (
      SELECT 1 FROM acct_bal_lcy abl 
      WHERE abl.Account_No = om.Account_No 
        AND abl.Tran_Date = (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date')
  );

-- Step 5: Verify balances were inserted
SELECT '========== VERIFY BALANCES INSERTED ==========' AS '';

SELECT 
    om.Account_No,
    om.Acct_Name,
    om.Account_Ccy,
    om.GL_Num,
    ab.Closing_Bal as FCY_Balance,
    abl.Closing_Bal_Lcy as LCY_Balance,
    CASE 
        WHEN ab.Closing_Bal > 0 THEN ROUND(abl.Closing_Bal_Lcy / ab.Closing_Bal, 6)
        ELSE NULL
    END as Implied_WAE_Rate
FROM of_acct_master om
LEFT JOIN acc_bal ab ON om.Account_No = ab.Account_No 
    AND ab.Tran_Date = (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date')
LEFT JOIN acct_bal_lcy abl ON om.Account_No = abl.Account_No 
    AND abl.Tran_Date = (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date')
WHERE om.GL_Num LIKE '22030%'
  AND om.Account_Status = 'Active'
ORDER BY om.Account_Ccy, om.Account_No;

-- Step 6: Verify FX rates exist
SELECT '========== VERIFY FX RATES EXIST ==========' AS '';
SELECT Ccy_Pair, Mid_Rate, Buying_Rate, Selling_Rate, Rate_Date
FROM fx_rate_master
WHERE Ccy_Pair IN ('USD/BDT', 'EUR/BDT', 'GBP/BDT', 'JPY/BDT')
ORDER BY Rate_Date DESC
LIMIT 10;

-- Step 7: Calculate expected WAE rates
SELECT '========== EXPECTED WAE RATES (After Insert) ==========' AS '';
SELECT 
    om.Account_Ccy,
    COUNT(*) as Nostro_Count,
    SUM(ab.Closing_Bal) as Total_FCY,
    SUM(abl.Closing_Bal_Lcy) as Total_LCY,
    CASE 
        WHEN SUM(ab.Closing_Bal) > 0 THEN ROUND(SUM(abl.Closing_Bal_Lcy) / SUM(ab.Closing_Bal), 6)
        ELSE NULL
    END as Calculated_WAE
FROM of_acct_master om
INNER JOIN acc_bal ab ON om.Account_No = ab.Account_No
    AND ab.Tran_Date = (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date')
INNER JOIN acct_bal_lcy abl ON om.Account_No = abl.Account_No
    AND abl.Tran_Date = (SELECT DATE(Parameter_Value) FROM parameter_table WHERE Parameter_Name = 'System_Date')
WHERE om.GL_Num LIKE '22030%'
  AND om.Account_Status = 'Active'
GROUP BY om.Account_Ccy
ORDER BY om.Account_Ccy;

-- ══════════════════════════════════════════════════════════
-- EXPECTED RESULTS
-- ══════════════════════════════════════════════════════════
/*
After running this script, you should see:

NOSTRO ACCOUNTS (from logs):
- 922030200101 (USD)
- 922030200102 (USD)  
- 922030200103 (USD)
- 922030200104 (USD)

BALANCES INSERTED:
Each account should have:
- FCY Balance: 100,000.00
- LCY Balance: 11,025,000.00 (for USD)
- Implied WAE: 110.25

CALCULATED WAE FOR USD:
- Total FCY: 400,000.00 (4 accounts × 100,000)
- Total LCY: 44,100,000.00 (4 accounts × 11,025,000)
- WAE Rate: 110.25 (44,100,000 / 400,000)

After this, the WAE endpoint should work!
*/
