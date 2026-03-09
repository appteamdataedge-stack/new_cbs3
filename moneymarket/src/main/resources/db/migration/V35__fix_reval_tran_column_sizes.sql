-- V35: Fix reval_tran column sizes
-- Reval/Reversal TranIds use "R/V + yyyyMMdd + UUID8" format (18 chars base)
-- plus GL_Movement.Tran_Id reference also needs headroom.
-- Align with the VARCHAR(30) already applied to Tran_Table and GL_Movement in V32.

SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE reval_tran
    MODIFY COLUMN Tran_Id          VARCHAR(30) NULL    COMMENT 'Transaction ID in tran_table',
    MODIFY COLUMN Reversal_Tran_Id VARCHAR(30) NULL    COMMENT 'BOD reversal transaction ID',
    MODIFY COLUMN Reval_GL         VARCHAR(20) NULL    COMMENT 'Revaluation GL (NULL for baseline/skipped records)';

SET FOREIGN_KEY_CHECKS = 1;
