# FCY Transaction Settlement and WAE Display – Change Summary

## Overview

- **Transaction row rules**: Two legs always get `tranId-1`, `tranId-2`. Settlement rows use `tranId-3`, `tranId-4` (and `tranId-5`, `tranId-6` when two settlement pairs).
- **Settlement triggers**: Liability FCY → settlement on DEBIT only; Asset FCY → settlement on CREDIT only. No settlement for Liability CR or Asset DR.
- **WAE**: Not recalculated; existing WAE from balance (account-only) is used. Frontend WAE display is driven only by balance API (account), not by DR/CR.

---

## 1. Backend – TransactionService.java

**Location:** `moneymarket/src/main/java/com/example/moneymarket/service/TransactionService.java`

**Changes:**

- **createTransaction**
  - Removed the previous block that added two generic settlement rows (`-3`, `-4`) based on net LCY difference.
  - Legs are still created with IDs `tranId-1`, `tranId-2` (unchanged).
  - After building the two legs, calls **buildFcySettlementRows** only when `isSettlement && transactions.size() == 2`.
  - Appends all settlement rows to `transactions`, then performs a single **saveAll(transactions)** so legs and settlement rows are in the same DB transaction.
  - Settlement gain/loss for the response is derived from the created settlement rows (FX Gain GL credits minus FX Loss GL debits).

- **buildFcySettlementRows** (new)
  - Inputs: base tran ID, the two leg transactions, tran date, value date.
  - Returns a list of settlement rows (pairs of Dr/Cr for double entry).
  - Early return if not FCY or if both WAE equal Mid (no settlement).
  - Uses **UnifiedAccountService** to classify each leg as Liability/Asset and Dr/Cr.
  - **Trigger**: Liability + Dr → trigger; Asset + Cr → trigger. Asset Dr + Liability Cr → no settlement.
  - For each triggered leg, uses existing WAE from **BalanceService.getComputedAccountBalance(accountNo, tranDate).getWae()** and mid from **ExchangeRateService**.
  - Applies your formulas:
    - **Debit to Liability**: mid > WAE → Loss = (mid − WAE)×FCY → Dr FX Loss; mid < WAE → Gain = (WAE − mid)×FCY → Cr FX Gain.
    - **Credit to Asset**: mid < WAE → Loss = (WAE − mid)×FCY → Dr FX Loss; mid > WAE → Gain = (mid − WAE)×FCY → Cr FX Gain.
  - Each settlement is one pair of rows: Gain → Dr Position GL, Cr FX Gain GL; Loss → Dr FX Loss GL, Cr Position GL.
  - First pair uses suffixes **-3**, **-4**; second pair (Asset CR + Liability DR) uses **-5**, **-6**.

- **addSettlementPair** (new)
  - Appends two rows (Entry status) with the given base ID and suffix pair, correct GLs (POSITION_GL_USD, FX_GAIN_GL, FX_LOSS_GL), and narration "FX Gain" / "FX Loss".

**Constants unchanged:** `FX_GAIN_GL = "140203002"`, `FX_LOSS_GL = "240203002"`, `POSITION_GL_USD = "920101001"`.

**Unchanged:** `isSettlementTransaction`, validation, LCY/WAE usage for the two legs, `postTransaction`, `verifyTransaction`, `reverseTransaction`, `getTransaction`, and all logic that finds lines by `tranId + "-"` (so -1, -2, -3, -4, -5, -6 are all included).

---

## 2. Backend – MultiCurrencyTransactionService.java

**Location:** `moneymarket/src/main/java/com/example/moneymarket/service/MultiCurrencyTransactionService.java`

**Changes:**

- **processBuyTransaction**
  - No longer calls **postPositionGLEntriesForBuy**.
  - Only calls **updateWAEMasterForBuy(transaction)** so WAE is updated on BUY without creating extra tran_table rows.

- **processSellTransaction**
  - No longer computes or posts Position GL or settlement gain/loss.
  - Returns `BigDecimal.ZERO` immediately; settlement is fully handled in TransactionService at create time.

**Unchanged:** `processMultiCurrencyTransaction` entry point and BDT_USD_MIX routing; `updateWAEMasterForBuy`; repository and other dependencies. Methods **postPositionGLEntriesForBuy**, **postPositionGLEntriesForSell**, **postSettlementGain**, **postSettlementLoss** remain in the class but are no longer called for BDT_USD_MIX (kept for possible reuse or cleanup later).

---

## 3. Frontend – TransactionForm.tsx

**Location:** `frontend/src/pages/transactions/TransactionForm.tsx`

**Changes:**

- **fetchAccountBalance**
  - Comment clarified: WAE is always from balance (account-only), not from DR/CR.
  - When USD is detected, still calls **applySettlementExchangeRate(index, balanceData, exchangeRateData.midRate)** so the exchange rate field and WAE display use the **just-fetched** balance (no stale state).

- **applySettlementExchangeRate**
  - Comment and in-code note: WAE is always taken from **balance** (fetched by account only), never from DR/CR or from the exchange rate field.
  - Mid rate fallback when no exchange rate is stored: `midRateOverride ?? exchangeRates.get(index)?.midRate ?? (typeof waeRate === 'number' ? waeRate : 1)` so we don’t use a wrong source for WAE.

- **WAE (LCY / FCY) read-only field**
  - Value is explicitly from **accountBalances.get(\`${index}\`)?.wae** only.
  - Display: `Number(wae).toFixed(4)` when present, else `'N/A'`.
  - Helper text set to: `"Weighted Average Exchange Rate (account-specific, from balance)"`.

**Unchanged:** Balance is still fetched only by **accountNo** via `getAccountBalance(accountNo)`; no DR/CR or other parameters are sent. Rate type (WAE vs Mid) for the exchange rate field still depends on settlement (Liability Dr / Asset Cr) for the **rate applied**, while the **WAE display** is always account-specific from balance.

---

## 4. Repositories / Entities / DTOs

- **No changes** to repositories, entities, or DTOs.
- Tran_table rows still have `tranId`, `accountNo`, `glNum`, `drCrFlag`, `lcyAmt`, `fcyAmt`, `exchangeRate`, etc.; settlement rows use the same structure with GL accounts.

---

## 5. Transaction ID Convention (Final)

| Scenario                         | Row IDs in tran_table        |
|----------------------------------|-----------------------------|
| 2 legs only (WAE = Mid or no trigger) | `tranId-1`, `tranId-2`      |
| 2 legs + 1 settlement pair       | `tranId-1`, `tranId-2`, `tranId-3`, `tranId-4` |
| 2 legs + 2 settlement pairs      | `tranId-1` … `tranId-6`     |

Settlement rows are created at **createTransaction** in one transactional **saveAll**, so they commit or roll back with the legs.

---

## 6. What Was Not Changed

- WAE calculation logic (BalanceService, WAE master, etc.).
- InterestCapitalizationService / RevaluationService (their use of -1, -2 for their own flows is unchanged).
- EOD, verification, or other EOD steps.
- GL mapping or account number generation.
- Any Flyway migrations.
