-- Update FX Rate Master table to support timestamp and multiple rates per date
-- Remove unique constraint and change Rate_Date to DATETIME

-- Drop the existing unique constraint
ALTER TABLE fx_rate_master DROP INDEX uq_fx_rate_date_pair;

-- Modify Rate_Date column from DATE to DATETIME
ALTER TABLE fx_rate_master MODIFY COLUMN Rate_Date DATETIME NOT NULL;

-- Add new unique constraint on Rate_Date (with timestamp) and Ccy_Pair
ALTER TABLE fx_rate_master ADD CONSTRAINT uq_fx_rate_datetime_pair UNIQUE (Rate_Date, Ccy_Pair);

-- Update existing sample data with timestamps (spread across different times)
UPDATE fx_rate_master SET Rate_Date = '2025-03-20 09:00:00' WHERE Rate_Date = '2025-03-20' AND Ccy_Pair = 'USD/BDT';
UPDATE fx_rate_master SET Rate_Date = '2025-03-21 09:00:00' WHERE Rate_Date = '2025-03-21' AND Ccy_Pair = 'USD/BDT';
UPDATE fx_rate_master SET Rate_Date = '2025-03-21 10:00:00' WHERE Rate_Date = '2025-03-21' AND Ccy_Pair = 'EUR/BDT';
UPDATE fx_rate_master SET Rate_Date = '2025-03-21 11:00:00' WHERE Rate_Date = '2025-03-21' AND Ccy_Pair = 'GBP/BDT';
UPDATE fx_rate_master SET Rate_Date = '2025-03-21 12:00:00' WHERE Rate_Date = '2025-03-21' AND Ccy_Pair = 'JPY/BDT';
UPDATE fx_rate_master SET Rate_Date = '2025-03-22 09:00:00' WHERE Rate_Date = '2025-03-22' AND Ccy_Pair = 'USD/BDT';
UPDATE fx_rate_master SET Rate_Date = '2025-03-27 09:00:00' WHERE Rate_Date = '2025-03-27' AND Ccy_Pair = 'USD/BDT';
