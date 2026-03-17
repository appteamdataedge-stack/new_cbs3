# Testing Guide: WAE Validation Fix for Same-Day FCY Transactions

## Test Environment Setup

### Prerequisites
1. A USD account with:
   - Zero or very low opening balance
   - No transactions today
2. Current Mid Rate for USD (e.g., 114.6000 BDT)
3. System Date = Current Business Date

## Test Cases

---

### Test Case 1: Same-Day Credit Then Debit (MAIN FIX SCENARIO)

**Objective:** Verify that an FCY account can be credited and then debited on the same day without WAE errors.

**Setup:**
- Account: USD account (e.g., yasir_abrar_td_usd - 100000008001)
- Opening Balance: 0.00 USD
- Current Date: 2026-03-16
- Mid Rate: 114.6000 BDT/USD

**Steps:**

1. **Credit Transaction:**
   ```
   Debit:  BDT account 15,000.00 BDT at rate 114.6000
   Credit: USD account 15,000.00 USD at rate 114.6000
   ```
   - Expected: Transaction succeeds
   - Verify: USD account balance = 15,000.00 USD

2. **Check Account Balance (UI):**
   - Expected: 
     - Available Balance: 15,000.00 USD
     - WAE: 114.6000
     - Available Balance (LCY): 1,719,000.00 BDT

3. **Debit Transaction (Same Day):**
   ```
   Debit:  USD account 15,000.00 USD (settlement trigger - should use WAE)
   Credit: BDT account 15,000.00 BDT at mid rate 114.6000
   ```
   - **Before Fix:** Error - "WAE not available for settlement account..."
   - **After Fix:** Transaction succeeds using live WAE = 114.6000

4. **Verify Logs:**
   - Look for: "WAE FALLBACK: Attempting to calculate live WAE from acc_bal table"
   - Look for: "LIVE WAE CALCULATED: 1719000.00 / 15000.00 = 114.6000"
   - Look for: "LIVE WAE CALCULATED from acc_bal for account 100000008001: 114.6000"

**Expected Result:**
- ✅ Both transactions succeed
- ✅ Final USD account balance: 0.00 USD
- ✅ No WAE errors
- ✅ Live WAE fallback logic is triggered and succeeds

---

### Test Case 2: Multiple Same-Day Transactions

**Objective:** Verify WAE calculation with multiple intraday transactions.

**Setup:**
- Account: USD account with 0.00 USD opening balance
- Mid Rate: 114.6000 BDT/USD

**Steps:**

1. **First Credit:** +10,000.00 USD at 114.6000
   - Expected WAE: 114.6000
   
2. **Second Credit:** +5,000.00 USD at 114.6000
   - Expected WAE: 114.6000
   - Total Balance: 15,000.00 USD

3. **First Debit:** -5,000.00 USD (settlement)
   - Expected: Uses live WAE = 114.6000
   - Remaining Balance: 10,000.00 USD

4. **Second Debit:** -10,000.00 USD (settlement)
   - Expected: Uses live WAE = 114.6000
   - Final Balance: 0.00 USD

**Expected Result:**
- ✅ All transactions succeed
- ✅ WAE calculated correctly at each step
- ✅ No errors

---

### Test Case 3: Account with Previous Day Balance

**Objective:** Verify that existing logic still works when `acct_bal_lcy` record exists.

**Setup:**
- Account: USD account with existing balance from previous day
- Previous Day Balance: 10,000.00 USD with WAE = 114.5000
- Today's Opening: 10,000.00 USD

**Steps:**

1. **Check if acct_bal_lcy exists for previous day:**
   ```sql
   SELECT * FROM Acct_Bal_LCY 
   WHERE Account_No = '100000008001' 
   AND Tran_Date = '2026-03-15'
   ```
   - Expected: Record exists with Closing_Bal_lcy

2. **Credit Transaction:** +5,000.00 USD at 114.6000
   - Expected: Transaction succeeds

3. **Debit Transaction:** -5,000.00 USD (settlement)
   - Expected: Uses WAE from acct_bal_lcy (NOT live fallback)
   - Verify logs: Should NOT see "WAE FALLBACK" messages

**Expected Result:**
- ✅ Existing logic path is used (no regression)
- ✅ Live fallback is NOT triggered
- ✅ WAE calculated from acct_bal_lcy table

---

### Test Case 4: Zero FCY Balance Validation

**Objective:** Verify that WAE validation correctly rejects when FCY balance is zero.

**Setup:**
- Account: USD account with 0.00 USD opening balance
- No transactions today

**Steps:**

1. **Attempt Debit on Zero Balance:**
   ```
   Debit: USD account 100.00 USD
   ```
   - Expected: Should fail with insufficient balance error (before WAE check)

2. **Check Account After Credit and Full Debit:**
   - Credit +1000 USD
   - Debit -1000 USD
   - Balance = 0.00 USD
   - Attempt another debit
   - Expected: Should fail (insufficient balance)

**Expected Result:**
- ✅ Cannot debit from zero balance
- ✅ Live WAE calculation handles zero denominator gracefully

---

### Test Case 5: Multiple Currency Types

**Objective:** Verify fix works for different FCY currencies (USD, EUR, GBP, JPY).

**Setup:**
- Accounts: One each for USD, EUR, GBP, JPY
- All with 0.00 opening balance

**Steps:**

For each currency:
1. Credit the account (e.g., 1000 units)
2. Debit the account (same amount, same day)
3. Verify WAE fallback logic works

**Expected Result:**
- ✅ Live WAE calculation works for all FCY types
- ✅ No currency-specific errors

---

### Test Case 6: Settlement Gain/Loss Calculation

**Objective:** Verify settlement gain/loss still works correctly with live WAE.

**Setup:**
- USD account with 10,000.00 USD opening balance
- WAE from previous day: 114.5000
- Today's Mid Rate: 114.6000

**Steps:**

1. **Credit Transaction:** +5,000.00 USD at 114.6000
   - Expected Available Balance: 15,000.00 USD

2. **Settlement Debit:** -15,000.00 USD
   - This should trigger settlement with gain/loss
   - WAE should be calculated as weighted average

3. **Check Settlement Rows:**
   - Verify gain/loss GL entries created
   - Verify amounts balance correctly

**Expected Result:**
- ✅ Settlement gain/loss calculated correctly
- ✅ All entries balance (total DR LCY = total CR LCY)

---

### Test Case 7: EOD Processing (Regression Test)

**Objective:** Verify that EOD batch processes still work correctly.

**Setup:**
- Create several same-day transactions using live WAE
- Run EOD batch job

**Steps:**

1. **Create Transactions Throughout Day:**
   - Multiple credits and debits on various USD accounts

2. **Run EOD Step 8 (Create acct_bal_lcy records):**
   - Verify acct_bal_lcy records are created correctly

3. **Next Day Transactions:**
   - Verify WAE is now read from acct_bal_lcy (not live fallback)
   - Check logs confirm normal path is used

**Expected Result:**
- ✅ EOD creates acct_bal_lcy records correctly
- ✅ Next day uses normal WAE calculation path
- ✅ No data integrity issues

---

## Verification Checklist

After running all tests:

- [ ] Same-day credit-then-debit works without errors
- [ ] Live WAE calculation produces correct values
- [ ] Existing accounts with acct_bal_lcy records use normal path (no regression)
- [ ] Zero balance validation still works
- [ ] Settlement gain/loss calculation works correctly
- [ ] EOD batch processing creates acct_bal_lcy records correctly
- [ ] Next-day transactions use normal path
- [ ] All currency types (USD, EUR, GBP, JPY) work correctly
- [ ] Log messages clearly indicate which calculation path is used
- [ ] No unexpected errors in application logs

## Database Queries for Verification

### Check acc_bal record:
```sql
SELECT Account_No, Tran_Date, Opening_Bal, CR_Summation, DR_Summation, 
       (Opening_Bal + CR_Summation - DR_Summation) AS Current_FCY,
       WAE_Rate
FROM Acct_Bal
WHERE Account_No = '100000008001' 
AND Tran_Date = '2026-03-16'
```

### Check acct_bal_lcy record (should not exist for current date before EOD):
```sql
SELECT Account_No, Tran_Date, Opening_Bal_lcy, CR_Summation_lcy, DR_Summation_lcy,
       (Opening_Bal_lcy + CR_Summation_lcy - DR_Summation_lcy) AS Current_LCY
FROM Acct_Bal_LCY
WHERE Account_No = '100000008001' 
AND Tran_Date = '2026-03-16'
```
*Expected: No records before EOD*

### Check today's transactions:
```sql
SELECT Tran_Id, Account_No, Dr_Cr_Flag, Tran_Ccy, 
       FCY_Amt, Exchange_Rate, LCY_Amt,
       Debit_Amount, Credit_Amount
FROM Tran_Table
WHERE Account_No = '100000008001' 
AND Tran_Date = '2026-03-16'
ORDER BY Tran_Id
```

### Manual WAE Calculation:
```sql
-- Calculate Live WAE manually
SELECT 
    acc.Account_No,
    acc.Tran_Date,
    -- FCY Calculation
    acc.Opening_Bal AS FCY_OPB,
    acc.CR_Summation AS FCY_CR,
    acc.DR_Summation AS FCY_DR,
    (acc.Opening_Bal + acc.CR_Summation - acc.DR_Summation) AS Total_FCY,
    
    -- LCY Calculation (from previous day acct_bal_lcy + today's tran_table)
    COALESCE(prev.Closing_Bal_lcy, 0) AS LCY_OPB,
    (SELECT COALESCE(SUM(Credit_Amount), 0) FROM Tran_Table 
     WHERE Account_No = acc.Account_No AND Tran_Date = acc.Tran_Date) AS LCY_CR,
    (SELECT COALESCE(SUM(Debit_Amount), 0) FROM Tran_Table 
     WHERE Account_No = acc.Account_No AND Tran_Date = acc.Tran_Date) AS LCY_DR,
    (COALESCE(prev.Closing_Bal_lcy, 0) + 
     (SELECT COALESCE(SUM(Credit_Amount), 0) FROM Tran_Table 
      WHERE Account_No = acc.Account_No AND Tran_Date = acc.Tran_Date) -
     (SELECT COALESCE(SUM(Debit_Amount), 0) FROM Tran_Table 
      WHERE Account_No = acc.Account_No AND Tran_Date = acc.Tran_Date)) AS Total_LCY,
    
    -- Live WAE
    ROUND(
        (COALESCE(prev.Closing_Bal_lcy, 0) + 
         (SELECT COALESCE(SUM(Credit_Amount), 0) FROM Tran_Table 
          WHERE Account_No = acc.Account_No AND Tran_Date = acc.Tran_Date) -
         (SELECT COALESCE(SUM(Debit_Amount), 0) FROM Tran_Table 
          WHERE Account_No = acc.Account_No AND Tran_Date = acc.Tran_Date)) /
        NULLIF((acc.Opening_Bal + acc.CR_Summation - acc.DR_Summation), 0)
    , 4) AS Live_WAE
FROM Acct_Bal acc
LEFT JOIN Acct_Bal_LCY prev ON prev.Account_No = acc.Account_No 
    AND prev.Tran_Date = DATEADD(day, -1, acc.Tran_Date)
WHERE acc.Account_No = '100000008001' 
AND acc.Tran_Date = '2026-03-16'
```

## Log Analysis

### Successful Live WAE Calculation:
```
WAE DIAGNOSTIC: No acct_bal_lcy record found for account 100000008001 on date 2026-03-16
WAE FALLBACK: Attempting to calculate live WAE from acc_bal table for same-day transactions
═══ LIVE WAE CALCULATION from acc_bal START for account 100000008001 ═══
LIVE WAE: FCY calculation - OPB=0.00, CR=15000.00, DR=0.00, Total=15000.00
LIVE WAE: Previous day LCY closing balance found: 0.00
LIVE WAE: LCY calculation - OPB=0.00, CR=1719000.00, DR=0.00, Total=1719000.00
═══ LIVE WAE CALCULATED: 1719000.00 / 15000.00 = 114.6000 ═══
═══ LIVE WAE CALCULATED from acc_bal for account 100000008001: 114.6000 ═══
Rate assignment: account 100000008001 (Liability DR) → WAE=114.6000 (settlement trigger), LCY=1719000.00
```

### Normal Path (acct_bal_lcy exists):
```
WAE DIAGNOSTIC: acct_bal_lcy found for 100000008001 - tran_date=2026-03-15
WAE DIAGNOSTIC: LCY calculation for 100000008001 - opening=0.00, CR=0.00, DR=0.00, current=0.00
═══ WAE CALCULATED for account 100000008001: 0.00 / 0.00 = N/A ═══
```

### Error Case (should not happen with fix):
```
LIVE WAE: Error calculating live WAE for account 100000008001: [error message]
WAE DIAGNOSTIC: Live WAE calculation failed. This account needs an acct_bal_lcy row! Check EOD Step 8 execution.
```

## Success Criteria

The fix is considered successful if:

1. **Functional:**
   - Same-day credit-then-debit transactions succeed without WAE errors
   - Live WAE calculation produces correct values
   - Settlement gain/loss calculations remain accurate

2. **No Regression:**
   - Existing accounts with acct_bal_lcy records work as before
   - EOD batch processing unaffected
   - All currency types work correctly

3. **Reliable:**
   - Edge cases (zero balance, new accounts) handled gracefully
   - Error logging is clear and actionable
   - No unexpected failures

4. **Performance:**
   - Transaction processing time not significantly increased
   - Database queries efficient (uses existing indexes)

---

**Note:** Run these tests in a test environment first before deploying to production!
