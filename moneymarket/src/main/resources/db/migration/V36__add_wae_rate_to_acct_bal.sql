-- V36: Add WAE_Rate column to Acct_Bal
-- WAE (Weighted Average Exchange Rate) stored per account, updated at each FCY credit posting.
-- Formula: WAE = (prevWAE * prevFcyBalance + lcyCreditAmount) / (prevFcyBalance + fcyCreditAmount)
-- NULL for BDT accounts; NULL for new FCY accounts until first credit is posted.

ALTER TABLE Acct_Bal
    ADD COLUMN WAE_Rate DECIMAL(10,4) NULL DEFAULT NULL
    COMMENT 'Weighted Average Exchange Rate, updated on each FCY credit posting';
