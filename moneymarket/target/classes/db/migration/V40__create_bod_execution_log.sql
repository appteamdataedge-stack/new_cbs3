-- V40: BOD execution log table for tracking daily BOD runs per business date.
-- Used by transaction/deal-booking validation to determine if BOD has been
-- executed for the current system date before allowing new transactions.
CREATE TABLE BOD_EXECUTION_LOG (
    ID                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    EXECUTION_DATE       DATE           NOT NULL COMMENT 'Business date for which BOD was executed',
    EXECUTION_TIMESTAMP  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    STATUS               VARCHAR(20)    NOT NULL COMMENT 'SUCCESS, PARTIAL, FAILED',
    SCHEDULES_EXECUTED   INT            NOT NULL DEFAULT 0,
    SCHEDULES_FAILED     INT            NOT NULL DEFAULT 0,
    TRANSACTIONS_POSTED  INT            NOT NULL DEFAULT 0,
    EXECUTED_BY          VARCHAR(50)    NULL,
    ERROR_MESSAGE        TEXT           NULL,

    UNIQUE KEY uq_bod_date (EXECUTION_DATE),
    INDEX idx_status (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
