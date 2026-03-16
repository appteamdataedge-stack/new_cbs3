# Quick Start Guide - Testing lcy_amt Implementation

## Prerequisites

1. ✅ Code changes have been deployed
2. ✅ Database migration V37 has been executed
3. ✅ You have a test USD account with existing or new accruals

---

## Step-by-Step Testing

### Step 1: Verify Column Exists

```sql
DESCRIBE acct_bal_accrual;
```

**Expected:** You should see a `lcy_amt` column with type `DECIMAL(20,2)` after `closing_bal`.

---

### Step 2: Check Backfill (If There's Historical Data)

```sql
SELECT account_no, tran_date, closing_bal, lcy_amt
FROM acct_bal_accrual
WHERE lcy_amt > 0
ORDER BY tran_date DESC
LIMIT 10;
```

**Expected:** Historical records should have `lcy_amt` populated if they had S-type entries.

---

### Step 3: Run EOD for Today

1. Navigate to **Admin > EOD** in the frontend
2. Click **"Run EOD"** for the current system date
3. Wait for all 6 batch jobs to complete

**Expected:** Batch Job 6 should complete successfully with message "Interest Accrual Account Balance Update completed"

---

### Step 4: Verify lcy_amt Populated After EOD

Replace `<your_test_account>` with an actual USD account number:

```sql
SELECT 
    account_no,
    tran_date,
    tran_ccy,
    closing_bal AS accrued_fcy_total,
    lcy_amt AS accrued_lcy_total,
    ROUND(lcy_amt / closing_bal, 4) AS wae
FROM acct_bal_accrual
WHERE account_no = '<your_test_account>'
ORDER BY tran_date DESC
LIMIT 3;
```

**Expected Output Example:**
```
account_no       | tran_date  | tran_ccy | accrued_fcy_total | accrued_lcy_total | wae
200140203002001 | 2026-03-15 | USD      | 45.00             | 5067.00           | 112.6000
```

**What to Check:**
- ✅ `lcy_amt` is > 0 (not zero)
- ✅ `wae` is close to the current exchange rate (e.g., ~112.60 for USD)
- ✅ `wae` is NOT 1.0000 (that would be wrong for FCY accounts)

---

### Step 5: Test Capitalization Preview API

Open browser console and run:

```javascript
fetch('/api/interest-capitalization/<your_test_account>/preview')
  .then(r => r.json())
  .then(data => console.log(data));
```

Or use curl:

```bash
curl http://localhost:8080/api/interest-capitalization/<your_test_account>/preview
```

**Expected Response:**
```json
{
  "accountNo": "200140203002001",
  "currency": "USD",
  "accruedFcy": 45.00,
  "accruedLcy": 5067.00,
  "waeRate": 112.6000,
  "midRate": 112.7000,
  "estimatedGainLoss": -4.50
}
```

**What to Check:**
- ✅ `accruedLcy` matches `acct_bal_accrual.lcy_amt` from Step 4
- ✅ `waeRate` is NOT 1.0000 for USD accounts
- ✅ `estimatedGainLoss` is calculated (can be positive or negative)

---

### Step 6: Perform Interest Capitalization

1. Navigate to **Home > Interest Capitalization**
2. Find your test USD account in the list
3. Click **"Capitalize"**
4. Review the preview dialog showing:
   - Accrued FCY amount
   - Accrued LCY amount
   - WAE rate
   - Mid rate
   - Estimated gain/loss
5. Confirm the capitalization

**Expected:** Success message appears, balance is updated.

---

### Step 7: Verify Capitalization Entry Uses Correct WAE

```sql
SELECT 
    accr_tran_id,
    account_no,
    tran_date,
    dr_cr_flag,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    ROUND(fcy_amt * exchange_rate, 2) AS calculated_lcy
FROM intt_accr_tran
WHERE accr_tran_id LIKE 'C%'
  AND account_no = '<your_test_account>'
ORDER BY tran_date DESC
LIMIT 3;
```

**Expected Output Example:**
```
accr_tran_id        | account_no      | tran_date  | dr_cr_flag | fcy_amt | exchange_rate | lcy_amt  | calculated_lcy
C20260315000001-1  | 200140203002001 | 2026-03-15 | D          | 45.00   | 112.6000      | 5067.00  | 5067.00
```

**What to Check:**
- ✅ `exchange_rate` = WAE (~112.6000), NOT 1.0000 ← **CRITICAL CHECK**
- ✅ `lcy_amt` = `fcy_amt` × `exchange_rate`
- ✅ `dr_cr_flag` = 'D' (debit to interest expense)

---

### Step 8: Verify Complete Transaction Set

```sql
-- Check all legs of the capitalization
SELECT 
    tran_id,
    dr_cr_flag,
    account_no,
    gl_num,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    narration
FROM tran_table
WHERE tran_id LIKE 'C20260315%'
  AND (account_no = '<your_test_account>' OR gl_num IN ('140203002', '240203002'))
ORDER BY tran_id;
```

**Expected Output Example:**
```
tran_id             | dr_cr_flag | account_no      | gl_num    | fcy_amt | exchange_rate | lcy_amt  | narration
C20260315000001-2  | C          | 200140203002001 | NULL      | 45.00   | 112.7000      | 5071.50  | Interest Capitalization - Credit
C20260315000001-3  | D          | NULL            | 240203002 | 4.50    | 1.0000        | 4.50     | FX Loss on Interest Capitalization
```

**What to Check:**
- ✅ Credit entry uses MID rate (112.7000)
- ✅ Gain/loss entry created if WAE ≠ MID
- ✅ Gain/loss amount = |DR LCY - CR LCY|

---

### Step 9: Verify LCY Balance

```sql
WITH cap_entries AS (
    SELECT lcy_amt, dr_cr_flag FROM intt_accr_tran
    WHERE accr_tran_id LIKE 'C20260315%' AND account_no = '<your_test_account>'
    UNION ALL
    SELECT lcy_amt, dr_cr_flag FROM tran_table
    WHERE tran_id LIKE 'C20260315%' 
      AND (account_no = '<your_test_account>' OR gl_num IN ('140203002', '240203002'))
)
SELECT 
    SUM(CASE WHEN dr_cr_flag = 'D' THEN lcy_amt ELSE 0 END) AS total_dr,
    SUM(CASE WHEN dr_cr_flag = 'C' THEN lcy_amt ELSE 0 END) AS total_cr,
    ABS(SUM(CASE WHEN dr_cr_flag = 'D' THEN lcy_amt ELSE 0 END) -
        SUM(CASE WHEN dr_cr_flag = 'C' THEN lcy_amt ELSE 0 END)) AS difference
FROM cap_entries;
```

**Expected Output:**
```
total_dr  | total_cr  | difference
5071.50   | 5071.50   | 0.00
```

**What to Check:**
- ✅ `total_dr` = `total_cr` (within 0.01 tolerance)
- ✅ LCY is balanced across all legs

---

### Step 10: Check Application Logs

Look for these log messages in the backend logs:

**During EOD:**
```
Total LCY Amount (from S-type entries): 5067.00
lcy_amt: 5067.00 (sum of LCY from S-type entries)
```

**During Capitalization:**
```
AccrualWAE | account=200140203002001 totalLCY=5067.00 totalFCY=45.00 WAE=112.6000
```

**What to Check:**
- ✅ No errors or warnings about missing `lcy_amt`
- ✅ WAE calculation logs show correct values
- ✅ No "using 1.0" fallback messages for FCY accounts

---

## Testing BDT Accounts (Optional)

Repeat Steps 5-9 with a BDT account:

**Expected Differences:**
- ✅ WAE = 1.0000 (correct for BDT)
- ✅ `exchange_rate` = 1.0000 in all entries
- ✅ `accruedLcy` = `accruedFcy` (same value)
- ✅ No gain/loss entry created

---

## Common Issues & Troubleshooting

### Issue 1: lcy_amt is Zero After EOD

**Possible Causes:**
1. No S-type entries exist for the account/date
2. Migration backfill didn't run correctly

**Check:**
```sql
SELECT COUNT(*), SUM(lcy_amt)
FROM intt_accr_tran
WHERE account_no = '<your_test_account>'
  AND accr_tran_id LIKE 'S%'
  AND accrual_date = CURDATE();
```

If count > 0 but lcy_amt is still 0 in `acct_bal_accrual`, check EOD logs for errors.

---

### Issue 2: WAE is 1.0000 for USD Account

**Possible Causes:**
1. `lcy_amt` is zero or null
2. `closing_bal` is zero
3. Code is falling back to 1.0 due to missing data

**Check:**
```sql
SELECT closing_bal, lcy_amt, lcy_amt / closing_bal AS wae
FROM acct_bal_accrual
WHERE account_no = '<your_test_account>'
ORDER BY tran_date DESC LIMIT 1;
```

If `lcy_amt` is zero, check if EOD Batch Job 6 ran successfully.

---

### Issue 3: Capitalization Entry Has exchange_rate = 1.0000

**This is the main bug this implementation fixes!**

**Check:**
```sql
SELECT exchange_rate, lcy_amt, fcy_amt
FROM intt_accr_tran
WHERE accr_tran_id LIKE 'C%'
  AND account_no = '<your_test_account>'
  AND tran_ccy != 'BDT';
```

If `exchange_rate` = 1.0000 for a non-BDT account, the WAE calculation is not working.

**Debug:**
1. Check `acct_bal_accrual.lcy_amt` is populated
2. Check application logs for "AccrualWAE" messages
3. Verify `calculateAccrualWae()` method is being called

---

### Issue 4: LCY Imbalance (DR ≠ CR)

**Possible Causes:**
1. Gain/loss entry not created when needed
2. Rounding issues

**Check:**
```sql
-- Run the LCY balance query from Step 9
-- Difference should be <= 0.01 BDT
```

If difference > 0.01, check the gain/loss GL entry exists:
```sql
SELECT * FROM tran_table
WHERE tran_id LIKE 'C%'
  AND gl_num IN ('140203002', '240203002')
ORDER BY tran_date DESC LIMIT 5;
```

---

## Success Criteria Checklist

Run through this checklist to confirm implementation is working:

- [ ] Column `lcy_amt` exists in `acct_bal_accrual` table
- [ ] After EOD, `lcy_amt` is populated with non-zero values for FCY accounts
- [ ] `lcy_amt` matches sum of S-type entries from `intt_accr_tran`
- [ ] Preview API returns correct `accruedLcy` value
- [ ] Capitalization debit entry has `exchange_rate` = WAE (not 1.0000)
- [ ] Capitalization credit entry has `exchange_rate` = MID rate
- [ ] Gain/loss entry created when WAE ≠ MID
- [ ] LCY is balanced (DR total = CR total within 0.01 tolerance)
- [ ] BDT accounts still use rate = 1.0000
- [ ] No errors in application logs
- [ ] Account balance updated correctly after capitalization

**If all items are checked, implementation is successful! ✅**

---

## Next Steps After Successful Test

1. Test with multiple USD accounts
2. Test with EUR/GBP accounts (if configured)
3. Test edge cases (new accounts, zero balance, etc.)
4. Run for multiple consecutive days to verify accumulation
5. Verify reporting/audit queries work correctly
6. Document any special configuration needed for production deployment

---

## Rollback Instructions (If Needed)

If critical issues are found:

1. **Immediate:** Disable capitalization feature in UI
2. **Code rollback:** Deploy previous version of:
   - `InterestCapitalizationService.java`
   - `InterestAccrualAccountBalanceService.java`
   - `InttAccrTranRepository.java`
   - `AcctBalAccrual.java`
3. **Database:** Column can stay (data is harmless if unused)
4. **Re-test:** After fixes, re-deploy and re-test

**Database rollback (only if column causes issues):**
```sql
ALTER TABLE acct_bal_accrual DROP COLUMN lcy_amt;
```

Then re-run Flyway migration history cleanup if needed.

---

## Questions or Issues?

If you encounter any issues during testing:

1. Check application logs in `moneymarket/backend.log`
2. Run verification SQL queries from `verify_lcy_amt_implementation.sql`
3. Check this document for troubleshooting steps
4. Review the implementation summary document for technical details

**Key Log Files:**
- Backend: `moneymarket/backend.log`
- EOD: Check for "Batch Job 6" messages
- Capitalization: Check for "AccrualWAE" messages
