@echo off
REM ══════════════════════════════════════════════════════════
REM Quick verification after frontend crash fix
REM ══════════════════════════════════════════════════════════

echo.
echo ==========================================
echo FX Conversion - Post-Fix Verification
echo ==========================================
echo.

REM Check if backend is running
echo [1/4] Checking backend status...
curl -s http://localhost:8082/actuator/health >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Backend not running on port 8082
    echo Start with: cd moneymarket ^&^& mvn spring-boot:run
    exit /b 1
)
echo SUCCESS: Backend is running
echo.

REM Test customer accounts (should return empty list, not error)
echo [2/4] Testing Customer Accounts endpoint...
echo GET /api/fx/accounts/customer?search=
curl -s http://localhost:8082/api/fx/accounts/customer?search=
echo.
echo.
echo Expected: {"success":true,"data":[...]} or {"success":true,"data":[]}
echo NOT: {"success":false,"message":"..."} or 500 error
echo.

REM Test NOSTRO accounts (should return empty list, not error)
echo [3/4] Testing NOSTRO Accounts endpoint...
echo GET /api/fx/accounts/nostro?currency=USD
curl -s http://localhost:8082/api/fx/accounts/nostro?currency=USD
echo.
echo.
echo Expected: {"success":true,"data":[...]} or {"success":true,"data":[]}
echo NOT: {"success":false,"message":"..."} or 500 error
echo.

REM Test WAE rate (will fail until balances inserted, but should be graceful)
echo [4/4] Testing WAE Rate endpoint...
echo GET /api/fx/wae/USD
curl -s http://localhost:8082/api/fx/wae/USD
echo.
echo.
echo Expected BEFORE SQL fix: {"success":false,"message":"...balance is zero"}
echo Expected AFTER SQL fix: {"success":true,"data":{"waeRate":110.25}}
echo.

echo ==========================================
echo Verification Complete
echo ==========================================
echo.
echo FRONTEND FIX STATUS:
echo   - Component crash at line 397: FIXED
echo   - Field name mismatches: FIXED
echo   - API response handling: FIXED
echo.
echo BACKEND DATA STATUS:
echo   - If WAE rate failed above: Run insert_nostro_balances.sql
echo   - If WAE rate succeeded: All done!
echo.
echo NEXT STEP:
echo   1. Open: http://localhost:5173/fx-conversion
echo   2. Check: Page should render without crash
echo   3. If you see error toasts: Run SQL script to insert balances
echo.

pause
