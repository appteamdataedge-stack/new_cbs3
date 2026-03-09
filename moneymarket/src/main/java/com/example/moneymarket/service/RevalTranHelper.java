package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-record transaction helper for FCY revaluation.
 *
 * Each public method runs in its own REQUIRES_NEW transaction so that a failure
 * in one account does not mark the shared Hibernate session as rollback-only
 * and does not prevent subsequent accounts from being processed.
 *
 * Transaction ID format:  "R" + yyyyMMdd + "-" + UUID8   (18 chars, fits VARCHAR(30))
 * Reversal ID format:     "V" + yyyyMMdd + "-" + UUID8   (18 chars, fits VARCHAR(30))
 * TranTable entry suffix: base + "-1" / "-2"             (20 chars, fits VARCHAR(30))
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RevalTranHelper {

    private final RevalTranRepository revalTranRepository;
    private final FxRateMasterRepository fxRateMasterRepository;
    private final WaeMasterRepository waeMasterRepository;
    private final GLSetupRepository glSetupRepository;
    private final TranTableRepository tranTableRepository;
    private final GLMovementRepository glMovementRepository;
    private final BalanceService balanceService;
    private final AcctBalRepository acctBalRepository;

    // Injected by JPA (not by Lombok constructor) — used for defensive em.clear() in catch blocks
    @PersistenceContext
    private EntityManager entityManager;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String UNREALISED_FX_GAIN_GL = "140203002";
    private static final String UNREALISED_FX_LOSS_GL = "240203002";

    // ================================================================
    // EOD: per-GL-account revaluation (Nostro accounts)
    // ================================================================

    /**
     * Revalue a single FCY Nostro GL account and persist the RevalTran record.
     * REQUIRES_NEW: failure in this account cannot poison the Hibernate session
     * for subsequent accounts.
     *
     * @return RevaluationEntry summary for the caller to accumulate totals, or null if skipped
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RevaluationService.RevaluationEntry saveGlRevalEntry(String glNum,
                                                                String currency,
                                                                LocalDate revalDate) {
        try {
            String ccyPair = currency + "/BDT";

            Optional<WaeMaster> waeMasterOpt = waeMasterRepository.findByCcyPair(ccyPair);
            if (waeMasterOpt.isEmpty()) {
                log.debug("WAE Master not found for {}, skipping GL {}", ccyPair, glNum);
                return null;
            }

            WaeMaster waeMaster = waeMasterOpt.get();
            BigDecimal fcyBalance = waeMaster.getFcyBalance();
            BigDecimal bookedLcy  = waeMaster.getLcyBalance();
            BigDecimal bookingRate = waeMaster.getWaeRate();

            if (fcyBalance == null || fcyBalance.compareTo(BigDecimal.ZERO) == 0) {
                log.debug("GL {} has zero FCY balance in WAE Master, skipping", glNum);
                return null;
            }

            Optional<FxRateMaster> fxRateOpt =
                fxRateMasterRepository.findFirstByCcyPairOrderByRateDateDesc(ccyPair);
            if (fxRateOpt.isEmpty()) {
                log.warn("No FX rate found for {}, skipping GL {}", ccyPair, glNum);
                return null;
            }

            BigDecimal midRate = fxRateOpt.get().getMidRate();
            BigDecimal mtmLcy  = fcyBalance.multiply(midRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal revalDiff = mtmLcy.subtract(bookedLcy != null ? bookedLcy : BigDecimal.ZERO);

            if (revalDiff.compareTo(BigDecimal.ZERO) == 0) {
                log.debug("No revaluation difference for GL {}, skipping", glNum);
                return null;
            }

            String revalGl = revalDiff.compareTo(BigDecimal.ZERO) > 0
                ? UNREALISED_FX_GAIN_GL : UNREALISED_FX_LOSS_GL;

            // Post TranTable + GLMovement entries (asset account logic)
            String tranId = postRevaluationEntries(glNum, revalGl, revalDiff, revalDate, true);

            revalTranRepository.saveAndFlush(RevalTran.builder()
                .revalDate(revalDate)
                .acctNum(glNum)
                .ccyCode(currency)
                .fcyBalance(fcyBalance)
                .midRate(midRate)
                .bookedLcy(bookedLcy != null ? bookedLcy : BigDecimal.ZERO)
                .mtmLcy(mtmLcy)
                .revalDiff(revalDiff)
                .revalGl(revalGl)
                .tranId(tranId)
                .status("POSTED")
                .createdOn(LocalDateTime.now())
                .build());

            log.info("GL {} revalued: FCY={}, BookingRate={}, BookedLCY={}, MidRate={}, MTMLCY={}, Diff={}",
                glNum, fcyBalance, bookingRate, bookedLcy, midRate, mtmLcy, revalDiff);

            return RevaluationService.RevaluationEntry.builder()
                .accountNo(glNum)
                .currency(currency)
                .fcyBalance(fcyBalance)
                .midRate(midRate)
                .bookedLcy(bookedLcy != null ? bookedLcy : BigDecimal.ZERO)
                .mtmLcy(mtmLcy)
                .revalDiff(revalDiff)
                .build();

        } catch (Exception e) {
            entityManager.clear(); // FIX B: clear session state on failure
            log.error("Error in GL revaluation for {}: {}", glNum, e.getMessage(), e);
            throw e; // re-throw so caller's loop catch can log and continue
        }
    }

    // ================================================================
    // EOD: per-customer-account revaluation
    // ================================================================

    /**
     * Revalue a single FCY customer account and persist the RevalTran record.
     * REQUIRES_NEW: failure in this account cannot poison the Hibernate session
     * for subsequent accounts.
     *
     * @return RevaluationEntry summary for the caller to accumulate totals, or null if skipped
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RevaluationService.RevaluationEntry saveAccountRevalEntry(CustAcctMaster acct,
                                                                     LocalDate revalDate) {
        String accountNo = acct.getAccountNo();
        String currency  = acct.getAccountCcy();

        try {
            Optional<AcctBal> balanceOpt = acctBalRepository.findLatestByAccountNo(accountNo);
            if (balanceOpt.isEmpty()) {
                log.debug("No balance found for account {}, skipping", accountNo);
                return null;
            }

            BigDecimal fcyBalance = balanceOpt.get().getCurrentBalance();
            if (fcyBalance == null || fcyBalance.compareTo(BigDecimal.ZERO) == 0) {
                log.debug("Account {} has zero balance, skipping", accountNo);
                return null;
            }

            String ccyPair = currency + "/BDT";
            Optional<FxRateMaster> fxRateOpt =
                fxRateMasterRepository.findFirstByCcyPairOrderByRateDateDesc(ccyPair);
            if (fxRateOpt.isEmpty()) {
                log.warn("No FX rate found for {}, skipping account {}", ccyPair, accountNo);
                return null;
            }

            BigDecimal midRate = fxRateOpt.get().getMidRate();
            BigDecimal mtmLcy  = fcyBalance.multiply(midRate).setScale(2, RoundingMode.HALF_UP);

            // Use previous day's MTM as today's booked LCY (day-over-day revaluation tracking)
            List<RevalTran> previousRevals =
                revalTranRepository.findByAcctNumAndStatusOrderByRevalDateDesc(accountNo, "POSTED");

            if (previousRevals.isEmpty()) {
                // First-ever revaluation — no previous MTM to compare against, so diff = 0.
                // Do NOT post any entries today; next EOD will have a baseline to compute diff from.
                log.info("First revaluation check for {}: no prior POSTED record, skipping today (diff=0)", accountNo);
                return null;
            }

            RevalTran prev    = previousRevals.get(0);
            BigDecimal bookedLcy   = prev.getMtmLcy() != null ? prev.getMtmLcy() : BigDecimal.ZERO;
            BigDecimal bookingRate = prev.getMidRate() != null ? prev.getMidRate() : BigDecimal.ZERO;

            log.debug("Reval baseline for {}: BookedLCY={}, Rate={}", accountNo, bookedLcy, bookingRate);

            BigDecimal revalDiff = mtmLcy.subtract(bookedLcy);
            if (revalDiff.compareTo(BigDecimal.ZERO) == 0) {
                log.debug("No revaluation difference for account {}, skipping", accountNo);
                return null;
            }

            // Liability accounts: gain/loss GL is the opposite of asset accounts
            String revalGl = revalDiff.compareTo(BigDecimal.ZERO) > 0
                ? UNREALISED_FX_LOSS_GL : UNREALISED_FX_GAIN_GL;

            String tranId = postRevaluationEntries(accountNo, revalGl, revalDiff, revalDate, false);

            revalTranRepository.saveAndFlush(RevalTran.builder()
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
                .createdOn(LocalDateTime.now())
                .build());

            log.info("Account {} revalued: FCY={}, BookingRate={}, BookedLCY={}, MidRate={}, MTMLCY={}, Diff={}",
                accountNo, fcyBalance, bookingRate, bookedLcy, midRate, mtmLcy, revalDiff);

            return RevaluationService.RevaluationEntry.builder()
                .accountNo(accountNo)
                .currency(currency)
                .fcyBalance(fcyBalance)
                .midRate(midRate)
                .bookedLcy(bookedLcy)
                .mtmLcy(mtmLcy)
                .revalDiff(revalDiff)
                .build();

        } catch (Exception e) {
            entityManager.clear(); // FIX B: clear session state on failure
            log.error("Error in account revaluation for {}: {}", accountNo, e.getMessage(), e);
            throw e;
        }
    }

    // ================================================================
    // BOD: per-record reversal
    // ================================================================

    /**
     * Reverse a single BOD revaluation entry and mark it REVERSED.
     * REQUIRES_NEW: failure in one reversal does not block the others.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processBodReversal(RevalTran revalTran, LocalDate today) {
        try {
            String originalTranId = revalTran.getTranId();

            // Generate a fresh reversal ID that fits within VARCHAR(30)
            String reversalTranId = "V" + today.format(DATE_FMT) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            List<TranTable> originalEntries = originalTranId != null
                ? tranTableRepository.findByTranIdStartingWith(originalTranId)
                : List.of();

            if (originalEntries.isEmpty()) {
                log.warn("No TranTable entries found for reversal of: {}", originalTranId);
            } else {
                int originalTranIdLen = originalTranId != null ? originalTranId.length() : 0;
                for (TranTable orig : originalEntries) {
                    DrCrFlag reversalFlag = orig.getDrCrFlag() == DrCrFlag.D ? DrCrFlag.C : DrCrFlag.D;
                    // Reproduce suffix ("-1", "-2") from the original TranTable entry
                    String suffix = (originalTranIdLen > 0 && orig.getTranId().length() > originalTranIdLen)
                        ? orig.getTranId().substring(originalTranIdLen)
                        : "";
                    createRevaluationEntry(
                        reversalTranId + suffix,
                        orig.getAccountNo(),
                        today,
                        reversalFlag,
                        orig.getLcyAmt(),
                        "BOD Reversal - " + orig.getNarration());
                }
            }

            revalTran.setReversalTranId(reversalTranId);
            revalTran.setStatus("REVERSED");
            revalTran.setReversedOn(LocalDateTime.now());
            revalTranRepository.saveAndFlush(revalTran);

        } catch (Exception e) {
            entityManager.clear(); // FIX B: clear session state on failure
            log.error("Error in BOD reversal for tranId={}: {}", revalTran.getTranId(), e.getMessage(), e);
            throw e;
        }
    }

    // ================================================================
    // Private helpers
    // ================================================================

    /**
     * Generate a transaction ID and post the two-legged revaluation entry
     * (account leg + GL gain/loss leg) to TranTable and update balances.
     *
     * ID format: "R" + yyyyMMdd + "-" + UUID8  (18 chars base, 20 with "-1"/"-2" suffix)
     * All lengths fit within VARCHAR(30) columns in Tran_Table and GL_Movement.
     */
    private String postRevaluationEntries(String acctNum, String revalGl, BigDecimal revalDiff,
                                          LocalDate revalDate, boolean isAsset) {
        // Use compact format: fits reval_tran.Tran_Id VARCHAR(30) and TranTable VARCHAR(30)
        String tranId = "R" + revalDate.format(DATE_FMT) + "-"
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        BigDecimal absAmount = revalDiff.abs();
        boolean isGain = revalDiff.compareTo(BigDecimal.ZERO) > 0;
        String label = "EOD Revaluation - " + (isGain ? "Gain" : "Loss");

        DrCrFlag acctFlag;
        DrCrFlag revalFlag;

        if (isAsset) {
            // Gain: Dr Account (↑ asset), Cr Gain GL
            // Loss: Cr Account (↓ asset), Dr Loss GL
            acctFlag = isGain ? DrCrFlag.D : DrCrFlag.C;
            revalFlag = isGain ? DrCrFlag.C : DrCrFlag.D;
        } else {
            // Liability — opposite direction:
            // Gain: Cr Account (↑ liability), Dr Loss GL
            // Loss: Dr Account (↓ liability), Cr Gain GL
            acctFlag = isGain ? DrCrFlag.C : DrCrFlag.D;
            revalFlag = isGain ? DrCrFlag.D : DrCrFlag.C;
        }

        createRevaluationEntry(tranId + "-1", acctNum, revalDate, acctFlag, absAmount, label);
        createRevaluationEntry(tranId + "-2", revalGl, revalDate, revalFlag, absAmount, label);

        return tranId;
    }

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

        if (accountNo != null && accountNo.length() == 9) {
            // GL account
            BigDecimal newBalance = balanceService.updateGLBalance(accountNo, drCrFlag, amount);
            GLSetup glSetup = glSetupRepository.findById(accountNo)
                .orElseThrow(() -> new RuntimeException("GL not found: " + accountNo));
            glMovementRepository.save(GLMovement.builder()
                .transaction(transaction)
                .glSetup(glSetup)
                .drCrFlag(drCrFlag)
                .tranDate(tranDate)
                .valueDate(tranDate)
                .amount(amount)
                .balanceAfter(newBalance)
                .build());
        } else {
            // Customer account
            balanceService.updateAccountBalance(accountNo, drCrFlag, amount);
        }
    }
}
