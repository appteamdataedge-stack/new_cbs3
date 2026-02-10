# Acct_Bal_LCY Data Flow Diagram

## Overall Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     EOD BATCH JOB 1                             │
│              Account Balance Update Service                      │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────────┐
        │   Get All Customer Accounts           │
        │   from Cust_Acct_Master               │
        └───────────────────────────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────────┐
        │   For Each Account:                   │
        │   - Get Opening Balance               │
        │   - Calculate DR/CR Summations        │
        │   - Calculate Closing Balance         │
        └───────────────────────────────────────┘
                            │
                ┌───────────┴───────────┐
                │                       │
                ▼                       ▼
    ┌──────────────────────┐   ┌──────────────────────┐
    │   Save to acct_bal   │   │ Save to acct_bal_lcy │
    │ (Original Currency)  │   │      (BDT Only)      │
    └──────────────────────┘   └──────────────────────┘
```

## Data Sources

```
┌─────────────────┐
│   Tran_Table    │  ← Primary data source
│                 │
│ - Tran_Date     │
│ - Account_No    │
│ - FCY_Amt       │  ← Original currency amount
│ - LCY_Amt       │  ← BDT converted amount
│ - Exchange_Rate │  ← Conversion rate used
│ - Dr_Cr_Flag    │
└─────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│   AccountBalanceUpdateService                        │
│                                                      │
│   Aggregates transactions:                          │
│   - DR_Summation = SUM(FCY_Amt WHERE Dr_Cr='D')    │
│   - CR_Summation = SUM(FCY_Amt WHERE Dr_Cr='C')    │
│   - DR_Summation_lcy = SUM(LCY_Amt WHERE Dr_Cr='D')│
│   - CR_Summation_lcy = SUM(LCY_Amt WHERE Dr_Cr='C')│
└──────────────────────────────────────────────────────┘
        │
        ├─────────────────────┬──────────────────────┐
        ▼                     ▼                      ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│   Acct_Bal      │  │  Acct_Bal_LCY   │  │  ExchangeRate   │
│                 │  │                 │  │     Service     │
│ Tran_Date       │  │ Tran_Date       │  │                 │
│ Account_No      │  │ Account_No      │  │ Provides rates  │
│ Account_Ccy     │  │ Opening_Bal_lcy │  │ if needed       │
│ Opening_Bal     │  │ DR_Summation_lcy│  │                 │
│ DR_Summation    │  │ CR_Summation_lcy│  └─────────────────┘
│ CR_Summation    │  │ Closing_Bal_lcy │
│ Closing_Bal     │  │                 │
└─────────────────┘  └─────────────────┘
```

## Conversion Logic Flow

### For BDT Accounts
```
┌─────────────────────────────────────────────────────┐
│            BDT Account (e.g., 200000023001)         │
└─────────────────────────────────────────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────┐
        │   Calculate in BDT                │
        │   Opening: 10,000.00 BDT          │
        │   CR:       5,000.00 BDT          │
        │   Closing: 15,000.00 BDT          │
        └───────────────────────────────────┘
                            │
                ┌───────────┴────────────┐
                │                        │
                ▼                        ▼
    ┌──────────────────────┐   ┌──────────────────────┐
    │    acct_bal          │   │   acct_bal_lcy       │
    │                      │   │                      │
    │ Opening: 10,000 BDT  │   │ Opening: 10,000 BDT  │
    │ CR:       5,000 BDT  │   │ CR:       5,000 BDT  │
    │ Closing: 15,000 BDT  │   │ Closing: 15,000 BDT  │
    │                      │   │                      │
    │   VALUES IDENTICAL   │   │   VALUES IDENTICAL   │
    └──────────────────────┘   └──────────────────────┘
```

### For USD/FCY Accounts
```
┌─────────────────────────────────────────────────────┐
│           USD Account (e.g., 200000023003)          │
│               Exchange Rate: 111.5                  │
└─────────────────────────────────────────────────────┘
                            │
                ┌───────────┴────────────┐
                │                        │
                ▼                        ▼
    ┌──────────────────────┐   ┌──────────────────────┐
    │  Calculate in USD    │   │  Calculate in BDT    │
    │                      │   │                      │
    │ Opening:  100.00 USD │   │ Opening: 11,150 BDT  │
    │ CR:       100.00 USD │   │ CR:      11,150 BDT  │
    │ Closing:  200.00 USD │   │ Closing: 22,300 BDT  │
    └──────────────────────┘   └──────────────────────┘
                │                        │
                ▼                        ▼
    ┌──────────────────────┐   ┌──────────────────────┐
    │    acct_bal          │   │   acct_bal_lcy       │
    │                      │   │                      │
    │ Opening:  100 USD    │   │ Opening: 11,150 BDT  │
    │ CR:       100 USD    │   │ CR:      11,150 BDT  │
    │ Closing:  200 USD    │   │ Closing: 22,300 BDT  │
    │                      │   │                      │
    │  ORIGINAL CURRENCY   │   │   CONVERTED TO BDT   │
    └──────────────────────┘   └──────────────────────┘
```

## Transaction Flow

```
Step 1: Transaction Posted
┌──────────────────────────────────┐
│     Tran_Table Record            │
│                                  │
│ Tran_Id: TXN001                 │
│ Account_No: 200000023003        │
│ Tran_Ccy: USD                   │
│ FCY_Amt: 100.00                 │
│ Exchange_Rate: 111.5            │
│ LCY_Amt: 11,150.00 (calculated) │ ← Already in BDT
│ Dr_Cr_Flag: C                   │
└──────────────────────────────────┘
                │
                ▼
Step 2: EOD Processing
┌──────────────────────────────────┐
│  AccountBalanceUpdateService     │
│                                  │
│  Aggregate all transactions:     │
│  - SUM(FCY_Amt) for USD column   │
│  - SUM(LCY_Amt) for BDT column   │
└──────────────────────────────────┘
                │
                ▼
Step 3: Dual Save
┌────────────────┬─────────────────┐
│   acct_bal     │  acct_bal_lcy   │
│                │                 │
│ CR: 100.00 USD │ CR: 11,150 BDT  │
└────────────────┴─────────────────┘
```

## Balance Calculation Flow

```
Opening Balance Calculation (3-Tier Fallback)
┌─────────────────────────────────────────────┐
│  Tier 1: Try Previous Day (systemDate - 1) │
└─────────────────────────────────────────────┘
                    │
                    ▼
            ┌───────────────┐
            │   Found?      │
            └───────────────┘
                │       │
               YES      NO
                │       │
                │       ▼
                │   ┌──────────────────────────────────┐
                │   │ Tier 2: Try Last Transaction     │
                │   │         MAX(Tran_Date < system)  │
                │   └──────────────────────────────────┘
                │               │
                │               ▼
                │       ┌───────────────┐
                │       │   Found?      │
                │       └───────────────┘
                │           │       │
                │          YES      NO
                │           │       │
                │           │       ▼
                │           │   ┌────────────────┐
                │           │   │ Tier 3: New    │
                │           │   │ Return 0       │
                │           │   └────────────────┘
                ▼           ▼           │
        ┌────────────────────────────────┐
        │   Use Previous Closing_Bal     │
        └────────────────────────────────┘
```

## Reporting Flow

```
Financial Reports
        │
        ▼
┌─────────────────────────────────────┐
│    Query acct_bal_lcy               │
│    (All balances already in BDT)    │
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  Aggregate Totals:                  │
│  - Total Assets (BDT)               │
│  - Total Liabilities (BDT)          │
│  - By Product/Branch/Currency       │
│                                     │
│  No runtime conversion needed!      │
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│     Generate Report                 │
│     (All in BDT)                    │
└─────────────────────────────────────┘
```

## Key Benefits

```
┌──────────────────────────────────────────────────────────┐
│                      BENEFITS                            │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  1. Performance                                          │
│     • No runtime conversion                              │
│     • Pre-calculated BDT amounts                         │
│     • Fast aggregation queries                           │
│                                                          │
│  2. Consistency                                          │
│     • Same rates as transaction posting                  │
│     • No rate lookup delays                              │
│     • Historical accuracy                                │
│                                                          │
│  3. Simplicity                                           │
│     • Single query for BDT totals                        │
│     • No currency joins needed                           │
│     • Easy reporting                                     │
│                                                          │
│  4. Compliance                                           │
│     • All reports in local currency                      │
│     • Regulatory requirements met                        │
│     • Audit trail complete                               │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## Summary

```
┌─────────────────────────────────────────────────────────────┐
│                        SUMMARY                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Acct_Bal          Acct_Bal_LCY         Purpose            │
│  ─────────────     ──────────────       ────────────       │
│  Original CCY  →   BDT (LCY)        →   Consolidated       │
│  Multi-currency    Single currency      Reporting          │
│  Per account       Per account          Easy aggregation   │
│  Runtime needed    Pre-calculated       Fast queries       │
│                                                             │
│  Both tables populated automatically during EOD!            │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```
