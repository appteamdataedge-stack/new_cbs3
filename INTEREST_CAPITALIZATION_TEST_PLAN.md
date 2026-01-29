# Interest Capitalization - Test Plan

## 🎯 OBJECTIVE
Test the Interest Capitalization feature with enhanced logging to diagnose the "Account balance record not found" error.

---

## 📋 PRE-REQUISITES

### 1. Code Changes Applied ✅
- [x] InterestCapitalizationService.java updated with enhanced logging
- [x] Imports added (List, Optional, Collectors)
- [x] No linter errors

### 2. Build & Deploy
- [ ] Backend rebuilt: `mvn clean package -DskipTests`
- [ ] Backend server restarted
- [ ] Frontend running (if needed)

---

## 🧪 TEST CASES

### Test Case 1: Account with Balance Record for System Date

**Setup:**
```sql
-- Verify balance exists for system date
SELECT * FROM Acct_Bal 
WHERE Account_No = 'YOUR_ACCOUNT_NUMBER' 
  AND Tran_Date = (SELECT Param_Value FROM Parameter_Table WHERE Param_Name = 'System_Date');
```

**Expected Result:** Should return 1 row

**Test Steps:**
1. Navigate to account in frontend
2. Click "Proceed Interest" button
3. Check backend logs

**Expected Logs:**
```
========================================
=== INTEREST CAPITALIZATION STARTED ===
========================================
Account Number: 1101010000001
System Date (Business Date) from SystemDateService: 2026-01-29
LocalDate.now() (Device Date - NOT USED): 2026-01-29

=== GETTING CURRENT BALANCE - AUDIT ===
Account Number: 1101010000001
System Date (Business Date): 2026-01-29
Found balance record for system date 2026-01-29: Balance = 10000.00

=== UPDATING ACCOUNT BALANCE - AUDIT ===
Account Number: 1101010000001
System Date (Business Date): 2026-01-29
Accrued Interest to Add: 150.00
Found balance record for system date 2026-01-29
Found balance record: Tran_Date=2026-01-29, Current_Balance=10000.00
Account balance updated successfully: 10000.00 + 150.00 = 10150.00
Reset accrued balance to 0 for account: 1101010000001
Interest capitalization completed for account: 1101010000001. Transaction ID: C20260129000001123
```

**Expected Response:**
```json
{
  "accountNo": "1101010000001",
  "accountName": "Test Account",
  "oldBalance": 10000.00,
  "accruedInterest": 150.00,
  "newBalance": 10150.00,
  "transactionId": "C20260129000001123",
  "capitalizationDate": "2026-01-29",
  "message": "Interest capitalization successful"
}
```

**Verification:**
```sql
-- Check updated balance
SELECT * FROM Acct_Bal WHERE Account_No = '1101010000001';

-- Check transaction created
SELECT * FROM Tran_Table WHERE Tran_Id LIKE 'C20260129%' ORDER BY Tran_Id DESC LIMIT 2;

-- Check interest expense entry
SELECT * FROM Intt_Accr_Tran WHERE Accr_Tran_Id LIKE 'C20260129%' ORDER BY Accr_Tran_Id DESC LIMIT 1;

-- Check accrued balance reset
SELECT * FROM Acct_Bal_Accrual WHERE Account_No = '1101010000001';
```

**Status:** ✅ PASS / ❌ FAIL

---

### Test Case 2: Account with Balance Record for Different Date (Fallback)

**Setup:**
```sql
-- Verify NO balance for system date, but has other dates
SELECT 
    Account_No,
    Tran_Date,
    Current_Balance
FROM Acct_Bal 
WHERE Account_No = 'YOUR_ACCOUNT_NUMBER' 
ORDER BY Tran_Date DESC;
```

**Expected Result:** Should return rows, but none for system date

**Test Steps:**
1. Navigate to account in frontend
2. Click "Proceed Interest" button
3. Check backend logs

**Expected Logs:**
```
========================================
=== INTEREST CAPITALIZATION STARTED ===
========================================
Account Number: 1101010000001
System Date (Business Date) from SystemDateService: 2026-01-29

=== GETTING CURRENT BALANCE - AUDIT ===
Account Number: 1101010000001
System Date (Business Date): 2026-01-29
No balance record found for system date 2026-01-29. Trying latest record...
Found latest balance record: Date = 2026-01-28, Balance = 10000.00

=== UPDATING ACCOUNT BALANCE - AUDIT ===
Account Number: 1101010000001
System Date (Business Date): 2026-01-29
No balance record found for system date 2026-01-29. Trying latest record...
Found balance record: Tran_Date=2026-01-28, Current_Balance=10000.00
Account balance updated successfully: 10000.00 + 150.00 = 10150.00
```

**Expected Response:** Same as Test Case 1 (success)

**Status:** ✅ PASS / ❌ FAIL

---

### Test Case 3: Account with NO Balance Records (Error Case)

**Setup:**
```sql
-- Verify NO balance records exist
SELECT COUNT(*) FROM Acct_Bal WHERE Account_No = 'YOUR_ACCOUNT_NUMBER';
```

**Expected Result:** Should return 0

**Test Steps:**
1. Navigate to account in frontend
2. Click "Proceed Interest" button
3. Check backend logs

**Expected Logs:**
```
========================================
=== INTEREST CAPITALIZATION STARTED ===
========================================
Account Number: 1101010000001
System Date (Business Date) from SystemDateService: 2026-01-29
LocalDate.now() (Device Date - NOT USED): 2026-01-29

=== GETTING CURRENT BALANCE - AUDIT ===
Account Number: 1101010000001
System Date (Business Date): 2026-01-29
LocalDate.now() (Device Date): 2026-01-29
No balance record found for system date 2026-01-29. Trying latest record...
NO balance records found for account 1101010000001. Total records in acct_bal: 0

=== UPDATING ACCOUNT BALANCE - AUDIT ===
Account Number: 1101010000001
System Date (Business Date): 2026-01-29
LocalDate.now() (Device Date): 2026-01-29
Accrued Interest to Add: 150.00
No balance record found for system date 2026-01-29. Trying latest record...
=== BALANCE RECORD NOT FOUND - DETAILED AUDIT ===
Account Number: 1101010000001
System Date searched: 2026-01-29
Total balance records for this account: 0
NO balance records exist for this account in acct_bal table!
```

**Expected Response:**
```json
{
  "timestamp": "2026-01-29T11:28:12.691",
  "status": 400,
  "error": "Business Rule Violation",
  "message": "Account balance record not found for system date. Account: 1101010000001, System Date: 2026-01-29, Device Date: 2026-01-29. Available dates: NONE",
  "path": "/api/interest-capitalization"
}
```

**Next Action:** Run database fix script to create missing balance record

**Status:** ✅ EXPECTED ERROR (with detailed diagnosis)

---

### Test Case 4: After Database Fix

**Setup:**
```sql
-- Run the fix
SET @account_number = 'YOUR_ACCOUNT_NUMBER';
SET @system_date = (SELECT Param_Value FROM Parameter_Table WHERE Param_Name = 'System_Date');

INSERT INTO Acct_Bal (
    Tran_Date, Account_No, Account_Ccy,
    Opening_Bal, DR_Summation, CR_Summation, Closing_Bal,
    Current_Balance, Available_Balance, Last_Updated
)
SELECT 
    @system_date, @account_number, COALESCE(Account_Ccy, 'BDT'),
    0.00, 0.00, 0.00, 0.00,
    0.00, 0.00, NOW()
FROM Cust_Acct_Master
WHERE Account_No = @account_number;

-- Verify
SELECT * FROM Acct_Bal WHERE Account_No = @account_number;
```

**Test Steps:**
1. Navigate to account in frontend
2. Click "Proceed Interest" button
3. Check backend logs

**Expected Result:** Same as Test Case 1 (success)

**Status:** ✅ PASS / ❌ FAIL

---

## 🔍 WHAT TO LOOK FOR IN LOGS

### Key Log Sections:

#### 1. Entry Point
```
=== INTEREST CAPITALIZATION STARTED ===
```
- Shows account number
- Shows system date (business date)
- Shows device date for comparison

#### 2. Get Current Balance
```
=== GETTING CURRENT BALANCE - AUDIT ===
```
- Shows what date is being searched
- Shows if balance found for system date
- Shows if fallback to latest record worked
- **Lists available dates if nothing found**

#### 3. Update Account Balance
```
=== UPDATING ACCOUNT BALANCE - AUDIT ===
```
- Shows accrued interest amount
- Shows balance before and after
- **Shows detailed error if balance not found**
- **Lists ALL available dates for the account**

---

## 📊 LOG ANALYSIS GUIDE

### Good Signs ✅
- "Found balance record for system date"
- "Account balance updated successfully"
- "Interest capitalization completed"
- Transaction ID generated (starts with 'C')

### Warning Signs ⚠️
- "No balance record found for system date. Trying latest record..."
- This is OK if fallback works

### Error Signs 🔴
- "NO balance records found for account"
- "Total balance records for this account: 0"
- "NO balance records exist for this account in acct_bal table!"
- "Available dates: NONE"

---

## 🐛 TROUBLESHOOTING

### Issue: System Date is Wrong
**Symptom:** Logs show different date than expected
**Check:**
```sql
SELECT * FROM Parameter_Table WHERE Param_Name = 'System_Date';
```
**Fix:**
```sql
UPDATE Parameter_Table 
SET Param_Value = '2026-01-29' 
WHERE Param_Name = 'System_Date';
```

---

### Issue: No Accrued Interest
**Symptom:** Error "There is no accrued interest"
**Check:**
```sql
SELECT * FROM Acct_Bal_Accrual WHERE Account_No = 'YOUR_ACCOUNT_NUMBER';
```
**Fix:** Run interest accrual process first

---

### Issue: Account Not Interest-Bearing
**Symptom:** Error "The account is Non-Interest bearing"
**Check:**
```sql
SELECT 
    cam.Account_No,
    pm.Interest_Bearing_Flag
FROM Cust_Acct_Master cam
JOIN Sub_Prod_Master spm ON cam.Sub_Product_Id = spm.Sub_Product_Id
JOIN Prod_Master pm ON spm.Product_Id = pm.Product_Id
WHERE cam.Account_No = 'YOUR_ACCOUNT_NUMBER';
```
**Fix:** Update product to be interest-bearing

---

### Issue: Interest Already Capitalized Today
**Symptom:** Error "Interest has already been capitalized"
**Check:**
```sql
SELECT 
    Account_No,
    Last_Interest_Payment_Date
FROM Cust_Acct_Master
WHERE Account_No = 'YOUR_ACCOUNT_NUMBER';
```
**Fix:** This is a business rule - cannot capitalize twice on same date

---

## 📝 TEST RESULTS TEMPLATE

```
Date: _______________
Tester: _______________
Backend Version: _______________

Test Case 1: Account with Balance for System Date
Status: [ ] PASS [ ] FAIL
Notes: _________________________________

Test Case 2: Account with Balance for Different Date
Status: [ ] PASS [ ] FAIL
Notes: _________________________________

Test Case 3: Account with NO Balance Records
Status: [ ] EXPECTED ERROR [ ] UNEXPECTED ERROR
Notes: _________________________________

Test Case 4: After Database Fix
Status: [ ] PASS [ ] FAIL
Notes: _________________________________

Overall Result: [ ] ALL TESTS PASSED [ ] ISSUES FOUND

Issues Found:
1. _________________________________
2. _________________________________
3. _________________________________

Recommendations:
1. _________________________________
2. _________________________________
3. _________________________________
```

---

## ✅ SUCCESS CRITERIA

The fix is successful if:
1. ✅ Logs show clear audit trail for every step
2. ✅ System date vs device date is visible in logs
3. ✅ When error occurs, logs show ALL available dates
4. ✅ Error message includes diagnostic information
5. ✅ After database fix, interest capitalization works
6. ✅ Transactions created correctly
7. ✅ Balance updated correctly
8. ✅ Accrued balance reset to 0

---

## 🚀 NEXT STEPS AFTER TESTING

Based on test results:

### If All Tests Pass ✅
1. Document the fix
2. Update user documentation
3. Train users on error messages
4. Monitor production logs

### If Tests Fail ❌
1. Review logs for error details
2. Check database state
3. Verify system date configuration
4. Run diagnostic SQL script
5. Apply database fixes as needed

---

**Test Plan Version:** 1.0  
**Last Updated:** 2026-01-29  
**Status:** Ready for Testing
