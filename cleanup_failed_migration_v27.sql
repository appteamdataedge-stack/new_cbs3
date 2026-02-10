-- cleanup_failed_migration_v27.sql
-- Script to clean up failed Flyway migration for V27

-- Step 1: Drop the Acct_Bal_LCY table if it exists (partially created)
DROP TABLE IF EXISTS Acct_Bal_LCY;

-- Step 2: Delete the failed migration entry from Flyway history
DELETE FROM flyway_schema_history WHERE version = '27';

-- Step 3: Verify cleanup
SELECT * FROM flyway_schema_history WHERE version = '27';
-- Should return 0 rows

-- Step 4: Check if table was dropped
SHOW TABLES LIKE 'Acct_Bal_LCY';
-- Should return empty result

-- Now you can restart the application and Flyway will re-run V27 migration
SELECT 'Cleanup complete. Restart application to run V27 migration again.' AS Status;
