package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service for Foreign Currency Revaluation (EOD/BOD)
 * Implements PTTP05 specification:
 * - EOD: Mark-to-market revaluation of all FCY positions
 * - BOD: Reversal of previous day revaluation entries
 *
 * Transaction design:
 *   - Main orchestration methods use NOT_SUPPORTED (no shared session).
 *   - Each per-record call in RevalTranHelper runs in its own REQUIRES_NEW
 *     transaction, so a failure in one account does not poison the Hibernate
 *     session and does not block subsequent accounts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevaluationService {

    private final RevalTranRepository revalTranRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final WaeMasterRepository waeMasterRepository;
    private final SystemDateService systemDateService;
    private final RevalTranHelper revalHelper;

    // FIX B: used to clear any lingering session state in loop catch blocks
    @PersistenceContext
    private EntityManager entityManager;

    // FCY GL Accounts (Nostro accounts)
    private static final List<String> FCY_GL_ACCOUNTS = Arrays.asList(
        "220302001", // Nostro USD
        "220303001", // Nostro EUR
        "220304001", // Nostro GBP
        "220305001"  // Nostro JPY
    );

    // GL Account → Currency mapping
    private static final java.util.Map<String, String> GL_TO_CURRENCY_MAP = java.util.Map.of(
        "220302001", "USD",
        "220303001", "EUR",
        "220304001", "GBP",
        "220305001", "JPY"
    );

    // ========================================
    // EOD REVALUATION
    // ========================================

    /**
     * Perform End-of-Day revaluation for all FCY positions.
     * NOT_SUPPORTED: no outer transaction — each account record handled by
     * RevalTranHelper in its own REQUIRES_NEW transaction.
     */
    // @Scheduled(cron = "0 0 18 * * *") // 6 PM daily - commented out for manual triggering
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RevaluationResult performEodRevaluation() {
        LocalDate revalDate = systemDateService.getSystemDate();
        
        // ══════════════════════════════════════════════════════════════════════
        // BYPASSED: EOD Step 7 MCT Revaluation completely disabled
        // No records will be created in tran_table, reval_tran, or gl_movement
        // ══════════════════════════════════════════════════════════════════════
        log.info("EOD Step 7 MCT Revaluation: BYPASSED - no revaluation performed for date: {}", revalDate);
        
        return RevaluationResult.builder()
            .revalDate(revalDate)
            .entriesPosted(0)
            .totalGain(BigDecimal.ZERO)
            .totalLoss(BigDecimal.ZERO)
            .entries(List.of())
            .build();
        
        /* ══════════════════════════════════════════════════════════════════════
         * ORIGINAL REVALUATION LOGIC - COMMENTED OUT FOR COMPLETE BYPASS
         * ══════════════════════════════════════════════════════════════════════
         * 
        log.info("Starting EOD revaluation for date: {}", revalDate);

        // ── EARLY-EXIT GUARD ──────────────────────────────────────────────────
        // Collect FCY accounts from both sources BEFORE doing any DB insertions.
        // If neither source has positions, skip entirely so no ghost tran_table /
        // GL_Movement / reval_tran rows are created.
        List<CustAcctMaster> fcyAccounts = custAcctMasterRepository.findAll().stream()
            .filter(acct -> !"BDT".equals(acct.getAccountCcy()))
            .filter(acct -> acct.getAccountStatus() == CustAcctMaster.AccountStatus.Active)
            .toList();

        boolean hasGlFcyPositions = FCY_GL_ACCOUNTS.stream()
            .anyMatch(glNum -> {
                String ccy = GL_TO_CURRENCY_MAP.get(glNum);
                return waeMasterRepository.findByCcyPair(ccy + "/BDT")
                    .map(w -> w.getFcyBalance() != null
                              && w.getFcyBalance().compareTo(BigDecimal.ZERO) != 0)
                    .orElse(false);
            });

        if (fcyAccounts.isEmpty() && !hasGlFcyPositions) {
            log.info("Step 7 MCT Revaluation: No FCY accounts or GL positions found for {}. Skipping.",
                revalDate);
            return RevaluationResult.builder()
                .revalDate(revalDate)
                .entriesPosted(0)
                .totalGain(BigDecimal.ZERO)
                .totalLoss(BigDecimal.ZERO)
                .entries(List.of())
                .build();
        }

        log.info("FCY positions found: {} customer account(s), Nostro GL data: {}",
            fcyAccounts.size(), hasGlFcyPositions);
        // ── END GUARD ─────────────────────────────────────────────────────────

        List<RevaluationEntry> entries = new ArrayList<>();
        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        // Step 1: Revalue FCY GL accounts (Nostro)
        for (String glNum : FCY_GL_ACCOUNTS) {
            String currency = GL_TO_CURRENCY_MAP.get(glNum);
            try {
                RevaluationEntry entry = revalHelper.saveGlRevalEntry(glNum, currency, revalDate);
                if (entry != null) {
                    entries.add(entry);
                    if (entry.getRevalDiff().compareTo(BigDecimal.ZERO) > 0) {
                        totalGain = totalGain.add(entry.getRevalDiff());
                    } else {
                        totalLoss = totalLoss.add(entry.getRevalDiff().abs());
                    }
                }
            } catch (Exception e) {
                entityManager.clear(); // FIX B: defensive session clear
                log.error("Error revaluing GL account {}: {}", glNum, e.getMessage(), e);
            }
        }

        // Step 2: Revalue FCY customer accounts (list already fetched by the early-exit guard above)
        log.info("Found {} active FCY customer accounts for revaluation", fcyAccounts.size());

        for (CustAcctMaster acct : fcyAccounts) {
            try {
                RevaluationEntry entry = revalHelper.saveAccountRevalEntry(acct, revalDate);
                if (entry != null) {
                    entries.add(entry);
                    if (entry.getRevalDiff().compareTo(BigDecimal.ZERO) > 0) {
                        totalGain = totalGain.add(entry.getRevalDiff());
                    } else {
                        totalLoss = totalLoss.add(entry.getRevalDiff().abs());
                    }
                }
            } catch (Exception e) {
                entityManager.clear(); // FIX B: defensive session clear
                log.error("Error revaluing account {}: {}", acct.getAccountNo(), e.getMessage(), e);
            }
        }

        log.info("EOD revaluation completed: {} entries, Total Gain: {}, Total Loss: {}",
            entries.size(), totalGain, totalLoss);
        log.info("MCT Revaluation complete. Records saved: {}. Date: {}", entries.size(), revalDate);

        return RevaluationResult.builder()
            .revalDate(revalDate)
            .entriesPosted(entries.size())
            .totalGain(totalGain)
            .totalLoss(totalLoss)
            .entries(entries)
            .build();
         *
         * ══════════════════════════════════════════════════════════════════════
         * END OF ORIGINAL REVALUATION LOGIC
         * ══════════════════════════════════════════════════════════════════════
         */
    }

    // ========================================
    // BOD REVALUATION REVERSAL
    // ========================================

    /**
     * Perform Beginning-of-Day reversal of previous day revaluation entries.
     * NOT_SUPPORTED: each reversal handled by RevalTranHelper in REQUIRES_NEW.
     */
    // @Scheduled(cron = "0 0 9 * * *") // 9 AM daily - commented out for manual triggering
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void performBodRevaluationReversal() {
        LocalDate today = systemDateService.getSystemDate();
        LocalDate yesterday = today.minusDays(1);
        log.info("Starting BOD revaluation reversal for date: {}", yesterday);

        List<RevalTran> yesterdayRevals =
            revalTranRepository.findPostedRevaluationsForDate(yesterday);

        if (yesterdayRevals.isEmpty()) {
            log.info("No revaluation entries found for reversal on date: {}", yesterday);
            return;
        }

        int reversalCount = 0;
        for (RevalTran revalTran : yesterdayRevals) {
            try {
                revalHelper.processBodReversal(revalTran, today);
                reversalCount++;
                log.debug("Reversed: {} -> REV-{}", revalTran.getTranId(), revalTran.getTranId());
            } catch (Exception e) {
                log.error("Error reversing revaluation for {}: {}",
                    revalTran.getTranId(), e.getMessage(), e);
            }
        }

        log.info("BOD revaluation reversal completed: {} entries reversed", reversalCount);
    }

    // ========================================
    // QUERY
    // ========================================

    /**
     * Get revaluation summary for a specific date.
     */
    public RevaluationResult getRevaluationSummary(LocalDate revalDate) {
        List<RevalTran> revaluations =
            revalTranRepository.findByRevalDateAndStatus(revalDate, "POSTED");

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
