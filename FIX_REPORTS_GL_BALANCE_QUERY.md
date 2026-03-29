# FIX: Trial Balance & Balance Sheet Reports Not Showing FX GL Balances ✅

## PROBLEM DIAGNOSED

**Error:** Trial Balance and Balance Sheet reports showing 0.00 for FX Conversion GL accounts despite actual balances in database

**Database balances (gl_balance table):**
```
140203001 (Realised Forex Gain): 236.82 BDT ✓
240203001 (Realised Forex Loss): -1225.00 BDT ✓
```

**Reports showing incorrect:**
```
140203001: 0.00, 0.00, 0.00, 0.00 ✗
240203001: 0.00, 0.00, 0.00, 0.00 ✗
```

## ROOT CAUSE IDENTIFIED

The `ensureFxGLsPresent()` method was designed to ensure forex GL accounts **appear** in reports even if missing from the active GL list. However, it was adding them with **hardcoded zero balances** instead of **fetching actual balances** from the database.

### Original Logic Flow:

1. Query `glBalanceRepository.findByTranDateAndGlNumIn(date, activeGLNumbers)`
   - Returns only GLs linked to sub-products (customer/office accounts)
   - **Excludes** forex GLs (140203001, 240203001) because they're not linked to sub-products

2. Call `ensureFxGLsPresent(glBalances, date)`
   - Checks if forex GLs are in the list
   - If missing, adds them with **hardcoded zero balances** ❌
   - **Never queries database** for actual balances

3. Generate report with zero balances for forex GLs ❌

### Why Forex GLs Are Excluded:

Forex GL accounts (140203001, 240203001) are **system GLs** used for:
- FX Conversion module gain/loss postings
- MCT settlement forex adjustments

These GLs are **not linked to any sub-products**, so they don't appear in the `activeGLNumbers` list from `glSetupRepository.findActiveGLNumbersWithAccounts()`.

## THE FIX

### Updated Logic Flow:

1. Query `glBalanceRepository.findByTranDateAndGlNumIn(date, activeGLNumbers)`
   - Returns only GLs linked to sub-products (same as before)

2. Call **improved** `ensureFxGLsPresent(glBalances, date)`
   - Checks if forex GLs are in the list
   - If missing, **queries database** for actual balances ✅
   - Only adds zero-balance entry if no database record exists ✅

3. Generate report with **actual balances** for forex GLs ✅

### File 1: FinancialReportsService.java (Lines 461-496)

**BEFORE:**
```java
private void ensureFxGLsPresent(List<GLBalance> glBalances, LocalDate date) {
    Set<String> existing = glBalances.stream()
            .map(GLBalance::getGlNum)
            .collect(Collectors.toSet());
    List.of("140203001", "140203002", "240203001", "240203002").forEach(glNum -> {
        if (!existing.contains(glNum)) {
            glBalances.add(GLBalance.builder()
                    .glNum(glNum)
                    .tranDate(date)
                    .openingBal(BigDecimal.ZERO)
                    .drSummation(BigDecimal.ZERO)
                    .crSummation(BigDecimal.ZERO)
                    .closingBal(BigDecimal.ZERO)
                    .currentBalance(BigDecimal.ZERO)
                    .build());
        }
    });
}
```

**AFTER:**
```java
private void ensureFxGLsPresent(List<GLBalance> glBalances, LocalDate date) {
    Set<String> existing = glBalances.stream()
            .map(GLBalance::getGlNum)
            .collect(Collectors.toSet());
    
    List<String> fxGlCodes = List.of("140203001", "140203002", "240203001", "240203002");
    
    fxGlCodes.forEach(glNum -> {
        if (!existing.contains(glNum)) {
            log.info("FX GL {} not in active GL list, fetching from database...", glNum);
            
            // Query gl_balance table directly for this GL
            List<GLBalance> fxBalances = glBalanceRepository.findByTranDateAndGlNumIn(date, List.of(glNum));
            
            if (!fxBalances.isEmpty()) {
                // Found actual balance record, use it
                GLBalance actualBalance = fxBalances.get(0);
                log.info("Found actual balance for {}: Opening={}, DR={}, CR={}, Closing={}", 
                        glNum, 
                        actualBalance.getOpeningBal(),
                        actualBalance.getDrSummation(),
                        actualBalance.getCrSummation(),
                        actualBalance.getClosingBal());
                glBalances.add(actualBalance);
            } else {
                // No balance record for this date, add zero-balance entry
                log.info("No balance record found for {} on {}, adding zero-balance entry", glNum, date);
                glBalances.add(GLBalance.builder()
                        .glNum(glNum)
                        .tranDate(date)
                        .openingBal(BigDecimal.ZERO)
                        .drSummation(BigDecimal.ZERO)
                        .crSummation(BigDecimal.ZERO)
                        .closingBal(BigDecimal.ZERO)
                        .currentBalance(BigDecimal.ZERO)
                        .build());
            }
        }
    });
}
```

**Key Changes:**
- ✅ Calls `glBalanceRepository.findByTranDateAndGlNumIn(date, List.of(glNum))` to fetch actual balance
- ✅ Adds actual balance to report if found
- ✅ Only adds zero-balance entry if no database record exists
- ✅ Added logging to show fetched balances

### File 2: EODStep8ConsolidatedReportService.java (Lines 929-956)

**Same fix applied** - Updated `ensureFxGLsPresent()` to fetch actual balances from database.

## COMPILATION STATUS ✅

```
[INFO] BUILD SUCCESS
[INFO] Total time:  28.309 s
[INFO] Finished at: 2026-03-29T16:12:04+06:00
```

══════════════════════════════════════════════════════════════════════════════
## HOW IT WORKS NOW
══════════════════════════════════════════════════════════════════════════════

### Report Generation Flow:

**Step 1: Get Active GLs**
```java
List<String> activeGLNumbers = glSetupRepository.findActiveGLNumbersWithAccounts();
// Returns: [110101001, 120101001, ...] (GLs linked to sub-products)
// EXCLUDES: 140203001, 240203001 (system GLs, no sub-product link)
```

**Step 2: Get GL Balances for Active GLs**
```java
List<GLBalance> glBalances = glBalanceRepository.findByTranDateAndGlNumIn(date, activeGLNumbers);
// Returns balances for GLs in activeGLNumbers list only
```

**Step 3: Ensure FX GLs Present (NEW LOGIC)**
```java
ensureFxGLsPresent(glBalances, date);

// For each forex GL (140203001, 240203001, etc.):
//   IF not in glBalances list:
//     Query database: findByTranDateAndGlNumIn(date, [glNum])
//     IF found:
//       Add actual balance to list ✅
//     ELSE:
//       Add zero-balance entry
```

**Step 4: Generate Report**
```java
// Trial Balance: glBalances now includes forex GLs with actual balances
// Balance Sheet: glBalances now includes forex GLs with actual balances
```

### Example Execution (with database balances):

```
Input date: 2026-03-29

Active GLs: [110101001, 120101001, ...] (50 GLs)
→ Missing: 140203001, 240203001

ensureFxGLsPresent():
  Check 140203001: NOT in list
    → Query database for 140203001 on 2026-03-29
    → Found: Opening=0.00, DR=236.82, CR=0.00, Closing=236.82
    → Add actual balance to list ✅
  
  Check 240203001: NOT in list
    → Query database for 240203001 on 2026-03-29
    → Found: Opening=0.00, DR=0.00, CR=1225.00, Closing=-1225.00
    → Add actual balance to list ✅

Final glBalances list: 54 GLs (50 active + 4 forex)

Trial Balance output:
  140203001, Realised Forex Gain, 0.00, 236.82, 0.00, 236.82 ✅
  240203001, Realised Forex Loss, 0.00, 0.00, 1225.00, -1225.00 ✅
```

══════════════════════════════════════════════════════════════════════════════
## RESTART BACKEND TO APPLY FIX
══════════════════════════════════════════════════════════════════════════════

**STEP 1:** Stop backend (Ctrl+C in terminal 4)

**STEP 2:** Start with new code:
```bash
cd c:\new_cbs3\cbs3\moneymarket
mvn spring-boot:run
```

**STEP 3:** Wait for: `Started MoneyMarketApplication`

**STEP 4:** Watch logs for FX GL fetch messages when generating reports:
```
FX GL 140203001 not in active GL list, fetching from database...
Found actual balance for 140203001: Opening=0.00, DR=236.82, CR=0.00, Closing=236.82
FX GL 240203001 not in active GL list, fetching from database...
Found actual balance for 240203001: Opening=0.00, DR=0.00, CR=1225.00, Closing=-1225.00
```

══════════════════════════════════════════════════════════════════════════════
## TESTING - TRIAL BALANCE REPORT
══════════════════════════════════════════════════════════════════════════════

### Frontend Testing:

1. Go to `http://localhost:5173/reports/trial-balance`
2. Click "Generate Report" or "Download"
3. Open the downloaded CSV file
4. Search for GL accounts:
   - **140203001** (Realised Forex Gain)
   - **240203001** (Realised Forex Loss)

**Expected Output:**
```
GL_Code,GL_Name,Opening_Bal,DR_Summation,CR_Summation,Closing_Bal
...
140203001,Realised Forex Gain,0.00,236.82,0.00,236.82
...
240203001,Realised Forex Loss,0.00,0.00,1225.00,-1225.00
...
```

**OLD (Incorrect):**
```
140203001,Realised Forex Gain,0.00,0.00,0.00,0.00 ✗
240203001,Realised Forex Loss,0.00,0.00,0.00,0.00 ✗
```

**NEW (Correct):**
```
140203001,Realised Forex Gain,0.00,236.82,0.00,236.82 ✓
240203001,Realised Forex Loss,0.00,0.00,1225.00,-1225.00 ✓
```

══════════════════════════════════════════════════════════════════════════════
## TESTING - BALANCE SHEET REPORT
══════════════════════════════════════════════════════════════════════════════

### Frontend Testing:

1. Go to `http://localhost:5173/reports/balance-sheet`
2. Click "Generate Report" or "Download"
3. Open the downloaded Excel file
4. Look for forex GL accounts in the **LIABILITIES** section (14* accounts start with "1")

**Expected Output:**

**LIABILITIES side:**
```
GL Code    | GL Name                | Closing Balance
140203001  | Realised Forex Gain    | 236.82
140203002  | Un-Realised Forex Gain | 0.00
```

**ASSETS side:**
```
GL Code    | GL Name                | Closing Balance
240203001  | Realised Forex Loss    | -1225.00
240203002  | Unrealised Forex Loss  | 0.00
```

**OLD (Incorrect):**
```
140203001  | Realised Forex Gain    | 0.00 ✗
240203001  | Realised Forex Loss    | 0.00 ✗
```

**NEW (Correct):**
```
140203001  | Realised Forex Gain    | 236.82 ✓
240203001  | Realised Forex Loss    | -1225.00 ✓
```

══════════════════════════════════════════════════════════════════════════════
## TESTING - EOD CONSOLIDATED REPORT
══════════════════════════════════════════════════════════════════════════════

### Manual Testing:

1. Run EOD Step 8: `POST /api/eod/step-8`
2. Download consolidated Excel report
3. Check **"Trial Balance"** sheet → Find 140203001, 240203001
4. Check **"Balance Sheet"** sheet → Find 140203001, 240203001

**Expected:** Both sheets show actual balances (236.82, -1225.00)

══════════════════════════════════════════════════════════════════════════════
## DATABASE VERIFICATION
══════════════════════════════════════════════════════════════════════════════

Run this query to verify gl_balance records exist:

```sql
-- Check forex GL balances
SELECT 
    GL_Num,
    Tran_Date,
    Opening_Bal,
    DR_Summation,
    CR_Summation,
    Closing_Bal,
    Current_Balance
FROM gl_balance
WHERE GL_Num IN ('140203001', '140203002', '240203001', '240203002')
  AND Tran_Date = CURDATE()
ORDER BY GL_Num;
```

**Expected Results:**
```
140203001 | 2026-03-29 | 0.00 | 236.82  | 0.00    | 236.82   | 236.82
240203001 | 2026-03-29 | 0.00 | 0.00    | 1225.00 | -1225.00 | -1225.00
```

**If no records found for today:**
- FX transactions may not have posted yet
- Run EOD process to update gl_balance
- Or check previous date with FX transactions

══════════════════════════════════════════════════════════════════════════════
## BACKEND LOGS TO VERIFY FIX
══════════════════════════════════════════════════════════════════════════════

When generating reports, watch backend logs for these messages:

**SUCCESS (Balances Found):**
```
FX GL 140203001 not in active GL list, fetching from database...
Found actual balance for 140203001: Opening=0.00, DR=236.82, CR=0.00, Closing=236.82
FX GL 240203001 not in active GL list, fetching from database...
Found actual balance for 240203001: Opening=0.00, DR=0.00, CR=1225.00, Closing=-1225.00
Trial Balance Report generated: 54 GL accounts, Total DR=XXX, Total CR=XXX
```

**NO BALANCES (No FX Transactions Posted Yet):**
```
FX GL 140203001 not in active GL list, fetching from database...
No balance record found for 140203001 on 2026-03-29, adding zero-balance entry
FX GL 240203001 not in active GL list, fetching from database...
No balance record found for 240203001 on 2026-03-29, adding zero-balance entry
```

══════════════════════════════════════════════════════════════════════════════
## COMPARISON: OLD vs NEW BEHAVIOR
══════════════════════════════════════════════════════════════════════════════

### Scenario: GL 140203001 has balance of 236.82 in database

**OLD BEHAVIOR:**
1. Active GL query excludes 140203001 (not linked to sub-product)
2. `ensureFxGLsPresent()` sees 140203001 missing
3. Adds hardcoded zero-balance entry ❌
4. Report shows: `140203001, Realised Forex Gain, 0.00, 0.00, 0.00, 0.00` ✗

**NEW BEHAVIOR:**
1. Active GL query excludes 140203001 (same)
2. `ensureFxGLsPresent()` sees 140203001 missing
3. Queries database: `findByTranDateAndGlNumIn(date, ["140203001"])` ✅
4. Finds actual balance: Opening=0.00, DR=236.82, CR=0.00, Closing=236.82 ✅
5. Adds actual balance to list ✅
6. Report shows: `140203001, Realised Forex Gain, 0.00, 236.82, 0.00, 236.82` ✓

══════════════════════════════════════════════════════════════════════════════
## FILES MODIFIED
══════════════════════════════════════════════════════════════════════════════

### Backend:
1. `FinancialReportsService.java`
   - Updated `ensureFxGLsPresent()` method (Line 461)
   - Added database query for missing forex GLs
   - Added logging for debugging

2. `EODStep8ConsolidatedReportService.java`
   - Updated `ensureFxGLsPresent()` method (Line 939)
   - Same fix as FinancialReportsService

══════════════════════════════════════════════════════════════════════════════
## VERIFICATION CHECKLIST
══════════════════════════════════════════════════════════════════════════════

**Pre-Test:**
- ✅ Backend compiled successfully (16:12:04)
- ✅ Database has forex GL balances for today's date
- ✅ FX Conversion transactions posted and verified

**During Test:**
- [ ] Backend logs show "Found actual balance for 140203001: ..."
- [ ] Backend logs show "Found actual balance for 240203001: ..."

**Post-Test:**
- [ ] Trial Balance shows 140203001 with balance 236.82
- [ ] Trial Balance shows 240203001 with balance -1225.00 (or 1225.00 in CR column)
- [ ] Balance Sheet shows 140203001 with balance 236.82
- [ ] Balance Sheet shows 240203001 with balance -1225.00
- [ ] EOD Consolidated Report shows correct balances in both sheets

══════════════════════════════════════════════════════════════════════════════
## EXPECTED RESULT
══════════════════════════════════════════════════════════════════════════════

### Trial Balance:
```
GL_Code   | GL_Name               | Opening_Bal | DR_Summation | CR_Summation | Closing_Bal
140203001 | Realised Forex Gain   | 0.00        | 236.82       | 0.00         | 236.82
140203002 | Un-Realised Forex Gain| 0.00        | 0.00         | 0.00         | 0.00
240203001 | Realised Forex Loss   | 0.00        | 0.00         | 1225.00      | -1225.00
240203002 | Unrealised Forex Loss | 0.00        | 0.00         | 0.00         | 0.00
```

### Balance Sheet:
```
LIABILITIES                         | ASSETS
GL_Code   | GL_Name      | Closing | GL_Code   | GL_Name      | Closing
140203001 | Forex Gain   | 236.82  | 240203001 | Forex Loss   | -1225.00
140203002 | Forex Gain   | 0.00    | 240203002 | Forex Loss   | 0.00
```

### Net Impact:
```
Total Forex Gains:  236.82
Total Forex Losses: 1225.00
Net Forex Impact:   -988.18 (Loss)
```

══════════════════════════════════════════════════════════════════════════════
## FINAL STATUS: FIX 10 COMPLETE ✅
══════════════════════════════════════════════════════════════════════════════

**Backend:** Compiled and ready (16:12:04)  
**Fix Applied:** Both report services updated  
**Database:** Forex GL balances verified  
**Logs:** Added for debugging  

**NEXT:** Restart backend and test reports! 🚀
