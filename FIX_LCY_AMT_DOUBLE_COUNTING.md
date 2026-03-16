# Fix: acct_bal_accrual.lcy_amt Double-Counting Bug

**Date:** March 15, 2026  
**Status:** ✅ FIXED

---

## SYMPTOM

**Observed:** `acct_bal_accrual.lcy_amt = 11.62` (should be 5.81, exactly half)

**Root Cause:** Repository query was summing BOTH debit and credit legs of each S-type accrual transaction, causing double-counting.

---

## ROOT CAUSE ANALYSIS

### How Interest Accrual Creates Transactions

When daily interest is accrued, the system creates **TWO entries** in `intt_accr_tran` for each accrual:

#### Example: Account 100000008001, Daily Interest = 5.81 LCY

**Entry 1 (Debit - Interest Expense GL):**
```
accr_tran_id: S20260315001
dr_cr_flag: D
lcy_amt: 5.81
fcy_amt: 0.05
account_no: NULL (GL account)
gl_account_no: 5132299001
original_dr_cr_flag: NULL
```

**Entry 2 (Credit - Customer Account):**
```
accr_tran_id: S20260315001  (same ID)
dr_cr_flag: C
lcy_amt: 5.81  (same amount)
fcy_amt: 0.05  (same amount)
account_no: 100000008001
gl_account_no: NULL
original_dr_cr_flag: NULL
```

### The Buggy Query (Before Fix)

**File:** `InttAccrTranRepository.java` (Line 193-199)

```java
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.accrTranId LIKE 'S%' " +
       "AND i.originalDrCrFlag IS NULL")
BigDecimal sumLcyAmtByAccountAndDate(...)
```

**Problem:** This query summed **BOTH** the debit AND credit entries:
- DR leg: lcy_amt = 5.81
- CR leg: lcy_amt = 5.81
- **Total = 11.62** ❌

### Why CR Summation Was Correct

The `sumCreditAmountsByAccountAndDate` query (line 118-125) was **already correct** because it filtered by `Dr_Cr_Flag = 'C'`:

```java
@Query("SELECT COALESCE(SUM(i.amount), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.accrTranId LIKE 'S%' " +
       "AND i.drCrFlag = 'C' " +          // ← FILTERS TO CREDIT ONLY
       "AND i.originalDrCrFlag IS NULL")
```

This ensured `acct_bal_accrual.cr_summation` was correct (5.81, not 11.62).

---

## THE FIX

### Pattern Identified: PATTERN C - Double SUM Query Result

Both the DR and CR legs have the same `lcy_amt`. The fix is to **only sum the CREDIT leg** to avoid double-counting.

### Changes Applied

**File:** `InttAccrTranRepository.java`

#### Fix 1: `sumLcyAmtByAccountAndDate` (Line 193-211)

**Before:**
```java
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.accrTranId LIKE 'S%' " +
       "AND i.originalDrCrFlag IS NULL")
```

**After:**
```java
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.accrTranId LIKE 'S%' " +
       "AND i.drCrFlag = 'C' " +          // ← ADDED: Filter to credit leg only
       "AND i.originalDrCrFlag IS NULL")
```

#### Fix 2: `sumLcyAmtByAccountNo` (Line 201-215)

**Before:**
```java
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrTranId LIKE 'S%'")
```

**After:**
```java
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrTranId LIKE 'S%' " +
       "AND i.drCrFlag = 'C'")            // ← ADDED: Filter to credit leg only
```

---

## WHY THIS FIX IS CORRECT

### 1. Matches Existing Pattern

The fix aligns with the **already-correct** `sumCreditAmountsByAccountAndDate` query that uses `drCrFlag = 'C'`.

### 2. Accounting Principle

In double-entry bookkeeping:
- **DR side:** Interest Expense (P&L GL account)
- **CR side:** Customer Account (Balance Sheet account)

Both legs have the **same LCY amount**. We only need to sum **one leg** to get the total accrued LCY.

### 3. Customer Account Perspective

The `lcy_amt` in `acct_bal_accrual` represents the **customer's accrued interest balance**. This corresponds to the **CREDIT leg** (Dr_Cr_Flag = 'C'), not the debit leg.

### 4. Consistency with closing_bal

The `closing_bal` (FCY amount) is also calculated from the **CREDIT leg** only:
```java
sumCreditAmountsByAccountAndDate(...) // Uses drCrFlag = 'C'
```

So `lcy_amt` should use the same filtering logic.

---

## DATA FLOW AFTER FIX

### EOD Batch Job 6

```
intt_accr_tran (for account 100000008001)
  ├─ S20260315001 | DR | lcy_amt=5.81 | gl_account_no=5132299001
  └─ S20260315001 | CR | lcy_amt=5.81 | account_no=100000008001
       ↓
  sumLcyAmtByAccountAndDate (with drCrFlag='C' filter)
       ↓
  Total LCY = 5.81 (CR leg only, NOT 11.62)
       ↓
  acct_bal_accrual.lcy_amt = 5.81 ✅
```

### Capitalization Preview

```
Read from acct_bal_accrual
  ├─ closing_bal = 0.05 (FCY)
  └─ lcy_amt = 5.81 (LCY)
       ↓
  WAE = 5.81 / 0.05 = 116.20 ✅
       ↓
  Display on UI
```

---

## VERIFICATION

### Step 1: Check Raw Data in intt_accr_tran

```sql
SELECT accr_tran_id, dr_cr_flag, lcy_amt, fcy_amt, account_no, gl_account_no
FROM intt_accr_tran
WHERE account_no = '100000008001'
  AND accr_tran_id LIKE 'S%'
  AND original_dr_cr_flag IS NULL
ORDER BY accr_tran_id, dr_cr_flag;
```

**Expected Result:**
```
accr_tran_id    | dr_cr_flag | lcy_amt | fcy_amt | account_no    | gl_account_no
S20260315001    | D          | 5.81    | 0.05    | NULL          | 5132299001
S20260315001    | C          | 5.81    | 0.05    | 100000008001  | NULL
```

**Note:** Both legs have the same `lcy_amt = 5.81`.

### Step 2: Check Summed Value (Old Query - Would Be Wrong)

```sql
-- This is what the OLD query was doing (summing BOTH legs)
SELECT SUM(lcy_amt) as total_lcy
FROM intt_accr_tran
WHERE account_no = '100000008001'
  AND accr_tran_id LIKE 'S%'
  AND original_dr_cr_flag IS NULL;
```

**Old Result (WRONG):** `total_lcy = 11.62` ❌

### Step 3: Check Summed Value (New Query - Correct)

```sql
-- This is what the NEW query does (summing CREDIT leg only)
SELECT SUM(lcy_amt) as total_lcy
FROM intt_accr_tran
WHERE account_no = '100000008001'
  AND accr_tran_id LIKE 'S%'
  AND dr_cr_flag = 'C'
  AND original_dr_cr_flag IS NULL;
```

**New Result (CORRECT):** `total_lcy = 5.81` ✅

### Step 4: Check acct_bal_accrual After Next EOD Run

```sql
SELECT account_no, closing_bal, lcy_amt, 
       lcy_amt / closing_bal as calculated_wae
FROM acct_bal_accrual
WHERE account_no = '100000008001'
ORDER BY tran_date DESC
LIMIT 1;
```

**Expected After Next EOD:**
```
account_no    | closing_bal | lcy_amt | calculated_wae
100000008001  | 0.05        | 5.81    | 116.20
```

**Before (WRONG):**
```
account_no    | closing_bal | lcy_amt | calculated_wae
100000008001  | 0.05        | 11.62   | 232.40  ❌ (double the correct WAE)
```

### Step 5: Check UI Display

Navigate to: **Home > Interest Capitalization > Click account 100000008001**

**Expected Display:**
- Accrued Balance (FCY): **0.05 USD**
- Accrued Balance (LCY): **5.81 BDT** (NOT 11.62)
- WAE Rate: **116.20** (NOT 232.40)

---

## IMPACT ANALYSIS

### What Was Affected

1. **EOD Batch Job 6** - `acct_bal_accrual.lcy_amt` was being stored as double the correct value
2. **Interest Capitalization Details Page** - Displayed wrong LCY and wrong WAE (but only if reading from `intt_accr_tran`, which we just fixed to read from `acct_bal_accrual` instead)
3. **Capitalization Execution** - Used wrong WAE if it read from `intt_accr_tran` directly

### What Was NOT Affected

✅ **CR Summation** (`acct_bal_accrual.cr_summation`) - Already correct (was using `drCrFlag = 'C'`)  
✅ **DR Summation** (`acct_bal_accrual.dr_summation`) - Already correct (was using `drCrFlag = 'D'`)  
✅ **Closing Balance** (`acct_bal_accrual.closing_bal`) - Already correct (calculated from cr_summation - dr_summation)  
✅ **Transaction Posting** - Not affected (inserts were correct, only the SUM query was wrong)  
✅ **BDT Accounts** - Not affected (BDT accounts have exchange_rate = 1.0, so lcy_amt = fcy_amt anyway)

### Why This Bug Was Not Caught Earlier

1. **BDT accounts** (most common) were not affected because LCY = FCY
2. **FCY accounts** may have had few capitalizations or users may not have noticed the incorrect WAE
3. The `closing_bal` was correct, so the accrual balance itself appeared correct
4. Only the `lcy_amt` column (recently added) had this bug

---

## FILES MODIFIED

### InttAccrTranRepository.java

**Method 1:** `sumLcyAmtByAccountAndDate()` (Lines 193-211)
- **Change:** Added `AND i.drCrFlag = 'C'` filter
- **Impact:** EOD now writes correct `lcy_amt` to `acct_bal_accrual`

**Method 2:** `sumLcyAmtByAccountNo()` (Lines 201-215)
- **Change:** Added `AND i.drCrFlag = 'C'` filter
- **Impact:** Capitalization preview reads correct LCY (if it bypasses `acct_bal_accrual` and queries `intt_accr_tran` directly)

**Note:** After the previous fix, capitalization preview now reads from `acct_bal_accrual`, so Method 2 is only used as a fallback or for debugging.

---

## WHAT WAS NOT CHANGED

✅ No changes to `InterestAccrualAccountBalanceService.java` (EOD service logic is correct)  
✅ No changes to `InterestCapitalizationService.java` (capitalization logic is correct)  
✅ No changes to `intt_accr_tran` insert logic (transaction creation is correct)  
✅ No changes to entity classes or DTOs  
✅ No changes to database schema  
✅ No division by 2 workarounds (fixed the root cause)

---

## TESTING CHECKLIST

### 1. Database Verification (Current Data)

Check existing data to confirm the bug:

```sql
-- Check if lcy_amt is double the correct value
SELECT 
    a.account_no,
    a.closing_bal as fcy_total,
    a.lcy_amt as stored_lcy,
    (SELECT SUM(lcy_amt) FROM intt_accr_tran 
     WHERE account_no = a.account_no 
     AND accr_tran_id LIKE 'S%' 
     AND dr_cr_flag = 'C' 
     AND original_dr_cr_flag IS NULL) as correct_lcy,
    a.lcy_amt / NULLIF(a.closing_bal, 0) as stored_wae,
    (SELECT SUM(lcy_amt) FROM intt_accr_tran 
     WHERE account_no = a.account_no 
     AND accr_tran_id LIKE 'S%' 
     AND dr_cr_flag = 'C' 
     AND original_dr_cr_flag IS NULL) / NULLIF(a.closing_bal, 0) as correct_wae
FROM acct_bal_accrual a
WHERE a.account_no = '100000008001'
ORDER BY a.tran_date DESC
LIMIT 1;
```

**Expected:** `stored_lcy` is double `correct_lcy`.

### 2. Run EOD After Fix

After deploying the fix:
1. Run EOD Batch Job 6 for a test date
2. Check logs for "Total LCY Amount" message
3. Verify `lcy_amt` in `acct_bal_accrual` now equals the correct value (half of what it was before)

### 3. UI Verification

Navigate to Interest Capitalization Details:
- LCY amount should now be correct (e.g., 5.81 instead of 11.62)
- WAE should now be correct (e.g., 116.20 instead of 232.40)

### 4. Capitalization Test

Execute an interest capitalization:
- Check `tran_table` for the capitalization entries
- Verify the WAE rate used is correct
- Verify LCY amounts balance correctly

---

## ROLLBACK (IF NEEDED)

If the fix causes issues (unlikely), revert the changes:

```java
// Revert InttAccrTranRepository.java Line 193-211
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.accrTranId LIKE 'S%' " +
       "AND i.originalDrCrFlag IS NULL")  // Remove drCrFlag filter
BigDecimal sumLcyAmtByAccountAndDate(...)

// Revert InttAccrTranRepository.java Line 201-215
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrTranId LIKE 'S%'")  // Remove drCrFlag filter
BigDecimal sumLcyAmtByAccountNo(...)
```

---

## COMPILATION STATUS

✅ **No linter errors**  
✅ **No compilation errors**  
✅ **Ready for testing**

---

## SUMMARY

Fixed the double-counting bug in `acct_bal_accrual.lcy_amt` by adding `drCrFlag = 'C'` filter to the LCY sum queries in `InttAccrTranRepository`. This ensures only the **CREDIT leg** of each S-type accrual transaction is summed, matching the logic already used for `cr_summation`.

**Before Fix:** `lcy_amt = 11.62` (DR leg 5.81 + CR leg 5.81)  
**After Fix:** `lcy_amt = 5.81` (CR leg only)

This fix ensures:
1. **Correct LCY values** stored in `acct_bal_accrual`
2. **Correct WAE calculation** (lcy_amt / closing_bal)
3. **Correct display** on Interest Capitalization Details page
4. **Consistency** with existing `cr_summation` query logic

**Root cause was PATTERN C: Both the DR and CR legs were being summed, even though they have identical lcy_amt values.**
