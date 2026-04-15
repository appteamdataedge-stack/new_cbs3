@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "TARGET_PORT=8082"
set "FOUND_ON_PORT=0"
set "KILLED_JAVA=0"

echo Stopping backend on port %TARGET_PORT%...

for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%TARGET_PORT% .*LISTENING"') do (
    set "FOUND_ON_PORT=1"
    call :kill_if_java %%P
)

if "%FOUND_ON_PORT%"=="0" (
    echo No process found on port %TARGET_PORT%
    exit /b 0
)

set "WAIT_COUNT=0"
:wait_for_release
netstat -ano | findstr /R /C:":%TARGET_PORT% .*LISTENING" >nul
if errorlevel 1 goto stopped_ok
set /a WAIT_COUNT+=1
if %WAIT_COUNT% GEQ 10 goto stop_failed
ping -n 2 127.0.0.1 >nul
goto wait_for_release

:stopped_ok
echo Backend stopped successfully
exit /b 0

:stop_failed
echo ERROR: Port %TARGET_PORT% is still in use after stop attempt.
exit /b 1

:kill_if_java
set "PID_TO_CHECK=%~1"
tasklist /FI "PID eq %PID_TO_CHECK%" /FO CSV /NH 2>nul | findstr /I "\"java.exe\"" >nul
if errorlevel 1 (
    echo PID %PID_TO_CHECK% is not java.exe, skipping.
    goto :eof
)
echo Killing java.exe (PID %PID_TO_CHECK%)...
taskkill /F /PID %PID_TO_CHECK% >nul 2>&1
set "KILLED_JAVA=1"
goto :eof
