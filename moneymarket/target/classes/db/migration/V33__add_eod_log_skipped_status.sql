-- Add 'Skipped' to EOD_Log_Table.Status enum for Step 7 MCT Revaluation bypass
ALTER TABLE EOD_Log_Table
  MODIFY COLUMN Status ENUM('Running', 'Success', 'Failed', 'Skipped') NOT NULL DEFAULT 'Running';
