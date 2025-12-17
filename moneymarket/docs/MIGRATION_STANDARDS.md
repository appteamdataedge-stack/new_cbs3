# Database Migration Standards

## Overview
This document defines standards for database migrations in the Money Market Core Banking System.

## File Naming Convention

All Flyway migration files **MUST** follow this pattern:

```
V{VERSION}__{description_in_lowercase}.sql
```

### Naming Rules

1. ✅ Start with capital `V`
2. ✅ Version number (integer, sequential, no gaps)
3. ✅ Double underscore `__` separator
4. ✅ Description in **lowercase** with underscores (not hyphens)
5. ✅ File extension `.sql`

### Examples

**✅ CORRECT:**
```
V1__create_tables.sql
V2__add_customer_index.sql
V10__configure_gl_accounts.sql
V11__create_tran_value_date_log.sql
```

**❌ INCORRECT:**
```
V1__Create_Tables.sql           ❌ Capitals in description
V2__add-customer-index.sql      ❌ Hyphens instead of underscores
v3__update_data.sql             ❌ Lowercase 'v'
V04__add_column.sql             ❌ Leading zero in version
migration_001_add_table.sql     ❌ Wrong format entirely
```

## Version Numbering

### Sequential Numbering
- Versions must be sequential: V1, V2, V3, ... V10, V11, etc.
- **NO gaps** in version numbers
- **NO duplicate** version numbers
- **NO leading zeros** (use V10, not V010)

### Version Number Conflicts
If you discover duplicate version numbers:
1. Rename the newer migration to the next available version
2. Update any documentation referencing the old version
3. If already applied, update Flyway history table (if enabled)

**Example Fix:**
```bash
# If you have both V1__create_tables.sql and V1__Create_Tables.sql
# Rename the newer one:
mv V1__Create_Tables.sql V11__create_tran_value_date_log.sql
```

## Migration Content Standards

### 1. Header Comments

Every migration **MUST** include a header with:
- Purpose description
- PTTP reference (if applicable)
- Date created
- Author

**Template:**
```sql
-- ========================================
-- Migration V{N}: {Title}
-- ========================================
-- Purpose: {What this migration does}
-- PTTP: {PTTP number if applicable}
-- Date: YYYY-MM-DD
-- Author: {Name or SYSTEM}
-- ========================================
```

**Example:**
```sql
-- ========================================
-- Migration V9: Add 'Future' to Tran_Status Enum
-- ========================================
-- Purpose: Support future-dated transactions in Value Date feature
-- PTTP05: Value Dated Transactions
-- Date: 2025-11-10
-- Author: System Migration
-- ========================================
```

### 2. Verification Queries

Every migration **SHOULD** include verification queries at the end:

```sql
-- ========================================
-- Verification
-- ========================================

SELECT
    'Migration V{N} Verification' AS Check_Type,
    {your verification logic here},
    CASE
        WHEN {condition} THEN '✓ SUCCESS'
        ELSE '✗ FAILED'
    END AS Status
FROM {table};

-- Log completion
SELECT 'Migration V{N} completed successfully' AS Status,
       NOW() AS Completion_Time;
```

### 3. Safe Operations

**Use transactions where appropriate:**
```sql
START TRANSACTION;

-- Your DDL/DML statements here
ALTER TABLE...
UPDATE...

COMMIT;
```

**⚠️ Note:** Some DDL statements (like ALTER TABLE) cannot be rolled back in MySQL. Use caution.

### 4. Idempotent Operations

Where possible, make migrations idempotent (safe to run multiple times):

**✅ Good:**
```sql
CREATE TABLE IF NOT EXISTS my_table (...);

ALTER TABLE my_table
ADD COLUMN IF NOT EXISTS my_column VARCHAR(50);

INSERT INTO config (key, value)
VALUES ('setting', 'value')
ON DUPLICATE KEY UPDATE value = 'value';
```

**❌ Avoid:**
```sql
CREATE TABLE my_table (...);  -- Fails if table exists
ALTER TABLE my_table ADD COLUMN my_column VARCHAR(50);  -- Fails if column exists
INSERT INTO config VALUES ('setting', 'value');  -- Fails on duplicate key
```

### 5. Rollback Scripts

For critical migrations, include rollback instructions in comments:

```sql
-- ========================================
-- ROLLBACK INSTRUCTIONS (if needed)
-- ========================================
-- If this migration needs to be reverted, run:
--
-- ALTER TABLE my_table DROP COLUMN new_column;
-- DELETE FROM config WHERE key = 'new_setting';
--
-- ========================================
```

## Migration Checklist

Before committing a migration:

- [ ] File name follows V{N}__{lowercase_description}.sql pattern
- [ ] Version number is sequential (no gaps, no duplicates)
- [ ] Header comment included with Purpose, Date, Author
- [ ] Verification queries included at end
- [ ] Idempotent where possible (CREATE IF NOT EXISTS, etc.)
- [ ] Tested against development database
- [ ] Rollback instructions documented (if applicable)
- [ ] No hardcoded values (use parameters where appropriate)
- [ ] No destructive operations without safeguards

## Common Patterns

### Adding a Column
```sql
-- Add column with default value
ALTER TABLE my_table
ADD COLUMN IF NOT EXISTS new_column VARCHAR(100) DEFAULT 'default_value'
COMMENT 'Description of the column';

-- Verify
SELECT COUNT(*) AS Column_Exists
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'my_table'
AND COLUMN_NAME = 'new_column';
```

### Modifying an Enum
```sql
-- Expand enum values
ALTER TABLE my_table
MODIFY COLUMN status_column ENUM('Value1', 'Value2', 'NewValue3') NOT NULL
DEFAULT 'Value1'
COMMENT 'Updated description';

-- Verify
SHOW COLUMNS FROM my_table WHERE Field = 'status_column';
```

### Adding an Index
```sql
-- Create index if not exists (MySQL 5.7+)
CREATE INDEX idx_column_name
ON my_table(column_name)
COMMENT 'Purpose of this index';

-- Verify
SHOW INDEX FROM my_table WHERE Key_name = 'idx_column_name';
```

### Updating Configuration
```sql
-- Insert or update parameter
INSERT INTO parameter_table (Parameter_Name, Parameter_Value, Parameter_Description)
VALUES ('New_Parameter', '100', 'Description')
ON DUPLICATE KEY UPDATE
    Parameter_Value = '100',
    Parameter_Description = 'Description',
    Last_Updated = NOW();

-- Verify
SELECT * FROM parameter_table WHERE Parameter_Name = 'New_Parameter';
```

## Flyway Configuration

### Current Setup
```properties
# In application.properties:
spring.flyway.enabled=false          # Currently disabled
spring.flyway.baseline-on-migrate=true
spring.flyway.locations=classpath:db/migration
spring.flyway.schemas=moneymarketdb
```

### Manual Migration Execution
When Flyway is disabled (current state), run migrations manually:

```bash
# Execute a specific migration
mysql -u root -p moneymarketdb < src/main/resources/db/migration/V9__add_future_tran_status.sql

# Verify it worked
mysql -u root -p moneymarketdb -e "SELECT VERSION FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

### Enabling Flyway (Future)
To enable automatic migrations:

1. Update `application.properties`:
   ```properties
   spring.flyway.enabled=true
   ```

2. On next application startup, Flyway will:
   - Check `flyway_schema_history` table
   - Execute any pending migrations
   - Record results in history table

## Best Practices

### DO:
✅ Test migrations in development environment first
✅ Keep migrations small and focused (one logical change)
✅ Use descriptive names
✅ Include verification queries
✅ Document any manual steps required
✅ Review changes with team before committing
✅ Back up database before applying migrations in production

### DON'T:
❌ Modify or delete existing migration files
❌ Reuse version numbers
❌ Include environment-specific values
❌ Make migrations dependent on specific data existing
❌ Use DROP commands without safety checks
❌ Skip version numbers
❌ Use mixed case in file names

## Emergency Procedures

### If a Migration Fails
1. Check the error message in application logs
2. Review the migration SQL for syntax errors
3. Check if migration was partially applied
4. If needed, manually rollback partial changes
5. Fix the migration file
6. Re-run the corrected migration

### If Migration Applied Incorrectly
1. Create a **new** rollback migration (don't modify existing)
2. Name it with next version: `V{N+1}__rollback_{what}_changes.sql`
3. Include clear comments explaining the rollback
4. Test in development first
5. Apply to production

**Example:**
```sql
-- ========================================
-- Migration V14: Rollback V13 Changes
-- ========================================
-- Purpose: Rollback incorrect column addition from V13
-- Date: 2025-11-10
-- Author: DBA Team
-- ========================================

ALTER TABLE my_table DROP COLUMN incorrect_column;

SELECT 'Rollback V13 completed' AS Status;
```

## References

- [Flyway Documentation](https://flywaydb.org/documentation/)
- [MySQL ALTER TABLE Reference](https://dev.mysql.com/doc/refman/8.0/en/alter-table.html)
- [SQL Naming Conventions](https://www.sqlstyle.guide/)

---

**Document Version:** 1.0
**Last Updated:** 2025-11-10
**Maintained By:** Development Team
