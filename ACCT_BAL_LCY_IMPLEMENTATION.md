# Acct_Bal_LCY Implementation Guide

## Overview

The `Acct_Bal_LCY` table is a mirror of the `Acct_Bal` table but stores ALL account balances in **Local Currency (BDT)**, regardless of the original account currency.

## Purpose

- **Consolidated Reporting**: All balances in a single currency (BDT) for easy aggregation
- **Regulatory Compliance**: Most regulatory reports require amounts in local currency
- **Financial Analysis**: Enables comparing balances across different currency accounts
- **Audit Trail**: Maintains historical balance data in BDT for all accounts

## Table Structure

```sql
CREATE TABLE Acct_Bal_LCY (
  Tran_Date DATE NOT NULL,
  Account_No VARCHAR(13) NOT NULL,
  Opening_Bal_lcy DECIMAL(20, 2) DEFAULT 0.00,
  DR_Summation_lcy DECIMAL(20, 2) DEFAULT 0.00,
  CR_Summation_lcy DECIMAL(20, 2) DEFAULT 0.00,
  Closing_Bal_lcy DECIMAL(20, 2) DEFAULT 0.00,
  Available_Balance_lcy DECIMAL(20, 2) NOT NULL DEFAULT 0.00,
  Last_Updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (Tran_Date, Account_No),
  FOREIGN KEY (Account_No) REFERENCES Cust_Acct_Master(Account_No)
);
```

## Data Population Logic

### For BDT Accounts
- Values in `acct_bal_lcy` are **identical** to `acct_bal`
- No currency conversion needed
- Example:
  ```
  acct_bal:     Opening=1000.00 BDT, CR=500.00 BDT, Closing=1500.00 BDT
  acct_bal_lcy: Opening=1000.00 BDT, CR=500.00 BDT, Closing=1500.00 BDT
  ```

### For USD/FCY Accounts
- Values in `acct_bal_lcy` are **converted to BDT** using exchange rates
- Uses the same conversion logic as `tran_table.lcy_amt`
- Formula: `LCY_Amt = FCY_Amt × Exchange_Rate`
- Example (USD account at rate 111.5):
  ```
  acct_bal:     Opening=100.00 USD, CR=100.00 USD, Closing=200.00 USD
  acct_bal_lcy: Opening=11150.00 BDT, CR=11150.00 BDT, Closing=22300.00 BDT
  ```

## Exchange Rate Source

The conversion uses exchange rates from:
1. **Primary Source**: `tran_table.lcy_amt` values (already converted)
2. **Fallback**: `fx_rate_master` table via `ExchangeRateService`

The `AccountBalanceUpdateService` aggregates LCY amounts directly from transaction records:
- DR_Summation_lcy = SUM(tran_table.lcy_amt WHERE dr_cr_flag='D')
- CR_Summation_lcy = SUM(tran_table.lcy_amt WHERE dr_cr_flag='C')

## EOD Processing Integration

### Batch Job 1: Account Balance Update

The `AccountBalanceUpdateService` handles both tables:

```java
@Service
public class AccountBalanceUpdateService {
    
    public int executeAccountBalanceUpdate(LocalDate systemDate) {
        // For each account:
        // 1. Calculate balances in original currency → save to acct_bal
        // 2. Calculate balances in BDT → save to acct_bal_lcy
    }
}
```

**Process Flow:**
1. Get all customer accounts from `Cust_Acct_Master`
2. For each account:
   - **Step a**: Get Opening_Bal from previous day's Closing_Bal (both currencies)
   - **Step b**: Calculate DR_Summation from Tran_Table (both currencies)
   - **Step c**: Calculate CR_Summation from Tran_Table (both currencies)
   - **Step d**: Calculate Closing_Bal = Opening + CR - DR (both currencies)
   - **Step e**: Save to `acct_bal` (original currency)
   - **Step f**: Save to `acct_bal_lcy` (BDT)

### 3-Tier Fallback Logic

Both tables use the same opening balance logic:
- **Tier 1**: Previous day's Closing_Bal (systemDate - 1)
- **Tier 2**: Last transaction date (MAX(Tran_Date) < systemDate)
- **Tier 3**: New account (return 0)

## Implementation Files

### Database
- **Migration**: `V27__create_acct_bal_lcy_table.sql`
- **Test Data**: `V28__test_acct_bal_lcy_data.sql`

### Entities
- **Entity**: `AcctBalLcy.java`
- **ID Class**: `AcctBalLcyId.java`

### Repository
- **Repository**: `AcctBalLcyRepository.java`

### Service
- **Service**: `AccountBalanceUpdateService.java`

## Usage Examples

### Query BDT Account Balance (LCY)
```sql
SELECT * FROM Acct_Bal_LCY 
WHERE Account_No = '200000023001' 
AND Tran_Date = '2024-01-15';
```

### Query USD Account Balance (converted to BDT)
```sql
SELECT * FROM Acct_Bal_LCY 
WHERE Account_No = '200000023003' 
AND Tran_Date = '2024-01-15';
```

### Compare Original vs LCY Balances
```sql
SELECT 
    ab.Account_No,
    ab.Tran_Date,
    ab.Account_Ccy,
    ab.Closing_Bal AS Closing_Bal_Original,
    abl.Closing_Bal_lcy AS Closing_Bal_BDT
FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Tran_Date = '2024-01-15'
ORDER BY ab.Account_No;
```

### Aggregate Total Balances in BDT
```sql
SELECT 
    Tran_Date,
    SUM(Closing_Bal_lcy) AS Total_Closing_Balance_BDT,
    COUNT(*) AS Total_Accounts
FROM Acct_Bal_LCY
WHERE Tran_Date = '2024-01-15'
GROUP BY Tran_Date;
```

## Benefits

1. **Simplified Reporting**: All reports can query a single table for BDT amounts
2. **Performance**: Pre-calculated BDT amounts eliminate runtime conversions
3. **Consistency**: Uses the same exchange rates as transaction records
4. **Audit**: Historical balances preserved in both currencies
5. **Compliance**: Meets regulatory requirements for local currency reporting

## Verification Queries

### Verify Data Population
```sql
-- Check if acct_bal_lcy is populated for today
SELECT COUNT(*) FROM Acct_Bal_LCY WHERE Tran_Date = CURDATE();
```

### Verify Currency Conversion
```sql
-- For USD accounts, verify conversion
SELECT 
    ab.Account_No,
    ab.Account_Ccy,
    ab.Closing_Bal AS USD_Amount,
    abl.Closing_Bal_lcy AS BDT_Amount,
    ROUND(abl.Closing_Bal_lcy / ab.Closing_Bal, 4) AS Effective_Rate
FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_Ccy = 'USD' 
    AND ab.Closing_Bal > 0
    AND ab.Tran_Date = CURDATE();
```

### Verify BDT Accounts (should match exactly)
```sql
-- For BDT accounts, amounts should be identical
SELECT 
    ab.Account_No,
    ab.Account_Ccy,
    ab.Closing_Bal,
    abl.Closing_Bal_lcy,
    CASE 
        WHEN ab.Closing_Bal = abl.Closing_Bal_lcy THEN 'MATCH'
        ELSE 'MISMATCH'
    END AS Status
FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl 
    ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_Ccy = 'BDT'
    AND ab.Tran_Date = CURDATE();
```

## Troubleshooting

### Issue: acct_bal_lcy not populated after EOD
**Solution**: Check EOD logs for Batch Job 1 errors
```bash
# Check application logs
grep "Batch Job 1" application.log
```

### Issue: Incorrect conversion rates
**Solution**: Verify exchange rates in fx_rate_master and tran_table
```sql
-- Check exchange rates used in transactions
SELECT DISTINCT 
    Tran_Date,
    Tran_Ccy,
    Exchange_Rate
FROM Tran_Table
WHERE Tran_Date = CURDATE()
ORDER BY Tran_Ccy;
```

### Issue: Mismatch between acct_bal and acct_bal_lcy for BDT accounts
**Solution**: Re-run Batch Job 1 for the affected date
```java
// Via EOD controller or service
accountBalanceUpdateService.executeAccountBalanceUpdate(LocalDate.of(2024, 1, 15));
```

## Notes

- The table is populated **automatically** during EOD processing
- Manual population is **not recommended** - always use the service
- Historical data conversion can be done by re-running Batch Job 1 for specific dates
- The service includes retry logic with duplicate key handling
- Both tables use pessimistic locking to prevent concurrent update issues

## Contact

For questions or issues related to Acct_Bal_LCY implementation, contact the development team.
