# Acct_Bal_LCY Quick Start Guide

## What is Acct_Bal_LCY?

A mirror table of `Acct_Bal` that stores ALL account balances in **BDT (Local Currency)**, regardless of the original account currency.

## Quick Reference

| Account Type | acct_bal | acct_bal_lcy |
|-------------|----------|--------------|
| BDT Account | Values in BDT | Same values (identical) |
| USD Account | Values in USD | Converted to BDT using exchange rate |
| Other FCY | Values in FCY | Converted to BDT using exchange rate |

## Installation

### 1. Migrations (Automatic)
The migrations will run automatically when you start the application:
- `V27__create_acct_bal_lcy_table.sql` - Creates table
- `V28__test_acct_bal_lcy_data.sql` - Info script

### 2. Verify Installation
```sql
-- Check if table exists
SHOW TABLES LIKE 'Acct_Bal_LCY';

-- Check structure
DESCRIBE Acct_Bal_LCY;
```

## Usage

### Automatic Population (EOD)
The table is populated **automatically** during EOD Batch Job 1:
```
EOD Process → Batch Job 1 → AccountBalanceUpdateService
    → Populates acct_bal (original currency)
    → Populates acct_bal_lcy (BDT)
```

**No manual intervention required!**

### Query Examples

**Get latest balance for an account (in BDT):**
```sql
SELECT * FROM Acct_Bal_LCY
WHERE Account_No = '200000023003'
ORDER BY Tran_Date DESC
LIMIT 1;
```

**Compare original vs BDT:**
```sql
SELECT 
    ab.Account_No,
    ab.Account_Ccy,
    ab.Closing_Bal AS Original_Amount,
    abl.Closing_Bal_lcy AS BDT_Amount
FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Tran_Date = CURDATE();
```

**Total balances in BDT:**
```sql
SELECT SUM(Closing_Bal_lcy) AS Total_BDT
FROM Acct_Bal_LCY
WHERE Tran_Date = CURDATE();
```

## Verification

Run the verification script:
```bash
mysql -u root -p < ACCT_BAL_LCY_VERIFICATION.sql
```

Or quick check:
```sql
-- Count records
SELECT COUNT(*) FROM Acct_Bal_LCY;

-- Check latest date
SELECT MAX(Tran_Date) FROM Acct_Bal_LCY;

-- Verify BDT accounts match
SELECT COUNT(*) FROM Acct_Bal ab
JOIN Acct_Bal_LCY abl ON ab.Account_No = abl.Account_No 
    AND ab.Tran_Date = abl.Tran_Date
WHERE ab.Account_Ccy = 'BDT'
    AND ABS(ab.Closing_Bal - abl.Closing_Bal_lcy) > 0.01;
-- Should return 0
```

## Conversion Formula

```
BDT Accounts:
  Closing_Bal_lcy = Closing_Bal (no conversion)

USD/FCY Accounts:
  Closing_Bal_lcy = Closing_Bal × Exchange_Rate
  
Example (USD at rate 111.5):
  100.00 USD → 11,150.00 BDT
  200.00 USD → 22,300.00 BDT
```

## Troubleshooting

**Problem**: Table not created  
**Solution**: Check Flyway migrations
```sql
SELECT * FROM flyway_schema_history 
WHERE script LIKE '%acct_bal_lcy%';
```

**Problem**: No data after EOD  
**Solution**: Check logs
```bash
grep "Batch Job 1" logs/application.log
```

**Problem**: Amounts don't match for BDT accounts  
**Solution**: Re-run EOD for that date
```sql
-- Contact dev team to re-run batch job
```

## Key Points

✓ Automatically populated during EOD  
✓ No manual data entry needed  
✓ Uses same exchange rates as transactions  
✓ All amounts in BDT for easy reporting  
✓ Historical data preserved  

## Files

| File | Purpose |
|------|---------|
| `ACCT_BAL_LCY_QUICK_START.md` | This guide |
| `ACCT_BAL_LCY_IMPLEMENTATION.md` | Detailed documentation |
| `ACCT_BAL_LCY_SUMMARY.md` | Implementation summary |
| `ACCT_BAL_LCY_VERIFICATION.sql` | Verification queries |

## Support

For detailed information, see:
- `ACCT_BAL_LCY_IMPLEMENTATION.md` - Full implementation guide
- `ACCT_BAL_LCY_VERIFICATION.sql` - Verification script
- Application logs - Check for errors during EOD

---

**Remember**: The table is populated automatically during EOD. You don't need to manually insert data!
