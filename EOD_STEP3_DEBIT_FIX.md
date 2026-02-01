# EOD Step 3 Fix: Debit Transactions Not Moving to gl_movement_accrl

## Problem Statement

During EOD Step 3 (Interest Accrual GL Movement Update), **debit transactions (DR flag)** from `innt_accr_tran` were NOT being posted to `gl_movement_accrl` table. Only credit transactions were being posted.

### Current Behavior (WRONG) ❌

- **Credit transactions** (S-type, daily accrual) from `innt_accr_tran` → ✅ Moving to `gl_movement_accrl`
- **Debit transactions** (C-type, capitalization) from `innt_accr_tran` → ❌ NOT moving to `gl_movement_accrl`

### Expected Behavior (CORRECT) ✅

- **ALL transactions** from `innt_accr_tran` (both CR and DR) → Should move to `gl_movement_accrl`

---

## Root Cause Analysis

### Investigation Steps

1. ✅ **Checked EOD Step 3 Query** - `InterestAccrualGLMovementService.java` line 58-59:
   ```java
   List<InttAccrTran> accrualTransactions = inttAccrTranRepository
           .findByAccrualDateAndStatus(processDate, InttAccrTran.AccrualStatus.Pending);
   ```
   - This query correctly fetches ALL transactions with status `Pending`
   - **No DR/CR filter** in the query ✅

2. ✅ **Checked Repository** - `InttAccrTranRepository.java` line 24:
   ```java
   List<InttAccrTran> findByAccrualDateAndStatus(LocalDate accrualDate, AccrualStatus status);
   ```
   - Repository method is correct ✅
   - Returns ALL records matching the status, regardless of DR/CR flag ✅

3. ❌ **Found the Issue** - Different status values for different transaction types:

   **Daily Accrual (S-type) - InterestAccrualService.java line 298:**
   ```java
   InttAccrTran accrualEntry = InttAccrTran.builder()
       ...
       .drCrFlag(drCrFlag)  // Usually 'C' (Credit)
       .status(AccrualStatus.Pending)  // ✅ Set to Pending for EOD Step 3
       ...
       .build();
   ```

   **Capitalization (C-type) - InterestCapitalizationService.java line 262 (BEFORE FIX):**
   ```java
   InttAccrTran debitEntry = InttAccrTran.builder()
       ...
       .drCrFlag(TranTable.DrCrFlag.D)  // Debit
       .status(InttAccrTran.AccrualStatus.Verified)  // ❌ Set to Verified, NOT Pending!
       ...
       .build();
   ```

### The Problem

| Transaction Type | DR/CR Flag | Status | Processed by EOD Step 3? |
|------------------|------------|--------|--------------------------|
| Daily Accrual (S-type) | C (Credit) | **Pending** | ✅ YES |
| Capitalization (C-type) | D (Debit) | **Verified** | ❌ NO |

**EOD Step 3 only processes records with status `Pending`**, so:
- ✅ S-type records (status=Pending) → Moved to `gl_movement_accrl`
- ❌ C-type records (status=Verified) → Skipped by EOD Step 3

---

## The Fix

### File Modified

**`InterestCapitalizationService.java`** - Line 262

### BEFORE (WRONG) ❌

```java
InttAccrTran debitEntry = InttAccrTran.builder()
        .accrTranId(transactionId + "-1")
        .accountNo(account.getAccountNo())
        .accrualDate(systemDate)
        .tranDate(systemDate)
        .valueDate(systemDate)
        .drCrFlag(TranTable.DrCrFlag.D)  // Debit
        .tranStatus(TranTable.TranStatus.Verified)
        .glAccountNo(interestExpenseGL)
        .tranCcy(account.getAccountCcy() != null ? account.getAccountCcy() : "BDT")
        .fcyAmt(amount)
        .exchangeRate(BigDecimal.ONE)
        .lcyAmt(amount)
        .amount(amount)
        .interestRate(subProduct.getEffectiveInterestRate() != null ? 
                     subProduct.getEffectiveInterestRate() : BigDecimal.ZERO)
        .status(InttAccrTran.AccrualStatus.Verified)  // ❌ WRONG: Verified instead of Pending
        .narration(narration != null ? narration : "Interest Capitalization - Expense")
        .udf1("Frontend_user")
        .build();
```

### AFTER (CORRECT) ✅

```java
InttAccrTran debitEntry = InttAccrTran.builder()
        .accrTranId(transactionId + "-1")
        .accountNo(account.getAccountNo())
        .accrualDate(systemDate)
        .tranDate(systemDate)
        .valueDate(systemDate)
        .drCrFlag(TranTable.DrCrFlag.D)  // Debit
        .tranStatus(TranTable.TranStatus.Verified)
        .glAccountNo(interestExpenseGL)
        .tranCcy(account.getAccountCcy() != null ? account.getAccountCcy() : "BDT")
        .fcyAmt(amount)
        .exchangeRate(BigDecimal.ONE)
        .lcyAmt(amount)
        .amount(amount)
        .interestRate(subProduct.getEffectiveInterestRate() != null ? 
                     subProduct.getEffectiveInterestRate() : BigDecimal.ZERO)
        .status(InttAccrTran.AccrualStatus.Pending)  // ✅ CORRECT: Set to Pending for EOD Step 3
        .narration(narration != null ? narration : "Interest Capitalization - Expense")
        .udf1("Frontend_user")
        .build();
```

### What Changed

**Single line change at line 262:**
```java
// BEFORE
.status(InttAccrTran.AccrualStatus.Verified)

// AFTER
.status(InttAccrTran.AccrualStatus.Pending)  // ✅ FIX: Set to Pending so EOD Step 3 processes it
```

---

## How It Works Now

### Complete Flow

```
Interest Capitalization
    ↓
Creates 2 records in intt_accr_tran:
    1. Debit Entry (C-type) with status=Pending ✅ (FIXED)
    2. Credit Entry goes to tran_table
    ↓
EOD Step 3: Interest Accrual GL Movement Update
    ↓
Query: SELECT * FROM innt_accr_tran 
       WHERE Accrual_Date = System_Date 
       AND Status = 'Pending'
    ↓
Result: BOTH S-type (CR) and C-type (DR) records ✅
    ↓
Process ALL records → gl_movement_accrl
    ↓
Update innt_accr_tran status to 'Posted'
```

### Status Lifecycle

| Stage | S-type (Daily Accrual) | C-type (Capitalization Debit) |
|-------|------------------------|-------------------------------|
| **Creation** | Status = Pending | Status = Pending ✅ (FIXED) |
| **EOD Step 3 Query** | ✅ Fetched | ✅ Fetched (FIXED) |
| **After EOD Step 3** | Status = Posted | Status = Posted ✅ (FIXED) |

---

## Comparison with EOD Step 4

### EOD Step 4 (Works Correctly) ✅

**Source Table:** `tran_table`  
**Target Table:** `gl_movement`  
**Behavior:** ALL transactions (both CR and DR) move to `gl_movement`

```java
// GLMovementUpdateService.java
List<TranTable> transactions = tranTableRepository
    .findByTranDateAndTranStatus(processDate, TranTable.TranStatus.Verified);
// Returns ALL transactions (CR and DR) with status Verified ✅
```

### EOD Step 3 (Now Fixed) ✅

**Source Table:** `innt_accr_tran`  
**Target Table:** `gl_movement_accrl`  
**Behavior:** ALL transactions (both CR and DR) move to `gl_movement_accrl`

```java
// InterestAccrualGLMovementService.java
List<InttAccrTran> transactions = inttAccrTranRepository
    .findByAccrualDateAndStatus(processDate, InttAccrTran.AccrualStatus.Pending);
// Returns ALL transactions (CR and DR) with status Pending ✅
```

**Both steps now work consistently!** ✅

---

## Verification Steps

### 1. Run Interest Capitalization

```sql
-- Check innt_accr_tran after capitalization
SELECT 
    Accr_Tran_Id,
    Account_No,
    GL_Account_No,
    Dr_Cr_Flag,
    Status,
    Amount,
    Narration
FROM intt_accr_tran
WHERE Accrual_Date = '2026-02-01'
ORDER BY Accr_Tran_Id;
```

**Expected Result:**
```
| Accr_Tran_Id           | Dr_Cr_Flag | Status  | Amount | Narration                         |
|------------------------|------------|---------|--------|-----------------------------------|
| S20260201000000001-1   | C          | Pending | 100.00 | Interest Accrual (S-type)         |
| C20260201000000001-1   | D          | Pending | 100.00 | Interest Capitalization - Expense |
```

Both should have **Status = Pending** ✅

### 2. Run EOD Step 3

```
POST /api/eod/batch-job-3
```

**Expected Log Output:**
```log
INFO  Starting Batch Job 3: Interest Accrual GL Movement Update for date: 2026-02-01
INFO  Found 2 pending interest accrual transactions to process
INFO  Created GL movement accrual for Accr_Tran_Id S20260201000000001-1 -> GL 410101, Amount: 100.00
INFO  Created GL movement accrual for Accr_Tran_Id C20260201000000001-1 -> GL 410101, Amount: 100.00
INFO  Batch Job 3 completed. Records created: 2, Errors: 0
```

### 3. Check gl_movement_accrl

```sql
-- Check gl_movement_accrl after EOD Step 3
SELECT 
    Accr_Tran_Id,
    GL_Num,
    Dr_Cr_Flag,
    Amount,
    Accrual_Date,
    Status
FROM gl_movement_accrl
WHERE Accrual_Date = '2026-02-01'
ORDER BY Accr_Tran_Id;
```

**Expected Result:**
```
| Accr_Tran_Id           | GL_Num  | Dr_Cr_Flag | Amount | Status  |
|------------------------|---------|------------|--------|---------|
| S20260201000000001-1   | 410101  | C          | 100.00 | Pending |
| C20260201000000001-1   | 410101  | D          | 100.00 | Pending |
```

**Both records should be present!** ✅

### 4. Check intt_accr_tran Status Update

```sql
-- Check innt_accr_tran status after EOD Step 3
SELECT 
    Accr_Tran_Id,
    Dr_Cr_Flag,
    Status,
    Amount
FROM intt_accr_tran
WHERE Accrual_Date = '2026-02-01'
ORDER BY Accr_Tran_Id;
```

**Expected Result:**
```
| Accr_Tran_Id           | Dr_Cr_Flag | Status | Amount |
|------------------------|------------|--------|--------|
| S20260201000000001-1   | C          | Posted | 100.00 |
| C20260201000000001-1   | D          | Posted | 100.00 |
```

Status should be updated to **Posted** for both records ✅

---

## Test Scenario

### Complete End-to-End Test

1. **Capitalize Interest for an account:**
   ```
   Account: 100000001001
   Accrued Interest: 100.00
   System Date: 2026-02-01
   ```

2. **Check intt_accr_tran immediately after capitalization:**
   ```sql
   SELECT * FROM intt_accr_tran WHERE Accrual_Date = '2026-02-01';
   ```
   **Expected:** 2 records (1 S-type CR, 1 C-type DR), both with Status = Pending

3. **Run EOD Step 3:**
   ```
   POST /api/eod/batch-job-3
   ```
   **Expected:** Processes 2 records, creates 2 gl_movement_accrl entries

4. **Check gl_movement_accrl:**
   ```sql
   SELECT * FROM gl_movement_accrl WHERE Accrual_Date = '2026-02-01';
   ```
   **Expected:** 2 records (both CR and DR)

5. **Check intt_accr_tran status:**
   ```sql
   SELECT Accr_Tran_Id, Status FROM intt_accr_tran WHERE Accrual_Date = '2026-02-01';
   ```
   **Expected:** Both records now have Status = Posted

---

## Benefits of This Fix

1. ✅ **Complete GL Movement Tracking**
   - Both debit and credit entries from interest accruals now move to `gl_movement_accrl`

2. ✅ **Consistent Status Handling**
   - All accrual records (S-type and C-type) use the same status lifecycle:
     - Creation: Pending
     - After EOD Step 3: Posted

3. ✅ **Accurate GL Balances**
   - GL balances will now reflect both sides of interest capitalization
   - No missing debit entries in GL movement records

4. ✅ **Consistent with Step 4**
   - Step 3 (innt_accr_tran → gl_movement_accrl) now works like Step 4 (tran_table → gl_movement)
   - Both process ALL transactions regardless of DR/CR flag

---

## Files Modified

1. **InterestCapitalizationService.java**
   - Line 262: Changed status from `Verified` to `Pending`

---

## Summary

**Problem:** Debit transactions from `innt_accr_tran` not moving to `gl_movement_accrl`  
**Root Cause:** C-type debit records had status `Verified` instead of `Pending`  
**Solution:** Set status to `Pending` when creating C-type debit records  
**Result:** Both CR and DR transactions now move to `gl_movement_accrl` during EOD Step 3 ✅

---

**Implementation Date:** February 1, 2026  
**Status:** ✅ FIXED - Ready for Testing  
**Modified By:** Cursor AI Assistant
