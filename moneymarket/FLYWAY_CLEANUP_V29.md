# Flyway cleanup and EEFC migration (V29)

Use this when:
- Undoing a **failed V29** run and applying the clean V29 migration.
- `flyway_schema_history` has a failed or inconsistent entry for version 29 (or 30).
- You need to run `flyway:repair` and then start the app so V29 applies (or is marked resolved).

## What V29 does

The single migration **V29__eefc_gl_and_account_ninth_digit.sql** runs with `FOREIGN_KEY_CHECKS = 0` for the whole script:

0. **Account_Ccy / Ccy_Code** – `currency_master.Ccy_Code`, `Cust_Acct_Master.Account_Ccy`, `OF_Acct_Master.Account_Ccy` set to `VARCHAR(3)` utf8mb4_unicode_ci.
1. **Collation** – Database and all application tables (including Value_Date_Intt_Accr) converted to utf8mb4_unicode_ci.
2. **Account number** – Existing EEFC/USD accounts with 9th digit `9` updated to `7` in all related tables.

---

## Quick path (recommended)

### Step 1 — Run Flyway repair (proper fix)

From the **moneymarket** directory (PowerShell):

```powershell
mvn flyway:repair -Dflyway.url="jdbc:mysql://127.0.0.1:3306/moneymarketdb" -Dflyway.user=root -Dflyway.password=asif@yasir123 -Dflyway.schemas=moneymarketdb
```

### Step 2 — Start the app

```powershell
mvn spring-boot:run
```

---

## If Flyway plugin not found

Run this in **MySQL Workbench** or mysql CLI to mark version 29 as succeeded and unblock Flyway:

```sql
UPDATE moneymarketdb.flyway_schema_history
SET success = 1
WHERE version = '29';
```

Then run:

```powershell
mvn spring-boot:run
```

**Warning:** Use this UPDATE only if you're sure V29 actually completed (e.g. only Flyway metadata failed). If the migration failed mid-way, use **repair** or **DELETE** (below) so V29 runs again from scratch.

---

## If it still fails after repair

The V29 script may have bad or partially-applied SQL. Check what ran:

```sql
SELECT * FROM moneymarketdb.flyway_schema_history WHERE version = '29';
```

Then share the result and the contents of `src/main/resources/db/migration/V29__eefc_gl_and_account_ninth_digit.sql` so the script can be fixed.

---

## Alternative: remove failed V29 and re-run

### Remove V29 from history (so Flyway will re-apply it)

```sql
USE moneymarketdb;
DELETE FROM flyway_schema_history WHERE version = '29';
```

Or one-liner:

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p moneymarketdb -e "DELETE FROM flyway_schema_history WHERE version IN ('29','30');"
```

### Then repair and start

```bash
cd moneymarket
mvn clean
mvn flyway:repair -Dflyway.url="jdbc:mysql://127.0.0.1:3306/moneymarketdb" -Dflyway.user=root "-Dflyway.password=asif@yasir123" -Dflyway.schemas=moneymarketdb
mvn spring-boot:run
```

---

## Validate

- **Flyway:** No migration errors; `flyway_schema_history` shows V29 with `success = 1`; no duplicate versions.
- **EEFC:** New EEFC accounts (GL 110103000) have 9th digit = 7; existing EEFC/USD with 9th digit 9 were updated to 7.
- **Collation:** No Error 3780; `Account_Ccy`/`Ccy_Code` and all tables use utf8mb4_unicode_ci.
