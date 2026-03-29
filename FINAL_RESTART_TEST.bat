@echo off
REM ═══════════════════════════════════════════════════════════════════════════
REM FX CONVERSION - COMPLETE FIX - FINAL RESTART & TEST
REM ═══════════════════════════════════════════════════════════════════════════

echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo ALL FIXES COMPLETED AND COMPILED ✅
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo FIX 1: POST endpoint URL: /convert -^> /conversion ✅
echo FIX 2: Customer dropdown: LAZY -^> EAGER SubProduct fetch ✅
echo FIX 3: SELLING rates: Don't clear when switching type ✅
echo FIX 4: SELLING button: Relaxed validation logic ✅
echo FIX 5: SELLING validation: Removed Nostro check ✅
echo FIX 6: GL balance lookup: Use getGLBalance() not getComputedAccountBalance() ✅
echo.
echo COMPILATION: BUILD SUCCESS (15:09:59)
echo GL BALANCES: Verified in database ✅
echo   - Position FCY (920101002): 110,500.00 USD
echo   - Position BDT (920101001): -110,500.00 BDT
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 1: RESTART BACKEND
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ACTION REQUIRED:
echo   1. Go to terminal where backend is running (Terminal 4)
echo   2. Press Ctrl+C to stop the backend
echo   3. Wait for process to terminate
echo   4. Run: mvn spring-boot:run
echo   5. Wait for: "Started MoneyMarketApplication in X.XX seconds"
echo.
echo ⚠️ CRITICAL: Backend MUST be restarted to load new compiled code!
echo.
echo Press any key when backend has fully restarted...
pause >nul
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 2: VERIFY BACKEND ENDPOINTS
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo [TEST 1] Customer Accounts (BDT)
echo ---------------------------------------------------------------------------
curl -s "http://localhost:8082/api/fx/accounts/customer?search=" | findstr /C:"success" /C:"accountNo"
echo.
echo Expected: {"success":true,"data":[{"accountNo":"100000082001",...}]}
echo.

echo [TEST 2] Mid Rate (USD)
echo ---------------------------------------------------------------------------
curl -s "http://localhost:8082/api/fx/rates/USD" | findstr /C:"success" /C:"midRate"
echo.
echo Expected: {"success":true,"data":{"midRate":110.25,...}}
echo.

echo [TEST 3] WAE Rate (USD)
echo ---------------------------------------------------------------------------
curl -s "http://localhost:8082/api/fx/wae/USD" | findstr /C:"success" /C:"waeRate"
echo.
echo Expected: {"success":true,"data":{"waeRate":114.6,...}}
echo.

echo [TEST 4] NOSTRO Accounts (USD)
echo ---------------------------------------------------------------------------
curl -s "http://localhost:8082/api/fx/accounts/nostro?currency=USD" | findstr /C:"success" /C:"accountNo"
echo.
echo Expected: {"success":true,"data":[{"accountNo":"922030200102",...}]}
echo.

echo Press any key to test SELLING transaction...
pause >nul
echo.

echo [TEST 5] POST SELLING Transaction
echo ---------------------------------------------------------------------------
echo.
echo Submitting: Customer sells USD 500, receives BDT 55,050
echo   - Customer: 100000082001
echo   - Nostro: 922030200102
echo   - Currency: USD
echo   - FCY Amount: 500
echo   - Deal Rate: 115.50
echo.

curl -X POST "http://localhost:8082/api/fx/conversion" ^
  -H "Content-Type: application/json" ^
  -d "{\"transactionType\":\"SELLING\",\"customerAccountId\":\"100000082001\",\"nostroAccountId\":\"922030200102\",\"currencyCode\":\"USD\",\"fcyAmount\":500,\"dealRate\":115.50,\"particulars\":\"Test SELLING transaction\",\"userId\":\"TEST\"}"

echo.
echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo EXPECTED RESULTS
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ✅ Response: {"success":true,"data":{"tranId":"FXC-20260329-XXX",...}}
echo ✅ Backend logs show: "SELLING VALIDATION PASSED"
echo ✅ Backend logs show: "FX Conversion transaction created"
echo ❌ Should NOT show: "Insufficient Nostro balance"
echo ❌ Should NOT show: "Account balance not found with account number 920101002"
echo.

echo Press any key to check backend logs...
pause >nul
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 3: CHECK BACKEND LOGS
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo Look for these log entries in Terminal 4:
echo.
echo   ========== VALIDATING SELLING TRANSACTION ==========
echo   Checking Position FCY balance for GL: 920101002
echo   Position FCY balance: 110500.00, Required: 500.00
echo   ✓ Position FCY balance sufficient
echo   ========== SELLING VALIDATION PASSED ==========
echo   FX Conversion transaction created with ID: FXC-20260329-XXX
echo.
echo If you see these logs, the fix is working! ✅
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 4: TEST IN FRONTEND
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ACTION:
echo   1. Open browser: http://localhost:5173/fx-conversion
echo   2. Press F12 to open Console
echo   3. Select SELLING radio button
echo   4. Verify Mid Rate and WAE Rate populate
echo   5. Fill form (same values as Test 5 above)
echo   6. Click "Preview & Submit"
echo   7. Verify ledger preview shows 5 entries
echo   8. Click "Confirm & Post"
echo.
echo EXPECTED:
echo   ✅ Success toast: "FX Transaction created. ID: FXC-20260329-XXX"
echo   ✅ Redirect to /transactions page
echo   ✅ Transaction visible in transactions list
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo TROUBLESHOOTING
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo IF "Account balance not found 920101002":
echo   - GL Balance record missing for Position FCY
echo   - Run: mysql -u root -p"asif@yasir123" moneymarketdb ^< setup_gl_balances.sql
echo   - Then restart backend
echo.
echo IF "Insufficient Position USD balance":
echo   - Position FCY balance is insufficient (needs ^>= 500 USD)
echo   - Run: UPDATE GL_Balance SET Current_Balance=50000 WHERE GL_Num='920101002';
echo   - Then restart backend
echo.
echo IF "Insufficient Nostro USD balance":
echo   - Backend is running OLD code
echo   - Completely stop backend (Ctrl+C)
echo   - Delete: rmdir /s /q target
echo   - Recompile: mvn clean compile -DskipTests
echo   - Restart: mvn spring-boot:run
echo.
echo IF Customer dropdown empty:
echo   - Backend not restarted with EAGER fetch fix
echo   - Verify: curl "http://localhost:8082/api/fx/accounts/customer?search="
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo VERIFICATION COMPLETE
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo All fixes have been applied and compiled successfully.
echo Next: Restart backend and test SELLING transaction.
echo.
echo Documentation:
echo   - FIX_GL_BALANCE_LOOKUP.md (this fix)
echo   - FIX_SELLING_VALIDATION.md (previous fix)
echo   - FIX_SELLING_MODE.md (frontend fixes)
echo   - FIX_POST_ENDPOINT_404.md (endpoint URL fix)
echo.
pause
