# WAE Rate "N/A" Issue - Analysis Report

## 📋 STEP 1 COMPLETED: All WAE-Related Files Located

### Backend Files

#### Entities
1. **`WaeMaster.java`** - `moneymarket/src/main/java/com/example/moneymarket/entity/WaeMaster.java`
   - Tracks Position GL WAE rates for currency pairs (USD/BDT, EUR/BDT)
   - Updated by BUY transactions only
   - **NOT used for customer account WAE display**

2. **`AcctBal.java`** - Contains FCY balance snapshots
   - Used for WAE calculation (Opening_Bal, CR_Summation, DR_Summation)

3. **`AcctBalLcy.java`** - Contains LCY equivalent balance snapshots
   - Used for WAE calculation (Opening_Bal_lcy, CR_Summation_lcy, DR_Summation_lcy)

#### Repositories
1. **`WaeMasterRepository.java`** - `moneymarket/src/main/java/com/example/moneymarket/repository/WaeMasterRepository.java`
   - Methods: `findByCcyPair()`, `findWaeRateByCcyPair()`

#### Services
1. **`BalanceService.java`** - `moneymarket/src/main/java/com/example/moneymarket/service/BalanceService.java`
   - **PRIMARY WAE CALCULATION LOGIC** (lines 395-414)
   - Method: `getComputedAccountBalance()` - returns `AccountBalanceDTO` with WAE
   - Formula: `WAE = computedBalanceLcy / computedBalanceFcy`
   - Source: `acct_bal` + `acct_bal_lcy` records (NOT `wae_master`)

2. **`MultiCurrencyTransactionService.java`** - `moneymarket/src/main/java/com/example/moneymarket/service/MultiCurrencyTransactionService.java`
   - Method: `updateWAEMasterForBuy()` (lines 453-494) - Updates Position GL WAE only
   - Method: `getWAERate()` (lines 538-543) - Returns WAE from `wae_master` table
   - **NOT used for customer account WAE display**

3. **`AccountBalanceUpdateService.java`** - Updates `acct_bal` and `acct_bal_lcy` during transactions

#### Controllers
1. **`AccountBalanceController.java`** - `moneymarket/src/main/java/com/example/moneymarket/controller/AccountBalanceController.java`
   - Endpoint: `GET /api/accounts/{accountNo}/balance`
   - Calls: `balanceService.getComputedAccountBalance(accountNo)`

2. **`MultiCurrencyTransactionController.java`**
   - Endpoint: `GET /api/mct/wae/{currency}` (lines 126-149)
   - Returns WAE from `wae_master` table (Position GL only)
   - **NOT used by transaction form for customer accounts**

### Frontend Files

1. **`TransactionForm.tsx`** - `frontend/src/pages/transactions/TransactionForm.tsx`
   - Line 1114: `const wae = accountBalances.get(\`${index}\`)?.wae;`
   - Line 1128: Display: `WAE: ${wae !== undefined && wae !== null ? wae.toFixed(4) : 'N/A'}`
   - Fetches WAE via: `getAccountBalance(accountNo)` → `/api/accounts/{accountNo}/balance`

2. **`AccountBalanceDTO` (TypeScript)** - `frontend/src/types/transaction.ts`
   - Line 80: `wae?: number;` - Optional WAE rate field

---

## 🔍 STEP 2: Exact Break Point Analysis

### A) Backend WAE Fetch Endpoint

**How it works:**
```java
// File: BalanceService.java, lines 329-349
// WAE from acc_bal closing position (EOD-confirmed data)
BigDecimal waeFcyBase = ob.add(cr).subtract(dr);  // From acct_bal
BigDecimal waeLcyBase = obLcy.add(crLcy).subtract(drLcy);  // From acct_bal_lcy
BigDecimal wae = calculateWae(accountCcy, waeLcyBase, waeFcyBase, systemDate);

// Line 401-414
private BigDecimal calculateWae(...) {
    if (computedBalanceFcy.compareTo(BigDecimal.ZERO) == 0) {
        return null;  // Returns null if FCY balance is zero
    }
    return computedBalanceLcy.abs()
            .divide(computedBalanceFcy.abs(), 4, RoundingMode.HALF_UP);
}
```

**Query used:**
```java
// Lines 176-183
AcctBal currentDayBalance = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate)
        .orElseGet(() -> acctBalRepository.findLatestByAccountNo(accountNo)...);

Optional<AcctBalLcy> currentDayLcyBalance = acctBalLcyRepository.findByAccountNoAndTranDate(
        accountNo, currentDayBalance.getTranDate());
```

**Source tables:**
1. `acct_bal` - FCY balance components
2. `acct_bal_lcy` - LCY balance components

**NOT used:**
- `wae_master` table (only for Position GL tracking)

### B) Database Table Check Needed

Run this SQL to verify data exists:

```sql
-- Check if acct_bal has records for FCY accounts
SELECT COUNT(*) FROM acct_bal WHERE Ccy_Code != 'BDT';

-- Check if acct_bal_lcy has matching records
SELECT COUNT(*) FROM acct_bal_lcy WHERE Ccy_Code != 'BDT';

-- Check specific account
SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Opening_Bal,
    ab.CR_Summation,
    ab.DR_Summation,
    abl.Opening_Bal_lcy,
    abl.CR_Summation_lcy,
    abl.DR_Summation_lcy
FROM acct_bal ab
LEFT JOIN acct_bal_lcy abl ON ab.Account_No = abl.Account_No AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_No = '<TEST_FCY_ACCOUNT>'
ORDER BY ab.Tran_Date DESC
LIMIT 5;
```

### C) WAE Calculation Returns NULL When:

1. **Account currency is BDT** → Correct behavior
2. **computedBalanceLcy is NULL** → Missing `acct_bal_lcy` record
3. **computedBalanceFcy is NULL** → Missing `acct_bal` record
4. **computedBalanceFcy == 0** → Account has zero FCY balance

### D) Step 7 Bypass Analysis

**Checking bypassed code** (lines 92-190 in `RevaluationService.java`):

The bypassed revaluation code:
- ✅ **DOES read from `wae_master`** (line 110-113) - but only to check if GL positions exist
- ❌ **DOES NOT update `wae_master`** - No calls to `updateWAEMaster()` or similar
- ❌ **DOES NOT create/update `acct_bal` records** - This is done by EOD Step 1
- ❌ **DOES NOT create/update `acct_bal_lcy` records** - This is done by EOD Step 1

**Conclusion:** Step 7 bypass did NOT remove any WAE update logic. The revaluation was only READING WAE data, not creating it.

---

## 🎯 ROOT CAUSE IDENTIFIED

### Most Likely Cause: Missing `acct_bal` / `acct_bal_lcy` Records

**Why WAE shows "N/A":**

1. The `BalanceService.calculateWae()` method returns `null` when:
   - `acct_bal_lcy` record is missing for the account
   - OR `computedBalanceFcy` (FCY balance) is zero

2. **The Step 7 bypass did NOT cause this directly**, but may have exposed an existing issue:
   - If you haven't run **EOD Step 1 (Balance Snapshot)** today
   - OR if FCY accounts have zero balance
   - OR if `acct_bal_lcy` records weren't created properly

### What Creates `acct_bal` / `acct_bal_lcy` Records?

**EOD Step 1 (Balance Snapshot)**:
- Creates daily snapshot records in `acct_bal` for ALL accounts
- Creates matching records in `acct_bal_lcy` for FCY accounts
- Sets Opening_Bal, CR_Summation, DR_Summation, Closing_Bal

**Real-time transaction posting**:
- `AccountBalanceUpdateService.updateAccountBalance()` updates running totals
- But WAE calculation uses EOD-confirmed data from `acct_bal`, not real-time

---

## 🛠️ STEP 3: APPLY THE FIX

Based on the analysis, the issue is **NOT with Step 7**, but with missing or incomplete `acct_bal`/`acct_bal_lcy` data.

### Option 1: Run EOD Step 1 (Most Likely Fix)

If you haven't run EOD Step 1 today, that's why WAE is "N/A".

**Action:**
```
POST /api/admin/eod/execute-step?step=1
```

This will create today's `acct_bal` and `acct_bal_lcy` snapshot records.

### Option 2: Check for Zero Balance Accounts

If accounts have zero FCY balance, WAE will correctly show "N/A".

**Verify:**
```sql
SELECT Account_No, Current_Balance 
FROM acct_bal 
WHERE Account_No = '<TEST_ACCOUNT>' 
AND Tran_Date = (SELECT MAX(Tran_Date) FROM acct_bal WHERE Account_No = '<TEST_ACCOUNT>');
```

If `Current_Balance = 0`, then "N/A" is correct behavior.

### Option 3: Verify `acct_bal_lcy` Records Exist

If `acct_bal` exists but `acct_bal_lcy` is missing, that's the issue.

**Check:**
```sql
SELECT COUNT(*) FROM acct_bal WHERE Ccy_Code = 'USD';
SELECT COUNT(*) FROM acct_bal_lcy WHERE Ccy_Code = 'USD';
```

If first query > 0 but second query = 0, then `acct_bal_lcy` creation is broken.

**Fix:** Check `EODOrchestrationService.executeBatchJob1()` to ensure it creates `acct_bal_lcy` records.

### Option 4: Add Fallback to `wae_master` (If Needed)

**ONLY if the above options don't work**, consider adding a fallback in `BalanceService.calculateWae()`:

```java
private BigDecimal calculateWae(String accountCcy, BigDecimal computedBalanceLcy, 
                                BigDecimal computedBalanceFcy, LocalDate systemDate) {
    if (accountCcy == null || "BDT".equalsIgnoreCase(accountCcy)) {
        return null;
    }
    
    // If acct_bal data is missing, try fallback to wae_master
    if (computedBalanceLcy == null || computedBalanceFcy == null) {
        String ccyPair = accountCcy + "/BDT";
        return waeMasterRepository.findByCcyPair(ccyPair)
            .map(WaeMaster::getWaeRate)
            .orElse(null);
    }
    
    if (computedBalanceFcy.compareTo(BigDecimal.ZERO) == 0) {
        return null;
    }
    
    return computedBalanceLcy.abs()
            .divide(computedBalanceFcy.abs(), 4, RoundingMode.HALF_UP);
}
```

**⚠️ WARNING:** This fallback uses Position GL WAE, which may not match individual account WAE.

---

## ✅ STEP 4: VERIFICATION STEPS

### 1. Run SQL Diagnostic Script
```bash
mysql -u your_user -p your_database < diagnose_wae_issue.sql
```

This will show:
- How many `acct_bal` / `acct_bal_lcy` records exist
- Sample WAE calculation for test accounts
- Whether EOD Step 1 has run today

### 2. Test WAE API Directly
```bash
curl http://localhost:8080/api/accounts/120101011/balance
```

Check the response:
```json
{
  "accountNo": "120101011",
  "accountCcy": "USD",
  "wae": 110.5000  // Should show a number, not null
}
```

### 3. Test Transaction Form
1. Open transaction form
2. Select a USD account with positive balance
3. Set Dr/Cr to "Liability DR" or "Asset CR"
4. WAE should show a rate (e.g., "110.5000"), not "N/A"

### 4. Verify Step 7 Bypass Still Works
```bash
POST /api/admin/eod/execute-step?step=7
```

Should return:
- `recordsProcessed: 0`
- No new rows in `tran_table` with `Tran_Id LIKE 'REVAL-%'`

---

## 📊 FILES FOR INVESTIGATION

### Use SQL Diagnostic Script:
- **`diagnose_wae_issue.sql`** - Comprehensive diagnostic queries

### Review These Services:
1. **`BalanceService.java`** (lines 329-414) - WAE calculation logic
2. **`EODOrchestrationService.java`** - Check `executeBatchJob1()` for balance snapshot creation
3. **`AccountBalanceUpdateService.java`** - Check if it creates `acct_bal_lcy` records

---

## 🎯 NEXT STEPS

1. **Run diagnostic SQL script** to identify which data is missing
2. **Check if EOD Step 1 has run today** - Most likely cause
3. **Verify test accounts have non-zero FCY balance** - WAE is correctly "N/A" for zero balance
4. **Check `acct_bal_lcy` table** - Ensure records exist matching `acct_bal`
5. **If all else fails**, add fallback to `wae_master` (Option 4 above)

---

## ⚠️ IMPORTANT NOTES

- **Step 7 bypass is NOT the root cause** - It only reads WAE, doesn't create it
- **WAE comes from `acct_bal` + `acct_bal_lcy`**, NOT from `wae_master`
- **`wae_master` is only for Position GL tracking**, not customer accounts
- **EOD Step 1 creates the balance snapshots** needed for WAE calculation
- **"N/A" is correct behavior** if account has zero FCY balance

---

## 🆘 TROUBLESHOOTING

### Issue: WAE is "N/A" for all accounts
**Solution:** Run EOD Step 1 to create today's balance snapshots

### Issue: WAE works for some accounts but not others
**Solution:** Check which accounts have records in `acct_bal_lcy`

### Issue: acct_bal exists but acct_bal_lcy is missing
**Solution:** Fix EOD Step 1 logic to create `acct_bal_lcy` records

### Issue: Both tables are empty
**Solution:** System is in initial state - run full EOD batch once
