# Implementation Summary: lcy_amt Column in acct_bal_accrual

**Date:** March 15, 2026  
**Task:** Add lcy_amt column to acct_bal_accrual table and use it during interest capitalization

---

## OVERVIEW

This implementation adds a new `lcy_amt` column to the `acct_bal_accrual` table to store the accumulated LCY (local currency) amounts from daily interest accrual entries. This value is then used during interest capitalization to calculate the correct WAE (Weighted Average Exchange Rate) for FCY (foreign currency) accounts.

### Business Problem Solved

Previously, the capitalization WAE calculation was done by querying `innt_accr_tran` entries on-the-fly. This approach had potential issues:
- Performance overhead from complex queries during capitalization
- Risk of inconsistency between accrual balance and WAE calculation
- Difficulty in auditing the LCY amounts used for WAE

The new approach stores the LCY total alongside the FCY total in `acct_bal_accrual`, making both values immediately available during capitalization with full audit trail.

---

## CHANGES IMPLEMENTED

### 1. Database Schema Change

**File:** `V37__add_lcy_amt_to_acct_bal_accrual.sql`

Added new column:
```sql
ALTER TABLE acct_bal_accrual
ADD COLUMN lcy_amt DECIMAL(20,2) DEFAULT 0.00 NOT NULL
AFTER closing_bal;
```

Also includes a one-time backfill to populate existing records:
```sql
UPDATE acct_bal_accrual aba
LEFT JOIN (
    SELECT account_no, tran_date, SUM(lcy_amt) as total_lcy
    FROM intt_accr_tran
    WHERE accr_tran_id LIKE 'S%'
    AND original_dr_cr_flag IS NULL
    GROUP BY account_no, tran_date
) iat ON aba.account_no = iat.account_no AND aba.tran_date = iat.tran_date
SET aba.lcy_amt = COALESCE(iat.total_lcy, 0.00);
```

### 2. Entity Update

**File:** `AcctBalAccrual.java`

Added field:
```java
@Builder.Default
@Column(name = "lcy_amt", precision = 20, scale = 2, nullable = false)
private BigDecimal lcyAmt = BigDecimal.ZERO;
```

This field will be automatically populated with getter/setter by Lombok's `@Data` annotation.

### 3. Repository Query Addition

**File:** `InttAccrTranRepository.java`

Added new query method to sum LCY amounts from S-type entries:
```java
@Query("SELECT COALESCE(SUM(i.lcyAmt), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.accrTranId LIKE 'S%' " +
       "AND i.originalDrCrFlag IS NULL")
BigDecimal sumLcyAmtByAccountAndDate(@Param("accountNo") String accountNo,
                                      @Param("accrualDate") LocalDate accrualDate);
```

This query:
- Sums `lcy_amt` from all S-type (system accrual) entries
- Excludes value date interest entries (originalDrCrFlag IS NULL)
- Returns 0 if no entries exist (COALESCE)

### 4. EOD Service Update - Populate lcy_amt

**File:** `InterestAccrualAccountBalanceService.java`

**Method:** `processAccountAccrualBalance()`

Added after value date interest impact calculation:
```java
// Step F2: Calculate LCY amount - sum all LCY amounts from S-type pending accrual entries
BigDecimal totalLcy = inttAccrTranRepository.sumLcyAmtByAccountAndDate(accountNo, systemDate);
log.info("Total LCY Amount (from S-type entries): {}", totalLcy);
```

Updated logging:
```java
log.info("lcy_amt: {} (sum of LCY from S-type entries)", totalLcy);
```

**Method:** `saveOrUpdateAccrualBalance()`

Updated method signature to include `lcyAmt` parameter:
```java
private void saveOrUpdateAccrualBalance(String accountNo, String glNum, LocalDate tranDate,
                                       BigDecimal openingBal, BigDecimal drSummation,
                                       BigDecimal crSummation, BigDecimal closingBal,
                                       BigDecimal interestAmount, BigDecimal lcyAmt)
```

Added field assignment in both update and create paths:
```java
// Update path:
acctBalAccrual.setLcyAmt(lcyAmt);

// Create path:
.lcyAmt(lcyAmt)
```

### 5. Capitalization Service Update - Use lcy_amt for WAE

**File:** `InterestCapitalizationService.java`

**Method:** `calculateAccrualWae()`

Complete rewrite to use `acct_bal_accrual` instead of querying `intt_accr_tran`:

**OLD APPROACH:**
- Query all S-type credit accrual entries from `innt_accr_tran`
- Filter by transaction date > last capitalization date
- Sum `fcyAmt` and `lcyAmt` from matching entries
- Calculate WAE = totalLcy / totalFcy

**NEW APPROACH:**
```java
private BigDecimal calculateAccrualWae(String acctNum, String ccy, LocalDate lastCapDate) {
    if ("BDT".equals(ccy)) return BigDecimal.ONE;

    Optional<AcctBalAccrual> accrualOpt = acctBalAccrualRepository.findLatestByAccountNo(acctNum);
    
    if (accrualOpt.isEmpty()) {
        log.warn("No accrual balance record found for account {}. Using WAE = 1.0", acctNum);
        return BigDecimal.ONE;
    }

    AcctBalAccrual accrual = accrualOpt.get();
    
    // totalLcy from acct_bal_accrual.lcy_amt
    BigDecimal totalLcy = accrual.getLcyAmt() != null ? accrual.getLcyAmt() : BigDecimal.ZERO;
    
    // totalFcy from acct_bal_accrual.closing_bal
    BigDecimal totalFcy = accrual.getClosingBal() != null ? accrual.getClosingBal() : BigDecimal.ZERO;

    if (totalFcy.compareTo(BigDecimal.ZERO) == 0) {
        log.warn("AccrualWAE | FCY accrual is zero for account={}, using 1.0", acctNum);
        return BigDecimal.ONE;
    }

    BigDecimal accrualWae = totalLcy.divide(totalFcy, 4, RoundingMode.HALF_UP);
    
    log.info("AccrualWAE | account={} totalLCY={} totalFCY={} WAE={}",
            acctNum, totalLcy, totalFcy, accrualWae);
    
    return accrualWae;
}
```

**Key Benefits:**
1. Single query to `acct_bal_accrual` instead of multiple joins/filters
2. Values are consistent with what EOD calculated
3. No date filtering needed - `lcy_amt` already represents current accrued balance
4. Full audit trail through `acct_bal_accrual` table

**Method:** `getCapitalizationPreview()`

Updated to read `accruedLcy` directly from `acct_bal_accrual.lcy_amt`:

```java
BigDecimal accruedLcy;
Optional<AcctBalAccrual> accrualOpt = acctBalAccrualRepository.findLatestByAccountNo(accountNo);
if (accrualOpt.isPresent() && !"BDT".equals(ccy)) {
    accruedLcy = accrualOpt.get().getLcyAmt() != null ? 
                accrualOpt.get().getLcyAmt() : BigDecimal.ZERO;
} else {
    accruedLcy = accruedFcy; // For BDT accounts, FCY = LCY
}
```

This ensures the preview shows the exact LCY amount that will be used during capitalization.

---

## DATA FLOW

### During EOD (Batch Job 6):

1. **Interest Accrual Service** creates S-type entries in `innt_accr_tran`:
   - `fcy_amt` = interest amount in account currency
   - `exchange_rate` = mid rate for that day
   - `lcy_amt` = fcy_amt × exchange_rate

2. **EOD Batch Job 6** (InterestAccrualAccountBalanceService):
   - Reads all S-type entries for the account/date
   - Sums `fcy_amt` → stored as `closing_bal` in `acct_bal_accrual`
   - Sums `lcy_amt` → stored as `lcy_amt` in `acct_bal_accrual`
   - Both values are now persisted and auditable

### During Capitalization:

1. **InterestCapitalizationService.capitalizeInterest()**:
   - Calls `calculateAccrualWae()` to get the WAE rate
   - WAE is computed as: `acct_bal_accrual.lcy_amt / acct_bal_accrual.closing_bal`
   - Creates debit entry (expense) at WAE rate
   - Creates credit entry (customer account) at MID rate
   - Creates gain/loss entry if WAE ≠ MID

2. **Preview API** (`getCapitalizationPreview()`):
   - Returns `accruedLcy` from `acct_bal_accrual.lcy_amt`
   - Frontend displays this value in the preview dialog

---

## VERIFICATION QUERIES

After EOD runs and before capitalization:

```sql
-- Check that lcy_amt is populated
SELECT account_no, closing_bal, lcy_amt, tran_date
FROM acct_bal_accrual
WHERE account_no = '<test_usd_account>'
ORDER BY tran_date DESC
LIMIT 5;
```

Expected: `lcy_amt` should equal the sum of `lcy_amt` from S-type entries for that date.

Cross-check:
```sql
SELECT 
    account_no,
    SUM(fcy_amt) as total_fcy,
    SUM(lcy_amt) as total_lcy,
    SUM(lcy_amt) / SUM(fcy_amt) as calculated_wae
FROM innt_accr_tran
WHERE account_no = '<test_usd_account>'
  AND accr_tran_id LIKE 'S%'
  AND original_dr_cr_flag IS NULL
  AND accrual_date = '<test_date>'
GROUP BY account_no;
```

Expected: `total_lcy` matches `acct_bal_accrual.lcy_amt` for that date.

After capitalization:

```sql
-- Check capitalization entry uses correct WAE
SELECT 
    accr_tran_id,
    account_no,
    tran_ccy,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    dr_cr_flag
FROM innt_accr_tran
WHERE accr_tran_id LIKE 'C%'
  AND account_no = '<test_usd_account>'
ORDER BY tran_date DESC
LIMIT 3;
```

Expected: 
- `exchange_rate` should be WAE (e.g., 112.6135), NOT 1.0000
- `lcy_amt` should equal `fcy_amt × exchange_rate`

---

## WHAT WAS NOT CHANGED

As per strict constraints, the following were NOT modified:

1. **MCT WAE Formula** - Multi-Currency Transaction WAE calculation using `acc_bal + acct_bal_lcy` remains unchanged
2. **EOD Steps** - No changes to other EOD batch jobs
3. **Validation Logic** - No changes to existing validation rules
4. **Settlement Rules** - No changes to transaction settlement logic
5. **Business Rules** - No changes to interest calculation rules
6. **BDT Capitalization** - BDT accounts still use exchange rate = 1.0
7. **Gain/Loss GL Numbers** - FX_GAIN_GL and FX_LOSS_GL remain unchanged
8. **Other Balance Tables** - `acc_bal`, `acct_bal_lcy`, `gl_bal` remain unchanged

---

## TESTING CHECKLIST

### 1. Migration Test
- [ ] Run V37 migration on test database
- [ ] Verify column exists: `DESCRIBE acct_bal_accrual;`
- [ ] Verify backfill populated existing records
- [ ] Check for any migration errors

### 2. EOD Test
- [ ] Run EOD for a date with FCY account accruals
- [ ] Verify `lcy_amt` is populated in `acct_bal_accrual`
- [ ] Cross-check sum matches `innt_accr_tran` S-type entries
- [ ] Check logs for "Total LCY Amount" message

### 3. Capitalization Test - USD Account
- [ ] Check preview API returns correct `accruedLcy`
- [ ] Perform capitalization
- [ ] Verify WAE rate is correct (not 1.0000)
- [ ] Verify debit entry uses WAE
- [ ] Verify credit entry uses MID rate
- [ ] Verify gain/loss entry created if WAE ≠ MID
- [ ] Verify LCY balance (DR total = CR total within 0.01 tolerance)

### 4. Capitalization Test - BDT Account
- [ ] Check preview API returns `accruedLcy = accruedFcy`
- [ ] Perform capitalization
- [ ] Verify exchange rate = 1.0
- [ ] Verify no gain/loss entry created

### 5. Edge Case Tests
- [ ] Account with no accrual balance record (new account)
- [ ] Account with zero closing_bal
- [ ] Account with multiple S-type entries on same day
- [ ] Account with value date interest (should be excluded from lcy_amt)

---

## FILES MODIFIED

1. `V37__add_lcy_amt_to_acct_bal_accrual.sql` - NEW migration file
2. `AcctBalAccrual.java` - Added `lcyAmt` field
3. `InttAccrTranRepository.java` - Added `sumLcyAmtByAccountAndDate()` query
4. `InterestAccrualAccountBalanceService.java` - Populate `lcy_amt` during EOD
5. `InterestCapitalizationService.java` - Use `lcy_amt` for WAE calculation

---

## ROLLBACK PLAN

If issues are discovered after deployment:

### Immediate Rollback (before next EOD):
1. Revert code changes to previous versions
2. Keep database column (data is harmless if unused)
3. Monitor for any errors

### Full Rollback (if column causes issues):
```sql
-- Only if absolutely necessary
ALTER TABLE acct_bal_accrual DROP COLUMN lcy_amt;
```

Then revert all code changes.

---

## PERFORMANCE IMPACT

**Positive:**
- Capitalization WAE calculation is now a simple read from `acct_bal_accrual` instead of complex join/filter query
- Single database query vs. potentially hundreds of rows scanned

**Neutral:**
- EOD Job 6 adds one additional query per account (summing LCY amounts)
- This is negligible compared to existing processing

**Storage:**
- Added one DECIMAL(20,2) column = 9 bytes per row
- Minimal impact on database size

---

## IMPLEMENTATION COMPLETE

All code changes have been implemented and are ready for testing. No compilation errors detected.

Next steps:
1. Review this summary document
2. Build and deploy the application
3. Run Flyway migration (V37)
4. Test with EOD and capitalization scenarios
5. Verify results using the queries provided above
