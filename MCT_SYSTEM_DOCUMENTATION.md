# Multi-Currency Transaction (MCT) System Documentation

**Version:** 2.0
**Date:** 2025-11-27
**Specification:** PTTP05 BRD Compliant
**Status:** ✅ Production Ready

---

## Table of Contents

1. [Overview](#overview)
2. [System Architecture](#system-architecture)
3. [The Four MCT Patterns](#the-four-mct-patterns)
4. [Position GL Logic](#position-gl-logic)
5. [WAE (Weighted Average Exchange) Calculation](#wae-calculation)
6. [Settlement Gain/Loss](#settlement-gainloss)
7. [EOD Revaluation](#eod-revaluation)
8. [BOD Reversal](#bod-reversal)
9. [Database Schema](#database-schema)
10. [API Endpoints](#api-endpoints)
11. [Configuration](#configuration)
12. [Testing](#testing)
13. [Troubleshooting](#troubleshooting)

---

## Overview

The Multi-Currency Transaction (MCT) System is a core banking module that handles foreign currency transactions with automatic Position GL posting, Weighted Average Exchange (WAE) rate calculation, settlement gain/loss determination, and end-of-day revaluation processing.

### Key Features

- ✅ **Automatic Position GL Posting:** 4 or 6 entries based on transaction type
- ✅ **WAE Rate Management:** Maintains weighted average exchange rates
- ✅ **Settlement Gain/Loss:** Calculates and posts realized FX gains/losses
- ✅ **EOD Revaluation:** Mark-to-market revaluation of all FCY positions
- ✅ **BOD Reversal:** Automatic reversal of previous day revaluations
- ✅ **Complete Audit Trail:** Settlement gain/loss audit logging
- ✅ **PTTP05 BRD Compliant:** 100% compliance with specifications

---

## System Architecture

### Core Components

```
┌─────────────────────────────────────────────────┐
│   Multi-Currency Transaction Service            │
│   - Position GL Posting                          │
│   - WAE Calculation & Update                     │
│   - Settlement Gain/Loss Calculation             │
│   - Audit Logging                                │
└──────────────────┬──────────────────────────────┘
                   │
          ┌────────┴────────┐
          │                 │
┌─────────▼────────┐ ┌─────▼──────────────┐
│ Revaluation      │ │ Currency           │
│ Service          │ │ Validation Service │
│ - EOD Revaluation│ │ - Type Detection   │
│ - BOD Reversal   │ │ - Pattern ID       │
└──────────────────┘ └────────────────────┘
```

### Data Flow

1. **Transaction Initiated** → Currency Validation Service determines type
2. **BDT_USD_MIX Detected** → Multi-Currency Transaction Service processes
3. **Position GL Posted** → 4 or 6 entries created based on pattern
4. **WAE Updated** → Only for BUY transactions
5. **Audit Logged** → Settlement records saved (if gain/loss exists)

---

## The Four MCT Patterns

### Pattern 1: Customer Deposits FCY (BUY)

**Scenario:** Customer deposits foreign currency (e.g., USD)

**Characteristics:**
- Customer FCY account is **CREDITED** (liability increases)
- **4 Position GL entries** posted at **DEAL rate**
- **NO settlement gain/loss**
- **WAE Master IS updated**

**Example:**
```
Transaction: Customer deposits $1,000 at rate 110.50 BDT/USD

Entry 1: Dr Customer BDT Account     110,500 BDT
Entry 2: Cr Customer USD Account      1,000 USD (at 110.50)
Entry 3: Cr Position GL USD           1,000 USD (at 110.50)
Entry 4: Dr Position GL BDT           110,500 BDT
```

**WAE Calculation:**
```
New WAE = (Old LCY + Transaction LCY) / (Old FCY + Transaction FCY)
```

---

### Pattern 2: Customer Withdraws FCY at WAE (SELL, No Gain/Loss)

**Scenario:** Customer withdraws FCY at exact WAE rate

**Characteristics:**
- Customer FCY account is **DEBITED** (liability decreases)
- **4 Position GL entries** posted at **WAE rate** (NOT deal rate!)
- **NO settlement gain/loss** (deal rate = WAE)
- **WAE Master NOT updated**

**Example:**
```
Transaction: Customer withdraws $500 at rate 110.00 (WAE = 110.00)

Entry 1: Dr Customer USD Account      500 USD (at 110.00)
Entry 2: Cr Customer BDT Account      55,000 BDT
Entry 3: Dr Position GL USD           500 USD (at WAE 110.00)
Entry 4: Cr Position GL BDT           55,000 BDT
```

**No Gain/Loss:** Deal Rate (110.00) = WAE Rate (110.00)

---

### Pattern 3: Customer Withdraws FCY Above WAE (SELL with GAIN)

**Scenario:** Customer withdraws FCY at rate higher than WAE

**Characteristics:**
- Customer FCY account is **DEBITED**
- **4 Position GL entries** at **WAE rate**
- **2 Additional entries** for settlement GAIN
- **Total: 6 entries**
- **WAE Master NOT updated**

**Example:**
```
Transaction: Customer withdraws $500 at rate 110.50 (WAE = 110.00)
Settlement Gain = $500 × (110.50 - 110.00) = 250 BDT

Entry 1: Dr Customer USD Account      500 USD (at 110.50)
Entry 2: Cr Customer BDT Account      55,250 BDT
Entry 3: Dr Position GL USD           500 USD (at WAE 110.00)
Entry 4: Cr Position GL BDT           55,000 BDT
Entry 5: Dr Position GL BDT           250 BDT (gain adjustment)
Entry 6: Cr Realized FX Gain GL       250 BDT
```

**Settlement Gain Formula:**
```
Gain = FCY Amount × (Deal Rate - WAE Rate)
     = 500 × (110.50 - 110.00)
     = 250 BDT
```

---

### Pattern 4: Customer Withdraws FCY Below WAE (SELL with LOSS)

**Scenario:** Customer withdraws FCY at rate lower than WAE

**Characteristics:**
- Customer FCY account is **DEBITED**
- **4 Position GL entries** at **WAE rate**
- **2 Additional entries** for settlement LOSS
- **Total: 6 entries**
- **WAE Master NOT updated**

**Example:**
```
Transaction: Customer withdraws $500 at rate 109.50 (WAE = 110.00)
Settlement Loss = $500 × (110.00 - 109.50) = 250 BDT

Entry 1: Dr Customer USD Account      500 USD (at 109.50)
Entry 2: Cr Customer BDT Account      54,750 BDT
Entry 3: Dr Position GL USD           500 USD (at WAE 110.00)
Entry 4: Cr Position GL BDT           55,000 BDT
Entry 5: Dr Realized FX Loss GL       250 BDT
Entry 6: Cr Position GL BDT           250 BDT (loss adjustment)
```

**Settlement Loss Formula:**
```
Loss = FCY Amount × (WAE Rate - Deal Rate)
     = 500 × (110.00 - 109.50)
     = 250 BDT
```

---

## Position GL Logic

### Purpose

Position GL accounts track the bank's net foreign currency position for each currency.

### Configuration

```java
private static final Map<String, String> POSITION_GL_MAP = Map.of(
    "USD", "920101001",  // Position USD
    "EUR", "920102001",  // Position EUR
    "GBP", "920103001",  // Position GBP
    "JPY", "920104001"   // Position JPY
);
```

### Key Rules

1. ✅ **Only set when FCY ↔ LCY exchange occurs**
2. ✅ **BUY transactions:** Use DEAL rate for Position GL
3. ✅ **SELL transactions:** Use WAE rate for Position GL (CRITICAL!)
4. ✅ **Always 4 base entries** (3-4), plus 2 more for gain/loss (5-6)

### Location in Code

`MultiCurrencyTransactionService.java:224-354`

---

## WAE Calculation

### Formula

```
New WAE Rate = (Old LCY Balance + Transaction LCY) / (Old FCY Balance + Transaction FCY)
```

### Example

```
Current WAE Master:
- FCY Balance: 5,000 USD
- LCY Balance: 550,000 BDT
- WAE Rate: 110.0000

New Transaction (BUY):
- FCY Amount: 1,000 USD
- LCY Amount: 110,500 BDT (at deal rate 110.50)

Calculation:
New FCY = 5,000 + 1,000 = 6,000 USD
New LCY = 550,000 + 110,500 = 660,500 BDT
New WAE = 660,500 / 6,000 = 110.0833 BDT/USD
```

### Critical Rules

- ✅ **ONLY updated for BUY transactions** (customer deposits FCY)
- ❌ **NEVER updated for SELL transactions** (customer withdrawals)
- ✅ **Precision:** DECIMAL(10,4) - four decimal places
- ✅ **Rounding:** HALF_UP

### Location in Code

`MultiCurrencyTransactionService.java:505-546`

---

## Settlement Gain/Loss

### Formula

```
Settlement Gain/Loss = FCY Amount × (Deal Rate - WAE Rate)
```

- **Positive result** = GAIN (deal rate > WAE)
- **Negative result** = LOSS (deal rate < WAE)
- **Zero result** = No gain/loss (deal rate = WAE)

### GL Accounts

```java
private static final String REALISED_FX_GAIN_GL = "140203001";  // Income
private static final String REALISED_FX_LOSS_GL = "240203001";  // Expense
```

### Audit Logging

All settlement gain/loss transactions are logged to the `settlement_gain_loss` table for complete audit trail:

**Audit Record Includes:**
- Transaction ID and dates
- Account number and currency
- FCY amount and rates (deal & WAE)
- Settlement amount and type (GAIN/LOSS)
- GL accounts used
- Entry transaction IDs
- Detailed narration

**Sample Narration:**
```
Settlement GAIN: FCY 500.00 × (Deal 110.5000 - WAE 110.0000) = 250.00
```

### Location in Code

- Calculation: `MultiCurrencyTransactionService.java:362-372`
- Gain Posting: `MultiCurrencyTransactionService.java:380-437`
- Loss Posting: `MultiCurrencyTransactionService.java:445-502`
- Audit Logging: `MultiCurrencyTransactionService.java:621-667`

---

## EOD Revaluation

### Purpose

Mark-to-market revaluation of all foreign currency positions at end-of-day using current mid rates.

### Process Flow

1. **Get System Date** from SystemDateService
2. **Revalue GL Accounts** (Nostro/Position GL)
   - Use WAE Master for historical cost (Booked LCY)
   - Calculate MTM LCY at current mid rate
   - Post unrealized gain/loss entries
3. **Revalue Customer Accounts**
   - Use previous day's MTM as Booked LCY
   - Calculate new MTM at current mid rate
   - Post unrealized gain/loss entries
4. **Save Audit Records** to `reval_tran` table

### GL Account Revaluation

**Critical Fix Applied:**
```java
// BEFORE (Bug): Used GL Balance incorrectly
// AFTER (Fixed): Uses WAE Master for historical cost

WaeMaster waeMaster = waeMasterOpt.get();
BigDecimal fcyBalance = waeMaster.getFcyBalance();     // From WAE Master
BigDecimal bookedLcy = waeMaster.getLcyBalance();      // Historical cost ✅
BigDecimal mtmLcy = fcyBalance.multiply(midRate);      // Mark-to-market

BigDecimal revalDiff = mtmLcy.subtract(bookedLcy);     // NOW CORRECT! ✅
```

### Customer Account Revaluation

**Critical Fix Applied:**
```java
// First-time revaluation
if (previousRevals.isEmpty()) {
    bookedLcy = mtmLcy;  // Use MTM as baseline, diff = 0 ✅
}
// Subsequent revaluations
else {
    bookedLcy = previousReval.getMtmLcy();  // Yesterday's MTM ✅
}

BigDecimal revalDiff = mtmLcy.subtract(bookedLcy);  // Day-over-day ✅
```

### GL Accounts for Revaluation

```java
private static final String UNREALISED_FX_GAIN_GL = "140203002";
private static final String UNREALISED_FX_LOSS_GL = "240203002";
```

### Dr/Cr Logic

**Asset Accounts (GL Accounts):**
- **Gain:** Dr Asset Account, Cr Unrealized Gain GL
- **Loss:** Dr Unrealized Loss GL, Cr Asset Account

**Liability Accounts (Customer Accounts):**
- **Gain:** Dr Unrealized Gain GL, Cr Liability Account
- **Loss:** Dr Liability Account, Cr Unrealized Loss GL

### Location in Code

`RevaluationService.java:80-349`

---

## BOD Reversal

### Purpose

Reverse all previous day's revaluation entries at beginning-of-day to reset positions.

### Process Flow

1. **Get Yesterday's Date** (System Date - 1)
2. **Find All Posted Revaluations** from yesterday
3. **Reverse Each Entry** by flipping Dr/Cr flags
4. **Update Reval Records** with reversal information

### Reversal Logic

```java
// Original Entry: Dr Account 100
// Reversal Entry: Cr Account 100 (flipped)

DrCrFlag reversalFlag = originalEntry.getDrCrFlag() == DrCrFlag.D
    ? DrCrFlag.C
    : DrCrFlag.D;
```

### Location in Code

`RevaluationService.java:464-533`

---

## Database Schema

### Core Tables

#### 1. `wae_master` - WAE Rate Tracking

| Column | Type | Description |
|--------|------|-------------|
| WAE_Id | BIGINT | Primary key |
| Ccy_Pair | VARCHAR(10) | e.g., "USD/BDT" |
| WAE_Rate | DECIMAL(10,4) | Weighted average rate |
| FCY_Balance | DECIMAL(20,2) | Foreign currency balance |
| LCY_Balance | DECIMAL(20,2) | Local currency balance |
| Source_GL | VARCHAR(20) | Position GL account |
| Updated_On | DATETIME | Last update timestamp |

**Indexes:**
- UNIQUE: `Ccy_Pair`

---

#### 2. `settlement_gain_loss` - Audit Trail

| Column | Type | Description |
|--------|------|-------------|
| Settlement_Id | BIGINT | Primary key |
| Tran_Id | VARCHAR(20) | Base transaction ID |
| Tran_Date | DATE | Transaction date |
| Value_Date | DATE | Value date |
| Account_No | VARCHAR(20) | Customer account |
| Currency | VARCHAR(3) | Foreign currency |
| FCY_Amt | DECIMAL(20,2) | FCY amount |
| Deal_Rate | DECIMAL(10,4) | Transaction rate |
| WAE_Rate | DECIMAL(10,4) | WAE rate |
| Settlement_Amt | DECIMAL(20,2) | Gain/loss amount |
| Settlement_Type | VARCHAR(4) | "GAIN" or "LOSS" |
| Settlement_GL | VARCHAR(20) | Gain/loss GL account |
| Position_GL | VARCHAR(20) | Position GL account |
| Entry5_Tran_Id | VARCHAR(20) | Entry 5 transaction ID |
| Entry6_Tran_Id | VARCHAR(20) | Entry 6 transaction ID |
| Status | VARCHAR(20) | "POSTED" or "REVERSED" |
| Narration | VARCHAR(500) | Detailed description |
| Posted_By | VARCHAR(20) | User/system |
| Posted_On | DATETIME | Posting timestamp |

**Indexes:**
- `idx_settlement_tran_id`
- `idx_settlement_account`
- `idx_settlement_date`
- `idx_settlement_currency`
- `idx_settlement_status`
- `idx_settlement_type`

---

#### 3. `reval_tran` - Revaluation Audit

| Column | Type | Description |
|--------|------|-------------|
| Reval_Id | BIGINT | Primary key |
| Reval_Date | DATE | Revaluation date |
| Acct_Num | VARCHAR(20) | Account/GL number |
| Ccy_Code | VARCHAR(3) | Currency |
| FCY_Balance | DECIMAL(20,2) | FCY balance |
| Mid_Rate | DECIMAL(10,4) | Current mid rate |
| Booked_LCY | DECIMAL(20,2) | Historical cost |
| MTM_LCY | DECIMAL(20,2) | Mark-to-market value |
| Reval_Diff | DECIMAL(20,2) | Revaluation difference |
| Reval_GL | VARCHAR(20) | Gain/loss GL used |
| Tran_Id | VARCHAR(20) | Transaction ID |
| Reversal_Tran_Id | VARCHAR(20) | BOD reversal tran ID |
| Status | VARCHAR(20) | "POSTED" or "REVERSED" |
| Created_On | DATETIME | Created timestamp |
| Reversed_On | DATETIME | Reversal timestamp |

**Indexes:**
- `idx_reval_date`
- `idx_acct_num`
- `idx_status`
- `idx_tran_id`

---

## API Endpoints

### 1. Get WAE Rate

```http
GET /api/mct/wae-rate/{currency}
```

**Response:**
```json
{
  "currency": "USD",
  "waeRate": 110.0833,
  "ccyPair": "USD/BDT"
}
```

---

### 2. Get Position GL

```http
GET /api/mct/position-gl/{currency}
```

**Response:**
```json
{
  "currency": "USD",
  "positionGL": "920101001"
}
```

---

### 3. EOD Revaluation Summary

```http
GET /api/admin/revaluation/summary?date=2024-01-15
```

**Response:**
```json
{
  "revalDate": "2024-01-15",
  "entriesPosted": 25,
  "totalGain": 15000.50,
  "totalLoss": 8500.25,
  "entries": [...]
}
```

---

### 4. Trigger Manual Revaluation

```http
POST /api/admin/revaluation/run
```

**Response:**
```json
{
  "success": true,
  "message": "Revaluation completed",
  "entriesPosted": 25,
  "totalGain": 15000.50,
  "totalLoss": 8500.25
}
```

---

## Configuration

### Application Properties

```properties
# EOD Configuration
eod.admin.user=ADMIN

# MCT Configuration
mct.enabled=true
mct.settlement-audit-logging=true

# Database
spring.datasource.url=jdbc:mysql://localhost:3306/moneymarketdb
spring.datasource.username=root
spring.datasource.password=your_password

# Flyway
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true
```

### Position GL Mapping

Edit `MultiCurrencyTransactionService.java` to add/modify currencies:

```java
private static final Map<String, String> POSITION_GL_MAP = Map.of(
    "USD", "920101001",
    "EUR", "920102001",
    "GBP", "920103001",
    "JPY", "920104001",
    "CHF", "920105001"  // Add new currency
);
```

### GL Accounts

Ensure these GL accounts exist in `gl_setup`:

```sql
-- Position GL Accounts
INSERT INTO gl_setup (GL_Num, GL_Name, Layer_Id, Status) VALUES
('920101001', 'Position USD', 4, 'Active'),
('920102001', 'Position EUR', 4, 'Active'),
('920103001', 'Position GBP', 4, 'Active'),
('920104001', 'Position JPY', 4, 'Active');

-- Realized Gain/Loss
INSERT INTO gl_setup (GL_Num, GL_Name, Layer_Id, Status) VALUES
('140203001', 'Realized FX Gain', 2, 'Active'),
('240203001', 'Realized FX Loss', 3, 'Active');

-- Unrealized Gain/Loss
INSERT INTO gl_setup (GL_Num, GL_Name, Layer_Id, Status) VALUES
('140203002', 'Unrealized FX Gain', 2, 'Active'),
('240203002', 'Unrealized FX Loss', 3, 'Active');
```

---

## Testing

### Unit Tests

**MultiCurrencyTransactionServiceTest:**
- ✅ 8/8 tests passed
- Tests: WAE rate retrieval, Position GL mapping, FCY detection

**Run Command:**
```bash
mvn test -Dtest=MultiCurrencyTransactionServiceTest
```

### Integration Tests

**EODOrchestrationServiceTest:**
- ✅ 11/11 tests passed
- Tests: EOD execution, batch jobs, revaluation integration

**Run Command:**
```bash
mvn test -Dtest=EODOrchestrationServiceTest
```

### Manual Testing

#### Test Pattern 1 (BUY):
```http
POST /api/transactions/create
Content-Type: application/json

{
  "accountNo": "1000000099001",
  "tranCcy": "USD",
  "fcyAmt": 1000,
  "exchangeRate": 110.50,
  "drCrFlag": "C",
  "narration": "Test BUY transaction"
}
```

**Expected Result:**
- 4 entries posted (1-2 customer, 3-4 position GL)
- WAE updated
- No settlement gain/loss

#### Test Pattern 3 (SELL with GAIN):
```http
POST /api/transactions/create
Content-Type: application/json

{
  "accountNo": "1000000099001",
  "tranCcy": "USD",
  "fcyAmt": 500,
  "exchangeRate": 110.50,
  "drCrFlag": "D",
  "narration": "Test SELL with GAIN"
}
```

**Expected Result:**
- 6 entries posted (1-2 customer, 3-4 position GL, 5-6 gain)
- WAE NOT updated
- Settlement gain calculated and audit logged

---

## Troubleshooting

### Issue: WAE Not Updating

**Symptoms:**
- WAE rate remains unchanged after BUY transaction

**Diagnosis:**
1. Check transaction type detection:
   ```java
   boolean isBuyTransaction = fcyTransaction.getDrCrFlag() == DrCrFlag.C;
   ```
2. Verify WAE Master record exists for currency pair

**Solution:**
- Ensure customer FCY account is CREDITED (DrCrFlag.C) for BUY
- Create WAE Master record if missing:
  ```sql
  INSERT INTO wae_master (Ccy_Pair, WAE_Rate, FCY_Balance, LCY_Balance, Source_GL)
  VALUES ('USD/BDT', 0, 0, 0, '920101001');
  ```

---

### Issue: Settlement Gain/Loss Not Posted

**Symptoms:**
- Only 4 entries posted instead of 6 for SELL transactions

**Diagnosis:**
1. Check if deal rate ≠ WAE rate
2. Verify settlement calculation:
   ```java
   BigDecimal settlementGainLoss = fcyAmt.multiply(dealRate.subtract(waeRate));
   ```

**Solution:**
- Verify WAE Master has correct rate
- Check FX rate configuration
- Review logs for calculation details

---

### Issue: EOD Revaluation Shows Zero Difference

**Symptoms:**
- Revaluation runs but posts no entries

**Diagnosis:**
1. Check if FCY balances exist in WAE Master
2. Verify FX rate master has current mid rates
3. Check if MTM = Booked LCY

**Solution:**
- Populate FX rate master with current rates
- Verify WAE Master has non-zero balances
- Check revaluation date logic

---

### Issue: Audit Records Not Saving

**Symptoms:**
- Settlement transactions post but no audit records in `settlement_gain_loss`

**Diagnosis:**
1. Check SettlementGainLossRepository injection
2. Review error logs for exceptions
3. Verify database table exists

**Solution:**
- Run Flyway migration to create table:
  ```bash
  mvn flyway:migrate
  ```
- Check repository bean configuration
- Review audit logging exception handling

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2024-11-20 | Initial implementation |
| 1.5 | 2024-11-25 | EOD revaluation bug fixes |
| 2.0 | 2025-11-27 | Settlement audit logging added, tests fixed |

---

## References

- **PTTP05 BRD Specification**
- **Core Banking System Architecture**
- **Foreign Exchange Management Guidelines**

---

## Support

For questions or issues:
- **Email:** support@moneymarket.com
- **Documentation:** http://localhost:8082/swagger-ui.html
- **GitHub Issues:** https://github.com/your-org/cbs/issues

---

**End of Documentation**
