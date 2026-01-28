# Interest Capitalization Feature

## Quick Start Guide

### Accessing the Feature
1. Navigate to the Money Market CBS application
2. In the left sidebar, click on **"Interest Capitalization"** (below "Transactions")
3. You'll see the main search and filter page

### Using Interest Capitalization

#### Step 1: Search for Interest-Bearing Accounts
Use the advanced search filters to find accounts:

**Filter Options:**
- **Product**: Select a product (e.g., Savings Cash, Saving Money, OverDraft)
- **Sub Product**: Automatically filters based on selected Product
- **CIF**: Search for customer by ID or name
- **Account No / Name**: Search by account number or account name

**Note:** Only interest-bearing accounts will be displayed in the results.

#### Step 2: Select an Account
- Click the **"Select"** button or the **View icon** for the account you want to process
- This navigates to the Account Details page

#### Step 3: Review Account Details
The details page shows:
- **Balance (Real Time)**: Current account balance including today's transactions
- **Accrued Balance**: The interest amount that will be capitalized
- **Last Interest Payment Date**: Date when interest was last posted
- Complete account and customer information

#### Step 4: Validation Checks
The system automatically validates:
1. ✅ Account must be interest-bearing
2. ✅ Interest cannot have been capitalized today already
3. ✅ Accrued balance must be greater than zero

If any validation fails, you'll see an error message and the "Proceed Interest" button will be disabled.

#### Step 5: Capitalize Interest
1. Click **"Proceed Interest"** button
2. Review the confirmation dialog showing:
   - Current Balance
   - Accrued Interest
   - New Balance (Current + Accrued)
3. Optionally add a narration/description
4. Click **"Confirm"** to proceed

#### Step 6: Success
Upon successful capitalization:
- A success notification displays with transaction details
- The new balance is shown
- A unique transaction ID is generated (starts with 'C')
- You'll be automatically redirected to the list page

## Transaction Details

### What Happens Behind the Scenes

When you capitalize interest, the system:

1. **Creates Two Transaction Entries:**
   - **Debit Entry**: Posted to Interest Expense GL account (in `Intt_Accr_Tran` table)
   - **Credit Entry**: Posted to Customer Account (in `Tran_Table`)
   - Both entries have the same Transaction ID with suffixes `-1` (debit) and `-2` (credit)

2. **Updates Account Balances:**
   - **Current Balance**: Increased by accrued interest amount
   - **Accrued Balance**: Reset to zero
   - **Last Interest Payment Date**: Set to current system date

3. **Transaction ID Format:**
   ```
   C + yyyyMMdd + 6-digit-sequence + 3-digit-random
   Example: C20260128000001123
   ```

### Transaction Processing Flow

```
BEFORE Capitalization:
├─ Balance (Real Time): 5,000 Taka
├─ Accrued Balance: 50 Taka
└─ Last Interest Payment Date: 2026-01-15

CAPITALIZATION PROCESS:
├─ Create Debit: C20260128000001123-1 → Interest Expense GL (50 Taka)
├─ Create Credit: C20260128000001123-2 → Customer Account (50 Taka)
└─ Update Account

AFTER Capitalization:
├─ Balance (Real Time): 5,050 Taka
├─ Accrued Balance: 0 Taka
└─ Last Interest Payment Date: 2026-01-28
```

## Account Types

### Supported Accounts
Interest Capitalization works with both:

**Liability Accounts:**
- Savings Accounts (SB)
- Current Accounts (CA)
- Fixed Deposit (TD)
- Recurring Deposit (RD)

**Asset Accounts:**
- Overdraft (OD)
- Cash Credit (CC)
- Term Loan (TL)

### Requirements
- Account's product must have `Interest Bearing` flag enabled
- Account must have accrued interest > 0
- Interest cannot have been capitalized on the same system date

## EOD Processing

Interest Capitalization transactions are fully integrated with End of Day (EOD) processing:

### Batch Job Processing
1. **Batch Job 1**: Processes the credit entries to update account balances
2. **Batch Job 3**: Processes the debit entries to update GL movements
3. **Batch Jobs 4 & 5**: Update GL balances for both sides

### Transaction Status
- Capitalization transactions are created with **"Posted"** status
- They are NOT deleted during EOD Entry transaction cleanup
- They persist and are properly reconciled

## Error Messages

| Error Message | Cause | Solution |
|--------------|-------|----------|
| "The account is Non-Interest bearing" | Product does not have interest bearing flag enabled | Select a different account with interest-bearing product |
| "Interest has already been capitalized" | Last Interest Payment Date >= Current System Date | Wait until next business day or select different account |
| "There is no accrued interest" | Accrued Balance = 0 | Interest hasn't accrued yet; run EOD to generate accrual or select different account |
| "Account not found" | Invalid account number | Verify account number and try again |
| "Account balance record not found for system date" | Missing balance entry | Contact system administrator |

## Best Practices

### When to Use Interest Capitalization
- ✅ Manual posting of accrued interest to customer accounts
- ✅ Special requests from customers for immediate interest posting
- ✅ Month-end or quarter-end interest posting
- ✅ Account closure with pending accrued interest

### When NOT to Use
- ❌ Regular daily interest accrual (use EOD process)
- ❌ Automated interest posting (handled by EOD)
- ❌ If interest was already capitalized today

### Timing Recommendations
1. **Capitalize interest BEFORE running EOD** for the cleanest transaction flow
2. **Verify accrued balance** before proceeding
3. **Check last payment date** to avoid duplicates
4. **Document the narration** for audit purposes

## Troubleshooting

### Issue: "Select" button doesn't navigate
**Solution:** Check browser console for errors, ensure routes are properly configured

### Issue: Validation always fails
**Solution:** 
1. Verify system date is set correctly (System Date page)
2. Check product has Interest Bearing flag enabled
3. Confirm account has accrued interest (run EOD first)

### Issue: Transaction succeeds but balance not updated
**Solution:** Refresh the page; balance updates are immediate but may need cache refresh

### Issue: Cannot find interest-bearing accounts
**Solution:**
1. Verify products have Interest Bearing flag enabled in Product Master
2. Check that accounts are associated with correct products
3. Ensure EOD has run at least once to generate initial accrual

## Developer Notes

### API Endpoints
```
POST /api/interest-capitalization
Request Body:
{
  "accountNo": "1101010010001",
  "narration": "Monthly interest capitalization"
}

Response:
{
  "accountNo": "1101010010001",
  "accountName": "John Doe - SB",
  "oldBalance": 5000.00,
  "accruedInterest": 50.00,
  "newBalance": 5050.00,
  "transactionId": "C20260128000001123",
  "capitalizationDate": "2026-01-28",
  "message": "Interest capitalization successful"
}
```

### Database Tables Affected
- `Cust_Acct_Master`: Last_Interest_Payment_Date updated
- `Acct_Bal`: Current_Balance increased
- `Acct_Bal_Accrual`: Interest_Amount reset to 0
- `Tran_Table`: Credit entry added (customer account)
- `Intt_Accr_Tran`: Debit entry added (interest expense GL)

### Key Service Methods
```java
// Backend
InterestCapitalizationService.capitalizeInterest(request)

// Frontend
capitalizeInterest(request: InterestCapitalizationRequest)
```

## Security & Permissions

### Access Control
- Feature available to all authenticated users
- No special role required (unlike BOD/EOD which require ADMIN role)
- All transactions are logged with user ID

### Audit Trail
Every capitalization creates:
1. Transaction entry in `Tran_Table` with unique ID
2. Accrual entry in `Intt_Accr_Tran` with unique ID
3. EOD log entries during batch processing
4. Account update timestamp

## Support & Documentation

### Related Features
- **Interest Accrual**: Automated during EOD Batch Job 2
- **Account Management**: View/edit account details
- **Statement of Accounts**: View transaction history
- **EOD Processing**: End of Day batch jobs

### Additional Resources
- [System Architecture](./ARCHITECTURE.md)
- [EOD Processing Guide](./EOD_PROCESSING.md)
- [Transaction Management](./TRANSACTION_GUIDE.md)

## Version History

### Version 1.0 (January 2026)
- Initial implementation
- Support for both liability and asset accounts
- Full EOD integration
- Comprehensive validation rules
- Real-time balance updates

---

**Feature Status:** ✅ Production Ready

**Last Updated:** January 28, 2026

**Maintained By:** Money Market Development Team
