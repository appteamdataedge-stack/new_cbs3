-- ========================================
-- Migration V10: Configure Missing GL Accounts for Interest Adjustments
-- ========================================
-- Purpose: Add GL account mappings for sub-products that were missing them
-- PTTP05: Value Date Transactions - Interest Adjustment Support
-- Date: 2025-11-10
-- ========================================

-- First, verify which products are missing GL configurations
SELECT
    'BEFORE UPDATE' AS Status,
    Sub_Product_Code,
    Sub_Product_Name,
    Interest_Receivable_Expenditure_GL_Num,
    Interest_Income_Payable_GL_Num
FROM Sub_Prod_Master
WHERE Interest_Receivable_Expenditure_GL_Num IS NULL
   OR Interest_Income_Payable_GL_Num IS NULL;

-- ========================================
-- Update CTLR1 (Cash Teller 1)
-- ========================================
-- Typically no interest, but configuration needed for system consistency
-- Using standard liability GL accounts

UPDATE Sub_Prod_Master
SET
    Interest_Receivable_Expenditure_GL_Num = '240101001',  -- Interest Expense GL
    Interest_Income_Payable_GL_Num = '130101001',          -- Accrued Interest Payable GL
    Effective_Interest_Rate = 0.00,                         -- No interest for cash
    Interest_Increment = 0.00
WHERE Sub_Product_Code = 'CTLR1'
  AND (Interest_Receivable_Expenditure_GL_Num IS NULL
       OR Interest_Income_Payable_GL_Num IS NULL);

-- ========================================
-- Update CAREG (Current Account Regular)
-- ========================================
-- Liability account (deposits) - customer account
-- Uses standard interest GL accounts

UPDATE Sub_Prod_Master
SET
    Interest_Receivable_Expenditure_GL_Num = '240101001',  -- Interest Expense GL (Dr for deposits)
    Interest_Income_Payable_GL_Num = '130101001',          -- Accrued Interest Payable GL (Cr for deposits)
    Effective_Interest_Rate = COALESCE(Effective_Interest_Rate, 2.00),  -- 2% default if not set
    Interest_Increment = COALESCE(Interest_Increment, 0.25)
WHERE Sub_Product_Code = 'CAREG'
  AND (Interest_Receivable_Expenditure_GL_Num IS NULL
       OR Interest_Income_Payable_GL_Num IS NULL);

-- ========================================
-- Update RCMIS (Miscellaneous Receivables)
-- ========================================
-- Asset account (receivables) - bank earns interest
-- Uses receivable interest GL accounts

UPDATE Sub_Prod_Master
SET
    Interest_Receivable_Expenditure_GL_Num = '140102001',  -- Accrued Interest Receivable GL (Dr for assets)
    Interest_Income_Payable_GL_Num = '240102001',          -- Interest Income GL (Cr for assets)
    Effective_Interest_Rate = COALESCE(Effective_Interest_Rate, 8.00),  -- 8% default for receivables
    Interest_Increment = COALESCE(Interest_Increment, 0.50)
WHERE Sub_Product_Code = 'RCMIS'
  AND (Interest_Receivable_Expenditure_GL_Num IS NULL
       OR Interest_Income_Payable_GL_Num IS NULL);

-- ========================================
-- Verification: Check all products now configured
-- ========================================

SELECT
    'AFTER UPDATE' AS Status,
    Sub_Product_Code,
    Sub_Product_Name,
    Interest_Receivable_Expenditure_GL_Num AS Dr_GL,
    Interest_Income_Payable_GL_Num AS Cr_GL,
    Effective_Interest_Rate AS Rate,
    CASE
        WHEN Interest_Receivable_Expenditure_GL_Num IS NULL
          OR Interest_Income_Payable_GL_Num IS NULL
        THEN '✗ STILL MISSING'
        ELSE '✓ CONFIGURED'
    END AS Config_Status
FROM Sub_Prod_Master
ORDER BY Sub_Product_Code;

-- ========================================
-- Verification: Count products by configuration status
-- ========================================

SELECT
    CASE
        WHEN Interest_Receivable_Expenditure_GL_Num IS NULL
          OR Interest_Income_Payable_GL_Num IS NULL
        THEN 'Missing GL Config'
        ELSE 'Properly Configured'
    END AS Status,
    COUNT(*) AS Product_Count
FROM Sub_Prod_Master
GROUP BY
    CASE
        WHEN Interest_Receivable_Expenditure_GL_Num IS NULL
          OR Interest_Income_Payable_GL_Num IS NULL
        THEN 'Missing GL Config'
        ELSE 'Properly Configured'
    END;

-- Expected output: All products should be "Properly Configured"

-- ========================================
-- Log the migration completion
-- ========================================

SELECT 'Migration V10 completed successfully' AS Status,
       NOW() AS Completion_Time;
