# WAE Validation Fix - Quick Reference

## What Changed?

**File:** `TransactionService.java`
**Methods Modified:** 1 existing method enhanced, 1 new method added

---

## Method 1: Enhanced `calculateWaeWithDiagnostics()`

**Location:** Lines ~1161-1290

**Change:** Added fallback logic when `acct_bal_lcy` record is missing

### Original Flow:
```
1. Get FCY from acc_bal → Success
2. Get LCY from acct_bal_lcy → FAIL (missing for current date)
3. Return null → Transaction rejected ❌
```

### New Flow:
```
1. Get FCY from acc_bal → Success
2. Get LCY from acct_bal_lcy → FAIL (missing for current date)
3. Call calculateLiveWAEFromAccBal() → Success
4. Return live WAE → Transaction succeeds ✅
```

### Key Code Addition:
```java
if (acctBalLcyOpt.isEmpty()) {
    log.warn("WAE DIAGNOSTIC: No acct_bal_lcy record found...");
    log.info("WAE FALLBACK: Attempting to calculate live WAE from acc_bal table");
    
    BigDecimal liveWAE = calculateLiveWAEFromAccBal(accountNo, tranDate);
    if (liveWAE != null) {
        log.info("═══ LIVE WAE CALCULATED from acc_bal: {} ═══", liveWAE);
        return liveWAE;
    } else {
        log.error("WAE DIAGNOSTIC: Live WAE calculation failed.");
        return null;
    }
}
```

---

## Method 2: New `calculateLiveWAEFromAccBal()`

**Location:** Lines ~1292-1390

**Purpose:** Calculate WAE from `acc_bal` + `tran_table` when `acct_bal_lcy` doesn't exist

### Formula:
```
WAE = Total_LCY / Total_FCY

Where:
  Total_FCY = FCY_Opening + FCY_Credits - FCY_Debits     (from acc_bal)
  Total_LCY = LCY_Opening + LCY_Credits - LCY_Debits     (from acct_bal_lcy + tran_table)
```

### Data Sources:

| Component | Source | Field/Query |
|-----------|--------|-------------|
| FCY Opening | `acc_bal` | `Opening_Bal` |
| FCY Credits | `acc_bal` | `CR_Summation` |
| FCY Debits | `acc_bal` | `DR_Summation` |
| LCY Opening | `acct_bal_lcy` (previous day) | `Closing_Bal_lcy` |
| LCY Credits | `tran_table` (today) | `SUM(Credit_Amount)` |
| LCY Debits | `tran_table` (today) | `SUM(Debit_Amount)` |

### Logic Steps:

1. **Get FCY from acc_bal**
   ```java
   AcctBal accBal = acctBalRepository.findByAccountNoAndTranDate(...);
   BigDecimal totalFcy = fcyOpb + fcyCr - fcyDr;
   if (totalFcy == 0) return null; // Cannot divide by zero
   ```

2. **Get LCY Opening Balance (3-tier fallback)**
   ```java
   // Try previous day
   acctBalLcyRepository.findByAccountNoAndTranDate(accountNo, previousDay)
   
   // Fallback: Latest before current date
   acctBalLcyRepository.findByAccountNoAndTranDateBeforeOrderByTranDateDesc(...)
   
   // Fallback: Use 0 (new account)
   ```

3. **Get Today's LCY Movements**
   ```java
   BigDecimal lcyCr = tranTableRepository.sumCreditTransactionsForAccountOnDate(...);
   BigDecimal lcyDr = tranTableRepository.sumDebitTransactionsForAccountOnDate(...);
   ```

4. **Calculate WAE**
   ```java
   BigDecimal totalLcy = lcyOpb + lcyCr - lcyDr;
   BigDecimal liveWae = totalLcy.divide(totalFcy, 4, RoundingMode.HALF_UP);
   return liveWae;
   ```

---

## When Does Fallback Trigger?

### Triggers When:
- ✅ FCY account (not BDT)
- ✅ Current date transaction
- ✅ No `acct_bal_lcy` record exists for current date
- ✅ Same-day credit-then-debit scenario

### Does NOT Trigger When:
- ❌ BDT account (WAE = 1.0 always)
- ❌ `acct_bal_lcy` record exists (uses normal path)
- ❌ Next day after EOD (acct_bal_lcy created)

---

## Example Calculation

### Scenario:
- Account: USD account with 0 opening balance
- Transaction 1 (today): Credit 15,000 USD at 114.6000
- Transaction 2 (today): Debit 15,000 USD (settlement)

### Calculation:

**FCY (from acc_bal):**
```
Opening:  0.00 USD
Credits:  15,000.00 USD
Debits:   0.00 USD
Total FCY: 15,000.00 USD
```

**LCY (from acct_bal_lcy + tran_table):**
```
Opening (previous day):  0.00 BDT
Credits (today):         1,719,000.00 BDT  (15,000 × 114.6000)
Debits (today):          0.00 BDT
Total LCY: 1,719,000.00 BDT
```

**Live WAE:**
```
WAE = 1,719,000.00 / 15,000.00 = 114.6000
```

**Result:** Transaction succeeds using WAE = 114.6000 ✅

---

## Repository Methods Used

All methods already exist - no new methods needed!

```java
// AcctBalRepository
acctBalRepository.findByAccountNoAndTranDate(accountNo, tranDate)
acctBalRepository.findLatestByAccountNo(accountNo)

// AcctBalLcyRepository
acctBalLcyRepository.findByAccountNoAndTranDate(accountNo, tranDate)
acctBalLcyRepository.findByAccountNoAndTranDateBeforeOrderByTranDateDesc(accountNo, tranDate)

// TranTableRepository
tranTableRepository.sumCreditTransactionsForAccountOnDate(accountNo, tranDate)
tranTableRepository.sumDebitTransactionsForAccountOnDate(accountNo, tranDate)
```

---

## Log Messages to Monitor

### Success - Fallback Used:
```
WAE FALLBACK: Attempting to calculate live WAE from acc_bal table
LIVE WAE CALCULATION from acc_bal START for account [account_no]
LIVE WAE: FCY calculation - OPB=[x], CR=[y], DR=[z], Total=[total]
LIVE WAE: LCY calculation - OPB=[x], CR=[y], DR=[z], Total=[total]
═══ LIVE WAE CALCULATED: [lcy] / [fcy] = [wae] ═══
```

### Success - Normal Path:
```
WAE DIAGNOSTIC: acct_bal_lcy found for [account_no]
═══ WAE CALCULATED for account [account_no]: [lcy] / [fcy] = [wae] ═══
```

### Error - Calculation Failed:
```
LIVE WAE: Error calculating live WAE for account [account_no]: [error]
WAE DIAGNOSTIC: Live WAE calculation failed.
```

---

## Testing Quick Check

### Minimum Test:
1. Find USD account with 0 opening balance
2. Credit 10,000 USD today
3. Debit 10,000 USD today (same day)
4. Check: Transaction succeeds without "WAE not available" error

### Success Indicators:
- ✅ Transaction completes successfully
- ✅ See "LIVE WAE CALCULATED" in logs
- ✅ WAE value matches mid rate used in first transaction
- ✅ No "WAE not available" error

---

## Impact Summary

| Aspect | Impact |
|--------|--------|
| **Code Changes** | 2 methods (1 enhanced, 1 new) |
| **Lines Added** | ~100 lines |
| **Database Changes** | None (uses existing tables) |
| **Configuration** | None |
| **EOD Batch** | No changes |
| **Existing Logic** | No changes (only adds fallback) |
| **Risk Level** | Low (non-breaking, fallback only) |
| **Testing Priority** | High (new user-facing feature) |

---

## Deployment Checklist

- [x] Code compiles successfully
- [x] No linter errors
- [ ] Unit tests pass (if applicable)
- [ ] Integration tests pass
- [ ] Manual testing completed (see Testing Guide)
- [ ] Logs reviewed (no unexpected errors)
- [ ] Performance acceptable (no significant slowdown)
- [ ] Documentation updated
- [ ] Team briefed on changes

---

## Rollback Plan

If issues occur:

1. **Immediate:** Revert `TransactionService.java` to previous version
2. **Alternative:** Comment out the fallback block in `calculateWaeWithDiagnostics()`
3. **Restore:** Original behavior returns (rejects same-day credit-then-debit)

**Note:** Rollback is safe - no database changes, no data corruption risk.

---

## Support Information

### If Transaction Fails:

1. **Check Logs:**
   - Look for "WAE DIAGNOSTIC" messages
   - Identify which calculation path was used
   - Check for error messages

2. **Verify Data:**
   - Query `acc_bal` - does record exist for account/date?
   - Query `acct_bal_lcy` - does previous day record exist?
   - Query `tran_table` - are today's transactions recorded?

3. **Common Issues:**
   - Zero FCY balance → Cannot calculate WAE (expected)
   - Missing `acc_bal` record → Database integrity issue
   - Sum queries return null → Check `tran_table` data

### Contact:
- Codebase: `moneymarket/src/main/java/com/example/moneymarket/service/TransactionService.java`
- Method: `calculateLiveWAEFromAccBal()`
- Lines: ~1292-1390

---

**Version:** 1.0
**Date:** 2026-03-16
**Author:** CBS Development Team
