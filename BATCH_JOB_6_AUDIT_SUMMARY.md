# Batch Job 6: Interest Accrual Account Balance Update - Audit Summary

## Executive Summary

**Status**: Java processes terminated successfully  
**Batch Job**: #6 - Interest Accrual Account Balance Update  
**Purpose**: Process interest accrual transactions from `intt_accr_tran` table and update account balance accrual records in `acct_bal_accrual` table

---

## 1. Process Termination

✅ **Java processes have been terminated** using `taskkill /F /IM java.exe`

---

## 2. Batch Job 6 Overview

### 2.1 Entry Point
- **Service**: `InterestAccrualAccountBalanceService.updateInterestAccrualAccountBalances()`
- **Orchestration**: Called from `EODOrchestrationService.executeBatchJob6()`
- **Execution Context**: Part of EOD (End of Day) process sequence
- **Position in EOD Sequence**: Job #6 (after GL Balance Update, before Financial Reports)

### 2.2 High-Level Flow
```
EOD Process
    ↓
Batch Job 1: Account Balance Update
    ↓
Batch Job 2: Interest Accrual Transaction Update (populates intt_accr_tran)
    ↓
Batch Job 3: Interest Accrual GL Movement Update
    ↓
Batch Job 4: GL Movement Update
    ↓
Batch Job 5: GL Balance Update
    ↓
Batch Job 6: Interest Accrual Account Balance Update ← WE ARE HERE
    ↓
Batch Job 7: Financial Reports Generation
    ↓
Batch Job 8: System Date Increment
```

---

## 3. Data Flow: intt_accr_tran → acct_bal_accrual

### 3.1 Source Table: `intt_accr_tran`
**Purpose**: Stores interest accrual transactions created by Batch Job 2

**Key Fields**:
- `Accr_Tran_Id`: Unique transaction ID (format: S + YYYYMMDD + 9-digit-sequential + -row-suffix)
- `Account_No`: Customer account number (13 characters)
- `Accrual_Date`: Date of accrual (matches System_Date)
- `Amount`: Interest amount accrued
- `Dr_Cr_Flag`: Debit/Credit flag ('D' or 'C')
- `Original_Dr_Cr_Flag`: Original transaction's Dr/Cr flag (for value date interest only)
- `Interest_Rate`: Interest rate applied
- `Status`: Accrual status (Pending, Posted, Verified)

**Data Types**:
- **Regular Interest**: Records with `Original_Dr_Cr_Flag = NULL`
- **Value Date Interest**: Records with `Original_Dr_Cr_Flag != NULL` (special handling required)

### 3.2 Target Table: `acct_bal_accrual`
**Purpose**: Stores daily account balance accrual records with opening/closing balances

**Key Fields**:
- `Accr_Bal_Id`: Auto-increment primary key
- `Account_No`: Foreign key to `Cust_Acct_Master`
- `GL_Num`: GL number from sub-product (9 characters)
- `Accrual_Date`: Date of accrual
- `Tran_date`: Transaction date (same as Accrual_Date)
- `Opening_Bal`: Opening balance (previous day's closing balance)
- `DR_Summation`: Sum of debit amounts (conditional based on GL_Num)
- `CR_Summation`: Sum of credit amounts (conditional based on GL_Num)
- `Closing_Bal`: Calculated closing balance
- `Interest_Amount`: Net interest amount for the day

---

## 4. Detailed Processing Logic

### 4.1 Step 1: Extract Unique Accounts
**Query**: `findDistinctAccountsByAccrualDate(systemDate)`
```sql
SELECT DISTINCT Account_No 
FROM intt_accr_tran 
WHERE Accrual_Date = :systemDate
```

**Purpose**: Get all unique account numbers that have interest accruals for the current system date

**Output**: List of account numbers to process

---

### 4.2 Step 2: Process Each Account (Per-Account Loop)

For each account number, the following steps are executed:

#### **Step 2A: Get GL Number**
**Method**: `getGLNumberForAccount(accountNo)`

**Query Path**:
```
cust_acct_master (Account_No)
    ↓
Sub_Product_Id
    ↓
sub_prod_master
    ↓
Cum_GL_Num (9 characters)
```

**Logic**:
- Fetch account from `Cust_Acct_Master`
- Get associated `SubProdMaster`
- Extract `Cum_GL_Num` (must be 9 characters)
- Validate GL_Num format

**Validation**:
- GL_Num must start with '1' (Liability) or '2' (Asset)
- GL_Num must be exactly 9 characters

---

#### **Step 2B: Determine Account Type**
**Logic**:
```java
boolean isLiability = glNum.startsWith("1");
boolean isAsset = glNum.startsWith("2");
```

**Account Types**:
- **Liability Accounts**: GL_Num starts with "1" (e.g., 1xxxxx)
- **Asset Accounts**: GL_Num starts with "2" (e.g., 2xxxxx)

---

#### **Step 2C: Get Opening Balance**
**Method**: `getOpeningBalance(accountNo, systemDate)`

**3-Tier Fallback Logic**:

**Tier 1: Previous Day's Record**
- Query: `findByAccountAccountNoAndTranDateBeforeOrderByTranDateDesc(accountNo, systemDate)`
- Look for record with `Tran_date = systemDate - 1`
- If found: `Opening_Bal = previous_day.Closing_Bal`

**Tier 2: Last Transaction Date**
- If previous day's record not found, use most recent record before systemDate
- `Opening_Bal = last_record.Closing_Bal`
- Logs warning about gap in dates

**Tier 3: New Account**
- If no previous records exist: `Opening_Bal = 0`

**Purpose**: Ensures continuity of balance calculation even if there are gaps in accrual records

---

#### **Step 2D: Calculate DR Summation (Conditional Logic)**
**Method**: `calculateDRSummation(accountNo, accrualDate, glNum)`

**Logic Based on GL_Num**:

**IF GL_Num starts with "1" (Liability)**:
- `DR_Summation = 0`
- Reason: Liability accounts only have credit interest accruals

**IF GL_Num starts with "2" (Asset)**:
- Query: `sumDebitAmountsByAccountAndDate(accountNo, accrualDate)`
- SQL:
  ```sql
  SELECT COALESCE(SUM(Amount), 0)
  FROM intt_accr_tran
  WHERE Account_No = :accountNo
    AND Accrual_Date = :accrualDate
    AND Dr_Cr_Flag = 'D'
    AND Original_Dr_Cr_Flag IS NULL  -- EXCLUDES value date interest
  ```
- `DR_Summation = SUM(Amount) WHERE Dr_Cr_Flag = 'D' AND Original_Dr_Cr_Flag IS NULL`

**Important**: Value date interest is EXCLUDED from DR summation (handled separately)

---

#### **Step 2E: Calculate CR Summation (Conditional Logic)**
**Method**: `calculateCRSummation(accountNo, accrualDate, glNum)`

**Logic Based on GL_Num**:

**IF GL_Num starts with "2" (Asset)**:
- `CR_Summation = 0`
- Reason: Asset accounts only have debit interest accruals

**IF GL_Num starts with "1" (Liability)**:
- Query: `sumCreditAmountsByAccountAndDate(accountNo, accrualDate)`
- SQL:
  ```sql
  SELECT COALESCE(SUM(Amount), 0)
  FROM intt_accr_tran
  WHERE Account_No = :accountNo
    AND Accrual_Date = :accrualDate
    AND Dr_Cr_Flag = 'C'
    AND Original_Dr_Cr_Flag IS NULL  -- EXCLUDES value date interest
  ```
- `CR_Summation = SUM(Amount) WHERE Dr_Cr_Flag = 'C' AND Original_Dr_Cr_Flag IS NULL`

**Important**: Value date interest is EXCLUDED from CR summation (handled separately)

---

#### **Step 2F: Calculate Value Date Interest Impact**
**Method**: `calculateValueDateInterestImpact(accountNo, accrualDate, isLiability)`

**Purpose**: Handle value date interest separately because balance impact depends on ORIGINAL transaction's Dr/Cr flag, not just the accrual entry's flag.

**IMPORTANT FIX**: Only processes records where `Dr_Cr_Flag = Original_Dr_Cr_Flag` to prevent double counting.

**Why This Filter is Needed**:
- Value date interest creates TWO records per transaction:
  1. **Balance Sheet record**: `Dr_Cr_Flag = Original_Dr_Cr_Flag` (affects account balance)
  2. **P&L record**: `Dr_Cr_Flag != Original_Dr_Cr_Flag` (Interest Income/Expense, does NOT affect account balance)
- Without this filter, both records would be summed, causing double counting
- Only the Balance Sheet record should be included in account balance calculation

**Query**: `findByAccountNoAndAccrualDateAndOriginalDrCrFlagNotNull(accountNo, accrualDate)`
```sql
SELECT * FROM intt_accr_tran
WHERE Account_No = :accountNo
  AND Accrual_Date = :accrualDate
  AND Original_Dr_Cr_Flag IS NOT NULL
  AND Dr_Cr_Flag = Original_Dr_Cr_Flag  -- CRITICAL: Prevents double counting
```

**Logic by Account Type**:

**LIABILITY Accounts (GL_Num starts with "1")**:
- **Original Transaction Credit (Deposit)** → **ADD interest** (we owe more)
  - Impact = `+Amount`
- **Original Transaction Debit (Withdrawal)** → **SUBTRACT interest** (we owe less)
  - Impact = `-Amount`

**ASSET Accounts (GL_Num starts with "2")**:
- **Original Transaction Debit (Advance)** → **SUBTRACT interest** (they owe more, balance more negative)
  - Impact = `-Amount`
- **Original Transaction Credit (Repayment)** → **ADD interest** (they owe less, balance less negative)
  - Impact = `+Amount`

**Calculation**:
```java
totalImpact = SUM(impact for each value date interest record)
```

---

#### **Step 2G: Validation**
**Rule**: For regular interest, one of DR or CR must be zero
```java
if (drSummation > 0 && crSummation > 0) {
    throw BusinessException("Both DR and CR summations are non-zero")
}
```

**Reason**: Regular interest accruals should only be either debit OR credit, not both

---

#### **Step 2H: Calculate Closing Balance**
**Formula**:
```java
Closing_Bal = Opening_Bal + CR_Summation - DR_Summation + Value_Date_Interest_Impact
```

**Breakdown**:
- Start with opening balance
- Add credit summations (for liability accounts)
- Subtract debit summations (for asset accounts)
- Add/subtract value date interest impact

---

#### **Step 2I: Calculate Interest Amount**
**Formula**:
```java
Interest_Amount = CR_Summation - DR_Summation + Value_Date_Interest_Impact
```

**Purpose**: Net interest amount accrued for the day (used for reporting)

---

#### **Step 2J: Save or Update Record**
**Method**: `saveOrUpdateAccrualBalance(...)`

**Logic**:
1. Check if record exists: `findByAccountAccountNoAndTranDate(accountNo, tranDate)`
2. **If exists**: Update existing record with new calculated values
3. **If not exists**: Create new record with all calculated values

**Fields Saved**:
- `Account_No` (via foreign key relationship)
- `GL_Num` (from sub-product)
- `Tran_date` = `Accrual_Date` = `systemDate`
- `Opening_Bal`
- `DR_Summation`
- `CR_Summation`
- `Closing_Bal`
- `Interest_Amount`

---

## 5. Key Processing Rules Summary

### 5.1 GL_Num-Based Conditional Logic

| GL_Num Type | DR_Summation | CR_Summation | Value Date Handling |
|-------------|--------------|--------------|---------------------|
| **Liability (starts with "1")** | Always 0 | SUM(Credit amounts) | Based on original transaction flag |
| **Asset (starts with "2")** | SUM(Debit amounts) | Always 0 | Based on original transaction flag |

### 5.2 Regular vs Value Date Interest

| Interest Type | Identification | DR/CR Calculation | Balance Impact |
|---------------|----------------|-------------------|----------------|
| **Regular Interest** | `Original_Dr_Cr_Flag IS NULL` | Included in DR/CR summation | Standard formula |
| **Value Date Interest** | `Original_Dr_Cr_Flag IS NOT NULL` AND `Dr_Cr_Flag = Original_Dr_Cr_Flag` | Excluded from DR/CR summation | Separate calculation based on original transaction (Balance Sheet record only) |

**Note**: Value date interest creates 2 records (Balance Sheet + P&L). Only the Balance Sheet record (`Dr_Cr_Flag = Original_Dr_Cr_Flag`) is processed to prevent double counting.

### 5.3 Opening Balance Fallback

1. **Tier 1**: Previous day's closing balance (preferred)
2. **Tier 2**: Last available closing balance (if gap exists)
3. **Tier 3**: Zero (new account or first accrual)

---

## 6. Data Transformation Example

### Example 1: Liability Account with Regular Interest

**Input (intt_accr_tran)**:
```
Account_No: 1000000000001
Accrual_Date: 2025-03-23
GL_Num: 100000001 (Liability)
Amount: 100.00
Dr_Cr_Flag: 'C'
Original_Dr_Cr_Flag: NULL (regular interest)
```

**Processing**:
- Opening_Bal: 1000.00 (from previous day)
- DR_Summation: 0 (Liability account)
- CR_Summation: 100.00
- Value_Date_Interest_Impact: 0
- Closing_Bal: 1000.00 + 100.00 - 0 + 0 = 1100.00
- Interest_Amount: 100.00 - 0 + 0 = 100.00

**Output (acct_bal_accrual)**:
```
Account_No: 1000000000001
GL_Num: 100000001
Tran_date: 2025-03-23
Opening_Bal: 1000.00
DR_Summation: 0.00
CR_Summation: 100.00
Closing_Bal: 1100.00
Interest_Amount: 100.00
```

### Example 2: Asset Account with Value Date Interest

**Input (intt_accr_tran)**:
```
Account_No: 2000000000001
Accrual_Date: 2025-03-23
GL_Num: 200000001 (Asset)
Amount: 50.00
Dr_Cr_Flag: 'D'
Original_Dr_Cr_Flag: 'D' (value date interest from advance)
```

**Processing**:
- Opening_Bal: 5000.00
- DR_Summation: 0 (no regular interest)
- CR_Summation: 0 (Asset account)
- Value_Date_Interest_Impact: -50.00 (Asset + Original Debit = subtract)
- Closing_Bal: 5000.00 + 0 - 0 - 50.00 = 4950.00
- Interest_Amount: 0 - 0 - 50.00 = -50.00

**Output (acct_bal_accrual)**:
```
Account_No: 2000000000001
GL_Num: 200000001
Tran_date: 2025-03-23
Opening_Bal: 5000.00
DR_Summation: 0.00
CR_Summation: 0.00
Closing_Bal: 4950.00
Interest_Amount: -50.00
```

---

## 7. Error Handling

### 7.1 Per-Account Error Handling
- Each account is processed independently
- Errors for one account do not stop processing of other accounts
- Errors are logged and collected in an error list
- Final log includes count of errors

### 7.2 Validation Errors
- **Invalid GL_Num**: Must start with '1' or '2' and be 9 characters
- **Both DR and CR non-zero**: Regular interest validation failure
- **Account not found**: Account doesn't exist in `Cust_Acct_Master`
- **Sub-product not found**: Account has no associated sub-product
- **Cum_GL_Num missing**: Sub-product has no GL number

### 7.3 Transaction Management
- Entire batch job runs in a `@Transactional` context
- If any critical error occurs, entire transaction rolls back
- Per-account errors are caught and logged but don't rollback entire job

---

## 8. Performance Considerations

### 8.1 Query Optimization
- Uses indexed queries on `Account_No` and `Accrual_Date`
- Distinct account query reduces processing overhead
- Batch processing per account (not per transaction)

### 8.2 Database Operations
- One query per account for:
  - Opening balance lookup
  - DR summation
  - CR summation
  - Value date interest records
- Upsert operation (save or update) for final record

### 8.3 Logging
- Debug-level logging for detailed account processing
- Info-level logging for summary statistics
- Warning-level logging for fallback scenarios (Tier 2 opening balance)

---

## 9. Dependencies

### 9.1 Prerequisites
- **Batch Job 2** must have completed successfully (populates `intt_accr_tran`)
- **System_Date** must be set correctly
- Account master data must exist (`Cust_Acct_Master`)
- Sub-product master data must exist (`Sub_Prod_Master`)

### 9.2 Downstream Impact
- **Batch Job 7** (Financial Reports) may use `acct_bal_accrual` data
- Reports may aggregate interest accrual balances by GL_Num

---

## 10. Code References

### 10.1 Main Service Class
- **File**: `moneymarket/src/main/java/com/example/moneymarket/service/InterestAccrualAccountBalanceService.java`
- **Method**: `updateInterestAccrualAccountBalances(LocalDate systemDate)`

### 10.2 Repository Interfaces
- **InttAccrTranRepository**: `findDistinctAccountsByAccrualDate()`, `sumDebitAmountsByAccountAndDate()`, `sumCreditAmountsByAccountAndDate()`, `findByAccountNoAndAccrualDateAndOriginalDrCrFlagNotNull()`
- **AcctBalAccrualRepository**: `findByAccountAccountNoAndTranDate()`, `findByAccountAccountNoAndTranDateBeforeOrderByTranDateDesc()`
- **CustAcctMasterRepository**: `findById()`

### 10.3 Entity Classes
- **InttAccrTran**: Source entity (`intt_accr_tran` table)
- **AcctBalAccrual**: Target entity (`acct_bal_accrual` table)
- **CustAcctMaster**: Account master entity
- **SubProdMaster**: Sub-product master entity

---

## 11. Summary

Batch Job 6 processes interest accrual transactions from `intt_accr_tran` and creates/updates account balance accrual records in `acct_bal_accrual`. The processing logic:

1. **Extracts** unique accounts with accruals for the system date
2. **Determines** account type (Liability/Asset) from GL_Num
3. **Calculates** opening balance using 3-tier fallback logic
4. **Sums** regular interest (DR/CR) conditionally based on GL_Num
5. **Handles** value date interest separately based on original transaction flag
6. **Calculates** closing balance and interest amount
7. **Saves/Updates** accrual balance records with GL_Num

The key differentiator is the **GL_Num-based conditional logic** that determines whether DR or CR summation is used, and the **separate handling of value date interest** based on the original transaction's Dr/Cr flag.

---

## 12. Bug Fix: Double Counting Issue (Fixed)

### 12.1 Issue Description
Value date interest was being double counted in `acct_bal_accrual` because:
- Value date interest creates TWO records per transaction:
  1. Balance Sheet record (affects account balance)
  2. P&L record (Interest Income/Expense, does NOT affect account balance)
- Both records have the same `Original_Dr_Cr_Flag` but different `Dr_Cr_Flag` values
- The original query processed ALL records with `Original_Dr_Cr_Flag IS NOT NULL`, causing both records to be summed

### 12.2 Solution Implemented
**Filter Added**: `Dr_Cr_Flag = Original_Dr_Cr_Flag`

**Updated Query**:
```sql
SELECT * FROM intt_accr_tran
WHERE Account_No = :accountNo
  AND Accrual_Date = :accrualDate
  AND Original_Dr_Cr_Flag IS NOT NULL
  AND Dr_Cr_Flag = Original_Dr_Cr_Flag  -- NEW: Prevents double counting
```

**Result**: Only the Balance Sheet record is processed, excluding the P&L record, preventing double counting.

### 12.3 Files Modified
1. `InttAccrTranRepository.java`: Updated query to include `Dr_Cr_Flag = Original_Dr_Cr_Flag` filter
2. `InterestAccrualAccountBalanceService.java`: Updated documentation to explain the fix

---

**Document Generated**: 2025-03-23  
**Last Updated**: 2025-03-23 (Double counting fix applied)  
**System**: Money Market CBS  
**Batch Job**: #6 - Interest Accrual Account Balance Update

