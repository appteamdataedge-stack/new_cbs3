-- Create Exchange Rate Master table for Multi-Currency Transaction support
-- Based on PTTP05 specification

CREATE TABLE fx_rate_master (
    Rate_Id BIGINT AUTO_INCREMENT PRIMARY KEY,
    Rate_Date DATE NOT NULL,
    Ccy_Pair VARCHAR(10) NOT NULL,
    Mid_Rate DECIMAL(10,4) NOT NULL,
    Buying_Rate DECIMAL(10,4) NOT NULL,
    Selling_Rate DECIMAL(10,4) NOT NULL,
    Source VARCHAR(20) NULL,
    Uploaded_By VARCHAR(20) NULL,
    Created_At TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Last_Updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT uq_fx_rate_date_pair UNIQUE (Rate_Date, Ccy_Pair),

    -- Indexes for performance
    INDEX idx_fx_rate_date (Rate_Date),
    INDEX idx_fx_ccy_pair (Ccy_Pair),
    INDEX idx_fx_rate_date_pair (Rate_Date, Ccy_Pair)
);

-- Insert sample data for testing
INSERT INTO fx_rate_master (Rate_Date, Ccy_Pair, Mid_Rate, Buying_Rate, Selling_Rate, Source, Uploaded_By) VALUES
('2025-03-20', 'USD/BDT', 124.3000, 123.6000, 125.0000, 'Bangladesh Bank', 'ADMIN'),
('2025-03-21', 'USD/BDT', 124.5000, 123.8000, 125.2000, 'Bangladesh Bank', 'ADMIN'),
('2025-03-21', 'EUR/BDT', 136.2000, 135.4000, 137.0000, 'Bangladesh Bank', 'ADMIN'),
('2025-03-21', 'GBP/BDT', 158.5000, 157.5000, 159.5000, 'Bangladesh Bank', 'ADMIN'),
('2025-03-21', 'JPY/BDT', 0.8350, 0.8300, 0.8400, 'Bangladesh Bank', 'ADMIN'),
('2025-03-22', 'USD/BDT', 124.6000, 123.9000, 125.3000, 'Bangladesh Bank', 'ADMIN'),
('2025-03-27', 'USD/BDT', 124.8000, 124.1000, 125.5000, 'Bangladesh Bank', 'ADMIN');
