package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.repository.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for Foreign Currency Revaluation (EOD/BOD)
 * Implements PTTP05 specification:
 * - EOD: Mark-to-market revaluation of all FCY positions
 * - BOD: Reversal of previous day revaluation entries
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevaluationService {

    private final RevalTranRepository revalTranRepository;
    private final FxRateMasterRepository fxRateMasterRepository;
    private final WaeMasterRepository waeMasterRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final GLSetupRepository glSetupRepository;
    private final GLBalanceRepository glBalanceRepository;
    private final TranTableRepository tranTableRepository;
    private final GLMovementRepository glMovementRepository;
    private final BalanceService balanceService;
    private final SystemDateService systemDateService;
    private final AcctBalRepository acctBalRepository;

    // FCY GL Accounts (Nostro accounts)
    private static final List<String> FCY_GL_ACCOUNTS = Arrays.asList(
        "220302001", // Nostro USD
        "220303001", // Nostro EUR
        "220304001", // Nostro GBP
        "220305001"  // Nostro JPY
    );

    // GL Account to Currency mapping
    private static final java.util.Map<String, String> GL_TO_CURRENCY_MAP = java.util.Map.of(
        "220302001", "USD",
        "220303001", "EUR",
        "220304001", "GBP",
        "220305001", "JPY"
    );

    // Revaluation GL accounts (Unrealised Gain/Loss)
    private static final String UNREALISED_FX_GAIN_GL = "140203002";
    private static final String UNREALISED_FX_LOSS_GL = "240203002";

    // ========================================
    // EOD REVALUATION
    // ========================================

    /**
     * Perform End-of-Day revaluation for all FCY positions
     * Scheduled to run at 6 PM daily (disabled for manual triggering)
     * Use EODOrchestrationService to trigger this as part of EOD batch
     *
     * @return Revaluation result summary
     */
    // @Scheduled(cron = "0 0 18 * * *") // 6 PM daily - commented out for manual triggering
    @Transactional
    public RevaluationResult performEodRevaluation() {
        LocalDate revalDate = systemDateService.getSystemDate();
        log.info("Starting EOD revaluation for date: {}", revalDate);

        List<RevaluationEntry> entries = new ArrayList<>();
        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        // Step 1: Revalue FCY GL accounts (Nostro)
        for (String glNum : FCY_GL_ACCOUNTS) {
            try {
                RevaluationEntry entry = processGLRevaluation(glNum, revalDate);
                if (entry != null) {
                    entries.add(entry);
                    if (entry.getRevalDiff().compareTo(BigDecimal.ZERO) > 0) {
                        totalGain = totalGain.add(entry.getRevalDiff());
                    } else {
                        totalLoss = totalLoss.add(entry.getRevalDiff().abs());
                    }
                }
            } catch (Exception e) {
                log.error("Error revaluing GL account {}: {}", glNum, e.getMessage(), e);
            }
        }

        // Step 2: Revalue FCY customer accounts
        List<CustAcctMaster> fcyAccounts = custAcctMasterRepository.findAll().stream()
            .filter(acct -> !"BDT".equals(acct.getAccountCcy()))
            .filter(acct -> acct.getAccountStatus() == CustAcctMaster.AccountStatus.Active)
            .toList();

        for (CustAcctMaster acct : fcyAccounts) {
            try {
                RevaluationEntry entry = processAccountRevaluation(acct, revalDate);
                if (entry != null) {
                    entries.add(entry);
                    if (entry.getRevalDiff().compareTo(BigDecimal.ZERO) > 0) {
                        totalGain = totalGain.add(entry.getRevalDiff());
                    } else {
                        totalLoss = totalLoss.add(entry.getRevalDiff().abs());
                    }
                }
            } catch (Exception e) {
                log.error("Error revaluing account {}: {}", acct.getAccountNo(), e.getMessage(), e);
            }
        }

        log.info("EOD revaluation completed: {} entries, Total Gain: {}, Total Loss: {}",
            entries.size(), totalGain, totalLoss);

        return RevaluationResult.builder()
            .revalDate(revalDate)
            .entriesPosted(entries.size())
            .totalGain(totalGain)
            .totalLoss(totalLoss)
            .entries(entries)
            .build();
    }

    /**
     * Process GL account revaluation (for Nostro/Position GL accounts)
     * Asset accounts: Dr Reval Gain / Cr Reval Loss
     *
     * FIXED: Now uses WAE Master to get FCY balance and booked LCY
     * - Booked LCY = LCY balance from WAE Master (historical cost)
     * - MTM LCY = FCY balance × Current Mid Rate (mark-to-market)
     * - Revaluation Diff = MTM LCY - Booked LCY (now calculates correctly!)
     */
    private RevaluationEntry processGLRevaluation(String glNum, LocalDate revalDate) {
        String currency = GL_TO_CURRENCY_MAP.get(glNum);
        if (currency == null) {
            log.warn("Currency mapping not found for GL: {}", glNum);
            return null;
        }

        // Get WAE Master for this Position GL account
        String ccyPair = currency + "/BDT";
        Optional<WaeMaster> waeMasterOpt = waeMasterRepository.findByCcyPair(ccyPair);

        if (waeMasterOpt.isEmpty()) {
            log.debug("WAE Master not found for {}, skipping revaluation of GL {}", ccyPair, glNum);
            return null;
        }

        WaeMaster waeMaster = waeMasterOpt.get();
        BigDecimal fcyBalance = waeMaster.getFcyBalance();
        BigDecimal bookedLcy = waeMaster.getLcyBalance();  // Historical cost from WAE
        BigDecimal bookingRate = waeMaster.getWaeRate();   // WAE rate as booking rate

        // Skip if FCY balance is zero
        if (fcyBalance.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("GL {} has zero FCY balance in WAE Master, skipping revaluation", glNum);
            return null;
        }

        // Get current mid rate for mark-to-market
        Optional<FxRateMaster> fxRateOpt = fxRateMasterRepository.findFirstByCcyPairOrderByRateDateDesc(ccyPair);
        if (fxRateOpt.isEmpty()) {
            log.warn("No FX rate found for {}, skipping revaluation of GL {}", ccyPair, glNum);
            return null;
        }

        BigDecimal midRate = fxRateOpt.get().getMidRate();

        // Calculate Mark-to-Market LCY value at current mid rate
        BigDecimal mtmLcy = fcyBalance.multiply(midRate).setScale(2, RoundingMode.HALF_UP);

        // Calculate revaluation difference (NOW IT WORKS!)
        // Diff = MTM (at current rate) - Booked (at WAE/historical rate)
        BigDecimal revalDiff = mtmLcy.subtract(bookedLcy);

        // Skip if no difference
        if (revalDiff.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("No revaluation difference for GL {}, skipping", glNum);
            return null;
        }

        // Determine revaluation GL
        String revalGl = revalDiff.compareTo(BigDecimal.ZERO) > 0 ?
            UNREALISED_FX_GAIN_GL : UNREALISED_FX_LOSS_GL;

        // Post revaluation entries (Asset account logic)
        String tranId = postRevaluationEntries(glNum, revalGl, revalDiff, revalDate, true);

        // Save to reval_tran table
        RevalTran revalTran = RevalTran.builder()
            .revalDate(revalDate)
            .acctNum(glNum)
            .ccyCode(currency)
            .fcyBalance(fcyBalance)
            .midRate(midRate)
            .bookedLcy(bookedLcy)
            .mtmLcy(mtmLcy)
            .revalDiff(revalDiff)
            .revalGl(revalGl)
            .tranId(tranId)
            .status("POSTED")
            .build();

        revalTranRepository.save(revalTran);

        log.info("GL {} revalued: FCY={}, Booking Rate={}, Booked LCY={}, Mid Rate={}, MTM LCY={}, Diff={}",
            glNum, fcyBalance, bookingRate, bookedLcy, midRate, mtmLcy, revalDiff);

        return RevaluationEntry.builder()
            .accountNo(glNum)
            .currency(currency)
            .fcyBalance(fcyBalance)
            .midRate(midRate)
            .bookedLcy(bookedLcy)
            .mtmLcy(mtmLcy)
            .revalDiff(revalDiff)
            .build();
    }

    /**
     * Process customer account revaluation
     * Liability accounts: Dr Reval Loss / Cr Reval Gain (opposite of asset)
     *
     * FIXED: Now calculates booked LCY from previous revaluation or uses account opening balance
     * - First revaluation: Uses current FCY × Mid Rate as baseline (booked = MTM, diff = 0)
     * - Subsequent revaluations: Uses MTM LCY from previous day as booked LCY
     * - This ensures revaluation differences are calculated correctly day-over-day
     */
    private RevaluationEntry processAccountRevaluation(CustAcctMaster acct, LocalDate revalDate) {
        String accountNo = acct.getAccountNo();
        String currency = acct.getAccountCcy();

        // Get account balance for the revaluation date
        Optional<AcctBal> balanceOpt = acctBalRepository.findLatestByAccountNo(accountNo);
        if (balanceOpt.isEmpty()) {
            log.debug("No balance found for account {}, skipping revaluation", accountNo);
            return null;
        }

        AcctBal balance = balanceOpt.get();
        BigDecimal fcyBalance = balance.getCurrentBalance();

        // Skip if balance is zero
        if (fcyBalance.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Account {} has zero balance, skipping revaluation", accountNo);
            return null;
        }

        // Get current mid rate for MTM
        String ccyPair = currency + "/BDT";
        Optional<FxRateMaster> fxRateOpt = fxRateMasterRepository.findFirstByCcyPairOrderByRateDateDesc(ccyPair);
        if (fxRateOpt.isEmpty()) {
            log.warn("No FX rate found for {}, skipping revaluation of account {}", ccyPair, accountNo);
            return null;
        }

        BigDecimal midRate = fxRateOpt.get().getMidRate();

        // Calculate Mark-to-Market LCY value at current mid rate
        BigDecimal mtmLcy = fcyBalance.multiply(midRate).setScale(2, RoundingMode.HALF_UP);

        // Get booked LCY from previous day's MTM (if exists)
        // This implements day-over-day revaluation tracking
        BigDecimal bookedLcy;
        BigDecimal bookingRate;

        // Find most recent previous revaluation for this account
        List<RevalTran> previousRevals = revalTranRepository.findByAcctNumAndStatusOrderByRevalDateDesc(accountNo, "POSTED");

        if (!previousRevals.isEmpty()) {
            // Use previous day's MTM as today's booked LCY
            RevalTran previousReval = previousRevals.get(0);
            bookedLcy = previousReval.getMtmLcy();  // Yesterday's MTM becomes today's booked
            bookingRate = previousReval.getMidRate(); // Yesterday's rate

            log.debug("Using previous revaluation as baseline for {}: Booked LCY={}, Rate={}",
                accountNo, bookedLcy, bookingRate);
        } else {
            // First time revaluation: Use current MTM as baseline
            // This establishes the initial booking, so diff will be zero
            bookedLcy = mtmLcy;
            bookingRate = midRate;

            log.info("First revaluation for {}: Setting baseline Booked LCY={} at Rate={}",
                accountNo, bookedLcy, bookingRate);
        }

        // Calculate revaluation difference
        // Diff = MTM (at current rate) - Booked (from previous day's MTM or initial baseline)
        BigDecimal revalDiff = mtmLcy.subtract(bookedLcy);

        // Skip if no difference
        if (revalDiff.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("No revaluation difference for account {}, skipping", accountNo);
            return null;
        }

        // Determine revaluation GL (opposite for liability)
        String revalGl = revalDiff.compareTo(BigDecimal.ZERO) > 0 ?
            UNREALISED_FX_LOSS_GL : UNREALISED_FX_GAIN_GL;

        // Post revaluation entries (Liability account logic - opposite of asset)
        String tranId = postRevaluationEntries(accountNo, revalGl, revalDiff, revalDate, false);

        // Save to reval_tran table
        RevalTran revalTran = RevalTran.builder()
            .revalDate(revalDate)
            .acctNum(accountNo)
            .ccyCode(currency)
            .fcyBalance(fcyBalance)
            .midRate(midRate)
            .bookedLcy(bookedLcy)
            .mtmLcy(mtmLcy)
            .revalDiff(revalDiff)
            .revalGl(revalGl)
            .tranId(tranId)
            .status("POSTED")
            .build();

        revalTranRepository.save(revalTran);

        log.info("Account {} revalued: FCY={}, Booking Rate={}, Booked LCY={}, Mid Rate={}, MTM LCY={}, Diff={}",
            accountNo, fcyBalance, bookingRate, bookedLcy, midRate, mtmLcy, revalDiff);

        return RevaluationEntry.builder()
            .accountNo(accountNo)
            .currency(currency)
            .fcyBalance(fcyBalance)
            .midRate(midRate)
            .bookedLcy(bookedLcy)
            .mtmLcy(mtmLcy)
            .revalDiff(revalDiff)
            .build();
    }

    /**
     * Post revaluation entries to transaction table
     *
     * @param acctNum Account/GL being revalued
     * @param revalGl Revaluation GL account (gain or loss)
     * @param revalDiff Revaluation difference amount
     * @param revalDate Revaluation date
     * @param isAsset True for asset accounts, false for liability accounts
     * @return Transaction ID
     */
    private String postRevaluationEntries(String acctNum, String revalGl, BigDecimal revalDiff,
                                         LocalDate revalDate, boolean isAsset) {
        String tranId = "REVAL-" + revalDate + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        BigDecimal absAmount = revalDiff.abs();
        boolean isGain = revalDiff.compareTo(BigDecimal.ZERO) > 0;

        DrCrFlag acctFlag;
        DrCrFlag revalFlag;

        if (isAsset) {
            // Asset account logic:
            // Gain: Dr Account (increase asset), Cr Gain GL
            // Loss: Dr Loss GL, Cr Account (decrease asset)
            if (isGain) {
                acctFlag = DrCrFlag.D;
                revalFlag = DrCrFlag.C;
            } else {
                acctFlag = DrCrFlag.C;
                revalFlag = DrCrFlag.D;
            }
        } else {
            // Liability account logic (opposite):
            // Gain: Dr Gain GL, Cr Account (increase liability)
            // Loss: Dr Account (decrease liability), Cr Loss GL
            if (isGain) {
                acctFlag = DrCrFlag.C;
                revalFlag = DrCrFlag.D;
            } else {
                acctFlag = DrCrFlag.D;
                revalFlag = DrCrFlag.C;
            }
        }

        // Entry 1: Account being revalued
        createRevaluationEntry(tranId + "-1", acctNum, revalDate, acctFlag, absAmount,
            "EOD Revaluation - " + (isGain ? "Gain" : "Loss"));

        // Entry 2: Revaluation GL
        createRevaluationEntry(tranId + "-2", revalGl, revalDate, revalFlag, absAmount,
            "EOD Revaluation - " + (isGain ? "Gain" : "Loss"));

        return tranId;
    }

    /**
     * Create a single revaluation entry in transaction table
     */
    private void createRevaluationEntry(String tranId, String accountNo, LocalDate tranDate,
                                       DrCrFlag drCrFlag, BigDecimal amount, String narration) {
        TranTable transaction = TranTable.builder()
            .tranId(tranId)
            .tranDate(tranDate)
            .valueDate(tranDate)
            .drCrFlag(drCrFlag)
            .tranStatus(TranStatus.Posted)
            .accountNo(accountNo)
            .tranCcy("BDT")
            .fcyAmt(amount)
            .exchangeRate(BigDecimal.ONE)
            .lcyAmt(amount)
            .debitAmount(drCrFlag == DrCrFlag.D ? amount : BigDecimal.ZERO)
            .creditAmount(drCrFlag == DrCrFlag.C ? amount : BigDecimal.ZERO)
            .narration(narration)
            .build();

        tranTableRepository.save(transaction);

        // Update balance (GL or customer account)
        if (accountNo.length() == 9) {
            // GL account
            BigDecimal newBalance = balanceService.updateGLBalance(accountNo, drCrFlag, amount);

            // Create GL movement
            GLSetup glSetup = glSetupRepository.findById(accountNo)
                .orElseThrow(() -> new RuntimeException("GL not found: " + accountNo));

            GLMovement glMovement = GLMovement.builder()
                .transaction(transaction)
                .glSetup(glSetup)
                .drCrFlag(drCrFlag)
                .tranDate(tranDate)
                .valueDate(tranDate)
                .amount(amount)
                .balanceAfter(newBalance)
                .build();

            glMovementRepository.save(glMovement);
        } else {
            // Customer account
            balanceService.updateAccountBalance(accountNo, drCrFlag, amount);
        }
    }

    // ========================================
    // BOD REVALUATION REVERSAL
    // ========================================

    /**
     * Perform Beginning-of-Day reversal of previous day revaluation entries
     * Scheduled to run at 9 AM daily (disabled for manual triggering)
     * Use BODScheduler to trigger this as part of BOD batch
     */
    // @Scheduled(cron = "0 0 9 * * *") // 9 AM daily - commented out for manual triggering
    @Transactional
    public void performBodRevaluationReversal() {
        LocalDate today = systemDateService.getSystemDate();
        LocalDate yesterday = today.minusDays(1);

        log.info("Starting BOD revaluation reversal for date: {}", yesterday);

        // Find all posted revaluations from yesterday
        List<RevalTran> yesterdayRevals = revalTranRepository.findPostedRevaluationsForDate(yesterday);

        if (yesterdayRevals.isEmpty()) {
            log.info("No revaluation entries found for reversal on date: {}", yesterday);
            return;
        }

        int reversalCount = 0;
        for (RevalTran revalTran : yesterdayRevals) {
            try {
                // Reverse the transaction by flipping Dr/Cr flags
                String reversalTranId = reverseRevaluationEntry(revalTran, today);

                // Update reval_tran record
                revalTran.setReversalTranId(reversalTranId);
                revalTran.setStatus("REVERSED");
                revalTran.setReversedOn(java.time.LocalDateTime.now());
                revalTranRepository.save(revalTran);

                reversalCount++;
                log.debug("Reversed revaluation entry: {} -> {}", revalTran.getTranId(), reversalTranId);
            } catch (Exception e) {
                log.error("Error reversing revaluation for {}: {}", revalTran.getTranId(), e.getMessage(), e);
            }
        }

        log.info("BOD revaluation reversal completed: {} entries reversed", reversalCount);
    }

    /**
     * Reverse a revaluation entry by flipping Dr/Cr flags
     */
    private String reverseRevaluationEntry(RevalTran revalTran, LocalDate reversalDate) {
        String originalTranId = revalTran.getTranId();
        String reversalTranId = "REV-" + originalTranId;

        // Find original transaction entries
        List<TranTable> originalEntries = tranTableRepository.findByTranIdStartingWith(originalTranId);

        if (originalEntries.isEmpty()) {
            log.warn("No transaction entries found for reversal: {}", originalTranId);
            return reversalTranId;
        }

        // Create reversal entries with flipped Dr/Cr flags
        for (TranTable originalEntry : originalEntries) {
            DrCrFlag reversalFlag = originalEntry.getDrCrFlag() == DrCrFlag.D ? DrCrFlag.C : DrCrFlag.D;

            String reversalEntryId = reversalTranId + originalEntry.getTranId().substring(originalTranId.length());

            createRevaluationEntry(
                reversalEntryId,
                originalEntry.getAccountNo(),
                reversalDate,
                reversalFlag,
                originalEntry.getLcyAmt(),
                "BOD Reversal - " + originalEntry.getNarration()
            );
        }

        return reversalTranId;
    }

    /**
     * Get revaluation summary for a specific date
     */
    public RevaluationResult getRevaluationSummary(LocalDate revalDate) {
        List<RevalTran> revaluations = revalTranRepository.findByRevalDateAndStatus(revalDate, "POSTED");

        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        List<RevaluationEntry> entries = new ArrayList<>();

        for (RevalTran revalTran : revaluations) {
            if (revalTran.getRevalDiff().compareTo(BigDecimal.ZERO) > 0) {
                totalGain = totalGain.add(revalTran.getRevalDiff());
            } else {
                totalLoss = totalLoss.add(revalTran.getRevalDiff().abs());
            }

            entries.add(RevaluationEntry.builder()
                .accountNo(revalTran.getAcctNum())
                .currency(revalTran.getCcyCode())
                .fcyBalance(revalTran.getFcyBalance())
                .midRate(revalTran.getMidRate())
                .bookedLcy(revalTran.getBookedLcy())
                .mtmLcy(revalTran.getMtmLcy())
                .revalDiff(revalTran.getRevalDiff())
                .build());
        }

        return RevaluationResult.builder()
            .revalDate(revalDate)
            .entriesPosted(entries.size())
            .totalGain(totalGain)
            .totalLoss(totalLoss)
            .entries(entries)
            .build();
    }

    // ========================================
    // DTOs
    // ========================================

    @Data
    @Builder
    public static class RevaluationResult {
        private LocalDate revalDate;
        private int entriesPosted;
        private BigDecimal totalGain;
        private BigDecimal totalLoss;
        private List<RevaluationEntry> entries;
    }

    @Data
    @Builder
    public static class RevaluationEntry {
        private String accountNo;
        private String currency;
        private BigDecimal fcyBalance;
        private BigDecimal midRate;
        private BigDecimal bookedLcy;
        private BigDecimal mtmLcy;
        private BigDecimal revalDiff;
    }
}
