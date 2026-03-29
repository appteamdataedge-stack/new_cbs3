@echo off
REM Test FX Conversion Backend Endpoints for SELLING Mode

echo ===============================================
echo Testing FX Conversion Backend - SELLING Mode
echo ===============================================
echo.

echo [TEST 1] GET Mid Rate for USD
echo ===============================================
curl -X GET "http://localhost:8082/api/fx/rates/USD"
echo.
echo.

echo [TEST 2] GET WAE Rate for USD
echo ===============================================
curl -X GET "http://localhost:8082/api/fx/wae/USD"
echo.
echo.

echo [TEST 3] GET Customer Accounts (BDT)
echo ===============================================
curl -X GET "http://localhost:8082/api/fx/accounts/customer?search="
echo.
echo.

echo [TEST 4] GET NOSTRO Accounts (USD)
echo ===============================================
curl -X GET "http://localhost:8082/api/fx/accounts/nostro?currency=USD"
echo.
echo.

echo [TEST 5] POST FX Conversion - SELLING
echo ===============================================
curl -X POST "http://localhost:8082/api/fx/conversion" ^
  -H "Content-Type: application/json" ^
  -d "{\"transactionType\":\"SELLING\",\"customerAccountId\":\"100000082001\",\"nostroAccountId\":\"922030200101\",\"currencyCode\":\"USD\",\"fcyAmount\":1000,\"dealRate\":115.50,\"particulars\":\"Test SELLING transaction\",\"userId\":\"TEST\"}"
echo.
echo.

echo ===============================================
echo All tests completed!
echo ===============================================
echo.
echo VERIFICATION:
echo - Test 1 should show: {"success":true,"data":{"midRate":110.25,...}}
echo - Test 2 should show: {"success":true,"data":{"waeRate":114.6,...}}
echo - Test 3 should show: {"success":true,"data":[...10+ accounts...]}
echo - Test 4 should show: {"success":true,"data":[...NOSTRO accounts...]}
echo - Test 5 should show: {"success":true,"data":{"tranId":"...","status":"Entry",...}}
echo.
pause
