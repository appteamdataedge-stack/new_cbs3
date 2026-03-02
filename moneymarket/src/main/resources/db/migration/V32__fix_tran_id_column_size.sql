-- V32: Increase Tran_Id column size to VARCHAR(30)
-- Revaluation and other batch jobs generate Tran_Ids that must stay within limit.
-- Code now uses R/V + yyyyMMdd + 8-digit + suffix (18 chars); this allows headroom.

SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE Tran_Table
    MODIFY COLUMN Tran_Id VARCHAR(30) NOT NULL;

ALTER TABLE GL_Movement
    MODIFY COLUMN Tran_Id VARCHAR(30) NOT NULL;

SET FOREIGN_KEY_CHECKS = 1;
