# Multi-Currency Balance Calculation Logic

## ✅ Implementation Complete

### Key Principle:
**Account balances are calculated using the transaction's FCY amount when the account is not BDT**

---

## 📊 Balance Calculation Rules

### Rule 1: BDT Accounts
```
Account Currency: BDT
Transaction Currency: BDT
Amount Used: LCY_Amt (same as FCY_Amt for BDT)
```

**Example:**
```
Transaction: BDT 50,000 deposit
Account Balance Before: 100,000 BDT
Amount Used: 50,000 BDT (LCY_Amt)
Account Balance After: 150,000 BDT ✅
```

---

### Rule 2: USD Accounts
```
Account Currency: USD
Transaction Currency: USD
Amount Used: FCY_Amt (in USD)
```

**Example:**
```
Transaction: USD 1,000 @ 110.00 = 110,000 BDT
Account Balance Before: 5,000 USD
Amount Used: 1,000 USD (FCY_Amt) ← NOT 110,000!
Account Balance After: 6,000 USD ✅
```

---

### Rule 3: EUR Accounts (or any other FCY)
```
Account Currency: EUR
Transaction Currency: EUR
Amount Used: FCY_Amt (in EUR)
```

**Example:**
```
Transaction: EUR 500 @ 120.00 = 60,000 BDT
Account Balance Before: 2,000 EUR
Amount Used: 500 EUR (FCY_Amt)
Account Balance After: 2,500 EUR ✅
```

---

## 🔄 Complete Transaction Flow

### Scenario: Customer deposits USD 1,000 @ 110.00

#### Entry 1: Nostro USD
```java
TranTable entry1 = {
    accountNo: "922030200101",     // Nostro USD account
    drCrFlag: D,
    tranCcy: "USD",
    fcyAmt: 1000.00,               // USD amount
    exchangeRate: 110.0000,
    lcyAmt: 110000.00              // BDT equivalent
}
```

#### Entry 2: Customer USD
```java
TranTable entry2 = {
    accountNo: "100000008001",     // Customer USD account
    drCrFlag: C,
    tranCcy: "USD",
    fcyAmt: 1000.00,               // USD amount
    exchangeRate: 110.0000,
    lcyAmt: 110000.00              // BDT equivalent
}
```

---

## 💾 Balance Updates

### Account Balance Update (Acct_Bal)

```java
// For Entry 1 (Nostro USD - Debit)
String accountCurrency = getAccountCurrency("922030200101"); // Returns "USD"
BigDecimal accountBalanceAmount;

if ("BDT".equals(entry1.getTranCcy())) {
    accountBalanceAmount = entry1.getLcyAmt();  // Use LCY for BDT
} else {
    accountBalanceAmount = entry1.getFcyAmt();  // Use FCY for USD/EUR/etc ✅
}

// accountBalanceAmount = 1000.00 USD
updateAccountBalance("922030200101", D, 1000.00);
```

**Result in Acct_Bal:**
```sql
Account_No: 922030200101
Account_Ccy: USD
Current_Balance: 1000.00  ← In USD! ✅
```

---

### GL Balance Update (GL_Balance)

```java
// For Entry 1 (GL 922030200)
String glNum = getGlNum("922030200101"); // Returns "922030200"

// GL Balance is ALWAYS in BDT
BigDecimal glBalanceAmount = entry1.getLcyAmt(); // Always LCY for GL ✅

// glBalanceAmount = 110000.00 BDT
updateGLBalance("922030200", D, 110000.00);
```

**Result in GL_Balance:**
```sql
GL_Num: 922030200
Current_Balance: 110000.00  ← In BDT! ✅
```

---

## 📝 Code Implementation

### 1. TransactionService.postCurrentTransaction()

```java
for (TranTable transaction : transactions) {
    String glNum = unifiedAccountService.getGlNum(transaction.getAccountNo());

    // Determine amount for account balance
    BigDecimal accountBalanceAmount;
    if ("BDT".equals(transaction.getTranCcy())) {
        accountBalanceAmount = transaction.getLcyAmt();  // BDT: Use LCY
    } else {
        accountBalanceAmount = transaction.getFcyAmt();  // FCY: Use FCY ✅
    }

    // Update account balance (in account's currency)
    validationService.updateAccountBalanceForTransaction(
        transaction.getAccountNo(),
        transaction.getDrCrFlag(),
        accountBalanceAmount  // USD amount for USD accounts
    );

    // Update GL balance (ALWAYS in BDT)
    balanceService.updateGLBalance(
        glNum,
        transaction.getDrCrFlag(),
        transaction.getLcyAmt()  // Always BDT for GL ✅
    );
}
```

---

### 2. BalanceService.updateAccountBalance()

```java
@Transactional(isolation = Isolation.REPEATABLE_READ)
public BigDecimal updateAccountBalance(String accountNo, DrCrFlag drCrFlag, BigDecimal amount) {
    AcctBal balance = acctBalRepository.findLatestByAccountNo(accountNo)
        .orElseThrow(...);

    BigDecimal oldBalance = balance.getCurrentBalance();
    BigDecimal newBalance;

    // Amount is already in account's currency
    // - For BDT account: amount is in BDT
    // - For USD account: amount is in USD ✅
    // - For EUR account: amount is in EUR ✅
    if (drCrFlag == DrCrFlag.D) {
        newBalance = oldBalance.add(amount);
    } else {
        newBalance = oldBalance.subtract(amount);
    }

    balance.setCurrentBalance(newBalance);
    acctBalRepository.save(balance);

    String accountCcy = balance.getAccountCcy();
    log.info("Account balance updated for {} account {}: {} {} {} = {} {}",
        accountCcy, accountNo, oldBalance, drCrFlag, amount, newBalance, accountCcy);

    return newBalance;
}
```

---

### 3. BalanceService.updateGLBalance()

```java
@Transactional(isolation = Isolation.REPEATABLE_READ)
public BigDecimal updateGLBalance(String glNum, DrCrFlag drCrFlag, BigDecimal amount) {
    GLBalance balance = glBalanceRepository.findByGlNumWithLock(glNum)
        .orElseThrow(...);

    BigDecimal oldBalance = balance.getCurrentBalance();
    BigDecimal newBalance;

    // Amount is ALWAYS in BDT (LCY) ✅
    if (drCrFlag == DrCrFlag.D) {
        newBalance = oldBalance.add(amount);
    } else {
        newBalance = oldBalance.subtract(amount);
    }

    balance.setCurrentBalance(newBalance);
    glBalanceRepository.save(balance);

    return newBalance;
}
```

---

## 🧪 Test Scenarios

### Test 1: USD Deposit

**Setup:**
- Account: 100000008001 (USD account)
- Initial Balance: 0 USD

**Transaction:**
```json
{
  "lines": [
    {
      "accountNo": "922030200101",
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 1000.00,
      "exchangeRate": 110.0000,
      "lcyAmt": 110000.00
    },
    {
      "accountNo": "100000008001",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 1000.00,
      "exchangeRate": 110.0000,
      "lcyAmt": 110000.00
    }
  ]
}
```

**Expected Results:**

| Table | Account/GL | Currency | Balance Before | Dr/Cr | Amount | Balance After |
|-------|-----------|----------|----------------|-------|--------|---------------|
| **Acct_Bal** | 922030200101 | USD | 0.00 | D | **1000.00 USD** | **1000.00 USD** ✅ |
| **Acct_Bal** | 100000008001 | USD | 0.00 | C | **1000.00 USD** | **1000.00 USD** ✅ |
| **GL_Balance** | 922030200 | BDT | 0.00 | D | **110000.00 BDT** | **110000.00 BDT** ✅ |
| **GL_Balance** | 110203001 | BDT | 0.00 | C | **110000.00 BDT** | **110000.00 BDT** ✅ |

---

### Test 2: USD Withdrawal

**Setup:**
- Account: 100000008001 (USD account)
- Initial Balance: 1000 USD

**Transaction:**
```json
{
  "lines": [
    {
      "accountNo": "100000008001",
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 500.00,
      "exchangeRate": 112.0000,
      "lcyAmt": 56000.00
    },
    {
      "accountNo": "922030200101",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 500.00,
      "exchangeRate": 112.0000,
      "lcyAmt": 56000.00
    }
  ]
}
```

**Expected Results:**

| Table | Account/GL | Currency | Balance Before | Dr/Cr | Amount | Balance After |
|-------|-----------|----------|----------------|-------|--------|---------------|
| **Acct_Bal** | 100000008001 | USD | 1000.00 | D | **500.00 USD** | **500.00 USD** ✅ |
| **Acct_Bal** | 922030200101 | USD | 1000.00 | C | **500.00 USD** | **500.00 USD** ✅ |
| **GL_Balance** | 110203001 | BDT | 110000.00 | D | **56000.00 BDT** | **166000.00 BDT** ✅ |
| **GL_Balance** | 922030200 | BDT | 110000.00 | C | **56000.00 BDT** | **54000.00 BDT** ✅ |

---

## 🔍 Verification Queries

### Check Account Balance (in account's currency)
```sql
SELECT
    Account_No,
    Account_Ccy,
    Current_Balance,
    CONCAT(Current_Balance, ' ', Account_Ccy) AS Display_Balance
FROM Acct_Bal
WHERE Account_No IN ('100000008001', '922030200101')
ORDER BY Account_No;
```

**Expected Output:**
```
Account_No    | Account_Ccy | Current_Balance | Display_Balance
--------------|-------------|-----------------|------------------
100000008001  | USD         | 1000.00         | 1000.00 USD ✅
922030200101  | USD         | 1000.00         | 1000.00 USD ✅
```

---

### Check GL Balance (always in BDT)
```sql
SELECT
    GL_Num,
    Current_Balance,
    CONCAT(Current_Balance, ' BDT') AS Display_Balance
FROM GL_Balance
WHERE GL_Num IN ('110203001', '922030200', '920101001')
ORDER BY GL_Num;
```

**Expected Output:**
```
GL_Num     | Current_Balance | Display_Balance
-----------|-----------------|------------------
110203001  | 110000.00       | 110000.00 BDT ✅
922030200  | 110000.00       | 110000.00 BDT ✅
920101001  | 0.00            | 0.00 BDT ✅
```

---

## ✅ Summary

| Aspect | BDT Account | USD/EUR Account |
|--------|-------------|-----------------|
| **Account Balance Uses** | LCY_Amt | FCY_Amt ✅ |
| **Account Balance Currency** | BDT | USD/EUR ✅ |
| **GL Balance Uses** | LCY_Amt | LCY_Amt ✅ |
| **GL Balance Currency** | BDT | BDT ✅ |
| **Customer Sees** | Balance in BDT | Balance in USD/EUR ✅ |
| **Accounting Sees** | GL Balance in BDT | GL Balance in BDT ✅ |

---

## 📋 Changes Made

1. ✅ **TransactionService.postCurrentTransaction()**
   - Added logic to use FCY_Amt for FCY accounts
   - Uses LCY_Amt for BDT accounts
   - GL balance always uses LCY_Amt

2. ✅ **TransactionService.reverseTransaction()**
   - Same logic for reversals

3. ✅ **BalanceService.updateAccountBalance()**
   - Updated documentation
   - Added currency logging

4. ✅ **AccountBalanceUpdateService**
   - Changed `.tranCcy()` to `.accountCcy()`

5. ✅ **Entity Updates**
   - AcctBal: Renamed Tran_Ccy to Account_Ccy
   - OFAcctMaster: Added Account_Ccy field

6. ✅ **Compilation**
   - BUILD SUCCESS ✅

---

## 🚀 Ready to Test!

The multi-currency balance calculation is now correctly implemented:
- USD accounts show balance in USD
- GL balances show balance in BDT
- No frontend changes needed

**Migration:** V23__add_multi_currency_balance_support.sql
**Status:** ✅ COMPLETE
