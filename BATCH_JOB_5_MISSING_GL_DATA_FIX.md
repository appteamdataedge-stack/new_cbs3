# Batch Job 5: Missing GL Data Fix

## Date: 2025-04-05
## Issue: Some GL numbers not appearing in gl_balance table

---

## Problem Summary

When executing Batch Job 5 (GL Balance Update), some GL numbers with transactions were missing from the `gl_balance` table for date 2025-04-05.

**Missing GL Numbers Identified:**
- `130103001`
- `240103001`
- `220302001`
- `110203001`

---

## Root Cause Analysis

### Issue 1: Incomplete GL Number Selection

**Location:** `GLBalanceUpdateService.java:214` - `getAllGLNumbers()` method

**Problem:**
The method only retrieved GL numbers that were linked to customer/office accounts through sub-products using `glSetupRepository.findActiveGLNumbersWithAccounts()`.

**Impact:**
GL numbers with transactions in `gl_movement` or `gl_movement_accrual` tables but NOT linked to any accounts were excluded from Batch Job 5 processing.

**Example Scenario:**
- GL `130103001` has transactions in `gl_movement` for 2025-04-05
- But GL `130103001` is not linked to any sub-product with customer/office accounts
- Result: GL `130103001` was excluded from batch job 5
- Result: No entry in `gl_balance` table for this GL

---

### Issue 2: Wrong Column Used for Calculation

**Location 1:** `GLMovementRepository.java:55-56` - `findDrCrSummationNative()` query
**Location 2:** `GLMovementAccrualRepository.java:70-71` - `findDrCrSummationNative()` query

**Problem:**
Both queries used `Amount` column instead of `LCY_Amt` (Local Currency Amount) column.

```sql
-- WRONG (Before Fix)
COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN Amount ELSE 0 END), 0) AS totalDr,
COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN Amount ELSE 0 END), 0) AS totalCr

-- CORRECT (After Fix)
COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END), 0) AS totalDr,
COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END), 0) AS totalCr
```

**Impact:**
- For multi-currency transactions, using `Amount` would give incorrect GL balances
- GL balance calculations must always use local currency amount (`LCY_Amt`) for accuracy
- This is a CBS compliance requirement

---

## Fix Implementation

### Fix 1: Include ALL GL Numbers with Transactions

**File:** `moneymarket/src/main/java/com/example/moneymarket/service/GLBalanceUpdateService.java`

**Method:** `getAllGLNumbers(LocalDate systemDate)`

**Changes:**
1. Still get GL numbers from sub-products with accounts (existing logic)
2. **NEW:** Also get ALL GL numbers from `gl_movement` table for the date
3. **NEW:** Also get ALL GL numbers from `gl_movement_accrual` table for the date
4. Combine all three sources to ensure NO GL data is missed

**Code:**
```java
private Set<String> getAllGLNumbers(LocalDate systemDate) {
    Set<String> glNumbers = new HashSet<>();

    // Get GL numbers from sub-products with accounts
    List<String> activeGLNumbers = glSetupRepository.findActiveGLNumbersWithAccounts();
    glNumbers.addAll(activeGLNumbers);

    // CRITICAL FIX: Also include ALL GLs that have transactions today
    List<String> movementGLNumbers = glMovementRepository.findDistinctGLNumbersByTranDate(systemDate);
    List<String> accrualGLNumbers = glMovementAccrualRepository.findDistinctGLNumbersByAccrualDate(systemDate);

    // Add all GL numbers from transactions
    for (String glNum : movementGLNumbers) {
        if (glNumbers.add(glNum)) {
            log.info("Added GL {} from gl_movement (not in sub-product accounts)", glNum);
        }
    }

    for (String glNum : accrualGLNumbers) {
        if (glNumbers.add(glNum)) {
            log.info("Added GL {} from gl_movement_accrual (not in sub-product accounts)", glNum);
        }
    }

    return glNumbers;
}
```

**Result:**
ALL GL numbers with transactions will now be processed, regardless of whether they are linked to accounts.

---

### Fix 2: Use LCY_Amt for GL Balance Calculations

**File 1:** `moneymarket/src/main/java/com/example/moneymarket/repository/GLMovementRepository.java`

**Method:** `findDrCrSummationNative()`

**Change:**
```sql
SELECT
    GL_Num,
    COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END), 0) AS totalDr,
    COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END), 0) AS totalCr
FROM gl_movement
WHERE GL_Num = :glNum AND Tran_Date BETWEEN :fromDate AND :toDate
GROUP BY GL_Num
```

**File 2:** `moneymarket/src/main/java/com/example/moneymarket/repository/GLMovementAccrualRepository.java`

**Method:** `findDrCrSummationNative()`

**Change:**
```sql
SELECT
    GL_Num,
    COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END), 0) AS totalDr,
    COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END), 0) AS totalCr
FROM gl_movement_accrual
WHERE GL_Num = :glNum AND Accrual_Date BETWEEN :fromDate AND :toDate
GROUP BY GL_Num
```

**Result:**
All GL balance calculations now use `LCY_Amt` (Local Currency Amount), ensuring accurate multi-currency support.

---

## Files Modified

| File | Type | Changes |
|------|------|---------|
| `GLBalanceUpdateService.java` | Service | Updated `getAllGLNumbers()` to include ALL GLs with transactions |
| `GLMovementRepository.java` | Repository | Changed `findDrCrSummationNative()` to use `LCY_Amt` |
| `GLMovementAccrualRepository.java` | Repository | Changed `findDrCrSummationNative()` to use `LCY_Amt` |

---

## Testing Instructions

### Test 1: Verify Missing GLs Are Now Included

1. **Re-run Batch Job 5** for date 2025-04-05:
   ```
   POST http://localhost:8082/api/admin/eod/batch/gl-balance
   ```

2. **Check the logs** for messages like:
   ```
   Added GL 130103001 from gl_movement (not in sub-product accounts)
   Added GL 240103001 from gl_movement (not in sub-product accounts)
   Added GL 220302001 from gl_movement (not in sub-product accounts)
   Added GL 110203001 from gl_movement (not in sub-product accounts)
   ```

3. **Verify gl_balance table** now has entries for these GLs:
   ```sql
   SELECT GL_Num, Tran_Date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal
   FROM gl_balance
   WHERE GL_Num IN ('130103001', '240103001', '220302001', '110203001')
     AND Tran_Date = '2025-04-05'
   ORDER BY GL_Num;
   ```

4. **Expected Result:**
   All 4 GL numbers should now have entries in the gl_balance table.

---

### Test 2: Verify LCY_Amt Is Used

1. **Check a multi-currency transaction:**
   ```sql
   -- Find a transaction with foreign currency
   SELECT Tran_ID, GL_Num, Dr_Cr_Flag, Amount, LCY_Amt, Currency_Code
   FROM gl_movement
   WHERE Currency_Code != 'BDT'  -- or your base currency
     AND Tran_Date = '2025-04-05'
   LIMIT 1;
   ```

2. **Compare with gl_balance:**
   ```sql
   SELECT GL_Num, DR_Summation, CR_Summation
   FROM gl_balance
   WHERE GL_Num = '<gl_num_from_above>'
     AND Tran_Date = '2025-04-05';
   ```

3. **Verify:** The DR_Summation and CR_Summation should match the sum of `LCY_Amt` values, NOT the `Amount` values.

---

### Test 3: End-to-End Test

1. **Delete existing gl_balance entries** for 2025-04-05:
   ```sql
   DELETE FROM gl_balance WHERE Tran_Date = '2025-04-05';
   ```

2. **Re-run Batch Job 5**

3. **Verify ALL GL numbers** with transactions are now in gl_balance:
   ```sql
   -- Get GL numbers from gl_movement
   SELECT DISTINCT GL_Num FROM gl_movement WHERE Tran_Date = '2025-04-05'
   UNION
   -- Get GL numbers from gl_movement_accrual
   SELECT DISTINCT GL_Num FROM gl_movement_accrual WHERE Accrual_Date = '2025-04-05'
   ORDER BY GL_Num;
   ```

4. **Compare with gl_balance:**
   ```sql
   SELECT GL_Num FROM gl_balance WHERE Tran_Date = '2025-04-05'
   ORDER BY GL_Num;
   ```

5. **Expected Result:** Both queries should return the SAME set of GL numbers.

---

## Benefits of This Fix

1. ✅ **Complete GL Data:** No more missing GL numbers in gl_balance table
2. ✅ **Accurate Multi-Currency:** Calculations use LCY_Amt for correct balances
3. ✅ **Better Logging:** Clear visibility when GLs from transactions are added
4. ✅ **CBS Compliance:** Uses local currency amount as required
5. ✅ **Balanced Books:** All GL movements are captured for complete trial balance

---

## Important Notes

- This fix ensures that **ALL** GL numbers with transactions are processed in Batch Job 5
- The fix maintains backward compatibility - GLs linked to accounts are still processed as before
- The logging will clearly show which GLs are being added from transaction tables
- Always use `LCY_Amt` for GL balance calculations (CBS standard requirement)

---

## Next Steps

1. ✅ Test the fix on date 2025-04-05 to verify missing GLs are now included
2. ✅ Verify LCY_Amt is being used for calculations
3. ✅ Run a full EOD cycle to ensure books remain balanced
4. ✅ Monitor logs for any warnings about missing GL data

---

**Fix Applied:** 2025-11-26
**Status:** ✅ COMPLETED - Ready for testing
