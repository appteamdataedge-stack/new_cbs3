# FCY Settlement Fix - Test Scenarios

## Test Environment Setup

### Prerequisites
- System Date configured in `System_Date` table
- FX rates configured in `FX_Rate_Master` for USD/BDT
- Test accounts created with different account types and WAE values
- GL accounts 140203002 (FX Gain) and 240203002 (FX Loss) configured in `GL_Setup`

---

## Test Scenario 1: Liability-to-Liability with LOSS

### Setup
- **Account 1**: USD Liability account (e.g., customer deposit)
  - Account No: 99001140101001
  - Current WAE: 115.2857
- **Account 2**: USD Liability account (different customer)
  - Account No: 100000248001110203001
  - Current WAE: 115.2857
- **Mid Rate**: 115.5000 (mid > WAE → LOSS)

### Transaction
```json
{
  "valueDate": "2026-02-26",
  "narration": "FCY Settlement Test - Liability to Liability LOSS",
  "lines": [
    {
      "accountNo": "99001140101001",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 115.5000,
      "lcyAmt": 1155.00
    },
    {
      "accountNo": "100000248001110203001",
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 115.2857,
      "lcyAmt": 1152.86
    }
  ]
}
```

### Expected Result
**3 legs total:**

1. **Leg 1** (T20260226xxxxxx-1):
   - DR/CR: C
   - Account: 99001140101001
   - FCY: 10.00 USD
   - Rate: 115.5000
   - LCY: 1155.00 BDT

2. **Leg 2** (T20260226xxxxxx-2):
   - DR/CR: D
   - Account: 100000248001110203001
   - FCY: 10.00 USD
   - Rate: 115.2857
   - LCY: 1152.86 BDT

3. **Leg 3 - LOSS** (T20260226xxxxxx-3):
   - DR/CR: **D** (Debit)
   - Account: **NULL**
   - GL Number: **240203002** (FX Loss GL)
   - FCY: 0.00
   - Rate: 1.0000
   - LCY: **2.14 BDT** (calculated as: (115.5 - 115.2857) × 10 ≈ 2.14)
   - Narration: "FX Loss on Settlement"

### Verification Steps
1. ✅ Query `tran_table` for transaction - should return exactly 3 rows
2. ✅ Verify leg 3 has `account_no IS NULL`
3. ✅ Verify leg 3 has `gl_num = '240203002'`
4. ✅ Verify leg 3 has `dr_cr_flag = 'D'`
5. ✅ Verify leg 3 LCY amount ≈ 2.14 BDT

---

## Test Scenario 2: Liability-to-Liability with GAIN

### Setup
- **Account 1**: USD Liability account
  - Account No: 99001140101001
  - Current WAE: 116.5000
- **Account 2**: USD Liability account
  - Account No: 100000248001110203001
  - Current WAE: 116.5000
- **Mid Rate**: 115.0000 (mid < WAE → GAIN)

### Transaction
```json
{
  "valueDate": "2026-02-26",
  "narration": "FCY Settlement Test - Liability to Liability GAIN",
  "lines": [
    {
      "accountNo": "99001140101001",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 115.0000,
      "lcyAmt": 1150.00
    },
    {
      "accountNo": "100000248001110203001",
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 116.5000,
      "lcyAmt": 1165.00
    }
  ]
}
```

### Expected Result
**3 legs total:**

1. **Leg 1** (T20260226xxxxxx-1)
2. **Leg 2** (T20260226xxxxxx-2)
3. **Leg 3 - GAIN** (T20260226xxxxxx-3):
   - DR/CR: **C** (Credit)
   - Account: **NULL**
   - GL Number: **140203002** (FX Gain GL)
   - FCY: 0.00
   - Rate: 1.0000
   - LCY: **15.00 BDT** (calculated as: (116.5 - 115.0) × 10 = 15.00)
   - Narration: "FX Gain on Settlement"

### Verification Steps
1. ✅ Query `tran_table` for transaction - should return exactly 3 rows
2. ✅ Verify leg 3 has `account_no IS NULL`
3. ✅ Verify leg 3 has `gl_num = '140203002'`
4. ✅ Verify leg 3 has `dr_cr_flag = 'C'`
5. ✅ Verify leg 3 LCY amount = 15.00 BDT

---

## Test Scenario 3: Asset-to-Asset with LOSS

### Setup
- **Account 1**: USD Asset account (e.g., cash account)
  - Account No: 100000248001110203001
  - Current WAE: 116.0000
- **Account 2**: USD Asset account (different cash account)
  - Account No: 99001140101002
  - Current WAE: 116.0000
- **Mid Rate**: 115.0000 (mid < WAE → LOSS for asset CR)

### Transaction
```json
{
  "valueDate": "2026-02-26",
  "narration": "FCY Settlement Test - Asset to Asset LOSS",
  "lines": [
    {
      "accountNo": "100000248001110203001",
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 20.00,
      "exchangeRate": 115.0000,
      "lcyAmt": 2300.00
    },
    {
      "accountNo": "99001140101002",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 20.00,
      "exchangeRate": 116.0000,
      "lcyAmt": 2320.00
    }
  ]
}
```

### Expected Result
**3 legs total:**

1. **Leg 1** (T20260226xxxxxx-1)
2. **Leg 2** (T20260226xxxxxx-2)
3. **Leg 3 - LOSS** (T20260226xxxxxx-3):
   - DR/CR: **D** (Debit)
   - Account: **NULL**
   - GL Number: **240203002** (FX Loss GL)
   - FCY: 0.00
   - Rate: 1.0000
   - LCY: **20.00 BDT** (calculated as: (116.0 - 115.0) × 20 = 20.00)
   - Narration: "FX Loss on Settlement"

### Verification Steps
1. ✅ Query `tran_table` for transaction - should return exactly 3 rows
2. ✅ Verify leg 3 has `account_no IS NULL`
3. ✅ Verify leg 3 has `gl_num = '240203002'`
4. ✅ Verify leg 3 has `dr_cr_flag = 'D'`
5. ✅ Verify leg 3 LCY amount = 20.00 BDT

---

## Test Scenario 4: Asset CR + Liability DR (4 legs expected)

### Setup
- **Account 1**: USD Asset account
  - Account No: 100000248001110203001
  - Current WAE: 116.0000
- **Account 2**: USD Liability account
  - Account No: 99001140101001
  - Current WAE: 115.0000
- **Mid Rate**: 115.5000

### Transaction
```json
{
  "valueDate": "2026-02-26",
  "narration": "FCY Settlement Test - Asset CR + Liability DR (4 legs)",
  "lines": [
    {
      "accountNo": "100000248001110203001",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 116.0000,
      "lcyAmt": 1160.00
    },
    {
      "accountNo": "99001140101001",
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 115.0000,
      "lcyAmt": 1150.00
    }
  ]
}
```

### Expected Result
**4 legs total:**

1. **Leg 1** (T20260226xxxxxx-1):
   - DR/CR: C
   - Account: 100000248001110203001 (Asset)
   - FCY: 10.00 USD
   - Rate: 116.0000
   - LCY: 1160.00 BDT

2. **Leg 2** (T20260226xxxxxx-2):
   - DR/CR: D
   - Account: 99001140101001 (Liability)
   - FCY: 10.00 USD
   - Rate: 115.0000
   - LCY: 1150.00 BDT

3. **Leg 3 - Asset CR evaluation** (T20260226xxxxxx-3):
   - **LOSS** (mid 115.5 < WAE 116.0)
   - DR/CR: **D** (Debit)
   - Account: **NULL**
   - GL Number: **240203002** (FX Loss GL)
   - LCY: **5.00 BDT** (calculated as: (116.0 - 115.5) × 10 = 5.00)
   - Narration: "FX Loss on Settlement"

4. **Leg 4 - Liability DR evaluation** (T20260226xxxxxx-4):
   - **GAIN** (mid 115.5 > WAE 115.0)
   - DR/CR: **C** (Credit)
   - Account: **NULL**
   - GL Number: **140203002** (FX Gain GL)
   - LCY: **5.00 BDT** (calculated as: (115.5 - 115.0) × 10 = 5.00)
   - Narration: "FX Gain on Settlement"

### Verification Steps
1. ✅ Query `tran_table` for transaction - should return exactly **4 rows** (not 6!)
2. ✅ Verify leg 3 has `account_no IS NULL` and `gl_num = '240203002'` and `dr_cr_flag = 'D'`
3. ✅ Verify leg 4 has `account_no IS NULL` and `gl_num = '140203002'` and `dr_cr_flag = 'C'`
4. ✅ Verify leg 3 LCY amount = 5.00 BDT (loss)
5. ✅ Verify leg 4 LCY amount = 5.00 BDT (gain)

---

## Test Scenario 5: No Settlement (WAE = Mid Rate)

### Setup
- **Account 1**: USD Liability account
  - Account No: 99001140101001
  - Current WAE: 115.5000
- **Account 2**: USD Liability account
  - Account No: 100000248001110203001
  - Current WAE: 115.5000
- **Mid Rate**: 115.5000 (mid == WAE → NO settlement)

### Transaction
```json
{
  "valueDate": "2026-02-26",
  "narration": "FCY Settlement Test - No Settlement (WAE = Mid)",
  "lines": [
    {
      "accountNo": "99001140101001",
      "drCrFlag": "C",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 115.5000,
      "lcyAmt": 1155.00
    },
    {
      "accountNo": "100000248001110203001",
      "drCrFlag": "D",
      "tranCcy": "USD",
      "fcyAmt": 10.00,
      "exchangeRate": 115.5000,
      "lcyAmt": 1155.00
    }
  ]
}
```

### Expected Result
**2 legs total (no settlement legs):**

1. **Leg 1** (T20260226xxxxxx-1)
2. **Leg 2** (T20260226xxxxxx-2)

### Verification Steps
1. ✅ Query `tran_table` for transaction - should return exactly **2 rows only**
2. ✅ Verify NO legs with `account_no IS NULL`
3. ✅ Verify NO entries in settlement gain/loss audit table

---

## SQL Verification Queries

### Query 1: Check Transaction Leg Count
```sql
SELECT 
    SUBSTRING(tran_id, 1, 18) as base_tran_id,
    COUNT(*) as leg_count
FROM tran_table
WHERE tran_id LIKE 'T20260226%'
GROUP BY SUBSTRING(tran_id, 1, 18)
ORDER BY tran_id DESC;
```

### Query 2: Verify Settlement Leg Details
```sql
SELECT 
    tran_id,
    dr_cr_flag,
    account_no,
    gl_num,
    tran_ccy,
    fcy_amt,
    exchange_rate,
    lcy_amt,
    debit_amount,
    credit_amount,
    narration
FROM tran_table
WHERE tran_id LIKE 'T20260226%'
  AND account_no IS NULL
ORDER BY tran_id;
```

### Query 3: Verify GL Numbers for Settlement Legs
```sql
SELECT 
    tran_id,
    gl_num,
    CASE 
        WHEN gl_num = '140203002' THEN 'FX Gain GL ✓'
        WHEN gl_num = '240203002' THEN 'FX Loss GL ✓'
        ELSE 'INVALID GL ✗'
    END as gl_verification,
    dr_cr_flag,
    lcy_amt
FROM tran_table
WHERE tran_id LIKE 'T20260226%'
  AND account_no IS NULL;
```

### Query 4: Check for Incorrectly Populated account_no
```sql
-- This should return 0 rows (no settlement legs with account_no populated)
SELECT 
    tran_id,
    account_no,
    gl_num
FROM tran_table
WHERE tran_id LIKE 'T20260226%'
  AND gl_num IN ('140203002', '240203002')
  AND account_no IS NOT NULL;
```

---

## Regression Test Checklist

### Before Fix (Expected Failures)
- [ ] Scenario 1: Returns 2 legs instead of 3 ❌
- [ ] Scenario 2: Returns 2 legs instead of 3 ❌
- [ ] Scenario 3: Returns 2 legs instead of 3 ❌
- [ ] Scenario 4: Returns 6 legs instead of 4 ❌
- [ ] Scenario 5: Correctly returns 2 legs ✅

### After Fix (Expected Passes)
- [ ] Scenario 1: Returns 3 legs with correct LOSS leg ✅
- [ ] Scenario 2: Returns 3 legs with correct GAIN leg ✅
- [ ] Scenario 3: Returns 3 legs with correct LOSS leg ✅
- [ ] Scenario 4: Returns 4 legs with both LOSS and GAIN ✅
- [ ] Scenario 5: Returns 2 legs (no settlement) ✅

---

## Notes for QA Team

1. **Decimal Precision**: LCY amounts are rounded to 2 decimal places using `RoundingMode.HALF_UP`
2. **WAE Source**: WAE values come from `acc_bal` table (previous day closing + CR - DR)
3. **Mid Rate Source**: Mid rate comes from `FX_Rate_Master` for the transaction date
4. **Settlement Triggers**: Only LIABILITY DR and ASSET CR trigger settlement evaluation
5. **Independent Evaluation**: Each triggered leg evaluates settlement independently using its own WAE
6. **Account_no NULL**: All settlement legs MUST have `account_no IS NULL` and only `gl_num` populated
7. **Transaction Status**: Settlement legs are created with status "Entry" and posted along with main transaction

---

## Known Edge Cases

### Edge Case 1: Zero FCY Amount
- If `fcyAmt` is 0, no settlement leg should be generated
- Tested by: `if (amount.compareTo(BigDecimal.ZERO) > 0)`

### Edge Case 2: WAE is NULL
- If WAE is NULL, fallback to mid rate
- Tested by: `if (wae0 == null) wae0 = mid;`

### Edge Case 3: Mid Rate Not Found
- If mid rate is not found for transaction date, use latest available rate
- Logs warning and continues with latest rate
- If no rate exists at all, skip settlement rows and log warning

### Edge Case 4: Both Legs Same Account Type
- Liability → Liability: Only the DR leg triggers
- Asset → Asset: Only the CR leg triggers
- Works correctly due to trigger logic: `(liability0 && dr0) || (asset0 && cr0)`

---

## Automated Test Template (JUnit)

```java
@Test
public void testLiabilityToLiabilityWithLoss() {
    // Given
    String account1 = "99001140101001";
    String account2 = "100000248001110203001";
    BigDecimal wae = new BigDecimal("115.2857");
    BigDecimal midRate = new BigDecimal("115.5000");
    BigDecimal fcyAmount = new BigDecimal("10.00");
    
    // Setup WAE and mid rate
    setupWaeForAccount(account1, wae);
    setupWaeForAccount(account2, wae);
    setupMidRate("USD", midRate);
    
    // When
    TransactionRequestDTO request = createLiabilityToLiabilityTransaction(
        account1, account2, fcyAmount
    );
    TransactionResponseDTO response = transactionService.createTransaction(request);
    
    // Then
    assertEquals(3, response.getLines().size(), "Should have 3 legs");
    
    // Verify settlement leg
    TransactionLineResponseDTO settlementLeg = response.getLines().get(2);
    assertNull(settlementLeg.getAccountNo(), "Account_no should be NULL");
    assertEquals("240203002", settlementLeg.getGlNum(), "Should be FX Loss GL");
    assertEquals("D", settlementLeg.getDrCrFlag().toString(), "Should be DEBIT");
    assertEquals(new BigDecimal("2.14"), settlementLeg.getLcyAmt(), "Loss amount should be 2.14");
}
```

---

## Contact for Issues

If any test fails after the fix is deployed:
1. Capture the transaction ID
2. Run verification queries to check leg structure
3. Check logs for WAE and mid rate values used in calculation
4. Compare actual vs expected leg count and amounts
5. Report findings with full transaction details
