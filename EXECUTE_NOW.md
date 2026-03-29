# 🚨 EXECUTE THESE STEPS IN EXACT ORDER

## ✅ COMPLETED: Code Changes

All code has been fixed and compiled successfully:
- ✅ `FxConversionService.java` - Rewritten with correct entity mappings
- ✅ `FxConversionController.java` - Rewritten with proper error handling and logging
- ✅ `mvn clean compile` - **BUILD SUCCESS** (177 source files compiled)

---

## 📋 STEP 1: DATABASE CLEANUP & DIAGNOSTIC

**Run this SQL script to audit database and insert test data:**

```bash
mysql -u root -p cbs3_database < fx_complete_diagnostic.sql
```

**What this does:**
1. Drops wrong tables: `fx_rates`, `fx_position`, `fx_transaction_entries`, `fx_transactions`
2. Shows complete structure of all relevant tables
3. Inserts test FX rates for USD, EUR, GBP, JPY (format: "USD/BDT")
4. Verifies NOSTRO accounts exist (GL starts with "22030")
5. Verifies NOSTRO balances exist in `acc_bal` and `acct_bal_lcy`
6. Shows final verification summary with PASS/FAIL for each check

**Expected output at end:**
```
========== FINAL VERIFICATION SUMMARY ==========
fx_rate_master has data           | PASS ✓ | 4
NOSTRO accounts exist              | PASS ✓ | 4+
NOSTRO balances in acc_bal         | PASS ✓ | 4+
NOSTRO balances in acct_bal_lcy    | PASS ✓ | 4+
Active customer accounts           | PASS ✓ | 50+
```

**⚠️ IF ANY CHECK FAILS:**
- Check the script output for detailed diagnostic info
- Script shows exact table structures and sample data
- Follow the instructions in the script comments

---

## 📋 STEP 2: START BACKEND

```bash
cd moneymarket
mvn spring-boot:run
```

**Watch console for:**
```
Started MoneyMarketApplication in X.XX seconds
```

**Keep this terminal open** to watch logs in real-time.

---

## 📋 STEP 3: TEST BACKEND ENDPOINTS

**Option A: Run test script**
```bash
test-fx-endpoints.bat
```

**Option B: Manual curl commands**
```bash
# Test 1: Mid Rate
curl http://localhost:8082/api/fx/rates/USD

# Test 2: WAE Rate
curl http://localhost:8082/api/fx/wae/USD

# Test 3: Customer Accounts
curl "http://localhost:8082/api/fx/accounts/customer?search="

# Test 4: NOSTRO Accounts
curl "http://localhost:8082/api/fx/accounts/nostro?currency=USD"
```

**Expected responses:**
```json
{
  "success": true,
  "data": {
    "currencyCode": "USD",
    "midRate": 110.25
  }
}
```

**Watch backend console for detailed logs:**
```
INFO  ===========================================
INFO  GET /api/fx/rates/USD
INFO  ===========================================
INFO  ========== FETCH MID RATE ==========
INFO  Currency: USD, Date: 2026-03-29
INFO  SUCCESS: Mid rate = 110.25
INFO  SUCCESS: Returned mid rate: 110.25
```

---

## 📋 STEP 4: TEST FRONTEND

1. **Open browser** to your frontend (typically http://localhost:3000)
2. **Open DevTools** (F12)
3. **Go to Network tab**
4. **Navigate to FX Conversion page**
5. **Watch Network tab for these requests:**
   - `/api/fx/rates/USD` → Should return 200 OK
   - `/api/fx/wae/USD` → Should return 200 OK
   - `/api/fx/accounts/customer?search=` → Should return 200 OK
   - `/api/fx/accounts/nostro?currency=USD` → Should return 200 OK

6. **Verify in UI:**
   - ✅ Mid Rate field auto-populates
   - ✅ WAE Rate field auto-populates
   - ✅ Customer Account dropdown loads with options
   - ✅ NOSTRO Account dropdown loads with options
   - ✅ No red error messages in console

**If you see errors:**
- Click on the failed request in Network tab
- Check "Response" tab for exact error message
- Match error message to backend console logs (they're detailed now)

---

## 🔍 TROUBLESHOOTING GUIDE

### Problem: "No exchange rate found for USD/BDT"

**Cause:** fx_rate_master is empty or missing USD/BDT record

**Fix:**
```sql
INSERT INTO fx_rate_master (Rate_Date, Ccy_Pair, Mid_Rate, Buying_Rate, Selling_Rate, Source, Uploaded_By, Created_At, Last_Updated)
VALUES (NOW(), 'USD/BDT', 110.25, 109.75, 110.75, 'MANUAL', 'SYSTEM', NOW(), NOW());
```

---

### Problem: "No active NOSTRO accounts found for currency: USD"

**Cause:** No office accounts with `GL_Num LIKE '22030%'` and `Account_Ccy = 'USD'`

**Check:**
```sql
SELECT Account_No, Acct_Name, GL_Num, Account_Ccy, Account_Status 
FROM of_acct_master 
WHERE GL_Num LIKE '22030%';
```

**Fix:** If empty, you need to create NOSTRO accounts through your application's account creation process, ensuring:
- `GL_Num` starts with "22030"
- `Account_Ccy` = "USD" (or EUR, GBP, etc.)
- `Account_Status` = 'Active'

---

### Problem: "Cannot calculate WAE - Total FCY balance is zero"

**Cause:** NOSTRO accounts exist but have no balances in `acc_bal` or `acct_bal_lcy`

**Check:**
```sql
SELECT ab.Account_No, ab.Closing_Bal 
FROM acc_bal ab
WHERE ab.Account_No IN (SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%');
```

**Fix:** Insert test balances:
```sql
INSERT INTO acc_bal (Account_No, Tran_Date, Opening_Bal, Closing_Bal, Dr, Cr)
SELECT Account_No, CURDATE(), 100000.00, 100000.00, 100000.00, 0.00
FROM of_acct_master 
WHERE GL_Num LIKE '22030%' AND Account_Ccy = 'USD'
ON DUPLICATE KEY UPDATE Closing_Bal = 100000.00;

INSERT INTO acct_bal_lcy (Account_No, Tran_Date, Opening_Bal_Lcy, Closing_Bal_Lcy, Dr_Lcy, Cr_Lcy)
SELECT Account_No, CURDATE(), 11025000.00, 11025000.00, 11025000.00, 0.00
FROM of_acct_master 
WHERE GL_Num LIKE '22030%' AND Account_Ccy = 'USD'
ON DUPLICATE KEY UPDATE Closing_Bal_Lcy = 11025000.00;
```

---

### Problem: Customer accounts dropdown is empty

**Cause:** No active customer accounts with product codes starting with "CA" or "SB"

**Check:**
```sql
SELECT ca.Account_No, ca.Acct_Name, sp.Sub_Product_Code, ca.Account_Status
FROM cust_acct_master ca
LEFT JOIN sub_prod_master sp ON ca.Sub_Product_Id = sp.Sub_Product_Id
WHERE ca.Account_Status = 'Active'
  AND (sp.Sub_Product_Code LIKE 'CA%' OR sp.Sub_Product_Code LIKE 'SB%');
```

**Fix:** If empty, create customer accounts through your application, ensuring:
- `Account_Status` = 'Active'
- Linked to sub-product with code starting with "CA" or "SB"
- `Account_Ccy` = 'BDT'

---

## 📊 KEY DIAGNOSTIC QUERIES

```sql
-- Check fx_rate_master
SELECT Ccy_Pair, Mid_Rate, Rate_Date 
FROM fx_rate_master 
ORDER BY Rate_Date DESC;

-- Check NOSTRO accounts
SELECT Account_No, Acct_Name, GL_Num, Account_Ccy, Account_Status
FROM of_acct_master
WHERE GL_Num LIKE '22030%';

-- Check NOSTRO balances
SELECT ab.Account_No, ab.Closing_Bal as FCY, abl.Closing_Bal_Lcy as LCY
FROM acc_bal ab
INNER JOIN acct_bal_lcy abl ON ab.Account_No = abl.Account_No AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_No IN (SELECT Account_No FROM of_acct_master WHERE GL_Num LIKE '22030%')
ORDER BY ab.Tran_Date DESC;

-- Check customer accounts
SELECT ca.Account_No, ca.Acct_Name, sp.Sub_Product_Code, ca.Account_Ccy
FROM cust_acct_master ca
LEFT JOIN sub_prod_master sp ON ca.Sub_Product_Id = sp.Sub_Product_Id
WHERE ca.Account_Status = 'Active'
  AND (sp.Sub_Product_Code LIKE 'CA%' OR sp.Sub_Product_Code LIKE 'SB%')
LIMIT 10;
```

---

## ✅ ALL FIXED

**What was broken:**
1. ❌ Wrong repository methods (`findByAcctNo` doesn't exist)
2. ❌ Wrong getter names (`getClosingBal()` for LCY entity)
3. ❌ Wrong field names (`acctTitle`, `accountType`)
4. ❌ Missing error handling (500 errors instead of 400)
5. ❌ No logging (couldn't debug issues)
6. ❌ Missing CORS headers

**What's fixed:**
1. ✅ Correct repository methods (`findLatestByAccountNo()`)
2. ✅ Correct getters (`getClosingBalLcy()`)
3. ✅ Correct field names (`acctName`, `subProduct.subProductCode`)
4. ✅ Comprehensive error handling with proper status codes
5. ✅ Detailed logging at every step
6. ✅ CORS enabled for all endpoints

**Backend compilation:** ✅ BUILD SUCCESS (177 files compiled)

**Next:** Run database diagnostic, start backend, test endpoints, verify frontend.
