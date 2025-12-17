-- ============================================================================
-- Script: Insert Value Dating Parameters
-- Purpose: Insert required parameters for value dating functionality
-- Database: moneymarketdb
-- Date: 2025-11-09
-- ============================================================================

USE moneymarketdb;

-- Insert value dating parameters
-- NOTE: These use INSERT IGNORE to prevent duplicates if parameters already exist

INSERT IGNORE INTO Parameter_Table
(Parameter_Name, Parameter_Value, Parameter_Description, Last_Updated, Updated_By)
VALUES
('Past_Value_Date_Limit_Days', '90', 'Maximum days in past for value dating', NOW(), 'SYSTEM');

INSERT IGNORE INTO Parameter_Table
(Parameter_Name, Parameter_Value, Parameter_Description, Last_Updated, Updated_By)
VALUES
('Future_Value_Date_Limit_Days', '30', 'Maximum days in future for value dating', NOW(), 'SYSTEM');

INSERT IGNORE INTO Parameter_Table
(Parameter_Name, Parameter_Value, Parameter_Description, Last_Updated, Updated_By)
VALUES
('Interest_Default_Divisor', '36500', 'Divisor for interest calculation (365 * 100)', NOW(), 'SYSTEM');

INSERT IGNORE INTO Parameter_Table
(Parameter_Name, Parameter_Value, Parameter_Description, Last_Updated, Updated_By)
VALUES
('Last_EOM_Date', '2024-12-31', 'Last End of Month date for interest calculations', NOW(), 'SYSTEM');

-- Verify parameter insertion
SELECT 'Value dating parameters inserted successfully!' AS Status;

SELECT
    Parameter_Name,
    Parameter_Value,
    Parameter_Description,
    Last_Updated,
    Updated_By
FROM Parameter_Table
WHERE Parameter_Name IN (
    'Past_Value_Date_Limit_Days',
    'Future_Value_Date_Limit_Days',
    'Interest_Default_Divisor',
    'Last_EOM_Date'
)
ORDER BY Parameter_Name;
