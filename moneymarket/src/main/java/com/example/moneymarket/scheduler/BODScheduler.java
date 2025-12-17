package com.example.moneymarket.scheduler;

import com.example.moneymarket.service.BODValueDateService;
import com.example.moneymarket.service.SystemDateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BOD (Beginning-of-Day) Scheduler
 * Automatically posts future-dated transactions when their value date arrives
 *
 * PTTP05: Value Date Transactions
 *
 * Configuration:
 * - bod.scheduler.enabled: true/false to enable/disable automatic execution
 * - bod.scheduler.cron: Cron expression for scheduling (default: 6:00 AM daily)
 *
 * Features:
 * - Automatic scheduled execution (configurable)
 * - Manual trigger via API endpoint
 * - Environment-specific configuration (dev/prod)
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class BODScheduler {

    private final BODValueDateService bodValueDateService;
    private final SystemDateService systemDateService;

    @Value("${bod.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    @Value("${bod.scheduler.cron:0 0 6 * * ?}")
    private String cronExpression;

    /**
     * Automatic BOD execution - Scheduled daily
     *
     * Default schedule: 6:00 AM daily (0 0 6 * * ?)
     *
     * This method:
     * 1. Checks if scheduler is enabled
     * 2. Runs BOD process to post future-dated transactions
     * 3. Logs execution results
     * 4. Handles errors gracefully
     */
    @Scheduled(cron = "${bod.scheduler.cron:0 0 6 * * ?}")
    public void runAutomaticBOD() {
        if (!schedulerEnabled) {
            log.debug("BOD Scheduler is DISABLED - skipping automatic execution. " +
                    "Set bod.scheduler.enabled=true to enable.");
            return;
        }

        log.info("========================================");
        log.info("Starting AUTOMATIC BOD process at {}", LocalDateTime.now());
        log.info("Cron expression: {}", cronExpression);
        log.info("========================================");

        try {
            // Execute BOD process
            BODResult result = runManualBOD();

            log.info("Automatic BOD process completed successfully");
            log.info("Result: Status={}, Processed={} transactions",
                    result.getStatus(), result.getProcessedCount());
            log.info("========================================");

        } catch (Exception e) {
            log.error("========================================");
            log.error("AUTOMATIC BOD PROCESS FAILED", e);
            log.error("Error message: {}", e.getMessage());
            log.error("========================================");

            // TODO: Consider sending email/SMS alert to operations team
            // alertService.sendAlert("BOD Failed", e.getMessage());
        }
    }

    /**
     * Run BOD processing manually
     * This should be called via API endpoint by administrators
     * This runs BEFORE business operations start for the day
     *
     * Can be called from:
     * 1. REST endpoint: POST /api/bod/run
     * 2. Admin UI
     * 3. Emergency procedures
     * 4. Automatic scheduler (runAutomaticBOD)
     */
    public BODResult runManualBOD() {
        LocalDate systemDate = systemDateService.getSystemDate();
        log.info("=".repeat(80));
        log.info("Starting MANUAL BOD (Beginning of Day) processing for date: {}", systemDate);
        log.info("=".repeat(80));

        BODResult result = new BODResult();
        result.setSystemDate(systemDate);

        try {
            // Step 1: Check pending future-dated transactions
            long pendingCount = bodValueDateService.getPendingFutureDatedTransactionsCount();
            log.info("BOD: Found {} pending future-dated transactions", pendingCount);
            result.setPendingCountBefore(pendingCount);

            // Step 2: Process future-dated transactions whose value date has arrived
            int processedCount = bodValueDateService.processFutureDatedTransactions();
            result.setProcessedCount(processedCount);

            if (processedCount > 0) {
                log.info("BOD: Successfully processed {} future-dated transactions", processedCount);
            } else {
                log.info("BOD: No future-dated transactions to process today");
            }

            // Step 3: Check remaining pending future-dated transactions
            long remainingCount = bodValueDateService.getPendingFutureDatedTransactionsCount();
            log.info("BOD: {} future-dated transactions still pending for future dates", remainingCount);
            result.setPendingCountAfter(remainingCount);

            result.setStatus("SUCCESS");
            result.setMessage("BOD processing completed successfully");

            log.info("=".repeat(80));
            log.info("BOD processing completed successfully for date: {}", systemDate);
            log.info("=".repeat(80));

        } catch (Exception e) {
            log.error("=".repeat(80));
            log.error("BOD processing failed for date: {}", systemDate, e);
            log.error("=".repeat(80));

            result.setStatus("FAILED");
            result.setMessage("BOD processing failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Get status of pending future-dated transactions
     */
    public BODStatus getBODStatus() {
        long pendingCount = bodValueDateService.getPendingFutureDatedTransactionsCount();
        LocalDate systemDate = systemDateService.getSystemDate();

        BODStatus status = new BODStatus();
        status.setSystemDate(systemDate);
        status.setPendingFutureDatedCount(pendingCount);
        status.setPendingTransactions(bodValueDateService.getPendingFutureDatedTransactions());

        return status;
    }

    /**
     * Get scheduler enabled status
     * @return true if automatic scheduler is enabled
     */
    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    /**
     * Get cron expression for scheduler
     * @return current cron schedule
     */
    public String getCronExpression() {
        return cronExpression;
    }

    /**
     * BOD processing result
     */
    @lombok.Data
    public static class BODResult {
        private LocalDate systemDate;
        private long pendingCountBefore;
        private int processedCount;
        private long pendingCountAfter;
        private String status;
        private String message;
    }

    /**
     * BOD status information
     */
    @lombok.Data
    public static class BODStatus {
        private LocalDate systemDate;
        private long pendingFutureDatedCount;
        private java.util.List<com.example.moneymarket.entity.TranTable> pendingTransactions;
    }
}
