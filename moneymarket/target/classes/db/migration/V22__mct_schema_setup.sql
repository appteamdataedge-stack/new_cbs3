-- V22: Multi-Currency Transaction (MCT) Schema Setup
-- Date: 2025-11-23
-- Description: Creates currency master, updates GL accounts, adds currency column to accounts

-- =====================================================
-- STEP 1: Create Currency Master Table
-- =====================================================

CREATE TABLE IF NOT EXISTS currency_master (
    Ccy_Code VARCHAR(3) PRIMARY KEY,
    Ccy_Name VARCHAR(50) NOT NULL,
    Ccy_Symbol VARCHAR(5),
    Is_Base_Ccy BOOLEAN DEFAULT FALSE,
    Decimal_Places INT DEFAULT 2,
    Is_Active BOOLEAN DEFAULT TRUE,
    Created_At TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    Updated_At TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- =====================================================
-- STEP 2: Insert Currency Master Data
-- =====================================================

INSERT INTO currency_master (Ccy_Code, Ccy_Name, Ccy_Symbol, Is_Base_Ccy, Decimal_Places, Is_Active) VALUES
('BDT', 'Bangladeshi Taka', '৳', TRUE, 2, TRUE),
('USD', 'US Dollar', '$', FALSE, 2, TRUE),
('EUR', 'Euro', '€', FALSE, 2, TRUE),
('GBP', 'British Pound', '£', FALSE, 2, TRUE),
('JPY', 'Japanese Yen', '¥', FALSE, 0, TRUE)
ON DUPLICATE KEY UPDATE
    Ccy_Name = VALUES(Ccy_Name),
    Ccy_Symbol = VALUES(Ccy_Symbol),
    Is_Base_Ccy = VALUES(Is_Base_Ccy);

-- =====================================================
-- STEP 3: Add Missing GL Accounts for Revaluation
-- =====================================================

-- Revaluation Adjustment GL hierarchy
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num) VALUES
('Reval Adjustment', 1, '50000000', '200000000', '250000000'),
('Reval Adj', 2, '0100000', '250000000', '250100000'),
('Reval Adj USD', 3, '01000', '250100000', '250101000'),
('RVUSD', 4, '001', '250101000', '250101001')
ON DUPLICATE KEY UPDATE
    GL_Name = GL_Name,
    Parent_GL_Num = Parent_GL_Num;

-- =====================================================
-- STEP 4: Add Account_Ccy Column to cust_acct_master
-- =====================================================

-- Check if column exists before adding
SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cust_acct_master'
    AND COLUMN_NAME = 'Account_Ccy'
);

SET @sql = IF(@column_exists = 0,
    'ALTER TABLE cust_acct_master ADD COLUMN Account_Ccy VARCHAR(3) DEFAULT ''BDT'' AFTER GL_Num',
    'SELECT ''Column Account_Ccy already exists'' AS message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Update existing USD account
UPDATE cust_acct_master
SET Account_Ccy = 'USD'
WHERE Account_No = '100000008001';

-- =====================================================
-- STEP 5: Add Foreign Key Constraint
-- =====================================================

SET @fk_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cust_acct_master'
    AND CONSTRAINT_NAME = 'fk_acct_ccy'
);

SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE cust_acct_master ADD CONSTRAINT fk_acct_ccy FOREIGN KEY (Account_Ccy) REFERENCES currency_master(Ccy_Code)',
    'SELECT ''Foreign key fk_acct_ccy already exists'' AS message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- =====================================================
-- STEP 6: Update WAE Master Source_GL References
-- =====================================================

UPDATE wae_master SET Source_GL = '920101001' WHERE Ccy_Pair = 'USD/BDT';
UPDATE wae_master SET Source_GL = '920102001' WHERE Ccy_Pair = 'EUR/BDT';
UPDATE wae_master SET Source_GL = '920103001' WHERE Ccy_Pair = 'GBP/BDT';
UPDATE wae_master SET Source_GL = '920104001' WHERE Ccy_Pair = 'JPY/BDT';

-- =====================================================
-- STEP 7: Ensure Position GL Accounts Exist
-- =====================================================

-- These should already exist from the dump, but ensuring they're configured correctly
INSERT INTO gl_setup (GL_Name, Layer_Id, Layer_GL_Num, Parent_GL_Num, GL_Num) VALUES
('Position USD', 4, '001', '920101000', '920101001'),
('Position EUR', 4, '001', '920102000', '920102001'),
('Position GBP', 4, '001', '920103000', '920103001'),
('Position JPY', 4, '001', '920104000', '920104001')
ON DUPLICATE KEY UPDATE
    GL_Name = GL_Name;

-- =====================================================
-- STEP 8: Add Indexes for Performance
-- =====================================================

-- Create indexes only if they don't exist
SET @index_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'currency_master' AND INDEX_NAME = 'idx_currency_master_active');
SET @sql = IF(@index_exists = 0,
    'CREATE INDEX idx_currency_master_active ON currency_master(Is_Active)',
    'SELECT ''Index idx_currency_master_active already exists'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cust_acct_master' AND INDEX_NAME = 'idx_cust_acct_ccy');
SET @sql = IF(@index_exists = 0,
    'CREATE INDEX idx_cust_acct_ccy ON cust_acct_master(Account_Ccy)',
    'SELECT ''Index idx_cust_acct_ccy already exists'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wae_master' AND INDEX_NAME = 'idx_wae_master_ccy_gl');
SET @sql = IF(@index_exists = 0,
    'CREATE INDEX idx_wae_master_ccy_gl ON wae_master(Ccy_Pair, Source_GL)',
    'SELECT ''Index idx_wae_master_ccy_gl already exists'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Migration completed successfully
