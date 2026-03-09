# EOD Step 8: Consolidated Report with Account Balance Details

## Overview
This feature extends the existing EOD Step 8 Financial Reports to include detailed Account Balance Reports for each subproduct, while maintaining all three existing report sheets.

## What's New

### Consolidated Workbook Structure
The EOD Step 8 now generates a comprehensive Excel workbook with the following sheets:

1. **Trial Balance** (existing)
   - GL Code, GL Name, Opening Balance, DR Summation, CR Summation, Closing Balance
   - Summary totals with validation

2. **Balance Sheet** (existing)
   - Side-by-side layout: Liabilities (left) and Assets (right)
   - GL-wise balances with totals

3. **Subproduct GL Balance Report** (existing, enhanced)
   - Grouped by BDT and FCY accounts
   - GL-wise subtotals and grand totals
   - **NEW**: Hyperlinks on Subproduct Name linking to corresponding detail sheet

4. **Account Balance Reports** (NEW - one sheet per subproduct)
   - Detailed account-level balances for each subproduct
   - Shows: Subproduct Code, Subproduct Name, GL Number, GL Name, Account No., Account Name, FCY Balance, LCY Balance
   - Grouped by BDT and FCY accounts
   - GL-wise subtotals and grand totals
   - Difference calculation for FCY accounts (LCY Total - GL Balance)
   - "No Data Available" message if no accounts exist for that subproduct

## Implementation Details

### Set-Based SQL Queries
All database queries are **set-based** (no cursors or row-by-row loops):

```sql
-- Single CTE query fetches all account balances per subproduct
WITH accounts AS (
    SELECT account_no, account_name, sub_product_id, currency 
    FROM cust_acct_master
    UNION ALL
    SELECT account_no, account_name, sub_product_id, currency 
    FROM of_acct_master
),
balances AS (
    SELECT a.account_no, a.account_name, a.sub_product_id, a.currency,
           ab.acc_bal, ab.acc_bal_lcy,
           pm.sub_product_code, pm.sub_product_name,
           gm.gl_num, gm.gl_name
    FROM accounts a
    JOIN acc_bal ab ON a.account_no = ab.account_no AND ab.eod_date = :eodDate
    JOIN prod_master pm ON a.sub_product_id = pm.sub_product_id
    JOIN gl_master gm ON pm.gl_num = gm.gl_num
    WHERE pm.sub_product_id = :subProductId
)
SELECT * FROM balances ORDER BY gl_num, account_no;
```

### Java Implementation
- **Service**: `EODStep8ConsolidatedReportService.java`
- **Controller**: `EODStep8ConsolidatedReportController.java`
- **Test**: `EODStep8ConsolidatedReportServiceTest.java`

### Key Features
1. **Hyperlinks**: Subproduct names in Sheet 3 are clickable and navigate to the corresponding detail sheet
2. **Sheet Naming**: Subproduct names are sanitized and truncated to 31 characters (Excel limit)
3. **Auto-Generation**: Runs automatically as part of EOD Batch Job 8
4. **No Manual Trigger**: Fully integrated into existing EOD process
5. **Consistent Formatting**: All sheets use matching column widths, fonts, styles, and freeze panes

## Report Layout

### BDT Accounts Section
```
Subproduct Code | Subproduct Name | GL Number | GL Name | Account No. | Account Name | FCY Account Balance | LCY Account Balance
----------------+-----------------+-----------+---------+-------------+--------------+---------------------+--------------------
CM001           | Call Money...   | 110101001 | Call... | CM001001    | Test Account | N/A                 | 1,000,000.00
                |                 |           |         |             | GL Subtotal: |                     | 1,000,000.00
                |                 |           |         |             | BDT Grand Total: |                 | 1,000,000.00
```

### FCY Accounts Section
```
Subproduct Code | Subproduct Name | GL Number | GL Name | Account No. | Account Name | FCY Account Balance | LCY Account Balance
----------------+-----------------+-----------+---------+-------------+--------------+---------------------+--------------------
CM002           | Call Money USD  | 110101002 | Call... | CM002001    | USD Account  | 10,000.00           | 1,000,000.00
                |                 |           |         |             | GL Subtotal: | 10,000.00           | 1,000,000.00
                |                 |           |         |             | FCY Grand Total (LCY): |           | 1,000,000.00
                |                 |           |         |             | GL Balance:  |                     | 1,000,000.00
                |                 |           |         |             | Difference:  |                     | 0.00
```

## API Endpoints

### Generate Consolidated Report
```http
POST /api/eod-step8/generate-consolidated-report?eodDate=2024-03-15
```

**Response**: Excel file (`.xlsx`) as attachment

### Health Check
```http
GET /api/eod-step8/health
```

**Response**:
```json
{
  "status": "UP",
  "service": "EOD Step 8: Consolidated Financial Reports"
}
```

## Integration with EOD Process

The consolidated report is automatically generated during EOD Batch Job 8:

```java
// EODOrchestrationService.executeBatchJob8()
byte[] consolidatedReport = eodStep8ConsolidatedReportService.generateConsolidatedReport(systemDate);
```

## Testing

Run unit tests:
```bash
mvn test -Dtest=EODStep8ConsolidatedReportServiceTest
```

## Technical Specifications

- **Technology**: Apache POI (XSSFWorkbook)
- **Java Version**: 17
- **Spring Boot**: 3.x
- **Database**: Set-based queries (JPA)
- **Excel Format**: `.xlsx` (OOXML)

## Performance Considerations

1. **Set-Based Queries**: All data fetching is done in bulk using JPA repository methods
2. **In-Memory Processing**: Workbook is built in memory using ByteArrayOutputStream
3. **Efficient Grouping**: Java Streams used for grouping by GL and currency
4. **No Row-by-Row Loops**: Database queries fetch all required data in single calls

## Constraints & Validations

- ✅ No cursor-based or row-by-row SQL loops
- ✅ Sheet names truncated to 31 characters (Excel limit)
- ✅ Automatic creation even with zero accounts
- ✅ Hyperlinks use `HyperlinkType.DOCUMENT` (internal links)
- ✅ All 3 existing sheets remain unchanged
- ✅ Consistent styling across all sheets

## Troubleshooting

### Issue: Sheet Not Found
**Solution**: Check if subproduct name contains special characters. Sheet names sanitize `\ / : * ? [ ]` to `_`.

### Issue: Hyperlink Not Working
**Solution**: Verify sheet name truncation. Hyperlink target must match actual sheet name (max 31 chars).

### Issue: "No Data Available" Message
**Solution**: This is expected behavior when no accounts exist for a subproduct on the EOD date.

## Future Enhancements

- [ ] Add drill-down capability for account transactions
- [ ] Include transaction history in detail sheets
- [ ] Add configurable report filters
- [ ] Support for custom date ranges
- [ ] Email notification with report attachment

## Authors
- CBS3 Development Team
- EOD Module Enhancement Project

## Version History
- **v1.0.0** (2026-03-05): Initial release with Account Balance Report feature
