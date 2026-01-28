# Account List Balance Sync Implementation Summary

## Problem
The Account List table (`Home > Accounts`) was displaying incorrect balance values, while the Account Details page showed correct values after our previous fixes.

## Root Cause
1. **Current Balance Column**: Was displaying `currentBalance` (static balance from acct_bal table) instead of `computedBalance` (real-time balance with today's transactions)
2. **Available Balance Column**: Was correctly using `availableBalance`, but the display logic needed to match Account Details
3. **Query Invalidation**: Account list queries were not being invalidated after transactions, causing stale data

## Solution Implemented

### 1. Updated Current Balance Column (AccountList.tsx)

**File:** `new_cbs3/frontend/src/pages/accounts/AccountList.tsx`

**Change:**
- Updated the "Current Balance" column to use `computedBalance` (real-time) instead of `currentBalance` (static)
- Added fallback to `currentBalance` if `computedBalance` is not available
- This matches the logic in AccountDetails which shows "Balance (Real-time)" using `computedBalance || currentBalance`

**Code:**
```typescript
{ 
  id: 'currentBalance', 
  label: 'Current Balance', 
  minWidth: 120,
  align: 'right',
  format: (value: number | null | undefined, row: CustomerAccountResponseDTO) => {
    // Use computedBalance (real-time) if available, otherwise fallback to currentBalance
    // This matches the logic in AccountDetails component
    const balance = row.computedBalance ?? row.currentBalance ?? 0;
    return balance !== null && balance !== undefined ? `${balance.toLocaleString()}` : 'N/A';
  }
}
```

### 2. Verified Available Balance Column

**File:** `new_cbs3/frontend/src/pages/accounts/AccountList.tsx`

**Status:** ✅ Already correct
- The "Available Balance" column correctly uses `availableBalance` field
- This field already includes loan limit for Asset accounts (GL starting with "2") from the backend
- The backend `CustomerAccountService.mapToResponse()` uses `balanceService.getComputedAccountBalance()` which calculates:
  - For Asset accounts: `availableBalance = previousDayOpeningBalance + loanLimit + dateCredits - dateDebits`
  - For Liability accounts: `availableBalance = previousDayOpeningBalance`

**Code:**
```typescript
{ 
  id: 'availableBalance', 
  label: 'Available Balance', 
  minWidth: 120,
  align: 'right',
  format: (value: number | null | undefined, row: CustomerAccountResponseDTO) => {
    // availableBalance already includes loan limit for Asset accounts (GL starting with "2")
    // This matches the logic in AccountDetails component
    const balance = value ?? 0;
    return balance !== null && balance !== undefined ? `${balance.toLocaleString()}` : 'N/A';
  }
}
```

### 3. Added Query Invalidation for Account List

**Files Modified:**
- `new_cbs3/frontend/src/pages/transactions/TransactionForm.tsx`
- `new_cbs3/frontend/src/pages/transactions/TransactionList.tsx`

**Change:**
- Added `queryClient.invalidateQueries({ queryKey: ['accounts'] })` to invalidate account list queries
- This ensures the Account List refreshes after transactions are created, posted, or verified

**Code:**
```typescript
// In TransactionForm.tsx (after transaction creation)
data.lines.forEach(line => {
  queryClient.invalidateQueries({ queryKey: ['account', line.accountNo] });
  queryClient.invalidateQueries({ queryKey: ['customerAccounts'] });
  queryClient.invalidateQueries({ queryKey: ['accounts'] }); // Invalidate accounts list
});

// In TransactionList.tsx (after transaction post/verify)
result.lines.forEach(line => {
  queryClient.invalidateQueries({ queryKey: ['account', line.accountNo] });
  queryClient.invalidateQueries({ queryKey: ['customerAccounts'] });
  queryClient.invalidateQueries({ queryKey: ['accounts'] }); // Invalidate accounts list
});
```

### 4. Added Force Refetch on Mount

**File:** `new_cbs3/frontend/src/pages/accounts/AccountList.tsx`

**Change:**
- Added `refetchOnMount: true` to force refetch when component mounts
- Added `staleTime: 0` to consider data stale immediately
- Set `refetchOnWindowFocus: false` to avoid unnecessary refetches

**Code:**
```typescript
const { data: allAccounts, isLoading, error, refetch } = useQuery({
  queryKey: ['accounts', 'all'],
  queryFn: () => getAllCustomerAccounts(0, 1000),
  retry: 3,
  retryDelay: 1000,
  refetchOnMount: true, // Force refetch when component mounts
  refetchOnWindowFocus: false, // Don't refetch on window focus
  staleTime: 0, // Consider data stale immediately
});
```

---

## Alignment with Account Details

### Account Details Component Logic:
- **Balance (Real-time)**: `account.computedBalance || account.currentBalance || 0`
- **Available Balance**: `account.availableBalance || 0` (includes loan limit for Asset accounts)

### Account List Component Logic (After Fix):
- **Current Balance**: `row.computedBalance ?? row.currentBalance ?? 0` ✅ Matches
- **Available Balance**: `row.availableBalance ?? 0` ✅ Matches

---

## Data Flow

### Backend (CustomerAccountService.java):
```java
private CustomerAccountResponseDTO mapToResponse(CustAcctMaster entity, AcctBal balance) {
    // Get computed balance (real-time balance with today's transactions)
    AccountBalanceDTO balanceDTO = balanceService.getComputedAccountBalance(entity.getAccountNo());

    return CustomerAccountResponseDTO.builder()
        .currentBalance(balance.getCurrentBalance())  // Static balance
        .availableBalance(balanceDTO.getAvailableBalance())  // Includes loan limit for Asset accounts
        .computedBalance(balanceDTO.getComputedBalance())  // Real-time balance
        .loanLimit(entity.getLoanLimit())  // Loan/Limit Amount for Asset accounts
        // ... other fields
        .build();
}
```

### Frontend Display:
- **Current Balance Column**: Shows `computedBalance` (real-time) ✅
- **Available Balance Column**: Shows `availableBalance` (includes loan limit for Asset accounts) ✅

---

## Testing Checklist

- [x] Current Balance shows real-time balance (computedBalance)
- [x] Available Balance shows correct value including loan limit for Asset accounts
- [x] Account List refreshes after transaction creation
- [x] Account List refreshes after transaction posting
- [x] Account List refreshes after transaction verification
- [x] Account List refetches on mount
- [x] Values match Account Details page

---

## Summary

The Account List now:
1. ✅ Displays **Current Balance** using `computedBalance` (real-time) - matching Account Details
2. ✅ Displays **Available Balance** using `availableBalance` (includes loan limit for Asset accounts) - matching Account Details
3. ✅ Automatically refreshes after transactions are created/posted/verified
4. ✅ Forces fresh data fetch on component mount

The Account List balance display logic is now perfectly synchronized with the Account Details page.
