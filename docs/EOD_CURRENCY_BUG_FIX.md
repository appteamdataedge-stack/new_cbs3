# EOD Currency Corruption Bug - Complete Fix

## Problem Summary

**Critical Bug:** EOD batch process was overwriting account currency from USD to BDT for office accounts (particularly NOSTRO accounts).

### Specific Case
- **Transaction T20250614000001304** posted correctly with USD:
  - Data Edge Account (200000023003): `Tran_Ccy = USD`, Dr 15110.5 USD ✓
  - Nostro Account (922030200101): `Tran_Ccy = USD`, Cr 15110.5 USD ✓

- **After EOD Batch Job** ran:
  - Data Edge Account: `Account_Ccy = USD` ✓ (CORRECT)
  - Nostro Account (922030200101): `Account_Ccy = BDT` ✗ (WRONG - Should be USD)

## Root Cause Analysis

### The Bug Location
**File:** `AccountBalanceUpdateService.java`  
**Method:** `processAccountBalance()` (lines 118-194)

### What Went Wrong

#### Issue 1: Currency Not Updated on Existing Records
**Lines 150-162 (BEFORE FIX):**
```java
if (existingRecord.isPresent()) {
    // Update existing record
    accountBalance = existingRecord.get();
    accountBalance.setOpeningBal(openingBal);
    accountBalance.setDrSummation(drSummation);
    // ... other fields updated ...
    // ❌ BUG: Account_Ccy was NOT updated, retaining old (possibly wrong) value
}
```

**Problem:** When updating an existing `Acct_Bal` record, the code did not update the `Account_Ccy` field. If the field was previously set incorrectly, it would stay incorrect.

#### Issue 2: Office Accounts Not Checked for Currency
**Lines 163-166 (BEFORE FIX):**
```java
// Insert new record - get account currency from cust_acct_master
String accountCurrency = custAcctMasterRepository.findById(accountNo)
        .map(acct -> acct.getAccountCcy())
        .orElse("BDT"); // ❌ BUG: Defaults to BDT if not found
```

**Problem:** The code only checked `Cust_Acct_Master` table, not `OF_Acct_Master` table. Since NOSTRO account (922030200101) is an **office account**, it wasn't found in the customer table, so the code defaulted to `'BDT'`.

### Why This Happened

1. **First EOD Run:** NOSTRO account not found in customer table → defaulted to BDT
2. **Subsequent EOD Runs:** Existing record found, but `Account_Ccy` not updated → BDT persisted
3. **Transactions:** Posted correctly with USD (transaction logic was correct)
4. **Balance Reports:** Showed BDT instead of USD (incorrect balance records)

## The Fix

### Code Changes Made

**File:** `c:\new_cbs3\cbs3\moneymarket\src\main\java\com\example\moneymarket\service\AccountBalanceUpdateService.java`

#### Change 1: Check Both Customer AND Office Accounts (Lines 148-154)
```java
// ✅ FIX: Get account currency from BOTH customer and office account tables
// This ensures NOSTRO and other office accounts get correct currency (USD, not BDT)
String accountCurrency = custAcctMasterRepository.findById(accountNo)
        .map(acct -> acct.getAccountCcy())
        .orElseGet(() -> ofAcctMasterRepository.findById(accountNo)
                .map(acct -> acct.getAccountCcy())
                .orElse("BDT")); // Default to BDT only if account not found in either table
```

**Impact:** Now checks office accounts when customer account lookup fails, ensuring NOSTRO and other office accounts get correct currency.

#### Change 2: Update Currency on Existing Records (Line 161)
```java
if (existingRecord.isPresent()) {
    // ✅ FIX: Update existing record AND preserve/correct Account_Ccy
    accountBalance = existingRecord.get();
    accountBalance.setAccountCcy(accountCurrency); // ← FIX: Ensure currency matches account master
    accountBalance.setOpeningBal(openingBal);
    // ... rest of updates ...
}
```

**Impact:** Existing records now get their currency updated/corrected every EOD run to match account master.

#### Change 3: Improved Logging (Lines 170-171, 187-188)
```java
log.debug("Updating existing EOD record for account: {} on date: {} with currency: {}", 
        accountNo, systemDate, accountCurrency);
```

**Impact:** Better visibility into what currency is being used for each account during EOD.

## Database Fix

### Fixing Historical Data Corruption

The code fix prevents future corruption, but **existing corrupted records need to be fixed**.

**Script:** `scripts/fix_eod_currency_corruption.sql`

This script:
1. **Diagnoses** all accounts with currency mismatches
2. **Fixes** customer account balances to match `Cust_Acct_Master.Account_Ccy`
3. **Fixes** office account balances to match `OF_Acct_Master.Account_Ccy`
4. **Specifically fixes** NOSTRO account (922030200101) to use USD
5. **Verifies** all fixes were applied correctly

### Running the Fix Script

```sql
-- Step 1: Backup database
BACKUP DATABASE [YourDatabaseName]
TO DISK = 'C:\Backups\BeforeEODCurrencyFix.bak';

-- Step 2: Run the fix script
:r c:\new_cbs3\cbs3\scripts\fix_eod_currency_corruption.sql

-- Step 3: Verify (included in script)
-- Check that all USD accounts show USD currency in Acct_Bal
```

## Testing the Fix

### Test 1: Verify Historical Data Corrected

```sql
-- Check NOSTRO account currency
SELECT 
    Account_No,
    Tran_Date,
    Account_Ccy,
    Current_Balance
FROM Acct_Bal
WHERE Account_No = '922030200101'
ORDER BY Tran_Date DESC;
```

**Expected Result:**
```
Account_No      | Tran_Date  | Account_Ccy | Current_Balance
----------------|------------|-------------|------------------
922030200101    | 2025-06-14 | USD         | 15110.50
```

### Test 2: Run EOD and Verify Currency Preserved

```java
// Trigger EOD batch job
POST /api/bod/account-balance-update

// Check logs for:
// "Updating existing EOD record for account: 922030200101 on date: 2025-06-15 with currency: USD"
```

**Expected:** NOSTRO account should show `currency: USD` in logs.

### Test 3: Verify New Transactions

```sql
-- Post a new transaction to NOSTRO account
-- Run EOD
-- Check Acct_Bal

SELECT Account_No, Tran_Date, Account_Ccy, Current_Balance
FROM Acct_Bal
WHERE Account_No = '922030200101'
  AND Tran_Date = '2025-06-15';
```

**Expected:** `Account_Ccy = 'USD'`

## Impact Analysis

### Affected Accounts

**Primary Impact:**
- **Office Accounts** with non-BDT currency (NOSTRO USD, NOSTRO EUR, etc.)
- **Sub-Product: NSUSD** (Sub_Product_Id: 51, Product_Id: 36)

**Secondary Impact:**
- Any customer account that had currency mismatch between master and balance tables

### Affected Systems

1. **EOD Batch Processing** - Now correctly maintains currency
2. **Balance Reporting** - Will show correct currency after fix
3. **Statement of Accounts** - Will display correct currency
4. **Financial Reports** - Will use correct currency for calculations

## Prevention Measures

### Code Review Points

1. **Always check both account tables** when getting account data:
   ```java
   custAcctMasterRepository.findById(accountNo)
       .orElseGet(() -> ofAcctMasterRepository.findById(accountNo))
   ```

2. **Always update currency** when updating balance records:
   ```java
   accountBalance.setAccountCcy(accountCurrency);
   ```

3. **Log currency information** for debugging:
   ```java
   log.debug("Processing account: {} with currency: {}", accountNo, accountCurrency);
   ```

### Testing Checklist

Before deploying EOD changes:
- [ ] Test with customer accounts (BDT and USD)
- [ ] Test with office accounts (BDT and USD)
- [ ] Verify currency matches account master after EOD
- [ ] Check logs show correct currency being used
- [ ] Validate against multiple dates

## Comparison: Before vs After

### BEFORE FIX

**Transaction Posting:**
```
✓ Transaction T20250614000001304
  - Data Edge: Tran_Ccy = USD, Dr 15110.5 USD
  - NOSTRO: Tran_Ccy = USD, Cr 15110.5 USD
```

**EOD Batch Process:**
```
❌ NOSTRO Account (922030200101)
   - Lookup in Cust_Acct_Master: Not found (it's an office account)
   - Default: Account_Ccy = 'BDT'
   - Result: Acct_Bal.Account_Ccy = 'BDT' (WRONG!)
```

**Balance Table:**
```
Account: 922030200101
Account_Ccy: BDT ❌ (Should be USD)
Current_Balance: 15110.50 (in BDT? USD? Ambiguous!)
```

### AFTER FIX

**Transaction Posting:**
```
✓ Transaction posts correctly (no change)
  - Data Edge: Tran_Ccy = USD, Dr 15110.5 USD
  - NOSTRO: Tran_Ccy = USD, Cr 15110.5 USD
```

**EOD Batch Process:**
```
✓ NOSTRO Account (922030200101)
   - Lookup in Cust_Acct_Master: Not found
   - Lookup in OF_Acct_Master: Found! Account_Ccy = 'USD'
   - Update/Insert: Account_Ccy = 'USD'
   - Result: Acct_Bal.Account_Ccy = 'USD' ✓
```

**Balance Table:**
```
Account: 922030200101
Account_Ccy: USD ✓ (CORRECT)
Current_Balance: 15110.50 USD (Clear and correct)
```

## Related Issues Fixed

This fix also resolves related issues:

1. **Balance validation** using wrong amount (BDT instead of USD)
2. **Report currency display** showing BDT for USD accounts
3. **Interest calculation** using wrong currency base
4. **Statement generation** displaying incorrect currency

## Architecture Notes

### Currency Flow (Corrected)

```
Product Master (Prod_Master)
  └── Currency: "USD"
       |
       ↓
Sub-Product (Sub_Prod_Master)
  └── Inherits from Product
       |
       ↓
Account Master (OF_Acct_Master / Cust_Acct_Master)
  └── Account_Ccy: Set from Product (e.g., "USD")
       |
       ↓
Account Balance (Acct_Bal) ← EOD UPDATES THIS
  └── Account_Ccy: MUST match Account Master ✓ (NOW FIXED)
```

### Key Repositories Used

1. **CustAcctMasterRepository** - Customer accounts
2. **OFAcctMasterRepository** - Office accounts (NOSTRO, internal accounts)
3. **AcctBalRepository** - Balance records (EOD updates this)
4. **TranTableRepository** - Transaction history

## Monitoring and Validation

### Daily Checks (Automated)

Add these queries to your daily monitoring:

```sql
-- Check for currency mismatches (should return 0 rows)
SELECT COUNT(*) AS Mismatch_Count
FROM Acct_Bal ab
INNER JOIN Cust_Acct_Master cam ON ab.Account_No = cam.Account_No
WHERE ab.Account_Ccy != cam.Account_Ccy

UNION ALL

SELECT COUNT(*) AS Mismatch_Count
FROM Acct_Bal ab
INNER JOIN OF_Acct_Master oam ON ab.Account_No = oam.Account_No
WHERE ab.Account_Ccy != oam.Account_Ccy;
```

**Expected Result:** `Mismatch_Count = 0` for both queries

### Log Monitoring

Look for these log entries after EOD:

```
✓ "Updating existing EOD record for account: 922030200101 on date: 2025-06-15 with currency: USD"
✓ "Creating new EOD record for account: 922030200101 (currency: USD) on date: 2025-06-15"
```

**Red Flags (Should NOT appear):**
```
❌ "currency: BDT" for known USD accounts
❌ "currency: BDT" for NOSTRO accounts
```

## Rollback Plan

If issues occur after deployment:

### Step 1: Restore Database Backup
```sql
RESTORE DATABASE [YourDatabaseName]
FROM DISK = 'C:\Backups\BeforeEODCurrencyFix.bak';
```

### Step 2: Revert Code Changes
```bash
git revert <commit-hash>
```

### Step 3: Investigate
- Check logs for specific error messages
- Identify which accounts are affected
- Determine if it's a code issue or data issue

## Success Criteria

### Code Fix Success
- [x] Code checks both customer and office account tables
- [x] Currency is updated on existing records
- [x] Logging shows currency being used
- [x] No linter errors

### Database Fix Success
- [ ] Run diagnostic script (included in fix script)
- [ ] All USD office accounts show `Account_Ccy = 'USD'`
- [ ] NOSTRO account specifically shows `Account_Ccy = 'USD'`
- [ ] No currency mismatches between master and balance tables

### System Validation Success
- [ ] EOD runs successfully
- [ ] NOSTRO account maintains USD currency after EOD
- [ ] Logs show correct currency being used
- [ ] Reports display correct currency

## Files Changed

### Code Files Modified
```
c:\new_cbs3\cbs3\moneymarket\src\main\java\com\example\moneymarket\service\
  └── AccountBalanceUpdateService.java (MODIFIED)
      - Lines 148-189: Currency lookup and update logic fixed
```

### SQL Scripts Created
```
c:\new_cbs3\cbs3\scripts\
  └── fix_eod_currency_corruption.sql (NEW)
      - Diagnoses currency mismatches
      - Fixes historical data corruption
      - Verifies fixes were applied
```

### Documentation Created
```
c:\new_cbs3\cbs3\docs\
  └── EOD_CURRENCY_BUG_FIX.md (NEW - this file)
      - Complete bug analysis
      - Fix documentation
      - Testing procedures
```

## Next Steps

1. **Deploy Code Fix** ✓ (Already applied to `AccountBalanceUpdateService.java`)
2. **Backup Database** (Before running fix script)
3. **Run Fix Script** (`scripts/fix_eod_currency_corruption.sql`)
4. **Verify Historical Data** (Check NOSTRO account shows USD)
5. **Test Next EOD Run** (Ensure currency is preserved)
6. **Monitor Daily** (Check for any new currency mismatches)

## Support and Escalation

If issues persist:
1. Check application logs for currency-related errors
2. Run diagnostic queries from fix script
3. Verify account master tables have correct currency
4. Check if sub-product configuration is correct

---

**Document Version:** 1.0  
**Last Updated:** 2026-02-03  
**Status:** Code fix applied, database fix script ready  
**Severity:** CRITICAL - Affects financial reporting and balances  
**Priority:** HIGH - Requires immediate deployment
