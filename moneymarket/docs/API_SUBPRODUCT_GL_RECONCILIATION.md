# Sub-Product GL Balance Reconciliation API Documentation

## Base URL
```
http://localhost:8080/api/subproduct-gl-reconciliation
```

## Endpoints

### 1. Generate Reconciliation Report

Generate Excel reconciliation report comparing sub-product account balances vs GL balances.

**Endpoint:** `POST /generate`

**Parameters:**
- `reportDate` (optional, query parameter): Date for the report in ISO format (YYYY-MM-DD)
  - If not provided, uses current system date

**Request Example:**
```bash
POST http://localhost:8080/api/subproduct-gl-reconciliation/generate?reportDate=2024-01-15
```

**cURL Example:**
```bash
curl -X POST "http://localhost:8080/api/subproduct-gl-reconciliation/generate?reportDate=2024-01-15" \
  -H "Accept: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  -o reconciliation_report.xlsx
```

**Response:**
- **Status:** 200 OK
- **Content-Type:** `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- **Body:** Excel file (binary)
- **Filename:** `SubProduct_GL_Reconciliation_YYYY-MM-DD.xlsx`

**Error Response:**
```json
{
  "error": "Internal Server Error",
  "message": "An error occurred while generating the reconciliation report: <details>"
}
```

**Excel Report Structure:**

**Header:**
- Title: "SUB-PRODUCT GL BALANCE RECONCILIATION REPORT (EOD STEP 8)"
- Report Date

**Columns:**
1. Sub-Product Code
2. Sub-Product Name
3. GL Number
4. GL Name
5. Account Count
6. Total Account Balance (LCY)
7. FCY Reference
8. GL Balance (LCY)
9. Difference
10. Status

**Summary Row:**
- Total accounts
- Total balances
- Total difference
- Matched/Unmatched count

**Notes Section:**
- Explanation of columns and calculations

---

### 2. Get Drill-Down Details

Retrieve detailed information for a specific sub-product including all underlying accounts and GL postings.

**Endpoint:** `GET /drill-down/{subProductCode}`

**Path Parameters:**
- `subProductCode` (required): Sub-product code to drill down into

**Query Parameters:**
- `reportDate` (optional): Date for the report in ISO format (YYYY-MM-DD)
  - If not provided, uses current system date

**Request Example:**
```bash
GET http://localhost:8080/api/subproduct-gl-reconciliation/drill-down/SP001?reportDate=2024-01-15
```

**cURL Example:**
```bash
curl -X GET "http://localhost:8080/api/subproduct-gl-reconciliation/drill-down/SP001?reportDate=2024-01-15" \
  -H "Accept: application/json"
```

**Success Response:**
```json
{
  "subProductCode": "SP001",
  "subProductName": "Savings Account",
  "glNum": "1001001",
  "reportDate": "2024-01-15",
  "accountBalances": [
    {
      "accountNo": "1234567890123",
      "accountName": "John Doe",
      "accountType": "Customer",
      "currency": "BDT",
      "lcyBalance": 50000.00
    },
    {
      "accountNo": "1234567890124",
      "accountName": "Jane Smith USD Account",
      "accountType": "Customer",
      "currency": "USD",
      "lcyBalance": 120000.00
    },
    {
      "accountNo": "9876543210987",
      "accountName": "Branch Operating Account",
      "accountType": "Office",
      "currency": "BDT",
      "lcyBalance": 30000.00
    }
  ],
  "totalAccountBalance": 200000.00,
  "fcyAccountsExist": true,
  "fcyBalances": "USD: 120000.00 (LCY equivalent)",
  "glPostings": [
    {
      "date": "2024-01-15",
      "tranId": "TXN001",
      "description": "Deposit Transaction",
      "debit": 50000.00,
      "credit": 0.00,
      "source": "GL Movement"
    },
    {
      "date": "2024-01-15",
      "tranId": "ACCR001",
      "description": "Interest Accrual",
      "debit": 0.00,
      "credit": 500.00,
      "source": "Interest Accrual"
    }
  ],
  "glBalance": 200000.00,
  "difference": 0.00
}
```

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| subProductCode | String | Sub-product unique code |
| subProductName | String | Sub-product display name |
| glNum | String | Corresponding GL account number |
| reportDate | Date | Report date in ISO format |
| accountBalances | Array | List of account balance details |
| accountBalances[].accountNo | String | Account number |
| accountBalances[].accountName | String | Account holder/purpose name |
| accountBalances[].accountType | String | "Customer" or "Office" |
| accountBalances[].currency | String | Account currency (BDT, USD, EUR, etc.) |
| accountBalances[].lcyBalance | Decimal | Balance in local currency |
| totalAccountBalance | Decimal | Sum of all account balances in LCY |
| fcyAccountsExist | Boolean | True if any FCY accounts exist |
| fcyBalances | String | Display string of FCY balances grouped by currency |
| glPostings | Array | List of GL posting details |
| glPostings[].date | Date | Transaction date |
| glPostings[].tranId | String | Transaction ID |
| glPostings[].description | String | Transaction description/narration |
| glPostings[].debit | Decimal | Debit amount |
| glPostings[].credit | Decimal | Credit amount |
| glPostings[].source | String | "GL Movement" or "Interest Accrual" |
| glBalance | Decimal | GL account balance in LCY |
| difference | Decimal | totalAccountBalance - glBalance |

**Error Responses:**

**Sub-Product Not Found:**
```json
{
  "error": "Internal Server Error",
  "message": "An error occurred while retrieving drill-down details: SubProduct not found: SP999"
}
```

**Invalid Date Format:**
```json
{
  "error": "Bad Request",
  "message": "Invalid date format. Use YYYY-MM-DD"
}
```

---

### 3. Health Check

Check if the reconciliation service is running.

**Endpoint:** `GET /health`

**Request Example:**
```bash
GET http://localhost:8080/api/subproduct-gl-reconciliation/health
```

**cURL Example:**
```bash
curl -X GET "http://localhost:8080/api/subproduct-gl-reconciliation/health"
```

**Success Response:**
```json
{
  "status": "UP",
  "service": "Sub-Product GL Reconciliation (EOD Step 8)"
}
```

---

## Data Models

### SubProductReconciliationEntry (Internal DTO)

Used internally to build the Excel report.

```java
{
  "subProductCode": "String",
  "subProductName": "String",
  "glNum": "String",
  "glName": "String",
  "accountCount": "Long",
  "totalLCYBalance": "BigDecimal",
  "fcyAccountsExist": "boolean",
  "fcyBalances": "String",
  "glBalance": "BigDecimal",
  "difference": "BigDecimal",
  "status": "String" // "Matched" or "Unmatched"
}
```

### AccountBalanceInfo

```java
{
  "accountNo": "String",
  "accountName": "String",
  "accountType": "String", // "Customer" or "Office"
  "currency": "String",
  "lcyBalance": "BigDecimal"
}
```

### GLPostingDetail

```java
{
  "date": "LocalDate",
  "tranId": "String",
  "description": "String",
  "debit": "BigDecimal",
  "credit": "BigDecimal",
  "source": "String" // "GL Movement" or "Interest Accrual"
}
```

---

## Business Rules

### Currency Handling
- **All balances are in LCY (Local Currency - BDT)**
- Foreign currency (FCY) accounts show their LCY equivalent
- FCY amounts are displayed for reference only
- FCY balances grouped by currency in display string

### Reconciliation Logic
```
Difference = Total Account Balance - GL Balance

Status = {
    "Matched"   if Difference == 0
    "Unmatched" if Difference != 0
}
```

### Account Types Included
- **Customer Accounts**: From `Cust_Acct_Master` table
- **Office Accounts**: From `OF_Acct_Master` table

### Active Sub-Products Only
- Only includes sub-products with status = "Active"
- Sorted alphabetically by sub-product code

### Report Sorting
1. Unmatched items appear first
2. Within each status, sorted by difference amount (largest first)

---

## Error Handling

### HTTP Status Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 400 | Bad Request (invalid parameters) |
| 404 | Not Found (sub-product not found) |
| 500 | Internal Server Error |

### Error Response Format

All errors return JSON in the following format:
```json
{
  "error": "Error Type",
  "message": "Detailed error message"
}
```

---

## Usage Examples

### Example 1: Generate Daily Reconciliation Report

```javascript
// JavaScript/Frontend
async function generateDailyReport() {
    const today = new Date().toISOString().split('T')[0];
    const response = await fetch(
        `http://localhost:8080/api/subproduct-gl-reconciliation/generate?reportDate=${today}`,
        { method: 'POST' }
    );
    
    if (response.ok) {
        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `reconciliation_${today}.xlsx`;
        a.click();
    }
}
```

### Example 2: Get Drill-Down for Investigation

```javascript
// JavaScript/Frontend
async function investigateUnmatch(subProductCode) {
    const reportDate = document.getElementById('reportDate').value;
    const response = await fetch(
        `http://localhost:8080/api/subproduct-gl-reconciliation/drill-down/${subProductCode}?reportDate=${reportDate}`
    );
    
    if (response.ok) {
        const data = await response.json();
        displayDrillDownDetails(data);
    }
}
```

### Example 3: Java Service Integration

```java
@Service
public class EODService {
    
    @Autowired
    private SubProductGLReconciliationService reconciliationService;
    
    public void runEODStep8(LocalDate eodDate) {
        // Generate reconciliation report
        byte[] report = reconciliationService.generateReconciliationReport(eodDate);
        
        // Save to file system
        String filename = String.format("reconciliation_%s.xlsx", eodDate);
        Files.write(Paths.get("reports", filename), report);
        
        // Check for unmatched items
        // ... additional logic ...
    }
}
```

---

## Integration with EOD Process

This reconciliation should be executed as **Step 8** in the End-of-Day process:

```
EOD Steps:
1. Interest Accrual
2. Interest Capitalization
3. Account Balance Updates
4. GL Balance Updates
5. Double-Entry Validation
6. Financial Reports Generation
7. Statement of Accounts
8. Sub-Product GL Balance Reconciliation ← THIS STEP
9. System Date Advance
```

---

## Performance Considerations

### Expected Response Times
- **Generate Report**: 2-5 seconds for 100 sub-products
- **Drill-Down Details**: < 1 second for typical sub-product

### Optimization Tips
1. Run during off-peak hours for large reports
2. Use date parameter to avoid unnecessary system date lookups
3. Consider caching active sub-product list if calling multiple times

### Scalability
- Tested with up to 500 sub-products
- Excel generation uses streaming to handle large datasets
- Database queries optimized with proper indexes

---

## Testing

### Manual Testing with cURL

```bash
# Test 1: Generate report for today
curl -X POST "http://localhost:8080/api/subproduct-gl-reconciliation/generate" \
  -o report.xlsx

# Test 2: Generate report for specific date
curl -X POST "http://localhost:8080/api/subproduct-gl-reconciliation/generate?reportDate=2024-01-15" \
  -o report_20240115.xlsx

# Test 3: Get drill-down details
curl -X GET "http://localhost:8080/api/subproduct-gl-reconciliation/drill-down/SP001?reportDate=2024-01-15" | jq

# Test 4: Health check
curl -X GET "http://localhost:8080/api/subproduct-gl-reconciliation/health"
```

### Testing with Postman

1. **Import Collection**: Create a new collection with the three endpoints
2. **Environment Variables**: 
   - `baseUrl`: `http://localhost:8080`
   - `reportDate`: `2024-01-15`
   - `subProductCode`: `SP001`
3. **Tests**: Add assertions for status codes and response structure

---

## Troubleshooting

### Issue: "No active sub-products found"
- **Cause**: No sub-products with status = "Active"
- **Solution**: Check `Sub_Prod_Master` table for active records

### Issue: "GL balance not found"
- **Cause**: GL balance record missing for the date
- **Solution**: Ensure EOD GL balance update has run for the date

### Issue: "Large difference in reconciliation"
- **Cause**: Possible missing transactions or GL postings
- **Solution**: Use drill-down to investigate accounts and GL postings

### Issue: "FCY not showing correctly"
- **Cause**: Account currency not properly set
- **Solution**: Check `Account_Ccy` field in account balance records

---

## Security Notes

### Authentication (To Be Implemented)
This API currently has no authentication. In production:
- Add Spring Security with JWT tokens
- Implement role-based access control (RBAC)
- Add audit logging for report generation

### CORS Configuration
Currently configured with:
```java
@CrossOrigin(origins = "*")
```

For production, restrict to specific origins:
```java
@CrossOrigin(origins = {"https://yourdomain.com"})
```

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2024-01-15 | Initial implementation with FCY support |

---

## Support

For API issues or questions:
- Check application logs: `logs/moneymarket.log`
- Review this documentation
- Contact: Development Team
