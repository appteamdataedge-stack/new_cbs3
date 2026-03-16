# FCY Interest Capitalization — WAE vs Mid Rate Implementation

**Date:** March 15, 2026  
**Task:** Implement proper accounting flow for FCY interest capitalization with WAE/MID rate gain/loss handling

---

## EXECUTIVE SUMMARY

**Status:** ✅ **COMPLETE - Ready for Testing**

The interest capitalization flow has been restructured to properly handle FCY accounts with separate rates for accrual release (WAE) and main account booking (MID rate). The system now creates three distinct transaction legs in `tran_table` that properly balance LCY amounts, with automatic gain/loss GL entries when WAE ≠ MID rate.

---

## WHAT CHANGED

### Before (OLD Flow - INCORRECT)

**Transaction Legs:**
1. **Debit Entry** in `intt_accr_tran` table → Interest Expense GL at WAE
2. **Credit Entry** in `tran_table` → Customer Account at MID rate  
3. **Gain/Loss Entry** in `tran_table` → FX Gain/Loss GL

**Problems:**
- Mixed expense booking with balance transfer
- Debit entry went to wrong table (`intt_accr_tran` instead of `tran_table`)
- Conceptually incorrect: capitalization is a balance transfer, not an expense
- Accrual balance not properly zeroed out
- S-prefix entries not marked as capitalized

### After (NEW Flow - CORRECT)

**Transaction Legs (all in `tran_table`):**

**LEG -1: Debit Accrual Account** (Release from accrual pool)
```
tran_id      = C20260315000001-1
account_no   = 200140203002001
dr_cr_flag   = D
tran_ccy     = USD
fcy_amt      = 45.00
exchange_rate= 112.6000 (accrual WAE)
lcy_amt      = 5,067.00 (45.00 × 112.6000)
narration    = "Interest Capitalization - Accrual Release"
```

**LEG -2: Credit Main Account** (Book into customer balance)
```
tran_id      = C20260315000001-2
account_no   = 200140203002001
dr_cr_flag   = C
tran_ccy     = USD
fcy_amt      = 45.00
exchange_rate= 112.7000 (current MID rate)
lcy_amt      = 5,071.50 (45.00 × 112.7000)
narration    = "Interest Capitalization - Credit to Account"
```

**LEG -3: FX Loss Entry** (Balance the LCY difference)
```
tran_id      = C20260315000001-3
account_no   = NULL
gl_num       = 240203002 (FX Loss GL)
dr_cr_flag   = D
tran_ccy     = BDT
fcy_amt      = 0.00
exchange_rate= 1.0000
lcy_amt      = 4.50 (|5,067.00 - 5,071.50|)
narration    = "FX Loss on Interest Capitalization"
```

**LCY Balance Check:**
- Total DR: 5,067.00 (accrual) + 4.50 (loss) = **5,071.50**
- Total CR: 5,071.50 (main account) = **5,071.50**
- ✓ **Balanced!**

---

## KEY BUSINESS LOGIC

### Gain/Loss Determination

**When WAE > MID Rate → LOSS**
- Accrual cost **more** than current market value
- Example: Accrued at average 112.6000, now worth 112.7000
- Wait, that's backwards... Let me recalculate:

Actually, if accrual WAE = 112.6000 and current MID = 112.7000:
- Accrual LCY = 45 × 112.6 = 5,067.00 (what we paid to accumulate interest)
- Current value = 45 × 112.7 = 5,071.50 (what it's worth now)
- We're **crediting** the account 5,071.50 but only **debiting** accrual 5,067.00
- We need to debit loss GL 4.50 more to balance

So **WAE < MID = LOSS** (not WAE > MID)

Let me correct this:

**When WAE < MID Rate → LOSS**
- Accrual accumulated at lower rate (cheaper then)
- Now worth more (expensive now)
- We credit account at current market rate (higher)
- But only debit accrual at historical cost (lower)
- Need to debit FX Loss GL to make up the difference
- **DR:** accrual (at WAE) + loss → **CR:** account (at MID)

**When WAE > MID Rate → GAIN**
- Accrual accumulated at higher rate (expensive then)
- Now worth less (cheaper now)
- We credit account at current market rate (lower)
- But debit accrual at historical cost (higher)
- Need to credit FX Gain GL for the excess
- **DR:** accrual (at WAE) → **CR:** account (at MID) + gain

### Example Scenario 1: LOSS (WAE < MID)

```
Accrued: 45 USD over 3 days
Day 1: 15 USD @ 112.5 = 1,687.50 BDT
Day 2: 15 USD @ 112.6 = 1,689.00 BDT
Day 3: 15 USD @ 112.6 = 1,689.50 BDT
Total: 45 USD, 5,066.00 BDT
WAE = 5,066.00 / 45 = 112.5778

Current MID rate = 112.7000

Capitalization:
LEG -1: DR accrual 45 @ 112.5778 = 5,066.00
LEG -2: CR account 45 @ 112.7000 = 5,071.50
LEG -3: DR loss GL            = 5.50

Balance: DR (5,066.00 + 5.50) = CR (5,071.50) ✓
```

### Example Scenario 2: GAIN (WAE > MID)

```
Accrued: 45 USD over 3 days
Day 1: 15 USD @ 113.0 = 1,695.00 BDT
Day 2: 15 USD @ 112.8 = 1,692.00 BDT
Day 3: 15 USD @ 112.6 = 1,689.00 BDT
Total: 45 USD, 5,076.00 BDT
WAE = 5,076.00 / 45 = 112.8000

Current MID rate = 112.5000

Capitalization:
LEG -1: DR accrual 45 @ 112.8000 = 5,076.00
LEG -2: CR account 45 @ 112.5000 = 5,062.50
LEG -3: CR gain GL            = 13.50

Balance: DR (5,076.00) = CR (5,062.50 + 13.50) ✓
```

---

## IMPLEMENTATION DETAILS

### 1. Transaction Leg Creation

**Method:** `capitalizeInterest()`

Changed from:
```java
// OLD: Create debit in intt_accr_tran, credit in tran_table
createDebitEntry(account, transactionId, systemDate, accruedBalance, wae, narration);
createCreditEntry(account, transactionId, systemDate, accruedBalance, midRate, narration);
```

To:
```java
// NEW: Both legs in tran_table, with proper LCY calculation
BigDecimal accrualLcyAmt = accruedBalance.multiply(wae).setScale(2, RoundingMode.HALF_UP);
BigDecimal mainAcctLcyAmt = accruedBalance.multiply(midRate).setScale(2, RoundingMode.HALF_UP);
BigDecimal gainLossAmt = accrualLcyAmt.subtract(mainAcctLcyAmt).abs();

createAccrualDebitEntry(account, transactionId, systemDate, accruedBalance, wae, accrualLcyAmt, narration);
createMainAccountCreditEntry(account, transactionId, systemDate, accruedBalance, midRate, mainAcctLcyAmt, narration);

if (gainLossAmt.compareTo(BigDecimal.ZERO) > 0) {
    boolean isLoss = wae.compareTo(midRate) < 0; // WAE < MID = LOSS
    createGainLossEntry(transactionId, systemDate, gainLossAmt, isLoss);
}
```

### 2. New Transaction Entry Methods

**`createAccrualDebitEntry()`**
- Creates debit entry in `tran_table` (not `intt_accr_tran`)
- Debits the customer account
- Uses WAE rate (historical cost basis)
- Sets `debitAmount` = `lcyAmount`
- Narration: "Interest Capitalization - Accrual Release"

**`createMainAccountCreditEntry()`**
- Creates credit entry in `tran_table`
- Credits the customer account
- Uses MID rate (current market rate)
- Sets `creditAmount` = `lcyAmount`
- Narration: "Interest Capitalization - Credit to Account"

**`createGainLossEntry()`**
- Creates GL entry in `tran_table`
- No account_no (GL entry only)
- GL 240203002 (DR) if loss, GL 140203002 (CR) if gain
- Always in BDT currency
- `fcyAmt` = 0, `exchangeRate` = 1.0
- `lcyAmt` = absolute difference

### 3. Accrual Balance Zeroing

**New Method:** `zeroOutAccrualBalance()`

```java
private void zeroOutAccrualBalance(String accountNo) {
    // 1. Zero out acct_bal_accrual fields
    AcctBalAccrual accrual = acctBalAccrualRepository.findLatestByAccountNo(accountNo).get();
    accrual.setClosingBal(BigDecimal.ZERO);
    accrual.setLcyAmt(BigDecimal.ZERO);
    accrual.setCrSummation(BigDecimal.ZERO);
    accrual.setDrSummation(BigDecimal.ZERO);
    accrual.setInterestAmount(BigDecimal.ZERO);
    
    // 2. Mark S-prefix entries as Posted
    List<InttAccrTran> pendingEntries = inttAccrTranRepository.findByAccountNoAndStatus(
            accountNo, InttAccrTran.AccrualStatus.Pending);
    
    for (InttAccrTran entry : pendingEntries) {
        if (entry.getAccrTranId().startsWith("S")) {
            entry.setStatus(InttAccrTran.AccrualStatus.Posted);
        }
    }
}
```

**What This Does:**
1. Clears all accrual balance fields in `acct_bal_accrual`
2. Marks all S-type (system accrual) entries as Posted/capitalized
3. Leaves C-type (previous capitalization) entries unchanged

### 4. Balance Update Fix

**Changed:** Use MID rate (not WAE) for account balance WAE recalculation

Old code used WAE for both accrual release and balance update.
New code uses:
- **WAE** for accrual debit leg (historical cost)
- **MID** for account credit leg and balance update (current market rate)

```java
// OLD
BigDecimal capitalizationLcy = accruedInterest.multiply(wae).setScale(2, RoundingMode.HALF_UP);

// NEW
BigDecimal capitalizationLcy = accruedInterest.multiply(midRate).setScale(2, RoundingMode.HALF_UP);
```

This ensures the account WAE is updated based on current market value, not historical accrual cost.

### 5. LCY Balance Validation

**New Method:** `validateLcyBalanceFcy()`

```java
if (wae.compareTo(midRate) > 0) {
    // WAE > MID = GAIN
    totalDrLcy = accrualLcy;
    totalCrLcy = mainAcctLcy.add(gainLossLcy);
} else if (wae.compareTo(midRate) < 0) {
    // WAE < MID = LOSS
    totalDrLcy = accrualLcy.add(gainLossLcy);
    totalCrLcy = mainAcctLcy;
} else {
    // WAE == MID, no gain/loss
    totalDrLcy = accrualLcy;
    totalCrLcy = mainAcctLcy;
}

// Validate DR == CR (within 0.01 tolerance)
if (totalDrLcy.subtract(totalCrLcy).abs().compareTo(0.01) > 0) {
    throw new RuntimeException("LCY imbalance");
}
```

---

## FILES MODIFIED

### Code Changes (1 file)

1. **`InterestCapitalizationService.java`** - Complete restructure of capitalization flow
   - Changed transaction leg creation logic
   - Added `createAccrualDebitEntry()` method
   - Added `createMainAccountCreditEntry()` method
   - Updated `createGainLossEntry()` method
   - Added `validateLcyBalanceFcy()` method
   - Added `zeroOutAccrualBalance()` method
   - Updated `updateAccountAfterCapitalization()` to use MID rate
   - Removed old `createDebitEntry()` and `createCreditEntry()` methods

---

## WHAT WAS NOT CHANGED

As per strict constraints:

- ✅ **NO** changes to MCT settlement WAE logic
- ✅ **NO** changes to BDT capitalization (rate = 1.0, no gain/loss)
- ✅ **NO** changes to EOD accrual update logic
- ✅ **NO** changes to gain/loss GL numbers (140203002, 240203002)
- ✅ **NO** changes to TD funding or mid-rate accrual logic
- ✅ **NO** new database tables or columns
- ✅ **NO** renamed entity fields or method signatures

---

## TESTING INSTRUCTIONS

### Step 1: Prepare Test Account

```sql
-- Find a USD account with accrued interest
SELECT 
    aba.account_no,
    cam.acct_name,
    aba.closing_bal AS accrued_fcy,
    aba.lcy_amt AS accrued_lcy,
    ROUND(aba.lcy_amt / aba.closing_bal, 4) AS wae
FROM acct_bal_accrual aba
JOIN cust_acct_master cam ON cam.account_no = aba.account_no
WHERE cam.account_ccy = 'USD'
  AND aba.closing_bal > 0
ORDER BY aba.tran_date DESC
LIMIT 5;
```

### Step 2: Check Current Mid Rate

```sql
SELECT ccy, mid_rate, rate_date
FROM fx_rate_master
WHERE ccy = 'USD'
ORDER BY rate_date DESC
LIMIT 1;
```

### Step 3: Perform Capitalization

1. Navigate to **Home > Interest Capitalization**
2. Find the test USD account
3. Click **Capitalize**
4. Review preview showing WAE, MID, estimated gain/loss
5. Confirm capitalization

### Step 4: Verify Transaction Legs

```sql
SELECT 
    tran_id,
    account_no,
    gl_num,
    dr_cr_flag,
    tran_ccy,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    debit_amount,
    credit_amount,
    narration
FROM tran_table
WHERE tran_id LIKE 'C20260315%' -- Replace with actual date
  AND (account_no = '200140203002001' OR gl_num IN ('140203002', '240203002'))
ORDER BY tran_id;
```

**Expected Output:**
```
-1: D  200140203002001  USD  45.00  112.6000  5067.00  5067.00  0.00     Accrual Release
-2: C  200140203002001  USD  45.00  112.7000  5071.50  0.00     5071.50  Credit to Account
-3: D  NULL  240203002  BDT  0.00   1.0000    4.50     4.50     0.00     FX Loss
```

### Step 5: Verify LCY Balance

```sql
SELECT 
    SUM(CASE WHEN dr_cr_flag='D' THEN lcy_amt ELSE 0 END) as total_dr,
    SUM(CASE WHEN dr_cr_flag='C' THEN lcy_amt ELSE 0 END) as total_cr,
    SUM(CASE WHEN dr_cr_flag='D' THEN lcy_amt ELSE 0 END) -
    SUM(CASE WHEN dr_cr_flag='C' THEN lcy_amt ELSE 0 END) as difference
FROM tran_table
WHERE tran_id LIKE 'C20260315%';
```

**Expected:** `difference` = 0.00 (or within 0.01 tolerance)

### Step 6: Verify Accrual Zeroed

```sql
SELECT account_no, closing_bal, lcy_amt, cr_summation, dr_summation
FROM acct_bal_accrual
WHERE account_no = '200140203002001';
```

**Expected:** All values = 0.00

### Step 7: Verify S-Entries Marked Posted

```sql
SELECT accr_tran_id, status, fcy_amt, lcy_amt
FROM intt_accr_tran
WHERE account_no = '200140203002001'
  AND accr_tran_id LIKE 'S%'
ORDER BY accrual_date DESC
LIMIT 10;
```

**Expected:** All S-entries have `status` = 'Posted'

### Step 8: Verify Account Balance Updated

```sql
SELECT 
    account_no,
    current_balance,
    available_balance,
    wae_rate
FROM acct_bal
WHERE account_no = '200140203002001'
ORDER BY tran_date DESC
LIMIT 1;
```

**Expected:** 
- `current_balance` increased by capitalized FCY amount
- `wae_rate` updated to reflect new blended rate

---

## COMMON SCENARIOS

### Scenario A: WAE < MID (Loss)

**Setup:**
- Accrued 100 USD @ average 110.5 = 11,050 BDT (WAE)
- Current rate: 111.0 BDT per USD (MID)

**Transaction Legs:**
```
-1: DR  account  100 @ 110.5 = 11,050 BDT
-2: CR  account  100 @ 111.0 = 11,100 BDT
-3: DR  loss GL                    50 BDT
```

**Balance:** DR (11,050 + 50) = CR (11,100) ✓

### Scenario B: WAE > MID (Gain)

**Setup:**
- Accrued 100 USD @ average 111.5 = 11,150 BDT (WAE)
- Current rate: 111.0 BDT per USD (MID)

**Transaction Legs:**
```
-1: DR  account  100 @ 111.5 = 11,150 BDT
-2: CR  account  100 @ 111.0 = 11,100 BDT
-3: CR  gain GL                    50 BDT
```

**Balance:** DR (11,150) = CR (11,100 + 50) ✓

### Scenario C: WAE == MID (No Gain/Loss)

**Setup:**
- Accrued 100 USD @ average 111.0 = 11,100 BDT (WAE)
- Current rate: 111.0 BDT per USD (MID)

**Transaction Legs:**
```
-1: DR  account  100 @ 111.0 = 11,100 BDT
-2: CR  account  100 @ 111.0 = 11,100 BDT
```

**Balance:** DR (11,100) = CR (11,100) ✓  
**No LEG -3 needed!**

### Scenario D: BDT Account

**Setup:**
- Accrued 1,000 BDT

**Transaction Legs:**
```
-1: DR  account  1,000 @ 1.0 = 1,000 BDT
-2: CR  account  1,000 @ 1.0 = 1,000 BDT
```

**Balance:** DR (1,000) = CR (1,000) ✓  
**No LEG -3, no WAE calculation, no gain/loss**

---

## TROUBLESHOOTING

### Issue 1: LCY Imbalance Error

**Symptom:** `RuntimeException: LCY imbalance on capitalization`

**Check:**
```sql
-- Verify gain/loss calculation
SELECT 
    (45.00 * 112.6000) as accrual_lcy,
    (45.00 * 112.7000) as account_lcy,
    ABS((45.00 * 112.6000) - (45.00 * 112.7000)) as gain_loss;
```

**Fix:** Check rounding logic in LCY calculation

### Issue 2: Accrual Not Zeroed

**Symptom:** `acct_bal_accrual.closing_bal` still has value after capitalization

**Check:**
```sql
SELECT * FROM acct_bal_accrual 
WHERE account_no = '<test_account>';
```

**Fix:** Ensure `zeroOutAccrualBalance()` is being called

### Issue 3: Wrong Gain/Loss Direction

**Symptom:** Gain posted as loss or vice versa

**Check Logic:**
- If WAE < MID → **LOSS** (accrual cheaper than current)
- If WAE > MID → **GAIN** (accrual more expensive than current)

**Verify:**
```sql
SELECT 
    wae,
    mid_rate,
    CASE 
        WHEN wae < mid_rate THEN 'LOSS'
        WHEN wae > mid_rate THEN 'GAIN'
        ELSE 'NONE'
    END as expected_type,
    gl_num,
    dr_cr_flag
FROM ... -- your query
```

---

## SUCCESS CRITERIA

- [ ] All three transaction legs created in `tran_table`
- [ ] LEG -1 uses WAE rate for accrual debit
- [ ] LEG -2 uses MID rate for account credit
- [ ] LEG -3 created only if WAE ≠ MID
- [ ] Gain/loss direction correct (loss=DR, gain=CR)
- [ ] LCY perfectly balanced (DR = CR within 0.01)
- [ ] Accrual balance zeroed out
- [ ] S-prefix entries marked as Posted
- [ ] Account balance increased by FCY amount
- [ ] Account WAE updated using MID rate
- [ ] No errors in application logs
- [ ] BDT accounts still work (no gain/loss, rate=1.0)

**If all criteria met, implementation is successful! ✅**

---

## DEPLOYMENT NOTES

1. **Backup:** This changes core capitalization logic - test thoroughly before production
2. **Migration:** No database changes needed (uses existing tables)
3. **Rollback:** Can revert to previous code version if issues found
4. **Monitoring:** Watch for LCY imbalance errors in first few capitalizations
5. **Documentation:** Update user manual to explain gain/loss entries

---

## NEXT STEPS

After successful testing:

1. Test with multiple FCY accounts (USD, EUR, GBP)
2. Test with varying WAE vs MID scenarios
3. Verify reporting includes gain/loss amounts
4. Update financial statements to show FX gain/loss
5. Train users on new transaction structure
6. Document for audit/compliance purposes

---

**Implementation Complete!**
All changes are ready for testing. The capitalization flow now properly handles FCY accounts with accurate WAE/MID rate gain/loss calculations.
