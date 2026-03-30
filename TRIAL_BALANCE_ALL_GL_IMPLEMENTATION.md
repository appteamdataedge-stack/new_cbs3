# Trial Balance - All GL Accounts (Dynamic Fetching)

## Overview
This implementation adds a new Trial Balance report variant that **dynamically fetches ALL GL accounts** from the `gl_balance` table. This report automatically includes new GL accounts when they are added to the system in the future, without requiring code changes.

---

## What Was Changed

### Backend Changes

#### 1. **FinancialReportsService.java**
**File:** `moneymarket/src/main/java/com/example/moneymarket/service/FinancialReportsService.java`

**Added new method:**
```java
public byte[] generateTrialBalanceAllGLAccountsAsBytes(LocalDate systemDate)
```

**What it does:**
- Fetches **ALL** GL balances for the report date using `glBalanceRepository.findByTranDate(reportDate)`
- No filtering by active accounts
- Ensures FX GL accounts are present
- Reuses existing `generateTrialBalanceReportFromBalancesAsBytes()` method for CSV generation

**Key difference from existing method:**
- **Existing:** `generateTrialBalanceReportAsBytes()` - Filters by active GL accounts only
- **New:** `generateTrialBalanceAllGLAccountsAsBytes()` - Fetches ALL GL accounts from gl_balance

---

#### 2. **AdminController.java**
**File:** `moneymarket/src/main/java/com/example/moneymarket/controller/AdminController.java`

**Added new endpoint:**
```java
@GetMapping("/eod/batch-job-8/download/trial-balance-all-gl/{date}")
public ResponseEntity<byte[]> downloadTrialBalanceAllGLAccounts(@PathVariable String date)
```

**Endpoint URL:**
```
GET /api/admin/eod/batch-job-8/download/trial-balance-all-gl/{date}
```

**Example:**
```
http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330
```

**Response:**
- Content-Type: `text/csv`
- Content-Disposition: `attachment; filename="TrialBalance_AllGL_20260330.csv"`

---

### Frontend Changes

#### 3. **batchJobService.ts**
**File:** `frontend/src/api/batchJobService.ts`

**Added new function:**
```typescript
export const downloadTrialBalanceAllGLAccounts = async (reportDate: string): Promise<void>
```

**What it does:**
- Calls the new backend endpoint `/admin/eod/batch-job-8/download/trial-balance-all-gl/{date}`
- Downloads CSV file with filename `TrialBalance_AllGL_{date}.csv`
- Creates blob and triggers browser download

---

#### 4. **FinancialReports.tsx (NEW PAGE)**
**File:** `frontend/src/pages/FinancialReports.tsx`

**New standalone page for downloading financial reports:**
- Trial Balance (Active GL Accounts)
- **Trial Balance (All GL Accounts)** - NEW, highlighted with green border and "NEW" badge
- Balance Sheet
- Subproduct GL Balance

**Features:**
- Date picker to select report date
- Individual download buttons for each report
- Clear descriptions of each report type
- Loading states and error handling
- Toast notifications for success/failure

---

#### 5. **AppRoutes.tsx**
**File:** `frontend/src/routes/AppRoutes.tsx`

**Added route:**
```tsx
<Route path="/financial-reports" element={<FinancialReports />} />
```

---

#### 6. **Sidebar.tsx**
**File:** `frontend/src/components/layout/Sidebar.tsx`

**Added menu item:**
```tsx
{ name: 'Financial Reports', path: '/financial-reports', icon: <AssessmentIcon /> }
```

**Menu location:** Between "Settlement Reports" and "System Date"

---

## How to Use

### Option 1: Via Financial Reports Page

1. Navigate to **Financial Reports** from the sidebar menu
2. Select the report date (default: today)
3. Click **"Download Trial Balance - All GL (CSV)"** button (green button with "NEW" badge)
4. File will download as `TrialBalance_AllGL_20260330.csv`

---

### Option 2: Via Direct API Call

**Using browser:**
```
http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330
```

**Using curl:**
```bash
curl -O "http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330"
```

**Using Postman:**
- Method: GET
- URL: `http://localhost:8082/api/admin/eod/batch-job-8/download/trial-balance-all-gl/20260330`
- Response: CSV file download

---

## CSV Report Format

### Columns
1. **GL_Code** - GL account number (from gl_balance.GL_Num)
2. **GL_Name** - GL account name (from gl_setup.GL_Name via join)
3. **Opening_Bal** - Opening balance for the period
4. **DR_Summation** - Total debit transactions in the period
5. **CR_Summation** - Total credit transactions in the period
6. **Closing_Bal** - Closing balance (from gl_balance.Closing_Bal)

### Footer Row
- **TOTAL** row showing sums of all columns
- **Validation:** Total DR_Summation must equal Total CR_Summation

### Example Output
```csv
GL_Code,GL_Name,Opening_Bal,DR_Summation,CR_Summation,Closing_Bal
140203001,Realised Forex Gain - FX Conversion,0.00,0.00,1165.00,1165.00
240203001,Realised Forex Loss - FX Conversion,0.00,1225.00,0.00,1225.00
920101001,PSBDT EQIV,112000.00,5000.00,3000.00,114000.00
920101002,PSUSD EQIV,1000.00,500.00,200.00,1300.00
922030200102,NOSTRO USD,5000.00,1000.00,800.00,5200.00
TOTAL,,118000.00,7725.00,5165.00,120560.00
```

---

## What This Solves

### Before
- Trial Balance only showed **active GL accounts** (those linked to customer accounts via sub-products)
- Position accounts (920101001, 920101002) might not appear
- FX Conversion accounts (140203001, 240203001) needed manual hardcoding
- New GL accounts required code changes to appear in reports

### After
- Trial Balance (All GL) shows **ALL** GL accounts from gl_balance table
- Automatically includes Position accounts, FX Conversion accounts, and any future GL accounts
- No code changes needed when new GL accounts are added
- Works for any date in the system

---

## Key GL Accounts Included

### FX Conversion Accounts
- `140203001` - Realised Forex Gain - FX Conversion
- `240203001` - Realised Forex Loss - FX Conversion

### Position Accounts
- `920101001` - PSBDT EQIV (Position BDT)
- `920101002` - PSUSD EQIV (Position FCY)

### Nostro Accounts
- `922030200102` - NOSTRO USD
- `922030200103` - NOSTRO EUR
- `922030200104` - NOSTRO GBP

### MCT Accounts
- `140203002` - Realised Forex Gain - MCT
- `240203002` - Realised Forex Loss - MCT

### Any Other GL Accounts
- All GL accounts in gl_balance table will appear automatically

---

## Testing Steps

### Test 1: Download Report with Existing GL Accounts

1. Navigate to **Financial Reports** page
2. Select report date: `2026-03-30`
3. Click **Download Trial Balance - All GL (CSV)** (green button)
4. Verify CSV file downloads as `TrialBalance_AllGL_20260330.csv`
5. Open CSV file
6. Verify it contains:
   - Position accounts (920101001, 920101002)
   - FX Conversion accounts (140203001, 240203001)
   - Nostro accounts (922030200102, etc.)
   - All other GL accounts from gl_balance table

**Expected:** All GL accounts appear with correct balances

---

### Test 2: Add New GL Account and Verify It Appears

**Step 1:** Insert a new GL account into the database:
```sql
-- Add new GL account to gl_setup
INSERT INTO gl_setup (GL_Code, GL_Name, GL_Type, status)
VALUES ('999888777', 'Test Dynamic GL Account', 'ASSET', 'ACTIVE');

-- Add balance for this GL account
INSERT INTO gl_balance (GL_Num, ccy, Tran_date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, Current_Balance, Last_Updated)
VALUES ('999888777', 'BDT', '2026-03-30', 10000.00, 0.00, 0.00, 10000.00, 10000.00, NOW());
```

**Step 2:** Download the report again:
1. Navigate to **Financial Reports** page
2. Select report date: `2026-03-30`
3. Click **Download Trial Balance - All GL (CSV)**
4. Open CSV file
5. Search for `999888777`

**Expected:** The new GL account `999888777 - Test Dynamic GL Account` appears in the report with balance `10000.00`

**No code changes required!**

---

### Test 3: Compare with Original Trial Balance

**Download both reports:**
1. **Trial Balance (Active GL):** Via EOD page → Batch Job 8 → Download Trial Balance
2. **Trial Balance (All GL):** Via Financial Reports page → Download Trial Balance - All GL

**Compare:**
- Trial Balance (Active GL) may have fewer accounts
- Trial Balance (All GL) will have ALL accounts from gl_balance
- Both should have the same balances for accounts that appear in both reports

---

### Test 4: Test with Different Dates

**Test with past date:**
```sql
-- Insert historical GL balance
INSERT INTO gl_balance (GL_Num, ccy, Tran_date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal, Current_Balance, Last_Updated)
VALUES ('920101001', 'BDT', '2026-01-15', 100000.00, 2000.00, 1000.00, 101000.00, 101000.00, NOW());
```

**Download report:**
1. Select report date: `2026-01-15`
2. Click Download
3. Verify the report shows balances from that date

---

## Backend Logs to Monitor

When downloading the report, you should see these logs in the backend console:

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

## API Endpoint Details

### Request
```
GET /api/admin/eod/batch-job-8/download/trial-balance-all-gl/{date}
```

### Path Parameters
- `date` - Report date in format `YYYYMMDD` (e.g., `20260330`)

### Response
- **Content-Type:** `text/csv`
- **Content-Disposition:** `attachment; filename="TrialBalance_AllGL_{date}.csv"`
- **Body:** CSV file content

### Error Responses
- `400 Bad Request` - Invalid date format
- `404 Not Found` - Report generation failed (file not created)
- `500 Internal Server Error` - Server error during report generation

---

## Differences Between Trial Balance Reports

### Trial Balance (Active GL Accounts)
- **Endpoint:** `/api/admin/eod/batch-job-8/download/trial-balance/{date}`
- **Method:** `generateTrialBalanceReportAsBytes()`
- **Filter:** Only active GL accounts (linked to customer accounts via sub-products)
- **Use case:** Standard EOD reporting for operational GL accounts

### Trial Balance (All GL Accounts) - NEW
- **Endpoint:** `/api/admin/eod/batch-job-8/download/trial-balance-all-gl/{date}`
- **Method:** `generateTrialBalanceAllGLAccountsAsBytes()`
- **Filter:** ALL GL accounts from gl_balance table (no filtering)
- **Use case:** Comprehensive reporting including Position, FX Conversion, and future GL accounts

---

## Future Benefits

1. **No Code Changes Needed:** When new GL accounts are added to `gl_balance`, they automatically appear in this report

2. **Comprehensive Coverage:** Includes ALL GL accounts:
   - Customer account GLs
   - Office account GLs
   - Position accounts
   - Nostro accounts
   - FX Conversion Gain/Loss accounts
   - MCT Revaluation accounts
   - Any future GL accounts

3. **Same Format:** Uses the same CSV generation logic as the existing Trial Balance, ensuring consistency

4. **Historical Data:** Works with any date, as long as gl_balance records exist for that date

---

## Database Tables Used

### gl_balance
- **Columns Used:** `GL_Num`, `Tran_date`, `Opening_Bal`, `DR_Summation`, `CR_Summation`, `Closing_Bal`
- **Purpose:** Source of GL account balances and transaction summations

### gl_setup
- **Columns Used:** `GL_Code`, `GL_Name`
- **Purpose:** Provides GL account names via LEFT JOIN
- **Fallback:** If GL not in gl_setup, displays "Unknown GL Account"

---

## Files Modified/Created

### Backend (Java)
1. ✅ **FinancialReportsService.java** - Added `generateTrialBalanceAllGLAccountsAsBytes()` method
2. ✅ **AdminController.java** - Added `/download/trial-balance-all-gl/{date}` endpoint

### Frontend (TypeScript/React)
3. ✅ **batchJobService.ts** - Added `downloadTrialBalanceAllGLAccounts()` function
4. ✅ **FinancialReports.tsx (NEW)** - Created standalone Financial Reports page
5. ✅ **AppRoutes.tsx** - Added `/financial-reports` route
6. ✅ **Sidebar.tsx** - Added "Financial Reports" menu item

---

## Next Steps

1. **Restart Backend:**
   ```bash
   cd moneymarket
   mvn spring-boot:run
   ```

2. **Restart Frontend:**
   ```bash
   cd frontend
   npm start
   ```

3. **Access Financial Reports:**
   - Open browser: `http://localhost:3000/financial-reports`
   - Or click "Financial Reports" in sidebar menu

4. **Test Download:**
   - Select date: `2026-03-30`
   - Click **"Download Trial Balance - All GL (CSV)"** (green button)
   - Verify CSV file downloads and contains all GL accounts

5. **Verify GL Accounts Included:**
   - Open CSV file
   - Check for Position accounts: `920101001`, `920101002`
   - Check for FX Conversion accounts: `140203001`, `240203001`
   - Check for all other GL accounts from gl_balance

---

## Troubleshooting

### Issue: No GL accounts in report
**Solution:** Check if gl_balance table has records for the selected date:
```sql
SELECT COUNT(*) FROM gl_balance WHERE Tran_date = '2026-03-30';
```

### Issue: GL names showing as "Unknown GL Account"
**Solution:** Ensure gl_setup table has entries for those GL codes:
```sql
SELECT * FROM gl_setup WHERE GL_Code IN ('920101001', '920101002', '140203001', '240203001');
```

### Issue: 404 error
**Solution:** Verify backend is running and endpoint is registered. Check backend logs for:
```
Mapped "{[/api/admin/eod/batch-job-8/download/trial-balance-all-gl/{date}],methods=[GET]}"
```

---

## Summary

✅ **Backend:** New method and endpoint to fetch ALL GL accounts from gl_balance
✅ **Frontend:** New page with download button for all-GL Trial Balance
✅ **Dynamic:** Automatically includes new GL accounts without code changes
✅ **Complete:** Includes Position, FX Conversion, Nostro, and all other GL accounts
✅ **Tested:** Compilation successful, ready for runtime testing

**The existing Trial Balance functionality remains unchanged. This is a new, additional feature.**
