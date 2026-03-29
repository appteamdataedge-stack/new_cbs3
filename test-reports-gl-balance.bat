@echo off
REM ═══════════════════════════════════════════════════════════════════════════
REM TEST: Trial Balance & Balance Sheet - FX GL Balance Display
REM Fix 10: Reports now fetch actual balances for forex GLs
REM ═══════════════════════════════════════════════════════════════════════════

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo FX CONVERSION - FINANCIAL REPORTS BALANCE TEST
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo This script verifies that forex GL accounts now display correct balances
echo in Trial Balance and Balance Sheet reports (Fix 10).
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 1: VERIFY DATABASE HAS FOREX GL BALANCES
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo Checking gl_balance table for forex GL accounts...
echo.

mysql -u root -p"asif@yasir123" -D moneymarketdb -e "SELECT GL_Num, Tran_Date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal FROM gl_balance WHERE GL_Num IN ('140203001', '140203002', '240203001', '240203002') AND Tran_Date = CURDATE() ORDER BY GL_Num;"

echo.
echo Expected:
echo   - 140203001 with non-zero balances (Forex Gain from FX Conversion)
echo   - 240203001 with non-zero balances (Forex Loss from FX Conversion)
echo.
echo If no records found:
echo   - FX transactions may not have been posted yet
echo   - Or check previous date with FX activity
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 2: CHECK HISTORICAL FOREX GL BALANCES (Last 7 Days)
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo Checking last 7 days for any forex GL activity...
echo.

mysql -u root -p"asif@yasir123" -D moneymarketdb -e "SELECT GL_Num, Tran_Date, Closing_Bal FROM gl_balance WHERE GL_Num IN ('140203001', '240203001') AND Tran_Date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) ORDER BY Tran_Date DESC, GL_Num;"

echo.
echo This shows when forex gains/losses were last recorded
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 3: RESTART BACKEND (If Not Already Done)
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo In terminal 4:
echo   1. Press Ctrl+C to stop backend
echo   2. Run: mvn spring-boot:run
echo   3. Wait for: Started MoneyMarketApplication
echo.
echo Have you restarted the backend with the new fix?
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 4: GENERATE TRIAL BALANCE REPORT (Frontend)
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo Manual Testing Steps:
echo.
echo 1. Open: http://localhost:5173/reports/trial-balance
echo 2. Click: "Generate Report" button
echo 3. Download: trial_balance_YYYYMMDD.csv file
echo 4. Open: CSV file in Excel or text editor
echo 5. Search: GL Code 140203001
echo 6. Verify: Closing Balance shows 236.82 (not 0.00)
echo 7. Search: GL Code 240203001
echo 8. Verify: Closing Balance shows -1225.00 (not 0.00)
echo.
echo Expected format:
echo   140203001,Realised Forex Gain,0.00,236.82,0.00,236.82
echo   240203001,Realised Forex Loss,0.00,0.00,1225.00,-1225.00
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 5: GENERATE BALANCE SHEET REPORT (Frontend)
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo Manual Testing Steps:
echo.
echo 1. Open: http://localhost:5173/reports/balance-sheet
echo 2. Click: "Generate Report" button
echo 3. Download: balance_sheet_YYYYMMDD.xlsx Excel file
echo 4. Open: Excel file
echo 5. Look: LIABILITIES section (left side)
echo 6. Find: 140203001 - Realised Forex Gain
echo 7. Verify: Closing Balance = 236.82 (not 0.00)
echo 8. Look: ASSETS section (right side)
echo 9. Find: 240203001 - Realised Forex Loss
echo 10. Verify: Closing Balance = -1225.00 (not 0.00)
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 6: CHECK BACKEND LOGS FOR DATABASE FETCH MESSAGES
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo In terminal 4 (backend logs), look for:
echo.
echo   "FX GL 140203001 not in active GL list, fetching from database..."
echo   "Found actual balance for 140203001: Opening=0.00, DR=236.82, CR=0.00, Closing=236.82"
echo   "FX GL 240203001 not in active GL list, fetching from database..."
echo   "Found actual balance for 240203001: Opening=0.00, DR=0.00, CR=1225.00, Closing=-1225.00"
echo.
echo These logs confirm the fix is working correctly.
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 7: TEST WITH NEW FX TRANSACTION
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo To verify fix completely:
echo.
echo 1. Create new FX SELLING transaction (generates forex gain/loss)
echo 2. Post and verify the transaction
echo 3. Wait for EOD process to update gl_balance
echo    (Or manually update gl_balance for testing)
echo 4. Generate Trial Balance → Verify updated balances
echo 5. Generate Balance Sheet → Verify updated balances
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 8: VERIFY FX GL POSTING LOGIC
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo Check recent FX transactions posted to forex GLs:
echo.

mysql -u root -p"asif@yasir123" -D moneymarketdb -e "SELECT Tran_Id, Tran_Date, Account_No, Dr_Cr, Ccy, Amount, LCY_Equiv, Narration FROM tran_table WHERE Account_No IN ('140203001', '240203001') ORDER BY Entry_Date DESC, Entry_Time DESC LIMIT 10;"

echo.
echo Expected:
echo   - Transaction IDs starting with F (FX Conversion)
echo   - Account_No = 140203001 (gains) or 240203001 (losses)
echo   - Dr_Cr = CR for gains, DR for losses
echo   - Amount matches forex calculation
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo COMPARISON: OLD vs NEW REPORT OUTPUT
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo OLD BEHAVIOR (INCORRECT):
echo ───────────────────────────────────────────────────────────────────────────
echo Trial Balance:
echo   140203001, Realised Forex Gain, 0.00, 0.00, 0.00, 0.00 ✗
echo   240203001, Realised Forex Loss, 0.00, 0.00, 0.00, 0.00 ✗
echo.
echo Balance Sheet:
echo   140203001 │ Realised Forex Gain │ 0.00 ✗
echo   240203001 │ Realised Forex Loss │ 0.00 ✗
echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo NEW BEHAVIOR (CORRECT):
echo ───────────────────────────────────────────────────────────────────────────
echo Trial Balance:
echo   140203001, Realised Forex Gain, 0.00, 236.82, 0.00, 236.82 ✓
echo   240203001, Realised Forex Loss, 0.00, 0.00, 1225.00, -1225.00 ✓
echo.
echo Balance Sheet:
echo   140203001 │ Realised Forex Gain │ 236.82 ✓
echo   240203001 │ Realised Forex Loss │ -1225.00 ✓
echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo.
pause

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo FIX 10 SUMMARY
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ISSUE:
echo   - Forex GL accounts always showed 0.00 in reports
echo   - Database had actual balances (236.82, -1225.00)
echo.
echo ROOT CAUSE:
echo   - ensureFxGLsPresent^(^) added hardcoded zero balances
echo   - Never queried database for actual balances
echo.
echo FIX:
echo   - Query gl_balance table directly for each forex GL
echo   - Use actual balance if found
echo   - Only use zero if no database record exists
echo.
echo FILES MODIFIED:
echo   - FinancialReportsService.java (Line 461)
echo   - EODStep8ConsolidatedReportService.java (Line 939)
echo.
echo COMPILATION: BUILD SUCCESS (16:12:04)
echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ✅ Fix 10 complete - Restart backend and test reports!
echo.
pause
