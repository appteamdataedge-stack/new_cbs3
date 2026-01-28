# Balance Logic Implementation Report

## 1. File Change List

### Backend Files Modified:

1. **`new_cbs3/moneymarket/src/main/java/com/example/moneymarket/dto/AccountBalanceDTO.java`**
   - Added `previousDayOpeningBalance` field to store static previous day closing balance
   - Updated field comments for clarity

2. **`new_cbs3/moneymarket/src/main/java/com/example/moneymarket/service/BalanceService.java`**
   - Updated `getComputedAccountBalance()` method to include `previousDayOpeningBalance` in the DTO response
   - The field is populated using `accountBalanceUpdateService.getPreviousDayClosingBalance()`

### Frontend Files Modified:

3. **`new_cbs3/frontend/src/types/transaction.ts`**
   - Added `previousDayOpeningBalance?: number` field to `AccountBalanceDTO` interface
   - Updated comments to clarify field purposes

4. **`new_cbs3/frontend/src/pages/transactions/TransactionForm.tsx`**
   - Added `getPreviousDayOpeningBalance()` helper function (lines 167-183)
   - Updated "Available Balance" field display to use `availableBalance` instead of `computedBalance` (line 596)
   - Updated "Previous Day Opening Balance" in summary section to use `getPreviousDayOpeningBalance()` (line 664)
   - Updated helper text to show appropriate message for Asset accounts (lines 614-616)

---

## 2. Data Source Audit

### Available Balance Data Source:

**Backend Calculation (BalanceService.java):**
- The `availableBalance` is **calculated in the backend** using the following logic:
  - **For Asset accounts (GL starting with "2")**: 
    ```java
    availableBalance = previousDayOpeningBalance + loanLimit + dateCredits - dateDebits
    ```
  - **For Liability accounts (GL starting with "1")**: 
    ```java
    availableBalance = previousDayOpeningBalance
    ```

**Database Tables Involved:**
1. **`acct_bal`** - Stores account balance records by date
   - Used to get `previousDayOpeningBalance` via `getPreviousDayClosingBalance()` method
   - Uses 3-tier fallback logic:
     - Tier 1: Previous day's record (systemDate - 1)
     - Tier 2: Last transaction date (MAX(Tran_Date) < systemDate)
     - Tier 3: New account (return 0)

2. **`cust_acct_master`** - Customer account master table
   - Contains `Loan_Limit` field for Asset accounts
   - Used to determine account type (GL number) and loan limit

3. **`tran_table`** - Transaction table
   - Used to calculate `dateCredits` and `dateDebits` for the current system date
   - Only VERIFIED transactions are included in the calculation

**API Endpoint:**
- `GET /api/accounts/{accountNo}/balance`
- Returns `AccountBalanceDTO` with all calculated fields including `availableBalance`

**Frontend Usage:**
- The frontend **does NOT calculate** the available balance manually
- It **fetches the pre-calculated value** from the API via `getAccountBalance(accountNo)`
- The value is stored in `accountBalances` state and displayed directly

---

## 3. Logic Verification

### Previous Day Opening Balance (Static):

**Variable Used:** `previousDayOpeningBalance`

**Source:**
- **Primary**: Backend field `AccountBalanceDTO.previousDayOpeningBalance`
- **Fallback**: Calculated as `computedBalance - todayCredits + todayDebits` (only if backend field is missing)

**How It Stays Static:**
1. The value is fetched **once** when an account is selected in the transaction form
2. It is stored in the `accountBalances` Map state: `accountBalances.get(`${index}`)`
3. The value is **never updated** during the transaction form session
4. The helper function `getPreviousDayOpeningBalance()` always returns the same value for the same account during the session
5. The backend calculates it using the **previous day's closing balance**, which doesn't change during the current business day

**Implementation:**
```typescript
const getPreviousDayOpeningBalance = (balance: AccountBalanceDTO | undefined): number => {
  if (!balance) return 0;
  
  // Use the backend field if available (static value)
  if (balance.previousDayOpeningBalance !== undefined) {
    return balance.previousDayOpeningBalance;
  }
  
  // Fallback: Calculate from computedBalance
  // previousDayOpeningBalance = computedBalance - todayCredits + todayDebits
  if (balance.computedBalance !== undefined) {
    return balance.computedBalance - (balance.todayCredits || 0) + (balance.todayDebits || 0);
  }
  
  return 0;
};
```

### Available Balance (Real-time):

**Variable Used:** `availableBalance`

**Source:**
- Fetched directly from backend API: `GET /api/accounts/{accountNo}/balance`
- The backend calculates it in real-time based on:
  - Previous day opening balance (static)
  - Loan limit (for Asset accounts)
  - Today's credits (changes as transactions are posted/verified)
  - Today's debits (changes as transactions are posted/verified)

**How It Reflects Real-time Changes:**
1. The value is fetched from the backend API which queries the database
2. When transactions are posted/verified, the `tran_table` is updated
3. The next time `getAccountBalance()` is called, it recalculates:
   - `dateCredits` = SUM of all VERIFIED credit transactions for today
   - `dateDebits` = SUM of all VERIFIED debit transactions for today
   - `availableBalance` = `previousDayOpeningBalance + loanLimit + dateCredits - dateDebits` (for Asset accounts)

**Note:** The frontend doesn't automatically refresh this value. It needs to be re-fetched or invalidated after transactions are posted/verified (this is addressed in the auto-refresh implementation).

---

## 4. Alignment with Account Details Logic

### Account Details Component (`AccountDetails.tsx`):

**Data Source:**
- Uses `getCustomerAccountByAccountNo(accountNo)` API
- Returns `CustomerAccountResponseDTO` which includes:
  - `availableBalance` - Same calculation as Transaction form
  - `computedBalance` - Real-time balance
  - `loanLimit` - Loan limit for Asset accounts

**Display Logic:**
- For Asset accounts (GL starting with "2"):
  - Shows `account.availableBalance` (line 182)
  - Shows `account.loanLimit` (line 169)
  - Helper text: "Balance + Loan Limit - Utilized Amount" (line 185)

**Transaction Form Alignment:**
- ✅ Uses same `availableBalance` field from backend
- ✅ Shows same helper text for Asset accounts
- ✅ Uses same calculation logic (backend handles it)
- ✅ Displays `previousDayOpeningBalance` as static value (matching the requirement)

---

## Summary

1. **Previous Day Opening Balance**: Static value fetched once, stored in state, never changes during session
2. **Available Balance**: Real-time value calculated by backend, includes loan limit for Asset accounts, reflects today's transactions
3. **Data Source**: Both values come from backend API, no frontend calculation
4. **Logic Match**: Transaction form logic perfectly matches Account Details logic
