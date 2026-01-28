@echo off
REM Quick Start Script with H2 Database (No MySQL Required)
REM This script starts the Spring Boot application with H2 in-memory database

echo =========================================
echo Money Market CBS - Quick Start (H2 Mode)
echo =========================================
echo.
echo NOTE: Using H2 in-memory database
echo No MySQL installation required!
echo Data will be lost on application restart
echo.

REM Check if we're in the correct directory
if not exist "pom.xml" (
    echo ERROR: pom.xml not found!
    echo Please run this script from the moneymarket directory
    pause
    exit /b 1
)

echo Creating H2 configuration...

REM Create application-h2.properties file
(
echo # H2 Database Configuration
echo spring.datasource.url=jdbc:h2:mem:moneymarketdb
echo spring.datasource.driver-class-name=org.h2.Driver
echo spring.datasource.username=sa
echo spring.datasource.password=
echo spring.jpa.hibernate.ddl-auto=none
echo spring.flyway.enabled=true
echo spring.h2.console.enabled=true
echo spring.h2.console.path=/h2-console
echo server.port=8082
echo.
echo # Logging
echo logging.level.root=INFO
echo logging.level.com.example.moneymarket=DEBUG
) > src\main\resources\application-h2.properties

echo H2 configuration created!
echo.

echo Starting Spring Boot Application with H2 database...
echo This may take a few moments on first run...
echo.
echo Application will be available at: http://localhost:8082
echo H2 Console: http://localhost:8082/h2-console
echo   JDBC URL: jdbc:h2:mem:moneymarketdb
echo   Username: sa
echo   Password: (leave empty)
echo.
echo Press Ctrl+C to stop the application
echo.
echo =========================================

REM Start with H2 profile
mvn spring-boot:run -Dspring-boot.run.profiles=h2 -DskipTests

pause
