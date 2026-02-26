-- V31: Make Tran_Table.Account_No nullable
-- Settlement gain/loss rows (FX Gain/Loss GLs) have no customer account;
-- only GL_Num is populated on those rows. Account_No must accept NULL.
--
-- MySQL allows NULL values in FK columns, so the existing FK constraint
-- on Account_No (referencing Cust_Acct_Master) is unaffected.

SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE Tran_Table
    MODIFY COLUMN Account_No VARCHAR(20) NULL;

SET FOREIGN_KEY_CHECKS = 1;
