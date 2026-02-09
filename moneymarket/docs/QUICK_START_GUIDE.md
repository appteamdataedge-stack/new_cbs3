# Sub-Product GL Balance Reconciliation - Quick Start Guide

## For Developers

### Prerequisites
- Java 17+
- Spring Boot application running
- Database with populated tables (Sub_Prod_Master, Acct_Bal, GL_Balance, etc.)
- Maven for dependency management

---

## Quick Setup (5 Minutes)

### Step 1: Verify Files
Ensure these files are in your project:

```
moneymarket/
├── src/main/java/com/example/moneymarket/
│   ├── service/SubProductGLReconciliationService.java ✓
│   ├── controller/SubProductGLReconciliationController.java ✓
│   └── repository/SubProdMasterRepository.java (updated) ✓
├── src/main/resources/
│   └── static/subproduct-gl-reconciliation.html ✓
└── src/test/java/com/example/moneymarket/
    └── service/SubProductGLReconciliationServiceTest.java ✓
```

### Step 2: Build and Run
```bash
# Build the application
mvn clean install

# Run the application
mvn spring-boot:run
```

### Step 3: Access the Interface
Open your browser:
```
http://localhost:8080/subproduct-gl-reconciliation.html
```

---

## Quick Test (2 Minutes)

### Test 1: Health Check
```bash
curl http://localhost:8080/api/subproduct-gl-reconciliation/health
```

**Expected Response:**
```json
{
  "status": "UP",
  "service": "Sub-Product GL Reconciliation (EOD Step 8)"
}
```

### Test 2: Generate Report
```bash
curl -X POST "http://localhost:8080/api/subproduct-gl-reconciliation/generate?reportDate=2024-01-15" \
  -o reconciliation_report.xlsx
```

**Expected:** Excel file downloaded

### Test 3: Drill-Down
```bash
curl "http://localhost:8080/api/subproduct-gl-reconciliation/drill-down/SP001?reportDate=2024-01-15"
```

**Expected:** JSON with account and GL details

---

## Common Issues & Fixes

### Issue 1: "Cannot connect to service"
**Solution:** Ensure Spring Boot application is running on port 8080
```bash
# Check if running
netstat -ano | findstr :8080

# Or check application logs
tail -f logs/application.log
```

### Issue 2: "No active sub-products found"
**Solution:** Insert test data
```sql
-- Insert test sub-product
INSERT INTO Sub_Prod_Master (Sub_Product_Code, Sub_Product_Name, Cum_GL_Num, Sub_Product_Status, ...)
VALUES ('SP001', 'Test Savings', '1001001', 'Active', ...);

-- Verify
SELECT * FROM Sub_Prod_Master WHERE Sub_Product_Status = 'Active';
```

### Issue 3: "Empty report generated"
**Solution:** Ensure account and GL balances exist for the report date
```sql
-- Check data for date
DECLARE @date DATE = '2024-01-15';

SELECT 'Account Balances' AS Type, COUNT(*) AS Count FROM Acct_Bal WHERE Tran_Date = @date
UNION ALL
SELECT 'GL Balances', COUNT(*) FROM GL_Balance WHERE Tran_date = @date;
```

---

## Integration with EOD

### Add to EODService.java

```java
@Service
public class EODService {
    
    @Autowired
    private SubProductGLReconciliationService reconciliationService;
    
    public EODSummary runEODProcessing(LocalDate eodDate) {
        // ... existing steps 1-7 ...
        
        // Step 8: Sub-Product GL Reconciliation
        log.info("Step 8: Generating Sub-Product GL Reconciliation Report");
        try {
            byte[] report = reconciliationService.generateReconciliationReport(eodDate);
            
            // Save to file system
            String filename = String.format("SubProduct_GL_Recon_%s.xlsx", eodDate);
            Path reportPath = Paths.get("reports", "eod", filename);
            Files.createDirectories(reportPath.getParent());
            Files.write(reportPath, report);
            
            log.info("Step 8: Reconciliation report saved to {}", reportPath);
        } catch (Exception e) {
            log.error("Step 8: Failed to generate reconciliation report", e);
            throw new BusinessException("EOD Step 8 failed: " + e.getMessage());
        }
        
        // ... remaining steps ...
    }
}
```

---

## Code Examples

### Example 1: Programmatic Report Generation

```java
@Autowired
private SubProductGLReconciliationService reconciliationService;

public void generateMonthlyReports() {
    LocalDate startDate = LocalDate.of(2024, 1, 1);
    LocalDate endDate = LocalDate.of(2024, 1, 31);
    
    for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
        try {
            byte[] report = reconciliationService.generateReconciliationReport(date);
            saveReport(report, date);
        } catch (Exception e) {
            log.error("Failed to generate report for {}", date, e);
        }
    }
}
```

### Example 2: Get Unmatched Sub-Products

```java
@Autowired
private SubProductGLReconciliationService reconciliationService;
@Autowired
private SubProdMasterRepository subProdRepository;

public List<String> findUnmatchedSubProducts(LocalDate date) {
    List<String> unmatched = new ArrayList<>();
    List<SubProdMaster> subProducts = subProdRepository.findAllActiveSubProducts();
    
    for (SubProdMaster sp : subProducts) {
        var details = reconciliationService.getDrillDownDetails(sp.getSubProductCode(), date);
        if (details.getDifference().compareTo(BigDecimal.ZERO) != 0) {
            unmatched.add(sp.getSubProductCode());
        }
    }
    
    return unmatched;
}
```

### Example 3: Custom Alert for Unmatched Items

```java
@Service
public class ReconciliationAlertService {
    
    @Autowired
    private SubProductGLReconciliationService reconciliationService;
    
    @Scheduled(cron = "0 0 1 * * ?") // Run at 1 AM daily
    public void checkReconciliationAndAlert() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        
        // Get drill-down for all active sub-products
        List<SubProdMaster> subProducts = subProdRepository.findAllActiveSubProducts();
        List<String> issues = new ArrayList<>();
        
        for (SubProdMaster sp : subProducts) {
            var details = reconciliationService.getDrillDownDetails(sp.getSubProductCode(), yesterday);
            
            if (details.getDifference().compareTo(BigDecimal.ZERO) != 0) {
                String issue = String.format("Sub-Product %s: Difference = %s", 
                    sp.getSubProductCode(), 
                    details.getDifference());
                issues.add(issue);
            }
        }
        
        if (!issues.isEmpty()) {
            sendAlertEmail("Reconciliation Issues Found", issues);
        }
    }
}
```

---

## Frontend Customization

### Change API Base URL

Edit `subproduct-gl-reconciliation.html`:

```javascript
// Line ~485
const API_BASE_URL = 'http://your-server:8080/api/subproduct-gl-reconciliation';
```

### Customize Colors

Edit the `<style>` section:

```css
/* Change primary color */
body {
    background: linear-gradient(135deg, #your-color1 0%, #your-color2 100%);
}

.btn-primary {
    background: linear-gradient(135deg, #your-color1 0%, #your-color2 100%);
}
```

---

## Database Indexes for Performance

### Recommended Indexes

```sql
-- Index on account balances for faster lookups
CREATE INDEX IX_Acct_Bal_AccountNo_TranDate 
ON Acct_Bal (Account_No, Tran_Date);

-- Index on GL balances
CREATE INDEX IX_GL_Balance_GLNum_TranDate 
ON GL_Balance (GL_Num, Tran_date);

-- Index on sub-product status
CREATE INDEX IX_Sub_Prod_Master_Status_Code 
ON Sub_Prod_Master (Sub_Product_Status, Sub_Product_Code);

-- Index on customer accounts by sub-product
CREATE INDEX IX_Cust_Acct_Master_SubProductId 
ON Cust_Acct_Master (Sub_Product_Id);

-- Index on office accounts by sub-product
CREATE INDEX IX_OF_Acct_Master_SubProductId 
ON OF_Acct_Master (Sub_Product_Id);
```

---

## Testing Checklist

### Before Deployment

- [ ] Health check returns "UP"
- [ ] Can generate report for current date
- [ ] Can generate report for past date
- [ ] Excel file downloads correctly
- [ ] Drill-down returns data for valid sub-product
- [ ] Drill-down returns error for invalid sub-product
- [ ] Frontend loads without errors
- [ ] Frontend can connect to API
- [ ] Unit tests pass: `mvn test`
- [ ] No linter errors
- [ ] Logs show no errors

### Sample Test Data

```sql
-- Insert test sub-product
INSERT INTO Sub_Prod_Master (Sub_Product_Code, Sub_Product_Name, Product_Id, Cum_GL_Num, Sub_Product_Status, Maker_Id, Entry_Date, Entry_Time)
VALUES ('SPTEST', 'Test Savings Product', 1, '1001001', 'Active', 'SYSTEM', GETDATE(), CAST(GETDATE() AS TIME));

-- Insert test customer account
INSERT INTO Cust_Acct_Master (Account_No, Sub_Product_Id, GL_Num, Account_Ccy, Cust_Id, Acct_Name, Date_Opening, Branch_Code, Account_Status)
VALUES ('1234567890123', (SELECT Sub_Product_Id FROM Sub_Prod_Master WHERE Sub_Product_Code = 'SPTEST'), 
        '1001001', 'BDT', 1, 'Test Customer Account', GETDATE(), 'BR001', 'Active');

-- Insert test account balance
INSERT INTO Acct_Bal (Account_No, Tran_Date, Account_Ccy, Current_Balance, Available_Balance, Last_Updated)
VALUES ('1234567890123', GETDATE(), 'BDT', 100000.00, 100000.00, GETDATE());

-- Insert test GL balance
INSERT INTO GL_Balance (GL_Num, Tran_date, Current_Balance, Last_Updated)
VALUES ('1001001', GETDATE(), 100000.00, GETDATE());
```

---

## Monitoring

### Log Patterns to Monitor

```bash
# Success pattern
grep "Sub-Product GL Reconciliation Report generated successfully" logs/application.log

# Error pattern
grep "Error generating Sub-Product GL Reconciliation Report" logs/application.log

# Performance pattern
grep "Reconciliation complete:" logs/application.log
```

### Key Metrics

Monitor these in production:
- Report generation time (should be < 5 seconds for 100 sub-products)
- Number of unmatched sub-products
- API response times
- Error rates

---

## Troubleshooting Commands

### Check Service Status
```bash
curl http://localhost:8080/api/subproduct-gl-reconciliation/health
```

### Test with Sample Sub-Product
```bash
# Replace SP001 with actual sub-product code from your database
curl "http://localhost:8080/api/subproduct-gl-reconciliation/drill-down/SP001?reportDate=$(date +%Y-%m-%d)"
```

### Verify Data Exists
```sql
-- Check if any active sub-products exist
SELECT COUNT(*) AS Active_SubProducts 
FROM Sub_Prod_Master 
WHERE Sub_Product_Status = 'Active';

-- Check if balances exist for today
SELECT COUNT(*) AS Account_Balances 
FROM Acct_Bal 
WHERE Tran_Date = CAST(GETDATE() AS DATE);

SELECT COUNT(*) AS GL_Balances 
FROM GL_Balance 
WHERE Tran_date = CAST(GETDATE() AS DATE);
```

---

## Documentation Links

| Document | Purpose |
|----------|---------|
| [IMPLEMENTATION_SUMMARY.md](./IMPLEMENTATION_SUMMARY.md) | Complete feature overview |
| [SUBPRODUCT_GL_RECONCILIATION_README.md](./SUBPRODUCT_GL_RECONCILIATION_README.md) | Detailed implementation guide |
| [API_SUBPRODUCT_GL_RECONCILIATION.md](./API_SUBPRODUCT_GL_RECONCILIATION.md) | API reference |
| [SQL_QUERIES_RECONCILIATION.md](./SQL_QUERIES_RECONCILIATION.md) | SQL troubleshooting queries |

---

## Support

### Getting Help

1. **Check Logs:** `logs/application.log`
2. **Review Documentation:** See links above
3. **Run SQL Queries:** Use queries from SQL_QUERIES_RECONCILIATION.md
4. **Check Test Cases:** Review SubProductGLReconciliationServiceTest.java

### Common Debugging Steps

1. Verify service is running: Health check
2. Check data exists: SQL queries
3. Test API directly: cURL commands
4. Review application logs: Check for errors
5. Run unit tests: `mvn test`

---

## Next Steps

After successful setup:

1. ✅ Test with real data from your database
2. ✅ Integrate with EOD process
3. ✅ Set up monitoring and alerts
4. ✅ Configure backup for generated reports
5. ✅ Add authentication/authorization (production)
6. ✅ Schedule automated report generation
7. ✅ Train users on the interface

---

## Quick Reference Card

### URLs
- **Frontend:** http://localhost:8080/subproduct-gl-reconciliation.html
- **Health:** http://localhost:8080/api/subproduct-gl-reconciliation/health
- **Generate:** POST /api/subproduct-gl-reconciliation/generate?reportDate=YYYY-MM-DD
- **Drill-Down:** GET /api/subproduct-gl-reconciliation/drill-down/{code}?reportDate=YYYY-MM-DD

### Key Classes
- **Service:** SubProductGLReconciliationService
- **Controller:** SubProductGLReconciliationController
- **Test:** SubProductGLReconciliationServiceTest

### Key Tables
- Sub_Prod_Master (sub-products)
- Cust_Acct_Master (customer accounts)
- OF_Acct_Master (office accounts)
- Acct_Bal (account balances)
- GL_Balance (GL balances)
- GL_Movement (GL postings)
- GL_Movement_Accrual (interest accruals)

---

**You're all set! Start testing with the Quick Test section above. 🚀**
