package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Service for Multi-Currency Transaction (MCT) operations
 * Implements PTTP05 specification:
 * - Position GL auto-posting (4 entries for FCY transactions)
 * - WAE (Weighted Average Exchange) calculation and update
 * - Settlement gain/loss calculation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MultiCurrencyTransactionService {

    private final WaeMasterRepository waeMasterRepository;
    private final TranTableRepository tranTableRepository;
    private final GLSetupRepository glSetupRepository;
    private final GLMovementRepository glMovementRepository;
    private final BalanceService balanceService;
    private final SystemDateService systemDateService;
    private final SettlementGainLossRepository settlementGainLossRepository;
    private final SettlementAlertService settlementAlertService;

    // Position GL account mapping (Currency -> GL Account)
    private static final Map<String, String> POSITION_GL_MAP = Map.of(
        "USD", "920101001",
        "EUR", "920102001",
        "GBP", "920103001",
        "JPY", "920104001"
    );

    // FX Gain/Loss GL accounts (using corrected account numbers)
    private static final String REALISED_FX_GAIN_GL = "140203001";
    private static final String UNREALISED_FX_GAIN_GL = "140203002";
    private static final String REALISED_FX_LOSS_GL = "240203001";
    private static final String UNREALISED_FX_LOSS_GL = "240203002";

    /**
     * Process multi-currency transaction posting based on transaction type
     * Called after main transaction is posted
     *
     * Implements PTTP05 MCT Patterns:
     * - Pattern 1: Customer deposits FCY (4 entries) - BUY
     * - Pattern 2: Customer withdraws FCY at WAE (4 entries) - SELL, no gain/loss
     * - Pattern 3: Customer withdraws FCY above WAE (6 entries) - SELL with GAIN
     * - Pattern 4: Customer withdraws FCY below WAE (6 entries) - SELL with LOSS
     *
     * @param transactions List of transaction lines
     * @param transactionType Type of transaction (BDT_ONLY, USD_ONLY, BDT_USD_MIX)
     */
    @Transactional
    public void processMultiCurrencyTransaction(List<TranTable> transactions,
                                               CurrencyValidationService.TransactionType transactionType) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        log.info("Processing MCT for transaction type: {}", transactionType);

        switch (transactionType) {
            case BDT_ONLY:
                // No MCT processing for BDT-only transactions
                log.debug("BDT-only transaction, skipping MCT processing");
                break;

            case USD_ONLY:
                // USD-only: Not a typical scenario, skip MCT
                log.info("USD-only transaction detected, skipping MCT processing");
                break;

            case BDT_USD_MIX:
                // BDT-USD mix: Full MCT processing
                log.info("Processing BDT-USD mixed transaction");
                processMixedCurrencyTransaction(transactions);
                break;

            default:
                log.warn("Invalid transaction type, skipping MCT processing");
                break;
        }

        log.info("MCT processing completed");
    }

    /**
     * Process mixed BDT-USD transaction with proper pattern detection
     */
    private void processMixedCurrencyTransaction(List<TranTable> transactions) {
        // Find the FCY (USD) transaction line
        TranTable fcyTransaction = transactions.stream()
            .filter(t -> "USD".equals(t.getTranCcy()))
            .findFirst()
            .orElse(null);

        if (fcyTransaction == null) {
            log.warn("No FCY transaction found in mixed currency transaction");
            return;
        }

        // Determine if this is a BUY or SELL transaction
        // BUY: Customer FCY account is CREDITED (liability increases) - Pattern 1
        // SELL: Customer FCY account is DEBITED (liability decreases) - Pattern 2, 3, 4
        boolean isBuyTransaction = fcyTransaction.getDrCrFlag() == DrCrFlag.C;

        String fcyAccount = fcyTransaction.getAccountNo();
        log.info("MCT Pattern Detection: Account={}, DrCr={}, Type={}",
            fcyAccount, fcyTransaction.getDrCrFlag(), isBuyTransaction ? "BUY" : "SELL");

        if (isBuyTransaction) {
            // Pattern 1: Customer deposits FCY (BUY)
            processBuyTransaction(fcyTransaction);
        } else {
            // Pattern 2, 3, or 4: Customer withdraws FCY (SELL)
            processSellTransaction(fcyTransaction);
        }
    }

    /**
     * Process BUY transaction (Pattern 1: Customer deposits FCY)
     * - Post 4 Position GL entries at DEAL rate
     * - NO settlement gain/loss
     * - UPDATE WAE Master
     */
    private void processBuyTransaction(TranTable transaction) {
        log.info("Processing BUY transaction (Pattern 1): {}", transaction.getTranId());

        // Step 1: Post Position GL entries at DEAL rate
        postPositionGLEntriesForBuy(transaction);

        // Step 2: Update WAE Master (only for BUY)
        updateWAEMasterForBuy(transaction);

        log.info("BUY transaction processing completed");
    }

    /**
     * Process SELL transaction (Pattern 2, 3, 4: Customer withdraws FCY)
     * - Post 4 Position GL entries at WAE rate (not deal rate!)
     * - Calculate settlement gain/loss
     * - If gain/loss exists, post 2 additional entries
     * - DO NOT update WAE Master (selling doesn't change WAE)
     */
    private void processSellTransaction(TranTable transaction) {
        log.info("Processing SELL transaction (Pattern 2/3/4): {}", transaction.getTranId());

        // Get current WAE rate
        String ccyPair = transaction.getTranCcy() + "/BDT";
        Optional<WaeMaster> waeMasterOpt = waeMasterRepository.findByCcyPair(ccyPair);

        if (waeMasterOpt.isEmpty()) {
            log.warn("WAE Master not found for {}, cannot process SELL transaction", ccyPair);
            return;
        }

        BigDecimal waeRate = waeMasterOpt.get().getWaeRate();
        BigDecimal dealRate = transaction.getExchangeRate();
        BigDecimal fcyAmt = transaction.getFcyAmt();

        // Step 1: Post Position GL entries at WAE rate (not deal rate!)
        postPositionGLEntriesForSell(transaction, waeRate);

        // Step 2: Calculate settlement gain/loss
        BigDecimal settlementGainLoss = calculateSettlementGainLoss(dealRate, waeRate, fcyAmt);

        // Step 3: If gain/loss exists, post additional entries
        if (settlementGainLoss.compareTo(BigDecimal.ZERO) != 0) {
            boolean isGain = settlementGainLoss.compareTo(BigDecimal.ZERO) > 0;

            if (isGain) {
                // Pattern 3: Withdrawal with GAIN
                log.info("Pattern 3: SELL with GAIN of {}", settlementGainLoss);
                postSettlementGain(transaction, settlementGainLoss);
            } else {
                // Pattern 4: Withdrawal with LOSS
                log.info("Pattern 4: SELL with LOSS of {}", settlementGainLoss.abs());
                postSettlementLoss(transaction, settlementGainLoss.abs());
            }
        } else {
            // Pattern 2: Withdrawal at WAE (no gain/loss)
            log.info("Pattern 2: SELL at WAE, no gain/loss");
        }

        // Step 4: DO NOT update WAE Master for SELL transactions
        log.debug("WAE Master not updated for SELL transaction (as per MCT rules)");

        log.info("SELL transaction processing completed");
    }

    /**
     * Process single FCY transaction (legacy method for backward compatibility)
     * @deprecated Use processMultiCurrencyTransaction instead
     */
    @Deprecated
    @Transactional
    public void processFCYTransaction(TranTable transaction) {
        log.warn("DEPRECATED: processFCYTransaction() called. Use processMultiCurrencyTransaction() instead.");

        // Redirect to new method - assume BDT_USD_MIX for backward compatibility
        processMultiCurrencyTransaction(
            List.of(transaction),
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );
    }

    /**
     * Post Position GL entries for BUY transaction (Pattern 1)
     * Uses DEAL rate for both Position GL entries
     *
     * Entry 3: Position GL - Credit USD (at deal rate)
     * Entry 4: Position GL - Debit BDT (at deal rate)
     */
    private void postPositionGLEntriesForBuy(TranTable transaction) {
        String positionGL = POSITION_GL_MAP.get(transaction.getTranCcy());
        if (positionGL == null) {
            log.warn("No Position GL mapping for currency: {}", transaction.getTranCcy());
            return;
        }

        GLSetup glSetup = glSetupRepository.findById(positionGL)
            .orElseThrow(() -> new RuntimeException("Position GL not found: " + positionGL));

        LocalDate tranDate = transaction.getTranDate();
        LocalDate valueDate = transaction.getValueDate();
        BigDecimal fcyAmt = transaction.getFcyAmt();
        BigDecimal dealRate = transaction.getExchangeRate();
        BigDecimal lcyAmt = transaction.getLcyAmt(); // Already calculated as FCY × Deal Rate

        String baseTranId = extractBaseTranId(transaction.getTranId());

        // Entry 3: Position GL - Credit USD (USD liability increases)
        String entryId3 = baseTranId + "-3";
        TranTable posEntry3 = TranTable.builder()
            .tranId(entryId3)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.C)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(positionGL)
            .tranCcy(transaction.getTranCcy()) // USD
            .fcyAmt(fcyAmt)
            .exchangeRate(dealRate)
            .lcyAmt(lcyAmt)
            .debitAmount(BigDecimal.ZERO)
            .creditAmount(lcyAmt)
            .narration("Position adjustment - " + transaction.getTranCcy() + " bought")
            .build();

        tranTableRepository.save(posEntry3);
        createGLMovement(posEntry3, glSetup, DrCrFlag.C, lcyAmt);

        // Entry 4: Position GL - Debit BDT (BDT asset increases)
        String entryId4 = baseTranId + "-4";
        TranTable posEntry4 = TranTable.builder()
            .tranId(entryId4)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.D)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(positionGL)
            .tranCcy("BDT")
            .fcyAmt(lcyAmt)
            .exchangeRate(BigDecimal.ONE)
            .lcyAmt(lcyAmt)
            .debitAmount(lcyAmt)
            .creditAmount(BigDecimal.ZERO)
            .narration("Position adjustment - BDT sold")
            .build();

        tranTableRepository.save(posEntry4);
        createGLMovement(posEntry4, glSetup, DrCrFlag.D, lcyAmt);

        log.info("Posted Position GL entries for BUY (Pattern 1): Entries 3-4");
    }

    /**
     * Post Position GL entries for SELL transaction (Pattern 2, 3, 4)
     * Uses WAE rate for Position GL entries (NOT deal rate!)
     *
     * Entry 3: Position GL - Debit USD (at WAE rate)
     * Entry 4: Position GL - Credit BDT (at WAE rate)
     */
    private void postPositionGLEntriesForSell(TranTable transaction, BigDecimal waeRate) {
        String positionGL = POSITION_GL_MAP.get(transaction.getTranCcy());
        if (positionGL == null) {
            log.warn("No Position GL mapping for currency: {}", transaction.getTranCcy());
            return;
        }

        GLSetup glSetup = glSetupRepository.findById(positionGL)
            .orElseThrow(() -> new RuntimeException("Position GL not found: " + positionGL));

        LocalDate tranDate = transaction.getTranDate();
        LocalDate valueDate = transaction.getValueDate();
        BigDecimal fcyAmt = transaction.getFcyAmt();
        BigDecimal lcyAmtAtWAE = fcyAmt.multiply(waeRate).setScale(2, RoundingMode.HALF_UP);

        String baseTranId = extractBaseTranId(transaction.getTranId());

        // Entry 3: Position GL - Debit USD (at WAE rate, not deal rate!)
        String entryId3 = baseTranId + "-3";
        TranTable posEntry3 = TranTable.builder()
            .tranId(entryId3)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.D)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(positionGL)
            .tranCcy(transaction.getTranCcy()) // USD
            .fcyAmt(fcyAmt)
            .exchangeRate(waeRate) // Use WAE, not deal rate!
            .lcyAmt(lcyAmtAtWAE)
            .debitAmount(lcyAmtAtWAE)
            .creditAmount(BigDecimal.ZERO)
            .narration("Position adjustment - " + transaction.getTranCcy() + " sold (at WAE)")
            .build();

        tranTableRepository.save(posEntry3);
        createGLMovement(posEntry3, glSetup, DrCrFlag.D, lcyAmtAtWAE);

        // Entry 4: Position GL - Credit BDT (at WAE rate)
        String entryId4 = baseTranId + "-4";
        TranTable posEntry4 = TranTable.builder()
            .tranId(entryId4)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.C)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(positionGL)
            .tranCcy("BDT")
            .fcyAmt(lcyAmtAtWAE)
            .exchangeRate(BigDecimal.ONE)
            .lcyAmt(lcyAmtAtWAE)
            .debitAmount(BigDecimal.ZERO)
            .creditAmount(lcyAmtAtWAE)
            .narration("Position adjustment - BDT acquired")
            .build();

        tranTableRepository.save(posEntry4);
        createGLMovement(posEntry4, glSetup, DrCrFlag.C, lcyAmtAtWAE);

        log.info("Posted Position GL entries for SELL at WAE rate {}: Entries 3-4", waeRate);
    }

    /**
     * Calculate settlement gain/loss for SELL transactions
     * Formula: FCY_Amt × (Deal_Rate - WAE_Rate)
     *
     * @return Settlement gain/loss amount (positive = gain, negative = loss)
     */
    private BigDecimal calculateSettlementGainLoss(BigDecimal dealRate, BigDecimal waeRate, BigDecimal fcyAmt) {
        // Gain/Loss = FCY_Amt × (Deal_Rate - WAE_Rate)
        BigDecimal rateDiff = dealRate.subtract(waeRate);
        BigDecimal settlementGainLoss = fcyAmt.multiply(rateDiff)
            .setScale(2, RoundingMode.HALF_UP);

        log.debug("Settlement calculation: Deal={}, WAE={}, FCY={}, Result={}",
            dealRate, waeRate, fcyAmt, settlementGainLoss);

        return settlementGainLoss;
    }

    /**
     * Post settlement GAIN entries (Pattern 3)
     *
     * Entry 5: Position GL - Debit BDT (settlement gain adjustment)
     * Entry 6: Realized Gain GL - Credit BDT
     */
    private void postSettlementGain(TranTable transaction, BigDecimal gainAmount) {
        String positionGL = POSITION_GL_MAP.get(transaction.getTranCcy());
        GLSetup positionGLSetup = glSetupRepository.findById(positionGL)
            .orElseThrow(() -> new RuntimeException("Position GL not found: " + positionGL));
        GLSetup gainGLSetup = glSetupRepository.findById(REALISED_FX_GAIN_GL)
            .orElseThrow(() -> new RuntimeException("Realized Gain GL not found"));

        LocalDate tranDate = transaction.getTranDate();
        LocalDate valueDate = transaction.getValueDate();
        String baseTranId = extractBaseTranId(transaction.getTranId());

        // Entry 5: Position GL - Debit BDT
        String entryId5 = baseTranId + "-5";
        TranTable gainEntry5 = TranTable.builder()
            .tranId(entryId5)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.D)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(positionGL)
            .tranCcy("BDT")
            .fcyAmt(gainAmount)
            .exchangeRate(BigDecimal.ONE)
            .lcyAmt(gainAmount)
            .debitAmount(gainAmount)
            .creditAmount(BigDecimal.ZERO)
            .narration("Settlement gain adjustment")
            .build();

        tranTableRepository.save(gainEntry5);
        createGLMovement(gainEntry5, positionGLSetup, DrCrFlag.D, gainAmount);

        // Entry 6: Realized Gain GL - Credit BDT
        String entryId6 = baseTranId + "-6";
        TranTable gainEntry6 = TranTable.builder()
            .tranId(entryId6)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.C)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(REALISED_FX_GAIN_GL)
            .tranCcy("BDT")
            .fcyAmt(gainAmount)
            .exchangeRate(BigDecimal.ONE)
            .lcyAmt(gainAmount)
            .debitAmount(BigDecimal.ZERO)
            .creditAmount(gainAmount)
            .narration("Forex gain on settlement")
            .build();

        tranTableRepository.save(gainEntry6);
        createGLMovement(gainEntry6, gainGLSetup, DrCrFlag.C, gainAmount);

        // Save settlement gain/loss audit record
        saveSettlementAuditRecord(transaction, gainAmount, true, positionGL, entryId5, entryId6);

        log.info("Posted settlement GAIN entries (Pattern 3): Entries 5-6, Amount={}", gainAmount);
    }

    /**
     * Post settlement LOSS entries (Pattern 4)
     *
     * Entry 5: Realized Loss GL - Debit BDT
     * Entry 6: Position GL - Credit BDT (settlement loss adjustment)
     */
    private void postSettlementLoss(TranTable transaction, BigDecimal lossAmount) {
        String positionGL = POSITION_GL_MAP.get(transaction.getTranCcy());
        GLSetup positionGLSetup = glSetupRepository.findById(positionGL)
            .orElseThrow(() -> new RuntimeException("Position GL not found: " + positionGL));
        GLSetup lossGLSetup = glSetupRepository.findById(REALISED_FX_LOSS_GL)
            .orElseThrow(() -> new RuntimeException("Realized Loss GL not found"));

        LocalDate tranDate = transaction.getTranDate();
        LocalDate valueDate = transaction.getValueDate();
        String baseTranId = extractBaseTranId(transaction.getTranId());

        // Entry 5: Realized Loss GL - Debit BDT
        String entryId5 = baseTranId + "-5";
        TranTable lossEntry5 = TranTable.builder()
            .tranId(entryId5)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.D)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(REALISED_FX_LOSS_GL)
            .tranCcy("BDT")
            .fcyAmt(lossAmount)
            .exchangeRate(BigDecimal.ONE)
            .lcyAmt(lossAmount)
            .debitAmount(lossAmount)
            .creditAmount(BigDecimal.ZERO)
            .narration("Forex loss on settlement")
            .build();

        tranTableRepository.save(lossEntry5);
        createGLMovement(lossEntry5, lossGLSetup, DrCrFlag.D, lossAmount);

        // Entry 6: Position GL - Credit BDT
        String entryId6 = baseTranId + "-6";
        TranTable lossEntry6 = TranTable.builder()
            .tranId(entryId6)
            .tranDate(tranDate)
            .valueDate(valueDate)
            .drCrFlag(DrCrFlag.C)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo(positionGL)
            .tranCcy("BDT")
            .fcyAmt(lossAmount)
            .exchangeRate(BigDecimal.ONE)
            .lcyAmt(lossAmount)
            .debitAmount(BigDecimal.ZERO)
            .creditAmount(lossAmount)
            .narration("Settlement loss adjustment")
            .build();

        tranTableRepository.save(lossEntry6);
        createGLMovement(lossEntry6, positionGLSetup, DrCrFlag.C, lossAmount);

        // Save settlement gain/loss audit record
        saveSettlementAuditRecord(transaction, lossAmount, false, positionGL, entryId5, entryId6);

        log.info("Posted settlement LOSS entries (Pattern 4): Entries 5-6, Amount={}", lossAmount);
    }

    /**
     * Update WAE Master for BUY transaction ONLY
     * Formula: New_WAE_Rate = (Old_LCY + Transaction_LCY) / (Old_FCY + Transaction_FCY)
     *
     * IMPORTANT: WAE is ONLY updated for BUY transactions (deposits).
     *            SELL transactions (withdrawals) do NOT change WAE.
     */
    private void updateWAEMasterForBuy(TranTable transaction) {
        String ccyPair = transaction.getTranCcy() + "/BDT";
        BigDecimal fcyAmt = transaction.getFcyAmt();
        BigDecimal lcyAmt = transaction.getLcyAmt();

        // Get or create WAE Master
        WaeMaster waeMaster = waeMasterRepository.findByCcyPair(ccyPair)
            .orElseGet(() -> {
                log.info("Creating new WAE Master for currency pair: {}", ccyPair);
                return WaeMaster.builder()
                    .ccyPair(ccyPair)
                    .waeRate(BigDecimal.ZERO)
                    .fcyBalance(BigDecimal.ZERO)
                    .lcyBalance(BigDecimal.ZERO)
                    .sourceGl(POSITION_GL_MAP.get(transaction.getTranCcy()))
                    .build();
            });

        // Get current balances
        BigDecimal oldFcyBalance = waeMaster.getFcyBalance();
        BigDecimal oldLcyBalance = waeMaster.getLcyBalance();

        // BUY transaction: Add to balances
        BigDecimal newFcyBalance = oldFcyBalance.add(fcyAmt);
        BigDecimal newLcyBalance = oldLcyBalance.add(lcyAmt);

        waeMaster.setFcyBalance(newFcyBalance);
        waeMaster.setLcyBalance(newLcyBalance);

        // Calculate new WAE rate
        if (newFcyBalance.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal newWaeRate = newLcyBalance.divide(newFcyBalance, 4, RoundingMode.HALF_UP);
            waeMaster.setWaeRate(newWaeRate);
            log.info("WAE updated for BUY: {} = {} (FCY: {}, LCY: {})",
                ccyPair, newWaeRate, newFcyBalance, newLcyBalance);
        } else {
            waeMaster.setWaeRate(BigDecimal.ZERO);
            log.info("WAE reset to zero for {} (FCY balance is zero)", ccyPair);
        }

        waeMasterRepository.save(waeMaster);
    }

    /**
     * Extract base transaction ID (remove line number suffix)
     * Example: "T20251123000001-1" → "T20251123000001"
     */
    private String extractBaseTranId(String fullTranId) {
        int lastDashIndex = fullTranId.lastIndexOf('-');
        return lastDashIndex > 0 ? fullTranId.substring(0, lastDashIndex) : fullTranId;
    }

    /**
     * Create GL movement record for a transaction
     */
    private void createGLMovement(TranTable transaction, GLSetup glSetup, DrCrFlag drCrFlag, BigDecimal amount) {
        // Update GL balance
        BigDecimal newBalance = balanceService.updateGLBalance(glSetup.getGlNum(), drCrFlag, amount);

        // Create GL movement
        GLMovement glMovement = GLMovement.builder()
            .transaction(transaction)
            .glSetup(glSetup)
            .drCrFlag(drCrFlag)
            .tranDate(transaction.getTranDate())
            .valueDate(transaction.getValueDate())
            .amount(amount)
            .balanceAfter(newBalance)
            .build();

        glMovementRepository.save(glMovement);
    }

    /**
     * Check if a transaction involves foreign currency
     */
    public boolean isFCYTransaction(TranTable transaction) {
        return transaction != null &&
               transaction.getTranCcy() != null &&
               !"BDT".equals(transaction.getTranCcy());
    }

    /**
     * Get current WAE rate for a currency pair
     */
    public BigDecimal getWAERate(String currency) {
        String ccyPair = currency + "/BDT";
        return waeMasterRepository.findByCcyPair(ccyPair)
            .map(WaeMaster::getWaeRate)
            .orElse(BigDecimal.ZERO);
    }

    /**
     * Get Position GL account for a currency
     */
    public Optional<String> getPositionGL(String currency) {
        return Optional.ofNullable(POSITION_GL_MAP.get(currency));
    }

    /**
     * Save settlement gain/loss audit record
     * Provides complete audit trail for all settlement calculations
     *
     * @param transaction Original transaction
     * @param amount Settlement gain or loss amount
     * @param isGain True for gain, false for loss
     * @param positionGL Position GL account number
     * @param entry5TranId Transaction ID of entry 5
     * @param entry6TranId Transaction ID of entry 6
     */
    private void saveSettlementAuditRecord(TranTable transaction, BigDecimal amount,
                                          boolean isGain, String positionGL,
                                          String entry5TranId, String entry6TranId) {
        try {
            // Get WAE rate for this transaction
            String ccyPair = transaction.getTranCcy() + "/BDT";
            BigDecimal waeRate = waeMasterRepository.findByCcyPair(ccyPair)
                .map(WaeMaster::getWaeRate)
                .orElse(BigDecimal.ZERO);

            // Build audit record
            SettlementGainLoss auditRecord = SettlementGainLoss.builder()
                .tranId(extractBaseTranId(transaction.getTranId()))
                .tranDate(transaction.getTranDate())
                .valueDate(transaction.getValueDate())
                .accountNo(transaction.getAccountNo())
                .currency(transaction.getTranCcy())
                .fcyAmt(transaction.getFcyAmt())
                .dealRate(transaction.getExchangeRate())
                .waeRate(waeRate)
                .settlementAmt(amount)
                .settlementType(isGain ? "GAIN" : "LOSS")
                .settlementGl(isGain ? REALISED_FX_GAIN_GL : REALISED_FX_LOSS_GL)
                .positionGl(positionGL)
                .entry5TranId(entry5TranId)
                .entry6TranId(entry6TranId)
                .postedBy("SYSTEM")
                .status("POSTED")
                .narration(String.format("Settlement %s: FCY %.2f × (Deal %.4f - WAE %.4f) = %.2f",
                    isGain ? "GAIN" : "LOSS",
                    transaction.getFcyAmt(),
                    transaction.getExchangeRate(),
                    waeRate,
                    amount))
                .build();

            settlementGainLossRepository.save(auditRecord);

            log.debug("Settlement audit record saved: {} - {} BDT {}",
                transaction.getTranId(), amount, isGain ? "GAIN" : "LOSS");

            // Check for alerts on large settlements
            SettlementAlertService.SettlementAlert alert = settlementAlertService.checkForAlert(auditRecord);
            if (alert != null) {
                log.warn("Settlement alert generated: {} - {} {}, Amount: {} BDT",
                    alert.getAlertId(),
                    alert.getSeverity(),
                    alert.getSettlementType(),
                    alert.getSettlementAmt());
                // Alert is logged; can be extended to send emails/notifications
            }

        } catch (Exception e) {
            log.error("Error saving settlement audit record for {}: {}",
                transaction.getTranId(), e.getMessage(), e);
            // Don't fail the transaction if audit logging fails
        }
    }
}
