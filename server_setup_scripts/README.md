# Server Database Setup for Value Dating Feature

## Overview
These SQL scripts set up the database components required for the Value Dating feature on the server.

## Why These Scripts Are Needed
Flyway migrations are disabled in the application (`spring.flyway.enabled=false`), so database changes must be applied manually.

## Files

1. **01_create_value_date_log_table.sql**
   - Creates the `Tran_Value_Date_Log` table
   - Adds indexes for optimal performance
   - Run this first

2. **02_insert_value_date_parameters.sql**
   - Inserts 4 required parameters into `Parameter_Table`
   - Uses `INSERT IGNORE` to prevent duplicate entries
   - Run this second

3. **03_verify_setup.sql**
   - Verification queries to confirm everything is set up correctly
   - Run this last to verify

## Database Connection Details

Based on `application.properties`:
- **Host**: 127.0.0.1 (or use environment variable `SPRING_DATASOURCE_URL`)
- **Port**: 3306
- **Database**: moneymarketdb
- **Username**: root (or use environment variable `SPRING_DATASOURCE_USERNAME`)
- **Password**: asif@yasir123 (or use environment variable `SPRING_DATASOURCE_PASSWORD`)

## How to Execute These Scripts

### Option 1: MySQL Command Line
```bash
# Navigate to the scripts directory
cd C:\cbs_prototype\cbs3\server_setup_scripts

# Execute each script in order
mysql -u root -p moneymarketdb < 01_create_value_date_log_table.sql
mysql -u root -p moneymarketdb < 02_insert_value_date_parameters.sql
mysql -u root -p moneymarketdb < 03_verify_setup.sql
```

### Option 2: MySQL Workbench
1. Open MySQL Workbench
2. Connect to your server database (moneymarketdb)
3. Open each SQL file in order
4. Execute the script using the "Execute" button (⚡ icon)
5. Verify results in the output panel

### Option 3: phpMyAdmin
1. Log in to phpMyAdmin
2. Select the `moneymarketdb` database
3. Go to the "SQL" tab
4. Copy and paste each script's content
5. Click "Go" to execute
6. Repeat for each script in order

### Option 4: DBeaver / DataGrip / Other Database Client
1. Connect to your server database
2. Open a SQL editor/console
3. Execute each script in order
4. Verify results

## Execution Order

⚠️ **IMPORTANT**: Execute scripts in this exact order:

1. `01_create_value_date_log_table.sql` - Creates the table
2. `02_insert_value_date_parameters.sql` - Inserts parameters
3. `03_verify_setup.sql` - Verifies everything is correct

## What Gets Created

### Table: Tran_Value_Date_Log

| Column | Type | Description |
|--------|------|-------------|
| Log_Id | BIGINT AUTO_INCREMENT | Primary key |
| Tran_Id | VARCHAR(20) | Transaction ID reference |
| Value_Date | DATE | Value date of transaction |
| Days_Difference | INT | Days between value date and tran date |
| Delta_Interest_Amt | DECIMAL(20,4) | Interest adjustment amount |
| Adjustment_Posted_Flag | VARCHAR(1) | 'Y' or 'N' |
| Created_Timestamp | DATETIME | When log entry was created |

**Indexes**:
- PRIMARY KEY on `Log_Id`
- INDEX on `Tran_Id`
- INDEX on `Value_Date`
- INDEX on `Adjustment_Posted_Flag`
- COMPOSITE INDEX on `(Value_Date, Adjustment_Posted_Flag)`

### Parameters Added to Parameter_Table

| Parameter Name | Value | Description |
|----------------|-------|-------------|
| Past_Value_Date_Limit_Days | 90 | Max days in past for value dating |
| Future_Value_Date_Limit_Days | 30 | Max days in future for value dating |
| Interest_Default_Divisor | 36500 | Divisor for interest calc (365 * 100) |
| Last_EOM_Date | 2024-12-31 | Last End of Month date |

## Verification

After running all scripts, the verification script (03_verify_setup.sql) should show:

✅ Table `Tran_Value_Date_Log` exists
✅ Table has 7 columns
✅ Table has 5 indexes (1 primary key + 4 indexes)
✅ Table has 0 records (initially)
✅ 4 parameters exist in Parameter_Table

## Troubleshooting

### Error: Table already exists
- If you see "Table 'Tran_Value_Date_Log' already exists", the table is already created
- You can skip script 01 or run the DROP TABLE command (commented in the script)

### Error: Duplicate entry in Parameter_Table
- Scripts use `INSERT IGNORE` to prevent duplicates
- This error should not occur, but if it does, parameters already exist
- Verify with: `SELECT * FROM Parameter_Table WHERE Parameter_Name LIKE '%Value_Date%'`

### Error: Access denied
- Verify your database credentials
- Ensure the user has CREATE and INSERT privileges
- Check if you can connect to the database

### Error: Unknown database 'moneymarketdb'
- The database doesn't exist
- Create it first: `CREATE DATABASE moneymarketdb;`
- Or check your database connection settings

## Post-Setup Testing

After setup, you can test with:

```sql
-- Insert a test log entry
INSERT INTO Tran_Value_Date_Log
(Tran_Id, Value_Date, Days_Difference, Delta_Interest_Amt, Adjustment_Posted_Flag)
VALUES
('TEST001', '2025-11-07', -2, 150.0000, 'N');

-- Verify the insert
SELECT * FROM Tran_Value_Date_Log WHERE Tran_Id = 'TEST001';

-- Clean up test data
DELETE FROM Tran_Value_Date_Log WHERE Tran_Id = 'TEST001';
```

## Next Steps

After running these scripts successfully:

1. ✅ Database setup is complete
2. 🚀 Deploy the backend application to the server
3. 🚀 Deploy the frontend application to the server
4. 🧪 Test the Value Dating feature end-to-end
5. 📊 Monitor the BOD (Beginning of Day) process

## Support

If you encounter any issues:
1. Check the verification script output
2. Review the MySQL error logs
3. Ensure all prerequisites are met
4. Verify database user permissions
