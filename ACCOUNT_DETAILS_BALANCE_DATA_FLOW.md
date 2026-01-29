# Account Details: Balance (Real-time) & Interest Accrued — Data Flow

Tracing the data flow for **Balance (Real-time)** and **Interest Accrued** on the Account Details page (and Interest Capitalization Details, which uses the same API).

**Example values:** Account `100000001001` → Balance (Real-time): **28,500**, Interest Accrued: **30.2**

---

## 1. API Endpoint Called When Viewing Account Details

| Item | Value |
|------|--------|
| **HTTP method** | `GET` |
| **URL** | `/api/accounts/customer/{accountNo}` |
| **Example** | `GET /api/accounts/customer/100000001001` |

**Frontend:**

- **Account Details:** `pages/accounts/AccountDetails.tsx`
- **Interest Capitalization Details:** `pages/interestCapitalization/InterestCapitalizationDetails.tsx`

Both use:

```ts
getCustomerAccountByAccountNo(accountNo)
```

**API client** (`api/customerAccountService.ts`):

```ts
export const getCustomerAccountByAccountNo = async (accountNo: string): Promise<CustomerAccountResponseDTO> => {
  return apiRequest<CustomerAccountResponseDTO>({
    method: 'GET',
    url: `${ACCOUNTS_ENDPOINT}/${accountNo}`,
  });
};
// ACCOUNTS_ENDPOINT = '/accounts/customer'
// → GET /api/accounts/customer/100000001001
```

**Backend controller:** `CustomerAccountController.getAccount`:

```java
@GetMapping("/{accountNo}")
public ResponseEntity<CustomerAccountResponseDTO> getAccount(@PathVariable String accountNo) {
    CustomerAccountResponseDTO account = customerAccountService.getAccount(accountNo);
    return ResponseEntity.ok(account);
}
```

---

## 2. Service Method That Handles It

**Service:** `CustomerAccountService`  
**Method:** `getAccount(String accountNo)`

**Location:** `CustomerAccountService.java` (lines 159–171)

```java
@Transactional(readOnly = true)
public CustomerAccountResponseDTO getAccount(String accountNo) {
    // 1. Load account master
    CustAcctMaster account = custAcctMasterRepository.findById(accountNo)
            .orElseThrow(() -> new ResourceNotFoundException("Customer Account", "Account Number", accountNo));

    // 2. Load latest balance (used for currentBalance; computed values come from BalanceService)
    AcctBal accountBalance = acctBalRepository.findLatestByAccountNo(accountNo)
            .orElseThrow(() -> new ResourceNotFoundException("Account Balance", "Account Number", accountNo));

    // 3. Map to DTO (includes call to BalanceService for computed + interest)
    return mapToResponse(account, accountBalance);
}
```

**Balance (Real-time)** and **Interest Accrued** are **not** taken from `accountBalance` here. They come from `BalanceService.getComputedAccountBalance` inside `mapToResponse`.

---

## 3. Where Balance (Real-time) and Interest Accrued Are Set

**Method:** `CustomerAccountService.mapToResponse(CustAcctMaster entity, AcctBal balance)`  
**Location:** `CustomerAccountService.java` (lines 277–307)

```java
private CustomerAccountResponseDTO mapToResponse(CustAcctMaster entity, AcctBal balance) {
    // ↓ THIS CALL FEEDS computedBalance AND interestAccrued
    com.example.moneymarket.dto.AccountBalanceDTO balanceDTO =
            balanceService.getComputedAccountBalance(entity.getAccountNo());

    return CustomerAccountResponseDTO.builder()
            // ...
            .currentBalance(balance.getCurrentBalance())           // from Acct_Bal
            .availableBalance(balanceDTO.getAvailableBalance())     // from BalanceService
            .computedBalance(balanceDTO.getComputedBalance())       // ← Balance (Real-time)
            .interestAccrued(balanceDTO.getInterestAccrued())       // ← Interest Accrued
            // ...
            .build();
}
```

So:

- **Balance (Real-time)** = `balanceDTO.getComputedBalance()`
- **Interest Accrued** = `balanceDTO.getInterestAccrued()`

Both are produced by `BalanceService.getComputedAccountBalance(accountNo)`.

---

## 4. Database Tables and Calculations in `BalanceService`

### 4.1 `BalanceService.getComputedAccountBalance(String accountNo)`

**Location:** `BalanceService.java` (lines 149–153, 168–289)

1. **Date used:** `systemDateService.getSystemDate()` (business date from `Parameter_Table`).
2. **Overload:** `getComputedAccountBalance(accountNo, systemDate)` does the real work.

---

### 4.2 **Balance (Real-time) = `computedBalance`**

**Formula:**

```text
computedBalance = previousDayOpeningBalance + dateCredits - dateDebits
```

**Logic (same as in code):**

- **Previous-day opening** = previous day’s **closing** balance from `Acct_Bal`, via 3-tier fallback (see below).
- **dateCredits** = sum of **credit** amounts for the account on `systemDate`.
- **dateDebits** = sum of **debit** amounts for the account on `systemDate`.

**Tables and calls:**

| Step | What | Table / component | Repository method |
|------|------|-------------------|--------------------|
| 1 | Current-day balance row (or latest) | `Acct_Bal` | `AcctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate)` then fallback `findLatestByAccountNo(accountNo)` |
| 2 | Previous-day closing balance | `Acct_Bal` | `AccountBalanceUpdateService.getPreviousDayClosingBalance(accountNo, systemDate)` → uses `findByAccountNoAndTranDateBeforeOrderByTranDateDesc` |
| 3 | Debits for the day | `Tran_Table` | `TranTableRepository.sumDebitTransactionsForAccountOnDate(accountNo, systemDate)` |
| 4 | Credits for the day | `Tran_Table` | `TranTableRepository.sumCreditTransactionsForAccountOnDate(accountNo, systemDate)` |

**Relevant code** (`BalanceService.java` 168–248):

```java
// Current day balance (with fallback)
AcctBal currentDayBalance = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate)
        .orElseGet(() -> acctBalRepository.findLatestByAccountNo(accountNo)
                .orElseThrow(...));

// Previous day closing = opening for “today”
BigDecimal previousDayOpeningBalance = accountBalanceUpdateService.getPreviousDayClosingBalance(accountNo, systemDate);

// BDT: use LCY amounts
dateDebits = tranTableRepository.sumDebitTransactionsForAccountOnDate(accountNo, systemDate).orElse(BigDecimal.ZERO);
dateCredits = tranTableRepository.sumCreditTransactionsForAccountOnDate(accountNo, systemDate).orElse(BigDecimal.ZERO);

// FCY: sum FCY_Amt from Tran_Table for same account/date.

// Formula
BigDecimal computedBalance = previousDayOpeningBalance.add(dateCredits).subtract(dateDebits);
```

So **Balance (Real-time)** is **calculated** from `Acct_Bal` + `Tran_Table`, not a single stored field.

---

### 4.3 **Interest Accrued**

**Source:** `BalanceService.getLatestInterestAccrued(accountNo)`  
**Location:** `BalanceService.java` (lines 299–328)

**Logic:**

- Take the **latest** `Acct_Bal_Accrual` row for the account (by `Tran_date` DESC).
- Use that row’s **`Closing_Bal`**.
- If no row or null `Closing_Bal`, return `0`.

**Table:** `Acct_Bal_Accrual`

**Repository:** `AcctBalAccrualRepository.findLatestByAccountNo(accountNo)`  
**Query:**  
`SELECT * FROM acct_bal_accrual WHERE Account_No = ?1 AND Tran_date IS NOT NULL ORDER BY Tran_date DESC LIMIT 1`

**Code:**

```java
private BigDecimal getLatestInterestAccrued(String accountNo) {
    Optional<AcctBalAccrual> latestAccrualOpt = acctBalAccrualRepository.findLatestByAccountNo(accountNo);
    if (latestAccrualOpt.isEmpty()) return BigDecimal.ZERO;
    BigDecimal closingBal = latestAccrualOpt.get().getClosingBal();
    return closingBal != null ? closingBal : BigDecimal.ZERO;
}
```

So **Interest Accrued** is **directly from the DB**: `Acct_Bal_Accrual.Closing_Bal` of the latest record (by `Tran_date`). No extra calculation.

---

### 4.4 **3-tier fallback for previous-day closing** (used in computed balance)

**Method:** `AccountBalanceUpdateService.getPreviousDayClosingBalance(accountNo, systemDate)`  
**Table:** `Acct_Bal` via `findByAccountNoAndTranDateBeforeOrderByTranDateDesc(accountNo, systemDate)`.

| Tier | Rule | Result |
|------|------|--------|
| 1 | Row for **previous day** (`systemDate - 1`) exists | Use that row’s `Closing_Bal` |
| 2 | No previous day row | Use **last** `Acct_Bal` row before `systemDate` (latest `Tran_Date`) `Closing_Bal` |
| 3 | No rows before `systemDate` | Use `0` (new account) |

---

## 5. End-to-End Data Flow (Summary)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│ FRONTEND                                                                         │
│  AccountDetails.tsx / InterestCapitalizationDetails.tsx                          │
│  useQuery → getCustomerAccountByAccountNo(accountNo)                             │
│  GET /api/accounts/customer/100000001001                                         │
└───────────────────────────────────────────┬─────────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ CONTROLLER                                                                       │
│  CustomerAccountController.getAccount(accountNo)                                 │
│  → customerAccountService.getAccount(accountNo)                                  │
└───────────────────────────────────────────┬─────────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ SERVICE: CustomerAccountService.getAccount                                       │
│  1. CustAcctMaster ← CustAcctMasterRepository.findById(accountNo)   [Cust_Acct_Master] │
│  2. AcctBal       ← AcctBalRepository.findLatestByAccountNo(accountNo) [Acct_Bal]     │
│  3. mapToResponse(account, balance)                                              │
│       → balanceService.getComputedAccountBalance(accountNo)                       │
└───────────────────────────────────────────┬─────────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ BalanceService.getComputedAccountBalance(accountNo)                              │
│  systemDate = systemDateService.getSystemDate()  [Parameter_Table.System_Date]   │
│                                                                                  │
│  Acct_Bal:                                                                       │
│    • findByAccountNoAndTranDate(accountNo, systemDate) or findLatestByAccountNo  │
│    • getPreviousDayClosingBalance(accountNo, systemDate)                         │
│      → findByAccountNoAndTranDateBeforeOrderByTranDateDesc                       │
│                                                                                  │
│  Tran_Table:                                                                     │
│    • sumDebitTransactionsForAccountOnDate(accountNo, systemDate)                 │
│    • sumCreditTransactionsForAccountOnDate(accountNo, systemDate)                │
│                                                                                  │
│  computedBalance = previousDayOpeningBalance + dateCredits - dateDebits          │
│                                                                                  │
│  getLatestInterestAccrued(accountNo):                                            │
│    Acct_Bal_Accrual: findLatestByAccountNo(accountNo)                            │
│    → use Closing_Bal of latest row (ORDER BY Tran_date DESC LIMIT 1)             │
│                                                                                  │
│  return AccountBalanceDTO(computedBalance, interestAccrued, ...)                 │
└───────────────────────────────────────────┬─────────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ mapToResponse builds CustomerAccountResponseDTO                                  │
│  .computedBalance(balanceDTO.getComputedBalance())   → Balance (Real-time)       │
│  .interestAccrued(balanceDTO.getInterestAccrued())   → Interest Accrued          │
└───────────────────────────────────────────┬─────────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ FRONTEND                                                                         │
│  Balance (Real-time): account.computedBalance ?? account.currentBalance ?? 0     │
│  Interest Accrued:    account.interestAccrued ?? 0                               │
│  (AccountDetails.tsx lines 139–140, 152–154)                                     │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Code References (Quick Index)

| What | File | Location |
|------|------|----------|
| API call | `frontend/src/api/customerAccountService.ts` | `getCustomerAccountByAccountNo` |
| Account Details UI | `frontend/src/pages/accounts/AccountDetails.tsx` | Balance: 139–140, Interest: 152–154 |
| Controller | `CustomerAccountController.java` | `getAccount` |
| Service entry | `CustomerAccountService.java` | `getAccount`, `mapToResponse` |
| Computed balance & interest | `BalanceService.java` | `getComputedAccountBalance`, `getLatestInterestAccrued` |
| Previous-day closing | `AccountBalanceUpdateService.java` | `getPreviousDayClosingBalance` |
| Acct_Bal | `AcctBalRepository.java` | `findByAccountNoAndTranDate`, `findLatestByAccountNo`, `findByAccountNoAndTranDateBeforeOrderByTranDateDesc` |
| Tran_Table sums | `TranTableRepository.java` | `sumDebitTransactionsForAccountOnDate`, `sumCreditTransactionsForAccountOnDate` |
| Acct_Bal_Accrual | `AcctBalAccrualRepository.java` | `findLatestByAccountNo` |

---

## 7. Direct vs Calculated

| Field | Source | Calculation? |
|-------|--------|--------------|
| **Balance (Real-time)** | `Acct_Bal` (previous day closing) + `Tran_Table` (same-day debits/credits) | **Yes.** `previousDayOpeningBalance + dateCredits - dateDebits` |
| **Interest Accrued** | `Acct_Bal_Accrual.Closing_Bal` (latest by `Tran_date`) | **No.** Direct column value. |

---

## 8. Note on Interest Capitalization vs Account Details

- **Interest Capitalization** “Accrued Balance” uses `Acct_Bal_Accrual.Interest_Amount` (via `InterestCapitalizationService.getAccruedBalance`).
- **Account Details** “Interest Accrued” uses `Acct_Bal_Accrual.Closing_Bal` (via `BalanceService.getLatestInterestAccrued`).

So the **same** `Acct_Bal_Accrual` table is used, but **different columns** for these two UIs. Keep that in mind if you align or change business rules between them.

---

*Document generated from codebase tracing. Tables: `Cust_Acct_Master`, `Acct_Bal`, `Tran_Table`, `Acct_Bal_Accrual`, `Parameter_Table`.*
