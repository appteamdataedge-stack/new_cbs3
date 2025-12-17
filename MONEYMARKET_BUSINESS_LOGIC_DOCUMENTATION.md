# Money Market Module - Complete Business Logic Documentation

**Version:** 1.0
**Date:** December 2025
**Location:** `C:\cbs_prototype\cbs3\moneymarket`

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Customer Management](#2-customer-management)
3. [Account Management](#3-account-management)
4. [General Ledger (GL) Structure](#4-general-ledger-gl-structure)
5. [Interest Calculation & Accrual](#5-interest-calculation--accrual)
6. [Transaction Processing](#6-transaction-processing)
7. [End of Day (EOD) Operations](#7-end-of-day-eod-operations)
8. [Value Dating](#8-value-dating)
9. [Multi-Currency Transactions (MCT)](#9-multi-currency-transactions-mct)
10. [Database Schema](#10-database-schema)
11. [Key Services & Utilities](#11-key-services--utilities)
12. [Technology Stack](#12-technology-stack)

---

## 1. System Overview

The Money Market Module is a comprehensive core banking system that handles:
- Customer and account management
- Multi-currency transaction processing
- Interest calculation and accrual
- General Ledger (GL) accounting
- End-of-Day (EOD) batch processing
- Value dating for backdated and future-dated transactions
- Foreign currency revaluation
- Financial reporting

### System Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Frontend (React)                     │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│              REST API Controllers                       │
│  CustomerController │ AccountController │ TxnController │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                  Service Layer                          │
│  Business Logic │ Validation │ Calculations             │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│              JPA Repositories                           │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                  MySQL Database                         │
└─────────────────────────────────────────────────────────┘
```

---

## 2. Customer Management

### 2.1 Customer Creation Flow

**Key Files:**
- Entity: `entity/CustMaster.java`
- Service: `service/CustomerService.java`
- Controller: `controller/CustomerController.java`
- Repository: `repository/CustMasterRepository.java`
- Validation: `validation/CustomerValidator.java`

### 2.2 Customer Types

1. **Individual (I)**: Retail customers
2. **Corporate (C)**: Corporate entities
3. **Bank (B)**: Other banks

### 2.3 Customer ID Generation

**Service:** `CustomerIdService.generateCustomerId(custType)`

**Format:**
- Individual: `I` + 9-digit sequential number (e.g., `I000000001`)
- Corporate: `C` + 9-digit sequential number (e.g., `C000000001`)
- Bank: `B` + 9-digit sequential number (e.g., `B000000001`)

### 2.4 Customer Fields

| Field | Type | Description | Required |
|-------|------|-------------|----------|
| Cust_Id | String(10) | Primary Key, Auto-generated | Yes |
| Ext_Cust_Id | String(20) | External/Legacy customer ID | Yes (Unique) |
| Cust_Type | Enum | I/C/B | Yes |
| First_Name | String(50) | First name (Individual only) | Conditional |
| Last_Name | String(50) | Last name (Individual only) | Conditional |
| Trade_Name | String(100) | Business name (Corporate/Bank) | Conditional |
| Address_1 | String(200) | Primary address | No |
| Address_2 | String(200) | Secondary address | No |
| City | String(50) | City | No |
| Postal_Code | String(10) | Postal code | No |
| Country | String(50) | Country | No |
| Mobile | String(15) | Mobile number | No |
| Email | String(100) | Email address | No |
| Branch_Code | String(10) | Home branch | Yes |
| Entry_Date | LocalDate | Entry date | Auto-set |
| Maker_Id | String(20) | Maker user ID | Auto-set |
| Verification_Date | LocalDate | Verification date | Auto-set |
| Verifier_Id | String(20) | Verifier user ID | Auto-set |

### 2.5 Business Rules

1. **Name Validation**:
   - Individual: Must have `First_Name` and `Last_Name`
   - Corporate/Bank: Must have `Trade_Name`

2. **Unique Constraints**:
   - `Ext_Cust_Id` must be unique across all customers
   - `Cust_Id` is auto-generated and unique

3. **Maker-Checker Process**:
   - Maker creates customer (Entry_Date, Maker_Id set)
   - Checker verifies customer (Verification_Date, Verifier_Id set)
   - Customer can be used only after verification

### 2.6 API Endpoints

```
POST   /api/customers                  - Create new customer
GET    /api/customers/{id}             - Get customer by ID
PUT    /api/customers/{id}             - Update customer
GET    /api/customers                  - List customers (paginated)
POST   /api/customers/{id}/verify      - Verify customer (Checker)
DELETE /api/customers/{id}             - Delete customer (soft delete)
GET    /api/customers/search           - Search customers
```

---

## 3. Account Management

### 3.1 Account Types

The system supports two types of accounts:

#### A. Customer Accounts (`Cust_Acct_Master`)
Accounts belonging to customers (individuals, corporates, banks)

#### B. Office Accounts (`OF_Acct_Master`)
Internal bank accounts (GL control accounts, suspense accounts, etc.)

### 3.2 Customer Account Creation

**Key Files:**
- Entity: `entity/CustAcctMaster.java`
- Service: `service/CustomerAccountService.java`
- Controller: `controller/CustomerAccountController.java`
- Repository: `repository/CustAcctMasterRepository.java`

### 3.3 Account Number Generation

**Service:** `AccountNumberService.generateCustomerAccountNumber(customer, subProduct)`

**Format:** `Branch(3) + Product(3) + Sequential(10)` = 16 digits

**Example:**
- Branch: `001` (Head Office)
- Product: `101` (Savings Account)
- Sequential: `0000000001`
- **Account Number:** `0011010000000001`

### 3.4 Product Categories

| Product Code | Product Name | GL Range | Account Type |
|--------------|-------------|----------|--------------|
| 110101 | Savings Bank (SB) | 110101xxx | Running (Liability) |
| 110102 | Current Account (CA) | 110102xxx | Running (Liability) |
| 110201 | Term Deposit (TD) | 110201xxx | Deal (Liability) |
| 110202 | Recurring Deposit (RD) | 110202xxx | Deal (Liability) |
| 210201 | Overdraft (OD) | 210201xxx | Deal (Asset) |
| 210202 | Term Loan (TL) | 210202xxx | Deal (Asset) |

### 3.5 Running vs Deal Accounts

#### Running Accounts (SB, CA)
- **Tenor:** NULL
- **Date_Maturity:** NULL
- **Balance:** Can fluctuate daily
- **Interest:** Calculated on daily balance

#### Deal Accounts (TD, RD, OD, TL)
- **Tenor:** Required (in days)
- **Date_Maturity:** Auto-calculated as `Date_Open + Tenor days`
- **Balance:** Fixed (for TD) or within limit (for OD/TL)
- **Interest:** Based on deal terms

### 3.6 GL Assignment Logic

**Process:**
1. User selects `Sub_Product_Id` during account creation
2. System fetches `Sub_Prod_Master` record
3. `GL_Num` is set from `Sub_Prod_Master.Cum_GL_Num`

**Example:**
```sql
Sub_Product_Id: 110101001
  ↓
Sub_Prod_Master.Cum_GL_Num: 110101001
  ↓
Cust_Acct_Master.GL_Num: 110101001
```

### 3.7 Loan Limit (Asset Accounts Only)

**Business Rule:** Only asset accounts (GL starts with "2") can have a loan limit.

**Fields:**
- `loanLimit`: Maximum approved limit (BigDecimal)
- Validation: Prevents overdrawing beyond limit

**Example:**
```
Account: 0012102010000000001 (Overdraft)
GL_Num: 210201001 (starts with "2" → Asset)
Loan_Limit: 100,000.00 BDT
Available Balance: Loan_Limit - Current_Balance
```

### 3.8 Tenor and Maturity Calculation

**Auto-Calculation Logic:**

1. **User provides Tenor:**
   ```
   Date_Open: 2025-01-15
   Tenor: 365 days
   → Date_Maturity: 2026-01-15 (auto-calculated)
   ```

2. **User provides Date_Maturity:**
   ```
   Date_Open: 2025-01-15
   Date_Maturity: 2026-01-15
   → Tenor: 365 days (auto-calculated)
   ```

### 3.9 Account Balance Initialization

When a customer account is created:
1. System creates a record in `Acct_Bal` table
2. **Composite PK:** `(Tran_Date, Account_No)`
3. **Initial Values:**
   - `Opening_Bal`: 0.00
   - `DR_Summation`: 0.00
   - `CR_Summation`: 0.00
   - `Closing_Bal`: 0.00
   - `Current_Balance`: 0.00
   - `Available_Balance`: 0.00

### 3.10 Account Closure

**Service:** `CustomerAccountService.closeAccount(accountNo)`

**Validation:**
1. Check if `Current_Balance` = 0
2. Check if `Accrued_Interest` = 0
3. If valid, set `Date_Close` = System_Date
4. If not valid, throw error: "Cannot close account with non-zero balance"

### 3.11 Customer Account Fields

| Field | Type | Description |
|-------|------|-------------|
| Account_No | String(16) | Primary Key, Auto-generated |
| Cust_Id | String(10) | Foreign Key → Cust_Master |
| Sub_Product_Id | String(9) | Foreign Key → Sub_Prod_Master |
| GL_Num | String(9) | Foreign Key → GL_Setup |
| Branch_Code | String(10) | Branch code |
| Ccy_Code | String(3) | Currency (BDT, USD, EUR, etc.) |
| Date_Open | LocalDate | Account opening date |
| Date_Close | LocalDate | Account closing date |
| Tenor | Integer | Tenor in days (Deal accounts) |
| Date_Maturity | LocalDate | Maturity date (Deal accounts) |
| loanLimit | BigDecimal | Loan limit (Asset accounts only) |
| Rate_of_Interest | BigDecimal | Interest rate (%) |
| Entry_Date | LocalDate | Entry date |
| Maker_Id | String(20) | Maker user ID |

### 3.12 Office Account Fields

Similar to Customer Accounts, but:
- No `Cust_Id` (internal bank accounts)
- Used for GL control accounts
- Examples: Cash-in-hand, Nostro accounts, Suspense accounts

### 3.13 API Endpoints

```
POST   /api/customer-accounts                    - Create customer account
GET    /api/customer-accounts/{accountNo}        - Get account details
PUT    /api/customer-accounts/{accountNo}        - Update account
GET    /api/customer-accounts                    - List accounts
POST   /api/customer-accounts/{accountNo}/close  - Close account
GET    /api/customer-accounts/balance/{accountNo} - Get account balance

POST   /api/office-accounts                      - Create office account
GET    /api/office-accounts/{accountNo}          - Get office account
```

---

## 4. General Ledger (GL) Structure

### 4.1 GL Hierarchy (5-Layer Chart of Accounts)

**Key Files:**
- Entity: `entity/GLSetup.java`
- Service: `service/GLSetupService.java`
- Hierarchy Service: `service/GLHierarchyService.java`
- Repository: `repository/GLSetupRepository.java`

### 4.2 GL Numbering System (9 Digits)

```
Position:  1   2 3   4 5   6 7   8 9
          [A] [BB] [CC] [DD] [EE]
           │    │    │    │    │
           │    │    │    │    └─ Layer 5: Sub-product level
           │    │    │    └────── Layer 4: Product level
           │    │    └─────────── Layer 3: Account group
           │    └──────────────── Layer 2: Sub-category
           └───────────────────── Layer 1: Major category
```

### 4.3 Layer 1: Major Categories

| Code | Category | Dr/Cr Normal |
|------|----------|--------------|
| 1 | **Assets** | Debit |
| 2 | **Liabilities** | Credit |
| 3 | **Equity** | Credit |
| 4 | **Income** | Credit |
| 5 | **Expenses** | Debit |

### 4.4 Layer 2: Sub-Categories

| Code | Sub-Category | Parent |
|------|-------------|--------|
| 11 | Current Assets | 1 (Assets) |
| 12 | Fixed Assets | 1 (Assets) |
| 13 | Investments | 1 (Assets) |
| 14 | Other Assets | 1 (Assets) |
| 21 | Current Liabilities | 2 (Liabilities) |
| 22 | Long-term Liabilities | 2 (Liabilities) |
| 24 | Other Liabilities | 2 (Liabilities) |
| 31 | Share Capital | 3 (Equity) |
| 32 | Reserves | 3 (Equity) |
| 41 | Interest Income | 4 (Income) |
| 42 | Fee Income | 4 (Income) |
| 51 | Interest Expense | 5 (Expenses) |
| 52 | Operating Expenses | 5 (Expenses) |

### 4.5 Layer 3: Account Groups

| Code | Account Group | Parent |
|------|--------------|--------|
| 110 | Customer Deposits | 11 (Current Assets) |
| 210 | Customer Loans | 21 (Current Liabilities) |
| 220 | Nostro Accounts | 22 (Long-term Liabilities) |

### 4.6 Layer 4: Product Level

| Code | Product | Parent |
|------|---------|--------|
| 1101 | Savings Bank | 110 (Customer Deposits) |
| 1102 | Current Account | 110 (Customer Deposits) |
| 1103 | Term Deposit | 110 (Customer Deposits) |
| 2101 | Overdraft | 210 (Customer Loans) |
| 2102 | Term Loan | 210 (Customer Loans) |

### 4.7 Layer 5: Sub-Product Level

| Code | Sub-Product | Parent |
|------|------------|--------|
| 110101001 | SB - Regular | 1101 (Savings) |
| 110101002 | SB - Student | 1101 (Savings) |
| 110201001 | TD - 1 Year | 1103 (Term Deposit) |
| 210201001 | OD - Secured | 2101 (Overdraft) |

### 4.8 GL Balance Update Logic

**Entity:** `entity/GLBalance.java`
**Service:** `service/GLBalanceUpdateService.java`

**Key Fields:**
- `Id`: Auto-increment primary key
- `GL_Num`: GL account number (9 digits)
- `Tran_Date`: Transaction date
- `Opening_Bal`: Opening balance
- `DR_Summation`: Sum of debits
- `CR_Summation`: Sum of credits
- `Closing_Bal`: Closing balance
- `Current_Balance`: Real-time balance (same as Closing_Bal after EOD)

**Unique Constraint:** `(GL_Num, Tran_Date)`

### 4.9 Balance Calculation Rules

#### Assets & Expenses (GL starts with "1" or "5"):
```
Closing_Bal = Opening_Bal + DR_Summation - CR_Summation
```

**Example:**
```
GL: 110101001 (Savings - Asset from bank's perspective)
Opening_Bal: 100,000
DR_Summation: 20,000 (debits increase assets)
CR_Summation: 10,000 (credits decrease assets)
Closing_Bal: 100,000 + 20,000 - 10,000 = 110,000
```

#### Liabilities, Equity & Income (GL starts with "2", "3", or "4"):
```
Closing_Bal = Opening_Bal + CR_Summation - DR_Summation
```

**Example:**
```
GL: 210201001 (Overdraft - Liability)
Opening_Bal: 500,000
DR_Summation: 30,000 (debits decrease liabilities)
CR_Summation: 50,000 (credits increase liabilities)
Closing_Bal: 500,000 + 50,000 - 30,000 = 520,000
```

### 4.10 GL Movement Table

**Entity:** `entity/GLMovement.java`
**Service:** `service/GLMovementUpdateService.java`

**Purpose:** Records all GL account movements from transactions

**Fields:**
| Field | Description |
|-------|-------------|
| GL_Mov_ID | Auto-increment primary key |
| GL_Num | GL account number |
| Tran_Date | Transaction date |
| Tran_ID | Reference to Tran_Table |
| Dr_Cr_Flag | 'D' or 'C' |
| LCY_Amt | Amount in BDT |
| FCY_Amt | Amount in foreign currency |
| Ccy_Code | Currency code |
| Exchange_Rate | Exchange rate |

### 4.11 GL Movement Accrual Table

**Entity:** `entity/GLMovementAccrual.java`
**Service:** `service/InterestAccrualGLMovementService.java`

**Purpose:** Records GL movements from interest accruals (separate from transaction-based GL movements)

### 4.12 GL API Endpoints

```
GET    /api/gl/setup                     - Get GL chart of accounts
GET    /api/gl/setup/{glNum}             - Get GL details
POST   /api/gl/setup                     - Create GL account
PUT    /api/gl/setup/{glNum}             - Update GL account
GET    /api/gl/balance/{glNum}/{date}    - Get GL balance
GET    /api/gl/hierarchy                 - Get GL hierarchy tree
GET    /api/gl/movements/{glNum}         - Get GL movements
```

---

## 5. Interest Calculation & Accrual

### 5.1 Interest Rate Master

**Entity:** `entity/InterestRateMaster.java`
**Service:** `service/InterestRateService.java`

**Fields:**
| Field | Type | Description |
|-------|------|-------------|
| Intt_Code | String(10) | Interest rate code (PK) |
| Intt_Rate | BigDecimal | Interest rate (%) |
| Intt_Effctv_Date | LocalDate | Effective date |
| Description | String(100) | Rate description |

**Example:**
```
Intt_Code: SB_BASE
Intt_Rate: 4.00
Intt_Effctv_Date: 2025-01-01
Description: Savings Bank Base Rate
```

### 5.2 Interest Accrual (Batch Job 2)

**Entity:** `entity/InttAccrTran.java`
**Service:** `service/InterestAccrualService.java`
**Method:** `runEODAccruals(systemDate)`

### 5.3 Interest Calculation Formula

```
Accrued Interest = |Account Balance| × Interest Rate / 36500
```

**Why 36500?**
- **365 days** in a year
- **100** (to convert percentage to decimal)
- Combined: 365 × 100 = 36,500

### 5.4 Interest Rate Determination

#### A. Running Accounts & Asset Deal Accounts (OD, TL):

**Rate Source:** `Interest_Rate_Master` table

**Logic:**
1. Query for latest rate where `Intt_Effctv_Date <= System_Date`
2. **Effective Interest Rate (EIR)** = `Base_Rate` + `Interest_Increment`

**Example:**
```sql
SELECT Intt_Rate
FROM Interest_Rate_Master
WHERE Intt_Code = 'SB_BASE'
  AND Intt_Effctv_Date <= '2025-12-07'
ORDER BY Intt_Effctv_Date DESC
LIMIT 1;

Result: Base_Rate = 4.00%

Account's Interest_Increment = 0.50%
→ EIR = 4.00% + 0.50% = 4.50%
```

#### B. Liability Deal Accounts (TD, RD):

**Rate Source:** `Sub_Prod_Master.effective_interest_rate`

**Logic:**
- Rate is FIXED at account opening
- No daily lookup in `Interest_Rate_Master`

**Example:**
```
Account_No: 0011102010000000001 (TD)
Sub_Product_Id: 110201001
Sub_Prod_Master.effective_interest_rate: 6.50%
→ Use 6.50% for entire tenor
```

### 5.5 Interest Accrual Process

**For each active customer account:**

1. **Fetch Account Balance:**
   ```sql
   SELECT Current_Balance, Ccy_Code
   FROM Acct_Bal
   WHERE Account_No = ? AND Tran_Date = System_Date;
   ```

2. **Determine Interest Rate:**
   - Running/Asset: Lookup from `Interest_Rate_Master`
   - Liability Deal: Use `Sub_Prod_Master.effective_interest_rate`

3. **Calculate Accrued Interest:**
   ```java
   BigDecimal accruedInterest = accountBalance.abs()
       .multiply(interestRate)
       .divide(new BigDecimal("36500"), 2, RoundingMode.HALF_UP);
   ```

4. **Determine GL Accounts:**

   **For Liability Accounts (GL starts with "1"):**
   - **Debit GL:** `interest_receivable_expenditure_gl_num` (Expenditure)
   - **Credit GL:** `interest_income_payable_gl_num` (Payable)

   **For Asset Accounts (GL starts with "2"):**
   - **Debit GL:** `interest_receivable_expenditure_gl_num` (Receivable)
   - **Credit GL:** `interest_income_payable_gl_num` (Income)

5. **Create Interest Accrual Entries:**

   **Entry 1 (Debit):**
   ```java
   InttAccrTran debitEntry = InttAccrTran.builder()
       .accrualId(generateAccrualId() + "-1")
       .accountNo(accountNo)
       .tranDate(systemDate)
       .drCrFlag(DrCrFlag.D)
       .glNum(debitGLNum)
       .fcyAmt(accruedInterest)  // In account currency
       .exchangeRate(exchangeRate)
       .lcyAmt(accruedInterestBDT)  // Converted to BDT
       .ccyCode(accountCurrency)
       .status(AccrualStatus.Pending)
       .build();
   ```

   **Entry 2 (Credit):**
   ```java
   InttAccrTran creditEntry = InttAccrTran.builder()
       .accrualId(generateAccrualId() + "-2")
       .accountNo(accountNo)
       .tranDate(systemDate)
       .drCrFlag(DrCrFlag.C)
       .glNum(creditGLNum)
       .fcyAmt(accruedInterest)
       .exchangeRate(exchangeRate)
       .lcyAmt(accruedInterestBDT)
       .ccyCode(accountCurrency)
       .status(AccrualStatus.Pending)
       .build();
   ```

6. **Save to Database:**
   ```java
   inttAccrTranRepository.save(debitEntry);
   inttAccrTranRepository.save(creditEntry);
   ```

### 5.6 Accrual ID Generation

**Format:** `S + YYYYMMDD + 9-digit-sequential + -row-suffix`

**Example:**
```
System_Date: 2025-12-07
Sequential: 1
→ Debit Entry:  S20251207000000001-1
→ Credit Entry: S20251207000000001-2
```

### 5.7 Multi-Currency Interest Accrual

**Fields:**
- `FCY_Amt`: Interest in account's currency (USD, EUR, etc.)
- `Ccy_Code`: Account currency
- `Exchange_Rate`: Exchange rate from `fx_rate_master`
- `LCY_Amt`: Interest converted to BDT

**Example:**
```
Account Currency: USD
Account Balance: 10,000 USD
Interest Rate: 3.50%
Exchange Rate (USD/BDT): 110.00

Accrued Interest (FCY): 10,000 × 3.50 / 36500 = 0.96 USD
Accrued Interest (LCY): 0.96 × 110.00 = 105.48 BDT
```

### 5.8 Value Date Interest Integration

**Entity:** `entity/ValueDateInttAccr.java`
**Service:** `service/ValueDateInterestService.java`

**Purpose:** When a past-dated transaction is posted, delta interest is calculated and stored in `Value_Date_Intt_Accr` table. During Batch Job 2, these delta interests are added to the regular daily accrual.

**Example:**
```
Regular Accrual: 100.00 BDT
Value Date Delta: +15.50 BDT (from backdated transaction)
Total Accrued: 115.50 BDT
```

### 5.9 Interest Accrual GL Movement (Batch Job 3)

**Service:** `service/InterestAccrualGLMovementService.java`
**Method:** `processInterestAccrualGLMovements(systemDate)`

**Process:**
1. Fetch all `Pending` records from `Intt_Accr_Tran` for `System_Date`
2. For each accrual entry, create a record in `GL_Movement_Accrual`
3. Update status from `Pending` → `Posted`

**GL Movement Accrual Entry:**
```java
GLMovementAccrual glMovement = GLMovementAccrual.builder()
    .glNum(accrual.getGlNum())
    .tranDate(accrual.getTranDate())
    .accrualId(accrual.getAccrualId())
    .drCrFlag(accrual.getDrCrFlag())
    .lcyAmt(accrual.getLcyAmt())
    .fcyAmt(accrual.getFcyAmt())
    .ccyCode(accrual.getCcyCode())
    .exchangeRate(accrual.getExchangeRate())
    .build();
```

### 5.10 Interest Accrual Account Balance (Batch Job 6)

**Entity:** `entity/AcctBalAccrual.java`
**Service:** `service/InterestAccrualAccountBalanceService.java`
**Method:** `updateInterestAccrualAccountBalances(systemDate)`

**Purpose:** Track accumulated accrued interest per account (separate from actual account balance)

**Process:**
1. Fetch all `Posted` records from `GL_Movement_Accrual`
2. Group by `Account_No`
3. Aggregate:
   - Sum of debits (Dr_Cr_Flag = 'D')
   - Sum of credits (Dr_Cr_Flag = 'C')
4. Update or insert into `Acct_Bal_Accrual` table

**Acct_Bal_Accrual Record:**
```java
AcctBalAccrual accrualBalance = AcctBalAccrual.builder()
    .accountNo(accountNo)
    .tranDate(systemDate)
    .openingAccrual(previousDayClosingAccrual)
    .debitAccrual(sumOfDebits)
    .creditAccrual(sumOfCredits)
    .closingAccrual(openingAccrual + creditAccrual - debitAccrual)
    .build();
```

### 5.11 Interest API Endpoints

```
GET    /api/interest/rates                        - Get interest rates
POST   /api/interest/rates                        - Create interest rate
PUT    /api/interest/rates/{code}                 - Update interest rate
GET    /api/interest/accrual/{accountNo}          - Get accrued interest
POST   /api/interest/post-accrual                 - Post accrued interest
GET    /api/interest/accrual-balance/{accountNo}  - Get accrual balance
```

---

## 6. Transaction Processing

### 6.1 Transaction Entity

**Entity:** `entity/TranTable.java`
**Service:** `service/TransactionService.java`
**Controller:** `controller/TransactionController.java`
**Validation:** `service/TransactionValidationService.java`

### 6.2 Transaction Workflow (Maker-Checker Model)

```
┌─────────────┐
│   ENTRY     │  Maker creates transaction
│  (Status)   │  - No balance update
└──────┬──────┘  - Validation only
       │
       ▼
┌─────────────┐
│   POSTED    │  Maker/System posts transaction
│  (Status)   │  - Updates account balances
└──────┬──────┘  - Creates GL movements
       │
       ▼
┌─────────────┐
│  VERIFIED   │  Checker verifies transaction
│  (Status)   │  - Creates transaction history
└─────────────┘  - Available in SOA
```

### 6.3 Transaction Statuses

| Status | Description | Balance Updated? |
|--------|-------------|------------------|
| **Entry** | Draft transaction (Maker created) | No |
| **Posted** | Posted to ledgers | Yes |
| **Verified** | Checker approved | Yes (already done at Posted) |
| **Future** | Future-dated (Value_Date > System_Date) | No |

### 6.4 Transaction Fields

| Field | Type | Description |
|-------|------|-------------|
| Tran_ID | String(20) | Primary Key, Auto-generated |
| Account_No | String(16) | Customer account number |
| Tran_Date | LocalDate | Transaction date (= System_Date) |
| Value_Date | LocalDate | Value date (can be past/future) |
| Dr_Cr_Flag | Enum | 'D' (Debit) or 'C' (Credit) |
| FCY_Amt | BigDecimal | Amount in account currency |
| Ccy_Code | String(3) | Currency code |
| Exchange_Rate | BigDecimal | Exchange rate (default 1.0 for BDT) |
| LCY_Amt | BigDecimal | Amount in BDT |
| GL_Num | String(9) | GL account number |
| Narration | String(200) | Transaction description |
| Tran_Status | Enum | Entry/Posted/Verified/Future |
| Maker_Id | String(20) | Maker user ID |
| Verifier_Id | String(20) | Verifier user ID |
| Entry_Time | LocalDateTime | Entry timestamp |
| Post_Time | LocalDateTime | Post timestamp |
| Verify_Time | LocalDateTime | Verification timestamp |

### 6.5 Transaction ID Generation

**Service:** `TransactionIdService.generateTransactionId()`

**Format:** `T + YYYYMMDD + HHMMSSmmm + 4-digit-random`

**Example:**
```
Date: 2025-12-07
Time: 14:30:45.123
Random: 7856
→ Transaction ID: T202512071430451237856
```

### 6.6 Transaction Validation

**Service:** `TransactionValidationService.validateTransaction()`

**Validation Rules:**
1. **Debit = Credit**: Total debits must equal total credits
2. **Account Exists**: Account_No must exist in `Cust_Acct_Master`
3. **Account Active**: Account must not be closed
4. **GL Exists**: GL_Num must exist in `GL_Setup`
5. **Currency Match**: Ccy_Code must match account's currency (or allow exchange)
6. **Sufficient Balance**:
   - For debits: `Available_Balance >= FCY_Amt`
   - For credits: No check
7. **Loan Limit**: For asset accounts (OD, TL), check `Current_Balance + FCY_Amt <= Loan_Limit`
8. **Value Date**: Value_Date must be within allowed range

### 6.7 Transaction Creation (Entry Status)

**API:** `POST /api/transactions/entry`

**Service Method:** `createTransaction(CreateTransactionRequest request)`

**Process:**
1. Validate transaction using `TransactionValidationService`
2. Generate `Tran_ID`
3. Set `Tran_Status` = **Entry**
4. Set `Tran_Date` = System_Date
5. Set `Value_Date` (can be past/current/future)
6. Set `Maker_Id` = Current user
7. Set `Entry_Time` = System_DateTime
8. **DO NOT update balances**
9. Save to `Tran_Table`

**Example Request:**
```json
{
  "accountNo": "0011010000000001",
  "drCrFlag": "C",
  "fcyAmt": 5000.00,
  "ccyCode": "BDT",
  "glNum": "110101001",
  "narration": "Salary credit",
  "valueDate": "2025-12-07"
}
```

### 6.8 Transaction Posting (Posted Status)

**API:** `POST /api/transactions/{tranId}/post`

**Service Method:** `postTransaction(tranId)`

**Process:**
1. Fetch transaction by `Tran_ID`
2. Validate status = **Entry** or **Future**
3. Check `Value_Date`:
   - If `Value_Date > System_Date`: Set status = **Future**, STOP
   - If `Value_Date <= System_Date`: Continue posting
4. **Update Account Balance:**
   - Debit: `Current_Balance -= FCY_Amt`
   - Credit: `Current_Balance += FCY_Amt`
5. **Create GL Movement:**
   - Insert into `GL_Movement` table
   - Use `LCY_Amt` for GL balance (always BDT)
6. Set `Tran_Status` = **Posted**
7. Set `Post_Time` = System_DateTime
8. Save transaction

**Example:**
```java
// Update Account Balance
AcctBal accountBalance = acctBalRepository.findById(
    new AcctBalId(systemDate, transaction.getAccountNo())
).orElseThrow();

if (transaction.getDrCrFlag() == DrCrFlag.D) {
    accountBalance.setCurrentBalance(
        accountBalance.getCurrentBalance().subtract(transaction.getFcyAmt())
    );
} else {
    accountBalance.setCurrentBalance(
        accountBalance.getCurrentBalance().add(transaction.getFcyAmt())
    );
}
acctBalRepository.save(accountBalance);

// Create GL Movement
GLMovement glMovement = GLMovement.builder()
    .glNum(transaction.getGlNum())
    .tranDate(systemDate)
    .tranId(transaction.getTranId())
    .drCrFlag(transaction.getDrCrFlag())
    .lcyAmt(transaction.getLcyAmt())  // Always BDT for GL
    .fcyAmt(transaction.getFcyAmt())
    .ccyCode(transaction.getCcyCode())
    .exchangeRate(transaction.getExchangeRate())
    .build();
glMovementRepository.save(glMovement);
```

### 6.9 Transaction Verification (Verified Status)

**API:** `POST /api/transactions/{tranId}/verify`

**Service Method:** `verifyTransaction(tranId, verifierId)`

**Process:**
1. Fetch transaction by `Tran_ID`
2. Validate status = **Posted**
3. Validate `Verifier_Id` ≠ `Maker_Id` (Maker-Checker segregation)
4. Set `Tran_Status` = **Verified**
5. Set `Verifier_Id` = Current user
6. Set `Verify_Time` = System_DateTime
7. **Create Transaction History** (for Statement of Accounts):
   ```java
   TxnHistAcct historyEntry = TxnHistAcct.builder()
       .accountNo(transaction.getAccountNo())
       .tranDate(transaction.getTranDate())
       .valueDate(transaction.getValueDate())
       .tranId(transaction.getTranId())
       .drCrFlag(transaction.getDrCrFlag())
       .fcyAmt(transaction.getFcyAmt())
       .lcyAmt(transaction.getLcyAmt())
       .ccyCode(transaction.getCcyCode())
       .narration(transaction.getNarration())
       .balance(currentBalance)  // Running balance
       .build();
   txnHistAcctRepository.save(historyEntry);
   ```
8. Save transaction

### 6.10 Balance Update Logic

#### BDT Transactions (Ccy_Code = "BDT"):
- **Account Balance:** Use `LCY_Amt`
- **GL Balance:** Use `LCY_Amt`

#### FCY Transactions (Ccy_Code ≠ "BDT"):
- **Account Balance:** Use `FCY_Amt` (in account's currency)
- **GL Balance:** Use `LCY_Amt` (always in BDT)

**Example:**
```
Transaction:
  Account_No: 0012203010000000001 (USD account)
  Dr_Cr_Flag: C (Credit)
  FCY_Amt: 100 USD
  Exchange_Rate: 110.00
  LCY_Amt: 11,000 BDT

Account Balance Update:
  Current_Balance (USD): 5,000 + 100 = 5,100 USD

GL Balance Update:
  GL: 220302001 (Nostro USD)
  Closing_Bal (BDT): Previous + 11,000 BDT
```

### 6.11 Transaction Reversal

**API:** `POST /api/transactions/{tranId}/reverse`

**Service Method:** `reverseTransaction(tranId, reason)`

**Process:**
1. Fetch original transaction
2. Validate status = **Posted** or **Verified**
3. Create a new transaction with:
   - Same `Account_No`, `GL_Num`, `Narration`
   - **Opposite Dr_Cr_Flag** (D → C, C → D)
   - Same `FCY_Amt`, `LCY_Amt`
   - `Narration` = "REVERSAL: " + original narration + " - " + reason
4. Post the reversal transaction
5. Mark original transaction as reversed (add flag if needed)

### 6.12 Entry Deletion (Batch Job 1 - Step 0)

**Service:** `AccountBalanceUpdateService.executeAccountBalanceUpdate()`

**Logic:**
```java
// Delete ALL Entry status transactions (regardless of date)
int deletedCount = tranTableRepository.deleteByTranStatus(TranStatus.Entry);
log.info("Deleted {} Entry transactions", deletedCount);
```

**Reason:** Entry transactions are drafts and should not carry forward to the next day. They must be posted or deleted.

### 6.13 Transaction API Endpoints

```
POST   /api/transactions/entry             - Create transaction (Entry status)
POST   /api/transactions/{tranId}/post     - Post transaction
POST   /api/transactions/{tranId}/verify   - Verify transaction
POST   /api/transactions/{tranId}/reverse  - Reverse transaction
GET    /api/transactions/{tranId}          - Get transaction details
GET    /api/transactions/account/{accountNo} - Get account transactions
GET    /api/transactions                   - List transactions (paginated)
DELETE /api/transactions/{tranId}          - Delete Entry transaction
```

---

## 7. End of Day (EOD) Operations

### 7.1 EOD Overview

**EOD Process:** A sequence of 9 batch jobs that run at the end of each business day to:
- Update account and GL balances
- Calculate and post interest accruals
- Revalue foreign currency positions
- Generate financial reports
- Increment the system date

**Key Files:**
- Orchestration: `service/EODOrchestrationService.java`
- Job Management: `service/EODJobManagementService.java`
- Controller: `controller/EODJobController.java`
- Validation: `service/EODValidationService.java`
- Reporting: `service/EODReportingService.java`
- Logging: `entity/EODLogTable.java`

### 7.2 EOD Execution Models

#### A. Complete EOD (All 9 jobs in sequence):
**API:** `POST /api/admin/run-eod`
**Service:** `EODOrchestrationService.executeEOD(userId)`

#### B. Individual Job Execution:
**API:** `POST /api/admin/eod/jobs/execute/{jobNumber}`
**Service:** `EODJobManagementService.executeJob(jobNumber, userId)`

**Sequential Enforcement:**
- Job 2 can only run if Job 1 is completed
- Job 3 can only run if Job 2 is completed
- And so on...

### 7.3 EOD Log Table

**Entity:** `entity/EODLogTable.java`

**Purpose:** Track execution status of each EOD job

**Fields:**
| Field | Type | Description |
|-------|------|-------------|
| EOD_Log_Id | Long | Primary Key (Auto-increment) |
| EOD_Date | LocalDate | EOD processing date |
| Job_Name | String(50) | Job name |
| Start_Timestamp | LocalDateTime | Job start time |
| End_Timestamp | LocalDateTime | Job end time |
| System_Date | LocalDate | System date at execution |
| User_ID | String(20) | User who initiated |
| Records_Processed | Integer | Number of records processed |
| Status | Enum | Running/Success/Failed |
| Error_Message | Text | Error details (if failed) |
| Failed_At_Step | String(500) | Where it failed |

**Statuses:**
- **Running**: Job is currently executing
- **Success**: Job completed successfully
- **Failed**: Job failed with errors

### 7.4 Pre-EOD Validations

**Service:** `EODValidationService.performPreEODValidations()`

**Validations:**
1. **No Entry Transactions:**
   - Check for any transactions with status = Entry
   - Error: "Entry transactions exist. Post or delete them before EOD."

2. **All Transactions Verified:**
   - Check for Posted transactions not yet Verified
   - Warning: "Unverified transactions exist."

3. **GL Balancing:**
   - Sum of all debit GL balances = Sum of all credit GL balances
   - Error: "GL accounts are out of balance."

4. **Previous EOD Completed:**
   - Check if previous day's EOD was successful
   - Error: "Previous EOD not completed."

### 7.5 Batch Job Details

#### **Batch Job 1: Account Balance Update**

**Service:** `AccountBalanceUpdateService.executeAccountBalanceUpdate()`

**Purpose:** Update customer account balances based on day's transactions

**Process:**

**STEP 0: Delete Entry Transactions**
```java
// Delete ALL Entry status transactions (regardless of date)
List<TranTable> entryTransactions = tranTableRepository
    .findByTranStatus(TranStatus.Entry);

log.info("Found {} Entry transactions to delete", entryTransactions.size());

int deletedCount = tranTableRepository.deleteByTranStatus(TranStatus.Entry);

log.info("Deleted {} Entry transactions", deletedCount);
```

**STEP 1: Update Account Balances**
```java
// For each customer account
for (CustAcctMaster account : accounts) {
    // Aggregate transactions for System_Date
    BigDecimal drSum = tranTableRepository
        .sumByAccountAndDateAndDrCr(account.getAccountNo(), systemDate, DrCrFlag.D);

    BigDecimal crSum = tranTableRepository
        .sumByAccountAndDateAndDrCr(account.getAccountNo(), systemDate, DrCrFlag.C);

    // Get or create Acct_Bal record
    AcctBal acctBal = acctBalRepository
        .findById(new AcctBalId(systemDate, account.getAccountNo()))
        .orElse(createNewAcctBal(account, systemDate));

    // Update balances
    acctBal.setDrSummation(drSum);
    acctBal.setCrSummation(crSum);

    // Calculate closing balance
    BigDecimal closingBal = acctBal.getOpeningBal()
        .add(crSum)  // Credits increase balance
        .subtract(drSum);  // Debits decrease balance

    acctBal.setClosingBal(closingBal);
    acctBal.setCurrentBalance(closingBal);
    acctBal.setAvailableBalance(calculateAvailableBalance(account, closingBal));

    acctBalRepository.save(acctBal);
}
```

**Records Processed:** Number of account balances updated

---

#### **Batch Job 2: Interest Accrual Transaction Update**

**Service:** `InterestAccrualService.runEODAccruals()`

**Purpose:** Calculate daily interest accrual for all active accounts

**Process:** (Detailed in Section 5.5)

**Records Processed:** Number of accrual entries created (× 2, since each account gets 2 entries: debit + credit)

---

#### **Batch Job 3: Interest Accrual GL Movement Update**

**Service:** `InterestAccrualGLMovementService.processInterestAccrualGLMovements()`

**Purpose:** Post interest accruals to GL Movement Accrual table

**Process:**
```java
// Fetch all Pending accruals for System_Date
List<InttAccrTran> pendingAccruals = inttAccrTranRepository
    .findByTranDateAndStatus(systemDate, AccrualStatus.Pending);

for (InttAccrTran accrual : pendingAccruals) {
    // Create GL Movement Accrual entry
    GLMovementAccrual glMovement = GLMovementAccrual.builder()
        .glNum(accrual.getGlNum())
        .tranDate(accrual.getTranDate())
        .accrualId(accrual.getAccrualId())
        .drCrFlag(accrual.getDrCrFlag())
        .lcyAmt(accrual.getLcyAmt())
        .fcyAmt(accrual.getFcyAmt())
        .ccyCode(accrual.getCcyCode())
        .exchangeRate(accrual.getExchangeRate())
        .build();

    glMovementAccrualRepository.save(glMovement);

    // Update status to Posted
    accrual.setStatus(AccrualStatus.Posted);
    inttAccrTranRepository.save(accrual);
}
```

**Records Processed:** Number of GL movement accrual entries created

---

#### **Batch Job 4: GL Movement Update**

**Service:** `GLMovementUpdateService.processGLMovements()`

**Purpose:** Post verified transactions to GL Movement table

**Process:**
```java
// Fetch all Verified transactions for System_Date
List<TranTable> verifiedTransactions = tranTableRepository
    .findByTranDateAndTranStatus(systemDate, TranStatus.Verified);

for (TranTable transaction : verifiedTransactions) {
    // Check if GL Movement already exists
    if (glMovementRepository.existsByTranId(transaction.getTranId())) {
        continue;  // Skip if already posted
    }

    // Create GL Movement entry
    GLMovement glMovement = GLMovement.builder()
        .glNum(transaction.getGlNum())
        .tranDate(systemDate)
        .tranId(transaction.getTranId())
        .drCrFlag(transaction.getDrCrFlag())
        .lcyAmt(transaction.getLcyAmt())
        .fcyAmt(transaction.getFcyAmt())
        .ccyCode(transaction.getCcyCode())
        .exchangeRate(transaction.getExchangeRate())
        .build();

    glMovementRepository.save(glMovement);
}
```

**Records Processed:** Number of GL movement entries created

---

#### **Batch Job 5: GL Balance Update**

**Service:** `GLBalanceUpdateService.updateGLBalances()`

**Purpose:** Update GL account balances based on GL movements

**Process:**
```java
// Fetch distinct GL numbers from GL_Movement and GL_Movement_Accrual for System_Date
List<String> glNumbers = new ArrayList<>();
glNumbers.addAll(glMovementRepository.findDistinctGLNumbersByTranDate(systemDate));
glNumbers.addAll(glMovementAccrualRepository.findDistinctGLNumbersByAccrualDate(systemDate));

Set<String> uniqueGLNumbers = new HashSet<>(glNumbers);

for (String glNum : uniqueGLNumbers) {
    // Aggregate movements from both tables
    BigDecimal drSumMovement = glMovementRepository
        .sumByGLNumAndDateAndDrCr(glNum, systemDate, DrCrFlag.D);
    BigDecimal crSumMovement = glMovementRepository
        .sumByGLNumAndDateAndDrCr(glNum, systemDate, DrCrFlag.C);

    BigDecimal drSumAccrual = glMovementAccrualRepository
        .sumByGLNumAndDateAndDrCr(glNum, systemDate, DrCrFlag.D);
    BigDecimal crSumAccrual = glMovementAccrualRepository
        .sumByGLNumAndDateAndDrCr(glNum, systemDate, DrCrFlag.C);

    BigDecimal totalDrSum = drSumMovement.add(drSumAccrual);
    BigDecimal totalCrSum = crSumMovement.add(crSumAccrual);

    // Get or create GL_Balance record
    GLBalance glBalance = glBalanceRepository
        .findByGlNumAndTranDate(glNum, systemDate)
        .orElse(createNewGLBalance(glNum, systemDate));

    // Update summations
    glBalance.setDrSummation(totalDrSum);
    glBalance.setCrSummation(totalCrSum);

    // Calculate closing balance based on GL type
    GLSetup glSetup = glSetupRepository.findById(glNum).orElseThrow();

    BigDecimal closingBal;
    if (isAssetOrExpense(glSetup)) {
        // Asset/Expense: Debit increases, Credit decreases
        closingBal = glBalance.getOpeningBal()
            .add(totalDrSum)
            .subtract(totalCrSum);
    } else {
        // Liability/Equity/Income: Credit increases, Debit decreases
        closingBal = glBalance.getOpeningBal()
            .add(totalCrSum)
            .subtract(totalDrSum);
    }

    glBalance.setClosingBal(closingBal);
    glBalance.setCurrentBalance(closingBal);

    glBalanceRepository.save(glBalance);
}
```

**Important:** This job ONLY processes GL accounts that have movements on the current day. It does NOT update all GL accounts.

**Records Processed:** Number of GL balances updated

---

#### **Batch Job 6: Interest Accrual Account Balance Update**

**Service:** `InterestAccrualAccountBalanceService.updateInterestAccrualAccountBalances()`

**Purpose:** Update interest accrual balances per account

**Process:**
```java
// Fetch all Posted GL Movement Accrual records for System_Date
List<GLMovementAccrual> postedAccruals = glMovementAccrualRepository
    .findByTranDate(systemDate);

// Group by Account_No
Map<String, List<GLMovementAccrual>> groupedByAccount = postedAccruals.stream()
    .collect(Collectors.groupingBy(GLMovementAccrual::getAccountNo));

for (Map.Entry<String, List<GLMovementAccrual>> entry : groupedByAccount.entrySet()) {
    String accountNo = entry.getKey();
    List<GLMovementAccrual> accruals = entry.getValue();

    // Sum debits and credits
    BigDecimal debitSum = accruals.stream()
        .filter(a -> a.getDrCrFlag() == DrCrFlag.D)
        .map(GLMovementAccrual::getLcyAmt)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal creditSum = accruals.stream()
        .filter(a -> a.getDrCrFlag() == DrCrFlag.C)
        .map(GLMovementAccrual::getLcyAmt)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Get or create Acct_Bal_Accrual record
    AcctBalAccrual accrualBalance = acctBalAccrualRepository
        .findById(new AcctBalAccrualId(systemDate, accountNo))
        .orElse(createNewAcctBalAccrual(accountNo, systemDate));

    // Update accrual balances
    accrualBalance.setDebitAccrual(debitSum);
    accrualBalance.setCreditAccrual(creditSum);

    BigDecimal closingAccrual = accrualBalance.getOpeningAccrual()
        .add(creditSum)
        .subtract(debitSum);

    accrualBalance.setClosingAccrual(closingAccrual);

    acctBalAccrualRepository.save(accrualBalance);
}
```

**Records Processed:** Number of accrual balances updated

---

#### **Batch Job 7: MCT Revaluation (Foreign Currency Revaluation)**

**Service:** `RevaluationService.performEodRevaluation()`

**Purpose:** Revalue foreign currency positions at end of day and post unrealized gain/loss

**Process:** (Detailed in Section 9.7)

**Key Steps:**
1. Revalue FCY GL accounts (Nostro: USD, EUR, GBP, JPY)
2. Revalue FCY customer accounts
3. Calculate revaluation difference (MTM_LCY - Booked_LCY)
4. Post unrealized gain/loss entries to Tran_Table
5. Save revaluation details to `reval_tran` table

**Records Processed:** Number of revaluation entries posted

---

#### **Batch Job 8: Financial Reports Generation**

**Service:** `FinancialReportsService.generateFinancialReports()`

**Purpose:** Generate financial reports for the day

**Reports Generated:**
1. **Trial Balance:** GL-level summary of all balances
2. **Balance Sheet:** Formatted by GL hierarchy (5 layers)
3. **Subproduct GL Balance Report:** Subproduct-wise balance summary

**Process:**
```java
public Map<String, String> generateFinancialReports(LocalDate systemDate) {
    String reportDateStr = systemDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    Map<String, String> result = new HashMap<>();
    result.put("success", "true");
    result.put("reportDate", reportDateStr);
    result.put("message", "Reports can be downloaded on-demand");

    // Reports are generated on-demand when user clicks download
    // Files: TrialBalance_{date}.csv, BalanceSheet_{date}.xlsx, SubproductGLBalance_{date}.csv

    return result;
}
```

**Records Processed:** 3 (number of report types)

**Download APIs:**
```
GET /api/admin/eod/batch-job-8/download/trial-balance/{date}
GET /api/admin/eod/batch-job-8/download/balance-sheet/{date}
GET /api/admin/eod/batch-job-8/download/subproduct-gl-balance/{date}
```

---

#### **Batch Job 9: System Date Increment**

**Service:** `EODOrchestrationService.executeBatchJob9()`

**Purpose:** Increment the system date by 1 day after successful EOD

**Process:**
```java
public boolean executeBatchJob9(LocalDate eodDate, LocalDate systemDate, String userId) {
    log.info("Starting Batch Job 9: System Date Increment");

    try {
        // Calculate new system date
        LocalDate newSystemDate = systemDate.plusDays(1);

        // Update Parameter_Table
        updateSystemDate(newSystemDate, userId);

        // Log success
        logEODJob(eodDate, "System Date Increment", systemDate, userId, 1,
                 EODLogTable.EODStatus.Success, null, "Completed");

        log.info("System date incremented from {} to {}", systemDate, newSystemDate);
        return true;

    } catch (Exception e) {
        log.error("Batch Job 9 failed: {}", e.getMessage(), e);
        throw new RuntimeException("Batch Job 9 failed: " + e.getMessage(), e);
    }
}

private void updateSystemDate(LocalDate newSystemDate, String userId) {
    // Update System_Date parameter
    Optional<ParameterTable> systemDateParam = parameterTableRepository
        .findByParameterName("System_Date");

    if (systemDateParam.isPresent()) {
        ParameterTable param = systemDateParam.get();
        param.setParameterValue(newSystemDate.toString());
        param.setUpdatedBy(userId);
        param.setLastUpdated(systemDateService.getSystemDateTime());
        parameterTableRepository.save(param);
    }

    // Update Last_EOD_Date, Last_EOD_Timestamp, Last_EOD_User
    updateParameter("Last_EOD_Date", newSystemDate.toString(), userId);
    updateParameter("Last_EOD_Timestamp", systemDateService.getSystemDateTime().toString(), userId);
    updateParameter("Last_EOD_User", userId, userId);
}
```

**Records Processed:** 1

---

### 7.6 EOD Job Dependencies

```
Job 1 (Account Balance Update)
  └─ Prerequisite for → Job 2

Job 2 (Interest Accrual)
  └─ Prerequisite for → Job 3

Job 3 (Interest GL Movement)
  └─ Prerequisite for → Job 4

Job 4 (GL Movement Update)
  └─ Prerequisite for → Job 5

Job 5 (GL Balance Update)
  └─ Prerequisite for → Job 6

Job 6 (Interest Accrual Balance)
  └─ Prerequisite for → Job 7

Job 7 (MCT Revaluation)
  └─ Prerequisite for → Job 8

Job 8 (Financial Reports)
  └─ Prerequisite for → Job 9

Job 9 (System Date Increment)
  └─ Completes EOD cycle
```

### 7.7 EOD API Endpoints

```
POST   /api/admin/run-eod                          - Run complete EOD (all 9 jobs)
POST   /api/admin/eod/jobs/execute/{jobNumber}    - Run individual job
GET    /api/admin/eod/jobs/status                 - Get all job statuses
GET    /api/admin/eod/jobs/can-execute/{jobNumber} - Check if job can run
GET    /api/admin/eod/jobs/{jobNumber}            - Get job details
POST   /api/admin/eod/validate                    - Run pre-EOD validations
GET    /api/admin/eod/status                      - Get EOD status
```

---

## 8. Value Dating

### 8.1 Value Dating Overview

**Value Dating** allows transactions to have a different **value date** than the **transaction date**:
- **Transaction Date (Tran_Date):** Always = System_Date (when transaction is entered)
- **Value Date:** Can be past, current, or future

**Use Cases:**
1. **Backdated Transactions:** Correct past mistakes
2. **Future-dated Transactions:** Schedule payments for future dates
3. **Delayed Value:** Transaction booked today but valued tomorrow

### 8.2 Value Date Types

| Type | Condition | Processing |
|------|-----------|------------|
| **CURRENT** | Value_Date = System_Date | Normal processing, immediate balance update |
| **PAST** | Value_Date < System_Date | Post immediately, calculate delta interest |
| **FUTURE** | Value_Date > System_Date | Set status = Future, no balance update until BOD |

### 8.3 Key Files

**Entities:**
- `entity/TranValueDateLog.java` - Value date transaction log
- `entity/ValueDateInttAccr.java` - Value date interest accruals

**Services:**
- `service/ValueDateValidationService.java` - Validation
- `service/ValueDateCalculationService.java` - Calculations
- `service/ValueDatePostingService.java` - Posting logic
- `service/ValueDateInterestService.java` - Interest adjustments
- `service/BODValueDateService.java` - Beginning of Day processing

### 8.4 Value Date Validation

**Service:** `ValueDateValidationService.validateValueDate()`

**Rules:**
1. **Date Range:**
   - `Value_Date >= System_Date - 7 days` (max 7 days backdated)
   - `Value_Date <= System_Date + 365 days` (max 1 year future)

2. **Account Status:**
   - Account must be active on Value_Date
   - `Date_Open <= Value_Date <= Date_Close` (or Date_Close = NULL)

3. **Sufficient Balance:**
   - For past-dated debits, check historical balance was sufficient

### 8.5 Past-Dated Transactions

**Example:**
```
System_Date: 2025-12-07
Transaction:
  Value_Date: 2025-12-05 (2 days ago)
  Account_No: 0011010000000001
  Dr_Cr_Flag: C (Credit)
  Amount: 10,000 BDT
```

**Process:**
1. **Post Transaction Immediately:**
   - Update `Current_Balance` on System_Date (2025-12-07)
   - Create GL movement

2. **Calculate Delta Interest:**
   - Interest should have been accrued for 2 days (Dec 5-6)
   - Calculate: `Delta_Interest = Amount × Rate × 2 / 36500`

3. **Post Delta Interest:**
   - Create entry in `Value_Date_Intt_Accr` table
   - Will be added to regular accrual in next EOD Batch Job 2

**Delta Interest Calculation:**
```java
public BigDecimal calculateDeltaInterest(
    LocalDate valueDate,
    LocalDate systemDate,
    BigDecimal amount,
    BigDecimal interestRate
) {
    // Calculate number of days between value date and system date
    long daysDiff = ChronoUnit.DAYS.between(valueDate, systemDate);

    if (daysDiff <= 0) {
        return BigDecimal.ZERO;  // No delta for current/future dates
    }

    // Calculate delta interest
    BigDecimal deltaInterest = amount
        .multiply(interestRate)
        .multiply(new BigDecimal(daysDiff))
        .divide(new BigDecimal("36500"), 2, RoundingMode.HALF_UP);

    return deltaInterest;
}
```

**Value Date Interest Entry:**
```java
ValueDateInttAccr deltaAccrual = ValueDateInttAccr.builder()
    .accountNo(transaction.getAccountNo())
    .tranDate(systemDate)
    .valueDate(transaction.getValueDate())
    .tranId(transaction.getTranId())
    .deltaInterest(deltaInterest)
    .numberOfDays((int) daysDiff)
    .interestRate(interestRate)
    .status(ValueDateStatus.Pending)
    .build();

valueDateInttAccrRepository.save(deltaAccrual);
```

### 8.6 Future-Dated Transactions

**Example:**
```
System_Date: 2025-12-07
Transaction:
  Value_Date: 2025-12-15 (8 days future)
  Account_No: 0011010000000001
  Dr_Cr_Flag: D (Debit)
  Amount: 5,000 BDT
```

**Process:**
1. **Create Transaction with Status = Future:**
   ```java
   TranTable futureTransaction = TranTable.builder()
       .tranId(generateTranId())
       .accountNo(accountNo)
       .tranDate(systemDate)  // 2025-12-07
       .valueDate(valueDate)  // 2025-12-15
       .drCrFlag(DrCrFlag.D)
       .fcyAmt(new BigDecimal("5000"))
       .tranStatus(TranStatus.Future)  // Status = Future
       .build();

   tranTableRepository.save(futureTransaction);
   ```

2. **DO NOT Update Balance:**
   - `Acct_Bal` remains unchanged
   - `GL_Balance` remains unchanged

3. **Wait for BOD Processing:**
   - On 2025-12-15 BOD, transaction will be posted automatically

### 8.7 Beginning of Day (BOD) Processing

**Service:** `BODValueDateService.processFutureDatedTransactions()`

**Execution:** Runs at the start of each business day (before any user transactions)

**Process:**
```java
public BODResult processFutureDatedTransactions() {
    LocalDate systemDate = systemDateService.getSystemDate();

    log.info("Starting BOD processing for system date: {}", systemDate);

    // Find all Future transactions where Value_Date <= System_Date
    List<TranTable> futureTransactions = tranTableRepository
        .findByTranStatusAndValueDateLessThanEqual(TranStatus.Future, systemDate);

    log.info("Found {} future-dated transactions to process", futureTransactions.size());

    int processedCount = 0;

    for (TranTable transaction : futureTransactions) {
        try {
            // Post the transaction
            transactionService.postTransaction(transaction.getTranId());

            // Update status from Future → Posted
            transaction.setTranStatus(TranStatus.Posted);
            transaction.setPostTime(systemDateService.getSystemDateTime());
            tranTableRepository.save(transaction);

            processedCount++;

        } catch (Exception e) {
            log.error("Failed to process future transaction {}: {}",
                     transaction.getTranId(), e.getMessage());
        }
    }

    log.info("BOD processing completed. {} transactions processed", processedCount);

    return BODResult.builder()
        .systemDate(systemDate)
        .pendingCountBefore(futureTransactions.size())
        .processedCount(processedCount)
        .status("Success")
        .build();
}
```

### 8.8 Value Date Interest Integration

**During EOD Batch Job 2 (Interest Accrual):**
```java
// Calculate regular daily interest
BigDecimal regularInterest = calculateDailyInterest(accountNo, systemDate);

// Fetch delta interest from Value_Date_Intt_Accr
BigDecimal deltaInterest = valueDateInttAccrRepository
    .sumDeltaInterestByAccountAndDate(accountNo, systemDate)
    .orElse(BigDecimal.ZERO);

// Total accrued interest
BigDecimal totalInterest = regularInterest.add(deltaInterest);

// Post total interest
postInterestAccrual(accountNo, totalInterest);

// Update delta interest status to Posted
valueDateInttAccrRepository.updateStatusByAccountAndDate(
    accountNo, systemDate, ValueDateStatus.Posted
);
```

### 8.9 Value Date Log

**Entity:** `entity/TranValueDateLog.java`

**Purpose:** Audit trail for all value-dated transactions

**Fields:**
| Field | Description |
|-------|-------------|
| Log_Id | Auto-increment primary key |
| Tran_ID | Reference to Tran_Table |
| Account_No | Customer account |
| Tran_Date | Transaction date |
| Value_Date | Value date |
| Value_Date_Type | CURRENT/PAST/FUTURE |
| Delta_Interest | Delta interest (if past-dated) |
| Original_Balance | Balance before transaction |
| New_Balance | Balance after transaction |
| Posted_At | When transaction was posted |
| Processed_At | When BOD processed it (for future) |

### 8.10 Value Date API Endpoints

```
POST   /api/transactions/value-date           - Create value-dated transaction
GET    /api/bod/status                        - Get BOD status
POST   /api/bod/run                           - Run BOD processing manually
GET    /api/bod/pending                       - Get pending future transactions
GET    /api/value-date/delta-interest/{accountNo} - Get delta interest
```

---

## 9. Multi-Currency Transactions (MCT)

### 9.1 Multi-Currency Overview

The system supports **multi-currency transactions** with:
- Foreign currency accounts (USD, EUR, GBP, JPY, etc.)
- Real-time exchange rates
- Currency conversion
- Foreign currency revaluation (mark-to-market)
- Settlement gain/loss tracking

### 9.2 Supported Currencies

**Base Currency:** BDT (Bangladeshi Taka)

**Foreign Currencies:**
- USD (US Dollar)
- EUR (Euro)
- GBP (British Pound)
- JPY (Japanese Yen)
- And more...

### 9.3 Key Files

**Entities:**
- `entity/FxRateMaster.java` - Exchange rates
- `entity/WaeMaster.java` - Weighted Average Exchange rates
- `entity/RevalTran.java` - Revaluation transactions
- `entity/RevalSummary.java` - Revaluation summary
- `entity/SettlementGainLoss.java` - Settlement gain/loss
- `entity/CurrencyMaster.java` - Currency master data

**Services:**
- `service/ExchangeRateService.java` - Exchange rate management
- `service/RevaluationService.java` - FCY revaluation
- `service/MultiCurrencyTransactionService.java` - MCT processing
- `service/SettlementReportService.java` - Settlement reports
- `service/CurrencyValidationService.java` - Currency validation

**Controllers:**
- `controller/ExchangeRateController.java`
- `controller/MultiCurrencyTransactionController.java`

### 9.4 Exchange Rate Master

**Entity:** `entity/FxRateMaster.java`

**Fields:**
| Field | Type | Description |
|-------|------|-------------|
| Rate_Id | Long | Primary Key (Auto-increment) |
| Ccy_Pair | String(10) | Currency pair (e.g., "USD/BDT") |
| Mid_Rate | BigDecimal | Mid-market rate |
| Buying_Rate | BigDecimal | Bank buying rate |
| Selling_Rate | BigDecimal | Bank selling rate |
| Effective_Date | LocalDate | Rate effective date |
| Effective_Time | LocalTime | Rate effective time |
| Created_By | String(20) | User who created rate |

**Example:**
```
Ccy_Pair: USD/BDT
Mid_Rate: 110.00
Buying_Rate: 109.50 (bank buys USD at this rate)
Selling_Rate: 110.50 (bank sells USD at this rate)
Effective_Date: 2025-12-07
Effective_Time: 09:00:00
```

### 9.5 Weighted Average Exchange (WAE) Master

**Entity:** `entity/WaeMaster.java`

**Purpose:** Track weighted average exchange rate for each currency position

**Fields:**
| Field | Description |
|-------|-------------|
| Ccy_Pair | Currency pair (PK) |
| WAE_Rate | Weighted average exchange rate |
| FCY_Balance | Foreign currency balance |
| LCY_Balance | Local currency equivalent (Booked_LCY) |
| Last_Updated | Last update timestamp |

**Example:**
```
Ccy_Pair: USD/BDT
WAE_Rate: 109.75 (weighted average of all USD purchases)
FCY_Balance: 100,000 USD
LCY_Balance: 10,975,000 BDT (100,000 × 109.75)
```

**WAE Calculation:**
```
New WAE = (Old_LCY_Balance + New_Transaction_LCY) / (Old_FCY_Balance + New_Transaction_FCY)
```

**Example:**
```
Existing Position:
  FCY: 50,000 USD
  LCY: 5,500,000 BDT
  WAE: 110.00

New Transaction:
  Buy 20,000 USD at 109.00
  LCY: 2,180,000 BDT

Updated Position:
  FCY: 50,000 + 20,000 = 70,000 USD
  LCY: 5,500,000 + 2,180,000 = 7,680,000 BDT
  WAE: 7,680,000 / 70,000 = 109.71
```

### 9.6 Multi-Currency Transaction Types

#### A. Same Currency (No MCT):
```
BDT → BDT: No currency conversion
USD → USD: No currency conversion
```

#### B. Currency Exchange (BDT ↔ FCY):
```
BDT → USD: Convert BDT to USD
USD → BDT: Convert USD to BDT
USD → EUR: Convert USD to EUR via BDT
```

### 9.7 Foreign Currency Revaluation (Batch Job 7)

**Service:** `RevaluationService.performEodRevaluation()`

**Purpose:** Mark-to-market revaluation of all foreign currency positions at EOD

**Process:**

#### Step 1: Revalue GL Accounts (Nostro Accounts)

**Nostro GL Accounts:**
- **220302001:** Nostro USD
- **220303001:** Nostro EUR
- **220304001:** Nostro GBP
- **220305001:** Nostro JPY

**For each FCY GL account:**
```java
// 1. Get current position from WAE_Master
WaeMaster waePosition = waeMasterRepository
    .findByCcyPair(currency + "/BDT")
    .orElseThrow();

BigDecimal fcyBalance = waePosition.getFcyBalance();
BigDecimal bookedLCY = waePosition.getLcyBalance();  // Historical cost

// 2. Get current exchange rate
FxRateMaster currentRate = fxRateMasterRepository
    .findLatestByCcyPair(currency + "/BDT", systemDate)
    .orElseThrow();

BigDecimal mtmRate = currentRate.getMidRate();  // Mark-to-market rate

// 3. Calculate MTM value
BigDecimal mtmLCY = fcyBalance.multiply(mtmRate);

// 4. Calculate revaluation difference
BigDecimal revalDiff = mtmLCY.subtract(bookedLCY);

// 5. Determine gain or loss
boolean isGain = revalDiff.compareTo(BigDecimal.ZERO) > 0;

// 6. Determine GL accounts
String gainLossGL = isGain ? "140203002" : "240203002";  // Unrealized Gain/Loss GL
String fcyGL = glNum;  // Nostro GL

// 7. Post revaluation entry
if (revalDiff.abs().compareTo(BigDecimal.ZERO) > 0) {
    // Create debit and credit entries
    TranTable debitEntry = createRevalEntry(
        isGain ? fcyGL : gainLossGL,
        DrCrFlag.D,
        revalDiff.abs(),
        currency
    );

    TranTable creditEntry = createRevalEntry(
        isGain ? gainLossGL : fcyGL,
        DrCrFlag.C,
        revalDiff.abs(),
        currency
    );

    tranTableRepository.save(debitEntry);
    tranTableRepository.save(creditEntry);
}

// 8. Save revaluation record
RevalTran revalRecord = RevalTran.builder()
    .revalDate(systemDate)
    .glNum(glNum)
    .fcyBalance(fcyBalance)
    .ccyCode(currency)
    .waeRate(waePosition.getWaeRate())
    .mtmRate(mtmRate)
    .bookedLCY(bookedLCY)
    .mtmLCY(mtmLCY)
    .revalDiff(revalDiff)
    .gainLossType(isGain ? "Gain" : "Loss")
    .build();

revalTranRepository.save(revalRecord);
```

**Example:**
```
GL Account: 220302001 (Nostro USD)

From WAE_Master:
  FCY_Balance: 100,000 USD
  Booked_LCY: 10,975,000 BDT (historical cost at WAE 109.75)

Current Exchange Rate:
  Mid_Rate: 110.50 BDT/USD

MTM Calculation:
  MTM_LCY: 100,000 × 110.50 = 11,050,000 BDT
  Reval_Diff: 11,050,000 - 10,975,000 = 75,000 BDT (Gain)

Revaluation Entry:
  Dr: 220302001 (Nostro USD) - 75,000 BDT
  Cr: 140203002 (Unrealized Gain) - 75,000 BDT
```

#### Step 2: Revalue Customer FCY Accounts

**For each customer account in FCY:**
```java
// 1. Get account details
CustAcctMaster account = custAcctMasterRepository.findById(accountNo).orElseThrow();

if (account.getCcyCode().equals("BDT")) {
    continue;  // Skip BDT accounts
}

// 2. Get account balance
AcctBal balance = acctBalRepository
    .findById(new AcctBalId(systemDate, accountNo))
    .orElseThrow();

BigDecimal fcyBalance = balance.getCurrentBalance();

// 3. Get current exchange rate
FxRateMaster rate = fxRateMasterRepository
    .findLatestByCcyPair(account.getCcyCode() + "/BDT", systemDate)
    .orElseThrow();

BigDecimal mtmRate = rate.getMidRate();

// 4. Calculate MTM value
BigDecimal mtmLCY = fcyBalance.multiply(mtmRate);

// 5. Get previous day's MTM (for comparison)
Optional<RevalTran> previousReval = revalTranRepository
    .findTopByAccountNoOrderByRevalDateDesc(accountNo);

BigDecimal bookedLCY;
if (previousReval.isEmpty()) {
    // First revaluation: Booked_LCY = MTM_LCY (baseline)
    bookedLCY = mtmLCY;
} else {
    // Subsequent: Booked_LCY = Previous day's MTM_LCY
    bookedLCY = previousReval.get().getMtmLCY();
}

// 6. Calculate revaluation difference
BigDecimal revalDiff = mtmLCY.subtract(bookedLCY);

// 7. Post revaluation entry (if difference exists)
// ... similar to GL account revaluation ...

// 8. Save revaluation record
RevalTran revalRecord = RevalTran.builder()
    .revalDate(systemDate)
    .accountNo(accountNo)
    .glNum(account.getGlNum())
    .fcyBalance(fcyBalance)
    .ccyCode(account.getCcyCode())
    .mtmRate(mtmRate)
    .bookedLCY(bookedLCY)
    .mtmLCY(mtmLCY)
    .revalDiff(revalDiff)
    .gainLossType(revalDiff.compareTo(BigDecimal.ZERO) >= 0 ? "Gain" : "Loss")
    .build();

revalTranRepository.save(revalRecord);
```

**Example:**
```
Customer Account: 0012203010000000001 (USD Savings)

Day 1 (First Revaluation):
  FCY_Balance: 50,000 USD
  MTM_Rate: 110.00 BDT/USD
  MTM_LCY: 5,500,000 BDT
  Booked_LCY: 5,500,000 BDT (baseline)
  Reval_Diff: 0 (no gain/loss)

Day 2:
  FCY_Balance: 50,000 USD
  MTM_Rate: 110.50 BDT/USD
  MTM_LCY: 5,525,000 BDT
  Booked_LCY: 5,500,000 BDT (previous day's MTM)
  Reval_Diff: 25,000 BDT (Gain)

  Entry:
    Dr: Customer GL - 25,000 BDT
    Cr: Unrealized Gain GL - 25,000 BDT
```

### 9.8 Settlement Gain/Loss

**Entity:** `entity/SettlementGainLoss.java`

**Purpose:** Track realized gain/loss when FCY transactions are settled

**When Calculated:**
- When FCY is purchased or sold
- Difference between settlement rate and WAE rate

**Example:**
```
WAE Rate: 109.75 BDT/USD
Settlement Rate: 110.00 BDT/USD
FCY Sold: 10,000 USD

Settlement Gain: 10,000 × (110.00 - 109.75) = 2,500 BDT
```

### 9.9 Position GL Accounts

**Purpose:** Track FCY position for each currency

| Currency | Position GL |
|----------|-------------|
| USD | 920101001 |
| EUR | 920102001 |
| GBP | 920103001 |
| JPY | 920104001 |

**Usage:** When BDT ↔ FCY transactions occur, position GL is used as `Pointing_Id` in transaction

### 9.10 Multi-Currency API Endpoints

```
GET    /api/exchange-rates                      - Get exchange rates
POST   /api/exchange-rates                      - Create exchange rate
PUT    /api/exchange-rates/{id}                 - Update exchange rate
GET    /api/exchange-rates/latest/{ccyPair}     - Get latest rate
POST   /api/mct/transactions                    - Create MCT transaction
GET    /api/mct/revaluation                     - Get revaluation summary
POST   /api/mct/revaluation/run                 - Run revaluation manually
GET    /api/mct/settlement-gl/{ccyPair}         - Get settlement gain/loss
GET    /api/mct/position/{currency}             - Get currency position
```

---

## 10. Database Schema

### 10.1 Core Master Tables

#### **1. Cust_Master**
**Purpose:** Customer master data

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Cust_Id | VARCHAR(10) | PK | Customer ID (auto-generated) |
| Ext_Cust_Id | VARCHAR(20) | UNIQUE | External customer ID |
| Cust_Type | ENUM | | I/C/B (Individual/Corporate/Bank) |
| First_Name | VARCHAR(50) | | First name (Individual) |
| Last_Name | VARCHAR(50) | | Last name (Individual) |
| Trade_Name | VARCHAR(100) | | Trade name (Corporate/Bank) |
| Address_1 | VARCHAR(200) | | Primary address |
| Address_2 | VARCHAR(200) | | Secondary address |
| City | VARCHAR(50) | | City |
| Postal_Code | VARCHAR(10) | | Postal code |
| Country | VARCHAR(50) | | Country |
| Mobile | VARCHAR(15) | | Mobile number |
| Email | VARCHAR(100) | | Email address |
| Branch_Code | VARCHAR(10) | | Home branch |
| Entry_Date | DATE | | Entry date |
| Maker_Id | VARCHAR(20) | | Maker user ID |
| Verification_Date | DATE | | Verification date |
| Verifier_Id | VARCHAR(20) | | Verifier user ID |

#### **2. Prod_Master**
**Purpose:** Product master (SB, CA, TD, etc.)

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Prod_Code | VARCHAR(10) | PK | Product code |
| Prod_Name | VARCHAR(50) | | Product name |
| Prod_Type | ENUM | | Liability/Asset |
| GL_Num | VARCHAR(9) | FK | GL account number |

#### **3. Sub_Prod_Master**
**Purpose:** Sub-product master (detailed product configuration)

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Sub_Product_Id | VARCHAR(9) | PK | Sub-product ID |
| Prod_Code | VARCHAR(10) | FK | Product code |
| Sub_Product_Name | VARCHAR(100) | | Sub-product name |
| Cum_GL_Num | VARCHAR(9) | FK | Cumulative GL number |
| interest_receivable_expenditure_gl_num | VARCHAR(9) | FK | Interest receivable/expenditure GL |
| interest_income_payable_gl_num | VARCHAR(9) | FK | Interest income/payable GL |
| effective_interest_rate | DECIMAL(10,4) | | Effective interest rate |
| Interest_Code | VARCHAR(10) | FK | Interest rate code |
| Interest_Increment | DECIMAL(10,4) | | Interest increment |

#### **4. GL_setup**
**Purpose:** Chart of Accounts (5-layer hierarchy)

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| GL_Num | VARCHAR(9) | PK | GL account number |
| GL_Description | VARCHAR(200) | | GL description |
| GL_Type | ENUM | | Asset/Liability/Equity/Income/Expense |
| Parent_GL | VARCHAR(9) | FK (self) | Parent GL (for hierarchy) |
| Layer | INT | | Hierarchy layer (1-5) |
| Is_Leaf | BOOLEAN | | Is leaf node? |

#### **5. Interest_Rate_Master**
**Purpose:** Interest rate configurations

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Intt_Code | VARCHAR(10) | PK | Interest rate code |
| Intt_Rate | DECIMAL(10,4) | | Interest rate (%) |
| Intt_Effctv_Date | DATE | | Effective date |
| Description | VARCHAR(100) | | Rate description |

#### **6. Parameter_Table**
**Purpose:** System parameters

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Parameter_Id | BIGINT | PK (Auto) | Parameter ID |
| Parameter_Name | VARCHAR(50) | UNIQUE | Parameter name |
| Parameter_Value | VARCHAR(200) | | Parameter value |
| Description | VARCHAR(200) | | Description |
| Updated_By | VARCHAR(20) | | Last updated by |
| Last_Updated | DATETIME | | Last update timestamp |

**Key Parameters:**
- `System_Date`: Current system date
- `Last_EOD_Date`: Last EOD date
- `Last_EOD_Timestamp`: Last EOD timestamp
- `Last_EOD_User`: Last EOD user

### 10.2 Account Tables

#### **7. Cust_Acct_Master**
**Purpose:** Customer account master

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Account_No | VARCHAR(16) | PK | Account number (auto-generated) |
| Cust_Id | VARCHAR(10) | FK | Customer ID |
| Sub_Product_Id | VARCHAR(9) | FK | Sub-product ID |
| GL_Num | VARCHAR(9) | FK | GL account number |
| Branch_Code | VARCHAR(10) | | Branch code |
| Ccy_Code | VARCHAR(3) | | Currency code |
| Date_Open | DATE | | Account opening date |
| Date_Close | DATE | | Account closing date |
| Tenor | INT | | Tenor in days (Deal accounts) |
| Date_Maturity | DATE | | Maturity date (Deal accounts) |
| loanLimit | DECIMAL(20,2) | | Loan limit (Asset accounts) |
| Rate_of_Interest | DECIMAL(10,4) | | Interest rate (%) |
| Entry_Date | DATE | | Entry date |
| Maker_Id | VARCHAR(20) | | Maker user ID |

**Indexes:**
- `idx_cust_acct_cust_id` (Cust_Id)
- `idx_cust_acct_gl_num` (GL_Num)

#### **8. OF_Acct_Master**
**Purpose:** Office account master

Similar structure to `Cust_Acct_Master`, but without `Cust_Id`.

#### **9. Acct_Bal**
**Purpose:** Customer account balances (daily)

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Tran_Date | DATE | PK (Composite) | Transaction date |
| Account_No | VARCHAR(16) | PK (Composite) | Account number |
| Opening_Bal | DECIMAL(20,2) | | Opening balance |
| DR_Summation | DECIMAL(20,2) | | Sum of debits |
| CR_Summation | DECIMAL(20,2) | | Sum of credits |
| Closing_Bal | DECIMAL(20,2) | | Closing balance |
| Current_Balance | DECIMAL(20,2) | | Current balance |
| Available_Balance | DECIMAL(20,2) | | Available balance |

**Composite PK:** `(Tran_Date, Account_No)`

#### **10. Acct_Bal_Accrual**
**Purpose:** Interest accrual balances

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Tran_Date | DATE | PK (Composite) | Transaction date |
| Account_No | VARCHAR(16) | PK (Composite) | Account number |
| Opening_Accrual | DECIMAL(20,2) | | Opening accrual |
| Debit_Accrual | DECIMAL(20,2) | | Debit accrual |
| Credit_Accrual | DECIMAL(20,2) | | Credit accrual |
| Closing_Accrual | DECIMAL(20,2) | | Closing accrual |

### 10.3 Transaction Tables

#### **11. Tran_Table**
**Purpose:** Main transaction table

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Tran_ID | VARCHAR(20) | PK | Transaction ID (auto-generated) |
| Account_No | VARCHAR(16) | FK | Customer account number |
| Tran_Date | DATE | | Transaction date |
| Value_Date | DATE | | Value date |
| Dr_Cr_Flag | ENUM | | D (Debit) / C (Credit) |
| FCY_Amt | DECIMAL(20,2) | | Amount in account currency |
| Ccy_Code | VARCHAR(3) | | Currency code |
| Exchange_Rate | DECIMAL(10,4) | | Exchange rate |
| LCY_Amt | DECIMAL(20,2) | | Amount in BDT |
| GL_Num | VARCHAR(9) | FK | GL account number |
| Narration | VARCHAR(200) | | Transaction description |
| Tran_Status | ENUM | | Entry/Posted/Verified/Future |
| Maker_Id | VARCHAR(20) | | Maker user ID |
| Verifier_Id | VARCHAR(20) | | Verifier user ID |
| Entry_Time | DATETIME | | Entry timestamp |
| Post_Time | DATETIME | | Post timestamp |
| Verify_Time | DATETIME | | Verification timestamp |

**Indexes:**
- `idx_tran_account_date` (Account_No, Tran_Date)
- `idx_tran_status` (Tran_Status)
- `idx_tran_value_date` (Value_Date)

#### **12. GL_Movement**
**Purpose:** GL movements from transactions

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| GL_Mov_ID | BIGINT | PK (Auto) | GL movement ID |
| GL_Num | VARCHAR(9) | FK | GL account number |
| Tran_Date | DATE | | Transaction date |
| Tran_ID | VARCHAR(20) | FK | Transaction ID |
| Dr_Cr_Flag | ENUM | | D / C |
| LCY_Amt | DECIMAL(20,2) | | Amount in BDT |
| FCY_Amt | DECIMAL(20,2) | | Amount in foreign currency |
| Ccy_Code | VARCHAR(3) | | Currency code |
| Exchange_Rate | DECIMAL(10,4) | | Exchange rate |

**Indexes:**
- `idx_gl_mov_gl_date` (GL_Num, Tran_Date)
- `idx_gl_mov_tran_id` (Tran_ID)

#### **13. GL_Balance**
**Purpose:** GL account balances (daily)

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Id | BIGINT | PK (Auto) | Auto-increment ID |
| GL_Num | VARCHAR(9) | UNIQUE (with Tran_Date) | GL account number |
| Tran_Date | DATE | UNIQUE (with GL_Num) | Transaction date |
| Opening_Bal | DECIMAL(20,2) | | Opening balance |
| DR_Summation | DECIMAL(20,2) | | Sum of debits |
| CR_Summation | DECIMAL(20,2) | | Sum of credits |
| Closing_Bal | DECIMAL(20,2) | | Closing balance |
| Current_Balance | DECIMAL(20,2) | | Current balance |

**Unique Constraint:** `(GL_Num, Tran_Date)`

#### **14. TXN_HIST_ACCT**
**Purpose:** Transaction history for Statement of Accounts

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Hist_ID | BIGINT | PK (Auto) | History ID |
| Account_No | VARCHAR(16) | FK | Account number |
| Tran_Date | DATE | | Transaction date |
| Value_Date | DATE | | Value date |
| Tran_ID | VARCHAR(20) | FK | Transaction ID |
| Dr_Cr_Flag | ENUM | | D / C |
| FCY_Amt | DECIMAL(20,2) | | Amount in FCY |
| LCY_Amt | DECIMAL(20,2) | | Amount in LCY |
| Ccy_Code | VARCHAR(3) | | Currency code |
| Narration | VARCHAR(200) | | Description |
| Balance | DECIMAL(20,2) | | Running balance |

**Indexes:**
- `idx_hist_account_date` (Account_No, Tran_Date)

### 10.4 Interest Tables

#### **15. Intt_Accr_Tran**
**Purpose:** Interest accrual transactions

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Accrual_Id | VARCHAR(30) | PK | Accrual ID (auto-generated) |
| Account_No | VARCHAR(16) | FK | Account number |
| Tran_Date | DATE | | Transaction date |
| Dr_Cr_Flag | ENUM | | D / C |
| GL_Num | VARCHAR(9) | FK | GL account number |
| FCY_Amt | DECIMAL(20,2) | | Amount in FCY |
| Exchange_Rate | DECIMAL(10,4) | | Exchange rate |
| LCY_Amt | DECIMAL(20,2) | | Amount in LCY |
| Ccy_Code | VARCHAR(3) | | Currency code |
| Status | ENUM | | Pending/Posted |

**Accrual ID Format:** `S + YYYYMMDD + 9-digit-sequential + -row-suffix`

#### **16. GL_Movement_Accrual**
**Purpose:** GL movements for accruals

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| GL_Mov_Accr_ID | BIGINT | PK (Auto) | GL movement accrual ID |
| GL_Num | VARCHAR(9) | FK | GL account number |
| Tran_Date | DATE | | Transaction date |
| Accrual_Id | VARCHAR(30) | FK | Accrual ID |
| Dr_Cr_Flag | ENUM | | D / C |
| LCY_Amt | DECIMAL(20,2) | | Amount in LCY |
| FCY_Amt | DECIMAL(20,2) | | Amount in FCY |
| Ccy_Code | VARCHAR(3) | | Currency code |
| Exchange_Rate | DECIMAL(10,4) | | Exchange rate |

#### **17. Value_Date_Intt_Accr**
**Purpose:** Value date interest accruals

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Value_Date_Intt_Id | BIGINT | PK (Auto) | Value date interest ID |
| Account_No | VARCHAR(16) | FK | Account number |
| Tran_Date | DATE | | Transaction date |
| Value_Date | DATE | | Value date |
| Tran_ID | VARCHAR(20) | FK | Transaction ID |
| Delta_Interest | DECIMAL(20,2) | | Delta interest amount |
| Number_Of_Days | INT | | Number of days |
| Interest_Rate | DECIMAL(10,4) | | Interest rate |
| Status | ENUM | | Pending/Posted |

### 10.5 Multi-Currency Tables

#### **18. fx_rate_master**
**Purpose:** Exchange rates

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Rate_Id | BIGINT | PK (Auto) | Rate ID |
| Ccy_Pair | VARCHAR(10) | | Currency pair (e.g., "USD/BDT") |
| Mid_Rate | DECIMAL(10,4) | | Mid-market rate |
| Buying_Rate | DECIMAL(10,4) | | Bank buying rate |
| Selling_Rate | DECIMAL(10,4) | | Bank selling rate |
| Effective_Date | DATE | | Rate effective date |
| Effective_Time | TIME | | Rate effective time |
| Created_By | VARCHAR(20) | | User who created rate |

#### **19. wae_master**
**Purpose:** Weighted average exchange rates

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Ccy_Pair | VARCHAR(10) | PK | Currency pair |
| WAE_Rate | DECIMAL(10,4) | | Weighted average rate |
| FCY_Balance | DECIMAL(20,2) | | Foreign currency balance |
| LCY_Balance | DECIMAL(20,2) | | Local currency equivalent |
| Last_Updated | DATETIME | | Last update timestamp |

#### **20. reval_tran**
**Purpose:** Revaluation transactions

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Reval_ID | BIGINT | PK (Auto) | Revaluation ID |
| Reval_Date | DATE | | Revaluation date |
| Account_No | VARCHAR(16) | FK | Account number (if account reval) |
| GL_Num | VARCHAR(9) | FK | GL account number |
| FCY_Balance | DECIMAL(20,2) | | Foreign currency balance |
| Ccy_Code | VARCHAR(3) | | Currency code |
| WAE_Rate | DECIMAL(10,4) | | Weighted average rate |
| MTM_Rate | DECIMAL(10,4) | | Mark-to-market rate |
| Booked_LCY | DECIMAL(20,2) | | Booked LCY value |
| MTM_LCY | DECIMAL(20,2) | | Mark-to-market LCY value |
| Reval_Diff | DECIMAL(20,2) | | Revaluation difference |
| Gain_Loss_Type | VARCHAR(10) | | Gain / Loss |

#### **21. reval_summary**
**Purpose:** Daily revaluation summary

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Summary_ID | BIGINT | PK (Auto) | Summary ID |
| Reval_Date | DATE | | Revaluation date |
| Total_Gain | DECIMAL(20,2) | | Total unrealized gain |
| Total_Loss | DECIMAL(20,2) | | Total unrealized loss |
| Net_Gain_Loss | DECIMAL(20,2) | | Net gain/loss |
| Entries_Posted | INT | | Number of entries posted |

#### **22. settlement_gain_loss**
**Purpose:** Settlement gain/loss tracking

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Settlement_ID | BIGINT | PK (Auto) | Settlement ID |
| Settlement_Date | DATE | | Settlement date |
| Ccy_Code | VARCHAR(3) | | Currency code |
| FCY_Amt | DECIMAL(20,2) | | Foreign currency amount |
| WAE_Rate | DECIMAL(10,4) | | Weighted average rate |
| Settlement_Rate | DECIMAL(10,4) | | Settlement rate |
| Gain_Loss_Amt | DECIMAL(20,2) | | Realized gain/loss |

#### **23. currency_master**
**Purpose:** Currency master data

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Ccy_Code | VARCHAR(3) | PK | Currency code |
| Ccy_Name | VARCHAR(50) | | Currency name |
| Ccy_Symbol | VARCHAR(5) | | Currency symbol |
| Decimal_Places | INT | | Decimal places |
| Is_Active | BOOLEAN | | Is active? |

### 10.6 Value Date Tables

#### **24. Tran_Value_Date_Log**
**Purpose:** Value date transaction log

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Log_Id | BIGINT | PK (Auto) | Log ID |
| Tran_ID | VARCHAR(20) | FK | Transaction ID |
| Account_No | VARCHAR(16) | FK | Account number |
| Tran_Date | DATE | | Transaction date |
| Value_Date | DATE | | Value date |
| Value_Date_Type | ENUM | | CURRENT/PAST/FUTURE |
| Delta_Interest | DECIMAL(20,2) | | Delta interest (if past) |
| Original_Balance | DECIMAL(20,2) | | Balance before transaction |
| New_Balance | DECIMAL(20,2) | | Balance after transaction |
| Posted_At | DATETIME | | When posted |
| Processed_At | DATETIME | | When BOD processed (future) |

### 10.7 System Tables

#### **25. EOD_Log_Table**
**Purpose:** EOD batch job execution log

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| EOD_Log_Id | BIGINT | PK (Auto) | EOD log ID |
| EOD_Date | DATE | | EOD processing date |
| Job_Name | VARCHAR(50) | | Job name |
| Start_Timestamp | DATETIME | | Job start time |
| End_Timestamp | DATETIME | | Job end time |
| System_Date | DATE | | System date at execution |
| User_ID | VARCHAR(20) | | User who initiated |
| Records_Processed | INT | | Number of records processed |
| Status | ENUM | | Running/Success/Failed |
| Error_Message | TEXT | | Error details (if failed) |
| Failed_At_Step | VARCHAR(500) | | Where it failed |
| Created_Timestamp | DATETIME | | Created timestamp |

#### **26. Account_Seq**
**Purpose:** Account number sequence generator

| Column | Type | Constraint | Description |
|--------|------|------------|-------------|
| Branch_Code | VARCHAR(10) | PK (Composite) | Branch code |
| Product_Code | VARCHAR(10) | PK (Composite) | Product code |
| Last_Sequence | BIGINT | | Last sequence number |

### 10.8 Entity Relationship Diagram (Simplified)

```
┌─────────────┐       ┌─────────────────┐       ┌──────────────┐
│ Cust_Master │──1:M─▶│ Cust_Acct_Master│──1:1─▶│   Acct_Bal   │
└─────────────┘       └─────────────────┘       └──────────────┘
                              │
                              │ 1:M
                              ▼
                      ┌─────────────┐
                      │ Tran_Table  │
                      └─────────────┘
                              │
                              │ 1:M
                              ▼
                      ┌─────────────┐
                      │ GL_Movement │
                      └─────────────┘

┌──────────────┐       ┌──────────────┐       ┌─────────────┐
│ Prod_Master  │──1:M─▶│Sub_Prod_Master│──1:M─▶│Cust_Acct_Master│
└──────────────┘       └──────────────┘       └─────────────┘

┌──────────────┐       ┌──────────────┐       ┌─────────────┐
│   GL_Setup   │──1:M─▶│ GL_Balance   │       │ GL_Movement │
└──────────────┘       └──────────────┘       └─────────────┘
       │                                              │
       └──────────────────1:M─────────────────────────┘

┌──────────────────┐       ┌────────────────────┐
│ Intt_Accr_Tran   │──1:M─▶│ GL_Movement_Accrual│
└──────────────────┘       └────────────────────┘
```

---

## 11. Key Services & Utilities

### 11.1 System Date Service

**Service:** `SystemDateService`

**Purpose:** Centralized system date management

**Key Methods:**
- `getSystemDate()`: Get current system date from Parameter_Table
- `setSystemDate(date, userId)`: Update system date
- `getSystemDateTime()`: Get current system date and time

**CRITICAL:** All date/time operations use System_Date from Parameter_Table, NOT device clock. This ensures consistency across distributed systems.

### 11.2 Balance Service

**Service:** `BalanceService`

**Purpose:** Centralized balance calculations

**Key Methods:**
- `updateAccountBalance(accountNo, drCrFlag, amount)`: Update account balance
- `updateGLBalance(glNum, drCrFlag, amount)`: Update GL balance
- `getComputedAccountBalance(accountNo, date)`: Real-time balance with today's transactions
- `getComputedGLBalance(glNum, date)`: Real-time GL balance

### 11.3 Statement of Accounts Service

**Service:** `StatementOfAccountsService`

**Purpose:** Generate customer account statements

**Key Methods:**
- `generateStatement(accountNo, fromDate, toDate)`: Generate statement for date range
- `generateMonthlyStatement(accountNo, month, year)`: Monthly statement

**Data Source:** `TXN_HIST_ACCT` table (populated during transaction verification)

### 11.4 Financial Reports Service

**Service:** `FinancialReportsService`

**Purpose:** Generate financial reports

**Reports:**
1. **Trial Balance:** GL-level summary
2. **Balance Sheet:** Formatted by GL hierarchy
3. **Subproduct GL Balance Report:** Subproduct-wise balances

### 11.5 ID Generation Services

#### AccountNumberService
- `generateCustomerAccountNumber(customer, subProduct)`

#### CustomerIdService
- `generateCustomerId(custType)`

#### TransactionIdService
- `generateTransactionId()`

### 11.6 Validation Services

#### CustomerValidator
- Validates customer data before creation/update

#### TransactionValidationService
- Validates transactions (debit = credit, sufficient balance, etc.)

#### CurrencyValidationService
- Validates currency combinations and exchange rates

#### ValueDateValidationService
- Validates value date ranges and constraints

---

## 12. Technology Stack

### 12.1 Backend Framework
- **Spring Boot 3.x**: Main application framework
- **Spring Data JPA**: ORM and data access
- **Hibernate**: JPA implementation
- **Spring Security**: Authentication and authorization (if implemented)

### 12.2 Database
- **MySQL 8.0**: Relational database
- **Flyway**: Database migration tool

### 12.3 Build Tools
- **Maven**: Dependency management and build

### 12.4 Libraries
- **Lombok**: Code generation (getters, setters, builders)
- **Jakarta Bean Validation**: Input validation
- **SLF4J + Logback**: Logging framework
- **Apache POI**: Excel report generation
- **OpenCSV**: CSV report generation

### 12.5 API Documentation
- **Swagger/OpenAPI**: API documentation (if implemented)

### 12.6 Testing
- **JUnit 5**: Unit testing framework
- **Mockito**: Mocking framework
- **Spring Boot Test**: Integration testing

### 12.7 Development Tools
- **IntelliJ IDEA / Eclipse**: IDE
- **Postman**: API testing
- **MySQL Workbench**: Database management

---

## Appendix A: Common Business Scenarios

### Scenario 1: Create Customer and Open Account
1. Create customer via `POST /api/customers`
2. Verify customer via `POST /api/customers/{id}/verify`
3. Create account via `POST /api/customer-accounts`
4. Account balance initialized with 0.00

### Scenario 2: Deposit Transaction
1. Create transaction via `POST /api/transactions/entry` (Credit, Entry status)
2. Post transaction via `POST /api/transactions/{tranId}/post` (Posted status)
3. Verify transaction via `POST /api/transactions/{tranId}/verify` (Verified status)
4. Account balance updated, GL movement created

### Scenario 3: EOD Processing
1. Validate pre-EOD conditions
2. Run Job 1: Update account balances, delete Entry transactions
3. Run Job 2: Calculate interest accruals
4. Run Job 3: Post accruals to GL Movement Accrual
5. Run Job 4: Post verified transactions to GL Movement
6. Run Job 5: Update GL balances
7. Run Job 6: Update interest accrual balances
8. Run Job 7: Revalue FCY positions
9. Run Job 8: Generate financial reports
10. Run Job 9: Increment system date

### Scenario 4: Multi-Currency Transaction
1. Check exchange rate in `fx_rate_master`
2. Create transaction with FCY_Amt and Exchange_Rate
3. Calculate LCY_Amt = FCY_Amt × Exchange_Rate
4. Post transaction (account balance uses FCY_Amt, GL balance uses LCY_Amt)
5. Update WAE_Master for currency position

### Scenario 5: Backdated Transaction
1. Create transaction with Value_Date < System_Date
2. Post transaction immediately
3. Calculate delta interest for backdated period
4. Post delta interest to Value_Date_Intt_Accr
5. Delta interest added to regular accrual in next EOD

---

## Appendix B: Troubleshooting Guide

### Issue 1: EOD Job Failed
**Symptoms:** EOD log shows job status = Failed

**Diagnosis:**
1. Check `EOD_Log_Table.Error_Message`
2. Check `Failed_At_Step` for failure point
3. Review application logs

**Common Causes:**
- GL accounts out of balance
- Entry transactions not deleted
- Database connection issues

### Issue 2: Account Balance Mismatch
**Symptoms:** Account balance doesn't match expected value

**Diagnosis:**
1. Check `Acct_Bal` table for account
2. Verify `DR_Summation` and `CR_Summation`
3. Check if EOD Batch Job 1 ran successfully

**Common Causes:**
- Posted transactions not included in balance calculation
- EOD not run for current day
- Manual database updates

### Issue 3: Interest Not Accruing
**Symptoms:** No interest accrual entries for account

**Diagnosis:**
1. Check if account has non-zero balance
2. Verify interest rate in `Interest_Rate_Master`
3. Check if EOD Batch Job 2 ran successfully

**Common Causes:**
- Interest rate not configured
- Account balance is zero
- EOD Batch Job 2 failed

---

## Document Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 1.0 | 2025-12-07 | Initial comprehensive documentation | System |

---

**End of Document**
