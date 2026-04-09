CREATE TABLE fx_position (
    Id BIGINT AUTO_INCREMENT PRIMARY KEY,
    Tran_Date DATE NOT NULL,
    Position_GL_Num VARCHAR(9) NOT NULL,
    Position_Ccy VARCHAR(3) NOT NULL,
    Opening_Bal DECIMAL(20,2) DEFAULT 0.00,
    DR_Summation DECIMAL(20,2) DEFAULT 0.00,
    CR_Summation DECIMAL(20,2) DEFAULT 0.00,
    Closing_Bal DECIMAL(20,2) DEFAULT 0.00,
    Last_Updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_position_date (Tran_Date, Position_GL_Num, Position_Ccy),
    INDEX idx_position_gl (Position_GL_Num),
    INDEX idx_position_date (Tran_Date),
    INDEX idx_position_ccy (Position_Ccy)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

