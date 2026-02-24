-- V30: Add GL_Num column to Tran_Table and backfill from account masters
-- GL_Num stores the posting GL at transaction-creation time so EOD Step 4
-- can use it directly without joining account-master tables at run time.
-- Fallback to master-table lookup is retained in GLMovementUpdateService
-- for any rows where GL_Num is still NULL after this migration.

SET FOREIGN_KEY_CHECKS = 0;

-- ── Step 1: Add nullable GL_Num column ──────────────────────────────────────
ALTER TABLE Tran_Table
    ADD COLUMN GL_Num VARCHAR(20) NULL AFTER Pointing_Id;

-- ── Step 2: Backfill – customer accounts (via Sub_Prod_Master.Cum_GL_Num) ───
-- Mirrors the logic in GLMovementUpdateService.getGLNumberForAccount (customer path).
UPDATE Tran_Table tt
    JOIN Cust_Acct_Master cam ON tt.Account_No = cam.Account_No
    JOIN Sub_Prod_Master spm  ON cam.Sub_Product_Id = spm.Sub_Product_Id
SET tt.GL_Num = spm.Cum_GL_Num
WHERE tt.GL_Num IS NULL;

-- ── Step 3: Backfill – office accounts (via Sub_Prod_Master.Cum_GL_Num) ─────
-- Mirrors the logic in GLMovementUpdateService.getGLNumberForAccount (office path).
UPDATE Tran_Table tt
    JOIN OF_Acct_Master oam ON tt.Account_No = oam.Account_No
    JOIN Sub_Prod_Master spm ON oam.Sub_Product_Id = spm.Sub_Product_Id
SET tt.GL_Num = spm.Cum_GL_Num
WHERE tt.GL_Num IS NULL;

-- ── Step 4: Backfill – GL-only lines (Account_No is itself a GL number) ─────
-- Handles FX Gain/Loss (140203002, 240203002), Position GL (920101001), etc.
UPDATE Tran_Table tt
    JOIN GL_setup gs ON tt.Account_No = gs.GL_Num
SET tt.GL_Num = tt.Account_No
WHERE tt.GL_Num IS NULL;

SET FOREIGN_KEY_CHECKS = 1;
