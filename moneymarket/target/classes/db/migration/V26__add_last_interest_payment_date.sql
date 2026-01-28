-- Add last_interest_payment_date to Cust_Acct_Master table for Interest Capitalization feature
-- This field tracks when interest was last capitalized/posted to the account

ALTER TABLE Cust_Acct_Master
ADD COLUMN Last_Interest_Payment_Date DATE NULL COMMENT 'Date when interest was last capitalized to the account';
