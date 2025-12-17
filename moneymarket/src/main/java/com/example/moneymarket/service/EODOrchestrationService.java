package com.example.moneymarket.service;

import com.example.moneymarket.entity.EODLogTable;
import com.example.moneymarket.entity.ParameterTable;
import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.repository.EODLogTableRepository;
import com.example.moneymarket.repository.ParameterTableRepository;
import com.example.moneymarket.repository.TranTableRepository;
import com.example.moneymarket.service.EODValidationService.EODValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Comprehensive EOD Orchestration Service
 * Implements all 9 batch jobs with proper transaction handling and logging
 * - Batch Job 7 (MCT Revaluation) added for foreign currency position revaluation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EODOrchestrationService {

    private final ParameterTableRepository parameterTableRepository;
    private final EODLogTableRepository eodLogTableRepository;
    private final TranTableRepository tranTableRepository;
    private final AccountBalanceUpdateService accountBalanceUpdateService;
    private final InterestAccrualService interestAccrualService;
    private final InterestAccrualGLMovementService interestAccrualGLMovementService;
    private final GLMovementUpdateService glMovementUpdateService;
    private final GLBalanceUpdateService glBalanceUpdateService;
    private final InterestAccrualAccountBalanceService interestAccrualAccountBalanceService;
    private final RevaluationService revaluationService;  // NEW: MCT Revaluation
    private final FinancialReportsService financialReportsService;
    private final EODValidationService eodValidationService;
    private final EODReportingService eodReportingService;
    private final SystemDateService systemDateService;

    @Value("${eod.admin.user:ADMIN}")
    private String eodAdminUser;

    @Value("${interest.default.divisor:36500}")
    private String interestDefaultDivisor;

    @Value("${currency.default:BDT}")
    private String currencyDefault;

    @Value("${exchange.rate.default:1.0}")
    private String exchangeRateDefault;

    /**
     * Execute complete EOD process with all 9 batch jobs
     * Job 7 (MCT Revaluation) added to handle foreign currency revaluation
     */
    @Transactional
    public EODResult executeEOD(String userId) {
        log.info("=== STARTING EOD PROCESS (9 Batch Jobs) ===");
        log.info("EOD initiated by user: {}", userId);
        
        // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
        LocalDate systemDate = systemDateService.getSystemDate();
        LocalDate eodDate = systemDate; // Use System_Date for EOD processing
        
        // Pre-EOD Validations
        log.info("Starting pre-EOD validations for system date: {}", systemDate);
        EODValidationResult validationResult = eodValidationService.performPreEODValidations(userId, systemDate);
        
        if (!validationResult.isValid()) {
            log.error("Pre-EOD validations failed: {}", validationResult.getErrorMessage());
            logEODJob(eodDate, "Pre-EOD Validation", systemDate, userId, 0, 
                     EODLogTable.EODStatus.Failed, validationResult.getErrorMessage(), "Pre-validation");
            return EODResult.failure("Pre-EOD validations failed: " + validationResult.getErrorMessage());
        }
        
        log.info("Pre-EOD validations passed successfully");
        
        try {
            // Batch Job 1: Account Balance Update
            int accountsProcessed = executeBatchJob1(eodDate, systemDate, userId);
            
            // Batch Job 2: Interest Accrual Transaction Update
            int interestEntriesProcessed = executeBatchJob2(eodDate, systemDate, userId);
            
            // Batch Job 3: Interest Accrual GL Movement Update
            int glMovementsProcessed = executeBatchJob3(eodDate, systemDate, userId);
            
            // Batch Job 4: GL Movement Update
            int glMovementsUpdated = executeBatchJob4(eodDate, systemDate, userId);
            
            // Batch Job 5: GL Balance Update
            int glBalancesUpdated = executeBatchJob5(eodDate, systemDate, userId);
            
            // Batch Job 6: Interest Accrual Account Balance Update
            int accrualBalancesUpdated = executeBatchJob6(eodDate, systemDate, userId);

            // Batch Job 7: MCT Revaluation (NEW)
            int revaluationEntriesProcessed = executeBatchJob7(eodDate, systemDate, userId);

            // Batch Job 8: Financial Reports Generation (previously Job 7)
            boolean reportsGenerated = executeBatchJob8(eodDate, systemDate, userId);

            // Batch Job 9: System Date Increment (previously Job 8)
            boolean systemDateIncremented = executeBatchJob9(eodDate, systemDate, userId);
            
            // Log overall EOD success
            logEODJob(eodDate, "EOD Complete", systemDate, userId, 
                     accountsProcessed + interestEntriesProcessed + glMovementsProcessed + 
                     glMovementsUpdated + glBalancesUpdated + accrualBalancesUpdated,
                     EODLogTable.EODStatus.Success, null, "All jobs completed");
            
            log.info("=== EOD PROCESS COMPLETED SUCCESSFULLY ===");
            return EODResult.success("EOD completed successfully", 
                    accountsProcessed, interestEntriesProcessed, glMovementsProcessed,
                    glMovementsUpdated, glBalancesUpdated, accrualBalancesUpdated);
                    
        } catch (Exception e) {
            log.error("EOD process failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "EOD Failed", systemDate, userId, 0, 
                     EODLogTable.EODStatus.Failed, e.getMessage(), "Exception during execution");
            return EODResult.failure("EOD process failed: " + e.getMessage());
        }
    }

    /**
     * Batch Job 1: Account Balance Update
     */
    @Transactional
    public int executeBatchJob1(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 1: Account Balance Update");
        // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
        LocalDateTime startTime = systemDateService.getSystemDateTime();

        try {
            // ========== STEP 0: DELETE ALL ENTRY STATUS TRANSACTIONS ==========
            log.info("========== STEP 0: DELETE ALL ENTRY STATUS TRANSACTIONS ==========");
            log.info("Deleting ALL Entry status transactions (regardless of transaction date)");

            // Query ALL Entry transactions before deletion (for detailed logging)
            List<TranTable> entryTransactions = tranTableRepository.findByTranStatus(TranTable.TranStatus.Entry);

            if (entryTransactions.isEmpty()) {
                log.info("No Entry status transactions found in the system");
            } else {
                log.info("Found {} Entry status transactions to delete:", entryTransactions.size());

                // Group by date for better logging
                Map<LocalDate, Long> entryByDate = entryTransactions.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                TranTable::getTranDate,
                                java.util.stream.Collectors.counting()
                        ));

                log.info("Entry transactions grouped by date:");
                entryByDate.forEach((date, count) ->
                    log.info("  - {}: {} transactions", date, count)
                );

                // Log details of each transaction to be deleted
                log.info("Transaction details:");
                for (TranTable tran : entryTransactions) {
                    log.info("  - Tran_ID: {}, Date: {}, Account: {}, Dr/Cr: {}, Amount: {}, Desc: {}",
                            tran.getTranId(), tran.getTranDate(), tran.getAccountNo(),
                            tran.getDrCrFlag(), tran.getLcyAmt(), tran.getNarration());
                }

                // Delete ALL Entry transactions
                log.info("Executing deletion for {} Entry transactions...", entryTransactions.size());
                int deletedCount = tranTableRepository.deleteByTranStatus(TranTable.TranStatus.Entry);
                log.info("Deletion executed. Deleted count: {}", deletedCount);

                // Verify deletion
                Long remainingCount = tranTableRepository.countByTranStatus(TranTable.TranStatus.Entry);

                if (remainingCount > 0) {
                    log.error("DELETION VERIFICATION FAILED: {} Entry transactions still exist after deletion",
                            remainingCount);

                    // Log remaining transactions for debugging
                    List<TranTable> remainingTrans = tranTableRepository.findByTranStatus(TranTable.TranStatus.Entry);
                    log.error("Remaining Entry transactions:");
                    for (TranTable tran : remainingTrans) {
                        log.error("  - Tran_ID: {}, Date: {}, Account: {}",
                                tran.getTranId(), tran.getTranDate(), tran.getAccountNo());
                    }

                    throw new RuntimeException("Failed to delete Entry status transactions. " +
                            "Expected to delete " + entryTransactions.size() + " but " + remainingCount + " remain.");
                }

                log.info("✓ Step 0 completed successfully: {} Entry transactions deleted and verified", deletedCount);
            }

            // ========== CONTINUE WITH EXISTING BATCH JOB 1 ==========
            log.info("========== STEP 1: ACCOUNT BALANCE UPDATE ==========");

            int recordsProcessed = accountBalanceUpdateService.executeAccountBalanceUpdate(systemDate);

            logEODJob(eodDate, "Account Balance Update", systemDate, userId, recordsProcessed,
                     EODLogTable.EODStatus.Success, null, "Completed");

            log.info("Batch Job 1 completed successfully. Records processed: {}", recordsProcessed);
            return recordsProcessed;

        } catch (Exception e) {
            log.error("Batch Job 1 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "Account Balance Update", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "Account balance update");
            throw new RuntimeException("Batch Job 1 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 2: Interest Accrual Transaction Update
     */
    @Transactional
    public int executeBatchJob2(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 2: Interest Accrual Transaction Update");
        
        try {
            int recordsProcessed = interestAccrualService.runEODAccruals(systemDate);
            
            logEODJob(eodDate, "Interest Accrual Transaction Update", systemDate, userId, recordsProcessed,
                     EODLogTable.EODStatus.Success, null, "Completed");
            
            log.info("Batch Job 2 completed successfully. Records processed: {}", recordsProcessed);
            return recordsProcessed;
            
        } catch (Exception e) {
            log.error("Batch Job 2 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "Interest Accrual Transaction Update", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "Interest accrual transaction update");
            throw new RuntimeException("Batch Job 2 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 3: Interest Accrual GL Movement Update
     */
    @Transactional
    public int executeBatchJob3(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 3: Interest Accrual GL Movement Update");

        try {
            int recordsProcessed = interestAccrualGLMovementService.processInterestAccrualGLMovements(systemDate);

            logEODJob(eodDate, "Interest Accrual GL Movement Update", systemDate, userId, recordsProcessed,
                     EODLogTable.EODStatus.Success, null, "Completed");

            log.info("Batch Job 3 completed successfully. Records processed: {}", recordsProcessed);
            return recordsProcessed;

        } catch (Exception e) {
            log.error("Batch Job 3 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "Interest Accrual GL Movement Update", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "Interest accrual GL movement update");
            throw new RuntimeException("Batch Job 3 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 4: GL Movement Update
     */
    @Transactional
    public int executeBatchJob4(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 4: GL Movement Update");

        try {
            int recordsProcessed = glMovementUpdateService.processGLMovements(systemDate);

            logEODJob(eodDate, "GL Movement Update", systemDate, userId, recordsProcessed,
                     EODLogTable.EODStatus.Success, null, "Completed");

            log.info("Batch Job 4 completed successfully. Records processed: {}", recordsProcessed);
            return recordsProcessed;

        } catch (Exception e) {
            log.error("Batch Job 4 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "GL Movement Update", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "GL movement update");
            throw new RuntimeException("Batch Job 4 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 5: GL Balance Update
     */
    @Transactional
    public int executeBatchJob5(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 5: GL Balance Update");

        try {
            int recordsProcessed = glBalanceUpdateService.updateGLBalances(systemDate);

            logEODJob(eodDate, "GL Balance Update", systemDate, userId, recordsProcessed,
                     EODLogTable.EODStatus.Success, null, "Completed");

            log.info("Batch Job 5 completed successfully. Records processed: {}", recordsProcessed);
            return recordsProcessed;

        } catch (Exception e) {
            log.error("Batch Job 5 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "GL Balance Update", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "GL balance update");
            throw new RuntimeException("Batch Job 5 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 6: Interest Accrual Account Balance Update
     */
    @Transactional
    public int executeBatchJob6(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 6: Interest Accrual Account Balance Update");

        try {
            int recordsProcessed = interestAccrualAccountBalanceService.updateInterestAccrualAccountBalances(systemDate);

            logEODJob(eodDate, "Interest Accrual Account Balance Update", systemDate, userId, recordsProcessed,
                     EODLogTable.EODStatus.Success, null, "Completed");

            log.info("Batch Job 6 completed successfully. Records processed: {}", recordsProcessed);
            return recordsProcessed;

        } catch (Exception e) {
            log.error("Batch Job 6 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "Interest Accrual Account Balance Update", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "Interest accrual account balance update");
            throw new RuntimeException("Batch Job 6 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 7: MCT Revaluation (NEW)
     * Performs End-of-Day revaluation of all foreign currency positions
     * Posts unrealized gain/loss entries for FCY GL accounts
     */
    @Transactional
    public int executeBatchJob7(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 7: MCT Revaluation (Foreign Currency Revaluation)");

        try {
            RevaluationService.RevaluationResult result = revaluationService.performEodRevaluation();
            int entriesPosted = result.getEntriesPosted();

            logEODJob(eodDate, "MCT Revaluation", systemDate, userId, entriesPosted,
                     EODLogTable.EODStatus.Success, null,
                     String.format("Completed - Gain: %s, Loss: %s", result.getTotalGain(), result.getTotalLoss()));

            log.info("Batch Job 7 completed successfully. Revaluation entries posted: {}, Total Gain: {}, Total Loss: {}",
                    entriesPosted, result.getTotalGain(), result.getTotalLoss());
            return entriesPosted;

        } catch (Exception e) {
            log.error("Batch Job 7 (MCT Revaluation) failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "MCT Revaluation", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "MCT revaluation");
            throw new RuntimeException("Batch Job 7 (MCT Revaluation) failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 8: Financial Reports Generation (previously Batch Job 7)
     */
    @Transactional
    public boolean executeBatchJob8(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 8: Financial Reports Generation");

        try {
            Map<String, String> reportPaths = financialReportsService.generateFinancialReports(systemDate);
            boolean reportsGenerated = !reportPaths.isEmpty();

            logEODJob(eodDate, "Financial Reports Generation", systemDate, userId, reportPaths.size(),
                     EODLogTable.EODStatus.Success, null, "Completed: " + String.join(", ", reportPaths.values()));

            log.info("Batch Job 8 completed successfully. Reports generated: {}", reportPaths);
            return reportsGenerated;

        } catch (Exception e) {
            log.error("Batch Job 8 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "Financial Reports Generation", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "Financial reports generation");
            throw new RuntimeException("Batch Job 8 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Batch Job 9: System Date Increment (previously Batch Job 8)
     */
    @Transactional
    public boolean executeBatchJob9(LocalDate eodDate, LocalDate systemDate, String userId) {
        log.info("Starting Batch Job 9: System Date Increment");

        try {
            LocalDate newSystemDate = systemDate.plusDays(1);
            updateSystemDate(newSystemDate, userId);

            logEODJob(eodDate, "System Date Increment", systemDate, userId, 1,
                     EODLogTable.EODStatus.Success, null, "Completed");

            log.info("Batch Job 9 completed successfully. System date incremented from {} to {}",
                    systemDate, newSystemDate);
            return true;

        } catch (Exception e) {
            log.error("Batch Job 9 failed: {}", e.getMessage(), e);
            logEODJob(eodDate, "System Date Increment", systemDate, userId, 0,
                     EODLogTable.EODStatus.Failed, e.getMessage(), "System date increment");
            throw new RuntimeException("Batch Job 9 failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get current system date from SystemDateService
     * CBS Compliance: Uses centralized SystemDateService instead of direct Parameter_Table access
     */
    public LocalDate getSystemDate() {
        return systemDateService.getSystemDate();
    }

    /**
     * Update system date in Parameter_Table
     */
    private void updateSystemDate(LocalDate newSystemDate, String userId) {
        Optional<ParameterTable> systemDateParam = parameterTableRepository.findByParameterName("System_Date");
        if (systemDateParam.isPresent()) {
            ParameterTable param = systemDateParam.get();
            param.setParameterValue(newSystemDate.toString());
            param.setUpdatedBy(userId);
            // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
            param.setLastUpdated(systemDateService.getSystemDateTime());
            parameterTableRepository.save(param);
        }
        
        // Update Last_EOD_Date, Last_EOD_Timestamp, Last_EOD_User
        updateParameter("Last_EOD_Date", newSystemDate.toString(), userId);
        // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
        updateParameter("Last_EOD_Timestamp", systemDateService.getSystemDateTime().toString(), userId);
        updateParameter("Last_EOD_User", userId, userId);
    }

    /**
     * Update a parameter in Parameter_Table
     */
    private void updateParameter(String parameterName, String parameterValue, String userId) {
        Optional<ParameterTable> param = parameterTableRepository.findByParameterName(parameterName);
        if (param.isPresent()) {
            ParameterTable parameter = param.get();
            parameter.setParameterValue(parameterValue);
            parameter.setUpdatedBy(userId);
            // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
            parameter.setLastUpdated(systemDateService.getSystemDateTime());
            parameterTableRepository.save(parameter);
        }
    }

    /**
     * Log EOD job execution
     */
    private void logEODJob(LocalDate eodDate, String jobName, LocalDate systemDate, String userId,
                          int recordsProcessed, EODLogTable.EODStatus status, String errorMessage, String failedAtStep) {
        EODLogTable logEntry = EODLogTable.builder()
                .eodDate(eodDate)
                .jobName(jobName)
                // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
                .startTimestamp(systemDateService.getSystemDateTime())
                .endTimestamp(systemDateService.getSystemDateTime())
                .systemDate(systemDate)
                .userId(userId)
                .recordsProcessed(recordsProcessed)
                .status(status)
                .errorMessage(errorMessage)
                .failedAtStep(failedAtStep)
                // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
                .createdTimestamp(systemDateService.getSystemDateTime())
                .build();
        
        eodLogTableRepository.save(logEntry);
    }

    /**
     * EOD Result class
     */
    public static class EODResult {
        private final boolean success;
        private final String message;
        private final int accountsProcessed;
        private final int interestEntriesProcessed;
        private final int glMovementsProcessed;
        private final int glMovementsUpdated;
        private final int glBalancesUpdated;
        private final int accrualBalancesUpdated;

        private EODResult(boolean success, String message, int accountsProcessed, int interestEntriesProcessed,
                         int glMovementsProcessed, int glMovementsUpdated, int glBalancesUpdated, int accrualBalancesUpdated) {
            this.success = success;
            this.message = message;
            this.accountsProcessed = accountsProcessed;
            this.interestEntriesProcessed = interestEntriesProcessed;
            this.glMovementsProcessed = glMovementsProcessed;
            this.glMovementsUpdated = glMovementsUpdated;
            this.glBalancesUpdated = glBalancesUpdated;
            this.accrualBalancesUpdated = accrualBalancesUpdated;
        }

        public static EODResult success(String message, int accountsProcessed, int interestEntriesProcessed,
                                       int glMovementsProcessed, int glMovementsUpdated, int glBalancesUpdated, int accrualBalancesUpdated) {
            return new EODResult(true, message, accountsProcessed, interestEntriesProcessed,
                    glMovementsProcessed, glMovementsUpdated, glBalancesUpdated, accrualBalancesUpdated);
        }

        public static EODResult failure(String message) {
            return new EODResult(false, message, 0, 0, 0, 0, 0, 0);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public int getAccountsProcessed() { return accountsProcessed; }
        public int getInterestEntriesProcessed() { return interestEntriesProcessed; }
        public int getGlMovementsProcessed() { return glMovementsProcessed; }
        public int getGlMovementsUpdated() { return glMovementsUpdated; }
        public int getGlBalancesUpdated() { return glBalancesUpdated; }
        public int getAccrualBalancesUpdated() { return accrualBalancesUpdated; }
    }
}
