# Trial Balance All GL Accounts - Testing Guide

## Pre-Testing Setup

### 1. Ensure Database Has GL Balance Data

Run this SQL script to verify and insert test data:

```sql
-- Check current GL balance records
SELECT 
    GL_Num, 
    ccy, 
    Tran_date, 
    Opening_Bal, 
    DR_Summation, 
    CR_Summation, 
    Closing_Bal
FROM gl_balance 
WHERE Tran_date = '2026-03-30'
ORDER BY GL_Num;

-- If no records, insert test data for today's date
INSERT INTO gl_balance (GL_Num, ccy, Tran_date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, Current_Balance, Last_Updated)
VALUES 
    -- Position accounts
    ('920101001', 'BDT', '2026-03-30', 112000.00, 5000.00, 3000.00, 114000.00, 114000.00, NOW()),
    ('920101002', 'USD', '2026-03-30', 1000.00, 500.00, 200.00, 1300.00, 1300.00, NOW()),
    
    -- FX Conversion accounts
    ('140203001', 'BDT', '2026-03-30', 0.00, 0.00, 1165.00, 1165.00, 1165.00, NOW()),
    ('240203001', 'BDT', '2026-03-30', 0.00, 1225.00, 0.00, 1225.00, 1225.00, NOW()),
    
    -- Nostro accounts
    ('922030200102', 'USD', '2026-03-30', 5000.00, 1000.00, 800.00, 5200.00, 5200.00, NOW()),
    ('922030200103', 'EUR', '2026-03-30', 2000.00, 500.00, 300.00, 2200.00, 2200.00, NOW())
ON DUPLICATE KEY UPDATE 
    Opening_Bal = VALUES(Opening_Bal),
    DR_Summation = VALUES(DR_Summation),
    CR_Summation = VALUES(CR_Summation),
    Closing_Bal = VALUES(Closing_Bal),
    Current_Balance = VALUES(Current_Balance),
    Last_Updated = NOW();

-- Verify GL setup entries exist
SELECT GL_Code, GL_Name FROM gl_setup 
WHERE GL_Code IN ('920101001', '920101002', '140203001', '240203001', '922030200102', '922030200103');

-- If missing, insert GL setup records
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, status)
VALUES 
    ('920101001', 'PSBDT EQIV', 'ASSET', 'ACTIVE'),
    ('920101002', 'PSUSD EQIV', 'ASSET', 'ACTIVE'),
    ('140203001', 'Realised Forex Gain - FX Conversion', 'INCOME', 'ACTIVE'),
    ('240203001', 'Realised Forex Loss - FX Conversion', 'EXPENSE', 'ACTIVE'),
    ('922030200102', 'NOSTRO USD', 'ASSET', 'ACTIVE'),
    ('922030200103', 'NOSTRO EUR', 'ASSET', 'ACTIVE')
ON DUPLICATE KEY UPDATE GL_Name = VALUES(GL_Name);
```

---

## Testing Scenarios

### Test 1: Backend Endpoint Direct Test

**Test using curl:**
```bash
curl -o TrialBalance_AllGL_20260330.csv \
  "http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330"
```

**Test using browser:**
1. Open browser
2. Navigate to: `http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330`
3. CSV file should download automatically

**Expected Result:**
- ✅ CSV file downloads as `TrialBalance_AllGL_20260330.csv`
- ✅ HTTP status: 200 OK
- ✅ Content-Type: text/csv
- ✅ File contains GL accounts with balances

**Backend Console Output:**
```
===========================================
Generating Trial Balance - ALL GL Accounts
Report Date: 2026-03-30
===========================================
Found 25 GL balance records for date: 2026-03-30
✓ Trial Balance (All GL) generated: 4567 bytes
===========================================
```

---

### Test 2: Frontend Page Test

**Step 1: Navigate to Financial Reports page**
1. Start frontend: `npm start` (in frontend folder)
2. Open browser: `http://localhost:3000`
3. Click **"Financial Reports"** in the sidebar menu
4. Verify page loads with 4 report cards

**Step 2: Download Trial Balance (All GL)**
1. Select Report Date: `2026-03-30` (or today's date)
2. Click **"Download Trial Balance - All GL (CSV)"** button (green button with "NEW" badge)
3. Verify success toast notification appears
4. Verify CSV file downloads as `TrialBalance_AllGL_20260330.csv`

**Expected Result:**
- ✅ Page loads without errors
- ✅ Green button is visible with "NEW" badge
- ✅ Date picker is functional
- ✅ Click triggers download
- ✅ Success toast: "Trial Balance (All GL Accounts) downloaded successfully"
- ✅ CSV file downloads to browser's download folder

**Browser Console Output:**
```
Downloading Trial Balance (All GL Accounts) for date: 20260330
[FX Service] Calling: /api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330
✓ Trial Balance (All GL) downloaded successfully
```

---

### Test 3: Verify CSV Content

**Open the downloaded CSV file and verify:**

1. **Header Row:**
   ```
   GL_Code,GL_Name,Opening_Bal,DR_Summation,CR_Summation,Closing_Bal
   ```

2. **Position Accounts Present:**
   - `920101001,PSBDT EQIV,112000.00,5000.00,3000.00,114000.00`
   - `920101002,PSUSD EQIV,1000.00,500.00,200.00,1300.00`

3. **FX Conversion Accounts Present:**
   - `140203001,Realised Forex Gain - FX Conversion,0.00,0.00,1165.00,1165.00`
   - `240203001,Realised Forex Loss - FX Conversion,0.00,1225.00,0.00,1225.00`

4. **Nostro Accounts Present:**
   - `922030200102,NOSTRO USD,5000.00,1000.00,800.00,5200.00`
   - `922030200103,NOSTRO EUR,2000.00,500.00,300.00,2200.00`

5. **Footer Row (Totals):**
   ```
   TOTAL,,{total_opening},{total_dr},{total_cr},{total_closing}
   ```

6. **Validation:**
   - Total DR_Summation should equal Total CR_Summation (accounting principle)

---

### Test 4: Dynamic GL Account Addition Test

**This test verifies that new GL accounts automatically appear without code changes.**

**Step 1: Insert a new GL account**
```sql
-- Add new test GL account
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, status)
VALUES ('777888999', 'Dynamic Test Account - Should Auto-Appear', 'ASSET', 'ACTIVE');

-- Add balance for this account
INSERT INTO gl_balance (GL_Num, ccy, Tran_date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, Current_Balance, Last_Updated)
VALUES ('777888999', 'BDT', '2026-03-30', 25000.00, 1000.00, 500.00, 25500.00, 25500.00, NOW());
```

**Step 2: Download report again**
1. Go to Financial Reports page
2. Select date: `2026-03-30`
3. Click **Download Trial Balance - All GL (CSV)**
4. Open CSV file

**Step 3: Search for the new account**
- Search for `777888999` in the CSV file

**Expected Result:**
- ✅ The new GL account appears in the report:
  ```
  777888999,Dynamic Test Account - Should Auto-Appear,25000.00,1000.00,500.00,25500.00
  ```

**✨ NO CODE CHANGES REQUIRED!**

---

### Test 5: Comparison Test (All GL vs Active GL)

**Download both Trial Balance reports:**

1. **Trial Balance (Active GL):**
   - Via EOD page → Batch Job 8 → Download
   - Or via Financial Reports page → Standard Trial Balance button

2. **Trial Balance (All GL):**
   - Via Financial Reports page → Green "All GL" button

**Compare the CSV files:**
- Trial Balance (All GL) should have **MORE** GL accounts
- Trial Balance (Active GL) only includes GL accounts linked to customer accounts
- Both should show the **SAME** balances for accounts that appear in both reports

**Example Differences:**

| GL Account | Active GL Report | All GL Report |
|------------|-----------------|---------------|
| 920101001 (Position BDT) | ❌ May not appear | ✅ Always appears |
| 920101002 (Position USD) | ❌ May not appear | ✅ Always appears |
| 140203001 (FX Gain) | ✅ Appears (if hardcoded) | ✅ Always appears |
| Customer GLs | ✅ Appears | ✅ Appears |
| Future GLs | ❌ Requires code change | ✅ Auto-appears |

---

### Test 6: Error Handling Test

**Test 1: Invalid date format**
```
http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/2026-03-30
```
**Expected:** 400 Bad Request (should use YYYYMMDD format, not YYYY-MM-DD)

**Test 2: Future date (no data)**
```
http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20270101
```
**Expected:** CSV file with empty data or header only

**Test 3: Past date**
```
http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260101
```
**Expected:** CSV file with GL balances from that date (if data exists)

---

## Manual Testing Checklist

- [ ] Backend compiles without errors
- [ ] Backend starts successfully
- [ ] Endpoint `/api/admin/eod/batch-job-8/download/trial-balance-all-gl/{date}` is mapped (check logs)
- [ ] Direct API call downloads CSV file
- [ ] CSV file has correct header row
- [ ] CSV file contains GL accounts from gl_balance table
- [ ] Position accounts (920101001, 920101002) are included
- [ ] FX Conversion accounts (140203001, 240203001) are included
- [ ] Frontend "Financial Reports" menu item appears in sidebar
- [ ] Financial Reports page loads without errors
- [ ] Green "All GL" button is visible with "NEW" badge
- [ ] Clicking button downloads CSV file
- [ ] Success toast notification appears
- [ ] CSV file opens in Excel/text editor
- [ ] Total DR_Summation equals Total CR_Summation (accounting validation)
- [ ] New GL accounts added to gl_balance automatically appear (Test 4)

---

## SQL Queries for Verification

### Query 1: Count GL accounts in gl_balance for a date
```sql
SELECT COUNT(DISTINCT GL_Num) as total_gl_accounts
FROM gl_balance 
WHERE Tran_date = '2026-03-30';
```

### Query 2: List all GL accounts with balances
```sql
SELECT 
    gb.GL_Num, 
    gs.GL_Name, 
    gb.ccy,
    gb.Opening_Bal, 
    gb.DR_Summation, 
    gb.CR_Summation, 
    gb.Closing_Bal
FROM gl_balance gb
LEFT JOIN gl_setup gs ON gb.GL_Num = gs.GL_Code
WHERE gb.Tran_date = '2026-03-30'
ORDER BY gb.GL_Num;
```

### Query 3: Verify totals match accounting principles
```sql
SELECT 
    SUM(DR_Summation) as total_debit,
    SUM(CR_Summation) as total_credit,
    SUM(DR_Summation) - SUM(CR_Summation) as difference
FROM gl_balance 
WHERE Tran_date = '2026-03-30';
-- Expected: difference should be 0 (total debits = total credits)
```

### Query 4: Check for GL accounts without names (orphaned GLs)
```sql
SELECT 
    gb.GL_Num, 
    'MISSING NAME' as issue
FROM gl_balance gb
LEFT JOIN gl_setup gs ON gb.GL_Num = gs.GL_Code
WHERE gb.Tran_date = '2026-03-30'
  AND gs.GL_Code IS NULL;
-- These will show as "Unknown GL Account" in the report
```

---

## Expected Test Results

### Backend Logs
```
===========================================
Generating Trial Balance - ALL GL Accounts
Report Date: 2026-03-30
===========================================
Found 47 GL balance records for date: 2026-03-30
✓ Trial Balance (All GL) generated: 8932 bytes
===========================================
```

### Frontend Console
```
Downloading Trial Balance (All GL Accounts) for date: 20260330
[FX Service] Calling: /api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330
✓ Trial Balance (All GL) downloaded successfully
```

### CSV File Structure
```csv
GL_Code,GL_Name,Opening_Bal,DR_Summation,CR_Summation,Closing_Bal
140203001,Realised Forex Gain - FX Conversion,0.00,0.00,1165.00,1165.00
240203001,Realised Forex Loss - FX Conversion,0.00,1225.00,0.00,1225.00
920101001,PSBDT EQIV,112000.00,5000.00,3000.00,114000.00
920101002,PSUSD EQIV,1000.00,500.00,200.00,1300.00
922030200102,NOSTRO USD,5000.00,1000.00,800.00,5200.00
... (all other GL accounts)
TOTAL,,{sum},{sum},{sum},{sum}
```

---

## Common Issues and Solutions

### Issue 1: CSV file is empty or has only header
**Cause:** No GL balance records for the selected date
**Solution:**
```sql
-- Check if data exists
SELECT COUNT(*) FROM gl_balance WHERE Tran_date = '2026-03-30';

-- If 0, run EOD process to generate GL balances for that date
-- Or insert test data (see Pre-Testing Setup)
```

---

### Issue 2: GL names showing as "Unknown GL Account"
**Cause:** GL codes exist in gl_balance but not in gl_setup
**Solution:**
```sql
-- Find orphaned GL codes
SELECT DISTINCT gb.GL_Num
FROM gl_balance gb
LEFT JOIN gl_setup gs ON gb.GL_Num = gs.GL_Code
WHERE gs.GL_Code IS NULL;

-- Insert missing GL setup records
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, status)
VALUES ('920101001', 'PSBDT EQIV', 'ASSET', 'ACTIVE')
ON DUPLICATE KEY UPDATE GL_Name = VALUES(GL_Name);
```

---

### Issue 3: 404 error when clicking download button
**Cause:** Backend endpoint not registered
**Solution:**
1. Check if backend is running: `http://localhost:8082/actuator/health`
2. Check backend logs for endpoint mapping:
   ```
   Mapped "{[/api/admin/eod/batch-job-8/download/trial-balance-all-gl/{date}],methods=[GET]}"
   ```
3. If not found, restart backend:
   ```bash
   cd moneymarket
   mvn clean install
   mvn spring-boot:run
   ```

---

### Issue 4: Total DR_Summation ≠ Total CR_Summation
**Cause:** Data integrity issue in gl_balance table
**Solution:**
```sql
-- Check imbalance
SELECT 
    SUM(DR_Summation) as total_dr,
    SUM(CR_Summation) as total_cr,
    SUM(DR_Summation) - SUM(CR_Summation) as imbalance
FROM gl_balance 
WHERE Tran_date = '2026-03-30';

-- If imbalance exists, investigate transaction entries
SELECT 
    GL_Num, 
    ccy, 
    DR_Summation, 
    CR_Summation,
    DR_Summation - CR_Summation as difference
FROM gl_balance 
WHERE Tran_date = '2026-03-30'
  AND (DR_Summation - CR_Summation) != 0
ORDER BY ABS(DR_Summation - CR_Summation) DESC;
```

---

### Issue 5: Position accounts (920101001, 920101002) not appearing
**Cause:** No gl_balance records for these accounts
**Solution:**
```sql
-- Check if Position accounts exist in gl_balance
SELECT * FROM gl_balance 
WHERE GL_Num IN ('920101001', '920101002') 
  AND Tran_date = '2026-03-30';

-- If missing, insert them
INSERT INTO gl_balance (GL_Num, ccy, Tran_date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, Current_Balance, Last_Updated)
VALUES 
    ('920101001', 'BDT', '2026-03-30', 112000.00, 0.00, 0.00, 112000.00, 112000.00, NOW()),
    ('920101002', 'USD', '2026-03-30', 1000.00, 0.00, 0.00, 1000.00, 1000.00, NOW())
ON DUPLICATE KEY UPDATE Last_Updated = NOW();
```

---

## Automated Test Script

**Run the provided batch script:**
```bash
cd c:\new_cbs3\cbs3
test-trial-balance-all-gl.bat
```

**Script performs:**
1. ✅ Checks if backend is running
2. ✅ Downloads Trial Balance All GL CSV
3. ✅ Previews CSV content
4. ✅ Checks for Position accounts (920101001, 920101002)
5. ✅ Checks for FX Conversion accounts (140203001, 240203001)
6. ✅ Counts total GL accounts in report

---

## Success Criteria

All of these should be true:

✅ **Backend:**
- Compiles without errors
- Endpoint is registered and accessible
- CSV file generates successfully
- Logs show correct GL account count

✅ **Frontend:**
- Financial Reports page loads
- Green "All GL" button is visible
- Button click triggers download
- Success toast notification appears

✅ **CSV File:**
- Downloads successfully
- Contains header row
- Contains Position accounts (920101001, 920101002)
- Contains FX Conversion accounts (140203001, 240203001)
- Contains all GL accounts from gl_balance table
- Footer row shows totals
- Total DR_Summation = Total CR_Summation

✅ **Dynamic Behavior:**
- New GL accounts added to gl_balance automatically appear in report
- No code changes required to include new accounts

---

## Next Steps After Testing

1. **Production Deployment:**
   - Deploy backend with new endpoint
   - Deploy frontend with new page and menu item
   - Notify users about new "Financial Reports" page

2. **User Training:**
   - Demonstrate difference between "Trial Balance" and "Trial Balance (All GL)"
   - Explain dynamic behavior (auto-includes new GLs)

3. **Documentation:**
   - Update user manual with new Financial Reports page
   - Document the dynamic GL fetching feature

---

## Rollback Plan (If Needed)

If issues occur, the new feature can be safely disabled:

1. **Remove menu item:** Comment out `{ name: 'Financial Reports', path: '/financial-reports', icon: <AssessmentIcon /> }` in Sidebar.tsx
2. **Remove route:** Comment out `<Route path="/financial-reports" element={<FinancialReports />} />` in AppRoutes.tsx
3. **Existing functionality unaffected:** The original Trial Balance endpoint remains unchanged

---

## Summary

✅ **Implementation Complete**
- Backend method and endpoint added
- Frontend page, route, and menu created
- Dynamically fetches ALL GL accounts from gl_balance
- Automatically includes new GL accounts in the future
- Existing Trial Balance functionality unchanged

✅ **Ready for Testing**
- Backend compiles successfully
- All files created/modified
- Test script provided
- Documentation complete

**Start testing by running: `test-trial-balance-all-gl.bat`**
