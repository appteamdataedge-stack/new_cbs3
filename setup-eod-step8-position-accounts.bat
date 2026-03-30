@echo off
REM ============================================
REM EOD Step 8 Position Accounts - Database Setup
REM ============================================

echo.
echo ========================================
echo EOD STEP 8 POSITION ACCOUNTS SETUP
echo ========================================
echo.

REM Update these variables if needed
set DB_HOST=localhost
set DB_PORT=3306
set DB_NAME=cbs3_db
set DB_USER=root

echo Setting up Position account data for EOD Step 8 Trial Balance...
echo.
echo Database: %DB_NAME%
echo Host: %DB_HOST%:%DB_PORT%
echo User: %DB_USER%
echo.

REM Run the SQL setup script
mysql -h %DB_HOST% -P %DB_PORT% -u %DB_USER% -p %DB_NAME% < eod-step8-position-accounts-setup.sql

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo SUCCESS: Database setup completed!
    echo ========================================
    echo.
    echo Next steps:
    echo 1. Restart backend: cd moneymarket ^&^& mvnw spring-boot:run
    echo 2. Execute EOD Step 8
    echo 3. Download Excel report and verify Position accounts
    echo.
    echo Position accounts that should now appear:
    echo   - 920101001 ^(PSBDT EQIV^) - BDT
    echo   - 920101002 ^(PSUSD EQIV^) - USD/EUR/GBP
    echo ========================================
) else (
    echo.
    echo ========================================
    echo ERROR: Database setup failed!
    echo ========================================
    echo.
    echo Troubleshooting:
    echo 1. Check if MySQL is running
    echo 2. Verify database credentials
    echo 3. Ensure cbs3_db database exists
    echo 4. Run SQL script manually in MySQL Workbench
    echo ========================================
)

echo.
pause
