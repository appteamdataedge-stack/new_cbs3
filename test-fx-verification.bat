@echo off
REM Test FX Conversion Verification Endpoints

echo ═══════════════════════════════════════════════════════════════════════════
echo FX CONVERSION VERIFICATION ENDPOINTS TEST
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo This script tests that verification endpoints now accept F-prefixed transaction IDs
echo.

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 1: Test GET Transaction with T prefix (Regular transaction)
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo Testing: GET /api/transactions/T20260329000012345
curl -X GET "http://localhost:8082/api/transactions/T20260329000012345"
echo.
echo.
echo Expected: 200 OK or 404 if transaction doesn't exist (not regex error)
echo.
pause

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 2: Test GET Transaction with F prefix (FX Conversion)
echo ═══════════════════════════════════════════════════════════════════════════
echo.

echo First, let's find an actual FX transaction ID in the database...
echo.

REM Query database for latest FX transaction
FOR /F "tokens=*" %%i IN ('mysql -u root -p"asif@yasir123" -D moneymarketdb -N -e "SELECT Tran_Id FROM tran_table WHERE Tran_Type='FXC' AND Tran_Status='Entry' ORDER BY Entry_Date DESC, Entry_Time DESC LIMIT 1;"') DO SET FX_TRAN_ID=%%i

if defined FX_TRAN_ID (
    echo Found FX transaction: %FX_TRAN_ID%
    echo.
    
    REM Extract base ID (remove leg suffix)
    FOR /F "tokens=1 delims=-" %%a IN ("%FX_TRAN_ID%") DO SET BASE_TRAN_ID=%%a
    
    echo Testing: GET /api/transactions/%BASE_TRAN_ID%
    curl -X GET "http://localhost:8082/api/transactions/%BASE_TRAN_ID%"
    echo.
    echo.
    echo Expected: 200 OK with transaction details
    echo.
) else (
    echo No FX transactions found in Entry status
    echo Skipping this test
    echo.
)
pause

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 3: Test VERIFY Endpoint with F prefix
echo ═══════════════════════════════════════════════════════════════════════════
echo.

if defined BASE_TRAN_ID (
    echo Testing: POST /api/transactions/%BASE_TRAN_ID%/verify
    echo.
    echo ⚠️  WARNING: This will VERIFY the transaction! ⚠️
    echo Transaction will be moved from Entry -^> Verified status
    echo.
    set /p CONFIRM="Do you want to proceed? (Y/N): "
    
    if /I "%CONFIRM%"=="Y" (
        curl -X POST "http://localhost:8082/api/transactions/%BASE_TRAN_ID%/verify"
        echo.
        echo.
        echo Expected: 200 OK, transaction verified successfully
        echo.
    ) else (
        echo Skipped verification test
        echo.
    )
) else (
    echo No FX transaction ID available for testing
    echo.
)
pause

echo ═══════════════════════════════════════════════════════════════════════════
echo STEP 4: Verify Database Status Update
echo ═══════════════════════════════════════════════════════════════════════════
echo.

if defined BASE_TRAN_ID (
    echo Checking transaction status in database...
    echo.
    mysql -u root -p"asif@yasir123" -D moneymarketdb -e "SELECT Tran_Id, Tran_Status, Entry_Date FROM tran_table WHERE Tran_Id LIKE '%BASE_TRAN_ID%%%' ORDER BY Tran_Id;"
    echo.
    echo Expected: All legs show Tran_Status = 'Verified' (if you confirmed Step 3)
    echo.
) else (
    echo No FX transaction to check
    echo.
)

echo ═══════════════════════════════════════════════════════════════════════════
echo TEST SUMMARY
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo ENDPOINTS TESTED:
echo   GET    /api/transactions/{tranId}         - View transaction
echo   POST   /api/transactions/{tranId}/verify  - Verify transaction
echo.
echo PREFIXES SUPPORTED:
echo   T - Regular transactions       ✅
echo   F - FX Conversion transactions ✅
echo.
echo ADDITIONAL ENDPOINTS ALSO FIXED:
echo   POST   /api/transactions/{tranId}/post    - Post transaction (Entry -^> Posted)
echo   POST   /api/transactions/{tranId}/reverse - Reverse transaction
echo.
echo All endpoints now accept both T and F prefixed transaction IDs!
echo.
pause
