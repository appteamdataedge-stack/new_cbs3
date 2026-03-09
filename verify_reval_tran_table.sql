-- =============================================================================
-- RevalTran Table Verification Script
-- Purpose: Verify reval_tran table structure and diagnose "null id" issues
-- =============================================================================

-- 1. CHECK TABLE STRUCTURE
-- Expected: Reval_Id should be BIGINT, NOT NULL, AUTO_INCREMENT, PRIMARY KEY
DESCRIBE reval_tran;

-- 2. SHOW FULL TABLE CREATION DDL
-- Verify AUTO_INCREMENT is set correctly
SHOW CREATE TABLE reval_tran;

-- 3. CHECK CURRENT AUTO_INCREMENT VALUE
-- Should show next ID to be used
SELECT AUTO_INCREMENT 
FROM information_schema.TABLES 
WHERE TABLE_SCHEMA = DATABASE() 
AND TABLE_NAME = 'reval_tran';

-- 4. CHECK FOR ANY NULL IDs (Should return 0 rows)
SELECT COUNT(*) AS null_id_count
FROM reval_tran 
WHERE Reval_Id IS NULL;

-- 5. CHECK FOR ANY RECORDS WITH INVALID DATA
-- All NOT NULL columns should be populated
SELECT 
    COUNT(*) AS total_records,
    SUM(CASE WHEN Reval_Date IS NULL THEN 1 ELSE 0 END) AS null_reval_date,
    SUM(CASE WHEN Acct_Num IS NULL THEN 1 ELSE 0 END) AS null_acct_num,
    SUM(CASE WHEN Ccy_Code IS NULL THEN 1 ELSE 0 END) AS null_ccy_code,
    SUM(CASE WHEN Created_On IS NULL THEN 1 ELSE 0 END) AS null_created_on,
    SUM(CASE WHEN Status IS NULL THEN 1 ELSE 0 END) AS null_status
FROM reval_tran;

-- 6. VIEW RECENT REVALUATION RECORDS
-- Check if records are being created correctly
SELECT 
    Reval_Id,
    Reval_Date,
    Acct_Num,
    Ccy_Code,
    Reval_Diff,
    Status,
    Created_On
FROM reval_tran
ORDER BY Created_On DESC
LIMIT 10;

-- 7. CHECK REVALUATION STATUS SUMMARY
SELECT 
    Reval_Date,
    Status,
    COUNT(*) AS record_count,
    SUM(Reval_Diff) AS total_reval_diff
FROM reval_tran
GROUP BY Reval_Date, Status
ORDER BY Reval_Date DESC, Status;

-- =============================================================================
-- FIX SCRIPT (Run only if table structure is incorrect)
-- =============================================================================

-- If Reval_Id is not AUTO_INCREMENT, run this:
-- ALTER TABLE reval_tran MODIFY Reval_Id BIGINT NOT NULL AUTO_INCREMENT;

-- If AUTO_INCREMENT counter needs reset (be careful!):
-- SET @max_id = (SELECT IFNULL(MAX(Reval_Id), 0) FROM reval_tran);
-- SET @sql = CONCAT('ALTER TABLE reval_tran AUTO_INCREMENT = ', @max_id + 1);
-- PREPARE stmt FROM @sql;
-- EXECUTE stmt;
-- DEALLOCATE PREPARE stmt;

-- =============================================================================
-- DIAGNOSTIC QUERIES FOR RELATED TABLES
-- =============================================================================

-- Check if FX rates exist for revaluation
SELECT 
    Ccy_Pair,
    Rate_Date,
    Mid_Rate,
    Buy_Rate,
    Sell_Rate
FROM fx_rate_master
WHERE Ccy_Pair IN ('USD/BDT', 'EUR/BDT', 'GBP/BDT', 'JPY/BDT')
ORDER BY Ccy_Pair, Rate_Date DESC;

-- Check FCY GL accounts with WAE Master data
SELECT 
    w.Ccy_Pair,
    w.FCY_Balance,
    w.LCY_Balance,
    w.WAE_Rate,
    w.Last_Updated
FROM wae_master w
WHERE w.Ccy_Pair IN ('USD/BDT', 'EUR/BDT', 'GBP/BDT', 'JPY/BDT');

-- Check FCY customer accounts
SELECT 
    cam.Account_No,
    cam.Account_Ccy,
    cam.Account_Status,
    ab.Current_Balance
FROM cust_acct_master cam
LEFT JOIN acct_bal ab ON cam.Account_No = ab.Account_No
WHERE cam.Account_Ccy != 'BDT'
AND cam.Account_Status = 'Active'
LIMIT 10;

-- =============================================================================
-- TEST DATA SETUP (If needed for testing)
-- =============================================================================

-- Insert test FX rates if missing
-- INSERT INTO fx_rate_master (Ccy_Pair, Rate_Date, Mid_Rate, Buy_Rate, Sell_Rate, Input_User, Input_Date)
-- VALUES 
--     ('USD/BDT', CURDATE(), 110.5000, 109.5000, 111.5000, 'SYSTEM', NOW()),
--     ('EUR/BDT', CURDATE(), 125.0000, 124.0000, 126.0000, 'SYSTEM', NOW()),
--     ('GBP/BDT', CURDATE(), 145.0000, 144.0000, 146.0000, 'SYSTEM', NOW()),
--     ('JPY/BDT', CURDATE(), 0.8500, 0.8400, 0.8600, 'SYSTEM', NOW());

-- =============================================================================
-- CLEANUP (Use with caution!)
-- =============================================================================

-- Delete test revaluation records (if needed for re-testing)
-- DELETE FROM reval_tran WHERE Reval_Date = CURDATE();

-- Reset reversal status for re-testing BOD
-- UPDATE reval_tran 
-- SET Status = 'POSTED', Reversal_Tran_Id = NULL, Reversed_On = NULL
-- WHERE Reval_Date = CURDATE() - INTERVAL 1 DAY;
