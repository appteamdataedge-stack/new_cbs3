-- Transaction list page performance: indexes on Tran_Table for commonly-filtered columns.
-- PK on Tran_Id already exists; V5 already defines idx_tran_table_date and idx_tran_table_status.
-- InnoDB indexes FK column Account_No (index name "Account_No"); idx_tran_table_account_no duplicates that column.
-- This server build does not support CREATE INDEX IF NOT EXISTS / DROP INDEX IF EXISTS; use conditional SQL instead.

SET @drop_sql = (
  SELECT IF(COUNT(*) > 0,
    'ALTER TABLE Tran_Table DROP INDEX idx_tran_table_account_no',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND LOWER(table_name) = 'tran_table'
    AND index_name = 'idx_tran_table_account_no'
);
PREPARE stmt FROM @drop_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_tran_table_tran_date ON Tran_Table(Tran_Date DESC)',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND LOWER(table_name) = 'tran_table'
    AND index_name = 'idx_tran_table_tran_date'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_tran_table_date_status ON Tran_Table(Tran_Date, Tran_Status)',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND LOWER(table_name) = 'tran_table'
    AND index_name = 'idx_tran_table_date_status'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_tran_table_gl_num ON Tran_Table(GL_Num)',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND LOWER(table_name) = 'tran_table'
    AND index_name = 'idx_tran_table_gl_num'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
