-- Clear failed status for EOD Step 7 (MCT Revaluation) so UI and next run show Success.
-- Run this if Step 7 is stuck as Failed and retry still shows Failed.
-- Table: EOD_Log_Table (Job_Name, Status, Error_Message, Failed_At_Step).

-- Option 1: Update only the most recent failed log for MCT Revaluation (today's date)
UPDATE EOD_Log_Table
SET Status = 'Success', Error_Message = NULL, Failed_At_Step = NULL
WHERE Job_Name = 'MCT Revaluation'
  AND Status = 'Failed'
  AND EOD_Date = CURDATE();

-- Option 2: If no rows updated, clear all failed MCT Revaluation logs (any date)
-- UPDATE EOD_Log_Table
-- SET Status = 'Success', Error_Message = NULL, Failed_At_Step = NULL
-- WHERE Job_Name = 'MCT Revaluation' AND Status = 'Failed';

-- Verify (MySQL): SELECT EOD_Log_Id, EOD_Date, Job_Name, Status, Error_Message FROM EOD_Log_Table WHERE Job_Name = 'MCT Revaluation' ORDER BY Start_Timestamp DESC LIMIT 5;
