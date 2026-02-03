# USD Account Behavior Reference

## Overview

This document explains how USD accounts (NOSTRO USD, TERM DEPOSIT USD, etc.) should behave in the banking system after the currency fix.

## Account Types and Balance Rules

### 1. NOSTRO USD Accounts (Office Asset Accounts)

**Account Characteristics:**
- **Type:** Office Asset Account
- **GL Pattern:** 22xxxx (starts with "2" = Asset)
- **Currency:** USD
- **Sub-Product Example:** NSUSD (Sub_Product_Id: 51, Product_Id: 36)

**Balance Behavior:**
- ✅ **CAN have POSITIVE balances** (e.g., deposits received from correspondent banks)
- ✅ **CAN have NEGATIVE balances** (e.g., payments made to correspondent banks)
- ✅ **CAN be ZERO**

**Example Transactions:**

```json
// Credit (Receive funds) - Creates POSITIVE balance
{
  "accountNo": "922030200101",
  "drCrFlag": "C",
  "tranCcy": "USD",
  "fcyAmt": 10000.00,
  "exchangeRate": 86.00,
  "lcyAmt": 860000.00
}
Result: Balance = +10,000.00 USD ✅

// Debit (Send funds) - Creates NEGATIVE balance or reduces positive
{
  "accountNo": "922030200101",
  "drCrFlag": "D",
  "tranCcy": "USD",
  "fcyAmt": 5000.00,
  "exchangeRate": 86.00,
  "lcyAmt": 430000.00
}
Result: Balance = +5,000.00 USD (if previous was 10,000) ✅
```

### 2. TERM DEPOSIT USD Accounts (Customer Liability Accounts)

**Account Characteristics:**
- **Type:** Customer Liability Account
- **GL Pattern:** 14xxxx (starts with "1" = Liability)
- **Currency:** USD
- **Sub-Product Example:** TDUSD (Term Deposit USD)

**Balance Behavior:**
- ✅ **CAN have POSITIVE balances** (customer deposits)
- ❌ **CANNOT have NEGATIVE balances** (prevents overdraft)
- ✅ **CAN be ZERO**

**Example Transactions:**

```json
// Credit (Customer deposits) - Creates POSITIVE balance
{
  "accountNo": "140101000123",
  "drCrFlag": "C",
  "tranCcy": "USD",
  "fcyAmt": 50000.00,
  "exchangeRate": 86.00,
  "lcyAmt": 4300000.00
}
Result: Balance = +50,000.00 USD ✅

// Debit (Customer withdrawal) - Reduces balance but cannot go negative
{
  "accountNo": "140101000123",
  "drCrFlag": "D",
  "tranCcy": "USD",
  "fcyAmt": 60000.00,  // Exceeds balance
  "exchangeRate": 86.00,
  "lcyAmt": 5160000.00
}
Result: ❌ ERROR - Insufficient balance ✅ (Correct behavior)
```

### 3. Savings USD Accounts (Customer Asset Accounts)

**Account Characteristics:**
- **Type:** Customer Asset Account
- **GL Pattern:** 21xxxx (starts with "2" = Asset)
- **Currency:** USD
- **Sub-Product Example:** SVUSD (Savings USD)

**Balance Behavior:**
- ✅ **CAN have POSITIVE balances** (customer savings)
- ✅ **CAN have NEGATIVE balances** (if loan limit is set)
- ✅ **CAN be ZERO**

**Example Transactions:**

```json
// Credit (Customer deposits) - Creates POSITIVE balance
{
  "accountNo": "210101000123",
  "drCrFlag": "C",
  "tranCcy": "USD",
  "fcyAmt": 1000.00,
  "exchangeRate": 86.00,
  "lcyAmt": 86000.00
}
Result: Balance = +1,000.00 USD ✅

// Debit (Customer withdrawal with overdraft) - Can go negative if loan limit set
{
  "accountNo": "210101000123",
  "drCrFlag": "D",
  "tranCcy": "USD",
  "fcyAmt": 1500.00,  // Exceeds balance but within loan limit
  "exchangeRate": 86.00,
  "lcyAmt": 129000.00
}
Result: Balance = -500.00 USD ✅ (if loan limit >= 500 USD)
```

## Currency Handling

### Amount Fields in Transactions

For USD accounts, the system uses **TWO amount fields**:

1. **FCY_Amt (Foreign Currency Amount)** - Amount in USD
2. **LCY_Amt (Local Currency Amount)** - Amount in BDT (Base currency)

**Relationship:**
```
LCY_Amt = FCY_Amt × Exchange_Rate
```

**Example:**
```
FCY_Amt: 1,000.00 USD
Exchange_Rate: 86.00
LCY_Amt: 86,000.00 BDT
```

### Balance Storage

**Account Balance (`Acct_Bal` table):**
- `Current_Balance` is stored in **account's currency** (USD for USD accounts)
- `Available_Balance` is stored in **account's currency** (USD for USD accounts)
- `Account_Ccy` = "USD"

**GL Balance (`GL_Balance` table):**
- ALL GL balances are stored in **BDT** (base currency)
- This is for consolidated reporting

### Validation Logic

**For USD Accounts:**
```java
// Get account currency
String accountCurrency = unifiedAccountService.getAccountCurrency(accountNo);
// Returns "USD" for USD accounts

// For validation, use FCY amount (USD amount)
if ("USD".equals(accountCurrency)) {
    validationAmount = transaction.getFcyAmt();
}

// For balance updates on USD accounts, use FCY amount
if ("USD".equals(accountCurrency)) {
    balanceUpdateAmount = transaction.getFcyAmt();
}
```

**For BDT Accounts:**
```java
// Get account currency
String accountCurrency = unifiedAccountService.getAccountCurrency(accountNo);
// Returns "BDT" for BDT accounts

// For validation, use LCY amount (BDT amount)
if ("BDT".equals(accountCurrency)) {
    validationAmount = transaction.getLcyAmt();
}

// For balance updates on BDT accounts, use LCY amount
if ("BDT".equals(accountCurrency)) {
    balanceUpdateAmount = transaction.getLcyAmt();
}
```

## Transaction Examples

### Example 1: Receive USD funds in NOSTRO account

**Scenario:** Chase Bank credits our NOSTRO account with $10,000 USD

**Transaction:**
```json
POST /api/transactions
{
  "valueDate": "2026-02-03",
  "narration": "USD funds received from Chase",
  "lines": [
    {
      "accountNo": "922030200101",  // NOSTRO USD (Chase NA)
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 10000.00,
      "exchangeRate": 86.00,
      "lcyAmt": 860000.00,
      "udf1": "USD receipt from correspondent"
    },
    {
      "accountNo": "110101000001",  // BDT Suspense Account
      "drCrFlag": "D",
      "tranCcy": "BDT",
      "fcyAmt": 860000.00,
      "exchangeRate": 1.00,
      "lcyAmt": 860000.00,
      "udf1": "Debit suspense for USD receipt"
    }
  ]
}
```

**Result:**
- NOSTRO USD Account: +10,000.00 USD ✅
- BDT Suspense: -860,000.00 BDT ✅
- No validation error (positive balance allowed) ✅

### Example 2: Customer makes USD Term Deposit

**Scenario:** Customer deposits $50,000 USD for 3 months

**Transaction:**
```json
POST /api/transactions
{
  "valueDate": "2026-02-03",
  "narration": "USD Term Deposit",
  "lines": [
    {
      "accountNo": "140101000123",  // Customer TD USD Account
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 50000.00,
      "exchangeRate": 86.00,
      "lcyAmt": 4300000.00,
      "udf1": "Customer USD deposit"
    },
    {
      "accountNo": "922030200101",  // NOSTRO USD (Chase NA)
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 50000.00,
      "exchangeRate": 86.00,
      "lcyAmt": 4300000.00,
      "udf1": "Debit from NOSTRO"
    }
  ]
}
```

**Result:**
- Customer TD Account: +50,000.00 USD ✅
- NOSTRO USD Account: -40,000.00 USD (if previous was 10,000) ✅
- Both positive and negative balances allowed ✅

### Example 3: Currency Exchange (BDT → USD)

**Scenario:** Exchange 860,000 BDT for 10,000 USD

**Transaction:**
```json
POST /api/transactions
{
  "valueDate": "2026-02-03",
  "narration": "Currency Exchange",
  "lines": [
    {
      "accountNo": "110101000001",  // BDT Account
      "drCrFlag": "D",
      "tranCcy": "BDT",
      "fcyAmt": 860000.00,
      "exchangeRate": 1.00,
      "lcyAmt": 860000.00,
      "udf1": "Debit BDT"
    },
    {
      "accountNo": "922030200101",  // NOSTRO USD
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 10000.00,
      "exchangeRate": 86.00,
      "lcyAmt": 860000.00,
      "udf1": "Credit USD"
    }
  ]
}
```

**Result:**
- BDT Account: Reduced by 860,000.00 BDT ✅
- NOSTRO USD: Increased by 10,000.00 USD ✅
- Multi-currency transaction table (MCT) updated ✅
- Position GL set for currency exchange ✅

## Reporting and Display

### Account Balance Display

**USD Account:**
```
Account: 922030200101 (Chase NA NOSTRO)
Currency: USD
Current Balance: 10,000.00 USD
Available Balance: 10,000.00 USD
```

**NOT:**
```
Account: 922030200101 (Chase NA NOSTRO)
Currency: BDT  ❌ WRONG
Current Balance: 860,000.00 BDT  ❌ WRONG
```

### GL Balance Display

**GL Balance (Consolidated):**
```
GL: 220302001 (NOSTRO USD)
Currency: BDT (All GL balances in base currency)
Closing Balance: 860,000.00 BDT
```

Note: GL balances are ALWAYS in BDT for consolidated reporting.

### Transaction History Display

**USD Account Transaction:**
```
Date: 2026-02-03
Account: 922030200101
Transaction: Credit
Amount: 10,000.00 USD
Exchange Rate: 86.00
LCY Equivalent: 860,000.00 BDT
Balance After: 10,000.00 USD
```

## Common Mistakes to Avoid

### ❌ Mistake 1: Using LCY amount for USD account validation
```java
// WRONG - Uses BDT amount for USD account
BigDecimal amount = transaction.getLcyAmt();
validateTransaction(accountNo, drCrFlag, amount);
```

```java
// CORRECT - Uses USD amount for USD account
String currency = getAccountCurrency(accountNo);
BigDecimal amount = "USD".equals(currency) 
    ? transaction.getFcyAmt() 
    : transaction.getLcyAmt();
validateTransaction(accountNo, drCrFlag, amount);
```

### ❌ Mistake 2: Displaying wrong currency in UI
```javascript
// WRONG - Shows BDT for USD account
<span>Balance: {balance.currentBalance} BDT</span>
```

```javascript
// CORRECT - Shows account currency
<span>Balance: {balance.currentBalance} {account.accountCcy}</span>
```

### ❌ Mistake 3: Hardcoding currency to BDT
```java
// WRONG - Hardcoded BDT
account.setAccountCcy("BDT");
```

```java
// CORRECT - Get from Product
String currency = subProduct.getProduct().getCurrency();
account.setAccountCcy(currency);
```

## Summary

| Account Type | GL Pattern | Currency | Positive Balance | Negative Balance | Validation Amount |
|--------------|------------|----------|------------------|------------------|-------------------|
| NOSTRO USD (Office Asset) | 22xxxx | USD | ✅ Allowed | ✅ Allowed | FCY_Amt (USD) |
| Term Deposit USD (Customer Liability) | 14xxxx | USD | ✅ Allowed | ❌ Not Allowed | FCY_Amt (USD) |
| Savings USD (Customer Asset) | 21xxxx | USD | ✅ Allowed | ✅ Allowed (with limit) | FCY_Amt (USD) |
| Any BDT Account | 1xxxx/2xxxx | BDT | ✅ Allowed | Depends on type | LCY_Amt (BDT) |

---

**Document Version:** 1.0  
**Last Updated:** 2026-02-03  
**Related:** USD_CURRENCY_FIX_GUIDE.md
