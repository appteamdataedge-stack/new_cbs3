package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.entity.BodExecutionLog;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.exception.ResourceNotFoundException;
import com.example.moneymarket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BOD (Beginning of Day) service for executing pending deal schedules.
 *
 * Accounting events per BRD Section 7:
 *   INT_PAY Liability: DR Interest Expenditure GL  → CR Term Deposit Account
 *   INT_PAY Asset:     DR Loan Account             → CR Interest Income GL
 *   MAT_PAY Liability: DR Term Deposit Account     → CR Operative Account
 *   MAT_PAY Asset:     DR Loan Account             → CR Operative Account
 *
 * FIX: Each schedule runs in its own REQUIRES_NEW transaction via self-injection
 * (self.executeSingleSchedule) so that one failure does not roll back others.
 * The outer executeDailySchedules is read-write (NOT readOnly) and logs the
 * overall BOD result in BOD_EXECUTION_LOG at the end.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BODSchedulerService {

    private static final String STATUS_EXECUTED = "EXECUTED";
    private static final String STATUS_FAILED   = "FAILED";
    private static final String EVENT_INT_PAY   = "INT_PAY";
    private static final String EVENT_MAT_PAY   = "MAT_PAY";
    private static final String SYSTEM_USER     = "SYSTEM";

    private final DealScheduleRepository   dealScheduleRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final AcctBalRepository        acctBalRepository;
    private final TranTableRepository      tranTableRepository;
    private final BodExecutionLogRepository bodExecutionLogRepository;
    private final BalanceService           balanceService;
    private final SystemDateService        systemDateService;

    /**
     * Self-reference injected via @Lazy to allow REQUIRES_NEW to work on
     * executeSingleSchedule calls. Without this, direct this.method() calls
     * bypass Spring's AOP proxy and REQUIRES_NEW is silently ignored.
     */
    @Autowired
    @Lazy
    private BODSchedulerService self;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Execute all pending schedules up to (and including) the given business date.
     * Each schedule is processed in its own REQUIRES_NEW transaction so one failure
     * does not roll back the others.
     * After all schedules are processed, the BOD execution is logged in
     * BOD_EXECUTION_LOG so the validation layer knows BOD ran today.
     *
     * @param businessDate The business date to process
     * @return Summary counts {total, executed, failed}
     */
    @Transactional
    public int[] executeDailySchedules(LocalDate businessDate) {
        List<DealSchedule> pending = dealScheduleRepository.findPendingSchedulesUpTo(businessDate);
        log.info("BOD Scheduler: found {} pending schedule(s) up to {}", pending.size(), businessDate);

        int executed = 0, failed = 0;
        for (DealSchedule schedule : pending) {
            // Call through self (proxy) so REQUIRES_NEW is honoured
            boolean ok = self.executeSingleSchedule(schedule, businessDate);
            if (ok) executed++; else failed++;
        }
        log.info("BOD Scheduler finished: executed={}, failed={}", executed, failed);

        // Log BOD execution so validation can unblock transactions for today.
        // Determine status: SUCCESS=all ok, PARTIAL=some failed, FAILED=all failed.
        String bodStatus;
        if (pending.isEmpty()) {
            bodStatus = "SUCCESS";  // Nothing to do — no pending schedules
        } else if (failed == 0) {
            bodStatus = "SUCCESS";
        } else if (executed > 0) {
            bodStatus = "PARTIAL";
        } else {
            bodStatus = "FAILED";
        }
        saveBodExecutionLog(businessDate, bodStatus, executed, failed);

        return new int[]{pending.size(), executed, failed};
    }

    // -------------------------------------------------------------------------
    // Per-schedule execution (separate REQUIRES_NEW transaction for idempotency)
    // -------------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean executeSingleSchedule(DealSchedule schedule, LocalDate businessDate) {
        log.info("Executing schedule id={} event={} account={} date={}",
                schedule.getScheduleId(), schedule.getEventCode(),
                schedule.getAccountNumber(), schedule.getScheduleDate());
        try {
            // Idempotency guard
            if (!STATUS_FAILED.equals(schedule.getStatus()) && !"PENDING".equals(schedule.getStatus())) {
                log.warn("Schedule {} is already in status '{}', skipping", schedule.getScheduleId(), schedule.getStatus());
                return false;
            }

            // Re-load from DB inside this new transaction to get a fresh managed entity
            DealSchedule managedSchedule = dealScheduleRepository.findById(schedule.getScheduleId())
                    .orElseThrow(() -> new ResourceNotFoundException("DealSchedule", "id", schedule.getScheduleId()));

            // Load deal account
            CustAcctMaster dealAccount = custAcctMasterRepository.findById(managedSchedule.getAccountNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("Deal Account", "Account Number",
                            managedSchedule.getAccountNumber()));

            SubProdMaster subProduct = dealAccount.getSubProduct();
            String ccy = managedSchedule.getCurrencyCode();

            if (EVENT_INT_PAY.equals(managedSchedule.getEventCode())) {
                processIntPay(managedSchedule, dealAccount, subProduct, ccy, businessDate);
            } else if (EVENT_MAT_PAY.equals(managedSchedule.getEventCode())) {
                processMatPay(managedSchedule, dealAccount, ccy, businessDate);
            } else {
                throw new IllegalArgumentException("Unknown event code: " + managedSchedule.getEventCode());
            }

            markExecuted(managedSchedule, businessDate);
            return true;

        } catch (Exception ex) {
            log.error("Failed to execute schedule id={}: {}", schedule.getScheduleId(), ex.getMessage(), ex);
            // Re-load for status update (outer entity may be from a different session)
            dealScheduleRepository.findById(schedule.getScheduleId()).ifPresent(s -> markFailed(s, ex));
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // INT_PAY processing
    // -------------------------------------------------------------------------

    /**
     * INT_PAY Liability: DR Interest Expenditure GL → CR Term Deposit Account
     * INT_PAY Asset:     DR Loan Account            → CR Interest Income GL
     */
    private void processIntPay(DealSchedule schedule, CustAcctMaster dealAccount,
                                SubProdMaster subProduct, String ccy, LocalDate businessDate) {
        BigDecimal amount = schedule.getScheduleAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("INT_PAY schedule {} has zero/null amount, skipping accounting entries",
                    schedule.getScheduleId());
            return;
        }

        boolean isLiability = "L".equals(schedule.getDealType());
        String narration = "INT_PAY - " + schedule.getAccountNumber()
                + " - " + businessDate.format(DateTimeFormatter.ISO_DATE);

        String baseTranId = generateBodTranId(businessDate);

        if (isLiability) {
            // DR Interest Expenditure GL, CR Term Deposit Account
            String interestExpGL = subProduct.getInterestReceivableExpenditureGLNum();
            if (interestExpGL == null) {
                throw new IllegalStateException("Interest Expenditure GL not configured for sub-product "
                        + subProduct.getSubProductCode());
            }
            saveTranEntry(buildTranEntry(baseTranId + "-1", businessDate, businessDate,
                    DrCrFlag.D, null, interestExpGL, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            saveTranEntry(buildTranEntry(baseTranId + "-2", businessDate, businessDate,
                    DrCrFlag.C, schedule.getAccountNumber(), null, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            balanceService.updateAccountBalance(schedule.getAccountNumber(), DrCrFlag.C, amount);

        } else {
            // Asset: DR Loan Account, CR Interest Income GL
            String interestIncGL = subProduct.getInterestIncomePayableGLNum();
            if (interestIncGL == null) {
                throw new IllegalStateException("Interest Income GL not configured for sub-product "
                        + subProduct.getSubProductCode());
            }
            saveTranEntry(buildTranEntry(baseTranId + "-1", businessDate, businessDate,
                    DrCrFlag.D, schedule.getAccountNumber(), null, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            saveTranEntry(buildTranEntry(baseTranId + "-2", businessDate, businessDate,
                    DrCrFlag.C, null, interestIncGL, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            balanceService.updateAccountBalance(schedule.getAccountNumber(), DrCrFlag.D, amount);
        }

        log.info("INT_PAY processed for account={} amount={}", schedule.getAccountNumber(), amount);
    }

    // -------------------------------------------------------------------------
    // MAT_PAY processing
    // -------------------------------------------------------------------------

    /**
     * MAT_PAY Liability: DR Term Deposit Account → CR Operative Account
     * MAT_PAY Asset:     DR Loan Account         → CR Operative Account
     *
     * The transfer amount is the deal account's current balance (absolute value).
     */
    private void processMatPay(DealSchedule schedule, CustAcctMaster dealAccount,
                                String ccy, LocalDate businessDate) {
        String operativeAccNo = schedule.getOperativeAccountNo();
        if (operativeAccNo == null || operativeAccNo.isBlank()) {
            throw new IllegalStateException("Operative account not set on schedule id=" + schedule.getScheduleId());
        }

        AcctBal dealBal = acctBalRepository.findLatestByAccountNo(schedule.getAccountNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Deal Account Balance",
                        "Account Number", schedule.getAccountNumber()));

        // Liability balance is negative; Asset balance is positive
        BigDecimal transferAmount = dealBal.getCurrentBalance().abs();
        if (transferAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("MAT_PAY schedule {} has zero balance on deal account, skipping", schedule.getScheduleId());
            return;
        }

        boolean isLiability = "L".equals(schedule.getDealType());
        String narration = "MAT_PAY - " + schedule.getAccountNumber()
                + " - " + businessDate.format(DateTimeFormatter.ISO_DATE);

        String baseTranId = generateBodTranId(businessDate);

        if (isLiability) {
            // DR TD Account, CR Operative Account
            saveTranEntry(buildTranEntry(baseTranId + "-1", businessDate, businessDate,
                    DrCrFlag.D, schedule.getAccountNumber(), null, ccy, transferAmount, narration, "BOD", EVENT_MAT_PAY));
            saveTranEntry(buildTranEntry(baseTranId + "-2", businessDate, businessDate,
                    DrCrFlag.C, operativeAccNo, null, ccy, transferAmount, narration, "BOD", EVENT_MAT_PAY));
            balanceService.updateAccountBalance(schedule.getAccountNumber(), DrCrFlag.D, transferAmount);
            balanceService.updateAccountBalance(operativeAccNo, DrCrFlag.C, transferAmount);

        } else {
            // Asset: DR Loan Account, CR Operative Account
            saveTranEntry(buildTranEntry(baseTranId + "-1", businessDate, businessDate,
                    DrCrFlag.D, schedule.getAccountNumber(), null, ccy, transferAmount, narration, "BOD", EVENT_MAT_PAY));
            saveTranEntry(buildTranEntry(baseTranId + "-2", businessDate, businessDate,
                    DrCrFlag.C, operativeAccNo, null, ccy, transferAmount, narration, "BOD", EVENT_MAT_PAY));
            balanceService.updateAccountBalance(schedule.getAccountNumber(), DrCrFlag.D, transferAmount);
            balanceService.updateAccountBalance(operativeAccNo, DrCrFlag.C, transferAmount);
        }

        // Mark deal account as Closed after maturity payment
        dealAccount.setAccountStatus(CustAcctMaster.AccountStatus.Closed);
        dealAccount.setDateClosure(businessDate);
        custAcctMasterRepository.save(dealAccount);

        log.info("MAT_PAY processed for account={} amount={}", schedule.getAccountNumber(), transferAmount);
    }

    // -------------------------------------------------------------------------
    // Schedule status updates
    // -------------------------------------------------------------------------

    private void markExecuted(DealSchedule schedule, LocalDate businessDate) {
        schedule.setStatus(STATUS_EXECUTED);
        schedule.setExecutionDateTime(businessDate.atStartOfDay());
        schedule.setExecutedBy(SYSTEM_USER);
        schedule.setLastUpdatedDateTime(LocalDateTime.now());
        schedule.setLastUpdatedBy(SYSTEM_USER);
        dealScheduleRepository.save(schedule);
    }

    private void markFailed(DealSchedule schedule, Exception ex) {
        schedule.setStatus(STATUS_FAILED);
        schedule.setErrorCode("BOD_EXEC_ERROR");
        schedule.setErrorMessage(ex.getMessage() != null
                ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 490))
                : "Unknown error");
        schedule.setLastUpdatedDateTime(LocalDateTime.now());
        schedule.setLastUpdatedBy(SYSTEM_USER);
        dealScheduleRepository.save(schedule);
    }

    // -------------------------------------------------------------------------
    // BOD execution log
    // -------------------------------------------------------------------------

    private void saveBodExecutionLog(LocalDate date, String status, int executed, int failed) {
        Optional<BodExecutionLog> existing = bodExecutionLogRepository.findByExecutionDate(date);
        BodExecutionLog logEntry = existing.orElse(BodExecutionLog.builder()
                .executionDate(date)
                .build());
        logEntry.setExecutionTimestamp(LocalDateTime.now());
        logEntry.setStatus(status);
        logEntry.setSchedulesExecuted(executed);
        logEntry.setSchedulesFailed(failed);
        logEntry.setTransactionsPosted(executed * 2);
        logEntry.setExecutedBy(SYSTEM_USER);
        bodExecutionLogRepository.save(logEntry);
        log.info("BOD execution logged: date={}, status={}, executed={}, failed={}", date, status, executed, failed);
    }

    // -------------------------------------------------------------------------
    // BOD status query (used by validation layer)
    // -------------------------------------------------------------------------

    /**
     * Returns true if BOD has been successfully (or partially) executed for the
     * given date. Used by TransactionService and DealBookingService to determine
     * whether to block new transactions.
     */
    public boolean isBodExecutedForDate(LocalDate date) {
        return bodExecutionLogRepository.existsByExecutionDateAndStatus(date, "SUCCESS")
                || bodExecutionLogRepository.existsByExecutionDateAndStatus(date, "PARTIAL");
    }

    // -------------------------------------------------------------------------
    // Transaction building helpers
    // -------------------------------------------------------------------------

    private TranTable buildTranEntry(String tranId, LocalDate tranDate, LocalDate valueDate,
                                      DrCrFlag drCrFlag, String accountNo, String glNum,
                                      String ccy, BigDecimal amount,
                                      String narration, String tranType, String tranSubType) {
        BigDecimal debitAmt  = DrCrFlag.D == drCrFlag ? amount : BigDecimal.ZERO;
        BigDecimal creditAmt = DrCrFlag.C == drCrFlag ? amount : BigDecimal.ZERO;

        return TranTable.builder()
                .tranId(tranId)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(drCrFlag)
                .tranStatus(TranStatus.Posted)
                .accountNo(accountNo)
                .glNum(glNum)
                .tranCcy(ccy)
                .fcyAmt(amount)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(amount)
                .debitAmount(debitAmt)
                .creditAmount(creditAmt)
                .narration(narration)
                .tranType(tranType)
                .tranSubType(tranSubType)
                .build();
    }

    private void saveTranEntry(TranTable entry) {
        tranTableRepository.save(entry);
    }

    /** Generates a BOD transaction ID: B + yyyyMMdd + 6-digit-seq + 3-digit-random (18 chars). */
    private String generateBodTranId(LocalDate businessDate) {
        String date = businessDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = tranTableRepository.countByTranDate(businessDate) + 1;
        int rand = ThreadLocalRandom.current().nextInt(1000);
        return String.format("B%s%06d%03d", date, seq, rand);
    }
}
