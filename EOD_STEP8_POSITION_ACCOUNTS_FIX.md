# EOD Step 8 Trial Balance - Position Accounts Fix

## Problem
The EOD Step 8 Trial Balance Excel report was not showing Position accounts (920101002 - PSUSD EQIV, 920101001 - PSBDT EQIV) even though they exist in `gl_balance` table.

## Root Cause
The `EODStep8ConsolidatedReportService.java` was fetching GL accounts from `gl_setup` using `findActiveGLNumbersWithAccounts()`, which filters based on whether GL accounts have linked customer/office accounts. Position accounts don't have such links, so they were being filtered out.

## Solution Implemented

### Code Changes

**File:** `moneymarket/src/main/java/com/example/moneymarket/service/EODStep8ConsolidatedReportService.java`

**Added Method:** `ensurePositionGLsPresent()`

This method ensures Position accounts (920101001, 920101002) are always included in the report, even if they're not in the active GL list. It:

1. Checks if Position accounts are already in the result list
2. If missing, fetches them from `gl_balance` table for the report date
3. If no balance exists for that date, fetches the latest balance
4. If no balance exists at all, adds a zero-balance placeholder

**Updated Methods:**

1. **`generateTrialBalanceSheet()`** - Added `ensurePositionGLsPresent(glBalances, eodDate);` after `ensureFxGLsPresent()`
2. **`generateBalanceSheetSheet()`** - Added `ensurePositionGLsPresent(glBalances, eodDate);` after `ensureFxGLsPresent()`

### Implementation Details

```java
private void ensurePositionGLsPresent(List<GLBalance> glBalances, LocalDate date) {
    Set<String> existing = glBalances.stream()
            .map(GLBalance::getGlNum)
            .collect(Collectors.toSet());
    
    List<String> positionGlCodes = List.of("920101001", "920101002");
    
    positionGlCodes.forEach(glNum -> {
        if (!existing.contains(glNum)) {
            // Fetch from database or add zero-balance placeholder
            // (See implementation in service file)
        }
    });
}
```

## Database Prerequisites

Ensure Position accounts exist in `gl_balance` and `gl_setup`:

```sql
-- 1. Verify Position accounts in gl_balance
SELECT gl_code, ccy, Opening_Bal, balance, last_updated
FROM gl_balance
WHERE gl_code IN ('920101001', '920101002')
ORDER BY gl_code, ccy;

-- Expected: At least 2 rows (920101001-BDT, 920101002-USD)

-- 2. Verify GL names in gl_setup
SELECT GL_Code, GL_Name, GL_Type
FROM gl_setup
WHERE GL_Code IN ('920101001', '920101002');

-- Expected:
-- 920101001 | PSBDT EQIV | LIABILITY
-- 920101002 | PSUSD EQIV | LIABILITY
```

**If missing, run:** `verify-position-accounts-data.sql` (already provided in previous documentation)

## Testing Steps

### 1. Start Backend
```bash
cd moneymarket
mvnw spring-boot:run
```

### 2. Run EOD Step 8
Navigate to EOD Management in the application and execute Step 8, or call the API directly:

```bash
curl -X POST http://localhost:8082/api/admin/eod/batch-job-8/execute
```

### 3. Check Console Logs
Look for these log messages:

```
=== Starting EOD Step 8: Consolidated Report Generation for date: 2026-03-30 ===
Generating Sheet 1: Trial Balance
Position GL 920101001 not in result list, fetching from database...
Found Position account 920101001 on 2026-03-30: Opening=112000.00, DR=0.00, CR=0.00, Closing=112000.00
Position GL 920101002 not in result list, fetching from database...
Found Position account 920101002 on 2026-03-30: Opening=1000.00, DR=0.00, CR=0.00, Closing=1000.00
✓ Position account 920101001 already in result list
✓ Position account 920101002 already in result list
Trial Balance sheet generated: 45 GL accounts
```

### 4. Download Excel Report
The generated Excel file should be available for download from the EOD Step 8 endpoint.

**Download URL:**
```
GET http://localhost:8082/api/admin/eod/batch-job-8/download/{date}
```

### 5. Verify Excel Contents

Open the downloaded Excel file and verify:

**Sheet 1: Trial Balance**

| GL Code   | GL Name      | Currency | Opening Balance | DR Sum | CR Sum | Closing Balance |
|-----------|--------------|----------|-----------------|--------|--------|-----------------|
| 920101001 | PSBDT EQIV   | BDT      | 112000.00       | 0.00   | 0.00   | 112000.00       |
| 920101002 | PSUSD EQIV   | USD      | 1000.00         | 0.00   | 0.00   | 1000.00         |
| 920101002 | PSUSD EQIV   | EUR      | 500.00          | 0.00   | 0.00   | 500.00          |
| ...       | ...          | ...      | ...             | ...    | ...    | ...             |

**Sheet 2: Balance Sheet**

Position accounts should appear in the LIABILITY section:

| GL Code   | GL Name      | Amount    |
|-----------|--------------|-----------|
| 920101001 | PSBDT EQIV   | 112000.00 |
| 920101002 | PSUSD EQIV   | 1000.00   |

## Expected Results

✅ Position accounts (920101001, 920101002) included in Trial Balance sheet
✅ Position accounts included in Balance Sheet sheet
✅ Multiple currencies displayed for 920101002 (USD, EUR, GBP)
✅ Correct opening/closing balances from `gl_balance` table
✅ GL names fetched from `gl_setup` or `gl_master`
✅ All other GL accounts continue to display correctly
✅ No breaking changes to existing EOD Step 8 logic

## Technical Details

### How It Works

1. **Initial Query:** EOD Step 8 fetches active GL accounts using `glSetupRepository.findActiveGLNumbersWithAccounts()`
2. **FX GLs Check:** `ensureFxGLsPresent()` adds FX Conversion accounts (140203001, 140203002, 240203001, 240203002) if missing
3. **Position GLs Check:** `ensurePositionGLsPresent()` adds Position accounts (920101001, 920101002) if missing
4. **Result:** All critical GL accounts are guaranteed to be in the report, regardless of filtering

### Why Position Accounts Were Missing

Position accounts don't have linked customer/office accounts in `sub_prod_master` or `cust_acct_master`, so they were filtered out by the `findActiveGLNumbersWithAccounts()` query. The fix ensures they're explicitly included.

### Database Table Structure

**gl_balance:**
- `gl_code` or `GL_Num` (VARCHAR) - GL account number
- `ccy` (VARCHAR) - Currency code (BDT, USD, EUR, etc.)
- `Opening_Bal` (DECIMAL) - Opening balance
- `DR_Summation` (DECIMAL) - Total debits
- `CR_Summation` (DECIMAL) - Total credits
- `Closing_Bal` or `balance` (DECIMAL) - Closing balance
- `Tran_Date` or `tran_date` (DATE) - Transaction date

## Files Modified

1. **EODStep8ConsolidatedReportService.java**
   - Added `ensurePositionGLsPresent()` method
   - Called in `generateTrialBalanceSheet()` (line ~107)
   - Called in `generateBalanceSheetSheet()` (line ~191)

## Troubleshooting

### Issue: Position accounts still not showing

**Possible causes:**

1. **No data in gl_balance:** Run `verify-position-accounts-data.sql` to insert data
2. **Column name mismatch:** Check if your table uses `GL_Num` vs `gl_code`
3. **Repository method issue:** Verify `glBalanceRepository.findByTranDateAndGlNumIn()` works correctly

**Debug steps:**

```sql
-- Check if Position accounts exist
SELECT * FROM gl_balance WHERE gl_code IN ('920101001', '920101002');

-- Check table structure
DESCRIBE gl_balance;

-- Check GL names
SELECT * FROM gl_setup WHERE GL_Code IN ('920101001', '920101002');
```

### Issue: Excel file shows "Unknown GL Account" for Position accounts

**Cause:** GL names not in `gl_setup` or `gl_master`

**Fix:** Insert GL master records:

```sql
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, CCY, Status)
VALUES 
('920101001', 'PSBDT EQIV', 'LIABILITY', 'BDT', 'Active'),
('920101002', 'PSUSD EQIV', 'LIABILITY', 'USD', 'Active')
ON DUPLICATE KEY UPDATE GL_Name = VALUES(GL_Name);
```

## Related Files and Documentation

- `TRIAL_BALANCE_POSITION_ACCOUNTS_COMPLETE.md` - Fix for Financial Reports page
- `verify-position-accounts-data.sql` - Database setup script
- `TRIAL_BALANCE_ENHANCEMENTS_COMPLETE.md` - Overall enhancement summary

## Summary

This fix ensures that Position accounts (920101001 - PSBDT EQIV, 920101002 - PSUSD EQIV) are **always included** in the EOD Step 8 Trial Balance and Balance Sheet Excel reports, even if they're not in the active GL list. The implementation follows the same pattern as the existing `ensureFxGLsPresent()` method, ensuring consistency and maintainability.

**No existing EOD Step 8 logic was modified** - only the GL account fetching was enhanced to include Position accounts.
