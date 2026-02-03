# Office Account Creation Bug - Quick Fix Summary

## 🚨 Critical Bug Fixed

**Problem:** Office account creation failing with "Column 'Account_Ccy' cannot be null"

**Root Cause:** `AcctBal` record was being created without setting the `account_ccy` field, causing NULL constraint violation.

## ✅ The Fix

**File Fixed:** `OfficeAccountService.java`

### What Changed

#### Before (BROKEN ❌)
```java
// Currency NOT retrieved
AcctBal accountBalance = AcctBal.builder()
    .tranDate(systemDateService.getSystemDate())
    .accountNo(savedAccount.getAccountNo())
    // ❌ Missing: .accountCcy(...)
    .currentBalance(BigDecimal.ZERO)
    .build();
// Result: NULL constraint error
```

#### After (FIXED ✅)
```java
// ✅ Currency retrieved from Product
String accountCurrency = subProduct.getProduct().getCurrency(); // 'USD' for NSUSD

// ✅ Validated
if (accountCurrency == null) {
    throw new BusinessException("Currency not configured");
}

// ✅ Set in AcctBal
AcctBal accountBalance = AcctBal.builder()
    .tranDate(systemDateService.getSystemDate())
    .accountNo(savedAccount.getAccountNo())
    .accountCcy(accountCurrency) // ✅ FIX: 'USD' for NOSTRO
    .currentBalance(BigDecimal.ZERO)
    .build();
// Result: Account created successfully
```

## 🎯 Impact

### Fixed Issues
1. ✅ NOSTRO USD office accounts can now be created
2. ✅ No more NULL constraint error
3. ✅ Currency correctly set to 'USD' (not default 'BDT')
4. ✅ Both `OF_Acct_Master` and `Acct_Bal` get correct currency

### What Was Changed
- **Lines 52-69:** Added currency retrieval from Product
- **Line 76:** Pass currency to mapToEntity method
- **Line 87:** Set `accountCcy` in AcctBal (fixes NULL error)
- **Lines 202-220:** Updated mapToEntity to set currency in OFAcctMaster

## 📋 Testing

### Test Steps
1. Navigate to office account creation
2. Select Sub Product: "NSUSD" (NOSTRO USD)
3. Fill in account details
4. Click "Create Account"

### Expected Result
- ✅ Account created successfully
- ✅ No error message
- ✅ Account number generated

### Verification Query
```sql
-- Check account was created with USD currency
SELECT 
    oam.Account_No,
    oam.Account_Ccy AS Account_Currency,
    ab.Account_Ccy AS Balance_Currency,
    pm.Currency AS Product_Currency
FROM OF_Acct_Master oam
INNER JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
INNER JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
LEFT JOIN Acct_Bal ab ON oam.Account_No = ab.Account_No
WHERE oam.Account_No = '922030200101';  -- Your new account

-- Expected: All currencies = 'USD'
```

## 🔗 Related Fixes

This fix is part of a comprehensive currency correction:

| Fix | File | Status |
|-----|------|--------|
| Transaction validation | `TransactionValidationService.java` | ✅ Done |
| USD currency config | Database (SQL scripts) | ⚠️ Run scripts |
| EOD currency corruption | `AccountBalanceUpdateService.java` | ✅ Done |
| Office account creation | `OfficeAccountService.java` | ✅ Done (this fix) |

## ⚠️ Important

**Before creating accounts, ensure Product currency is set:**

```sql
-- Check Product 36 (NOSTRO USD)
SELECT Product_Id, Product_Name, Currency
FROM Prod_Master
WHERE Product_Id = 36;

-- Should show: Currency = 'USD'

-- If NULL or BDT, run fix:
UPDATE Prod_Master
SET Currency = 'USD'
WHERE Product_Id = 36;
```

## 📁 Files

**Modified:**
- `OfficeAccountService.java` (currency fix applied)

**Documentation:**
- `docs/OFFICE_ACCOUNT_CURRENCY_FIX.md` (complete guide)
- `OFFICE_ACCOUNT_FIX_SUMMARY.md` (this file)

## ✅ Action Checklist

- [x] **Code fix applied** - OfficeAccountService.java updated
- [ ] **Verify Product currency** - Run SQL check above
- [ ] **Run currency fix scripts** - Fix Product/Account currencies
- [ ] **Test account creation** - Create NOSTRO USD account
- [ ] **Verify in database** - Check currencies are USD

---

**Status:** Code fixed ✅ | Database needs verification ⚠️  
**Priority:** CRITICAL  
**Time:** ~5 minutes to test
