# Fix: Interest Capitalization Details - Read from acct_bal_accrual

**Date:** March 15, 2026  
**Status:** ✅ FIXED

---

## REQUIREMENT

**Single Source of Truth:** `acct_bal_accrual` table

All values on Interest Capitalization Details page must come from:
- **Table:** `acct_bal_accrual`
- **Filter:** `account_no = :accountNo`

**Field Mapping:**
- Accrued Balance (FCY) → `acct_bal_accrual.closing_bal`
- Accrued Balance (LCY) → `acct_bal_accrual.lcy_amt`
- WAE = `lcy_amt / closing_bal`

**Constraint:** Do NOT read from `intt_accr_tran` for these values.

---

## WHAT WAS WRONG

### Before Fix (Lines 169-171)

```java
// Get total accrued amounts directly from intt_accr_tran (sum of all S-type entries)
BigDecimal accruedFcy = inttAccrTranRepository.sumFcyAmtByAccountNo(accountNo);
BigDecimal accruedLcy = inttAccrTranRepository.sumLcyAmtByAccountNo(accountNo);
```

**Problem:** Reading directly from `intt_accr_tran` table instead of `acct_bal_accrual`.

---

## FIX APPLIED

### After Fix (Lines 174-183)

```java
// Read all accrued values from acct_bal_accrual (single source of truth)
Optional<AcctBalAccrual> accrualOpt = acctBalAccrualRepository.findLatestByAccountNo(accountNo);

BigDecimal totalFcy = BigDecimal.ZERO;
BigDecimal totalLcy = BigDecimal.ZERO;

if (accrualOpt.isPresent()) {
    AcctBalAccrual accrual = accrualOpt.get();
    totalFcy = accrual.getClosingBal() != null ? accrual.getClosingBal() : BigDecimal.ZERO;
    totalLcy = accrual.getLcyAmt() != null ? accrual.getLcyAmt() : BigDecimal.ZERO;
}
```

**Changes Made:**
1. ✅ Removed `inttAccrTranRepository.sumFcyAmtByAccountNo()` call
2. ✅ Removed `inttAccrTranRepository.sumLcyAmtByAccountNo()` call
3. ✅ Added `acctBalAccrualRepository.findLatestByAccountNo()` call
4. ✅ Read `totalFcy` from `accrual.getClosingBal()`
5. ✅ Read `totalLcy` from `accrual.getLcyAmt()`
6. ✅ Calculate WAE from these stored values

---

## DATA FLOW

### Correct Flow (After Fix)

```
acct_bal_accrual (populated during EOD)
  ├─ closing_bal (FCY total)
  └─ lcy_amt (LCY total)
       ↓
  Read via findLatestByAccountNo()
       ↓
  totalFcy = closing_bal
  totalLcy = lcy_amt
  waeRate = totalLcy / totalFcy
       ↓
  Display on UI
```

### Why This Is Correct

1. **EOD Process** already sums `intt_accr_tran` entries and stores results in `acct_bal_accrual`
2. **Single query** to `acct_bal_accrual` is more efficient than summing `intt_accr_tran`
3. **Consistent values** - all parts of the system read from the same source
4. **No date filtering needed** - `closing_bal` and `lcy_amt` already represent current totals

---

## VERIFICATION

### Database Check

```sql
SELECT account_no, closing_bal, lcy_amt, 
       lcy_amt / closing_bal as wae
FROM acct_bal_accrual
WHERE account_no = '100000008001'
ORDER BY tran_date DESC
LIMIT 1;
```

**Expected Result:**
```
account_no       | closing_bal | lcy_amt | wae
100000008001    | 1.64        | 190.61  | 116.2256
```

### UI Display

Navigate to: **Home > Interest Capitalization > Click account 100000008001**

**Expected Display:**
- Accrued Balance (FCY): **1.64 USD**
- Accrued Balance (LCY): **190.61 BDT**
- WAE Rate: **116.2256**
- Mid Rate: **114.15** (from fx_rate_master)
- Estimated Gain/Loss: **190.61 - (1.64 × 114.15) = +3.47 BDT (Gain)**

**These values must EXACTLY match the database values from `acct_bal_accrual` table.**

---

## FILES MODIFIED

### InterestCapitalizationService.java (Lines 159-210)

**Method:** `getCapitalizationPreview()`

**Changed:**
- Data source: `intt_accr_tran` → `acct_bal_accrual`
- Repository: `inttAccrTranRepository` → `acctBalAccrualRepository`
- Method: `sumFcyAmtByAccountNo()` + `sumLcyAmtByAccountNo()` → `findLatestByAccountNo()`
- Fields: Direct SUM queries → `getClosingBal()` + `getLcyAmt()`

**Added:** Comment explaining this is the single source of truth

---

## WHAT WAS NOT CHANGED

✅ No changes to other methods  
✅ No changes to capitalization execution logic  
✅ No changes to EOD logic  
✅ No changes to transaction posting  
✅ No changes to DTO structure  
✅ Only changed the READ path for preview

---

## WHY acct_bal_accrual IS THE CORRECT SOURCE

### 1. Design Intent
The `acct_bal_accrual` table was specifically created to store aggregated accrual balances:
- Populated during EOD Batch Job 6
- Contains pre-computed totals from `intt_accr_tran`
- Updated in a controlled, consistent manner

### 2. Performance
- Single table lookup vs. SUM aggregation across many rows
- Indexed by account_no for fast retrieval
- No filtering or in-memory processing needed

### 3. Consistency
- All system components read from same source
- Values match what EOD calculated
- No risk of timing issues or partial data

### 4. Simplicity
- One query, two fields, simple division
- No date filtering logic needed
- No status checking needed
- Clear and maintainable

---

## TESTING CHECKLIST

### 1. Database Verification
```sql
-- Check acct_bal_accrual values
SELECT account_no, closing_bal, lcy_amt
FROM acct_bal_accrual
WHERE account_no = '100000008001';
```
Expected: `closing_bal = 1.64, lcy_amt = 190.61`

### 2. API Test
```bash
curl http://localhost:8080/api/interest-capitalization/100000008001/preview
```
Expected JSON:
```json
{
  "accountNo": "100000008001",
  "currency": "USD",
  "accruedFcy": 1.64,
  "accruedLcy": 190.61,
  "waeRate": 116.2256,
  "midRate": 114.15,
  "estimatedGainLoss": 3.47
}
```

### 3. UI Verification
- Open Interest Capitalization Details for account 100000008001
- Verify all displayed values match database exactly
- Check logs for: `CapitalizationPreview | account=100000008001 FCY=1.64 LCY=190.61 WAE=116.2256`

---

## COMPILATION STATUS

✅ **No linter errors**  
✅ **No compilation errors**  
✅ **Ready for testing**

---

## SUMMARY

Fixed the Interest Capitalization Details page to read from the correct source (`acct_bal_accrual`) instead of aggregating from `intt_accr_tran`. This ensures:

1. **Consistency** - Same values shown throughout the system
2. **Performance** - Single table lookup instead of SUM aggregation
3. **Correctness** - Uses EOD-computed values (single source of truth)
4. **Simplicity** - Clean, maintainable code with clear intent

**The UI will now display exactly what is stored in `acct_bal_accrual.closing_bal` and `acct_bal_accrual.lcy_amt`.**
