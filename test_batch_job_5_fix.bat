@echo off
REM ========================================
REM Test Script for Batch Job 5 Missing GL Data Fix
REM Date: 2025-04-05
REM Missing GLs: 130103001, 240103001, 220302001, 110203001
REM ========================================

echo ========================================
echo TEST: Batch Job 5 Missing GL Data Fix
echo ========================================
echo.

echo Step 1: Wait for backend to fully start...
timeout /t 60 /nobreak > nul
echo Backend should be ready now.
echo.

echo Step 2: Check backend health...
curl -s http://localhost:8082/actuator/health
echo.
echo.

echo Step 3: Delete existing gl_balance entries for 2025-04-05...
mysql -u root -pasif@yasir123 moneymarketdb -e "DELETE FROM gl_balance WHERE Tran_Date = '2025-04-05';"
echo Deleted existing gl_balance entries.
echo.

echo Step 4: Run Batch Job 5...
curl -X POST http://localhost:8082/api/admin/eod/batch/gl-balance ^
  -H "Content-Type: application/json" ^
  -d "{\"systemDate\": \"2025-04-05\"}"
echo.
echo.
echo Batch Job 5 executed.
echo.

echo Step 5: Wait 5 seconds for processing to complete...
timeout /t 5 /nobreak > nul
echo.

echo Step 6: Verify missing GLs are now in gl_balance...
mysql -u root -pasif@yasir123 moneymarketdb -e "SELECT GL_Num, Tran_Date, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal FROM gl_balance WHERE GL_Num IN ('130103001', '240103001', '220302001', '110203001') AND Tran_Date = '2025-04-05' ORDER BY GL_Num;"
echo.

echo ========================================
echo TEST COMPLETE
echo ========================================
echo.
echo If you see all 4 GL numbers above, the fix is working!
echo If any are missing, check the backend logs for errors.
echo.
pause
