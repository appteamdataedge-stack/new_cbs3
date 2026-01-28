@echo off
REM Quick Start Script for Money Market CBS Application
REM This script starts the Spring Boot application with optimal settings

echo =========================================
echo Money Market CBS - Quick Start
echo =========================================
echo.

REM Check if we're in the correct directory
if not exist "pom.xml" (
    echo ERROR: pom.xml not found!
    echo Please run this script from the moneymarket directory
    pause
    exit /b 1
)

echo [1/3] Checking Java installation...
java -version
if errorlevel 1 (
    echo ERROR: Java not found! Please install Java 17 or higher
    pause
    exit /b 1
)
echo.

echo [2/3] Checking Maven installation...
mvn --version
if errorlevel 1 (
    echo ERROR: Maven not found! Please install Maven 3.6 or higher
    pause
    exit /b 1
)
echo.

echo [3/3] Starting Spring Boot Application...
echo This may take a few moments on first run (downloading dependencies)...
echo.
echo Application will be available at: http://localhost:8082
echo API Documentation: http://localhost:8082/swagger-ui.html
echo Health Check: http://localhost:8082/actuator/health
echo.
echo Press Ctrl+C to stop the application
echo.
echo =========================================

REM Start the application with skipTests to avoid test delays
mvn spring-boot:run -DskipTests

pause
