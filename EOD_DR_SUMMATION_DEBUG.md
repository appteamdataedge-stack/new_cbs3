# EOD DR Summation Debug - Investigation

**Date:** 2026-01-29  
**Issue:** `dr_summation` appears to be including `opening_bal` value  
**Status:** 🔍 DEBUGGING ADDED

---

## 🔴 REPORTED PROBLEM

### User's Observation:
```
Expected:
- opening_bal = 45
- dr_summation = 45 (sum of DR transactions only)
- cr_summation = 5 (sum of CR transactions only)
- closing_bal = 5 (45 + 5 - 45)

Actual:
- opening_bal = 45 ✅
- dr_summation = 50 ❌ (should be 45, appears to be 45 + 5)
- cr_summation = 5 ✅
- closing_bal = 5 ✅ (correct by coincidence)
```

**Issue:** `dr_summation = 50` instead of `45`, suggesting it's incorrectly adding 5 (the CR amount or something else).

---

## 🔍 INVESTIGATION STEPS

### Step 1: Examined Repository Queries

**DR Summation Query (Lines 81-87 of InttAccrTranRepository):**
```java
@Query("SELECT COALESCE(SUM(i.amount), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.drCrFlag = 'D' " +
       "AND i.originalDrCrFlag IS NULL")
BigDecimal sumDebitAmountsByAccountAndDate(accountNo, accrualDate);
```

**Analysis:**
- ✅ Query only sums `amount` field (no opening_bal)
- ✅ Filters by `drCrFlag = 'D'` (debits only)
- ✅ Excludes value date interest (`originalDrCrFlag IS NULL`)
- ✅ Query appears CORRECT

**CR Summation Query (Lines 98-104 of InttAccrTranRepository):**
```java
@Query("SELECT COALESCE(SUM(i.amount), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.drCrFlag = 'C' " +
       "AND i.originalDrCrFlag IS NULL")
BigDecimal sumCreditAmountsByAccountAndDate(accountNo, accrualDate);
```

**Analysis:**
- ✅ Query only sums `amount` field (no opening_bal)
- ✅ Filters by `drCrFlag = 'C'` (credits only)
- ✅ Query appears CORRECT

---

### Step 2: Examined EOD Processing Logic

**Processing Method (Lines 103-168 of InterestAccrualAccountBalanceService):**
```java
// Get opening balance
BigDecimal openingBal = getOpeningBalance(accountNo, systemDate);

// Calculate summations
BigDecimal drSummation = inttAccrTranRepository.sumDebitAmountsByAccountAndDate(...);
BigDecimal crSummation = inttAccrTranRepository.sumCreditAmountsByAccountAndDate(...);

// Calculate value date interest impact
BigDecimal valueDateInterestImpact = calculateValueDateInterestImpact(...);

// Calculate closing balance
BigDecimal closingBal = openingBal.add(crSummation).subtract(drSummation).add(valueDateInterestImpact);

// Save to database
saveOrUpdateAccrualBalance(accountNo, glNum, systemDate, openingBal, drSummation, crSummation, closingBal, interestAmount);
```

**Analysis:**
- ✅ `drSummation` is NOT modified after query
- ✅ `opening_bal` is NOT added to `drSummation`
- ✅ Summations are saved as-is from repository queries
- ✅ Logic appears CORRECT

---

### Step 3: Possible Causes

#### Theory 1: Database Data Issue
The `intt_accr_tran` table might have incorrect data:
- Extra DR transaction with amount=5
- Duplicate DR transaction
- Wrong `drCrFlag` on some records

#### Theory 2: Value Date Interest Confusion
Value date interest transactions might be:
- Not properly excluded by `originalDrCrFlag IS NULL`
- Double-counted somehow
- Miscategorized

#### Theory 3: Caching or State Issue
- JPA cache returning stale data
- Transaction isolation issue
- Multiple EOD runs without clearing data

#### Theory 4: Display vs Storage Issue
- Values calculated correctly
- Display logic showing wrong value
- Frontend/API formatting issue

---

## ✅ DEBUGGING ADDED

### Change 1: Enhanced Logging in InterestAccrualAccountBalanceService

**Added comprehensive logging to track values:**

```java
log.info("========================================");
log.info("=== EOD BATCH JOB 6: PROCESSING ACCOUNT ===");
log.info("========================================");
log.info("Account Number: {}", accountNo);
log.info("Account Type: {}", accountType);
log.info("GL Number: {}", glNum);
log.info("System Date: {}", systemDate);
log.info("Opening Balance (from previous day): {}", openingBal);

log.info("--- Querying intt_accr_tran for DR and CR summations ---");
log.info("DR Summation (SUM of DR transactions, excluding value date): {}", drSummation);
log.info("CR Summation (SUM of CR transactions, excluding value date): {}", crSummation);

// List all transactions for debugging
log.info("Total transactions in intt_accr_tran for this account/date: {}", allTransactions.size());
for (InttAccrTran tran : allTransactions) {
    log.info("  Transaction: ID={}, DrCrFlag={}, Amount={}, OriginalDrCrFlag={}", 
        tran.getAccrTranId(), tran.getDrCrFlag(), tran.getAmount(), tran.getOriginalDrCrFlag());
}

log.info("Value Date Interest Impact: {}", valueDateInterestImpact);
log.info("Formula: closing_bal = opening_bal + cr_summation - dr_summation + value_date_impact");
log.info("Formula: {} = {} + {} - {} + {}", closingBal, openingBal, crSummation, drSummation, valueDateInterestImpact);

log.info("=== VALUES BEING SAVED TO ACCT_BAL_ACCRUAL ===");
log.info("opening_bal: {}", openingBal);
log.info("dr_summation: {} (should ONLY be sum of DR transactions)", drSummation);
log.info("cr_summation: {} (should ONLY be sum of CR transactions)", crSummation);
log.info("closing_bal: {}", closingBal);
log.info("interest_amount: {}", interestAmount);
```

---

### Change 2: Added Debug Method to Repository

**Added method to retrieve all transactions for debugging:**

```java
/**
 * Find all transactions for a specific account and accrual date (for debugging)
 */
List<InttAccrTran> findByAccountNoAndAccrualDate(String accountNo, LocalDate accrualDate);
```

---

## 🧪 HOW TO DEBUG

### Step 1: Run EOD with Enhanced Logging

After deploying the changes:
1. Create test transactions in `intt_accr_tran`
2. Run EOD Batch Job 6
3. Check logs for detailed output

### Step 2: Analyze Log Output

**Look for:**

```
========================================
=== EOD BATCH JOB 6: PROCESSING ACCOUNT ===
========================================
Account Number: 1101010000001
Opening Balance (from previous day): 45

--- Querying intt_accr_tran for DR and CR summations ---
DR Summation (SUM of DR transactions, excluding value date): ???
CR Summation (SUM of CR transactions, excluding value date): ???

Total transactions in intt_accr_tran for this account/date: ???
  Transaction: ID=S20260129001, DrCrFlag=C, Amount=5, OriginalDrCrFlag=null
  Transaction: ID=C20260129001, DrCrFlag=D, Amount=45, OriginalDrCrFlag=null
  
=== VALUES BEING SAVED TO ACCT_BAL_ACCRUAL ===
opening_bal: 45
dr_summation: ??? (should be 45)
cr_summation: ??? (should be 5)
closing_bal: ???
```

---

### Step 3: Verify Database State

**Before EOD:**
```sql
-- Check what transactions exist
SELECT 
    Accr_Tran_Id,
    Account_No,
    Dr_Cr_Flag,
    Amount,
    Original_Dr_Cr_Flag,
    Accrual_Date
FROM Intt_Accr_Tran
WHERE Account_No = '1101010000001'
  AND Accrual_Date = '2026-01-29'
ORDER BY Accr_Tran_Id;

-- Expected:
-- S20260129001, C, 5.00, null
-- C20260129001, D, 45.00, null
```

**After EOD:**
```sql
-- Check what was saved
SELECT 
    Account_No,
    Tran_Date,
    Opening_Bal,
    DR_Summation,
    CR_Summation,
    Closing_Bal
FROM Acct_Bal_Accrual
WHERE Account_No = '1101010000001'
  AND Tran_Date = '2026-01-29';

-- Expected:
-- Opening: 45.00
-- DR_Summation: 45.00 (not 50.00!)
-- CR_Summation: 5.00
-- Closing: 5.00
```

---

### Step 4: Manual Verification Query

**Verify the repository query manually:**
```sql
-- This is what the repository query does
SELECT COALESCE(SUM(Amount), 0) AS DR_Summation
FROM Intt_Accr_Tran
WHERE Account_No = '1101010000001'
  AND Accrual_Date = '2026-01-29'
  AND Dr_Cr_Flag = 'D'
  AND Original_Dr_Cr_Flag IS NULL;

-- Expected result: 45.00 (not 50.00)

SELECT COALESCE(SUM(Amount), 0) AS CR_Summation
FROM Intt_Accr_Tran
WHERE Account_No = '1101010000001'
  AND Accrual_Date = '2026-01-29'
  AND Dr_Cr_Flag = 'C'
  AND Original_Dr_Cr_Flag IS NULL;

-- Expected result: 5.00
```

---

## 🎯 EXPECTED FINDINGS

After running with debug logging, we should be able to determine:

### Scenario A: Query Returns Wrong Value
```
Logs show:
- DR Summation (from query): 50 ❌

Root cause: Database has extra DR transaction or duplicate
Solution: Clean up database, fix transaction creation logic
```

### Scenario B: Query Returns Correct Value, But Saved Wrong
```
Logs show:
- DR Summation (from query): 45 ✅
- dr_summation (being saved): 50 ❌

Root cause: Value being modified between query and save
Solution: Find where modification happens (shouldn't exist based on code review)
```

### Scenario C: Everything Correct in Code, Database Shows Wrong
```
Logs show:
- DR Summation (from query): 45 ✅
- dr_summation (being saved): 45 ✅

Database shows:
- DR_Summation: 50 ❌

Root cause: Database trigger, constraint, or concurrent update
Solution: Check database for triggers/constraints
```

---

## 📝 FILES MODIFIED

1. **InterestAccrualAccountBalanceService.java**
   - Added comprehensive debug logging (Lines 122-161)
   - Shows opening balance, query results, calculations, and saved values

2. **InttAccrTranRepository.java**
   - Added `findByAccountNoAndAccrualDate()` method for debugging
   - Returns all transactions for detailed inspection

---

## 🚀 NEXT STEPS

1. **Deploy Changes:**
   ```bash
   mvn clean package -DskipTests
   ```

2. **Restart Backend Server**

3. **Create Test Scenario:**
   - Account with opening_bal = 45
   - S transaction: CR 5
   - C transaction: DR 45

4. **Run EOD Batch Job 6**

5. **Check Logs:**
   - Look for "EOD BATCH JOB 6: PROCESSING ACCOUNT"
   - Verify DR summation from query
   - Verify values being saved

6. **Check Database:**
   - Verify actual stored values
   - Compare with log output

7. **Identify Root Cause:**
   - Based on logs and database state
   - Determine where the +5 is coming from

---

## ⚠️ IMPORTANT NOTES

### The Code Logic is Correct

Based on code review:
- ✅ Repository queries only sum transaction amounts
- ✅ No modification between query and save
- ✅ `opening_bal` is never added to `drSummation`
- ✅ Formula is correct: `closing = opening + cr - dr`

### Possible Issues:

1. **Database has extra/duplicate DR transactions**
2. **Value date interest not properly filtered**
3. **Display/frontend showing wrong value**
4. **Multiple EOD runs without cleanup**
5. **Database trigger modifying values**

The debug logging will reveal which of these is the actual cause.

---

**Status:** 🔍 DEBUGGING ADDED | READY FOR INVESTIGATION  
**Next Action:** Run EOD with debug logging and analyze output
