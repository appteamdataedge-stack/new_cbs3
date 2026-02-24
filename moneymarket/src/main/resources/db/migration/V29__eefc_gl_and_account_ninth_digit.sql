-- V29: EEFC GL mapping and account number convention
-- Entire script wrapped in FOREIGN_KEY_CHECKS=0/1 to avoid MySQL Error 3780 (incompatible collation).
-- Order: (1) Fix Account_Ccy/Ccy_Code columns and currency/cust/OF tables first, (2) Then all others including Value_Date_Intt_Accr.

SET FOREIGN_KEY_CHECKS = 0;

-- ========== PART 0: Fix Account_Ccy / Ccy_Code mismatch (FK: currency_master -> Cust_Acct_Master, OF_Acct_Master) ==========
ALTER TABLE currency_master
    MODIFY COLUMN Ccy_Code VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;

ALTER TABLE Cust_Acct_Master
    MODIFY COLUMN Account_Ccy VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'BDT';

ALTER TABLE OF_Acct_Master
    MODIFY COLUMN Account_Ccy VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'BDT';

-- ========== PART 1: Database and table collation (utf8mb4_unicode_ci) ==========
ALTER DATABASE moneymarketdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Convert currency and account masters first (resolve Account_Ccy/Ccy_Code FK)
ALTER TABLE currency_master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Cust_Acct_Master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE OF_Acct_Master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Remaining tables (Value_Date_Intt_Accr has Account_No FK to Cust_Acct_Master — convert to avoid mismatch)
ALTER TABLE Cust_Master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Prod_Master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Sub_Prod_Master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Tran_Table CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE GL_setup CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Acct_Bal CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE GL_Balance CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE GL_Movement CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Intt_Accr_Tran CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE GL_Movement_Accrual CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Acct_Bal_Accrual CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Account_Seq CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Interest_Rate_Master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Parameter_Table CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE EOD_Log_Table CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Tran_Value_Date_Log CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Value_Date_Intt_Accr CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE fx_rate_master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE wae_master CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE reval_tran CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE settlement_gain_loss CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE reval_summary CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE Acct_Bal_LCY CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ========== PART 2: Fix existing EEFC/USD accounts (9th digit 9 -> 7) ==========
CREATE TEMPORARY TABLE IF NOT EXISTS _eefc_account_renumber (
    old_no VARCHAR(13) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY,
    new_no VARCHAR(13) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
);

INSERT INTO _eefc_account_renumber (old_no, new_no)
SELECT cam.Account_No,
       CONCAT(SUBSTRING(cam.Account_No, 1, 8), '7', SUBSTRING(cam.Account_No, 10)) AS new_no
FROM Cust_Acct_Master cam
JOIN Sub_Prod_Master sp ON cam.Sub_Product_Id = sp.Sub_Product_Id
JOIN Prod_Master p ON sp.Product_Id = p.Product_Id
WHERE SUBSTRING(cam.Account_No, 9, 1) = '9'
  AND p.Cum_GL_Num IN ('110103000', '110203000');

UPDATE Cust_Acct_Master cam
JOIN _eefc_account_renumber t ON cam.Account_No = t.old_no
SET cam.Account_No = t.new_no;

UPDATE Acct_Bal ab
JOIN _eefc_account_renumber t ON ab.Account_No = t.old_no
SET ab.Account_No = t.new_no;

UPDATE Tran_Table tt
JOIN _eefc_account_renumber t ON tt.Account_No = t.old_no
SET tt.Account_No = t.new_no;

UPDATE Intt_Accr_Tran iat
JOIN _eefc_account_renumber t ON iat.Account_No = t.old_no
SET iat.Account_No = t.new_no;

UPDATE Acct_Bal_LCY abl
JOIN _eefc_account_renumber t ON abl.Account_No = t.old_no
SET abl.Account_No = t.new_no;

UPDATE Value_Date_Intt_Accr vdia
JOIN _eefc_account_renumber t ON vdia.Account_No = t.old_no
SET vdia.Account_No = t.new_no;

DROP TEMPORARY TABLE IF EXISTS _eefc_account_renumber;

SET FOREIGN_KEY_CHECKS = 1;
