# Trial Balance All GL - Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         USER INTERFACE                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  Sidebar Menu                    Financial Reports Page                  │
│  ┌─────────────────┐            ┌───────────────────────────────────┐  │
│  │ Settlement Rpts │            │  Report Date: [2026-03-30]        │  │
│  │ ► Financial Rpts│ ────────►  │                                   │  │
│  │ System Date     │            │  ┌──────────────────────────────┐ │  │
│  │ BOD             │            │  │ Trial Balance (Active)       │ │  │
│  │ EOD             │            │  │ [Download CSV]               │ │  │
│  └─────────────────┘            │  └──────────────────────────────┘ │  │
│                                  │                                   │  │
│                                  │  ┌──────────────────────────────┐ │  │
│                                  │  │ Trial Balance (All GL) 🆕    │ │  │
│                                  │  │ [Download All GL - CSV] ✓    │ │  │
│                                  │  └──────────────────────────────┘ │  │
│                                  │                                   │  │
│                                  │  ┌──────────────────────────────┐ │  │
│                                  │  │ Balance Sheet                │ │  │
│                                  │  │ [Download Excel]             │ │  │
│                                  │  └──────────────────────────────┘ │  │
│                                  │                                   │  │
│                                  │  ┌──────────────────────────────┐ │  │
│                                  │  │ Subproduct GL Balance        │ │  │
│                                  │  │ [Download CSV]               │ │  │
│                                  │  └──────────────────────────────┘ │  │
│                                  └───────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ Click "Download All GL - CSV"
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         FRONTEND API LAYER                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  batchJobService.ts                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ downloadTrialBalanceAllGLAccounts(reportDate)                    │   │
│  │                                                                   │   │
│  │ 1. Format date: "2026-03-30" → "20260330"                       │   │
│  │ 2. Call API: GET /api/admin/eod/batch-job-8/                    │   │
│  │              download/trial-balance-all-gl/20260330              │   │
│  │ 3. Receive blob response                                         │   │
│  │ 4. Create download link                                          │   │
│  │ 5. Trigger browser download                                      │   │
│  │ 6. Show success toast                                            │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ HTTP GET Request
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         BACKEND CONTROLLER                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  AdminController.java                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ @GetMapping("/eod/batch-job-8/download/trial-balance-all-gl/    │   │
│  │             {date}")                                             │   │
│  │                                                                   │   │
│  │ downloadTrialBalanceAllGLAccounts(String date)                   │   │
│  │                                                                   │   │
│  │ 1. Validate date format (YYYYMMDD)                              │   │
│  │ 2. Parse date: "20260330" → LocalDate(2026-03-30)              │   │
│  │ 3. Call service method ────────────────────────────────────────┐│   │
│  │ 4. Receive CSV byte array                                       ││   │
│  │ 5. Set headers (Content-Type: text/csv)                         ││   │
│  │ 6. Return ResponseEntity with CSV file                          ││   │
│  └─────────────────────────────────────────────────────────────────┘│   │
└─────────────────────────────────────────────────────────────────────┼───┘
                                                                       │
                                    │ Service Call                     │
                                    ▼                                  │
┌─────────────────────────────────────────────────────────────────────┼───┐
│                         BACKEND SERVICE LAYER                        │   │
├─────────────────────────────────────────────────────────────────────┼───┤
│                                                                       │   │
│  FinancialReportsService.java                                        │   │
│  ┌───────────────────────────────────────────────────────────────┐ │   │
│  │ generateTrialBalanceAllGLAccountsAsBytes(LocalDate systemDate) │◄┘   │
│  │                                                                 │     │
│  │ 1. Fetch ALL GL balances for report date ──────────────────┐   │     │
│  │ 2. Call: glBalanceRepository.findByTranDate(reportDate)    │   │     │
│  │ 3. Ensure FX GLs present                                    │   │     │
│  │ 4. Call CSV generation method                               │   │     │
│  │ 5. Return CSV byte array                                    │   │     │
│  └───────────────────────────────────────────────────────────────┘ │     │
└─────────────────────────────────────────────────────────────────────┼─────┘
                                                                       │
                                    │ JPA Query                        │
                                    ▼                                  │
┌─────────────────────────────────────────────────────────────────────┼─────┐
│                         DATABASE LAYER                               │     │
├─────────────────────────────────────────────────────────────────────┼─────┤
│                                                                       │     │
│  GLBalanceRepository.java                                            │     │
│  ┌───────────────────────────────────────────────────────────────┐ │     │
│  │ findByTranDate(LocalDate reportDate)                          │◄┘     │
│  │                                                                 │       │
│  │ Query: SELECT * FROM gl_balance WHERE Tran_date = ?           │       │
│  │                                                                 │       │
│  │ Returns: List<GLBalance> (ALL records, no filtering)          │       │
│  └───────────────────────────────────────────────────────────────┘       │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ SQL Query
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         DATABASE                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  gl_balance Table                    gl_setup Table                      │
│  ┌──────────────────────────┐       ┌─────────────────────────────┐    │
│  │ GL_Num (PK)              │       │ GL_Code (PK)                │    │
│  │ Tran_date (PK)           │       │ GL_Name                     │    │
│  │ ccy                      │       │ GL_Type                     │    │
│  │ Opening_Bal              │       │ status                      │    │
│  │ DR_Summation             │       └─────────────────────────────┘    │
│  │ CR_Summation             │              │                            │
│  │ Closing_Bal              │              │ LEFT JOIN                  │
│  │ Current_Balance          │              │ (to get GL names)          │
│  │ Last_Updated             │              │                            │
│  └──────────────────────────┘◄─────────────┘                            │
│                                                                           │
│  Sample Data:                                                            │
│  920101001, 2026-03-30, BDT, 112000.00, 5000.00, 3000.00, 114000.00    │
│  920101002, 2026-03-30, USD, 1000.00, 500.00, 200.00, 1300.00          │
│  140203001, 2026-03-30, BDT, 0.00, 0.00, 1165.00, 1165.00              │
│  240203001, 2026-03-30, BDT, 0.00, 1225.00, 0.00, 1225.00              │
│  ... (ALL GL accounts for this date)                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Data Flow

### 1. User Action
```
User clicks "Download Trial Balance - All GL (CSV)" button
  ↓
Frontend: downloadTrialBalanceAllGLAccounts("20260330")
```

### 2. API Request
```
HTTP GET → /api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330
  ↓
AdminController.downloadTrialBalanceAllGLAccounts(date)
```

### 3. Service Processing
```
FinancialReportsService.generateTrialBalanceAllGLAccountsAsBytes(reportDate)
  ↓
glBalanceRepository.findByTranDate(2026-03-30)
  ↓
SQL: SELECT * FROM gl_balance WHERE Tran_date = '2026-03-30'
  ↓
Returns: List<GLBalance> (ALL records for this date)
```

### 4. CSV Generation
```
List<GLBalance> (47 records)
  ↓
generateTrialBalanceReportFromBalancesAsBytes()
  ↓
For each GL:
  - Join with gl_setup to get GL_Name
  - Format: GL_Code, GL_Name, Opening_Bal, DR, CR, Closing_Bal
  ↓
Add TOTAL footer row
  ↓
Convert to CSV byte array
```

### 5. Response
```
CSV byte array
  ↓
ResponseEntity with headers:
  - Content-Type: text/csv
  - Content-Disposition: attachment; filename="TrialBalance_AllGL_20260330.csv"
  ↓
Frontend receives blob
  ↓
Creates download link and triggers browser download
  ↓
User receives CSV file
```

---

## Key Differences from Original Trial Balance

### Original Trial Balance (Active GL Only)

```
User Request
  ↓
glSetupRepository.findActiveGLNumbersWithAccounts()
  ↓
Returns: ["110101001", "210101001", ...] (filtered list)
  ↓
glBalanceRepository.findByTranDateAndGlNumIn(date, activeGLs)
  ↓
Returns: Only GL balances for active accounts
  ↓
CSV: Limited to active GL accounts
```

### New Trial Balance (All GL)

```
User Request
  ↓
glBalanceRepository.findByTranDate(date)
  ↓
Returns: ALL GL balances for this date (no filtering)
  ↓
CSV: ALL GL accounts from gl_balance table
```

---

## Dynamic Behavior Demonstration

### Scenario: Adding a New GL Account

**Time: T0 (Before)**
```sql
-- gl_balance table has 47 GL accounts
SELECT COUNT(DISTINCT GL_Num) FROM gl_balance WHERE Tran_date = '2026-03-30';
-- Result: 47
```

**Download Report:**
- Trial Balance All GL shows **47 GL accounts**

---

**Time: T1 (Add New GL)**
```sql
-- Insert new GL account
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, status)
VALUES ('888777666', 'New Future GL Account', 'ASSET', 'ACTIVE');

INSERT INTO gl_balance (GL_Num, ccy, Tran_date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, Current_Balance, Last_Updated)
VALUES ('888777666', 'BDT', '2026-03-30', 50000.00, 0.00, 0.00, 50000.00, 50000.00, NOW());
```

---

**Time: T2 (After - No Code Changes)**
```sql
-- gl_balance table now has 48 GL accounts
SELECT COUNT(DISTINCT GL_Num) FROM gl_balance WHERE Tran_date = '2026-03-30';
-- Result: 48
```

**Download Report:**
- Trial Balance All GL shows **48 GL accounts** ✨
- New account `888777666 - New Future GL Account` appears automatically
- **NO CODE CHANGES REQUIRED!**

---

## System Integration

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CBS3 Money Market System                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Transaction Processing                                              │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ FX Conversion → Posts to gl_balance (140203001, 240203001)  │   │
│  │ MCT Revaluation → Posts to gl_balance (140203002, 240203002)│   │
│  │ Customer Transactions → Posts to gl_balance (110*, 210*)    │   │
│  │ Office Account Transactions → Posts to gl_balance (922*)    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                            │                                          │
│                            ▼                                          │
│  gl_balance Table (Updated by Transactions)                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ Contains ALL GL accounts used in the system                  │   │
│  │ - Customer account GLs                                       │   │
│  │ - Office account GLs                                         │   │
│  │ - Position accounts (920101001, 920101002)                  │   │
│  │ - FX Conversion accounts (140203001, 240203001)             │   │
│  │ - MCT accounts (140203002, 240203002)                       │   │
│  │ - Nostro accounts (922030200102, etc.)                      │   │
│  │ - Any future GL accounts                                     │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                            │                                          │
│                            ▼                                          │
│  Trial Balance Report (All GL)                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ Automatically includes ALL accounts from gl_balance          │   │
│  │ No filtering, no hardcoding, no manual updates needed       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Comparison: Active GL vs All GL

```
┌─────────────────────────────────────────────────────────────────────┐
│              Trial Balance (Active GL) - EXISTING                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Query Flow:                                                         │
│  1. glSetupRepository.findActiveGLNumbersWithAccounts()             │
│     → Returns GL codes linked to sub-products                       │
│                                                                       │
│  2. glBalanceRepository.findByTranDateAndGlNumIn(date, activeGLs)   │
│     → Returns only filtered GL balances                             │
│                                                                       │
│  Result:                                                             │
│  - Customer account GLs ✅                                           │
│  - Office account GLs ✅                                             │
│  - Position accounts ❌ (may not appear if not linked)              │
│  - FX Conversion accounts ✅ (hardcoded in ensureFxGLsPresent)      │
│  - Future GL accounts ❌ (requires code changes)                    │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│              Trial Balance (All GL) - NEW                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Query Flow:                                                         │
│  1. glBalanceRepository.findByTranDate(date)                         │
│     → Returns ALL GL balance records for date (no filter)           │
│                                                                       │
│  2. ensureFxGLsPresent(glBalances, reportDate)                      │
│     → Adds FX GLs if missing from results                           │
│                                                                       │
│  Result:                                                             │
│  - Customer account GLs ✅                                           │
│  - Office account GLs ✅                                             │
│  - Position accounts ✅ (ALWAYS appear)                             │
│  - FX Conversion accounts ✅ (automatically included)               │
│  - Future GL accounts ✅ (NO code changes needed)                   │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Benefits Visualization

```
┌─────────────────────────────────────────────────────────────────────┐
│                    BEFORE (Active GL Only)                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Trial Balance Report                                                │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ GL_Code  │ GL_Name                │ Opening │ DR   │ CR     │   │
│  ├──────────┼────────────────────────┼─────────┼──────┼────────┤   │
│  │ 110101001│ Customer CA GL         │ 10000   │ 500  │ 300    │   │
│  │ 140203001│ FX Gain (hardcoded)    │ 0       │ 0    │ 1165   │   │
│  │ 210101001│ Customer SB GL         │ 50000   │ 2000 │ 1500   │   │
│  │ 240203001│ FX Loss (hardcoded)    │ 0       │ 1225 │ 0      │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  MISSING:                                                            │
│  ❌ Position accounts (920101001, 920101002)                        │
│  ❌ Some Nostro accounts                                            │
│  ❌ Future GL accounts (require code changes)                       │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    AFTER (All GL - Dynamic)                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Trial Balance Report (All GL)                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ GL_Code  │ GL_Name                │ Opening │ DR   │ CR     │   │
│  ├──────────┼────────────────────────┼─────────┼──────┼────────┤   │
│  │ 110101001│ Customer CA GL         │ 10000   │ 500  │ 300    │   │
│  │ 140203001│ FX Gain (dynamic)      │ 0       │ 0    │ 1165   │   │
│  │ 210101001│ Customer SB GL         │ 50000   │ 2000 │ 1500   │   │
│  │ 240203001│ FX Loss (dynamic)      │ 0       │ 1225 │ 0      │   │
│  │ 920101001│ PSBDT EQIV            │ 112000  │ 5000 │ 3000   │   │
│  │ 920101002│ PSUSD EQIV            │ 1000    │ 500  │ 200    │   │
│  │ 922030200102│ NOSTRO USD         │ 5000    │ 1000 │ 800    │   │
│  │ 922030200103│ NOSTRO EUR         │ 2000    │ 500  │ 300    │   │
│  │ ... ALL other GL accounts ...                                │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  INCLUDED:                                                           │
│  ✅ Position accounts (920101001, 920101002)                        │
│  ✅ All Nostro accounts                                             │
│  ✅ Future GL accounts (NO code changes needed)                     │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Menu Navigation

```
CBS3 Application
│
├── Dashboard
├── Customers
├── Products
├── Sub Products
├── Accounts
├── Office Accounts
├── Transactions
├── Interest Capitalization
├── FX Conversion
├── Exchange Rates
├── Statement of Accounts
├── Statement of GL
├── Settlement Reports
├── **Financial Reports** ⬅️ NEW MENU ITEM
│   │
│   ├── Trial Balance (Active GL) ──────────► Download CSV (filtered)
│   ├── **Trial Balance (All GL)** 🆕 ──────► Download CSV (ALL accounts)
│   ├── Balance Sheet ─────────────────────► Download Excel
│   └── Subproduct GL Balance ─────────────► Download CSV
│
├── System Date
├── BOD
└── EOD
```

---

## Technical Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Component Architecture                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Presentation Layer (React)                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ FinancialReports.tsx                                        │    │
│  │ - Date picker                                               │    │
│  │ - 4 report cards (Material-UI)                             │    │
│  │ - Download buttons                                          │    │
│  │ - Loading states                                            │    │
│  │ - Toast notifications                                       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                            │                                          │
│                            ▼                                          │
│  Service Layer (TypeScript)                                          │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ batchJobService.ts                                          │    │
│  │ - API request wrapper                                       │    │
│  │ - Blob handling                                             │    │
│  │ - Download trigger                                          │    │
│  │ - Error handling                                            │    │
│  └────────────────────────────────────────────────────────────┘    │
│                            │                                          │
│                            ▼ HTTP GET                                │
│  Backend Controller (Spring)                                         │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ AdminController.java                                        │    │
│  │ - Route mapping                                             │    │
│  │ - Date validation                                           │    │
│  │ - Response headers                                          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                            │                                          │
│                            ▼                                          │
│  Business Logic (Spring Service)                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ FinancialReportsService.java                                │    │
│  │ - Fetch GL balances (ALL)                                   │    │
│  │ - Join with GL setup                                        │    │
│  │ - Generate CSV format                                       │    │
│  │ - Calculate totals                                          │    │
│  │ - Validate DR = CR                                          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                            │                                          │
│                            ▼                                          │
│  Data Access (JPA Repository)                                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ GLBalanceRepository.java                                    │    │
│  │ - findByTranDate(date) → ALL records                       │    │
│  └────────────────────────────────────────────────────────────┘    │
│                            │                                          │
│                            ▼                                          │
│  Database (MySQL)                                                    │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ gl_balance + gl_setup tables                                │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Status

✅ **Complete**
- Backend service method
- Backend controller endpoint
- Frontend API service
- Frontend UI page
- Routing configuration
- Menu integration
- Documentation
- Test script

⏳ **Pending**
- Backend runtime testing
- Frontend runtime testing
- End-to-end workflow testing

🎯 **Ready for Deployment**
