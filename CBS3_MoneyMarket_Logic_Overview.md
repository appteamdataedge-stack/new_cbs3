# CBS3 Money Market Module — Technical Logic Overview

**Version**: 1.0 | **Date**: 2026-04-16  
**Purpose**: Authoritative reference for migrating CBS3 from Java Spring Boot + MySQL to Python (FastAPI/Django) + PostgreSQL/MS SQL + Stored Procedures

---

## Table of Contents

1. [System Architecture Overview](#1-system-architecture-overview)
2. [FX Conversion (FXC) Module](#2-fx-conversion-fxc-module)
3. [Multi-Currency Transaction (MCT) Settlement](#3-multi-currency-transaction-mct-settlement)
4. [Interest Accrual & Capitalization](#4-interest-accrual--capitalization)
5. [Deal Booking Module](#5-deal-booking-module)
6. [EOD Batch Processing (9 Steps)](#6-eod-batch-processing-9-steps)
7. [Database Schema & Key Tables](#7-database-schema--key-tables)
8. [Critical Business Rules & Validations](#8-critical-business-rules--validations)
9. [Migration Considerations: Java → Python + Stored Procedures](#9-migration-considerations-java--python--stored-procedures)

---

## 1. System Architecture Overview

### 1.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     FRONTEND (React/TypeScript)              │
│   /transactions  /fx-conversion  /deal-booking  /eod-jobs   │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTP REST (port 8082)
┌───────────────────────────▼─────────────────────────────────┐
│               SPRING BOOT API (Java 21)                      │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │  Controllers │  │   Services   │  │   Repositories    │  │
│  │  (HTTP layer)│→ │ (Business    │→ │   (JPA/Hibernate  │  │
│  │              │  │  Logic)      │  │    → MySQL)        │  │
│  └──────────────┘  └──────────────┘  └───────────────────┘  │
└───────────────────────────┬─────────────────────────────────┘
                            │ JDBC
┌───────────────────────────▼─────────────────────────────────┐
│                    MySQL (moneymarketdb)                      │
│  tran_table  acc_bal  acct_bal_lcy  gl_balance  fx_position  │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Module Structure and Layer Responsibilities

| Layer | Java Class Type | Responsibility |
|-------|----------------|----------------|
| **Controller** | `@RestController` | Parse HTTP request, call service, return JSON response |
| **Service** | `@Service @Transactional` | All business logic — WAE calculation, ledger building, validation |
| **Repository** | `@Repository` (Spring Data JPA) | SQL queries; returns entity objects or projections |
| **Entity** | `@Entity` | Maps to database table columns; Hibernate manages SQL |
| **DTO** | Plain Java class | Request/response shapes; not persisted |

**Key principle**: All accounting logic lives in the Service layer. Controllers are thin — they only translate HTTP to service calls and catch exceptions into error responses.

### 1.3 Core Service Dependencies

```
TransactionService
    ├── BalanceService           (WAE computation, balance read/write)
    ├── TransactionValidationService (pre-posting checks)
    ├── MultiCurrencyTransactionService (FCY settlement detection)
    ├── CurrencyValidationService (currency/GL routing)
    ├── ExchangeRateService      (MID rate lookup from fx_rate_master)
    ├── UnifiedAccountService    (resolve account → GL, type, currency)
    └── ValueDatePostingService  (past/future-dated transaction logic)

FxConversionService
    ├── BalanceService
    ├── ExchangeRateService
    └── SystemDateService        (system date from parameter_table)

EODOrchestrationService
    ├── AccountBalanceUpdateService  (Job 1)
    ├── InterestAccrualService       (Job 2)
    ├── InterestAccrualGLMovementService (Job 3)
    ├── GLMovementUpdateService      (Job 4)
    ├── GLBalanceUpdateService       (Job 5)
    ├── InterestAccrualAccountBalanceService (Job 6)
    ├── RevaluationService           (Job 7 — bypassed)
    └── FinancialReportsService      (Job 8)
```

### 1.4 Transaction Flow: Frontend → Database

```
1. User submits transaction on React UI
2. POST /api/transactions/create  →  TransactionController.createTransaction()
3. TransactionService.createTransaction():
   a. Check BOD executed (if deal schedules exist for today)
   b. Validate transaction balance (DR LCY == CR LCY, or FCY totals match)
   c. Validate value date (within ±30 days)
   d. For each line: validate account exists, currency matches, balance sufficient
   e. Resolve exchange rate per leg (WAE for settlement trigger, MID for others)
   f. Build TranTable entities in Entry status
   g. Save to tran_table (maker stage)
4. Checker calls POST /api/transactions/{id}/post  →  postTransaction()
5. postTransaction():
   a. Re-read transaction lines from DB
   b. Update acc_bal (current_balance, dr/cr_summation)
   c. Update acct_bal_lcy (LCY side)
   d. Build GLMovement entries
   e. If FCY settlement: build gain/loss rows (tran_table rows -3, -4)
   f. Change TranStatus → Posted
6. Final checker calls POST /api/transactions/{id}/verify  →  Verified status
```

**Transaction ID format**: `T{yyyyMMdd}{6-random-digits}`, lines appended as `-1`, `-2`, etc.  
**Deal booking IDs**: `D{yyyyMMdd}{9-random-digits}` (no line suffix; each posting is a standalone row).

---

## 2. FX Conversion (FXC) Module

### 2.1 Concept

FX Conversion records the bank buying or selling foreign currency against BDT. It involves:
- A **Nostro** (correspondent bank) account in FCY
- A **Customer** account in BDT
- Two internal **Position** GL accounts (FCY side + BDT equivalent side)
- An optional **Gain/Loss** GL entry if the deal rate differs from WAE

**Service**: `FxConversionService.java`  
**Controllers**: `FxConversionController.java` (BUYING), `FxConversionSellingController.java` (SELLING)

---

### 2.2 FX BUYING Transaction (Bank buys FCY from customer)

The customer deposits FCY into the bank's Nostro; the bank credits the customer's BDT account at the deal rate.

**Ledger Structure** (4 lines, `tranType = 'FXC'`, `tranSubType = 'BUYING'`):

```
Line -1: DR  Nostro Account       FCY amount   @ deal rate   → LCY = FCY × deal_rate
Line -2: CR  Position FCY GL      FCY amount   @ deal rate   → LCY = FCY × deal_rate
Line -3: DR  Position BDT GL      BDT amount   @ 1.0000      → LCY = same BDT amount
Line -4: CR  Customer Account     BDT amount   @ 1.0000      → LCY = same BDT amount
```

**WHY this structure**:
- Lines -1 and -2 move FCY from the customer (via Nostro) into the bank's position book.
- Lines -3 and -4 move the BDT equivalent out of the bank's BDT position and into the customer's account.
- The two Position GL accounts (`920101001` = BDT, `920101002` = FCY equivalent) track the bank's open FCY position. Debit BDT position + Credit FCY position = no net change to the position account pair.

**No gain/loss in BUYING** — the deal rate is used uniformly for both FCY and BDT legs. WAE is only needed when relieving (crediting) the Nostro, which happens in SELLING.

---

### 2.3 FX SELLING Transaction (Bank sells FCY to customer)

The customer buys FCY from the bank; the bank's Nostro is credited (FCY returns to Nostro), and the customer's BDT account is debited at the deal rate.

**Rates Used**:
- **WAE1** = Nostro WAE (weighted average LCY cost of FCY held in the Nostro account)
- **WAE2** = Position FCY WAE (weighted average LCY cost of the bank's FCY position)
- **Deal Rate** = agreed exchange rate with the customer
- **MID Rate** = market mid-rate from `fx_rate_master`

**WAE Calculation Method**:

```java
// FxConversionService.calculateWaeFromTranTable()
// Reads acc_bal + acct_bal_lcy for the account on system date
WAE = Total LCY Balance / Total FCY Balance
    = (prev_day_closing_lcy + today_cr_lcy - today_dr_lcy)
    / (prev_day_closing_fcy + today_cr_fcy - today_dr_fcy)
// If FCY balance == 0: returns null → caller falls back to MID rate
```

**Position WAE2 Calculation**:

```java
// FxConversionService.calculatePositionWae2OnTheFly()
// Reads gl_balance for Position FCY GL (920101002) and Position BDT GL (920101001)
WAE2 = gl_balance(positionBdtGl).closing_bal
     / gl_balance(positionFcyGl).closing_bal
// Absolute value always used in postings (sign comes from DR/CR flag)
```

**Ledger Structure** (4–5 lines):

```
Step -1: CR  Nostro Account       FCY amount  @ WAE1   → LCY = FCY × WAE1   (lcyEquiv2)
Step -2: DR  Position FCY GL      FCY amount  @ WAE2   → LCY = FCY × WAE2   (lcyEquiv1)
Step -3: CR  Position BDT GL      lcyEquiv1   @ 1.0    → LCY = lcyEquiv1
Step -4: CR/DR [GAIN/LOSS GL]     |step4|     @ 1.0    → LCY = |step4|     (conditional)
Step -5: DR  Customer Account     lcyEquiv    @ 1.0    → LCY = FCY × dealRate
```

**Force-Balance Gain/Loss Formula**:

```
lcyEquiv  = FCY × deal_rate  (customer pays at deal rate)
lcyEquiv1 = FCY × WAE2       (position relieved at WAE2)
lcyEquiv2 = FCY × WAE1       (nostro relieved at WAE1)

DR_without_Step4 = lcyEquiv1 + lcyEquiv
CR_without_Step4 = lcyEquiv2 + lcyEquiv1

step4_amount = DR_without_Step4 - CR_without_Step4
             = lcyEquiv - lcyEquiv2
             = FCY × (deal_rate - WAE1)

if step4_amount > 0: DR Customer more than CR Nostro → GAIN → CR GL 140203001
if step4_amount < 0: CR Customer less than DR Nostro → LOSS → DR GL 240203001
if step4_amount = 0: No step 4 inserted
```

**WHY force-balance instead of `|MID - WAE| × FCY`?**  
Independent rounding of legs creates a ±0.01 BDT discrepancy. Computing step 4 as the residual of already-rounded legs ensures the ledger always balances to the cent.

**Nostro Credit Validation**:
- Office asset accounts (Nostro, GL starts with `922x`) may only be credited up to their existing balance.
- They cannot go positive (bank cannot lend via the Nostro).
- `validateOfficeAssetAccountBalance()` checks available balance ≥ FCY amount before allowing the SELLING credit.

---

### 2.4 FX Conversion GL Account Reference

| Account | GL Number | Role |
|---------|-----------|------|
| Position USD (FCY) | `920101002` | FCY leg of position; DR on SELLING, CR on BUYING |
| Position USD (BDT) | `920101001` | BDT equivalent leg; CR on SELLING, DR on BUYING |
| Realised Forex Gain | `140203001` | CR when SELLING gain |
| Realised Forex Loss | `240203001` | DR when SELLING loss |
| MCT FX Gain | `140203002` | CR when settlement gain (MCT module) |
| MCT FX Loss | `240203002` | DR when settlement loss (MCT module) |

---

## 3. Multi-Currency Transaction (MCT) Settlement

### 3.1 Concept

MCT covers FCY-denominated transactions between customer or office accounts — for example, settling a USD interbank payment or transferring USD between two accounts. When FCY moves between accounts with **different LCY costs** (different WAEs), a foreign exchange gain or loss must be recognised.

**Service**: `TransactionService.java` (methods `isSettlementTransaction`, `buildFcySettlementRows`)

---

### 3.2 Settlement Detection

Settlement applies when:
1. **Exactly 2 lines**, **same non-BDT currency** (e.g., both USD)
2. AND one of:
   - Liability DR + Asset CR (cross-type: most common)
   - Liability DR + Liability CR (liability-to-liability: only DR triggers)
   - Asset CR + Asset DR (asset-to-asset: only CR triggers)

```java
// TransactionService.isSettlementTransaction()
// Returns false for: BDT transactions, mixed currencies, 3+ lines, Asset DR + Liability CR
```

**WHY Asset DR + Liability CR does not trigger settlement**: An asset account being debited in FCY simply increases an FCY receivable at MID (no realised gain/loss). The credit to a liability in FCY increases what is owed at MID. No FCY has actually changed hands between the bank and a counterparty.

---

### 3.3 Rate Assignment Per Leg

| Leg Type | Rate Used | WHY |
|----------|-----------|-----|
| Liability DR | WAE of that account | FCY is being paid out; realised at the account's carrying cost |
| Asset CR | WAE of that account | FCY is being received; realised at the account's carrying cost |
| Liability CR | MID | FCY received into liability at market rate; not yet realised |
| Asset DR | MID | FCY paid from asset at market rate; not yet realised |

```java
// TransactionService.createTransaction() — rate selection
boolean isSettlementTrigger = isSettlement && ((liability && isDr) || (asset && isCr));
if (isSettlementTrigger) {
    exchangeRate = waeFromAccBal(accountNo);   // WAE from acc_bal.WAE_Rate
} else {
    exchangeRate = midRateFcy;                 // MID from fx_rate_master
}
lcyAmt = fcyAmt × exchangeRate   (rounded to 2 decimal places)
```

---

### 3.4 Gain/Loss Row Generation

After the 2 main legs are built, `buildFcySettlementRows()` appends rows `-3` (and `-4` if two triggers exist).

**Formula per triggered leg**:

```
legLcy = leg.lcyAmt          (already rounded, fcyAmt × WAE)
midLcy = fcyAmt × MID        (rounded)
gainLossAmount = |legLcy - midLcy|

For Liability DR:
    MID < WAE → bank paid out at WAE, market says MID → bank got a good rate → GAIN
    MID > WAE → bank paid out at WAE, market says MID → bank paid less than market → LOSS

For Asset CR:
    MID > WAE → bank received more at MID than WAE carrying cost → GAIN
    MID < WAE → bank received less at MID than WAE carrying cost → LOSS
```

```
Gain: CR GL 140203002  (FX Gain)
Loss: DR GL 240203002  (FX Loss)
Both gain/loss rows: accountNo = NULL, glNum set directly
```

**WHY compute from rounded leg amounts instead of `|MID - WAE| × FCY`**:  
The leg LCY was already rounded independently. If both legs are rounded, then `leg_lcy - mid_lcy` gives the exact difference without a second rounding error. The accounting ledger balances exactly.

---

### 3.5 Full MCT Transaction Example

**Scenario**: Transfer 10 USD from Liability account A (WAE=114.0) to Liability account B (MID=115.5)

```
Input legs:
  Line -1: DR  AcctA (Liability)  10 USD  @ WAE=114.0  → LCY = 1140.00
  Line -2: CR  AcctB (Liability)  10 USD  @ MID=115.5  → LCY = 1155.00

Settlement triggered by: Liability DR (Line -1) only

Gain/loss for Line -1:
  legLcy = 1140.00  (DR at WAE)
  midLcy = 1155.00  (10 × 115.5)
  amount = |1140 - 1155| = 15.00
  MID (115.5) > WAE (114.0) → LOSS for bank
  Line -3: DR GL 240203002  15.00 BDT

Final ledger (3 lines):
  DR  AcctA   10 USD  @ 114.0  = 1140.00 BDT
  CR  AcctB   10 USD  @ 115.5  = 1155.00 BDT
  DR  FXLoss  (GL only)         =   15.00 BDT

Check: Total DR = 1140 + 15 = 1155 = Total CR ✓
```

---

## 4. Interest Accrual & Capitalization

### 4.1 Daily Accrual (EOD Job 2)

**Service**: `InterestAccrualService.java`

Every account with a non-zero balance accrues interest each business day.

**Accrual Formula**:
```
Daily Interest = |Balance| × (Annual_Rate / 100) / 36500 × 1 day
```

**Interest Rate Source**:
- Deal accounts (GL `1102xxxx`, `2102xxxx`): Fixed rate from `sub_prod_master`
- Running / savings accounts: Latest rate from `interest_rate_master` + `interest_increment`

**Two accrual entries per account** (stored in `intt_accr_tran`, prefix `S`):
```
Entry -1: DR  Interest Expense GL   (DR_CR_FLAG = 'D', status = Pending)
Entry -2: CR  Interest Payable GL   (DR_CR_FLAG = 'C', status = Pending)
```

**ID format**: `S{yyyyMMdd}{9-digit-seq}-1` / `-2`

EOD Job 3 (`InterestAccrualGLMovementService`) moves these from Pending → Posted and creates `gl_movement_accrual` records.

---

### 4.2 Interest Capitalization (Manual)

**Service**: `InterestCapitalizationService.java`  
**Endpoint**: `POST /api/interest-capitalization/{acctNum}/capitalize`

**When**: A banker manually triggers capitalization to pay accumulated interest to the customer's account.

**For BDT accounts** (simple case):
- DR Interest Payable GL  @ 1.0
- CR Customer Account     @ 1.0
- No gain/loss entry

**For FCY accounts** (3 entries):
```
Entry -1: DR  Interest Expense GL    @ accrual_WAE   → LCY = FCY × accrualWAE
Entry -2: CR  Customer Account       @ MID            → LCY = FCY × MID
Entry -3: [GAIN/LOSS] if WAE ≠ MID:
    if accrualWAE > MID: DR GL 240203002  (Loss: interest expense > MID credit)
    if accrualWAE < MID: CR GL 140203002  (Gain)
```

**Accrual WAE Calculation**:
```java
// From intt_accr_tran S-type CREDIT entries (DR_CR_FLAG='C') since last cap date
accrualWAE = SUM(lcy_amt) / SUM(fcy_amt)
// These credit entries record the FCY amount and the LCY cost at time of accrual
```

**After capitalization**:
- `acct_bal_accrual.closing_bal` reduced by capitalized FCY amount
- `acc_bal.WAE_Rate` updated:
  ```
  new_WAE = (old_fcy × old_wae + cap_fcy × accrual_wae) / (old_fcy + cap_fcy)
  ```

---

## 5. Deal Booking Module

### 5.1 Concept

Deals are fixed-term deposits (TD) or loans with a defined principal, rate, tenor, and maturity. Each deal:
1. Creates a dedicated **deal account** (separate from the customer's operative account)
2. Posts an **initial funding transaction** (moves money between operative and deal account)
3. Generates a **schedule of events** (INT_PAY, MAT_PAY) for BOD processing

**Service**: `DealBookingService.java`  
**Endpoint**: `POST /api/deals/create`

---

### 5.2 Sub-Products

| Code | Type | Interest | GL Pattern |
|------|------|----------|------------|
| TDPIP | Liability TD | Non-compounding | `1102xxxx` |
| TDCUM | Liability TD | Compounding | `1102xxxx` |
| ODATD | Asset Loan | Non-compounding | `2102xxxx` |
| STLTR | Asset Loan | Compounding | `2102xxxx` |

These codes must exist in `sub_prod_master` before deal booking works.

---

### 5.3 Deal Creation Flow

```
1. Validate request (amount, tenor, dates, currency, customer)
2. Check BOD executed (if any schedules exist today)
3. Load customer and operative account
4. Determine sub-product code from (dealType, interestType)
5. Calculate maturityDate = valueDate + tenor (days)
6. Generate deal account number (formatted per GL pattern)
7. Create CustAcctMaster record for deal account
8. Initialize AcctBal record (zero balance; for Asset: loanLimit = dealAmount)
9. Post initial funding:
   - Liability: DR Operative → CR Deal Account
   - Asset:     CR Operative ← DR Deal Account
   (Direct TranTable insert, status = Posted, bypasses maker-checker)
10. Generate schedules → insert into deal_schedule
```

**Why Asset deals set `loanLimit = dealAmount`**: Balance validation checks `available_balance = current_balance + loan_limit`. For loans, the account starts at zero but the bank is allowing a draw-down of `dealAmount`, so loanLimit reserves that capacity for the initial DR to pass.

---

### 5.4 Schedule Generation

**Non-compounding**:
```
INT_PAY on maturity date  →  amount = principal × rate × tenor / 36500
MAT_PAY on maturity date  →  amount = principal
```

**Compounding**: Interest periods at each frequency interval; each INT_PAY credits the deal account (capitalizing), MAT_PAY = principal + all accumulated interest.

**Schedule table**: `deal_schedule`  
Key columns: `account_number`, `event_code` (INT_PAY/MAT_PAY), `schedule_date`, `schedule_amount`, `status` (PENDING/EXECUTED/FAILED), `operative_account_no`

---

### 5.5 BOD Schedule Execution

**Service**: `BODSchedulerService.java`  
**Endpoint**: `POST /api/deals/bod/execute?businessDate=yyyy-MM-dd`

Each schedule runs in a `REQUIRES_NEW` transaction for isolation (one failure does not roll back others).

**INT_PAY accounting**:
```
Liability: DR Interest Payable GL   CR Deal Account  (accrued interest paid to customer)
Asset:     DR Loan Account          CR Interest Income GL  (bank collects interest)
```

**MAT_PAY accounting**:
```
Liability: DR Deal Account  CR Operative Account  (principal returned to customer)
Asset:     DR Loan Account  CR Operative Account  (principal collected by bank)
Amount = sum of INT_PAY credits + principal
```

**BOD Blocking Rule**: If `deal_schedule` contains rows for today (count > 0) AND BOD has not been executed for today, ALL transaction creation (MCT and deal booking) is blocked with `BODNotExecutedException`.

---

## 6. EOD Batch Processing (9 Steps)

**Service**: `EODOrchestrationService.java`  
**Endpoint**: `POST /api/eod/execute`

The entire EOD runs in a single outer `@Transactional`. Each job is logged to `eod_log_table`. System date is incremented only after all post-EOD validations pass.

```
┌──────────────────────────────────────────────────────────────┐
│ Pre-EOD Validations                                          │
│   • All transactions in Entry status? (none unposted)        │
│   • System date lock check                                   │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 1: Account Balance Update (AccountBalanceUpdateService)   │
│   • For every account: compute opening/closing for today     │
│   • closing_bal = opening + cr_sum - dr_sum (FCY)            │
│   • closing_bal_lcy = opening_lcy + cr_lcy - dr_lcy          │
│   • CRITICAL: carry forward prev day closing_bal_lcy directly│
│     (do NOT recompute as FCY × MID — this would reset WAE)   │
│   • Also updates fx_position table per GL + currency         │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 2: Interest Accrual (InterestAccrualService)             │
│   • For every active account: create 2 intt_accr_tran rows   │
│   • Status = Pending                                         │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 3: Accrual GL Movement (InterestAccrualGLMovementService) │
│   • Move Pending intt_accr_tran entries → Posted             │
│   • Create gl_movement_accrual records                       │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 4: GL Movement Update (GLMovementUpdateService)          │
│   • Post all Posted tran_table entries to gl_movement        │
│   • Updates running GL balance after each posting            │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 5: GL Balance Update (GLBalanceUpdateService)            │
│   • Compute closing_bal for each GL on system date           │
│   • Stores in gl_balance table                               │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 6: Accrual Account Balance (InterestAccrualAccountBalanceService)│
│   • Update acct_bal_accrual.closing_bal for each account     │
│   • Reflects accrued but not yet capitalized interest        │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 7: MCT Revaluation (RevaluationService) — CURRENTLY BYPASSED│
│   • Purpose: Revalue all FCY balances at end-of-day MID rate │
│   • Creates unrealised gain/loss entries in reval_summary    │
│   • BYPASSED because: revaluation logic conflicts with WAE   │
│     management approach — forced to carry forward historical │
│     cost; MID-based revaluation creates WAE reset on next day│
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 8: Financial Reports (FinancialReportsService)           │
│   • Trial Balance: all GL accounts, DR/CR totals, difference │
│   • Balance Sheet: grouped by GL hierarchy (Asset/Liability/ │
│     Equity), difference = Asset - Liability - Equity         │
│   • Account Balance Report: per-account snapshot             │
│   • Subproduct GL Balance Report                             │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Post-EOD Validations                                         │
│   • Reports integrity check                                  │
│   • If failed: EOD does NOT increment system date            │
└────────────────────────┬─────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────┐
│ Job 9: System Date Increment                                 │
│   • UPDATE parameter_table SET system_date = system_date + 1 │
└─────────────────────────────────────────────────────────────-┘
```

### 6.1 WAE Staleness Bug (Fixed)

**Root cause**: In old code, EOD Job 1 set `opening_bal_lcy = FCY_closing × MID_rate`. On the next day, `WAE = opening_lcy / opening_fcy = MID`. Every night the WAE was reset to MID.

**Fix**: Carry forward `prev_day.closing_bal_lcy` directly into `new_day.opening_bal_lcy`. Do not recalculate from FCY × MID.

---

## 7. Database Schema & Key Tables

### 7.1 `tran_table` — All Transaction Postings

**Purpose**: Every debit or credit ever made is one row in this table. The "transaction" is a group of rows sharing a base `tran_id`.

| Column | Type | Notes |
|--------|------|-------|
| `tran_id` | VARCHAR | PK. Format `T{date}{random}-{lineNum}` or `D{date}{random}` |
| `tran_date` | DATE | System date at time of posting |
| `value_date` | DATE | Effective date (may differ from tran_date) |
| `dr_cr_flag` | ENUM('D','C') | Debit or Credit |
| `tran_status` | ENUM | Entry / Posted / Future / Verified |
| `account_no` | VARCHAR NULL | Customer/office account; NULL for GL-only rows |
| `gl_num` | VARCHAR | GL account number (always populated) |
| `tran_ccy` | VARCHAR | Currency of this leg (USD, BDT, etc.) |
| `fcy_amt` | DECIMAL(18,4) | Foreign currency amount |
| `exchange_rate` | DECIMAL(10,4) | WAE or MID used for this leg |
| `lcy_amt` | DECIMAL(18,2) | BDT equivalent = FCY × exchange_rate |
| `tran_type` | VARCHAR | 'MCT', 'FXC', 'INT', 'DEAL', 'BOD' |
| `tran_sub_type` | VARCHAR | 'BUYING', 'SELLING', 'INT_PAY', 'MAT_PAY' |
| `narration` | VARCHAR | Description |
| `deal_rate` | DECIMAL(10,4) | FXC only: customer-agreed rate |
| `mid_rate` | DECIMAL(10,4) | FXC only: market mid rate |
| `wae_rate` | DECIMAL(10,4) | WAE rate at time of posting |
| `gain_loss_amt` | DECIMAL(18,2) | FXC: step-4 gain/loss amount |

**Key constraint**: `account_no` is nullable (V31 migration). Settlement and gain/loss rows have `account_no = NULL` and `gl_num` set directly.

---

### 7.2 `acc_bal` — FCY Account Balance Snapshot

**Purpose**: End-of-day FCY balance per account per date. Also holds `current_balance` (intraday running balance) and `wae_rate`.

| Column | Notes |
|--------|-------|
| `account_no` | FK to cust_acct_master or of_acct_master |
| `tran_date` | Date of snapshot |
| `opening_bal` | Previous day's closing_bal (FCY) |
| `dr_summation` | Sum of FCY debits for this date |
| `cr_summation` | Sum of FCY credits for this date |
| `closing_bal` | = opening_bal + cr_summation - dr_summation |
| `current_balance` | Real-time balance (updated per transaction) |
| `available_balance` | = current_balance + loan_limit |
| `wae_rate` | DECIMAL(10,4) NULL — updated by EOD or capitalization |
| `account_ccy` | Currency code |

**WAE source priority** (in `BalanceService`):
1. `acc_bal.wae_rate` column (V36 — added explicitly)
2. Fallback: iterate `acct_bal_lcy` records newest-first, compute `closing_bal_lcy / closing_bal`
3. Fallback 2: MID rate (if FCY balance is zero)

---

### 7.3 `acct_bal_lcy` — BDT Balance Snapshot

**Purpose**: Parallel to `acc_bal` but in BDT (LCY). Stores the LCY side of each account's balance history.

| Column | Notes |
|--------|-------|
| `account_no` | FK |
| `tran_date` | Date of snapshot |
| `opening_bal_lcy` | Carried forward from prev day closing_bal_lcy (NOT FCY × MID) |
| `dr_summation_lcy` | Sum of LCY debits |
| `cr_summation_lcy` | Sum of LCY credits |
| `closing_bal_lcy` | = opening_bal_lcy + cr_lcy - dr_lcy |

---

### 7.4 `gl_balance` — GL-Level Balance Snapshot

**Purpose**: Daily closing balance per GL account, per currency.

| Column | Notes |
|--------|-------|
| `gl_num` | GL account code |
| `tran_date` | Date |
| `ccy` | Currency (BDT, USD, etc.) |
| `opening_bal` | Previous closing |
| `closing_bal` | Current day closing |
| `current_balance` | Real-time (updated by GLBalanceUpdateService) |

---

### 7.5 `fx_position` — FX Position Tracking

**Purpose**: Tracks the bank's open FCY position per position GL account per day.

| Column | Notes |
|--------|-------|
| `tran_date` | Date |
| `position_gl_num` | GL of the position account (e.g. 920101002) |
| `position_ccy` | Currency |
| `opening_bal` | Previous day closing |
| `dr_summation` | FCY debits today |
| `cr_summation` | FCY credits today |
| `closing_bal` | Net position |

---

### 7.6 `gl_setup` — Chart of Accounts

**Purpose**: Master list of all GL accounts with type and normal balance direction.

| Column | Notes |
|--------|-------|
| `gl_num` | 9-digit GL code |
| `gl_name` | Human-readable name |
| `gl_type` | Asset / Liability / Income / Expense / Equity |
| `debit_normal` | TRUE if normal balance is debit (Assets, Expenses) |
| `position_account` | TRUE for Position GLs (these are in acc_bal but not of_acct_master) |

**GL hierarchy rule for Balance Sheet**:
- GL starts with `1` → Liability
- GL starts with `2` → Asset
- GL starts with `3` → Equity
- GL starts with `4` or `5` → Income / Expense

---

### 7.7 `cust_acct_master` — Customer Account Master

| Column | Notes |
|--------|-------|
| `account_no` | PK |
| `cust_id` | FK to cust_master |
| `gl_num` | GL this account belongs to |
| `account_ccy` | Currency |
| `sub_product` | FK to sub_prod_master |
| `tenor` | Days (for deal accounts) |
| `date_maturity` | Computed from value_date + tenor |
| `last_interest_payment_date` | Updated after each INT_PAY |
| `loan_limit` | For asset deal accounts = deal amount |
| `account_status` | Active / Inactive / Closed |

---

### 7.8 `deal_schedule` — BOD Event Queue

| Column | Notes |
|--------|-------|
| `schedule_id` | PK |
| `account_number` | Deal account |
| `operative_account_no` | Where principal/interest is returned |
| `event_code` | 'INT_PAY' or 'MAT_PAY' |
| `schedule_date` | Date BOD should execute this event |
| `schedule_amount` | Pre-computed payment amount |
| `status` | PENDING / EXECUTED / FAILED |
| `executed_at` | Timestamp of execution |
| `failure_reason` | Error message if FAILED |

---

### 7.9 `intt_accr_tran` — Interest Accrual Entries

| Column | Notes |
|--------|-------|
| `accr_tran_id` | PK. Format `S{yyyyMMdd}{9-seq}-1` or `-2` |
| `account_no` | FK |
| `dr_cr_flag` | D (expense) or C (payable) |
| `fcy_amt` | FCY accrual amount |
| `lcy_amt` | BDT equivalent |
| `exchange_rate` | Rate at time of accrual |
| `tran_date` | Accrual date |
| `status` | Pending / Posted |

---

### 7.10 Key GL Accounts Summary

| Name | GL Number | Account No | Purpose |
|------|-----------|------------|---------|
| PSUSD (BDT leg) | `920101001` | — | Position BDT equivalent; DR on BUYING, CR on SELLING |
| PSUSD EQIV (FCY leg) | `920101002` | — | Position USD; CR on BUYING, DR on SELLING |
| Realised Forex Gain (FXC) | `140203001` | — | SELLING gain CR |
| Realised Forex Loss (FXC) | `240203001` | — | SELLING loss DR |
| MCT FX Gain (MCT settlement) | `140203002` | — | Settlement gain CR |
| MCT FX Loss (MCT settlement) | `240203002` | — | Settlement loss DR |

---

## 8. Critical Business Rules & Validations

### 8.1 Double-Entry Integrity

Every transaction must balance before saving:
- **Same FCY transactions** (e.g., both legs USD): `SUM(fcy_dr) = SUM(fcy_cr)`
- **Mixed/BDT transactions**: `SUM(lcy_dr) = SUM(lcy_cr)`
- Gain/loss rows are injected by the system after user input to maintain balance — users cannot submit an unbalanced ledger.

### 8.2 Nostro Account Credit Rule

Office accounts with GL `922x` (Nostro/correspondent accounts) are asset accounts. They hold FCY on behalf of the bank. **They cannot have a positive balance on the credit side** — i.e., the bank cannot owe FCY to its own Nostro.

Validation: `available_balance ≥ amount` before crediting. If the credit would push the balance above zero (positive), it is rejected.

### 8.3 Maker-Checker Workflow

```
Maker (Entry status) → Checker POST (Posted status) → Final POST (Verified)
```
- **Entry**: Saved to `tran_table` but no balance updates
- **Posted**: Balance updated, GL movements created
- **Verified**: Audit trail snapshot in `txn_hist_acct`
- **Reversal**: Auto-created in Verified status (bypasses maker-checker)

FXC and Deal Booking transactions bypass maker-checker — they are created directly in `Posted` (or `Entry` for FXC).

### 8.4 Value Date Rules

- Value date within ±30 days of system date
- **Past**: Posts normally, THEN calculates backdated interest delta
- **Future**: Status = Future; balances NOT updated until that date becomes today

### 8.5 WAE Refresh Timing

| Event | WAE Refreshed |
|-------|--------------|
| EOD Job 1 runs | acc_bal.wae_rate updated from closing balances |
| Intraday transaction | WAE read live from acc_bal; wae_rate column used if present |
| Interest capitalization | acc_bal.wae_rate updated with weighted blend |
| Revaluation (Job 7) | Bypassed; would otherwise reset WAE to MID |

### 8.6 BOD Blocking

If `deal_schedule` has ANY rows for today's system date AND `bod_execution_log` has no record for today:
- `createTransaction()` → blocked
- `createDeal()` → blocked
- Error: `BODNotExecutedException` with count of pending schedules

### 8.7 Account Currency Matching

Each transaction leg's `tran_ccy` must equal the account's `account_ccy`. No cross-currency posting on a single leg (cross-currency is handled by having two separate legs).

### 8.8 Ledger Rounding Rule

Always round at the **final step** using `RoundingMode.HALF_UP` to 2 decimal places for LCY, 4 decimal places for rates. Never round intermediate values (e.g., compute `FCY × WAE` fully before rounding to 2dp). This prevents cumulative rounding errors in multi-leg transactions.

---

## 9. Migration Considerations: Java → Python + Stored Procedures

### 9.1 Layer Mapping

| Java Layer | Target Python/SP Layer | Notes |
|-----------|------------------------|-------|
| `@RestController` | FastAPI router / Django view | Thin: parse request, call SP or Python service, return JSON |
| `@Service @Transactional` | Stored Procedure (core math) + Python orchestration | Split: calculation in SP, orchestration in Python |
| `@Repository` (JPQL) | SP or raw SQL / ORM query | Convert JPQL aggregates to SQL |
| `@Entity` (Hibernate) | SQLAlchemy model / Django model | 1:1 table mapping |
| `@Scheduled` (EOD/BOD) | Celery Beat / APScheduler / cron | Same job logic in SP |

---

### 9.2 Stored Procedure Boundaries

**Put in Stored Procedures** (atomic, multi-table, ACID):
- WAE calculation per account (reads `acc_bal` + `acct_bal_lcy`)
- FCY settlement gain/loss row generation (inside SP: `buildFcySettlementRows`)
- Interest accrual entry creation (Job 2)
- EOD account balance rollover (Job 1)
- Deal schedule execution — INT_PAY and MAT_PAY accounting
- GL balance snapshot update (Job 5)

**Keep in Python** (orchestration, HTTP, validation):
- Request parsing and response formatting
- BOD blocking check
- Value date validation
- Pagination and reporting queries
- Maker-checker workflow state management

---

### 9.3 Critical JPQL → SQL Conversions

#### WAE Calculation (intraday)

```sql
-- Java: BalanceService.calculateLiveWAEFromAccBal()
-- Equivalent SQL (PostgreSQL/MS SQL):
SELECT
    (ab.opening_bal_lcy + COALESCE(cr.cr_lcy,0) - COALESCE(dr.dr_lcy,0))
    / NULLIF(
        (ab.opening_bal + COALESCE(cr.cr_fcy,0) - COALESCE(dr.dr_fcy,0)), 0
    ) AS wae_rate
FROM acct_bal ab
LEFT JOIN (
    SELECT account_no, SUM(lcy_amt) cr_lcy, SUM(fcy_amt) cr_fcy
    FROM tran_table
    WHERE tran_date = :date AND dr_cr_flag = 'C' AND tran_status = 'Posted'
    GROUP BY account_no
) cr ON cr.account_no = ab.account_no
LEFT JOIN (
    SELECT account_no, SUM(lcy_amt) dr_lcy, SUM(fcy_amt) dr_fcy
    FROM tran_table
    WHERE tran_date = :date AND dr_cr_flag = 'D' AND tran_status = 'Posted'
    GROUP BY account_no
) dr ON dr.account_no = ab.account_no
WHERE ab.account_no = :accountNo
  AND ab.tran_date = (
      SELECT MAX(tran_date) FROM acct_bal WHERE account_no = :accountNo AND tran_date < :date
  );
```

#### Settlement Row Insert (SP pattern)

```sql
-- SP: sp_build_settlement_rows(p_tran_id, p_account_no, p_fcy, p_wae, p_mid, p_dr_cr)
DECLARE @leg_lcy   DECIMAL(18,2) = ROUND(@p_fcy * @p_wae, 2);
DECLARE @mid_lcy   DECIMAL(18,2) = ROUND(@p_fcy * @p_mid, 2);
DECLARE @gl_amount DECIMAL(18,2) = ABS(@leg_lcy - @mid_lcy);

IF @gl_amount > 0
BEGIN
    -- Determine gain or loss based on leg type and rate comparison
    -- Insert into tran_table with account_no = NULL, gl_num = gain/loss GL
    INSERT INTO tran_table (tran_id, dr_cr_flag, account_no, gl_num, lcy_amt, ...)
    VALUES (@p_tran_id + '-3', @dr_cr, NULL, @gl_num, @gl_amount, ...);
END
```

---

### 9.4 Transaction Management

**Java**: `@Transactional(isolation = REPEATABLE_READ)` on service methods — Spring manages COMMIT/ROLLBACK automatically.

**Python + SP equivalent**:
```python
# FastAPI with SQLAlchemy
async with db.begin():           # START TRANSACTION
    await db.execute(             # Call SP for core logic
        text("CALL sp_post_transaction(:tran_id, :user_id)"),
        {"tran_id": tran_id, "user_id": user_id}
    )
    # If SP raises an exception: ROLLBACK automatically
    # On success: COMMIT
```

**For EOD** (long multi-step transaction): Use a single DB connection/transaction spanning all 9 jobs, or use compensating transactions per job with a master `eod_log_table` tracking which jobs completed.

---

### 9.5 Architecture Decisions for SP-based Python Migration

#### Option A: Thin Python, Fat SP (Recommended)
- Python: HTTP parsing, auth, request validation, response serialization
- SP: All accounting math (WAE, gain/loss, ledger inserts, balance updates)
- **PRO**: Keeps ACID guarantees, easy to test SP logic in isolation
- **CON**: Harder to unit-test SP logic without DB

#### Option B: Fat Python, Thin SP
- Python: All business logic including WAE, ledger building
- SP: Only batch operations (EOD balance rollover)
- **PRO**: Easier testing (mock DB calls)
- **CON**: Risk of partial commits if Python crashes mid-transaction

**Recommendation**: Use **Option A** for EOD batch and deal booking (critical ACID); **Option B** style for reporting and balance inquiry.

---

### 9.6 Key Migration Pitfalls to Avoid

1. **WAE reset on EOD**: Do NOT recompute `opening_bal_lcy = closing_bal_fcy × MID`. Always carry forward `closing_bal_lcy` directly.

2. **Rounding sequence matters**: Round LCY amounts to 2dp per leg, THEN compute gain/loss as the difference. Do not compute `|MID - WAE| × FCY` and round — this causes ±0.01 imbalance.

3. **NULL account_no on GL rows**: Settlement, gain/loss, and interest GL-only rows have `account_no = NULL`. Ensure your ORM/SP handles nullable FKs correctly.

4. **Negative exchange rates (historical bug)**: WAE from signed balance ratios can be negative if the balance is negative (e.g., Nostro over-credited). Always take `.abs()` before using WAE as an exchange rate in ledger postings.

5. **Position accounts are not in `of_acct_master`**: Position GLs (`920101001`, `920101002`) live in `acc_bal` and `gl_balance` but are not customer accounts. Balance lookups for these must hit `gl_balance`, not `cust_acct_master`.

6. **BOD must execute before any intraday transactions**: Enforce this at the API gateway layer, not just in each service.

7. **Double-write bug (historical)**: When EOD Job 1 and intraday transaction both update `acc_bal.current_balance` concurrently, use `SELECT FOR UPDATE` / pessimistic lock to avoid lost updates.

8. **Interest divisor is 36500 (not 365)**: `|balance| × rate / 36500` — the extra factor of 100 converts the percentage rate to a decimal. Do not change this.

---

### 9.7 Suggested SP Structure

```
sp_fxc_buying(p_tran_id, p_customer, p_nostro, p_ccy, p_fcy, p_deal_rate, p_mid_rate)
sp_fxc_selling(p_tran_id, p_customer, p_nostro, p_ccy, p_fcy, p_deal_rate, p_wae1, p_wae2)
sp_mct_post_transaction(p_tran_id, p_lines JSON)
  └── sp_build_settlement_rows(p_tran_id, p_legs, p_mid)
sp_eod_job1_balance_update(p_eod_date)
sp_eod_job2_interest_accrual(p_eod_date)
sp_eod_job3_accrual_gl(p_eod_date)
sp_eod_job4_gl_movement(p_eod_date)
sp_eod_job5_gl_balance(p_eod_date)
sp_eod_job6_accrual_balance(p_eod_date)
sp_eod_job8_reports(p_eod_date)
sp_eod_increment_system_date(p_eod_date)
sp_bod_execute_schedule(p_schedule_id)
sp_deal_book(p_request JSON)
sp_capitalize_interest(p_account_no, p_user_id)
sp_compute_wae(p_account_no, p_date) RETURNS DECIMAL
```

---

*End of Document*

**Maintained by**: CBS3 Migration Team  
**Source Code**: `C:\new_cbs3\cbs3\moneymarket\src\main\java\com\example\moneymarket\`  
**Database**: `moneymarketdb` on MySQL (localhost:3306)  
**Current Version**: V39 (Flyway schema migration)
