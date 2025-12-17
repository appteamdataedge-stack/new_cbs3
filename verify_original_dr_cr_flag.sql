-- ============================================================
-- Verification Queries for Original_Dr_Cr_Flag Implementation
-- ============================================================

-- 1. Verify columns exist in both tables
-- ============================================================
SHOW COLUMNS FROM Value_Date_Intt_Accr LIKE 'Original_Dr_Cr_Flag';
SHOW COLUMNS FROM Intt_Accr_Tran LIKE 'Original_Dr_Cr_Flag';

-- 2. Check if any value date interest records have Original_Dr_Cr_Flag populated
-- ============================================================
SELECT
    COUNT(*) as Total_Records,
    COUNT(Original_Dr_Cr_Flag) as With_Original_Flag,
    COUNT(*) - COUNT(Original_Dr_Cr_Flag) as Without_Original_Flag
FROM Value_Date_Intt_Accr;

SELECT
    COUNT(*) as Total_Records,
    COUNT(Original_Dr_Cr_Flag) as With_Original_Flag,
    COUNT(*) - COUNT(Original_Dr_Cr_Flag) as Without_Original_Flag
FROM Intt_Accr_Tran;

-- 3. Sample records from Value_Date_Intt_Accr with Original_Dr_Cr_Flag
-- ============================================================
SELECT
    Accr_Tran_Id,
    Account_No,
    Tran_Date,
    Value_Date,
    Dr_Cr_Flag as Accrual_Dr_Cr,
    Original_Dr_Cr_Flag as Original_Tran_Dr_Cr,
    Interest_Rate,
    Amount,
    Narration
FROM Value_Date_Intt_Accr
ORDER BY Tran_Date DESC, Account_No
LIMIT 10;

-- 4. Sample records from Intt_Accr_Tran with Original_Dr_Cr_Flag
-- ============================================================
SELECT
    Accr_Tran_Id,
    Account_No,
    Accrual_Date,
    Tran_Date,
    Value_Date,
    Dr_Cr_Flag as Accrual_Dr_Cr,
    Original_Dr_Cr_Flag as Original_Tran_Dr_Cr,
    Amount,
    Narration
FROM Intt_Accr_Tran
WHERE Original_Dr_Cr_Flag IS NOT NULL
ORDER BY Accrual_Date DESC, Account_No
LIMIT 10;

-- 5. Verify value date interest logic - LIABILITY accounts
-- ============================================================
-- Should show different Dr/Cr patterns based on Original_Dr_Cr_Flag
SELECT
    v.Account_No,
    v.Tran_Date,
    v.Original_Dr_Cr_Flag as 'Original Tran',
    v.Dr_Cr_Flag as 'Accrual Entry',
    v.Amount,
    v.GL_Account_No,
    v.Narration,
    CASE
        WHEN v.Original_Dr_Cr_Flag = 'C' AND v.Dr_Cr_Flag = 'C' THEN '✓ Correct (Deposit → Credit Payable)'
        WHEN v.Original_Dr_Cr_Flag = 'D' AND v.Dr_Cr_Flag = 'D' THEN '✓ Correct (Withdrawal → Debit Payable)'
        ELSE '✗ Incorrect Pattern'
    END as Validation
FROM Value_Date_Intt_Accr v
JOIN tran_table t ON v.Tran_Id = t.Tran_ID
WHERE v.GL_Account_No LIKE '130%'  -- Interest Payable (Liability)
  AND v.Tran_Date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
ORDER BY v.Tran_Date DESC
LIMIT 20;

-- 6. Verify value date interest logic - ASSET accounts
-- ============================================================
-- Should show different Dr/Cr patterns based on Original_Dr_Cr_Flag
SELECT
    v.Account_No,
    v.Tran_Date,
    v.Original_Dr_Cr_Flag as 'Original Tran',
    v.Dr_Cr_Flag as 'Accrual Entry',
    v.Amount,
    v.GL_Account_No,
    v.Narration,
    CASE
        WHEN v.Original_Dr_Cr_Flag = 'D' AND v.Dr_Cr_Flag = 'D' THEN '✓ Correct (Advance → Debit Receivable)'
        WHEN v.Original_Dr_Cr_Flag = 'C' AND v.Dr_Cr_Flag = 'C' THEN '✓ Correct (Repayment → Credit Receivable)'
        ELSE '✗ Incorrect Pattern'
    END as Validation
FROM Value_Date_Intt_Accr v
JOIN tran_table t ON v.Tran_Id = t.Tran_ID
WHERE v.GL_Account_No LIKE '230%'  -- Interest Receivable (Asset)
  AND v.Tran_Date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
ORDER BY v.Tran_Date DESC
LIMIT 20;

-- 7. Check acct_bal_accrual for correct balance impact
-- ============================================================
-- Compare accounts with value date interest
SELECT
    a.Account_No,
    a.Tran_Date,
    a.Opening_Bal,
    a.CR_Summation as Regular_CR,
    a.DR_Summation as Regular_DR,
    a.Closing_Bal,
    a.Interest_Amount,
    (a.Closing_Bal - a.Opening_Bal) as Balance_Change,
    COUNT(i.Accr_Tran_Id) as Value_Date_Records
FROM acct_bal_accrual a
LEFT JOIN Intt_Accr_Tran i ON i.Account_No = a.Account_No
    AND i.Accrual_Date = a.Tran_Date
    AND i.Original_Dr_Cr_Flag IS NOT NULL
WHERE a.Tran_Date >= DATE_SUB(CURDATE(), INTERVAL 3 DAY)
GROUP BY a.Account_No, a.Tran_Date
HAVING Value_Date_Records > 0
ORDER BY a.Tran_Date DESC, a.Account_No
LIMIT 10;

-- 8. Detailed breakdown for a specific account with value date interest
-- ============================================================
-- Replace '100000131001' with actual account number
SELECT
    'Regular Interest' as Type,
    Dr_Cr_Flag,
    COUNT(*) as Count,
    SUM(Amount) as Total_Amount
FROM Intt_Accr_Tran
WHERE Account_No = '100000131001'
  AND Accrual_Date = '2025-03-20'
  AND Original_Dr_Cr_Flag IS NULL
GROUP BY Dr_Cr_Flag

UNION ALL

SELECT
    'Value Date Interest' as Type,
    CONCAT(Dr_Cr_Flag, ' (Orig: ', Original_Dr_Cr_Flag, ')') as Dr_Cr_Flag,
    COUNT(*) as Count,
    SUM(Amount) as Total_Amount
FROM Intt_Accr_Tran
WHERE Account_No = '100000131001'
  AND Accrual_Date = '2025-03-20'
  AND Original_Dr_Cr_Flag IS NOT NULL
GROUP BY Dr_Cr_Flag, Original_Dr_Cr_Flag;

-- 9. Index verification
-- ============================================================
SHOW INDEX FROM Intt_Accr_Tran WHERE Key_name = 'idx_intt_accr_tran_original_flag';

-- 10. Migration status check
-- ============================================================
SELECT * FROM flyway_schema_history
WHERE script = 'V14__add_original_dr_cr_flag.sql'
ORDER BY installed_rank DESC;
