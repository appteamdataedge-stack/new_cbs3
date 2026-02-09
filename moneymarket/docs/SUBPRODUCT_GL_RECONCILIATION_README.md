# Sub-Product GL Balance Reconciliation Report (EOD Step 8)

## Overview

This implementation provides a comprehensive reconciliation report that compares sub-product account balances against their corresponding GL balances. The system supports multi-currency (FCY) accounts and provides drill-down capabilities for detailed analysis.

## Features

### 1. Reconciliation Report
- **Compare**: Sub-product account balances vs GL balances
- **Multi-Currency Support**: Displays FCY amounts (in LCY equivalent) for reference
- **Status Tracking**: Automatically identifies matched vs unmatched balances
- **Excel Export**: Professional formatted Excel report with summary statistics

### 2. Drill-Down Capability
- **Account Details**: View all accounts under a sub-product with balances
- **GL Postings**: See all GL movements and accruals affecting the GL
- **FCY Breakdown**: Separate display of foreign currency accounts

### 3. Business Logic
- **Main Balance Always in LCY**: All reconciliation calculations use Local Currency (BDT)
- **FCY Reference Only**: Foreign currency amounts shown for information purposes
- **Date-Based**: Generate report for any business date
- **Comprehensive Coverage**: Includes both customer and office accounts

## Architecture

### Backend Components

#### 1. Service Layer
**File**: `SubProductGLReconciliationService.java`

**Key Methods**:
- `generateReconciliationReport(LocalDate reportDate)`: Generates Excel reconciliation report
- `getDrillDownDetails(String subProductCode, LocalDate reportDate)`: Provides detailed breakdown
- `calculateAccountBalances(SubProdMaster subProduct, LocalDate reportDate)`: Aggregates account balances with FCY breakdown
- `getGLBalance(String glNum, LocalDate reportDate)`: Retrieves GL balance
- `getGLPostings(String glNum, LocalDate reportDate)`: Fetches GL movements and accruals

**Key Features**:
- Transaction isolation with `@Transactional(readOnly = true)`
- Comprehensive error handling and logging
- Apache POI for Excel generation with professional styling
- Multi-currency awareness with FCY grouping

#### 2. Controller Layer
**File**: `SubProductGLReconciliationController.java`

**Endpoints**:

1. **Generate Report**
   ```
   POST /api/subproduct-gl-reconciliation/generate?reportDate=2024-01-15
   ```
   - Generates and downloads Excel reconciliation report
   - Returns Excel file as attachment

2. **Drill-Down Details**
   ```
   GET /api/subproduct-gl-reconciliation/drill-down/{subProductCode}?reportDate=2024-01-15
   ```
   - Returns JSON with account and GL posting details
   - Includes FCY breakdown

3. **Health Check**
   ```
   GET /api/subproduct-gl-reconciliation/health
   ```
   - Service health status

#### 3. Repository Extensions
**File**: `SubProdMasterRepository.java`

Added query:
- `findAllActiveSubProductsWithProduct()`: Eager load product relationships for optimization

### Frontend

**File**: `subproduct-gl-reconciliation.html`

**Features**:
- Modern, responsive design with gradient styling
- Date picker for report generation
- One-click Excel download
- Interactive drill-down interface
- Real-time status messages
- Professional data tables with formatting
- Health check on page load

**User Interface**:
1. **Main Screen**: Date selection and report generation
2. **Drill-Down Screen**: Detailed account and GL posting views
3. **Status Indicators**: Visual feedback for matched/unmatched items

## Report Columns

### Main Reconciliation Report

| Column | Description |
|--------|-------------|
| Sub-Product Code | Unique identifier for the sub-product |
| Sub-Product Name | Display name of the sub-product |
| GL Number | Corresponding GL account number |
| GL Name | GL account description |
| Account Count | Number of accounts under this sub-product |
| Total Account Balance (LCY) | Sum of all account balances in local currency |
| FCY Reference | Foreign currency amounts (LCY equivalent) for information |
| GL Balance (LCY) | GL account balance in local currency |
| Difference | Total Account Balance - GL Balance |
| Status | "Matched" (green) if difference = 0, "Unmatched" (red) otherwise |

### Drill-Down: Account Balances

| Column | Description |
|--------|-------------|
| Account No | Account number |
| Account Name | Account holder/purpose |
| Type | Customer or Office account |
| Currency | Account currency (BDT, USD, EUR, etc.) |
| Balance (LCY) | Account balance in local currency |

### Drill-Down: GL Postings

| Column | Description |
|--------|-------------|
| Date | Transaction date |
| Transaction ID | Unique transaction identifier |
| Description | Transaction narration |
| Debit | Debit amount |
| Credit | Credit amount |
| Source | GL Movement or Interest Accrual |

## Business Rules

### 1. Currency Handling
- **Primary Balance**: Always in LCY (BDT)
- **FCY Accounts**: Shown separately for reference
- **Conversion**: FCY amounts already converted to LCY in account balance
- **Display**: FCY grouped by currency with LCY equivalent shown

### 2. Account Types
- **Customer Accounts**: From `Cust_Acct_Master` table
- **Office Accounts**: From `OF_Acct_Master` table
- **Both Included**: In total account balance calculation

### 3. GL Balance Source
- **Table**: `GL_Balance`
- **Date Match**: Must match report date exactly
- **Account Match**: Uses sub-product's `Cum_GL_Num`

### 4. Reconciliation Logic
```
Difference = Total Account Balance - GL Balance

Status = {
    "Matched"   if Difference = 0
    "Unmatched" if Difference ≠ 0
}
```

### 5. Active Subproducts Only
- Only includes subproducts with `Sub_Product_Status = 'Active'`
- Sorted alphabetically by sub-product code

### 6. GL Postings Include
- Regular GL movements from `GL_Movement` table
- Interest accruals from `GL_Movement_Accrual` table
- Filtered by date and GL number

## Data Model

### Key Entities

1. **SubProdMaster**
   - `subProductCode`: Unique code
   - `subProductName`: Display name
   - `cumGLNum`: Corresponding GL account

2. **CustAcctMaster** / **OFAcctMaster**
   - `accountNo`: Account number
   - `subProduct`: Link to sub-product
   - `accountCcy`: Account currency

3. **AcctBal**
   - `accountNo`: Account reference
   - `tranDate`: Balance date
   - `currentBalance`: Balance in LCY
   - `accountCcy`: Currency code

4. **GLBalance**
   - `glNum`: GL account number
   - `tranDate`: Balance date
   - `currentBalance`: GL balance in LCY

5. **GLMovement** / **GLMovementAccrual**
   - GL posting transactions
   - Support drill-down analysis

## API Usage Examples

### 1. Generate Report for Today

```bash
curl -X POST "http://localhost:8080/api/subproduct-gl-reconciliation/generate?reportDate=2024-01-15" \
  -H "Accept: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  -o reconciliation_report.xlsx
```

### 2. Get Drill-Down Details

```bash
curl -X GET "http://localhost:8080/api/subproduct-gl-reconciliation/drill-down/SP001?reportDate=2024-01-15" \
  -H "Accept: application/json"
```

**Response**:
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
      "accountName": "Jane Smith",
      "accountType": "Customer",
      "currency": "USD",
      "lcyBalance": 120000.00
    }
  ],
  "totalAccountBalance": 170000.00,
  "fcyAccountsExist": true,
  "fcyBalances": "USD: 120000.00 (LCY equivalent)",
  "glPostings": [
    {
      "date": "2024-01-15",
      "tranId": "TXN001",
      "description": "Deposit",
      "debit": 50000.00,
      "credit": 0.00,
      "source": "GL Movement"
    }
  ],
  "glBalance": 170000.00,
  "difference": 0.00
}
```

### 3. Health Check

```bash
curl -X GET "http://localhost:8080/api/subproduct-gl-reconciliation/health"
```

**Response**:
```json
{
  "status": "UP",
  "service": "Sub-Product GL Reconciliation (EOD Step 8)"
}
```

## Excel Report Format

### Header Section
- **Title**: "SUB-PRODUCT GL BALANCE RECONCILIATION REPORT (EOD STEP 8)"
- **Report Date**: Selected business date
- **Professional Styling**: Bold headers, colored status indicators

### Data Section
- **Sortable**: Unmatched items shown first, then by difference amount
- **Color Coded**: 
  - Green: Matched status
  - Red: Unmatched status
- **Amount Formatting**: Currency format with thousand separators

### Summary Section
- **Total Accounts**: Count across all sub-products
- **Total Balances**: Sum of account and GL balances
- **Total Difference**: Net difference
- **Status Summary**: Count of matched vs unmatched

### Notes Section
Explains column meanings and calculation logic

## Integration with EOD Process

This report should be executed as **Step 8** in the End-of-Day process:

```java
// In EODService.java
public EODSummary runEODProcessing(LocalDate eodDate) {
    // ... previous steps ...
    
    // Step 8: Generate Sub-Product GL Reconciliation Report
    log.info("Step 8: Generating Sub-Product GL Reconciliation Report");
    byte[] reconciliationReport = subProductGLReconciliationService
        .generateReconciliationReport(eodDate);
    
    // Save report to file system or document management system
    saveReportToFileSystem(reconciliationReport, eodDate);
    
    // ... continue with remaining steps ...
}
```

## Testing

### Unit Testing

```java
@Test
public void testReconciliationWithMatchedBalances() {
    // Given: Sub-product with matching account and GL balances
    LocalDate reportDate = LocalDate.of(2024, 1, 15);
    
    // When: Generate reconciliation report
    byte[] report = reconciliationService.generateReconciliationReport(reportDate);
    
    // Then: Report should be generated and show matched status
    assertNotNull(report);
    assertTrue(report.length > 0);
}

@Test
public void testDrillDownWithFCYAccounts() {
    // Given: Sub-product with USD and EUR accounts
    String subProductCode = "SP001";
    LocalDate reportDate = LocalDate.of(2024, 1, 15);
    
    // When: Get drill-down details
    SubProductDrillDownDetails details = reconciliationService
        .getDrillDownDetails(subProductCode, reportDate);
    
    // Then: Should show FCY breakdown
    assertTrue(details.isFcyAccountsExist());
    assertNotNull(details.getFcyBalances());
}
```

### Manual Testing

1. **Access Frontend**:
   ```
   http://localhost:8080/subproduct-gl-reconciliation.html
   ```

2. **Generate Report**:
   - Select date
   - Click "Generate Report"
   - Verify Excel download

3. **Test Drill-Down**:
   - Click "Test Drill-Down"
   - Enter sub-product code
   - Verify account and GL details display

## Error Handling

### Service Layer
- **BusinessException**: For business logic errors (e.g., sub-product not found)
- **Logging**: Comprehensive error logging with stack traces
- **Default Values**: Graceful handling of missing data (default to zero)

### Controller Layer
- **HTTP 500**: Internal server errors with descriptive messages
- **HTTP 200**: Successful operations
- **Error Response Format**:
  ```json
  {
    "error": "Internal Server Error",
    "message": "Detailed error description"
  }
  ```

### Frontend
- **Visual Feedback**: Color-coded status messages
- **User-Friendly Errors**: Clear error descriptions
- **Loading States**: Spinner during API calls

## Performance Considerations

### Optimization Strategies
1. **Batch Loading**: Fetch all active sub-products at once
2. **Join Fetch**: Eager load related entities when needed
3. **Read-Only Transactions**: Use `@Transactional(readOnly = true)` for queries
4. **Indexed Queries**: Database indexes on frequently queried fields
5. **Lazy Loading**: Load related entities only when needed

### Scalability
- **Report Generation**: Handles hundreds of sub-products efficiently
- **Memory Management**: Uses streaming for large Excel files
- **Connection Pooling**: Database connection reuse

## Security Considerations

### Authentication (To Be Implemented)
- Add Spring Security authentication to endpoints
- Role-based access control (RBAC)
- Audit logging of report generation

### Data Access
- Read-only transactions prevent data modification
- Parameterized queries prevent SQL injection
- CORS configuration for frontend access

## Maintenance

### Logging
- **INFO**: Report generation start/end, counts
- **ERROR**: Exceptions with full stack traces
- **DEBUG**: (if enabled) Detailed processing steps

### Monitoring
- Health check endpoint for service monitoring
- Log analysis for performance issues
- Exception tracking for error patterns

## Future Enhancements

1. **Scheduled Reports**: Automatic generation post-EOD
2. **Email Distribution**: Send reports to stakeholders
3. **Dashboard Integration**: Real-time reconciliation status
4. **Historical Comparison**: Compare across multiple dates
5. **Exception Management**: Workflow for resolving unmatched items
6. **PDF Export**: Alternative to Excel format
7. **Advanced Filtering**: Filter by sub-product type, GL range, etc.
8. **Bulk Drill-Down**: Export all drill-down details at once

## Troubleshooting

### Common Issues

1. **Report Generation Fails**
   - Check if report date has account and GL balances
   - Verify database connectivity
   - Check logs for specific errors

2. **Drill-Down Returns No Data**
   - Verify sub-product code exists
   - Check if accounts exist for the sub-product
   - Ensure date has balance records

3. **FCY Not Showing**
   - Verify account currency is not BDT
   - Check if currency is properly set in account balance

4. **Frontend Cannot Connect**
   - Verify backend is running on port 8080
   - Check CORS configuration
   - Inspect browser console for errors

## Support

For issues or questions:
1. Check application logs in `logs/` directory
2. Review this documentation
3. Contact development team

## License

Internal CBS3 Banking System Component
