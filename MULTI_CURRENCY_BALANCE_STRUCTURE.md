# Multi-Currency Balance Structure

## ✅ Database Schema Changes

### Key Principle:
- **Account Balances (Acct_Bal)**: Stored in **account's currency** (USD for USD accounts, BDT for BDT accounts)
- **GL Balances (GL_Balance)**: ALWAYS stored in **BDT (LCY)** - the base currency for consolidation

---

## 📊 Table Structures

### 1. **Acct_Bal** (Account Balance)
Stores balances in the **account's currency**.

```sql
CREATE TABLE Acct_Bal (
    Tran_Date DATE NOT NULL,
    Account_No VARCHAR(13) NOT NULL,
    Account_Ccy VARCHAR(3) NOT NULL DEFAULT 'BDT',  -- ✅ NEW: Account's currency
    Tran_Ccy VARCHAR(3) NOT NULL DEFAULT 'BDT',
    Opening_Bal DECIMAL(20,2),
    DR_Summation DECIMAL(20,2),
    CR_Summation DECIMAL(20,2),
    Closing_Bal DECIMAL(20,2),
    Current_Balance DECIMAL(20,2) NOT NULL,
    Available_Balance DECIMAL(20,2) NOT NULL,
    Last_Updated DATETIME NOT NULL,
    PRIMARY KEY (Tran_Date, Account_No),
    FOREIGN KEY (Account_Ccy) REFERENCES currency_master(Ccy_Code)
);
```

**Example Records:**
```
Account_No      | Account_Ccy | Current_Balance | (Balance in account's currency)
----------------|-------------|-----------------|--------------------------------
100000008001    | USD         | 1000.00         | 1000.00 USD
922030200101    | USD         | 5000.00         | 5000.00 USD
110101001001    | BDT         | 50000.00        | 50000.00 BDT
```

---

### 2. **GL_Balance** (GL Balance)
ALWAYS stores balances in **BDT (LCY)**, regardless of the GL's currency.

```sql
CREATE TABLE GL_Balance (
    Id BIGINT AUTO_INCREMENT PRIMARY KEY,
    GL_Num VARCHAR(9) NOT NULL,
    Tran_date DATE NOT NULL,
    -- NO GL_Ccy column - always BDT!
    Opening_Bal DECIMAL(20,2),
    DR_Summation DECIMAL(20,2),
    CR_Summation DECIMAL(20,2),
    Closing_Bal DECIMAL(20,2),
    Current_Balance DECIMAL(20,2) NOT NULL,  -- ALWAYS in BDT
    Last_Updated DATETIME NOT NULL,
    FOREIGN KEY (GL_Num) REFERENCES GL_setup(GL_Num)
);
```

**Example Records:**
```
GL_Num      | GL_Name               | Current_Balance | (ALWAYS in BDT)
------------|-----------------------|-----------------|------------------
110203001   | TD FCY USD            | 110000.00       | 110,000 BDT (from 1000 USD @ 110)
220302001   | NOSTRO USD            | 550000.00       | 550,000 BDT (from 5000 USD @ 110)
920101001   | Position USD          | 0.00            | 0 BDT (balanced)
140203001   | Realised Forex Gain   | 5000.00         | 5,000 BDT
```

---

### 3. **Cust_Acct_Master** (Customer Account Master)
```sql
ALTER TABLE Cust_Acct_Master
ADD COLUMN Account_Ccy VARCHAR(3) DEFAULT 'BDT' AFTER GL_Num;

-- Example:
Account_No      | GL_Num     | Account_Ccy | Acct_Name
----------------|------------|-------------|------------------
100000008001    | 110203001  | USD         | Yasir Abrar USD TD
```

---

### 4. **OF_Acct_Master** (Office Account Master)
```sql
ALTER TABLE OF_Acct_Master
ADD COLUMN Account_Ccy VARCHAR(3) DEFAULT 'BDT' AFTER GL_Num;

-- Example:
Account_No      | GL_Num     | Account_Ccy | Acct_Name
----------------|------------|-------------|------------------
922030200101    | 922030200  | USD         | Cash USD
```

---

## 🔄 Balance Update Logic

### When USD Transaction is Posted

**Scenario:** Customer deposits USD 1,000 @ 110.00

#### Entry 1 & 2: Customer/Nostro Entries
```
Entry 1: Nostro USD Dr 1,000 USD @ 110.00 = 110,000 BDT
Entry 2: Customer USD Cr 1,000 USD @ 110.00 = 110,000 BDT
```

#### Account Balance Updates (in USD):
```sql
-- Update Nostro Account Balance (922030200101)
-- Account Currency = USD, so update in USD
UPDATE Acct_Bal
SET Current_Balance = Current_Balance + 1000.00  -- +1000 USD
WHERE Account_No = '922030200101';

-- Update Customer Account Balance (100000008001)
-- Account Currency = USD, so update in USD
UPDATE Acct_Bal
SET Current_Balance = Current_Balance + 1000.00  -- +1000 USD
WHERE Account_No = '100000008001';
```

#### GL Balance Updates (ALWAYS in BDT):
```sql
-- Update Nostro GL Balance (220302001)
-- GL Balance is ALWAYS in BDT
UPDATE GL_Balance
SET Current_Balance = Current_Balance + 110000.00  -- +110,000 BDT
WHERE GL_Num = '220302001';

-- Update Customer USD GL Balance (110203001)
-- GL Balance is ALWAYS in BDT
UPDATE GL_Balance
SET Current_Balance = Current_Balance + 110000.00  -- +110,000 BDT
WHERE GL_Num = '110203001';
```

---

## 💡 Key Points

### ✅ Account Balance (Acct_Bal):
1. **Stored in account's currency**
2. USD account → Balance in USD
3. EUR account → Balance in EUR
4. BDT account → Balance in BDT
5. **This is what customers see** - their balance in their currency

### ✅ GL Balance (GL_Balance):
1. **ALWAYS stored in BDT (LCY)**
2. Even USD GLs → Balance in BDT
3. Even EUR GLs → Balance in BDT
4. **This is for accounting consolidation** - everything in base currency
5. For reporting: Convert GL balance using current exchange rate

---

## 🧮 Balance Calculation Examples

### Example 1: USD Account After Deposit

**Transaction:** Deposit USD 1,000 @ 110.00

**Account Balance (Acct_Bal):**
```
Account_No: 100000008001
Account_Ccy: USD
Current_Balance: 1,000.00 USD  ← Customer sees this
```

**GL Balance (GL_Balance):**
```
GL_Num: 110203001 (TD FCY USD)
Current_Balance: 110,000.00 BDT  ← Accounting sees this
```

**To display in reports:**
- Customer statement: Show 1,000.00 USD (from Acct_Bal)
- GL report in BDT: Show 110,000.00 BDT (from GL_Balance)
- GL report in USD: Show 110,000.00 / current_rate USD (convert from GL_Balance)

---

### Example 2: After Multiple USD Transactions

**Initial:** 0 USD
**Deposit 1:** +1,000 USD @ 110.00
**Deposit 2:** +500 USD @ 112.00
**Withdrawal:** -300 USD @ 111.00

**Account Balance:**
```
Account_No: 100000008001
Account_Ccy: USD
Current_Balance: 1,200.00 USD  (1000 + 500 - 300)
```

**GL Balance:**
```
GL_Num: 110203001
Current_Balance: 132,700.00 BDT
  (110,000 + 56,000 - 33,300)
```

---

## 🔧 Code Changes Required

### 1. **BalanceService.updateAccountBalance()**
```java
public BigDecimal updateAccountBalance(String accountNo, DrCrFlag drCrFlag, BigDecimal amount) {
    // Get account balance
    AcctBal balance = acctBalRepository.findLatestByAccountNo(accountNo)
        .orElseThrow(...);

    // Update balance in ACCOUNT'S CURRENCY
    // If USD account and amount is 1000 USD, update by 1000 (not 110,000 BDT)
    BigDecimal newBalance;
    if (drCrFlag == DrCrFlag.D) {
        newBalance = oldBalance.add(amount);  // amount is in account's currency
    } else {
        newBalance = oldBalance.subtract(amount);
    }

    balance.setCurrentBalance(newBalance);
    acctBalRepository.save(balance);

    return newBalance;
}
```

### 2. **BalanceService.updateGLBalance()**
```java
public BigDecimal updateGLBalance(String glNum, DrCrFlag drCrFlag, BigDecimal amount) {
    // Get GL balance
    GLBalance balance = glBalanceRepository.findByGlNumWithLock(glNum)
        .orElseThrow(...);

    // Update balance in BDT (LCY)
    // amount should ALWAYS be in BDT when calling this method
    BigDecimal newBalance;
    if (drCrFlag == DrCrFlag.D) {
        newBalance = oldBalance.add(amount);  // amount is in BDT
    } else {
        newBalance = oldBalance.subtract(amount);
    }

    balance.setCurrentBalance(newBalance);
    glBalanceRepository.save(balance);

    return newBalance;
}
```

### 3. **TransactionService - When Posting Transaction**
```java
// For each transaction line
for (TranTable transaction : transactions) {
    String glNum = getGlNum(transaction.getAccountNo());

    // Update account balance in FCY (account's currency)
    balanceService.updateAccountBalance(
        transaction.getAccountNo(),
        transaction.getDrCrFlag(),
        transaction.getFcyAmt()  // ← Use FCY amount for account balance
    );

    // Update GL balance in LCY (always BDT)
    balanceService.updateGLBalance(
        glNum,
        transaction.getDrCrFlag(),
        transaction.getLcyAmt()  // ← Use LCY amount for GL balance
    );
}
```

---

## 📋 Migration Checklist

- [x] Add `Account_Ccy` column to `Acct_Bal`
- [x] Add `Account_Ccy` column to `Cust_Acct_Master`
- [x] Add `Account_Ccy` column to `OF_Acct_Master`
- [x] Update account `100000008001` to `Account_Ccy = 'USD'`
- [x] Update account `922030200101` to `Account_Ccy = 'USD'`
- [x] Create `Acct_Bal` record for office account `922030200101`
- [x] Ensure GL balances exist for all USD GLs
- [ ] Update `BalanceService.updateAccountBalance()` to use FCY amount
- [ ] Update transaction posting to use correct amounts (FCY for account, LCY for GL)
- [ ] Test USD deposit transaction
- [ ] Test USD withdrawal transaction

---

## 🧪 Testing

### Test 1: Verify Account Balance is in USD
```sql
-- After depositing USD 1,000 @ 110.00
SELECT Account_No, Account_Ccy, Current_Balance
FROM Acct_Bal
WHERE Account_No = '100000008001';

-- Expected:
-- Account_No: 100000008001
-- Account_Ccy: USD
-- Current_Balance: 1000.00  ← In USD, not 110000!
```

### Test 2: Verify GL Balance is in BDT
```sql
-- After same deposit
SELECT GL_Num, Current_Balance
FROM GL_Balance
WHERE GL_Num = '110203001';

-- Expected:
-- GL_Num: 110203001
-- Current_Balance: 110000.00  ← In BDT
```

---

## ✅ Summary

| Table | Balance Currency | Example |
|-------|-----------------|---------|
| **Acct_Bal** | Account's currency | USD account = balance in USD |
| **GL_Balance** | ALWAYS BDT | Even USD GL = balance in BDT |
| **Reporting** | Can convert as needed | GL_Balance / exchange_rate for FCY display |

This structure allows:
- ✅ Customers to see balances in their currency
- ✅ Accounting to consolidate everything in BDT
- ✅ Reports to show balances in any currency by conversion
- ✅ Proper multi-currency support

**Migration file created:** `V23__add_multi_currency_balance_support.sql`
**Status:** ✅ Ready to apply
