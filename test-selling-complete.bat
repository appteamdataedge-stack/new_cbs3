@echo off
REM Complete FX Conversion Fix - Restart and Test Guide

echo ═══════════════════════════════════════════════════════════════════════════
echo FX CONVERSION - COMPLETE FIX VERIFICATION
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ALL FIXES APPLIED:
echo   1. ✅ POST endpoint URL: /convert -^> /conversion
echo   2. ✅ SubProduct fetch: LAZY -^> EAGER
echo   3. ✅ SELLING validation: Removed Nostro check, added Position FCY check
echo   4. ✅ Frontend: Rates persist when switching transaction type
echo.
echo COMPILATION: ✅ BUILD SUCCESS
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 1: RESTART BACKEND
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ACTION REQUIRED:
echo   1. Go to terminal where backend is running (Terminal 4)
echo   2. Press Ctrl+C to stop
echo   3. Run: cd c:\new_cbs3\cbs3\moneymarket
echo   4. Run: mvn spring-boot:run
echo   5. Wait for: "Started MoneyMarketApplication"
echo.
echo Press any key when backend has restarted...
pause >nul
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 2: TEST BACKEND ENDPOINTS
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo [TEST 1] Customer Accounts (Should return 10+ BDT accounts)
echo ---------------------------------------------------------------------------
curl -X GET "http://localhost:8082/api/fx/accounts/customer?search="
echo.
echo.
echo Expected: {"success":true,"data":[...10+ accounts...]}
echo Press any key to continue...
pause >nul
echo.

echo [TEST 2] Mid Rate for USD
echo ---------------------------------------------------------------------------
curl -X GET "http://localhost:8082/api/fx/rates/USD"
echo.
echo.
echo Expected: {"success":true,"data":{"midRate":110.25,...}}
echo Press any key to continue...
pause >nul
echo.

echo [TEST 3] WAE Rate for USD
echo ---------------------------------------------------------------------------
curl -X GET "http://localhost:8082/api/fx/wae/USD"
echo.
echo.
echo Expected: {"success":true,"data":{"waeRate":114.6,...}}
echo Press any key to continue...
pause >nul
echo.

echo [TEST 4] NOSTRO Accounts for USD
echo ---------------------------------------------------------------------------
curl -X GET "http://localhost:8082/api/fx/accounts/nostro?currency=USD"
echo.
echo.
echo Expected: {"success":true,"data":[...NOSTRO accounts...]}
echo Press any key to continue...
pause >nul
echo.

echo [TEST 5] POST SELLING Transaction
echo ---------------------------------------------------------------------------
curl -X POST "http://localhost:8082/api/fx/conversion" ^
  -H "Content-Type: application/json" ^
  -d "{\"transactionType\":\"SELLING\",\"customerAccountId\":\"100000082001\",\"nostroAccountId\":\"922030200102\",\"currencyCode\":\"USD\",\"fcyAmount\":500,\"dealRate\":110.50,\"particulars\":\"Test SELLING\",\"userId\":\"TEST\"}"
echo.
echo.
echo Expected: {"success":true,"data":{"tranId":"FXC-20260329-XXX",...}}
echo.
echo ❌ If you see: "Insufficient Nostro USD balance" - Backend NOT restarted
echo ✅ If you see: Success with tranId - FIX WORKING!
echo.
echo Press any key to continue...
pause >nul
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 3: TEST FRONTEND
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ACTION:
echo   1. Open browser: http://localhost:5173/fx-conversion
echo   2. Press F12 to open DevTools Console
echo   3. Select SELLING radio button
echo   4. Verify Mid Rate and WAE Rate populate automatically
echo   5. Select customer: 100000082001 (Shahrukh Khan)
echo   6. Select currency: USD
echo   7. Select nostro: 922030200102
echo   8. Enter FCY Amount: 500
echo   9. Enter Deal Rate: 110.50
echo   10. Click "Preview & Submit"
echo   11. Verify ledger shows 5 entries
echo   12. Click "Confirm & Post"
echo.
echo EXPECTED RESULT:
echo   ✅ Success message: "Transaction created. ID: FXC-20260329-XXX"
echo   ✅ No "Insufficient Nostro balance" error
echo   ✅ Transaction saved with status "Entry"
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo TROUBLESHOOTING
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo IF CUSTOMER DROPDOWN EMPTY:
echo   - Backend not restarted with EAGER fetch fix
echo   - Run SQL: SELECT COUNT(*) FROM cust_acct_master WHERE Account_Ccy='BDT';
echo.
echo IF RATES NOT FETCHING:
echo   - Check browser console for errors
echo   - Verify backend endpoints return 200 OK (Tests 2 & 3 above)
echo.
echo IF SELLING FAILS WITH "Insufficient Position USD balance":
echo   - Run: verify_selling_balances.sql to check/insert Position FCY balance
echo   - Position account 920101002 must have Available_Balance ^> 0
echo.
echo IF STILL SHOWING "Insufficient Nostro USD balance":
echo   - Backend is running OLD code
echo   - Stop backend completely (Ctrl+C)
echo   - Delete target folder: rmdir /s /q target
echo   - Recompile: mvn clean compile -DskipTests
echo   - Restart: mvn spring-boot:run
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo ALL TESTS COMPLETED
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo FINAL CHECKLIST:
echo   ✓ Customer accounts dropdown populated
echo   ✓ Mid Rate fetches for both BUYING and SELLING
echo   ✓ WAE Rate fetches for both BUYING and SELLING
echo   ✓ NOSTRO accounts dropdown populated
echo   ✓ Preview & Submit button enabled
echo   ✓ Ledger preview displays correctly
echo   ✓ SELLING transaction posts successfully
echo   ✓ No "Insufficient Nostro balance" error
echo.
pause
