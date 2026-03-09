# EOD Step 8 Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EOD ORCHESTRATION SERVICE                           │
│                                                                             │
│  executeEOD()                                                               │
│    │                                                                         │
│    ├─→ Batch Job 1: Account Balance Update                                 │
│    ├─→ Batch Job 2: Interest Accrual Transaction Update                    │
│    ├─→ Batch Job 3: Interest Accrual GL Movement Update                    │
│    ├─→ Batch Job 4: GL Movement Update                                     │
│    ├─→ Batch Job 5: GL Balance Update                                      │
│    ├─→ Batch Job 6: Interest Accrual Account Balance Update                │
│    ├─→ Batch Job 7: MCT Revaluation                                        │
│    │                                                                         │
│    ├─→ Batch Job 8: Financial Reports Generation ◄── NEW IMPLEMENTATION    │
│    │      │                                                                 │
│    │      └──→ EODStep8ConsolidatedReportService.generateConsolidatedReport()│
│    │                                                                         │
│    └─→ Batch Job 9: System Date Increment                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                │
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              EOD STEP 8 CONSOLIDATED REPORT SERVICE                         │
│                                                                             │
│  generateConsolidatedReport(eodDate)                                        │
│    │                                                                         │
│    ├─→ Create XSSFWorkbook                                                  │
│    │                                                                         │
│    ├─→ generateTrialBalanceSheet()                                          │
│    │      ├── Fetch active GLs (SET-BASED)                                 │
│    │      ├── Fetch GL balances for eodDate (SET-BASED)                    │
│    │      └── Build sheet with totals                                       │
│    │                                                                         │
│    ├─→ generateBalanceSheetSheet()                                          │
│    │      ├── Fetch balance sheet GLs (SET-BASED)                          │
│    │      ├── Separate Liabilities (1*) and Assets (2*)                    │
│    │      └── Build side-by-side layout                                     │
│    │                                                                         │
│    ├─→ generateSubproductGLBalanceSheet()                                   │
│    │      ├── Fetch all active subproducts (SET-BASED)                     │
│    │      ├── FOR EACH subproduct:                                          │
│    │      │     ├── calculateSubproductBalances()                          │
│    │      │     │     ├── Fetch customer accounts (SET-BASED)              │
│    │      │     │     ├── Fetch office accounts (SET-BASED)                │
│    │      │     │     ├── Bulk fetch account balances (SET-BASED)          │
│    │      │     │     ├── Bulk fetch LCY balances (SET-BASED)              │
│    │      │     │     └── Calculate totals (IN-MEMORY)                     │
│    │      │     └── Create hyperlink to detail sheet                        │
│    │      ├── Group by BDT vs FCY                                           │
│    │      ├── Sub-group by GL                                               │
│    │      └── Build sheet with subtotals                                    │
│    │                                                                         │
│    └─→ generateAccountBalanceSheets()                                       │
│           ├── FOR EACH subproduct:                                          │
│           │     ├── fetchAccountBalances()                                  │
│           │     │     ├── Fetch customer accounts (SET-BASED)              │
│           │     │     ├── Fetch office accounts (SET-BASED)                │
│           │     │     ├── Bulk fetch balances (SET-BASED)                  │
│           │     │     └── Bulk fetch LCY balances (SET-BASED)              │
│           │     ├── Group by BDT vs FCY                                     │
│           │     ├── Sub-group by GL                                         │
│           │     ├── generateAccountBalanceDetailSheet()                     │
│           │     │     ├── IF accounts.isEmpty()                            │
│           │     │     │    └── Show "No Data Available"                    │
│           │     │     ├── ELSE                                              │
│           │     │     │    ├── Build BDT section                           │
│           │     │     │    │    ├── Data rows                              │
│           │     │     │    │    ├── GL subtotals                           │
│           │     │     │    │    └── BDT grand total                        │
│           │     │     │    └── Build FCY section                           │
│           │     │     │         ├── Data rows                              │
│           │     │     │         ├── GL subtotals                           │
│           │     │     │         ├── FCY grand total (LCY)                  │
│           │     │     │         ├── GL balance                             │
│           │     │     │         └── Difference (LCY - GL)                  │
│           │     │     └── Apply formatting & freeze panes                   │
│           │     └── truncateSheetName() to 31 chars                        │
│           └── Return workbook as byte[]                                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                │
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         REPOSITORY LAYER                                    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │ GLBalanceRepository                                         │           │
│  │  - findByTranDate(date)                                     │           │
│  │  - findByTranDateAndGlNumIn(date, glNums)                   │           │
│  │  - findByGlNumAndTranDate(glNum, date)                      │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │ GLSetupRepository                                           │           │
│  │  - findActiveGLNumbersWithAccounts()                        │           │
│  │  - findBalanceSheetGLNumbersWithAccounts()                  │           │
│  │  - findById(glNum)                                          │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │ SubProdMasterRepository                                     │           │
│  │  - findAllActiveSubProducts()                               │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │ CustAcctMasterRepository                                    │           │
│  │  - findBySubProductSubProductId(subProductId)               │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │ OFAcctMasterRepository                                      │           │
│  │  - findBySubProductSubProductId(subProductId)               │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │ AcctBalRepository                                           │           │
│  │  - findByAccountNoInAndTranDate(accountNos, date)           │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────┐           │
│  │ AcctBalLcyRepository                                        │           │
│  │  - findByAccountNoInAndTranDate(accountNos, date)           │           │
│  └─────────────────────────────────────────────────────────────┘           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                │
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATABASE LAYER                                      │
│                                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ GL_Balance   │  │ GL_Setup     │  │ Prod_Master  │  │ Cust_Acct_   │   │
│  │              │  │              │  │              │  │ Master       │   │
│  │ - gl_num     │  │ - gl_num     │  │ - sub_       │  │ - account_no │   │
│  │ - tran_date  │  │ - gl_name    │  │   product_id │  │ - sub_       │   │
│  │ - opening_   │  │ - ...        │  │ - sub_       │  │   product_id │   │
│  │   bal        │  │              │  │   product_   │  │ - acct_name  │   │
│  │ - dr_        │  │              │  │   code       │  │ - ...        │   │
│  │   summation  │  │              │  │ - sub_       │  │              │   │
│  │ - cr_        │  │              │  │   product_   │  │              │   │
│  │   summation  │  │              │  │   name       │  │              │   │
│  │ - closing_   │  │              │  │ - cum_gl_num │  │              │   │
│  │   bal        │  │              │  │ - ...        │  │              │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│                                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                     │
│  │ OF_Acct_     │  │ Acct_Bal     │  │ Acct_Bal_Lcy │                     │
│  │ Master       │  │              │  │              │                     │
│  │ - account_no │  │ - account_no │  │ - account_no │                     │
│  │ - sub_       │  │ - tran_date  │  │ - tran_date  │                     │
│  │   product_id │  │ - account_   │  │ - closing_   │                     │
│  │ - acct_name  │  │   ccy        │  │   bal_lcy    │                     │
│  │ - ...        │  │ - current_   │  │ - ...        │                     │
│  │              │  │   balance    │  │              │                     │
│  │              │  │ - ...        │  │              │                     │
│  └──────────────┘  └──────────────┘  └──────────────┘                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Excel Workbook Structure

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     EOD_Step8_Consolidated_Report.xlsx                      │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ├─ [Sheet 1] Trial Balance
    │     └─ GL_Code | GL_Name | Opening_Bal | DR_Sum | CR_Sum | Closing_Bal
    │
    ├─ [Sheet 2] Balance Sheet
    │     └─ Liabilities (left) | [gap] | Assets (right)
    │
    ├─ [Sheet 3] Subproduct GL Balance Report
    │     │
    │     ├─ === BDT ACCOUNTS ===
    │     │     │
    │     │     ├─ GL 110101001
    │     │     │     ├─ [CM001] Call Money Overnight → [HYPERLINK to Sheet 4]
    │     │     │     └─ [CM002] Call Money Weekly → [HYPERLINK to Sheet 5]
    │     │     │     └─ GL Subtotal: 5,000,000.00
    │     │     │
    │     │     └─ BDT Grand Total: 5,000,000.00
    │     │
    │     └─ === FCY ACCOUNTS ===
    │           │
    │           ├─ GL 210101001
    │           │     ├─ [TM001] Term Money USD → [HYPERLINK to Sheet 6]
    │           │     └─ GL Subtotal: 100,000 USD | 10,000,000 BDT
    │           │
    │           ├─ FCY Grand Total (LCY): 10,000,000.00
    │           ├─ GL Balance: 10,000,000.00
    │           └─ Difference: 0.00
    │
    ├─ [Sheet 4] Call Money Overnight
    │     │
    │     ├─ === BDT ACCOUNTS ===
    │     │     │
    │     │     ├─ GL 110101001
    │     │     │     ├─ CM001001 | Customer A | N/A | 1,000,000.00
    │     │     │     ├─ CM001002 | Customer B | N/A | 2,000,000.00
    │     │     │     └─ GL Subtotal: 3,000,000.00
    │     │     │
    │     │     └─ BDT Grand Total: 3,000,000.00
    │     │
    │     └─ === FCY ACCOUNTS ===
    │           └─ (none)
    │
    ├─ [Sheet 5] Call Money Weekly
    │     └─ No Data Available for EOD Date: 15-Mar-2024
    │
    └─ [Sheet 6] Term Money USD
          │
          └─ === FCY ACCOUNTS ===
                │
                ├─ GL 210101001
                │     ├─ TM001001 | Customer C | 10,000.00 | 1,000,000.00
                │     ├─ TM001002 | Customer D | 20,000.00 | 2,000,000.00
                │     └─ GL Subtotal: 30,000.00 | 3,000,000.00
                │
                ├─ FCY Grand Total (LCY): 3,000,000.00
                ├─ GL Balance: 3,000,000.00
                └─ Difference: 0.00
```

---

## REST API Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CLIENT APPLICATION                                  │
│                                                                             │
│  Web Browser / Postman / cURL                                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                │
                                │ HTTP POST
                                │ /api/eod-step8/generate-consolidated-report
                                │ ?eodDate=2024-03-15
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   EOD STEP 8 CONSOLIDATED REPORT CONTROLLER                 │
│                                                                             │
│  @RestController                                                            │
│  @RequestMapping("/api/eod-step8")                                          │
│                                                                             │
│  generateConsolidatedReport(LocalDate eodDate)                              │
│    │                                                                         │
│    ├─→ Validate request                                                     │
│    ├─→ Call reportService.generateConsolidatedReport(eodDate)               │
│    ├─→ Set response headers (Content-Type, filename)                        │
│    └─→ Return byte[] as ResponseEntity                                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                │
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              EOD STEP 8 CONSOLIDATED REPORT SERVICE                         │
│                                                                             │
│  @Service                                                                   │
│  @Transactional(readOnly = true)                                            │
│                                                                             │
│  generateConsolidatedReport(LocalDate eodDate)                              │
│    └─→ [See detailed flow in main diagram above]                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                │
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CLIENT APPLICATION                                  │
│                                                                             │
│  Receives:                                                                  │
│    - Content-Type: application/vnd...spreadsheetml.sheet                    │
│    - Content-Disposition: attachment; filename="EOD_Step8_...xlsx"          │
│    - Body: Binary Excel file (byte[])                                       │
│                                                                             │
│  Downloads: EOD_Step8_Consolidated_Report_2024-03-15.xlsx                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Data Aggregation Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     SUBPRODUCT: Call Money Overnight                        │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ├─→ Fetch Customer Accounts (SET-BASED)
    │     └─→ [CM001001, CM001002, CM001003]
    │
    ├─→ Fetch Office Accounts (SET-BASED)
    │     └─→ [OFCM001, OFCM002]
    │
    ├─→ Combine All Account Numbers
    │     └─→ allAccountNos = [CM001001, CM001002, CM001003, OFCM001, OFCM002]
    │
    ├─→ Bulk Fetch Account Balances (SET-BASED)
    │     │   acctBalRepository.findByAccountNoInAndTranDate(allAccountNos, eodDate)
    │     │
    │     └─→ Map<String, AcctBal> acctBalMap = {
    │           "CM001001" -> AcctBal(acc_no=CM001001, ccy=BDT, bal=1000000),
    │           "CM001002" -> AcctBal(acc_no=CM001002, ccy=USD, bal=10000),
    │           "CM001003" -> AcctBal(acc_no=CM001003, ccy=BDT, bal=500000),
    │           "OFCM001"  -> AcctBal(acc_no=OFCM001, ccy=BDT, bal=2000000),
    │           "OFCM002"  -> AcctBal(acc_no=OFCM002, ccy=BDT, bal=1500000)
    │         }
    │
    ├─→ Bulk Fetch LCY Balances (SET-BASED)
    │     │   acctBalLcyRepository.findByAccountNoInAndTranDate(allAccountNos, eodDate)
    │     │
    │     └─→ Map<String, AcctBalLcy> acctBalLcyMap = {
    │           "CM001002" -> AcctBalLcy(acc_no=CM001002, lcy_bal=1000000)
    │         }
    │
    ├─→ Process Each Account (IN-MEMORY JAVA LOOP)
    │     │
    │     ├─→ CM001001 (BDT): bal=1000000, lcy=1000000
    │     ├─→ CM001002 (USD): bal=10000, lcy=1000000 (from acctBalLcyMap)
    │     ├─→ CM001003 (BDT): bal=500000, lcy=500000
    │     ├─→ OFCM001  (BDT): bal=2000000, lcy=2000000
    │     └─→ OFCM002  (BDT): bal=1500000, lcy=1500000
    │
    ├─→ Group by Currency (IN-MEMORY STREAMS)
    │     │
    │     ├─→ BDT Accounts: [CM001001, CM001003, OFCM001, OFCM002]
    │     │     └─→ Total LCY: 5,000,000
    │     │
    │     └─→ FCY Accounts: [CM001002]
    │           ├─→ Total FCY: 10,000 USD
    │           └─→ Total LCY: 1,000,000
    │
    ├─→ Sub-Group by GL (IN-MEMORY STREAMS)
    │     │
    │     ├─→ GL 110101001 (BDT): 5,000,000 LCY
    │     └─→ GL 110101002 (FCY): 10,000 USD | 1,000,000 LCY
    │
    └─→ Generate Excel Sheet
          │
          ├─→ Write BDT Section
          │     ├─→ Headers
          │     ├─→ Data rows (grouped by GL)
          │     ├─→ GL Subtotals
          │     └─→ BDT Grand Total
          │
          └─→ Write FCY Section
                ├─→ Headers
                ├─→ Data rows (grouped by GL)
                ├─→ GL Subtotals
                ├─→ FCY Grand Total (LCY)
                ├─→ GL Balance (from GL_Balance table)
                └─→ Difference (LCY Total - GL Balance)
```

---

## Key Performance Optimizations

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     PERFORMANCE OPTIMIZATION STRATEGIES                     │
└─────────────────────────────────────────────────────────────────────────────┘

1. SET-BASED QUERIES (Not Row-by-Row)
   ════════════════════════════════════
   ❌ BAD:  for (accountNo : accountNos) {
              acctBalRepository.findByAccountNo(accountNo);  // N queries!
            }

   ✅ GOOD: acctBalRepository.findByAccountNoInAndTranDate(accountNos, date);
                                                             // 1 query!

2. BULK DATA FETCHING (Map-Based Lookups)
   ═══════════════════════════════════════
   ❌ BAD:  for (accountNo : accountNos) {
              AcctBal bal = acctBalMap.get(accountNo);
              if (bal == null) {
                bal = acctBalRepository.findByAccountNo(accountNo);  // Query!
              }
            }

   ✅ GOOD: Map<String, AcctBal> acctBalMap = acctBalRepository
              .findByAccountNoInAndTranDate(accountNos, date)
              .stream()
              .collect(Collectors.toMap(AcctBal::getAccountNo, ab -> ab));
            // O(1) lookups, no additional queries!

3. IN-MEMORY GROUPING (Java Streams)
   ══════════════════════════════════
   ✅ GOOD: Map<String, List<AccountBalanceDetail>> groups = accounts.stream()
              .collect(Collectors.groupingBy(a -> 
                LCY.equals(a.getCurrency()) ? "BDT" : "FCY"));
            // Fast in-memory grouping

4. WORKBOOK IN-MEMORY (ByteArrayOutputStream)
   ═══════════════════════════════════════════
   ✅ GOOD: try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
              workbook.write(out);
              return out.toByteArray();
            }
            // No file I/O, pure in-memory

5. SINGLE QUERY PER TABLE (Not Per Subproduct)
   ════════════════════════════════════════════
   ❌ BAD:  for (subproduct : subproducts) {
              List<CustAcctMaster> accounts = 
                custAcctMasterRepository.findBySubProductSubProductId(
                  subproduct.getId());  // N queries!
            }

   ✅ GOOD: // Actually, this is acceptable because we need to process
            // each subproduct separately for sheet generation.
            // The optimization is in the ACCOUNT BALANCE fetching,
            // which uses bulk queries.
```

---

*This architecture diagram provides a comprehensive overview of the EOD Step 8 enhancement implementation.*
