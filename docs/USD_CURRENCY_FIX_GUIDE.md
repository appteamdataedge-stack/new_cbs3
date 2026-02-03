# USD Currency Issue - Complete Fix Guide

## Problem Summary

USD-based accounts (NOSTRO USD, TERM DEPOSIT USD) were experiencing two critical issues:

1. **Currency Mismatch**: Accounts showing BDT instead of USD
2. **Balance Validation**: System preventing positive balances on asset accounts

## Root Causes Identified

### 1. Database Currency Configuration
- **Product Master** (`Prod_Master.Currency`) may have NULL or 'BDT' instead of 'USD' for USD products
- **Account Master** (`Cust_Acct_Master.Account_Ccy`) inherits wrong currency from Product
- **Account Balance** (`Acct_Bal.Account_Ccy`) stores wrong currency

### 2. Business Logic Restrictions (FIXED)
- **TransactionValidationService** was preventing asset accounts from having positive balances
- This restriction has been **REMOVED** in the code fix

## Complete Solution

### Part 1: Code Changes (COMPLETED ✅)

#### File Modified: `TransactionValidationService.java`

**Location:** `c:\new_cbs3\cbs3\moneymarket\src\main\java\com\example\moneymarket\service\TransactionValidationService.java`

**Changes Made:**

1. **Office Asset Accounts (Lines 213-223)**
   - **Before**: Blocked positive balances with error: "Asset Account cannot have positive balance"
   - **After**: Allows both positive and negative balances
   - **Impact**: Nostro accounts can now hold positive USD balances

2. **Customer Asset Accounts (Lines 124-157)**
   - **Before**: Restricted loan accounts to negative balances only
   - **After**: Allows both positive and negative balances
   - **Impact**: Customer asset accounts (savings, deposits) can have positive balances

### Part 2: Database Fixes (ACTION REQUIRED ⚠️)

#### Step 1: Run Diagnostic Script

**File:** `c:\new_cbs3\cbs3\scripts\diagnose_usd_currency_issue.sql`

This script will:
- List all USD products and their current currency settings
- Identify accounts with currency mismatches
- Show balance records with wrong currency
- Provide a summary of issues found

**How to run:**
```sql
-- In your SQL Server Management Studio or Azure Data Studio
-- Connect to your database and execute:
:r c:\new_cbs3\cbs3\scripts\diagnose_usd_currency_issue.sql
```

**Expected Output:**
You should see tables showing:
- Products with "❌ NEEDS FIX" status
- Accounts with currency mismatches
- Balance records with wrong currency

#### Step 2: Backup Your Database

**CRITICAL: Always backup before running UPDATE statements!**

```sql
-- Create a backup
BACKUP DATABASE [YourDatabaseName]
TO DISK = 'C:\Backups\YourDatabase_BeforeUSDFix.bak'
WITH FORMAT, INIT, NAME = 'Before USD Currency Fix';
```

#### Step 3: Run Fix Script

**File:** `c:\new_cbs3\cbs3\scripts\fix_usd_currency_issue.sql`

This script will:
1. Update `Prod_Master.Currency` to 'USD' for USD products
2. Update `Cust_Acct_Master.Account_Ccy` to match Product currency
3. Update `OF_Acct_Master.Account_Ccy` to match Product currency
4. Update `Acct_Bal.Account_Ccy` to match Product currency
5. Specifically fix NOSTRO USD account (922030200101)

**How to run:**
```sql
-- In your SQL Server Management Studio or Azure Data Studio
-- Connect to your database and execute:
:r c:\new_cbs3\cbs3\scripts\fix_usd_currency_issue.sql
```

**Verification:**
After running the fix script, verify:
- All USD products have `Currency = 'USD'`
- All USD accounts have `Account_Ccy = 'USD'`
- All USD balances have `Account_Ccy = 'USD'`
- NOSTRO account 922030200101 shows USD currency

## System Architecture Reference

### Currency Flow in the System

```
Product Master (Prod_Master)
    └── Currency: "USD" (PRIMARY SOURCE)
         |
         ↓
Sub Product Master (Sub_Prod_Master)
    └── Inherits currency from Product
         |
         ↓
Account Master (Cust_Acct_Master / OF_Acct_Master)
    └── Account_Ccy: Set from Product.Currency
         |
         ↓
Account Balance (Acct_Bal)
    └── Account_Ccy: Must match Account Master
```

### Code Components Involved

1. **UnifiedAccountService.getAccountCurrency()**
   - Returns account currency from hierarchy
   - Checks Account → SubProduct → Product
   - Falls back to "BDT" if not found

2. **TransactionValidationService.validateTransaction()**
   - Now allows positive balances for asset accounts
   - Uses correct currency for validation

3. **CustomerAccountService.createCustomerAccount()**
   - Sets `Account_Ccy` from `Product.Currency` during account creation
   - Ensures new accounts have correct currency

4. **TransactionService.createTransaction()**
   - Validates transaction currency matches account currency
   - Uses correct amount (FCY for USD, LCY for BDT)

## Testing the Fix

### Test Case 1: Credit NOSTRO USD Account

**Account:** 922030200101 (Chase NA - NOSTRO USD)
**Transaction:** Credit 500 USD

**Expected Before Fix:**
```
❌ ERROR: "Asset Account 922030200101 (GL: 220302001) cannot have positive balance"
```

**Expected After Fix:**
```
✅ SUCCESS: Transaction created
✅ Account balance: 500.00 USD (positive)
✅ Currency: USD
```

**How to test:**
```json
POST /api/transactions
{
  "valueDate": "2026-02-03",
  "narration": "Test USD credit",
  "lines": [
    {
      "accountNo": "922030200101",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 500.00,
      "exchangeRate": 1.00,
      "lcyAmt": 43000.00,
      "udf1": "Test credit to NOSTRO USD"
    },
    {
      "accountNo": "[DEBIT_ACCOUNT]",
      "drCrFlag": "D",
      "tranCcy": "BDT",
      "fcyAmt": 43000.00,
      "exchangeRate": 1.00,
      "lcyAmt": 43000.00,
      "udf1": "Debit from BDT account"
    }
  ]
}
```

### Test Case 2: Verify Account Balance Shows Positive USD

**Query:**
```sql
SELECT 
    cam.Account_No,
    cam.Acct_Name,
    cam.Account_Ccy,
    ab.Current_Balance,
    ab.Available_Balance,
    ab.Account_Ccy AS Balance_Currency
FROM Cust_Acct_Master cam
INNER JOIN Acct_Bal ab ON cam.Account_No = ab.Account_No
WHERE cam.Account_No = '922030200101'
ORDER BY ab.Tran_Date DESC;
```

**Expected Result:**
```
Account_No      | Acct_Name        | Account_Ccy | Current_Balance | Balance_Currency
----------------|------------------|-------------|-----------------|------------------
922030200101    | Chase NA NOSTRO  | USD         | 500.00          | USD
```

### Test Case 3: Create New USD Account

**Verify new accounts get USD currency:**
```json
POST /api/customer-accounts
{
  "custId": "CUST001",
  "subProductId": 51,
  "acctName": "Test USD Account",
  "dateOpening": "2026-02-03",
  "branchCode": "BR001",
  "accountStatus": "Active"
}
```

**Verification:**
```sql
-- Check the newly created account
SELECT 
    Account_No,
    Acct_Name,
    Account_Ccy,
    Sub_Product_Id
FROM Cust_Acct_Master
WHERE Sub_Product_Id = 51
ORDER BY Account_No DESC;
```

**Expected:** `Account_Ccy = 'USD'`

## Validation Rules After Fix

### Asset Accounts (GL starts with "2")
- ✅ **Can have positive balances** (e.g., Nostro with deposits)
- ✅ **Can have negative balances** (e.g., Loans, overdrafts)
- ✅ **Currency-aware validation** (USD amounts for USD accounts)

### Liability Accounts (GL starts with "1")
- ✅ **Can have positive balances** (normal for deposits)
- ❌ **Cannot have negative balances** (prevents overdraft on non-OD accounts)
- ✅ **Currency-aware validation** (USD amounts for USD accounts)

## Common Issues and Troubleshooting

### Issue 1: Transaction still shows "Currency mismatch" error

**Cause:** Database not updated, Product still has BDT currency

**Solution:** 
1. Run diagnostic script to verify Product.Currency
2. Run fix script to update currency
3. Restart application to clear any caches

### Issue 2: Balance still shows negative for USD account

**Cause:** Historical transactions may have used wrong currency

**Solution:**
```sql
-- Check transaction history
SELECT 
    Tran_Id,
    Account_No,
    Tran_Ccy,
    FCY_Amt,
    LCY_Amt,
    DR_CR_Flag
FROM Tran_Table
WHERE Account_No = '922030200101'
ORDER BY Tran_Date DESC;

-- If needed, reverse incorrect transactions and re-post
```

### Issue 3: New accounts still getting BDT currency

**Cause:** Product Master still has wrong currency

**Solution:**
```sql
-- Verify and fix Product Master
UPDATE Prod_Master
SET Currency = 'USD'
WHERE Product_Id = 36; -- Your USD Product ID

-- Verify
SELECT * FROM Prod_Master WHERE Product_Id = 36;
```

## Files Changed Summary

### Code Files Modified
1. `TransactionValidationService.java` - Balance validation logic updated

### SQL Scripts Created
1. `scripts/diagnose_usd_currency_issue.sql` - Diagnostic queries
2. `scripts/fix_usd_currency_issue.sql` - Database fix script

### Documentation Created
1. `docs/USD_CURRENCY_FIX_GUIDE.md` - This comprehensive guide

## Next Steps

1. ✅ **Code fix is complete** (TransactionValidationService updated)
2. ⚠️ **Run diagnostic script** to assess database state
3. ⚠️ **Backup database** before running fixes
4. ⚠️ **Run fix script** to update currency in database
5. ✅ **Test transactions** on NOSTRO USD account
6. ✅ **Verify balances** show positive USD amounts
7. ✅ **Create new USD accounts** and verify currency

## Support

If you encounter issues:
1. Check application logs for detailed error messages
2. Run diagnostic script to verify database state
3. Verify Product.Currency is set to 'USD' for USD products
4. Ensure application has been restarted after database changes

## Technical References

- **Entity Classes:** `ProdMaster.java`, `SubProdMaster.java`, `CustAcctMaster.java`, `AcctBal.java`
- **Service Classes:** `UnifiedAccountService.java`, `TransactionValidationService.java`, `CustomerAccountService.java`
- **Database Tables:** `Prod_Master`, `Sub_Prod_Master`, `Cust_Acct_Master`, `Acct_Bal`

---

**Document Version:** 1.0  
**Last Updated:** 2026-02-03  
**Status:** Code fixes complete, database fixes pending execution
