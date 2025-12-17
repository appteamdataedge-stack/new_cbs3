-- V21: Update existing intt_accr_tran data with proper multi-currency values
-- Date: 2025-11-20
-- Description: Updates FCY_Amt, Exchange_Rate, and LCY_Amt based on transaction currency

-- =====================================================
-- STEP 1: Update USD transactions in intt_accr_tran
-- =====================================================
-- For USD transactions, fetch exchange rate from fx_rate_master and recalculate amounts

UPDATE intt_accr_tran iat
LEFT JOIN (
    SELECT
        ccy_pair,
        mid_rate,
        rate_date,
        ROW_NUMBER() OVER (PARTITION BY ccy_pair ORDER BY rate_date DESC) as rn
    FROM fx_rate_master
    WHERE ccy_pair = 'USD/BDT'
) fx ON fx.rn = 1 AND iat.Tran_Date >= fx.rate_date
SET
    -- For USD transactions:
    -- FCY_Amt = Amount (the original interest amount in USD)
    -- Exchange_Rate = Mid_Rate from fx_rate_master
    -- LCY_Amt = FCY_Amt × Exchange_Rate
    iat.FCY_Amt = CASE
        WHEN iat.Tran_Ccy = 'USD' THEN iat.Amount
        ELSE iat.Amount
    END,
    iat.Exchange_Rate = CASE
        WHEN iat.Tran_Ccy = 'USD' AND fx.mid_rate IS NOT NULL THEN fx.mid_rate
        WHEN iat.Tran_Ccy = 'USD' THEN 120.50  -- Fallback rate if no FX rate found
        ELSE 1.0000
    END,
    iat.LCY_Amt = CASE
        WHEN iat.Tran_Ccy = 'USD' AND fx.mid_rate IS NOT NULL THEN ROUND(iat.Amount * fx.mid_rate, 2)
        WHEN iat.Tran_Ccy = 'USD' THEN ROUND(iat.Amount * 120.50, 2)
        ELSE iat.Amount
    END
WHERE iat.Tran_Ccy IS NOT NULL;

-- =====================================================
-- STEP 2: Ensure BDT transactions have correct values
-- =====================================================
-- For BDT transactions, FCY_Amt = LCY_Amt = Amount, Exchange_Rate = 1

UPDATE intt_accr_tran
SET
    FCY_Amt = Amount,
    Exchange_Rate = 1.0000,
    LCY_Amt = Amount
WHERE Tran_Ccy = 'BDT' OR Tran_Ccy IS NULL;

-- =====================================================
-- STEP 3: Set default currency for NULL values
-- =====================================================

UPDATE intt_accr_tran
SET Tran_Ccy = 'BDT'
WHERE Tran_Ccy IS NULL;

-- Migration completed successfully
