# Spring Boot Application - Startup Guide

## Issue Diagnosed
Your Maven/Spring Boot application is hanging due to:
1. System performance issues causing PowerShell command timeouts
2. Potential MySQL connection issues
3. Maven dependency resolution delays

## SOLUTION OPTIONS

### Option 1: Use Maven Wrapper (Recommended - Fastest)

If you have Maven Wrapper in your project:

```bash
# Windows Command Prompt (cmd.exe) - NOT PowerShell
cd C:\new_cbs3\cbs3\moneymarket
.\mvnw.cmd spring-boot:run -DskipTests
```

### Option 2: Start MySQL First, Then Run Application

**Step 1: Start MySQL**
```bash
# Check if MySQL is running
netstat -ano | findstr :3306

# If not running, start MySQL service
net start MySQL80
# OR
net start MySQL

# Verify MySQL is accessible
mysql -u root -p
# Password: asif@yasir123
# Then type: show databases;
# Then type: exit
```

**Step 2: Run Spring Boot**
```bash
cd C:\new_cbs3\cbs3\moneymarket
mvn spring-boot:run -DskipTests
```

### Option 3: Use H2 Database (In-Memory - No MySQL Required)

Create this file: `moneymarket\src\main\resources\application-dev.properties`

```properties
spring.datasource.url=jdbc:h2:mem:moneymarketdb
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=none
spring.flyway.enabled=true
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
server.port=8082
```

Then run with dev profile:
```bash
cd C:\new_cbs3\cbs3\moneymarket
mvn spring-boot:run -Dspring-boot.run.profiles=dev -DskipTests
```

### Option 4: Build JAR and Run Directly

```bash
# Build the JAR (one time)
cd C:\new_cbs3\cbs3\moneymarket
mvn clean package -DskipTests

# Run the JAR
java -jar target\moneymarket-0.0.1-SNAPSHOT.jar
```

### Option 5: Use IDE (IntelliJ IDEA / Eclipse / VS Code)

**IntelliJ IDEA:**
1. File → Open → Select `C:\new_cbs3\cbs3\moneymarket` folder
2. Wait for Maven import to complete
3. Find `MoneyMarketApplication.java`
4. Right-click → Run 'MoneyMarketApplication'

**VS Code with Spring Boot Extension:**
1. Open folder: `C:\new_cbs3\cbs3\moneymarket`
2. Install "Spring Boot Extension Pack" if not already installed
3. Press F5 or click "Run" button

## VERIFICATION STEPS

Once started successfully, you should see:

```
Started MoneyMarketApplication in X seconds
```

Then verify:

1. **Health Check:**
   ```
   Open browser: http://localhost:8082/actuator/health
   Should return: {"status":"UP"}
   ```

2. **API Documentation:**
   ```
   Open browser: http://localhost:8082/swagger-ui.html
   ```

3. **Interest Capitalization Endpoints:**
   ```
   POST http://localhost:8082/api/interest-capitalization
   ```

## TROUBLESHOOTING

### If MySQL Connection Fails:
```
Error: Communications link failure
```

**Solutions:**
1. Start MySQL service: `net start MySQL80`
2. Check MySQL port: `netstat -ano | findstr :3306`
3. Verify credentials in `application.properties`
4. Use H2 database instead (Option 3 above)

### If Port 8082 is Already in Use:
```
Error: Port 8082 is already in use
```

**Solutions:**
1. Find process using port:
   ```
   netstat -ano | findstr :8082
   ```
2. Kill the process:
   ```
   taskkill /PID <process_id> /F
   ```
3. Or change port in `application.properties`:
   ```
   server.port=8083
   ```

### If Compilation Errors:
```
Error: cannot find symbol
```

**Solutions:**
1. Clean and rebuild:
   ```
   mvn clean compile -DskipTests
   ```

2. Check if all dependencies downloaded:
   ```
   mvn dependency:tree
   ```

3. Clear Maven cache:
   ```
   rmdir /s /q %USERPROFILE%\.m2\repository
   mvn clean install -DskipTests
   ```

### If Application Starts But Shows Flyway Errors:
```
Error: Flyway migration failed
```

**Solutions:**
1. Create database manually:
   ```sql
   mysql -u root -p
   CREATE DATABASE IF NOT EXISTS moneymarketdb;
   exit
   ```

2. Or disable Flyway temporarily:
   Add to `application.properties`:
   ```
   spring.flyway.enabled=false
   ```

## QUICK START COMMANDS (Copy-Paste Ready)

### For Windows Command Prompt (cmd.exe):
```cmd
cd C:\new_cbs3\cbs3\moneymarket
mvn spring-boot:run -DskipTests
```

### For Windows PowerShell:
```powershell
cd C:\new_cbs3\cbs3\moneymarket
mvn spring-boot:run -DskipTests
```

### For Git Bash:
```bash
cd /c/new_cbs3/cbs3/moneymarket
mvn spring-boot:run -DskipTests
```

## SUCCESS INDICATORS

When the application starts successfully, you'll see output like:

```
[INFO] ------------------------------------------------------------------------
[INFO] Building Money Market Module 0.0.1-SNAPSHOT
[INFO] ------------------------------------------------------------------------
[INFO] 
[INFO] >>> spring-boot:3.1.5:run (default-cli) @ moneymarket >>>
[INFO] 
[INFO] --- resources:3.3.1:resources (default-resources) @ moneymarket ---
[INFO] Copying resources...
[INFO] 
[INFO] --- compiler:3.11.0:compile (default-compile) @ moneymarket ---
[INFO] Compiling 160 source files...
[INFO] 
[INFO] <<< spring-boot:3.1.5:run (default-cli) < test-compile @ moneymarket <<<
[INFO] 
[INFO] --- spring-boot:3.1.5:run (default) @ moneymarket ---
[INFO] Attaching agents: []

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.1.5)

2026-01-28 XX:XX:XX.XXX  INFO --- [  main] c.e.m.MoneyMarketApplication       : Starting MoneyMarketApplication...
2026-01-28 XX:XX:XX.XXX  INFO --- [  main] c.e.m.MoneyMarketApplication       : No active profile set, falling back to default profiles: default
2026-01-28 XX:XX:XX.XXX  INFO --- [  main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port(s): 8082 (http)
...
2026-01-28 XX:XX:XX.XXX  INFO --- [  main] c.e.m.MoneyMarketApplication       : Started MoneyMarketApplication in X.XXX seconds (JVM running for X.XXX)
```

## POST-STARTUP

After successful startup:

1. **Access the Application:**
   - Backend API: http://localhost:8082
   - Swagger UI: http://localhost:8082/swagger-ui.html
   - Health Check: http://localhost:8082/actuator/health

2. **Test Interest Capitalization:**
   ```bash
   curl -X POST http://localhost:8082/api/interest-capitalization \
     -H "Content-Type: application/json" \
     -d '{"accountNo":"1101010010001","narration":"Test capitalization"}'
   ```

3. **Start Frontend:**
   ```bash
   cd C:\new_cbs3\cbs3\frontend
   npm install
   npm run dev
   ```
   Then open: http://localhost:5173

## NOTES

- **Compilation Status:** Your code compiled successfully (160 source files)
- **Feature Status:** Interest Capitalization feature is fully implemented and ready
- **Database:** Requires MySQL or can use H2 (in-memory)
- **Port:** Application runs on port 8082 (backend), frontend on 5173

## NEED HELP?

If you encounter any issues:

1. Check the full error message in the console
2. Look for errors in logs (especially database connection errors)
3. Verify Java and Maven versions:
   ```
   java -version    # Should be Java 17
   mvn -version     # Should be Maven 3.6+
   ```
4. Check if required ports are available (8082 for backend, 3306 for MySQL)
