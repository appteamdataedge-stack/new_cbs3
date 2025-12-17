-- V17: Create WAE Master table for Weighted Average Exchange rate tracking
-- This table tracks the weighted average exchange rate for each foreign currency
-- Used for calculating settlement gain/loss when selling FCY at different rates

CREATE TABLE `wae_master` (
  `WAE_Id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `Ccy_Pair` VARCHAR(10) NOT NULL COMMENT 'Currency pair (e.g., USD/BDT, EUR/BDT)',
  `WAE_Rate` DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT 'Weighted average exchange rate',
  `FCY_Balance` DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT 'Net FCY balance (cumulative)',
  `LCY_Balance` DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT 'Equivalent LCY balance (cumulative)',
  `Updated_On` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `Source_GL` VARCHAR(20) NULL COMMENT 'Position GL account reference',
  UNIQUE KEY `unique_ccy_pair` (`Ccy_Pair`),
  INDEX `idx_ccy_pair` (`Ccy_Pair`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Weighted Average Exchange rates for settlement gain/loss calculation';

-- Initialize with supported currencies
-- Initial values are 0 until first FCY transaction is posted
INSERT INTO `wae_master` (Ccy_Pair, WAE_Rate, FCY_Balance, LCY_Balance, Source_GL)
VALUES
  ('USD/BDT', 0.0000, 0.00, 0.00, '920101001'),
  ('EUR/BDT', 0.0000, 0.00, 0.00, '920102001'),
  ('GBP/BDT', 0.0000, 0.00, 0.00, '920103001'),
  ('JPY/BDT', 0.0000, 0.00, 0.00, '920104001');
