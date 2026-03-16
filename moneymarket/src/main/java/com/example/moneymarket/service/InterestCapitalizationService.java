package com.example.moneymarket.service;

import com.example.moneymarket.dto.CapitalizationPreviewDTO;
import com.example.moneymarket.dto.InterestCapitalizationRequestDTO;
import com.example.moneymarket.dto.InterestCapitalizationResponseDTO;
import com.example.moneymarket.entity.*;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Service for Interest Capitalization
 * Handles manual posting of accrued interest to customer accounts.
 *
 * FCY accounts use WAE (weighted average exchange rate from pending accruals) for the
 * interest-expense debit leg, and MID rate for the customer-account credit leg.
 * When WAE != MID a gain/loss GL entry is created to keep LCY balanced.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterestCapitalizationService {

    private static final String FX_GAIN_GL = "140203002";
    private static final String FX_LOSS_GL = "240203002";

    private final CustAcctMasterRepository custAcctMasterRepository;
    private final AcctBalAccrualRepository acctBalAccrualRepository;
    private final AcctBalRepository acctBalRepository;
    private final TranTableRepository tranTableRepository;
    private final InttAccrTranRepository inttAccrTranRepository;
    private final SubProdMasterRepository subProdMasterRepository;
    private final SystemDateService systemDateService;
    private final TransactionHistoryService transactionHistoryService;
    private final ExchangeRateService exchangeRateService;
    private final Random random = new Random();

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Process interest capitalization for an account.
     */
    @Transactional
    public InterestCapitalizationResponseDTO capitalizeInterest(InterestCapitalizationRequestDTO request) {
        String accountNo = request.getAccountNo();

        log.info("========================================");
        log.info("=== INTEREST CAPITALIZATION STARTED ===");
        log.info("========================================");
        log.info("Account Number: {}", accountNo);
        log.info("Narration: {}", request.getNarration());

        // 1. Fetch and validate account
        CustAcctMaster account = custAcctMasterRepository.findById(accountNo)
                .orElseThrow(() -> new BusinessException("Account not found: " + accountNo));

        // 2. Validate account is interest-bearing
        validateInterestBearing(account);

        // 3. Get system date
        LocalDate systemDate = systemDateService.getSystemDate();
        log.info("System Date: {}", systemDate);

        // 4. Validate no duplicate payment
        validateNoDuplicatePayment(account, systemDate);

        // 5. Get accrued interest balance (FCY amount for FCY accounts)
        BigDecimal accruedBalance = getAccruedBalance(accountNo);

        // 6. Validate accrued balance > 0
        validateAccruedBalance(accruedBalance);

        // 7. Get current balance (before capitalization)
        BigDecimal currentBalance = getCurrentBalance(accountNo, systemDate);
        BigDecimal oldBalance = currentBalance;

        // 8. Determine currency and exchange rates
        String ccy = account.getAccountCcy() != null ? account.getAccountCcy() : "BDT";
        boolean isFcy = !"BDT".equals(ccy);

        BigDecimal wae = BigDecimal.ONE;
        BigDecimal midRate = BigDecimal.ONE;

        if (isFcy) {
            // Pass null lastCapDate so ALL historical S-type entries are used for WAE.
            // Date-filtered lookup fails when EOD hasn't yet created entries for today.
            wae = calculateAccrualWae(accountNo, ccy, null);
            if (wae == null || wae.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("WAE not found for {} {} - using 1.0", accountNo, ccy);
                wae = BigDecimal.ONE;
            }
            midRate = resolveMidRate(ccy, systemDate);
            log.info("FCY Capitalization — CCY={} WAE={} MID={} FCY={}",
                    ccy, wae, midRate, accruedBalance);
        }

        // 9. Calculate new balance
        BigDecimal newBalance = oldBalance.add(accruedBalance);

        // 10. Generate transaction ID with 'C' prefix
        String transactionId = generateCapitalizationTransactionId(systemDate);

        // 11. Create debit entry in Intt_Accr_Tran (Interest Expense) at WAE
        createDebitEntry(account, transactionId, systemDate, accruedBalance, wae, request.getNarration());

        // 12. Create credit entry in Tran_Table (Customer Account) at MID
        createCreditEntry(account, transactionId, systemDate, accruedBalance, midRate, request.getNarration());

        // 13. For FCY accounts: create gain/loss GL leg if WAE != MID, then validate balance
        if (isFcy) {
            // Pre-compute each leg's rounded LCY using the SAME formula as the entry creators.
            // Gain/loss = difference of already-rounded amounts — avoids 0.01 drift from
            // computing (WAE-MID)×FCY independently.
            BigDecimal drLcy = accruedBalance.multiply(wae).setScale(2, RoundingMode.HALF_UP);
            BigDecimal crLcy = accruedBalance.multiply(midRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal gainLossLcy = drLcy.subtract(crLcy).abs();
            boolean isGain = drLcy.compareTo(crLcy) > 0;
            createGainLossEntryIfNeeded(transactionId, systemDate, gainLossLcy, isGain);
            validateLcyBalance(drLcy, crLcy, gainLossLcy);
        }

        // 14. Update account: balance and last interest payment date
        updateAccountAfterCapitalization(accountNo, systemDate, accruedBalance, wae, ccy);

        // 15. Update account entity's last interest payment date
        account.setLastInterestPaymentDate(systemDate);
        custAcctMasterRepository.save(account);

        log.info("Interest capitalization completed for account: {}. Transaction ID: {}", accountNo, transactionId);

        return InterestCapitalizationResponseDTO.builder()
                .accountNo(accountNo)
                .accountName(account.getAcctName())
                .oldBalance(oldBalance)
                .accruedInterest(accruedBalance)
                .newBalance(newBalance)
                .transactionId(transactionId)
                .capitalizationDate(systemDate)
                .message("Interest capitalization successful")
                .build();
    }

    /**
     * Returns a preview of accrued FCY/LCY amounts, WAE, mid rate, and estimated gain/loss.
     * Used by the frontend to display FCY breakdown before the user confirms capitalization.
     * 
     * CRITICAL: All values read from acct_bal_accrual table (single source of truth).
     * This table is populated during EOD from intt_accr_tran sums.
     */
    public CapitalizationPreviewDTO getCapitalizationPreview(String accountNo) {
        CustAcctMaster account = custAcctMasterRepository.findById(accountNo)
                .orElseThrow(() -> new BusinessException("Account not found: " + accountNo));

        String ccy = account.getAccountCcy() != null ? account.getAccountCcy() : "BDT";
        LocalDate systemDate = systemDateService.getSystemDate();

        // Read all accrued values from acct_bal_accrual (single source of truth)
        Optional<AcctBalAccrual> accrualOpt = acctBalAccrualRepository.findLatestByAccountNo(accountNo);
        
        BigDecimal totalFcy = BigDecimal.ZERO;
        BigDecimal totalLcy = BigDecimal.ZERO;
        
        if (accrualOpt.isPresent()) {
            AcctBalAccrual accrual = accrualOpt.get();
            totalFcy = accrual.getClosingBal() != null ? accrual.getClosingBal() : BigDecimal.ZERO;
            totalLcy = accrual.getLcyAmt() != null ? accrual.getLcyAmt() : BigDecimal.ZERO;
        }

        // Calculate WAE from stored values
        BigDecimal waeRate = BigDecimal.ONE;
        if (!"BDT".equals(ccy) && totalFcy.compareTo(BigDecimal.ZERO) > 0) {
            waeRate = totalLcy.divide(totalFcy, 4, RoundingMode.HALF_UP);
        }
        
        BigDecimal midRate = !"BDT".equals(ccy) ? resolveMidRate(ccy, systemDate) : BigDecimal.ONE;

        // Calculate estimated gain/loss using stored LCY vs mid rate LCY
        BigDecimal midBasedLcy = totalFcy.multiply(midRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal estimatedGainLoss = totalLcy.subtract(midBasedLcy);

        log.info("CapitalizationPreview | account={} FCY={} LCY={} WAE={} MID={} gainLoss={}",
                accountNo, totalFcy, totalLcy, waeRate, midRate, estimatedGainLoss);

        return CapitalizationPreviewDTO.builder()
                .accountNo(accountNo)
                .currency(ccy)
                .accruedFcy(totalFcy)
                .accruedLcy(totalLcy)
                .waeRate(waeRate)
                .midRate(midRate)
                .estimatedGainLoss(estimatedGainLoss)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Rate helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * WAE = SUM(lcy_amt) / SUM(fcy_amt) across all S-type credit accrual entries
     * created AFTER the last capitalization date (or all entries if never capitalized).
     *
     * S-type records start as Pending and become Posted after EOD Job 3.
     * We filter by tranDate > lastCapDate to capture the un-capitalized period.
     * Returns 1.0 for BDT or when no qualifying records exist.
     */
    private BigDecimal calculateAccrualWae(String acctNum, String ccy, LocalDate lastCapDate) {
        if ("BDT".equals(ccy)) return BigDecimal.ONE;

        List<InttAccrTran> allCreditAccruals = inttAccrTranRepository
                .findCreditAccrualsByAccountAndCcy(acctNum, ccy);

        if (allCreditAccruals == null || allCreditAccruals.isEmpty()) return BigDecimal.ONE;

        // Include only entries AFTER the last capitalization date
        List<InttAccrTran> periodAccruals = allCreditAccruals.stream()
                .filter(a -> lastCapDate == null
                        || a.getTranDate() == null
                        || a.getTranDate().isAfter(lastCapDate))
                .collect(Collectors.toList());

        if (periodAccruals.isEmpty()) return BigDecimal.ONE;

        BigDecimal totalFcy = periodAccruals.stream()
                .map(InttAccrTran::getFcyAmt)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalLcy = periodAccruals.stream()
                .map(InttAccrTran::getLcyAmt)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalFcy.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE;

        BigDecimal wae = totalLcy.divide(totalFcy, 4, RoundingMode.HALF_UP);
        log.info("Accrual WAE for account {} ({}) since {}: totalFcy={} totalLcy={} WAE={}",
                acctNum, ccy, lastCapDate, totalFcy, totalLcy, wae);
        return wae;
    }

    /**
     * Resolve mid rate for the given currency on the given date, with fallback to latest rate.
     */
    private BigDecimal resolveMidRate(String ccy, LocalDate date) {
        try {
            return exchangeRateService.getExchangeRate(ccy, date);
        } catch (Exception e) {
            log.warn("No mid rate for {} on {}; using latest available rate", ccy, date);
            BigDecimal latest = exchangeRateService.getLatestMidRate(ccy);
            if (latest == null) {
                throw new BusinessException("No exchange rate available for " + ccy);
            }
            return latest;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Validation helpers
    // ─────────────────────────────────────────────────────────────

    private void validateInterestBearing(CustAcctMaster account) {
        SubProdMaster subProduct = account.getSubProduct();
        ProdMaster product = subProduct.getProduct();
        Boolean isInterestBearing = product.getInterestBearingFlag();
        if (isInterestBearing == null || !isInterestBearing) {
            throw new BusinessException("The account is Non-Interest bearing");
        }
    }

    private void validateNoDuplicatePayment(CustAcctMaster account, LocalDate systemDate) {
        LocalDate lastPaymentDate = account.getLastInterestPaymentDate();
        if (lastPaymentDate != null && !lastPaymentDate.isBefore(systemDate)) {
            throw new BusinessException("Interest has already been capitalized");
        }
    }

    private void validateAccruedBalance(BigDecimal accruedBalance) {
        if (accruedBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("There is no accrued interest");
        }
    }

    /**
     * Validates that total DR LCY == total CR LCY across all legs (with 0.01 tolerance).
     * Because gainLossLcy = |drLcy - crLcy|, balance holds by construction.
     * Tolerance guards against any unexpected external deviation.
     *
     * @param drLcy       rounded expense LCY (FCY × WAE)
     * @param crLcy       rounded account LCY (FCY × MID)
     * @param gainLossLcy |drLcy - crLcy| — the exact gain/loss entry amount
     */
    private void validateLcyBalance(BigDecimal drLcy, BigDecimal crLcy, BigDecimal gainLossLcy) {
        BigDecimal totalDrLcy, totalCrLcy;
        if (drLcy.compareTo(crLcy) >= 0) {
            // GAIN: DR=expense; CR=account+gain
            totalDrLcy = drLcy;
            totalCrLcy = crLcy.add(gainLossLcy);
        } else {
            // LOSS: DR=expense+loss; CR=account
            totalDrLcy = drLcy.add(gainLossLcy);
            totalCrLcy = crLcy;
        }

        BigDecimal diff = totalDrLcy.subtract(totalCrLcy).abs();
        BigDecimal tolerance = new BigDecimal("0.01");
        if (diff.compareTo(tolerance) > 0) {
            log.error("Capitalization LCY imbalance: DR={} CR={} diff={}", totalDrLcy, totalCrLcy, diff);
            throw new RuntimeException("LCY imbalance on capitalization: DR=" + totalDrLcy + " CR=" + totalCrLcy);
        }
        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("Minor LCY rounding diff of {} BDT — within tolerance", diff);
        }
        log.info("LCY balance validated: DR={} CR={}", totalDrLcy, totalCrLcy);
    }

    // ─────────────────────────────────────────────────────────────
    // Balance helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Get the accrued interest balance for the account.
     * Uses closing_bal (total accumulated interest) from acct_bal_accrual.
     */
    private BigDecimal getAccruedBalance(String accountNo) {
        log.info("=== GETTING ACCRUED INTEREST BALANCE ===");

        Optional<AcctBalAccrual> acctBalAccrualOpt = acctBalAccrualRepository.findLatestByAccountNo(accountNo);

        if (acctBalAccrualOpt.isEmpty()) {
            log.warn("No accrued balance record found for account: {}", accountNo);
            return BigDecimal.ZERO;
        }

        AcctBalAccrual acctBalAccrual = acctBalAccrualOpt.get();
        BigDecimal closingBal = acctBalAccrual.getClosingBal() != null ? acctBalAccrual.getClosingBal() : BigDecimal.ZERO;
        log.info("Account: {} — Closing Balance (Total Accumulated Interest): {}", accountNo, closingBal);
        return closingBal;
    }

    /**
     * Get current (real-time) balance for the account from acct_bal.
     */
    private BigDecimal getCurrentBalance(String accountNo, LocalDate systemDate) {
        log.info("=== GETTING CURRENT BALANCE ===");

        Optional<AcctBal> balanceForSystemDate = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate);
        if (balanceForSystemDate.isPresent()) {
            log.info("Found balance record for system date {}: Balance = {}",
                    systemDate, balanceForSystemDate.get().getCurrentBalance());
            return balanceForSystemDate.get().getCurrentBalance();
        }

        log.warn("No balance record found for system date {}. Trying latest record...", systemDate);

        Optional<AcctBal> latestBalance = acctBalRepository.findLatestByAccountNo(accountNo);
        if (latestBalance.isPresent()) {
            log.info("Found latest balance record: Date = {}, Balance = {}",
                    latestBalance.get().getTranDate(), latestBalance.get().getCurrentBalance());
            return latestBalance.get().getCurrentBalance();
        }

        List<AcctBal> allBalances = acctBalRepository.findByAccountNoOrderByTranDateDesc(accountNo);
        log.error("NO balance records found for account {}. Total records in acct_bal: {}", accountNo, allBalances.size());
        return BigDecimal.ZERO;
    }

    // ─────────────────────────────────────────────────────────────
    // Transaction entry creators
    // ─────────────────────────────────────────────────────────────

    /**
     * Create debit entry in Intt_Accr_Tran (Interest Expense GL) at WAE.
     * For BDT: exchangeRate = 1.0, lcyAmt = fcyAmt (unchanged behaviour).
     * For FCY: exchangeRate = WAE, lcyAmt = fcyAmt × WAE.
     */
    private void createDebitEntry(CustAcctMaster account, String transactionId,
                                   LocalDate systemDate, BigDecimal fcyAmount,
                                   BigDecimal exchangeRate, String narration) {
        String interestExpenseGL = getInterestExpenseGL(account);
        BigDecimal lcyAmt = fcyAmount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);

        log.info("=== CREATING DEBIT ENTRY IN INTT_ACCR_TRAN ===");
        log.info("Account: {}, GL: {}, FCY: {}, Rate: {}, LCY: {}",
                account.getAccountNo(), interestExpenseGL, fcyAmount, exchangeRate, lcyAmt);

        InttAccrTran debitEntry = InttAccrTran.builder()
                .accrTranId(transactionId + "-1")
                .accountNo(account.getAccountNo())
                .accrualDate(systemDate)
                .tranDate(systemDate)
                .valueDate(systemDate)
                .drCrFlag(TranTable.DrCrFlag.D)
                .tranStatus(TranTable.TranStatus.Verified)
                .glAccountNo(interestExpenseGL)
                .tranCcy(account.getAccountCcy() != null ? account.getAccountCcy() : "BDT")
                .fcyAmt(fcyAmount)
                .exchangeRate(exchangeRate)
                .lcyAmt(lcyAmt)
                .amount(fcyAmount)
                .interestRate(account.getSubProduct().getEffectiveInterestRate() != null ?
                              account.getSubProduct().getEffectiveInterestRate() : BigDecimal.ZERO)
                .status(InttAccrTran.AccrualStatus.Pending)
                .narration(narration != null ? narration : "Interest Capitalization - Expense")
                .udf1("Frontend_user")
                .build();

        inttAccrTranRepository.save(debitEntry);
        log.info("Debit entry saved: {}", transactionId + "-1");
    }

    /**
     * Create credit entry in Tran_Table (Customer Account) at MID rate.
     * For BDT: exchangeRate = 1.0, lcyAmt = fcyAmt (unchanged behaviour).
     * For FCY: exchangeRate = MID, lcyAmt = fcyAmt × MID.
     */
    private void createCreditEntry(CustAcctMaster account, String transactionId,
                                    LocalDate systemDate, BigDecimal fcyAmount,
                                    BigDecimal exchangeRate, String narration) {
        BigDecimal lcyAmt = fcyAmount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);

        log.info("=== CREATING CREDIT ENTRY IN TRAN_TABLE ===");
        log.info("Account: {}, FCY: {}, Rate: {}, LCY: {}",
                account.getAccountNo(), fcyAmount, exchangeRate, lcyAmt);

        TranTable creditEntry = TranTable.builder()
                .tranId(transactionId + "-2")
                .tranDate(systemDate)
                .valueDate(systemDate)
                .drCrFlag(TranTable.DrCrFlag.C)
                .tranStatus(TranTable.TranStatus.Verified)
                .accountNo(account.getAccountNo())
                .glNum(account.getGlNum())            // FIX 1: populate GL number from cust_acct_master
                .tranCcy(account.getAccountCcy() != null ? account.getAccountCcy() : "BDT")
                .fcyAmt(fcyAmount)
                .exchangeRate(exchangeRate)
                .lcyAmt(lcyAmt)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(lcyAmt)                  // FIX 2: LCY amount (not FCY) for acc_bal_lcy crSummationLcy
                .narration(narration != null ? narration : "Interest Capitalization - Credit")
                .udf1("Frontend_user")
                .build();

        tranTableRepository.save(creditEntry);
        log.info("Credit entry saved: {}", transactionId + "-2");

        try {
            transactionHistoryService.createTransactionHistory(creditEntry, "SYSTEM");
            log.info("Transaction history created for: {}", transactionId + "-2");
        } catch (Exception e) {
            log.error("Failed to create transaction history for {}: {}", transactionId + "-2", e.getMessage(), e);
        }
    }

    /**
     * Create FX gain or loss GL entry in Tran_Table when WAE != MID.
     *   WAE > MID (isGain=true)  →  GAIN  →  CR  GL 140203002
     *   WAE < MID (isGain=false) →  LOSS  →  DR  GL 240203002
     *
     * @param gainLossLcy pre-computed as |drLcy - crLcy| (difference of already-rounded leg amounts)
     * @param isGain      true when DR leg LCY > CR leg LCY (WAE > MID)
     */
    private void createGainLossEntryIfNeeded(String transactionId, LocalDate systemDate,
                                              BigDecimal gainLossLcy, boolean isGain) {
        if (gainLossLcy.compareTo(BigDecimal.ZERO) == 0) {
            log.info("WAE == MID — no gain/loss entry needed");
            return;
        }

        String glNum = isGain ? FX_GAIN_GL : FX_LOSS_GL;
        TranTable.DrCrFlag drCrFlag = isGain ? TranTable.DrCrFlag.C : TranTable.DrCrFlag.D;
        String desc = isGain ? "FX Gain on Interest Capitalization" : "FX Loss on Interest Capitalization";

        log.info("Creating FX {} entry: GL={} LCY={}", isGain ? "Gain" : "Loss", glNum, gainLossLcy);

        TranTable gainLossEntry = TranTable.builder()
                .tranId(transactionId + "-3")
                .tranDate(systemDate)
                .valueDate(systemDate)
                .drCrFlag(drCrFlag)
                .tranStatus(TranTable.TranStatus.Verified)
                .accountNo(null)
                .glNum(glNum)
                .tranCcy("BDT")
                .fcyAmt(gainLossLcy)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(gainLossLcy)
                .debitAmount(isGain ? BigDecimal.ZERO : gainLossLcy)
                .creditAmount(isGain ? gainLossLcy : BigDecimal.ZERO)
                .narration(desc)
                .udf1("Frontend_user")
                .build();

        tranTableRepository.save(gainLossEntry);
        log.info("FX {} entry saved: {}", isGain ? "Gain" : "Loss", transactionId + "-3");
    }

    // ─────────────────────────────────────────────────────────────
    // Account balance update
    // ─────────────────────────────────────────────────────────────

    /**
     * Update account balance after capitalization.
     * For FCY accounts: also recalculates WAE on the acc_bal record.
     * BDT path: unchanged behaviour (add accruedInterest to currentBalance).
     */
    private void updateAccountAfterCapitalization(String accountNo, LocalDate systemDate,
                                                   BigDecimal accruedInterest, BigDecimal wae, String ccy) {
        log.info("=== UPDATING ACCOUNT BALANCE ===");
        log.info("Account: {}, CCY: {}, Amount: {}, WAE: {}", accountNo, ccy, accruedInterest, wae);

        Optional<AcctBal> acctBalOpt = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate)
                .or(() -> acctBalRepository.findLatestByAccountNo(accountNo));

        if (acctBalOpt.isEmpty()) {
            List<AcctBal> allBalances = acctBalRepository.findByAccountNoOrderByTranDateDesc(accountNo);
            throw new BusinessException(String.format(
                "Account balance record not found for system date. Account: %s, System Date: %s. Available dates: %s",
                accountNo, systemDate,
                allBalances.isEmpty() ? "NONE" :
                    allBalances.stream().map(AcctBal::getTranDate).map(Object::toString)
                               .collect(Collectors.joining(", "))
            ));
        }

        AcctBal acctBal = acctBalOpt.get();
        BigDecimal oldFcyBalance = acctBal.getCurrentBalance();
        BigDecimal newFcyBalance = oldFcyBalance.add(accruedInterest);

        acctBal.setCurrentBalance(newFcyBalance);
        acctBal.setAvailableBalance(newFcyBalance);
        acctBal.setLastUpdated(systemDateService.getSystemDateTime());

        // For FCY accounts: recalculate WAE on acc_bal
        if (!"BDT".equals(ccy)) {
            BigDecimal oldWae = acctBal.getWaeRate() != null ? acctBal.getWaeRate() : wae;
            // Derive LCY balances from WAE
            BigDecimal oldLcyBalance = oldFcyBalance.multiply(oldWae).setScale(2, RoundingMode.HALF_UP);
            BigDecimal capitalizationLcy = accruedInterest.multiply(wae).setScale(2, RoundingMode.HALF_UP);
            BigDecimal newLcyBalance = oldLcyBalance.add(capitalizationLcy);

            if (newFcyBalance.abs().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newWae = newLcyBalance.divide(newFcyBalance.abs(), 4, RoundingMode.HALF_UP);
                acctBal.setWaeRate(newWae);
                log.info("WAE updated: old={} new={} (FCY: {}→{}, LCY: {}→{})",
                        oldWae, newWae, oldFcyBalance, newFcyBalance, oldLcyBalance, newLcyBalance);
            }
        }

        acctBalRepository.save(acctBal);
        log.info("Account balance updated: {} + {} = {}", oldFcyBalance, accruedInterest, newFcyBalance);

        log.info("=== ACCT_BAL_ACCRUAL UPDATE DEFERRED TO EOD ===");
        log.info("EOD Batch Job 6 will process the capitalization debit and reduce closing_bal by {}", accruedInterest);
    }

    // ─────────────────────────────────────────────────────────────
    // ID generation
    // ─────────────────────────────────────────────────────────────

    /**
     * Generate transaction ID with 'C' prefix for capitalization.
     * Format: C + yyyyMMdd + 6-digit-sequence + 3-digit-random (18 characters)
     */
    private String generateCapitalizationTransactionId(LocalDate systemDate) {
        String date = systemDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long sequenceNumber = tranTableRepository.countByTranDateAndTranIdStartingWith(systemDate, "C") + 1;
        String sequenceComponent = String.format("%06d", sequenceNumber);
        String randomPart = String.format("%03d", random.nextInt(1000));
        return "C" + date + sequenceComponent + randomPart;
    }

    // ─────────────────────────────────────────────────────────────
    // GL account lookup
    // ─────────────────────────────────────────────────────────────

    private String getInterestExpenseGL(CustAcctMaster account) {
        SubProdMaster subProduct = account.getSubProduct();
        String glNum = account.getGlNum();
        boolean isAssetAccount = glNum != null && glNum.startsWith("2");
        return isAssetAccount
                ? subProduct.getInterestReceivableExpenditureGLNum()
                : subProduct.getInterestIncomePayableGLNum();
    }
}
