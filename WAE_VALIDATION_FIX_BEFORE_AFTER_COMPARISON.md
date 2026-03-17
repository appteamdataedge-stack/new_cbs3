# WAE Calculation Fix - Before vs After Comparison

## Side-by-Side Comparison

### Data Source Comparison

| Component | ❌ OLD (Wrong) | ✅ NEW (Correct) |
|-----------|----------------|------------------|
| **Today's FCY Credits** | `acc_bal.CR_Summation` | `SUM(tran_table.FCY_Amt WHERE Dr_Cr_Flag='C')` |
| **Today's FCY Debits** | `acc_bal.DR_Summation` | `SUM(tran_table.FCY_Amt WHERE Dr_Cr_Flag='D')` |
| **Today's LCY Credits** | `tran_table sum` | `SUM(tran_table.Credit_Amount WHERE Dr_Cr_Flag='C')` |
| **Today's LCY Debits** | `tran_table sum` | `SUM(tran_table.Debit_Amount WHERE Dr_Cr_Flag='D')` |
| **Previous Day FCY** | *(Not fetched)* | `acc_bal.Closing_Bal` for yesterday |
| **Previous Day LCY** | `acct_bal_lcy.Closing_Bal_lcy` | `acct_bal_lcy.Closing_Bal_lcy` for yesterday |

### Key Insight

**❌ OLD APPROACH:** Tried to get today's FCY data from `acc_bal` table
- **Problem:** `acc_bal` is NOT updated after each transaction
- **Problem:** `acc_bal` only updates during EOD batch (once per day)
- **Result:** `acc_bal.CR_Summation` and `acc_bal.DR_Summation` were ZERO during the day

**✅ NEW APPROACH:** Gets today's data from `tran_table`
- **Correct:** `tran_table` is updated immediately when transaction is posted
- **Correct:** `tran_table` is the live transaction ledger
- **Result:** Today's movements are captured in real-time

---

## Code Comparison

### ❌ OLD IMPLEMENTATION (WRONG)

```java
private BigDecimal calculateLiveWAEFromAccBal(String accountNo, LocalDate tranDate) {
    // WRONG: Trying to get today's data from acc_bal
    Optional<AcctBal> accBalOpt = acctBalRepository
        .findByAccountNoAndTranDate(accountNo, tranDate);  // Looking for TODAY's record
    
    if (accBalOpt.isEmpty()) {
        accBalOpt = acctBalRepository.findLatestByAccountNo(accountNo);
    }
    
    if (accBalOpt.isEmpty()) {
        return null;  // No acc_bal record
    }

    AcctBal accBal = accBalOpt.get();
    
    // WRONG: These fields are EMPTY during the day!
    BigDecimal fcyOpb = accBal.getOpeningBal();      // e.g., 0
    BigDecimal fcyCr = accBal.getCrSummation();      // WRONG: 0 (not updated yet!)
    BigDecimal fcyDr = accBal.getDrSummation();      // WRONG: 0 (not updated yet!)
    BigDecimal totalFcy = fcyOpb.add(fcyCr).subtract(fcyDr);  // = 0 + 0 - 0 = 0 ❌
    
    // ... rest of calculation
}
```

**Why This Failed:**
```
Transaction posted at 10:00 AM → tran_table
                                      ↓
                           acc_bal NOT updated yet!
                                      ↓
                         acc_bal.CR_Summation = 0 ❌
                         acc_bal.DR_Summation = 0 ❌
                                      ↓
                         WAE calculation = 0 / 0 = ERROR
```

---

### ✅ NEW IMPLEMENTATION (CORRECT)

```java
private BigDecimal calculateLiveWAEFromAccBal(String accountNo, LocalDate tranDate) {
    // CORRECT: Get PREVIOUS day's closing from balance tables (snapshot)
    LocalDate previousDay = tranDate.minusDays(1);
    
    BigDecimal previousDayFcyClosing = BigDecimal.ZERO;
    BigDecimal previousDayLcyClosing = BigDecimal.ZERO;
    
    // Get yesterday's snapshot from balance tables
    Optional<AcctBalLcy> previousDayLcyOpt = acctBalLcyRepository
        .findByAccountNoAndTranDate(accountNo, previousDay);  // YESTERDAY
    
    if (previousDayLcyOpt.isPresent()) {
        previousDayLcyClosing = previousDayLcyOpt.get().getClosingBalLcy();
        
        Optional<AcctBal> previousDayFcyOpt = acctBalRepository
            .findByAccountNoAndTranDate(accountNo, previousDay);  // YESTERDAY
        
        if (previousDayFcyOpt.isPresent()) {
            previousDayFcyClosing = previousDayFcyOpt.get().getClosingBal();
        }
    }
    
    // CORRECT: Get TODAY's movements from tran_table (live ledger)
    List<TranTable> todayTransactions = tranTableRepository
        .findByAccountNoAndTranDate(accountNo, tranDate);  // TODAY
    
    // Sum FCY amounts from today's transactions
    BigDecimal todayFcyCredits = todayTransactions.stream()
        .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
        .map(TranTable::getFcyAmt)
        .reduce(BigDecimal.ZERO, BigDecimal::add);  // CORRECT: Has today's data ✓
    
    BigDecimal todayFcyDebits = todayTransactions.stream()
        .filter(t -> t.getDrCrFlag() == DrCrFlag.D)
        .map(TranTable::getFcyAmt)
        .reduce(BigDecimal.ZERO, BigDecimal::add);  // CORRECT: Has today's data ✓
    
    // Sum LCY amounts from today's transactions
    BigDecimal todayLcyCredits = todayTransactions.stream()
        .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
        .map(t -> t.getCreditAmount() != null ? t.getCreditAmount() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);  // CORRECT: Has today's data ✓
    
    BigDecimal todayLcyDebits = todayTransactions.stream()
        .filter(t -> t.getDrCrFlag() == DrCrFlag.D)
        .map(t -> t.getDebitAmount() != null ? t.getDebitAmount() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);  // CORRECT: Has today's data ✓
    
    // Calculate current balances
    BigDecimal totalFcy = previousDayFcyClosing  // Yesterday's snapshot
        .add(todayFcyCredits)                    // + Today's credits from tran_table
        .subtract(todayFcyDebits);               // - Today's debits from tran_table
    
    BigDecimal totalLcy = previousDayLcyClosing  // Yesterday's snapshot
        .add(todayLcyCredits)                    // + Today's credits from tran_table
        .subtract(todayLcyDebits);               // - Today's debits from tran_table
    
    // Calculate WAE
    if (totalFcy.compareTo(BigDecimal.ZERO) == 0) {
        return null;
    }
    
    return totalLcy.abs().divide(totalFcy.abs(), 4, RoundingMode.HALF_UP);
}
```

**Why This Works:**
```
Transaction posted at 10:00 AM → tran_table ✓
                                      ↓
                         Query tran_table for TODAY ✓
                                      ↓
                         Get FCY_Amt = 15,000 ✓
                         Get Credit_Amount = 1,719,000 ✓
                                      ↓
                         WAE = 1,719,000 / 15,000 = 114.6000 ✓
```

---

## Example Execution Trace

### Scenario
- Account: 100000008001 (USD)
- BOD Balance: 0.00 USD
- Transaction: Credit 15,000 USD at 114.6000
- Time: 10:00 AM (same day)

### ❌ OLD CODE EXECUTION (Failed)

```
Step 1: Look for acc_bal record for TODAY (2026-03-16)
  Query: SELECT * FROM Acct_Bal WHERE Account_No='100000008001' AND Tran_Date='2026-03-16'
  Result: No record (acc_bal only updates during EOD) ❌

Step 2: Fallback to latest acc_bal
  Query: SELECT * FROM Acct_Bal WHERE Account_No='100000008001' ORDER BY Tran_Date DESC LIMIT 1
  Result: Found record for YESTERDAY (2026-03-15)
    Opening_Bal = 0
    CR_Summation = 0  ← WRONG: Should have today's 15,000 but doesn't!
    DR_Summation = 0
    Closing_Bal = 0

Step 3: Calculate FCY
  totalFcy = 0 + 0 - 0 = 0 ❌

Step 4: Check if zero
  if (totalFcy == 0) return null ❌

Result: WAE = null → Transaction REJECTED ❌
Error: "WAE not available for settlement account"
```

---

### ✅ NEW CODE EXECUTION (Success)

```
Step 1: Get PREVIOUS day's closing from balance tables
  Query acc_bal for YESTERDAY (2026-03-15):
    Result: Closing_Bal = 0 (no previous balance) ✓
  
  Query acct_bal_lcy for YESTERDAY (2026-03-15):
    Result: Closing_Bal_lcy = 0 (no previous balance) ✓

Step 2: Get TODAY's transactions from tran_table
  Query: SELECT * FROM Tran_Table WHERE Account_No='100000008001' AND Tran_Date='2026-03-16'
  Result: Found 1 transaction ✓
    Tran_Id: T20260316001234-1
    Dr_Cr_Flag: C (Credit)
    FCY_Amt: 15,000.00 ✓
    Credit_Amount: 1,719,000.00 ✓

Step 3: Sum transactions
  todayFcyCredits = 15,000.00 ✓
  todayFcyDebits = 0.00 ✓
  todayLcyCredits = 1,719,000.00 ✓
  todayLcyDebits = 0.00 ✓

Step 4: Calculate totals
  totalFcy = 0 + 15,000 - 0 = 15,000.00 ✓
  totalLcy = 0 + 1,719,000 - 0 = 1,719,000.00 ✓

Step 5: Calculate WAE
  WAE = 1,719,000.00 / 15,000.00 = 114.6000 ✓

Result: WAE = 114.6000 → Transaction SUCCEEDS ✅
```

---

## Table Update Timeline

### During Business Hours (10:00 AM - 5:00 PM)

| Time | Action | tran_table | acc_bal | acct_bal_lcy |
|------|--------|------------|---------|--------------|
| 10:00 AM | Credit 15K USD | ✅ **Updated** (new row) | ❌ Still yesterday's data | ❌ Still yesterday's data |
| 10:30 AM | Check balance (UI) | ✅ **Reads from here** | Not used | Not used |
| 11:00 AM | Debit 15K USD | ✅ **Updated** (new row) | ❌ Still yesterday's data | ❌ Still yesterday's data |
| 11:30 AM | Another credit | ✅ **Updated** (new row) | ❌ Still yesterday's data | ❌ Still yesterday's data |

**Old Code:** Tried to read from `acc_bal` during the day → Got empty/zero values ❌
**New Code:** Reads from `tran_table` during the day → Gets real-time data ✅

### After EOD Batch (Midnight)

| Time | Action | tran_table | acc_bal | acct_bal_lcy |
|------|--------|------------|---------|--------------|
| 12:00 AM | EOD Batch runs | ✅ Has all day's transactions | ⚙️ **Updating...** | ⚙️ **Updating...** |
| 12:30 AM | EOD Complete | ✅ Unchanged | ✅ **New record created** | ✅ **New record created** |

**Now balance tables have TODAY's data!**

```sql
-- acc_bal after EOD
INSERT INTO Acct_Bal VALUES (
  '2026-03-16',        -- Tran_Date
  '100000008001',      -- Account_No
  0,                   -- Opening_Bal
  15000,               -- CR_Summation ← Aggregated from tran_table
  15000,               -- DR_Summation ← Aggregated from tran_table
  0                    -- Closing_Bal
);

-- acct_bal_lcy after EOD
INSERT INTO Acct_Bal_LCY VALUES (
  '2026-03-16',        -- Tran_Date
  '100000008001',      -- Account_No
  0,                   -- Opening_Bal_lcy
  1719000,             -- CR_Summation_lcy ← Aggregated from tran_table
  1719000,             -- DR_Summation_lcy ← Aggregated from tran_table
  0                    -- Closing_Bal_lcy
);
```

---

## Why The Confusion Happened

### The Misleading Names

**Problem:** The old code had a misleading method name:
```java
calculateLiveWAEFromAccBal()  // Name says "from acc_bal"
```

**Reality:** It tried to get live data from `acc_bal`, but `acc_bal` doesn't have live data!

**Solution:** Keep the method name (for backward compatibility) but fix the implementation to use `tran_table` (which does have live data).

### The Architecture Misunderstanding

```
Developer thought:
  "acc_bal has balances → I'll get today's balance from acc_bal"

Reality:
  acc_bal = SNAPSHOT (updates once per day during EOD)
  tran_table = LEDGER (updates immediately when transaction posted)
  
  For LIVE balance → must query tran_table
  For EOD balance → can query acc_bal (next day)
```

---

## Verification: Which Table Has Today's Data?

### Test Query (Run During Business Hours)

```sql
-- Check tran_table (should have today's transactions)
SELECT COUNT(*) AS transactions_today,
       SUM(CASE WHEN Dr_Cr_Flag='C' THEN FCY_Amt ELSE 0 END) AS credits_fcy,
       SUM(CASE WHEN Dr_Cr_Flag='D' THEN FCY_Amt ELSE 0 END) AS debits_fcy
FROM Tran_Table
WHERE Account_No = '100000008001'
AND Tran_Date = '2026-03-16';  -- TODAY

-- Expected: transactions_today > 0, credits/debits show real values ✓

-- Check acc_bal (should NOT have today's data yet)
SELECT CR_Summation, DR_Summation
FROM Acct_Bal
WHERE Account_No = '100000008001'
AND Tran_Date = '2026-03-16';  -- TODAY

-- Expected: No rows OR CR_Summation=0, DR_Summation=0 ✓
```

**This proves:**
- ✅ `tran_table` has today's data (correct source for live calculations)
- ❌ `acc_bal` does NOT have today's data (wrong source for live calculations)

---

## Summary

### What Was Wrong
```
OLD: Get today's data from acc_bal
     ↓
     acc_bal.CR_Summation = 0 (not updated yet!)
     ↓
     WAE calculation failed ❌
```

### What Is Correct
```
NEW: Get yesterday's closing from acc_bal (snapshot)
     Get today's movements from tran_table (live ledger)
     ↓
     tran_table has real-time data ✓
     ↓
     WAE calculation succeeds ✅
```

### The Key Principle
**For ANY intraday calculation:**
- Previous data → from balance tables (yesterday's snapshot) ✓
- Today's data → from transaction table (live ledger) ✓

**Never try to get "today's data" from balance tables during the day!**

---

**Date:** 2026-03-16
**Status:** ✅ Fixed and Compiled Successfully
**Next:** Functional testing
