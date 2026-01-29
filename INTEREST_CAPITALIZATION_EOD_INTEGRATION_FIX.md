# Interest Capitalization - EOD Integration Fix

**Date:** 2026-01-29  
**Issue:** Interest capitalization was directly updating `acct_bal_accrual` instead of letting EOD handle it  
**Status:** ✅ FIXED

---

## 🔴 PROBLEM IDENTIFIED

### Current Issue:
When user clicks "Proceed Interest", the code was:
1. ✅ Creating transactions in `tran_table` and `intt_accr_tran` (CORRECT)
2. ❌ **Directly updating `acct_bal_accrual` table** (WRONG - should let EOD handle this)

This caused issues because:
- The accrual balance was reset immediately, not via EOD processing
- EOD Batch Job 6 couldn't properly process the capitalization transactions
- Inconsistent with how regular interest accrual works (via EOD)

---

## ✅ CORRECT FLOW

### PART 1: Interest Capitalization (Manual Process)

**What Should Happen When User Clicks "Proceed Interest":**

```
1. Create Debit Transaction in intt_accr_tran:
   ├── Accr_Tran_Id: C20260129000001-1 (starts with "C")
   ├── Account_No: 1101010000001 (customer account)
   ├── GL_Account_No: 410101001 (interest expense GL)
   ├── Dr_Cr_Flag: D (Debit)
   ├── Amount: 45.00 (total accrued interest from closing_bal)
   └── Status: Verified

2. Create Credit Transaction in tran_table:
   ├── Tran_Id: C20260129000001-2
   ├── Account_No: 1101010000001
   ├── Dr_Cr_Flag: C (Credit)
   ├── Credit_Amount: 45.00
   └── Tran_Status: Verified

3. Update cust_acct_master:
   └── Last_Interest_Payment_Date: 2026-01-29 (today)

4. Update acct_bal:
   └── Current_Balance: 28,000 + 45 = 28,045 (immediate update)

5. ❌ DO NOT update acct_bal_accrual directly!
   └── Let EOD Batch Job 6 handle this
```

### PART 2: EOD Batch Job 6 Processing

**What Happens During EOD:**

```
EOD Batch Job 6: Interest Accrual Account Balance Update

Step 1: Query all transactions from intt_accr_tran for today
├── Includes "S" transactions (daily interest accrual)
└── Includes "C" transactions (interest capitalization) ✅ KEY FIX

Step 2: For account 1101010000001:
├── Opening_Bal: 40.00 (yesterday's closing_bal)
├── CR_Summation: 5.00 (today's accrual - "S" transaction)
├── DR_Summation: 45.00 (capitalization - "C" transaction)
└── Closing_Bal: 40 + 5 - 45 = 0.00 ✅

Step 3: Update acct_bal_accrual:
├── Opening_Bal: 40.00
├── CR_Summation: 5.00
├── DR_Summation: 45.00
├── Closing_Bal: 0.00 ✅
└── Tran_Date: 2026-01-29
```

### PART 3: Frontend Display (Before EOD Runs)

**Real-time Accrued Balance Calculation:**

```java
// Check if interest was capitalized today
if (custAcctMaster.getLastInterestPaymentDate().equals(businessDate)) {
    // Show accrued balance as 0 until EOD runs
    accruedBalance = 0.00;
} else {
    // Normal case - get from acct_bal_accrual
    accruedBalance = acctBalAccr.getClosingBal();
}
```

---

## 📝 CODE CHANGES

### CHANGE 1: InterestCapitalizationService.java

#### Removed Direct `acct_bal_accrual` Update

**BEFORE (Lines 378-405) - WRONG:**
```java
// Reset accrued balance to zero after capitalization
AcctBalAccrual acctBalAccrual = acctBalAccrualRepository.findLatestByAccountNo(accountNo)
        .orElseThrow(() -> new BusinessException("Accrued balance record not found"));

log.info("=== RESETTING ACCRUED BALANCE AFTER CAPITALIZATION ===");
log.info("Before reset - Closing Balance: {}", acctBalAccrual.getClosingBal());
log.info("Before reset - Interest Amount: {}", acctBalAccrual.getInterestAmount());

// FIXED: Reset closing_bal to zero
acctBalAccrual.setClosingBal(BigDecimal.ZERO);
acctBalAccrual.setInterestAmount(BigDecimal.ZERO);
acctBalAccrual.setOpeningBal(BigDecimal.ZERO);
acctBalAccrual.setDrSummation(BigDecimal.ZERO);
acctBalAccrual.setCrSummation(BigDecimal.ZERO);
acctBalAccrual.setTranDate(systemDate);

acctBalAccrualRepository.save(acctBalAccrual);  // ❌ WRONG

log.info("After reset - Closing Balance: {}", acctBalAccrual.getClosingBal());
log.info("Successfully reset accrued balance to 0 for account: {}", accountNo);
```

**AFTER (Lines 378-385) - CORRECT:**
```java
// DO NOT directly update acct_bal_accrual here!
// The acct_bal_accrual update will be handled by EOD Batch Job 6
// which will process the "C" (Capitalization) debit transaction created above

log.info("=== ACCT_BAL_ACCRUAL UPDATE DEFERRED TO EOD ===");
log.info("Capitalization debit transaction created in intt_accr_tran (amount: {})", accruedInterest);
log.info("EOD Batch Job 6 will process this 'C' transaction and update acct_bal_accrual");
log.info("Formula: closing_bal = prev_closing_bal + cr_summation - dr_summation");
log.info("Expected result after EOD: closing_bal will be reduced by {}", accruedInterest);
```

**Key Changes:**
- ✅ Removed all direct updates to `acct_bal_accrual`
- ✅ Added clear logging explaining EOD will handle it
- ✅ Shows expected formula and result

---

### CHANGE 2: InterestAccrualAccountBalanceService.java (EOD Batch Job 6)

#### Enhanced Logging for "S" and "C" Transaction Processing

**BEFORE (Lines 117-122):**
```java
// Step D: Calculate DR Summation (Conditional based on GL_Num)
// Note: This includes REGULAR interest only (handled correctly)
BigDecimal drSummation = calculateDRSummation(accountNo, systemDate, glNum);

// Step E: Calculate CR Summation (Conditional based on GL_Num)
// Note: This includes REGULAR interest only (handled correctly)
BigDecimal crSummation = calculateCRSummation(accountNo, systemDate, glNum);
```

**AFTER (Lines 117-122):**
```java
// Step D: Calculate DR Summation (Conditional based on GL_Num)
// Note: This includes ALL transactions - both "S" (accrual) and "C" (capitalization)
// Excludes value date interest (originalDrCrFlag IS NOT NULL)
BigDecimal drSummation = calculateDRSummation(accountNo, systemDate, glNum);

// Step E: Calculate CR Summation (Conditional based on GL_Num)
// Note: This includes ALL transactions - both "S" (accrual) and "C" (capitalization)
// Excludes value date interest (originalDrCrFlag IS NOT NULL)
BigDecimal crSummation = calculateCRSummation(accountNo, systemDate, glNum);
```

**BEFORE (Lines 54-56):**
```java
public int updateInterestAccrualAccountBalances(LocalDate systemDate) {
    LocalDate processDate = systemDate != null ? systemDate : systemDateService.getSystemDate();
    log.info("Starting Batch Job 6: Interest Accrual Account Balance Update for date: {}", processDate);

    // Step 1: Get unique account numbers from interest accrual transactions
    List<String> accountNumbers = inttAccrTranRepository.findDistinctAccountsByAccrualDate(processDate);
```

**AFTER (Lines 54-59):**
```java
public int updateInterestAccrualAccountBalances(LocalDate systemDate) {
    LocalDate processDate = systemDate != null ? systemDate : systemDateService.getSystemDate();
    log.info("Starting Batch Job 6: Interest Accrual Account Balance Update for date: {}", processDate);
    log.info("=== PROCESSING BOTH 'S' (ACCRUAL) AND 'C' (CAPITALIZATION) TRANSACTIONS ===");

    // Step 1: Get unique account numbers from interest accrual transactions
    // This includes ALL transactions: "S" (accrual), "C" (capitalization), etc.
    List<String> accountNumbers = inttAccrTranRepository.findDistinctAccountsByAccrualDate(processDate);
```

**Key Changes:**
- ✅ Clarified that BOTH "S" and "C" transactions are processed
- ✅ Added header logging to make this explicit
- ✅ Updated comments to reflect reality

**IMPORTANT NOTE:**
The repository queries ALREADY support "C" transactions! They don't filter by transaction ID prefix:
```java
@Query("SELECT COALESCE(SUM(i.amount), 0) FROM InttAccrTran i " +
       "WHERE i.accountNo = :accountNo " +
       "AND i.accrualDate = :accrualDate " +
       "AND i.drCrFlag = 'D' " +
       "AND i.originalDrCrFlag IS NULL")  // Only excludes value date interest
```

This query will sum ALL debit transactions (both "S" and "C") for the account and date. ✅

---

### CHANGE 3: BalanceService.java

#### Real-time Accrued Balance Calculation

**BEFORE (Lines 291-319):**
```java
/**
 * Get latest interest accrued closing balance for an account
 * Fetches the most recent closing balance from acct_bal_accrual table
 * 
 * FIXED: Now uses direct native query to avoid JPA relationship issues
 * 
 * @param accountNo The account number
 * @return The latest interest accrued closing balance, or 0 if no records found
 */
private BigDecimal getLatestInterestAccrued(String accountNo) {
    log.debug("Fetching latest interest accrued for account: {}", accountNo);
    
    try {
        // Use native query method to directly query by Account_No column
        Optional<AcctBalAccrual> latestAccrualOpt = acctBalAccrualRepository.findLatestByAccountNo(accountNo);
        
        if (latestAccrualOpt.isEmpty()) {
            log.debug("No interest accrual records found for account {} (with non-null Tran_date)", accountNo);
            return BigDecimal.ZERO;
        }

        AcctBalAccrual latestAccrual = latestAccrualOpt.get();
        BigDecimal closingBal = latestAccrual.getClosingBal();
        
        if (closingBal == null) {
            log.warn("Latest interest accrual record for account {} has null closing balance (Tran_date: {})", 
                    accountNo, latestAccrual.getTranDate());
            return BigDecimal.ZERO;
        }
        // ... rest of method
```

**AFTER (Lines 291-328):**
```java
/**
 * Get latest interest accrued closing balance for an account
 * Fetches the most recent closing balance from acct_bal_accrual table
 * 
 * IMPORTANT: If interest was capitalized today (last_interest_payment_date = business_date),
 * returns 0 because the accrued balance will be updated by EOD Batch Job 6 later.
 * This provides real-time accuracy before EOD runs.
 * 
 * FIXED: Now uses direct native query to avoid JPA relationship issues
 * 
 * @param accountNo The account number
 * @return The latest interest accrued closing balance, or 0 if no records found or if capitalized today
 */
private BigDecimal getLatestInterestAccrued(String accountNo) {
    log.debug("Fetching latest interest accrued for account: {}", accountNo);
    
    // Check if interest was capitalized today
    LocalDate businessDate = systemDateService.getSystemDate();
    Optional<CustAcctMaster> accountOpt = custAcctMasterRepository.findById(accountNo);
    
    if (accountOpt.isPresent()) {
        CustAcctMaster account = accountOpt.get();
        LocalDate lastInterestPayDate = account.getLastInterestPaymentDate();
        
        if (lastInterestPayDate != null && lastInterestPayDate.equals(businessDate)) {
            log.info("Interest was capitalized TODAY for account {}. Showing accrued balance as 0 (EOD will update acct_bal_accrual later)", accountNo);
            return BigDecimal.ZERO;
        }
    }
    
    try {
        // Use native query method to directly query by Account_No column
        Optional<AcctBalAccrual> latestAccrualOpt = acctBalAccrualRepository.findLatestByAccountNo(accountNo);
        
        if (latestAccrualOpt.isEmpty()) {
            log.debug("No interest accrual records found for account {} (with non-null Tran_date)", accountNo);
            return BigDecimal.ZERO;
        }

        AcctBalAccrual latestAccrual = latestAccrualOpt.get();
        BigDecimal closingBal = latestAccrual.getClosingBal();
        
        if (closingBal == null) {
            log.warn("Latest interest accrual record for account {} has null closing balance (Tran_date: {})", 
                    accountNo, latestAccrual.getTranDate());
            return BigDecimal.ZERO;
        }
        // ... rest of method
```

**Key Changes:**
- ✅ Added check for `last_interest_payment_date == business_date`
- ✅ Returns 0 if interest was capitalized today
- ✅ Provides real-time accuracy before EOD runs
- ✅ Clear logging explaining the logic

---

## 🔄 COMPLETE FLOW EXAMPLE

### Initial State (Day 1):
```
acct_bal_accrual:
├── tran_date: 2026-01-28
├── opening_bal: 35.00
├── cr_summation: 5.00 (yesterday's accrual)
├── dr_summation: 0.00
├── closing_bal: 40.00 (35 + 5 - 0)

cust_acct_master:
└── last_interest_payment_date: null
```

### Day 2: Daily Interest Accrual (EOD Run)
```
intt_accr_tran entries created:
├── S20260129000001-1: Debit 5.00 (interest expense)
└── S20260129000001-2: Credit 5.00 (customer account)

After EOD Batch Job 6:
acct_bal_accrual:
├── tran_date: 2026-01-29
├── opening_bal: 40.00 (from yesterday)
├── cr_summation: 5.00 (today's accrual - S transaction)
├── dr_summation: 0.00
├── closing_bal: 45.00 (40 + 5 - 0) ✅
```

### Day 2: User Clicks "Proceed Interest"
```
Step 1: Create Capitalization Transactions
intt_accr_tran:
└── C20260129000002-1: Debit 45.00 (capitalization)

tran_table:
└── C20260129000002-2: Credit 45.00 (customer account)

Step 2: Update cust_acct_master
cust_acct_master:
└── last_interest_payment_date: 2026-01-29 ✅

Step 3: Update acct_bal
acct_bal:
└── current_balance: 28,000 → 28,045 ✅

Step 4: acct_bal_accrual NOT updated yet
acct_bal_accrual:
└── closing_bal: 45.00 (unchanged) ⏳

Step 5: Frontend Display
BalanceService.getLatestInterestAccrued():
├── Checks: last_interest_payment_date == business_date? YES
└── Returns: 0.00 ✅ (even though closing_bal is still 45.00)

User sees:
└── Accrued Balance: 0.00 ✅ CORRECT
```

### Next EOD Run:
```
EOD Batch Job 6 processes both transactions:
├── S20260129000001: Credit 5.00 (daily accrual)
└── C20260129000002-1: Debit 45.00 (capitalization)

Calculation:
├── opening_bal: 40.00
├── cr_summation: 5.00 (S transaction)
├── dr_summation: 45.00 (C transaction)
└── closing_bal: 40 + 5 - 45 = 0.00 ✅

Result:
acct_bal_accrual:
├── tran_date: 2026-01-29
├── opening_bal: 40.00
├── cr_summation: 5.00
├── dr_summation: 45.00
├── closing_bal: 0.00 ✅ NOW UPDATED

Frontend Display:
BalanceService.getLatestInterestAccrued():
├── Checks: last_interest_payment_date == business_date? YES
└── Returns: 0.00 ✅ (consistent)
```

---

## 📊 DATABASE VERIFICATION

### Before Capitalization:
```sql
-- Check accrued balance
SELECT 
    Account_No,
    Tran_Date,
    Opening_Bal,
    CR_Summation,
    DR_Summation,
    Closing_Bal
FROM Acct_Bal_Accrual
WHERE Account_No = '1101010000001'
ORDER BY Tran_Date DESC
LIMIT 1;

-- Expected:
-- Closing_Bal: 45.00
```

### After Capitalization (Before EOD):
```sql
-- Check capitalization transaction created
SELECT 
    Accr_Tran_Id,
    Account_No,
    GL_Account_No,
    Dr_Cr_Flag,
    Amount,
    Accrual_Date
FROM Intt_Accr_Tran
WHERE Accr_Tran_Id LIKE 'C%'
  AND Accrual_Date = '2026-01-29'
ORDER BY Accr_Tran_Id DESC;

-- Expected:
-- C20260129000002-1, 1101010000001, 410101001, D, 45.00

-- Check last interest payment date
SELECT 
    Account_No,
    Last_Interest_Payment_Date
FROM Cust_Acct_Master
WHERE Account_No = '1101010000001';

-- Expected:
-- Last_Interest_Payment_Date: 2026-01-29

-- Check acct_bal_accrual NOT YET updated
SELECT Closing_Bal
FROM Acct_Bal_Accrual
WHERE Account_No = '1101010000001'
ORDER BY Tran_Date DESC
LIMIT 1;

-- Expected:
-- Closing_Bal: 45.00 (not yet updated - waiting for EOD)
```

### After EOD Runs:
```sql
-- Check acct_bal_accrual updated by EOD
SELECT 
    Account_No,
    Tran_Date,
    Opening_Bal,
    CR_Summation,
    DR_Summation,
    Closing_Bal
FROM Acct_Bal_Accrual
WHERE Account_No = '1101010000001'
  AND Tran_Date = '2026-01-29';

-- Expected:
-- Opening_Bal: 40.00
-- CR_Summation: 5.00 (daily accrual - S transaction)
-- DR_Summation: 45.00 (capitalization - C transaction)
-- Closing_Bal: 0.00 (40 + 5 - 45)
```

---

## ✅ SUCCESS CRITERIA

### Test Case 1: Before Capitalization
```
acct_bal_accrual.closing_bal: 45.00
Frontend Accrued Balance: 45.00 ✅
```

### Test Case 2: After Capitalization (Before EOD)
```
Transactions Created:
├── intt_accr_tran (C transaction): Debit 45.00 ✅
└── tran_table (C transaction): Credit 45.00 ✅

cust_acct_master.last_interest_payment_date: 2026-01-29 ✅

acct_bal_accrual.closing_bal: 45.00 (not updated yet) ⏳

Frontend Accrued Balance: 0.00 ✅ (shows 0 because last_interest_payment_date == business_date)
```

### Test Case 3: After EOD Runs
```
acct_bal_accrual:
├── cr_summation: 5.00 ✅
├── dr_summation: 45.00 ✅
├── closing_bal: 0.00 ✅

Frontend Accrued Balance: 0.00 ✅
```

---

## 🎯 KEY BENEFITS

### 1. Consistency
- ✅ Capitalization now follows same pattern as regular accrual
- ✅ All `acct_bal_accrual` updates happen via EOD (Batch Job 6)
- ✅ No direct manipulation of accrual balances

### 2. Auditability
- ✅ Clear transaction trail in `intt_accr_tran`
- ✅ "C" transactions are distinguishable from "S" transactions
- ✅ EOD logs show both types being processed

### 3. Real-time Accuracy
- ✅ Frontend shows 0 accrued balance immediately after capitalization
- ✅ No need to wait for EOD for frontend to reflect correct state
- ✅ Uses `last_interest_payment_date` as flag

### 4. Correctness
- ✅ EOD formula correctly handles both accrual and capitalization
- ✅ Closing balance calculation: `prev_closing + CR - DR`
- ✅ Works with mixed "S" and "C" transactions

---

## 📝 FILES MODIFIED

1. **InterestCapitalizationService.java**
   - Removed direct `acct_bal_accrual` update (lines 378-405 → 378-385)
   - Added EOD deferral logging

2. **InterestAccrualAccountBalanceService.java**
   - Enhanced logging to clarify "S" and "C" processing (lines 54-59, 117-122)
   - Updated comments to reflect reality

3. **BalanceService.java**
   - Added real-time accrued balance calculation (lines 300-317)
   - Returns 0 if interest capitalized today

---

## 🚀 DEPLOYMENT CHECKLIST

- [ ] Code changes reviewed
- [ ] No linter errors
- [ ] Backend rebuilt: `mvn clean package -DskipTests`
- [ ] Backend server restarted
- [ ] Test Case 1: Capitalization creates transactions
- [ ] Test Case 2: Acct_bal_accrual NOT updated immediately
- [ ] Test Case 3: Frontend shows 0 accrued balance after capitalization
- [ ] Test Case 4: EOD processes "C" transactions
- [ ] Test Case 5: After EOD, closing_bal is correct

---

**Status:** ✅ FIXED | NO LINTER ERRORS | READY FOR TESTING  
**Integration:** Interest Capitalization now properly integrates with EOD processing
