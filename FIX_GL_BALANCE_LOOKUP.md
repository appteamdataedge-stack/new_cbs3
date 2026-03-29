# FIX: GL Account Balance Lookup Error ✅

## PROBLEM DIAGNOSED

**Error Message:**
```
Failed to process FX conversion: Failed to validate Position FCY Balance. 
Account balance not found with account number 920101002.
```

**Root Cause:**
The validation code was calling `balanceService.getComputedAccountBalance("920101002")`, which:
1. Looks for account in `cust_acct_master` table ❌
2. Falls back to `of_acct_master` table ❌
3. Throws `ResourceNotFoundException` if not found ❌

**But Position account `920101002` is a GL account**, not a customer or office account!

## ACCOUNT TYPES IN THE SYSTEM

### Customer Accounts (e.g., 100000082001)
- **Table:** `cust_acct_master`
- **Balance Table:** `acc_bal` / `acct_bal_lcy`
- **Method:** `balanceService.getComputedAccountBalance(accountNo)`

### Office Accounts (e.g., 922030200102 - Nostro)
- **Table:** `of_acct_master`
- **Balance Table:** `acc_bal` / `acct_bal_lcy`
- **Method:** `balanceService.getComputedAccountBalance(accountNo)`

### GL Accounts (e.g., 920101002 - Position FCY)
- **Table:** `gl_setup`
- **Balance Table:** `GL_Balance` ⚠️ **DIFFERENT TABLE**
- **Method:** `balanceService.getGLBalance(glNum)` ✅

## THE FIX

### File: FxConversionService.java (Line 422)

**BEFORE (Lines 422-424) - WRONG:**
```java
com.example.moneymarket.dto.AccountBalanceDTO positionBalanceDto = 
    balanceService.getComputedAccountBalance(positionFcyGlCode);
BigDecimal positionFcyBalance = positionBalanceDto.getAvailableBalance();
```
❌ Tries to find GL account in customer/office account tables → Throws exception

**AFTER (Line 422) - CORRECT:**
```java
BigDecimal positionFcyBalance = balanceService.getGLBalance(positionFcyGlCode);
```
✅ Uses `getGLBalance()` which queries `GL_Balance` table directly

### Complete Updated Method:

```403:449:moneymarket/src/main/java/com/example/moneymarket/service/FxConversionService.java
private void validateSellingTransaction(String customerAccountNo, String nostroAccountNo,
                                          String currencyCode, BigDecimal fcyAmount, BigDecimal lcyEquiv) {
    log.info("========== VALIDATING SELLING TRANSACTION ==========");
    
    // Validate Position FCY balance (will be debited in Line 2)
    String positionFcyGlCode = getGlNumByName("PSUSD EQIV");
    log.info("Checking Position FCY balance for GL: {}", positionFcyGlCode);
    
    try {
        // Position accounts are GL accounts, use getGLBalance instead of getComputedAccountBalance
        BigDecimal positionFcyBalance = balanceService.getGLBalance(positionFcyGlCode);
        
        log.info("Position FCY balance: {}, Required: {}", positionFcyBalance, fcyAmount);
        
        if (positionFcyBalance.compareTo(fcyAmount) < 0) {
            throw new BusinessException(String.format(
                    "Insufficient Position %s balance. Available: %s. Required: %s.",
                    currencyCode, positionFcyBalance, fcyAmount));
        }
        
        log.info("✓ Position FCY balance sufficient");
        
    } catch (BusinessException e) {
        throw e;
    } catch (Exception e) {
        log.error("Error validating Position FCY balance: ", e);
        throw new BusinessException("Failed to validate Position FCY balance: " + e.getMessage());
    }
    
    log.info("========== SELLING VALIDATION PASSED ==========");
}
```

## BalanceService Methods Reference

### For GL Accounts (Position 920101001, 920101002):
```java
// Located in BalanceService.java line 459
public BigDecimal getGLBalance(String glNum) {
    return glBalanceRepository.findLatestByGlNum(glNum)
            .map(GLBalance::getCurrentBalance)
            .orElse(BigDecimal.ZERO);
}
```

### For Customer/Office Accounts:
```java
// Located in BalanceService.java line 155
public AccountBalanceDTO getComputedAccountBalance(String accountNo) {
    // Queries acct_bal and acct_bal_lcy tables
    // Looks up in cust_acct_master or of_acct_master
}
```

## COMPILATION STATUS ✅

```
[INFO] BUILD SUCCESS
[INFO] Total time:  27.242 s
[INFO] Finished at: 2026-03-29T15:09:59+06:00
```

══════════════════════════════════════════════════════════════════════════════
## RESTART BACKEND NOW ⚡
══════════════════════════════════════════════════════════════════════════════

**STEP 1:** Stop backend (`Ctrl+C` in terminal 4)

**STEP 2:** Start with new code:
```bash
cd c:\new_cbs3\cbs3\moneymarket
mvn spring-boot:run
```

**STEP 3:** Wait for: `Started MoneyMarketApplication`

══════════════════════════════════════════════════════════════════════════════
## SETUP GL BALANCES (If Needed)
══════════════════════════════════════════════════════════════════════════════

Before testing, ensure GL balances exist. Run `setup_gl_balances.sql`:

```bash
mysql -u root -p"asif@yasir123" moneymarketdb < setup_gl_balances.sql
```

Or run manually:
```sql
-- Check if GL balances exist
SELECT GL_Num, Current_Balance FROM GL_Balance WHERE GL_Num IN ('920101001', '920101002');

-- If empty, insert test data (see setup_gl_balances.sql)
```

══════════════════════════════════════════════════════════════════════════════
## TESTING - SELLING TRANSACTION
══════════════════════════════════════════════════════════════════════════════

### Frontend Test:

1. Open `http://localhost:5173/fx-conversion`
2. Select **SELLING** radio button
3. Fill form:
   - Customer: 100000082001
   - Currency: USD
   - Nostro: 922030200102
   - FCY Amount: 500
   - Deal Rate: 115.50
4. Click "Preview & Submit"
5. Click "Confirm & Post"

**Expected Result:**
- ✅ Success message: "Transaction created. ID: FXC-20260329-XXX"
- ✅ No "Account balance not found" error
- ✅ No "Insufficient Nostro balance" error

### Backend Logs to Watch For:

```
========== VALIDATING SELLING TRANSACTION ==========
Checking Position FCY balance for GL: 920101002
Position FCY balance: 50000.00, Required: 500.00
✓ Position FCY balance sufficient
========== SELLING VALIDATION PASSED ==========
FX Conversion transaction created with ID: FXC-20260329-001 in Entry status
```

### API Test (curl):

```bash
curl -X POST "http://localhost:8082/api/fx/conversion" ^
  -H "Content-Type: application/json" ^
  -d "{\"transactionType\":\"SELLING\",\"customerAccountId\":\"100000082001\",\"nostroAccountId\":\"922030200102\",\"currencyCode\":\"USD\",\"fcyAmount\":500,\"dealRate\":115.50,\"particulars\":\"Test SELLING\",\"userId\":\"TEST\"}"
```

**Expected:**
```json
{
  "success": true,
  "data": {
    "tranId": "FXC-20260329-001",
    "status": "Entry",
    ...
  },
  "message": "FX Conversion transaction created successfully (Entry status - pending approval)"
}
```

══════════════════════════════════════════════════════════════════════════════
## IF STILL GETTING ERROR
══════════════════════════════════════════════════════════════════════════════

### Error: "GL Balance not found for GL Number 920101002"

**Solution:** Run `setup_gl_balances.sql` to insert GL balance records.

Check:
```sql
SELECT * FROM GL_Balance WHERE GL_Num = '920101002';
```

If empty, Position FCY has no balance record. Insert it:
```sql
INSERT INTO GL_Balance (GL_Num, Tran_date, Current_Balance, Opening_Bal, Last_Updated)
VALUES ('920101002', CURDATE(), 50000.00, 50000.00, NOW());
```

### Error: "GL Setup not found for PSUSD EQIV"

Check if GL account exists in gl_setup:
```sql
SELECT GL_Num, GL_Desc FROM gl_setup WHERE GL_Desc LIKE '%PSUSD%';
```

If missing, you need to verify the correct GL name/number.

══════════════════════════════════════════════════════════════════════════════
## ALL FIXES SUMMARY (Complete Journey)
══════════════════════════════════════════════════════════════════════════════

### Fix 1: POST Endpoint URL ✅
**Issue:** Frontend called `/conversion`, backend had `/convert`  
**Fix:** Changed backend to `/conversion`

### Fix 2: Customer Account Dropdown Empty ✅
**Issue:** SubProduct relationship was LAZY, causing filter failures  
**Fix:** Changed to EAGER fetch

### Fix 3: SELLING Mode Rates Not Fetching ✅
**Issue:** Frontend cleared rates when switching transaction type  
**Fix:** Don't clear rates on type switch

### Fix 4: SELLING Button Disabled ✅
**Issue:** Button validation too strict (blocked on `!waeRate` even when waeRate = 0)  
**Fix:** Changed to check `waeRate === null`

### Fix 5: Incorrect Nostro Balance Validation ✅
**Issue:** SELLING validated Nostro (which is CREDITED, not debited)  
**Fix:** Removed Nostro validation, added Position FCY validation

### Fix 6: GL Balance Lookup Error ✅ (CURRENT FIX)
**Issue:** Used `getComputedAccountBalance()` for GL account (920101002)  
**Fix:** Use `getGLBalance()` for GL accounts instead

══════════════════════════════════════════════════════════════════════════════
## NEXT STEPS
══════════════════════════════════════════════════════════════════════════════

1. **Run:** `setup_gl_balances.sql` to insert GL balance records
2. **Restart backend:** `mvn spring-boot:run`
3. **Test SELLING:** Complete a test transaction in the frontend
4. **Verify:** Success message and transaction in `tran_table`

All code fixes are complete and compiled successfully! 🎉
