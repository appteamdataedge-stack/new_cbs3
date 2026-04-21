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
import java.math.RoundingMode;
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
 *   INT_PAY Asset:     DR Interest Receivable GL    → DR Loan Account  (tran_table DR; balance update CR)
 *   MAT_PAY Liability: DR Term Deposit Account     → CR Operative Account
 *   MAT_PAY Asset:     CR Loan Account             → CR Operative Account  (tran_table CR; balance update DR)
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
    private final InterestRateMasterRepository interestRateMasterRepository;
    private final BodExecutionLogRepository bodExecutionLogRepository;
    private final BalanceService           balanceService;
    private final SystemDateService        systemDateService;
    private final TransactionHistoryService transactionHistoryService;

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
     * INT_PAY Asset:     DR Interest Receivable GL  → CR Loan Account
     */
    private void processIntPay(DealSchedule schedule, CustAcctMaster dealAccount,
                                SubProdMaster subProduct, String ccy, LocalDate businessDate) {
        BigDecimal amount = calculateInterestForPeriod(schedule, dealAccount);
        // Keep schedule amount aligned with actual posted interest for audit/reporting.
        schedule.setScheduleAmount(amount);
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
            // DR Interest Payable GL, CR Term Deposit Account
            String interestPayableGL = subProduct.getInterestIncomePayableGLNum();
            if (interestPayableGL == null) {
                throw new IllegalStateException("Interest Payable GL not configured for sub-product "
                        + subProduct.getSubProductCode());
            }
            saveTranEntry(buildTranEntry(baseTranId + "-1", businessDate, businessDate,
                    DrCrFlag.D, null, interestPayableGL, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            saveTranEntry(buildTranEntry(baseTranId + "-2", businessDate, businessDate,
                    DrCrFlag.C, schedule.getAccountNumber(), null, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            balanceService.updateAccountBalance(schedule.getAccountNumber(), DrCrFlag.C, amount);

        } else {
            // Asset INT_PAY: DR Interest Receivable GL → DR Loan Account (interest accrues on loan)
            // Loan account entry uses DrCrFlag.D so the account statement shows a debit
            // (interest increases what the customer owes). Balance update still uses C so
            // currentBalance remains negative (more owed).
            String interestIncGL = subProduct.getInterestIncomePayableGLNum();
            if (interestIncGL == null) {
                throw new IllegalStateException("Interest Receivable GL not configured for sub-product "
                        + subProduct.getSubProductCode());
            }
            saveTranEntry(buildTranEntry(baseTranId + "-1", businessDate, businessDate,
                    DrCrFlag.D, null, interestIncGL, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            saveTranEntry(buildTranEntry(baseTranId + "-2", businessDate, businessDate,
                    DrCrFlag.D, schedule.getAccountNumber(), null, ccy, amount, narration, "BOD", EVENT_INT_PAY));
            balanceService.updateAccountBalance(schedule.getAccountNumber(), DrCrFlag.C, amount);
        }

        log.info("INT_PAY processed for account={} amount={}", schedule.getAccountNumber(), amount);
    }

    // -------------------------------------------------------------------------
    // MAT_PAY processing
    // -------------------------------------------------------------------------

    /**
     * MAT_PAY Liability: DR Term Deposit Account → CR Operative Account
     * MAT_PAY Asset:     CR Loan Account         → CR Operative Account
     *
     * For Asset (Loan) maturity: the loan account gets a CR entry (account statement shows
     * customer repaying the debt). The balance update uses DR (add) to bring the negative
     * loan balance back to zero. The operative account is CR'd (repayment received).
     */
    private void processMatPay(DealSchedule schedule, CustAcctMaster dealAccount,
                                String ccy, LocalDate businessDate) {
        String operativeAccNo = schedule.getOperativeAccountNo();
        if (operativeAccNo == null || operativeAccNo.isBlank()) {
            throw new IllegalStateException("Operative account not set on schedule id=" + schedule.getScheduleId());
        }

        BigDecimal transferAmount = calculateMaturityAmount(schedule, businessDate);
        // Keep schedule amount aligned with actual maturity posting (principal + accrued interest).
        schedule.setScheduleAmount(transferAmount);
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
            // Asset MAT_PAY: loan closes out.
            // CR Loan Account (statement shows credit = customer repays, zeroes balance)
            // CR Operative Account (repayment funds credited to customer's account)
            // Balance update uses DrCrFlag.D (add) to bring the negative loan balance back to 0.
            saveTranEntry(buildTranEntry(baseTranId + "-1", businessDate, businessDate,
                    DrCrFlag.C, schedule.getAccountNumber(), null, ccy, transferAmount, narration, "BOD", EVENT_MAT_PAY));
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

    /**
     * MAT_PAY amount must be Principal + Total Interest capitalized into the deal account.
     * Principal is stored on the MAT_PAY schedule at booking time (scheduleAmount).
     * Interest is identified as credit entries posted by the deal scheduler with tranSubType=INT_PAY.
     */
    private BigDecimal calculateMaturityAmount(DealSchedule schedule, LocalDate businessDate) {
        BigDecimal principal = getBookedPrincipalAmount(schedule);
        // Liability INT_PAY entries are CR on the deal account; Asset INT_PAY entries are DR
        // (they were changed to DR so the account statement shows the correct indicator).
        boolean isLiability = "L".equals(schedule.getDealType());
        DrCrFlag intPayFlag = isLiability ? DrCrFlag.C : DrCrFlag.D;
        BigDecimal totalInterest = tranTableRepository.findByAccountNo(schedule.getAccountNumber()).stream()
                .filter(t -> EVENT_INT_PAY.equals(t.getTranSubType()))
                .filter(t -> t.getDrCrFlag() == intPayFlag)
                .filter(t -> t.getTranDate() != null && !t.getTranDate().isAfter(businessDate))
                .map(t -> t.getFcyAmt() != null ? t.getFcyAmt() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal maturityAmount = principal.add(totalInterest);
        log.info("MAT_PAY maturity amount for account={}: principal={} interest={} total={}",
                schedule.getAccountNumber(), principal, totalInterest, maturityAmount);
        return maturityAmount;
    }

    /**
     * Derive booked principal from the original DEAL/BOOKING transaction line for this deal account.
     * This avoids double-counting interest for compounding deals where MAT_PAY scheduleAmount can
     * already include projected interest.
     */
    private BigDecimal getBookedPrincipalAmount(DealSchedule schedule) {
        boolean isLiability = "L".equals(schedule.getDealType());
        // Liability booking entries are CR on the deal account; Asset booking entries are DR.
        DrCrFlag bookingFlag = isLiability ? DrCrFlag.C : DrCrFlag.D;
        return tranTableRepository.findByAccountNo(schedule.getAccountNumber()).stream()
                .filter(t -> "BOOKING".equals(t.getTranSubType()))
                .filter(t -> t.getTranStatus() == TranStatus.Posted || t.getTranStatus() == TranStatus.Verified)
                .filter(t -> t.getDrCrFlag() == bookingFlag)
                .map(t -> t.getFcyAmt() != null ? t.getFcyAmt() : BigDecimal.ZERO)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Could not derive original principal from booking transaction for account={}, falling back to MAT_PAY schedule amount",
                            schedule.getAccountNumber());
                    return schedule.getScheduleAmount() != null ? schedule.getScheduleAmount() : BigDecimal.ZERO;
                });
    }

    /**
     * Calculates INT_PAY amount for one period.
     * - Compounding (e.g. TDCUM/STLTR): calculate on current TD balance.
     * - Non-compounding: keep booked schedule amount (existing behavior).
     */
    private BigDecimal calculateInterestForPeriod(DealSchedule schedule, CustAcctMaster dealAccount) {
        BigDecimal bookedAmount = schedule.getScheduleAmount() != null ? schedule.getScheduleAmount() : BigDecimal.ZERO;

        if (!isCompoundingDeal(dealAccount)) {
            return bookedAmount;
        }

        SubProdMaster subProduct = dealAccount.getSubProduct();
        if (subProduct == null || subProduct.getInttCode() == null || subProduct.getInttCode().isBlank()) {
            log.warn("Compounding account {} missing inttCode; falling back to booked schedule amount",
                    schedule.getAccountNumber());
            return bookedAmount;
        }

        LocalDate asOfDate = schedule.getScheduleDate() != null ? schedule.getScheduleDate() : systemDateService.getSystemDate();
        Optional<InterestRateMaster> rateOpt = interestRateMasterRepository
                .findTopByInttCodeAndInttEffctvDateLessThanEqualOrderByInttEffctvDateDesc(subProduct.getInttCode(), asOfDate);
        if (rateOpt.isEmpty() || rateOpt.get().getInttRate() == null) {
            log.warn("No interest rate found for account {} inttCode={} asOfDate={}; falling back to booked schedule amount",
                    schedule.getAccountNumber(), subProduct.getInttCode(), asOfDate);
            return bookedAmount;
        }

        BigDecimal principal = getCurrentTDBalance(schedule.getAccountNumber());
        if (principal.compareTo(BigDecimal.ZERO) <= 0) {
            principal = getOriginalDealAmount(schedule.getAccountNumber());
        }

        BigDecimal interestRate = rateOpt.get().getInttRate();
        return principal
                .multiply(interestRate)
                .divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);
    }

    private boolean isCompoundingDeal(CustAcctMaster dealAccount) {
        if (dealAccount == null || dealAccount.getSubProduct() == null) {
            return false;
        }
        String subProductCode = dealAccount.getSubProduct().getSubProductCode();
        return "TDCUM".equals(subProductCode) || "STLTR".equals(subProductCode);
    }

    private BigDecimal getCurrentTDBalance(String accountNumber) {
        Optional<AcctBal> latestBal = acctBalRepository.findLatestByAccountNo(accountNumber);
        if (latestBal.isPresent() && latestBal.get().getCurrentBalance() != null) {
            return latestBal.get().getCurrentBalance();
        }

        // Fallback: derive from posted credits if no balance row is available.
        return tranTableRepository.findByAccountNo(accountNumber).stream()
                .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
                .map(t -> t.getFcyAmt() != null ? t.getFcyAmt() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal getOriginalDealAmount(String accountNumber) {
        return dealScheduleRepository.findByAccountNumber(accountNumber).stream()
                .filter(s -> EVENT_MAT_PAY.equals(s.getEventCode()))
                .map(s -> s.getScheduleAmount() != null ? s.getScheduleAmount() : BigDecimal.ZERO)
                .findFirst()
                .orElse(BigDecimal.ZERO);
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
        // Some downstream readers expect Account_No to be populated even for GL postings.
        String postingAccountNo = (accountNo == null || accountNo.isBlank()) ? glNum : accountNo;

        return TranTable.builder()
                .tranId(tranId)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(drCrFlag)
                .tranStatus(TranStatus.Posted)
                .accountNo(postingAccountNo)
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
        // Record transaction history so the Account Statement shows BOD events.
        // Called before the subsequent balanceService.updateAccountBalance so that
        // openingBalance is read from acct_bal before the current event changes it.
        transactionHistoryService.createTransactionHistory(entry, SYSTEM_USER);
    }

    /** Generates a BOD transaction ID: B + yyyyMMdd + 6-digit-seq + 3-digit-random (18 chars). */
    private String generateBodTranId(LocalDate businessDate) {
        String date = businessDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = tranTableRepository.countByTranDate(businessDate) + 1;
        int rand = ThreadLocalRandom.current().nextInt(1000);
        return String.format("B%s%06d%03d", date, seq, rand);
    }
}
