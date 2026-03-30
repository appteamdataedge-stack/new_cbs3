@echo off
REM Test Trial Balance All GL Accounts Endpoint
REM Date: 2026-03-30

echo ===================================================
echo Testing Trial Balance All GL Accounts Endpoint
echo ===================================================
echo.

REM Set variables
set BASE_URL=http://localhost:8082
set ENDPOINT=/api/admin/eod/batch-job-8/download/trial-balance-all-gl
set REPORT_DATE=20260330

echo Test 1: Check if backend is running
echo URL: %BASE_URL%/actuator/health
curl -s "%BASE_URL%/actuator/health" > nul 2>&1
if %errorlevel% neq 0 (
    echo [FAIL] Backend is not running on %BASE_URL%
    echo Please start backend: cd moneymarket ^&^& mvn spring-boot:run
    pause
    exit /b 1
)
echo [PASS] Backend is running
echo.

echo Test 2: Download Trial Balance (All GL Accounts)
echo URL: %BASE_URL%%ENDPOINT%/%REPORT_DATE%
echo Output: TrialBalance_AllGL_%REPORT_DATE%.csv
curl -o "TrialBalance_AllGL_%REPORT_DATE%.csv" "%BASE_URL%%ENDPOINT%/%REPORT_DATE%" -w "\nHTTP Status: %%{http_code}\n"
echo.

if exist "TrialBalance_AllGL_%REPORT_DATE%.csv" (
    echo [PASS] CSV file downloaded successfully
    echo File: TrialBalance_AllGL_%REPORT_DATE%.csv
    echo.
    
    echo File Contents Preview:
    echo ---------------------------------------------------
    type "TrialBalance_AllGL_%REPORT_DATE%.csv" | more
    echo ---------------------------------------------------
    echo.
    
    echo File saved to: %cd%\TrialBalance_AllGL_%REPORT_DATE%.csv
) else (
    echo [FAIL] CSV file not downloaded
    echo Check backend logs for errors
)
echo.

echo Test 3: Verify GL accounts are included
if exist "TrialBalance_AllGL_%REPORT_DATE%.csv" (
    echo Checking for Position accounts...
    findstr "920101001" "TrialBalance_AllGL_%REPORT_DATE%.csv" > nul && (
        echo [PASS] Found 920101001 - PSBDT EQIV
    ) || (
        echo [WARN] 920101001 not found
    )
    
    findstr "920101002" "TrialBalance_AllGL_%REPORT_DATE%.csv" > nul && (
        echo [PASS] Found 920101002 - PSUSD EQIV
    ) || (
        echo [WARN] 920101002 not found
    )
    
    echo.
    echo Checking for FX Conversion accounts...
    findstr "140203001" "TrialBalance_AllGL_%REPORT_DATE%.csv" > nul && (
        echo [PASS] Found 140203001 - Realised Forex Gain
    ) || (
        echo [WARN] 140203001 not found
    )
    
    findstr "240203001" "TrialBalance_AllGL_%REPORT_DATE%.csv" > nul && (
        echo [PASS] Found 240203001 - Realised Forex Loss
    ) || (
        echo [WARN] 240203001 not found
    )
)
echo.

echo Test 4: Count total GL accounts in report
if exist "TrialBalance_AllGL_%REPORT_DATE%.csv" (
    for /f %%i in ('type "TrialBalance_AllGL_%REPORT_DATE%.csv" ^| find /c /v ""') do set LINE_COUNT=%%i
    set /a ACCOUNT_COUNT=%LINE_COUNT%-2
    echo Total GL Accounts in report: %ACCOUNT_COUNT%
)
echo.

echo ===================================================
echo Test Complete
echo ===================================================
echo.
echo Next Steps:
echo 1. Review the downloaded CSV file
echo 2. Verify all GL accounts from gl_balance table are included
echo 3. Test in frontend: http://localhost:3000/financial-reports
echo 4. Compare with standard Trial Balance to see the difference
echo.
pause
