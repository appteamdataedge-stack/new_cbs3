# Office Account Creation Currency Bug - Fix Documentation

## Problem Summary

**Critical Error:** Office account creation was failing with NULL constraint violation on `Account_Ccy` column.

### Error Details
```
Status: 500 Internal Server Error
Error Message: "Column 'Account_Ccy' cannot be null"
SQL Error: INSERT into acct_bal fails
API Endpoint: POST /api/accounts/office
```

**SQL Statement Failing:**
```sql
INSERT INTO acct_bal (
    account_ccy,        -- ❌ NULL value causing error
    available_balance, 
    closing_bal, 
    cr_summation, 
    current_balance, 
    dr_summation, 
    last_updated, 
    opening_bal, 
    account_no, 
    tran_date
) 
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```

### Reproduction Steps
1. User navigates to office account creation form
2. Selects Sub Product: "NSUSD" (NOSTRO USD)
3. Fills in: Account Name, Branch Code, Date of Opening, Account Status
4. Clicks "Create Account" button
5. **Error:** "Column 'Account_Ccy' cannot be null"

## Root Cause Analysis

### The Bug Location
**File:** `OfficeAccountService.java`  
**Method:** `createAccount()` (lines 42-100)

### What Went Wrong

#### Issue 1: AcctBal Created Without Currency (Lines 63-72 BEFORE FIX)
```java
// ❌ BUG: account_ccy was NOT set
AcctBal accountBalance = AcctBal.builder()
    .tranDate(systemDateService.getSystemDate())
    .accountNo(savedAccount.getAccountNo())
    // ❌ Missing: .accountCcy(...)
    .currentBalance(BigDecimal.ZERO)
    .availableBalance(BigDecimal.ZERO)
    .lastUpdated(systemDateService.getSystemDateTime())
    .build();
```

**Problem:** The `accountCcy` field was not being set when creating the `AcctBal` record, causing it to be NULL in the INSERT statement.

#### Issue 2: OFAcctMaster Currency Not Set from Product (Line 181 BEFORE FIX)
```java
// ❌ BUG: Using default 'BDT' instead of Product currency
private OFAcctMaster mapToEntity(OfficeAccountRequestDTO dto, SubProdMaster subProduct, 
                                 String accountNo, String glNum) {
    return OFAcctMaster.builder()
        .accountNo(accountNo)
        .subProduct(subProduct)
        .glNum(glNum)
        // ❌ Missing: .accountCcy(...)
        // Entity defaults to 'BDT' instead of getting from Product
        .acctName(dto.getAcctName())
        // ... other fields
        .build();
}
```

**Problem:** The `OFAcctMaster` entity has a default value of `'BDT'` for `accountCcy`, but for NOSTRO USD accounts, it should be `'USD'` from the Product configuration.

### Why This Happened

1. **No Currency Resolution:** The service didn't retrieve currency from Product/SubProduct
2. **Missing Field in AcctBal:** The `accountCcy` field was not set during `AcctBal` creation
3. **Database Constraint:** `Acct_Bal` table has NOT NULL constraint on `account_ccy` column
4. **Result:** INSERT statement sent NULL value → Database rejected with constraint violation

## The Fix

### Code Changes Made

**File:** `c:\new_cbs3\cbs3\moneymarket\src\main\java\com\example\moneymarket\service\OfficeAccountService.java`

#### Change 1: Retrieve Currency from Product (Lines 52-69)
```java
// ✅ FIX: Get currency from Product (via SubProduct relationship)
// This ensures NOSTRO USD (NSUSD) gets currency = 'USD', not default 'BDT'
String accountCurrency = null;
if (subProduct.getProduct() != null) {
    accountCurrency = subProduct.getProduct().getCurrency();
    log.info("Office account currency retrieved from Product: {} for sub-product: {}", 
            accountCurrency, subProduct.getSubProductCode());
}

// Validate currency was found
if (accountCurrency == null || accountCurrency.isEmpty()) {
    log.error("Currency not found for sub-product: {} (ID: {})", 
            subProduct.getSubProductCode(), subProduct.getSubProductId());
    throw new BusinessException(
        String.format("Cannot create office account: Currency not configured for sub-product %s. " +
                    "Please ensure the Product has a valid currency configured.",
                    subProduct.getSubProductCode()));
}
```

**Impact:** 
- Retrieves currency from Product configuration (USD for NOSTRO USD)
- Validates currency exists before proceeding
- Provides clear error message if currency not configured

#### Change 2: Set Currency in AcctBal (Line 87)
```java
// ✅ FIX: Initialize account balance with correct currency from Product
// This fixes the "Column 'Account_Ccy' cannot be null" error
AcctBal accountBalance = AcctBal.builder()
    .tranDate(systemDateService.getSystemDate())
    .accountNo(savedAccount.getAccountNo())
    .accountCcy(accountCurrency) // ✅ FIX: Set currency from Product (USD for NSUSD)
    .currentBalance(BigDecimal.ZERO)
    .availableBalance(BigDecimal.ZERO)
    .lastUpdated(systemDateService.getSystemDateTime())
    .build();
```

**Impact:** 
- `accountCcy` field is now populated with correct currency
- No more NULL constraint violation
- NOSTRO USD accounts get `'USD'` currency

#### Change 3: Set Currency in OFAcctMaster (Lines 202-220)
```java
private OFAcctMaster mapToEntity(OfficeAccountRequestDTO dto, SubProdMaster subProduct, 
                                 String accountNo, String glNum, String accountCurrency) {
    log.info("Creating office account {} with currency: {} from product: {} ({})", 
            accountNo, accountCurrency, 
            subProduct.getProduct().getProductName(), 
            subProduct.getProduct().getProductCode());
    
    return OFAcctMaster.builder()
        .accountNo(accountNo)
        .subProduct(subProduct)
        .glNum(glNum)
        .accountCcy(accountCurrency) // ✅ FIX: Set currency from Product, not default BDT
        .acctName(dto.getAcctName())
        .dateOpening(dto.getDateOpening())
        .dateClosure(dto.getDateClosure())
        .branchCode(dto.getBranchCode())
        .accountStatus(dto.getAccountStatus())
        .reconciliationRequired(dto.getReconciliationRequired())
        .build();
}
```

**Impact:** 
- Office account master record now has correct currency
- Overrides default `'BDT'` value
- Consistent currency across all tables

#### Change 4: Enhanced Logging (Lines 95-96)
```java
log.info("✅ Office Account created successfully - Account: {}, Currency: {}, Sub-Product: {}", 
        savedAccount.getAccountNo(), accountCurrency, subProduct.getSubProductCode());
```

**Impact:** 
- Better visibility into account creation
- Shows which currency was used
- Helps debugging if issues occur

## Testing the Fix

### Test Case 1: Create NOSTRO USD Office Account

**Steps:**
1. Navigate to office account creation form
2. Select Sub Product: "NSUSD" (NOSTRO USD)
3. Fill in:
   - Account Name: "Chase NA NOSTRO USD"
   - Branch Code: "BR001"
   - Date of Opening: "2026-02-03"
   - Account Status: "Active"
   - Reconciliation Required: Yes
4. Click "Create Account"

**Expected Result:**
- ✅ Account created successfully
- ✅ No NULL constraint error
- ✅ Account number generated (e.g., 922030200101)
- ✅ Success message displayed

**Verify in Database:**
```sql
-- Check OF_Acct_Master
SELECT 
    Account_No, 
    Account_Ccy, 
    Acct_Name, 
    GL_Num,
    Sub_Product_Id
FROM OF_Acct_Master
WHERE Account_No = '922030200101';

-- Expected: Account_Ccy = 'USD'

-- Check Acct_Bal
SELECT 
    Account_No,
    Account_Ccy,
    Current_Balance,
    Available_Balance,
    Tran_Date
FROM Acct_Bal
WHERE Account_No = '922030200101';

-- Expected: Account_Ccy = 'USD'
```

### Test Case 2: Verify Currency Inheritance

**Query:**
```sql
-- Verify currency flows from Product → SubProduct → Account
SELECT 
    pm.Product_Id,
    pm.Product_Name,
    pm.Currency AS Product_Currency,
    spm.Sub_Product_Id,
    spm.Sub_Product_Code,
    oam.Account_No,
    oam.Account_Ccy AS Account_Currency,
    ab.Account_Ccy AS Balance_Currency,
    CASE 
        WHEN pm.Currency = oam.Account_Ccy AND oam.Account_Ccy = ab.Account_Ccy 
            THEN '✅ CORRECT'
        ELSE '❌ MISMATCH'
    END AS Status
FROM OF_Acct_Master oam
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
LEFT JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No
WHERE spm.Sub_Product_Code = 'NSUSD';
```

**Expected Result:** All rows show `Status = '✅ CORRECT'`

### Test Case 3: Error Handling - Missing Product Currency

**Scenario:** Product doesn't have currency configured

**Expected Behavior:**
- ❌ Account creation fails
- ✅ Clear error message: "Cannot create office account: Currency not configured for sub-product NSUSD. Please ensure the Product has a valid currency configured."
- ✅ Transaction rolled back
- ✅ No partial data created

**Fix if this occurs:**
```sql
-- Set currency for the Product
UPDATE Prod_Master
SET Currency = 'USD'
WHERE Product_Id = 36;  -- NOSTRO USD Product
```

## Comparison: Before vs After

### BEFORE FIX ❌

**Account Creation Request:**
```json
POST /api/accounts/office
{
  "subProductId": 51,
  "acctName": "Chase NA NOSTRO USD",
  "branchCode": "BR001",
  "dateOpening": "2026-02-03",
  "accountStatus": "Active",
  "reconciliationRequired": true
}
```

**Process:**
```
1. SubProduct retrieved: NSUSD (ID: 51)
2. Account number generated: 922030200101
3. OFAcctMaster created:
   - accountCcy = 'BDT' (default) ❌ WRONG
4. AcctBal created:
   - accountCcy = NULL ❌ CAUSES ERROR
5. Database INSERT fails:
   - ERROR: "Column 'Account_Ccy' cannot be null"
6. Transaction rolled back
7. User sees: "Failed to create office account"
```

**Result:** Account creation failed ❌

### AFTER FIX ✅

**Account Creation Request:**
```json
POST /api/accounts/office
{
  "subProductId": 51,
  "acctName": "Chase NA NOSTRO USD",
  "branchCode": "BR001",
  "dateOpening": "2026-02-03",
  "accountStatus": "Active",
  "reconciliationRequired": true
}
```

**Process:**
```
1. SubProduct retrieved: NSUSD (ID: 51)
2. Product retrieved: NOSTRO USD (ID: 36)
3. Currency extracted: 'USD' ✅
4. Currency validated: Not NULL ✅
5. Account number generated: 922030200101
6. OFAcctMaster created:
   - accountCcy = 'USD' ✅ CORRECT
7. AcctBal created:
   - accountCcy = 'USD' ✅ CORRECT
8. Database INSERT succeeds ✅
9. Transaction committed ✅
10. User sees: "Account created successfully"
```

**Result:** Account created successfully ✅

## Database Schema Reference

### Tables Involved

#### OF_Acct_Master
```sql
CREATE TABLE OF_Acct_Master (
    Account_No VARCHAR(13) PRIMARY KEY,
    Sub_Product_Id INT NOT NULL,
    GL_Num VARCHAR(20) NOT NULL,
    Account_Ccy VARCHAR(3) NOT NULL DEFAULT 'BDT',  -- ✅ Now set from Product
    Acct_Name VARCHAR(100) NOT NULL,
    Date_Opening DATE NOT NULL,
    Date_Closure DATE,
    Branch_Code VARCHAR(10) NOT NULL,
    Account_Status VARCHAR(20) NOT NULL,
    Reconciliation_Required BIT NOT NULL,
    FOREIGN KEY (Sub_Product_Id) REFERENCES Sub_Prod_Master(Sub_Product_Id)
);
```

#### Acct_Bal
```sql
CREATE TABLE Acct_Bal (
    Account_No VARCHAR(13) NOT NULL,
    Tran_Date DATE NOT NULL,
    Account_Ccy VARCHAR(3) NOT NULL,  -- ✅ Now populated from Product
    Opening_Bal DECIMAL(20,2),
    DR_Summation DECIMAL(20,2),
    CR_Summation DECIMAL(20,2),
    Closing_Bal DECIMAL(20,2),
    Current_Balance DECIMAL(20,2) NOT NULL,
    Available_Balance DECIMAL(20,2) NOT NULL,
    Last_Updated DATETIME NOT NULL,
    PRIMARY KEY (Account_No, Tran_Date)
);
```

#### Product Currency Configuration
```sql
-- Verify Product has USD currency
SELECT Product_Id, Product_Name, Currency
FROM Prod_Master
WHERE Product_Id = 36;

-- Expected: Currency = 'USD'
```

## Related Fixes

This fix is related to previous currency fixes:

1. **Transaction Validation** - Removed restrictions on positive balances for asset accounts
2. **USD Currency Configuration** - Fixed Product/Account currency mismatches
3. **EOD Currency Corruption** - Fixed EOD batch process overwriting currency

All these fixes work together to ensure:
- ✅ Office accounts get correct currency from Product
- ✅ Balance records have correct currency
- ✅ Transactions validate using correct currency
- ✅ EOD preserves correct currency

## Prevention Measures

### Code Review Checklist

When creating accounts:
- [ ] Always retrieve currency from Product/SubProduct
- [ ] Validate currency is not NULL before creating records
- [ ] Set `accountCcy` in both account master and balance tables
- [ ] Log currency information for debugging
- [ ] Provide meaningful error messages if currency missing

### Testing Checklist

Before deploying account creation changes:
- [ ] Test with USD sub-products (NSUSD)
- [ ] Test with BDT sub-products
- [ ] Verify currency in both OF_Acct_Master and Acct_Bal
- [ ] Check logs show correct currency being used
- [ ] Verify error handling for missing currency

## Monitoring

### Daily Checks

```sql
-- Check for any NULL currencies in office accounts
SELECT COUNT(*) AS Null_Currency_Count
FROM OF_Acct_Master
WHERE Account_Ccy IS NULL OR Account_Ccy = '';

-- Expected: 0

-- Check for currency mismatches
SELECT 
    oam.Account_No,
    oam.Account_Ccy AS Account_Currency,
    ab.Account_Ccy AS Balance_Currency,
    pm.Currency AS Product_Currency
FROM OF_Acct_Master oam
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
LEFT JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No
WHERE oam.Account_Ccy != pm.Currency
   OR ab.Account_Ccy != pm.Currency;

-- Expected: 0 rows (no mismatches)
```

### Log Monitoring

Look for these log entries after account creation:

```
✅ "Office account currency retrieved from Product: USD for sub-product: NSUSD"
✅ "Creating office account 922030200101 with currency: USD from product: NOSTRO USD (NSUSD)"
✅ "Office Account created successfully - Account: 922030200101, Currency: USD, Sub-Product: NSUSD"
```

**Red Flags (Should NOT appear):**
```
❌ "Currency not found for sub-product"
❌ "Column 'Account_Ccy' cannot be null"
❌ "Creating office account ... with currency: null"
```

## Files Changed

### Modified Files
```
c:\new_cbs3\cbs3\moneymarket\src\main\java\com\example\moneymarket\service\
  └── OfficeAccountService.java (MODIFIED)
      - Lines 52-69: Currency retrieval and validation
      - Line 76: Pass currency to mapToEntity
      - Line 87: Set accountCcy in AcctBal
      - Lines 202-220: Updated mapToEntity method
```

### Documentation Created
```
c:\new_cbs3\cbs3\docs\
  └── OFFICE_ACCOUNT_CURRENCY_FIX.md (NEW - this file)
```

## Success Criteria

- [x] Code fix applied to OfficeAccountService.java
- [x] Currency retrieved from Product configuration
- [x] Currency validated before account creation
- [x] AcctBal.accountCcy populated (fixes NULL error)
- [x] OFAcctMaster.accountCcy set from Product
- [x] Enhanced logging added
- [x] Error handling for missing currency
- [ ] **YOUR ACTION:** Test account creation with NSUSD
- [ ] **YOUR ACTION:** Verify currency in database
- [ ] **YOUR ACTION:** Check logs show correct currency

## Next Steps

1. **Deploy the fix** to your environment
2. **Run the database currency fix scripts** (from previous fixes)
3. **Test account creation** with NOSTRO USD sub-product
4. **Verify** both OF_Acct_Master and Acct_Bal have `Account_Ccy = 'USD'`
5. **Monitor logs** for successful account creation messages

---

**Document Version:** 1.0  
**Last Updated:** 2026-02-03  
**Status:** Code fix applied, ready for testing  
**Severity:** CRITICAL - Blocks office account creation  
**Priority:** HIGH - Required for NOSTRO USD accounts
