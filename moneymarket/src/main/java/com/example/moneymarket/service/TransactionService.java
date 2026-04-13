package com.example.moneymarket.service;

import com.example.moneymarket.dto.TransactionLineDTO;
import com.example.moneymarket.dto.TransactionLineResponseDTO;
import com.example.moneymarket.dto.TransactionRequestDTO;
import com.example.moneymarket.dto.TransactionResponseDTO;
import com.example.moneymarket.entity.AcctBal;
import com.example.moneymarket.entity.AcctBalLcy;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.GLMovement;
import com.example.moneymarket.entity.GLSetup;
import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.exception.BODNotExecutedException;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.exception.ResourceNotFoundException;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.DealScheduleRepository;
import com.example.moneymarket.repository.AcctBalLcyRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.GLMovementRepository;
import com.example.moneymarket.repository.GLSetupRepository;
import com.example.moneymarket.repository.TranTableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for transaction operations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TranTableRepository tranTableRepository;
    private final AcctBalRepository acctBalRepository;
    private final AcctBalLcyRepository acctBalLcyRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final GLMovementRepository glMovementRepository;
    private final GLSetupRepository glSetupRepository;
    private final BalanceService balanceService;
    private final TransactionValidationService validationService;
    private final SystemDateService systemDateService;
    private final UnifiedAccountService unifiedAccountService;
    private final TransactionHistoryService transactionHistoryService;
    private final ValueDateValidationService valueDateValidationService;
    private final ValueDateCalculationService valueDateCalculationService;
    private final ValueDatePostingService valueDatePostingService;
    private final MultiCurrencyTransactionService multiCurrencyTransactionService;
    private final CurrencyValidationService currencyValidationService;
    private final ExchangeRateService exchangeRateService;
    private final FxConversionService fxConversionService;
    private final DealScheduleRepository dealScheduleRepository;
    private final BODSchedulerService bodSchedulerService;

    private final Random random = new Random();

    /** FX Gain/Loss GL for FCY-FCY settlement (config: 140203002, 240203002) */
    private static final String FX_GAIN_GL = "140203002";
    private static final String FX_LOSS_GL = "240203002";
    private static final String POSITION_GL_USD = "920101001";

    /**
     * Create a new transaction with Entry status (Maker-Checker workflow)
     * 
     * @param transactionRequestDTO The transaction data
     * @return The created transaction response
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransactionResponseDTO createTransaction(TransactionRequestDTO transactionRequestDTO) {
        // Block if there are deal schedules for today AND BOD has not been executed yet.
        // Use the BOD execution log (not PENDING count) so that successfully-executed BOD
        // unblocks transactions even when some schedules failed.
        LocalDate today = systemDateService.getSystemDate();
        long schedulesForToday = dealScheduleRepository.countByScheduleDate(today);
        if (schedulesForToday > 0 && !bodSchedulerService.isBodExecutedForDate(today)) {
            long pendingCount = dealScheduleRepository.countByScheduleDateAndStatus(today, "PENDING");
            throw new BODNotExecutedException((int) pendingCount, today);
        }

        // Validate transaction balance
        validateTransactionBalance(transactionRequestDTO);

        // Generate a transaction ID
        String tranId = generateTransactionId();
        LocalDate tranDate = systemDateService.getSystemDate();
        LocalDate valueDate = transactionRequestDTO.getValueDate();

        // Validate value date
        valueDateValidationService.validateValueDate(valueDate);
        
        List<TranTable> transactions = new ArrayList<>();
        
        String transactionNarration = transactionRequestDTO.getNarration();
        if ((transactionNarration == null || transactionNarration.isBlank()) && !transactionRequestDTO.getLines().isEmpty()) {
            transactionNarration = transactionRequestDTO.getLines().get(0).getUdf1();
        }

        boolean isSettlement = isSettlementTransaction(transactionRequestDTO.getLines());

        // Mid rate for FCY: used for non-settlement sides (Liability CR, Asset DR) and for Asset DR + Liability CR (both legs)
        BigDecimal midRateFcy = null;
        String fcyCurrency = transactionRequestDTO.getLines().stream()
                .map(TransactionLineDTO::getTranCcy)
                .filter(ccy -> ccy != null && !"BDT".equals(ccy))
                .findFirst()
                .orElse(null);
        if (fcyCurrency != null) {
            try {
                midRateFcy = exchangeRateService.getExchangeRate(fcyCurrency, tranDate);
            } catch (BusinessException e) {
                midRateFcy = exchangeRateService.getLatestMidRate(fcyCurrency);
            }
        }

        // Process each transaction line - create in Entry status
        int lineNumber = 1;
        for (TransactionLineDTO lineDTO : transactionRequestDTO.getLines()) {
            // Validate account exists (customer or office account)
            if (!unifiedAccountService.accountExists(lineDTO.getAccountNo())) {
                throw new ResourceNotFoundException("Account", "Account Number", lineDTO.getAccountNo());
            }
            
            // Get account info for GL number and for settlement rate selection
            UnifiedAccountService.AccountInfo accountInfo = unifiedAccountService.getAccountInfo(lineDTO.getAccountNo());
            
            // ✅ FIX ISSUE 4: Validate transaction currency matches account currency
            String accountCurrency = unifiedAccountService.getAccountCurrency(lineDTO.getAccountNo());
            String tranCurrency = lineDTO.getTranCcy();
            
            if (!accountCurrency.equals(tranCurrency)) {
                throw new BusinessException(String.format(
                    "Currency mismatch for account %s. Account currency: %s, Transaction currency: %s. " +
                    "Transactions must be in the account's currency.",
                    lineDTO.getAccountNo(), accountCurrency, tranCurrency
                ));
            }
            
            // For USD accounts, validate using FCY amount; for BDT accounts, validate using LCY amount
            BigDecimal validationAmount;
            if ("USD".equals(accountCurrency)) {
                validationAmount = lineDTO.getFcyAmt();
                log.debug("USD account {} - validating with FCY amount: {}", lineDTO.getAccountNo(), validationAmount);
            } else {
                validationAmount = lineDTO.getLcyAmt();
                log.debug("BDT account {} - validating with LCY amount: {}", lineDTO.getAccountNo(), validationAmount);
            }
            
            // Validate transaction with correct amount (settlement: still validate FCY amount vs balance)
            try {
                validationService.validateTransaction(
                        lineDTO.getAccountNo(), lineDTO.getDrCrFlag(), validationAmount);
            } catch (BusinessException e) {
                throw new BusinessException("Transaction validation failed for account " +
                        lineDTO.getAccountNo() + ": " + e.getMessage());
            }
            
            // Exchange rate and LCY per leg — resolved INDEPENDENTLY for each leg:
            //   Settlement trigger (Liability DR, Asset CR) → WAE of THIS account from acc_bal
            //   Non-settlement side (Liability CR, Asset DR, or Asset DR + Liability CR) → MID rate
            BigDecimal lcyAmt = lineDTO.getLcyAmt();
            BigDecimal exchangeRate = lineDTO.getExchangeRate();
            boolean isFcy = lineDTO.getTranCcy() != null && !"BDT".equals(lineDTO.getTranCcy());
            if (isFcy && midRateFcy != null) {
                boolean liability = accountInfo.isLiabilityAccount();
                boolean asset = accountInfo.isAssetAccount();
                boolean isDr = lineDTO.getDrCrFlag() == DrCrFlag.D;
                boolean isCr = lineDTO.getDrCrFlag() == DrCrFlag.C;

                // Each leg resolves its OWN rate from its OWN account — rates are NEVER shared between legs.
                // Settlement trigger = Liability DR or Asset CR → WAE from acc_bal.WAE_Rate for THIS account.
                // All other combinations → MID rate.
                boolean isSettlementTrigger = isSettlement && ((liability && isDr) || (asset && isCr));
                
                log.info("RATE SELECTION for account {}: isSettlement={}, liability={}, asset={}, isDr={}, isCr={}, isSettlementTrigger={}",
                        lineDTO.getAccountNo(), isSettlement, liability, asset, isDr, isCr, isSettlementTrigger);

                if (isSettlementTrigger) {
                    // Read WAE for this leg's account with enhanced diagnostics
                    log.info("SETTLEMENT TRIGGER: account {} is {} {}, will use WAE",
                            lineDTO.getAccountNo(),
                            liability ? "Liability" : "Asset",
                            isDr ? "DR" : "CR");
                    
                    // ALWAYS calculate WAE live from acc_bal + acct_bal_lcy balances
                    // DO NOT use stored wae_rate column (can be stale)
                    log.info("Calculating live WAE from balance fields for account {}", lineDTO.getAccountNo());
                    BigDecimal lineWae = calculateWaeWithDiagnostics(lineDTO.getAccountNo(), lineDTO.getTranCcy(), tranDate);

                    // Intraday: acct_bal_lcy can exist for today but LCY columns stay zero until EOD, so backend WAE is 0/null
                    // while the UI already computed WAE from live tran_table (same strategy as BalanceService). Use the
                    // rate submitted on the line (exchangeRate is WAE for settlement legs in TransactionForm).
                    if (lineWae == null || lineWae.compareTo(BigDecimal.ZERO) == 0) {
                        BigDecimal requestRate = lineDTO.getExchangeRate();
                        if (requestRate != null && requestRate.compareTo(BigDecimal.ZERO) > 0) {
                            lineWae = requestRate;
                            log.info("WAE INTRADAY FALLBACK: using client-provided rate {} for settlement account {} (backend WAE null/zero)",
                                    lineWae, lineDTO.getAccountNo());
                        }
                    }

                    if (lineWae == null || lineWae.compareTo(BigDecimal.ZERO) == 0) {
                        throw new BusinessException(
                            "WAE not available for settlement account " + lineDTO.getAccountNo() +
                            ". The account must have an FCY balance with a known LCY cost basis. " +
                            "Check that acct_bal_lcy table has a record for this account.");
                    }
                    exchangeRate = lineWae;
                    lcyAmt = lineDTO.getFcyAmt().multiply(lineWae).setScale(2, RoundingMode.HALF_UP);
                    log.info("Rate assignment: account {} ({} {}) → WAE={} (settlement trigger), LCY={}",
                            lineDTO.getAccountNo(), liability ? "Liability" : "Asset",
                            isDr ? "DR" : "CR", exchangeRate, lcyAmt);
                } else {
                    exchangeRate = midRateFcy;
                    lcyAmt = lineDTO.getFcyAmt().multiply(midRateFcy).setScale(2, RoundingMode.HALF_UP);
                    log.info("Rate assignment: account {} ({} {}) → MID={} (non-settlement side), LCY={}",
                            lineDTO.getAccountNo(), liability ? "Liability" : "Asset",
                            isDr ? "DR" : "CR", exchangeRate, lcyAmt);
                }
            }
            
            // Resolve GL_Num at insert time (avoids master-table join in EOD Step 4)
            String lineGlNum = resolveGlNumForAccount(lineDTO.getAccountNo());

            // Create transaction record with Entry status
            String lineId = tranId + "-" + lineNumber++;
            TranTable transaction = TranTable.builder()
                    .tranId(lineId)
                    .tranDate(tranDate)
                    .valueDate(valueDate)
                    .drCrFlag(lineDTO.getDrCrFlag())
                    .tranStatus(TranStatus.Entry)  // Initial status is Entry (Maker)
                    .accountNo(lineDTO.getAccountNo())
                    .tranCcy(lineDTO.getTranCcy())
                    .fcyAmt(lineDTO.getFcyAmt())
                    .exchangeRate(exchangeRate)
                    .lcyAmt(lcyAmt)
                    .debitAmount(lineDTO.getDrCrFlag() == DrCrFlag.D ? lcyAmt : BigDecimal.ZERO)
                    .creditAmount(lineDTO.getDrCrFlag() == DrCrFlag.C ? lcyAmt : BigDecimal.ZERO)
                    .narration(lineDTO.getUdf1())
                    .udf1(null)
                    .glNum(lineGlNum)
                    .build();
            
            transactions.add(transaction);
        }
        
        // FCY settlement: add settlement rows only when WAE != Mid and trigger rules apply (Liability Dr / Asset Cr)
        BigDecimal settlementGainLoss = BigDecimal.ZERO;
        if (isSettlement && transactions.size() == 2) {
            List<TranTable> settlementRows = buildFcySettlementRows(tranId, transactions, tranDate, valueDate);
            transactions.addAll(settlementRows);
            settlementGainLoss = settlementRows.stream()
                    .filter(t -> FX_GAIN_GL.equals(t.getGlNum()))
                    .map(TranTable::getLcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .subtract(
                            settlementRows.stream()
                                    .filter(t -> FX_LOSS_GL.equals(t.getGlNum()))
                                    .map(TranTable::getLcyAmt)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    );
        }

        // Core rule: total LCY debit must equal total LCY credit across ALL legs (gain/loss legs balance the difference)
        BigDecimal totalDrLcy = transactions.stream()
                .filter(t -> t.getDrCrFlag() == DrCrFlag.D)
                .map(t -> t.getLcyAmt() != null ? t.getLcyAmt() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCrLcy = transactions.stream()
                .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
                .map(t -> t.getLcyAmt() != null ? t.getLcyAmt() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lcyDiff = totalDrLcy.subtract(totalCrLcy).abs();
        BigDecimal tolerance = new BigDecimal("0.01");
        if (lcyDiff.compareTo(tolerance) > 0) {
            log.error("FCY settlement LCY imbalance: Total DR LCY={}, Total CR LCY={}. Transaction ID: {}", totalDrLcy, totalCrLcy, tranId);
            throw new BusinessException("Settlement gain/loss calculation error: total debit LCY (" + totalDrLcy + ") must equal total credit LCY (" + totalCrLcy + "). Please contact support.");
        }
        if (lcyDiff.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("Minor LCY rounding diff of {} BDT — within tolerance, Transaction ID: {}", lcyDiff, tranId);
        }

        // Save all transaction lines (legs + any settlement rows) in one transaction
        tranTableRepository.saveAll(transactions);

        // Create response
        String responseNarration = (transactionNarration != null && !transactionNarration.isBlank())
                ? transactionNarration
                : transactions.stream()
                        .findFirst()
                        .map(TranTable::getNarration)
                        .orElse(null);

        TransactionResponseDTO response = buildTransactionResponse(tranId, tranDate, valueDate, 
                responseNarration, transactions);
        if (settlementGainLoss.compareTo(BigDecimal.ZERO) != 0) {
            response.setSettlementGainLoss(settlementGainLoss.abs());
            response.setSettlementGainLossType(settlementGainLoss.compareTo(BigDecimal.ZERO) > 0 ? "GAIN" : "LOSS");
        }
        log.info("Transaction created with ID: {} in Entry status", tranId);
        return response;
    }

    /**
     * Post a transaction (move from Entry to Posted status)
     * This updates balances and creates GL movements
     * Enhanced with value dating logic
     *
     * @param tranId The transaction ID
     * @return The updated transaction response
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransactionResponseDTO postTransaction(String tranId) {
        // Find all transaction lines with Entry status using PK-index prefix scan.
        // Supports both multi-line (-N suffix) and standalone rows (exact match).
        List<TranTable> transactions = tranTableRepository.findByTranIdStartingWith(tranId).stream()
                .filter(t -> matchesTranId(t.getTranId(), tranId) && t.getTranStatus() == TranStatus.Entry)
                .collect(Collectors.toList());

        if (transactions.isEmpty()) {
            throw new ResourceNotFoundException("Transaction", "ID", tranId);
        }

        // Validate again before posting.
        // Settlement transactions (accountNo=null rows present) use different WAE per leg so
        // LCY totals intentionally differ. Validate FCY balance of the main legs only.
        boolean hasSettlementRows = transactions.stream().anyMatch(t -> t.getAccountNo() == null);
        if (hasSettlementRows) {
            List<TranTable> mainLegs = transactions.stream()
                    .filter(t -> t.getAccountNo() != null)
                    .collect(Collectors.toList());
            BigDecimal totalDebitFcy = mainLegs.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.D)
                    .map(TranTable::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCreditFcy = mainLegs.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
                    .map(TranTable::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalDebitFcy.compareTo(totalCreditFcy) != 0) {
                throw new BusinessException("Cannot post unbalanced transaction");
            }
        } else {
            BigDecimal totalDebit = transactions.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.D)
                    .map(TranTable::getLcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = transactions.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
                    .map(TranTable::getLcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalDebit.compareTo(totalCredit) != 0) {
                throw new BusinessException("Cannot post unbalanced transaction");
            }
        }

        // Validate all transactions again before posting using new business rules
        for (TranTable transaction : transactions) {
            // Skip validation for GL-only lines: settlement rows (accountNo=null) and GL setup rows
            if (transaction.getAccountNo() == null
                    || glSetupRepository.findById(transaction.getAccountNo()).isPresent()) {
                log.debug("Skipping account validation for GL-only line: {}", transaction.getAccountNo());
                continue;
            }
            try {
                // ✅ CRITICAL FIX: Use account currency to determine validation amount
                String accountCurrency = unifiedAccountService.getAccountCurrency(transaction.getAccountNo());
                BigDecimal validationAmount;
                
                if ("USD".equals(accountCurrency)) {
                    // USD account: validate using FCY amount
                    validationAmount = transaction.getFcyAmt();
                    log.debug("Validating USD account {} with FCY amount: {} USD", 
                            transaction.getAccountNo(), validationAmount);
                } else {
                    // BDT account: validate using LCY amount
                    validationAmount = transaction.getLcyAmt();
                    log.debug("Validating BDT account {} with LCY amount: {} BDT", 
                            transaction.getAccountNo(), validationAmount);
                }
                
                validationService.validateTransaction(
                        transaction.getAccountNo(), transaction.getDrCrFlag(), validationAmount);
            } catch (BusinessException e) {
                throw new BusinessException("Transaction validation failed for account " +
                        transaction.getAccountNo() + ": " + e.getMessage());
            }
        }

        // Determine value date classification
        TranTable firstLine = transactions.get(0);
        LocalDate valueDate = firstLine.getValueDate();
        String valueDateType = valueDateValidationService.classifyValueDate(valueDate);

        // Process based on value date type
        if ("FUTURE".equals(valueDateType)) {
            // FUTURE-DATED: Set status to Future, don't update balances
            return postFutureDatedTransaction(tranId, transactions);
        } else if ("PAST".equals(valueDateType)) {
            // PAST-DATED: Post normally + calculate and post interest adjustments
            return postPastDatedTransaction(tranId, transactions);
        } else {
            // CURRENT: Process normally
            return postCurrentTransaction(tranId, transactions);
        }
    }

    /**
     * Post a current-dated transaction (normal processing)
     */
    private TransactionResponseDTO postCurrentTransaction(String tranId, List<TranTable> transactions) {
        List<GLMovement> glMovements = new ArrayList<>();

        // Process each transaction line - update balances and create GL movements
        for (TranTable transaction : transactions) {
            // Update status to Posted
            transaction.setTranStatus(TranStatus.Posted);

            // Update FX Position for FXC transactions when posting (checker approval)
            if ("FXC".equals(transaction.getTranType())) {
                fxConversionService.updateFxPositionOnApproval(transaction);
            }

            String glNum;
            boolean isGlOnlyRow;
            if (transaction.getAccountNo() == null) {
                // Settlement row: accountNo is null, glNum is already stamped on the entity
                glNum = transaction.getGlNum();
                isGlOnlyRow = true;
            } else {
                Optional<GLSetup> glOpt = glSetupRepository.findById(transaction.getAccountNo());
                if (glOpt.isPresent()) {
                    // GL-only line (e.g. Position GL 920101001 used as accountNo)
                    glNum = transaction.getAccountNo();
                    isGlOnlyRow = true;
                } else {
                    glNum = unifiedAccountService.getGlNum(transaction.getAccountNo());
                    isGlOnlyRow = false;
                }
            }
            GLSetup glSetup = glSetupRepository.findById(glNum)
                    .orElseThrow(() -> new ResourceNotFoundException("GL", "GL Number", glNum));

            if (!isGlOnlyRow) {
                // Customer/Office account: update account balance and GL
                BigDecimal accountBalanceAmount;
                BigDecimal lcyAmountForWae;
                if ("BDT".equals(transaction.getTranCcy())) {
                    accountBalanceAmount = transaction.getLcyAmt();
                    lcyAmountForWae = null; // BDT account — no WAE update needed
                } else {
                    accountBalanceAmount = transaction.getFcyAmt();
                    lcyAmountForWae = transaction.getLcyAmt(); // FCY account — pass LCY for WAE recalculation
                }
                validationService.updateAccountBalanceForTransaction(
                        transaction.getAccountNo(), transaction.getDrCrFlag(),
                        accountBalanceAmount, lcyAmountForWae);
            }

            // Update GL balance (ALWAYS in BDT)
            BigDecimal newGLBalance = balanceService.updateGLBalance(
                    glNum, transaction.getDrCrFlag(), transaction.getLcyAmt());

            GLMovement glMovement = GLMovement.builder()
                    .transaction(transaction)
                    .glSetup(glSetup)
                    .drCrFlag(transaction.getDrCrFlag())
                    .tranDate(transaction.getTranDate())
                    .valueDate(transaction.getValueDate())
                    .amount(transaction.getLcyAmt())
                    .balanceAfter(newGLBalance)
                    .build();

            glMovements.add(glMovement);
        }

        // Validate currency combinations before processing
        currencyValidationService.validateCurrencyCombination(transactions);

        // Determine transaction type to check if Position GL should be set
        CurrencyValidationService.TransactionType transactionType =
            currencyValidationService.getTransactionType(transactions);

        // Set Pointing_Id ONLY for currency exchange transactions (BDT ↔ USD)
        if (transactionType == CurrencyValidationService.TransactionType.BDT_USD_MIX) {
            // Currency exchange detected - set Position GL on Entry 1 and Entry 2
            Integer positionGL = getPositionGLForCurrencyExchange(transactions);
            if (positionGL != null) {
                for (TranTable transaction : transactions) {
                    transaction.setPointingId(positionGL);
                }
                log.info("Position GL {} set for currency exchange transaction", positionGL);
            }
        } else {
            // Same currency transaction (BDT → BDT or USD → USD) - NO Position GL
            for (TranTable transaction : transactions) {
                transaction.setPointingId(null);
            }
            log.debug("No Position GL set for same-currency transaction ({})", transactionType);
        }

        // Save updated transaction status (with pointingId now set if applicable)
        tranTableRepository.saveAll(transactions);

        // Save all GL movements
        glMovementRepository.saveAll(glMovements);

        // Process multi-currency transactions (MCT) based on transaction type
        BigDecimal settlementGainLoss = multiCurrencyTransactionService.processMultiCurrencyTransaction(transactions, transactionType);

        TranTable firstLine = transactions.get(0);
        TransactionResponseDTO response = buildTransactionResponse(tranId, firstLine.getTranDate(),
                firstLine.getValueDate(), firstLine.getNarration(), transactions);

        if (settlementGainLoss != null && settlementGainLoss.compareTo(BigDecimal.ZERO) != 0) {
            response.setSettlementGainLoss(settlementGainLoss.abs());
            response.setSettlementGainLossType(settlementGainLoss.compareTo(BigDecimal.ZERO) > 0 ? "GAIN" : "LOSS");
        }

        log.info("Current-dated transaction posted with ID: {} (Type: {})", tranId, transactionType);
        return response;
    }

    /**
     * Post a past-dated transaction
     * Includes delta interest calculation and adjustment posting
     */
    private TransactionResponseDTO postPastDatedTransaction(String tranId, List<TranTable> transactions) {
        // First, post the main transaction using normal logic
        TransactionResponseDTO response = postCurrentTransaction(tranId, transactions);

        // Calculate and post interest adjustments for each line (skip GL-only and settlement rows)
        for (TranTable transaction : transactions) {
            if (transaction.getAccountNo() == null
                    || glSetupRepository.findById(transaction.getAccountNo()).isPresent()) {
                continue;
            }
            LocalDate valueDate = transaction.getValueDate();
            int daysDifference = valueDateValidationService.calculateDaysDifference(valueDate);

            // Calculate delta interest
            BigDecimal deltaInterest = valueDateCalculationService.calculateDeltaInterest(
                transaction.getAccountNo(), transaction.getLcyAmt(), daysDifference);

            // Post interest adjustment if delta > 0
            if (deltaInterest.compareTo(BigDecimal.ZERO) > 0) {
                valueDatePostingService.postInterestAdjustment(transaction, deltaInterest);
            }

            // Log value date transaction
            valueDatePostingService.logValueDateTransaction(
                transaction.getTranId(), valueDate, daysDifference, deltaInterest, "Y");
        }

        log.info("Past-dated transaction posted with interest adjustments: {}", tranId);
        return response;
    }

    /**
     * Post a future-dated transaction
     * Sets status to Future and does NOT update balances
     */
    private TransactionResponseDTO postFutureDatedTransaction(String tranId, List<TranTable> transactions) {
        // Update status to Future (not Posted)
        for (TranTable transaction : transactions) {
            transaction.setTranStatus(TranStatus.Future);

            LocalDate valueDate = transaction.getValueDate();
            int daysDifference = valueDateValidationService.calculateDaysDifference(valueDate);

            // Log future-dated transaction (no delta interest yet)
            valueDatePostingService.logValueDateTransaction(
                transaction.getTranId(), valueDate, daysDifference, BigDecimal.ZERO, "N");
        }

        // Save updated transaction status (but don't update balances or create GL movements)
        tranTableRepository.saveAll(transactions);

        TranTable firstLine = transactions.get(0);
        TransactionResponseDTO response = buildTransactionResponse(tranId, firstLine.getTranDate(),
                firstLine.getValueDate(), firstLine.getNarration(), transactions);

        log.info("Future-dated transaction created with ID: {}. Balances NOT updated.", tranId);
        return response;
    }

    /**
     * Verify a transaction (move from any status to Verified status)
     * Simple verification logic like products/customers/subproducts
     * Also creates transaction history records for Statement of Accounts
     * 
     * @param tranId The transaction ID
     * @return The updated transaction response
     */
    @Transactional
    public TransactionResponseDTO verifyTransaction(String tranId) {
        // Find all transaction lines using PK-index prefix scan (avoids full table scan).
        // Supports both multi-line transactions (tranId + "-N" suffix) and
        // standalone single-entry rows (exact tranId match, used by Deal Booking / BOD).
        List<TranTable> allLines = tranTableRepository.findByTranIdStartingWith(tranId).stream()
                .filter(t -> matchesTranId(t.getTranId(), tranId))
                .collect(Collectors.toList());

        List<TranTable> transactions = allLines.stream()
                .filter(t -> t.getTranStatus() != TranStatus.Verified)
                .collect(Collectors.toList());

        if (transactions.isEmpty()) {
            if (!allLines.isEmpty()) {
                throw new BusinessException("Transaction is already verified.");
            } else {
                throw new ResourceNotFoundException("Transaction", "ID", tranId);
            }
        }
        
        // Allow verification for Entry and Posted (maker-checker submit not yet implemented, so Entry → Verified is valid).
        // Block only invalid statuses: Future (value-dated, not yet posted) and any unknown.
        TranStatus currentStatus = transactions.get(0).getTranStatus();
        if (currentStatus == TranStatus.Future) {
            throw new BusinessException("Future-dated transactions must be posted first before they can be verified.");
        }
        // Entry and Posted are allowed to proceed to Verified.
        
        // Update status to Verified
        transactions.forEach(t -> t.setTranStatus(TranStatus.Verified));
        tranTableRepository.saveAll(transactions);
        
        // Create transaction history records for each transaction line (skip settlement legs: account_no is null)
        // This populates TXN_HIST_ACCT table for Statement of Accounts
        String verifierUserId = "SYSTEM"; // TODO: Get from security context when authentication is implemented
        for (TranTable transaction : transactions) {
            if (transaction.getAccountNo() == null) {
                log.debug("Skipping transaction history for settlement leg (no account): {}", transaction.getTranId());
                continue;
            }
            transactionHistoryService.createTransactionHistory(transaction, verifierUserId);
        }
        
        TranTable firstLine = transactions.get(0);
        TransactionResponseDTO response = buildTransactionResponse(tranId, firstLine.getTranDate(), 
                firstLine.getValueDate(), firstLine.getNarration(), transactions);
        
        log.info("Transaction verified with ID: {}", tranId);
        return response;
    }

    /**
     * Reverse a transaction by creating opposite entries
     * 
     * @param tranId The original transaction ID to reverse
     * @param reason The reason for reversal
     * @return The reversal transaction response
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransactionResponseDTO reverseTransaction(String tranId, String reason) {
        // Find all transaction lines using PK-index prefix scan (avoids full table scan).
        // Supports both multi-line (-N suffix) and standalone rows (exact match).
        List<TranTable> originalTransactions = tranTableRepository.findByTranIdStartingWith(tranId).stream()
                .filter(t -> matchesTranId(t.getTranId(), tranId))
                .collect(Collectors.toList());
        
        if (originalTransactions.isEmpty()) {
            throw new ResourceNotFoundException("Transaction", "ID", tranId);
        }
        
        // Generate reversal transaction ID
        String reversalTranId = generateTransactionId();
        LocalDate tranDate = systemDateService.getSystemDate();
        LocalDate valueDate = originalTransactions.get(0).getValueDate();
        
        List<TranTable> reversalTransactions = new ArrayList<>();
        List<GLMovement> glMovements = new ArrayList<>();
        
        // Create opposite entries
        int lineNumber = 1;
        for (TranTable original : originalTransactions) {
            // Create opposite entry
            DrCrFlag oppositeDrCr = original.getDrCrFlag() == DrCrFlag.D ? DrCrFlag.C : DrCrFlag.D;
            
            String lineId = reversalTranId + "-" + lineNumber++;
            TranTable reversalTran = TranTable.builder()
                    .tranId(lineId)
                    .tranDate(tranDate)
                    .valueDate(valueDate)
                    .drCrFlag(oppositeDrCr)
                    .tranStatus(TranStatus.Verified) // Reversals are auto-verified
                    .accountNo(original.getAccountNo())
                    .tranCcy(original.getTranCcy())
                    .fcyAmt(original.getFcyAmt())
                    .exchangeRate(original.getExchangeRate())
                    .lcyAmt(original.getLcyAmt())
                    .debitAmount(oppositeDrCr == DrCrFlag.D ? original.getLcyAmt() : BigDecimal.ZERO)
                    .creditAmount(oppositeDrCr == DrCrFlag.C ? original.getLcyAmt() : BigDecimal.ZERO)
                    .narration("REVERSAL: " + reason + " (Original: " + original.getTranId() + ")")
                    .pointingId(original.getPointingId()) // Link to original
                    .udf1(null)
                    .build();
            
            reversalTransactions.add(reversalTran);

            // Get GL number from account
            CustAcctMaster account = custAcctMasterRepository.findById(original.getAccountNo())
                    .orElseThrow(() -> new ResourceNotFoundException("Account", "Account Number", original.getAccountNo()));

            String glNum = account.getGlNum();
            // Persist GL_Num on the reversal row so EOD Step 4 can use it directly
            reversalTran.setGlNum(glNum);
            GLSetup glSetup = glSetupRepository.findById(glNum)
                    .orElseThrow(() -> new ResourceNotFoundException("GL", "GL Number", glNum));

            // Determine the correct amount for account balance update (reversal)
            BigDecimal accountBalanceAmount;
            if ("BDT".equals(original.getTranCcy())) {
                accountBalanceAmount = original.getLcyAmt();
            } else {
                accountBalanceAmount = original.getFcyAmt();
            }

            // Update account balance (opposite direction)
            balanceService.updateAccountBalance(
                    original.getAccountNo(), oppositeDrCr, accountBalanceAmount);

            // Update GL balance (opposite direction, ALWAYS in BDT)
            BigDecimal newGLBalance = balanceService.updateGLBalance(
                    glNum, oppositeDrCr, original.getLcyAmt());
            
            // Create GL movement record
            GLMovement glMovement = GLMovement.builder()
                    .transaction(reversalTran)
                    .glSetup(glSetup)
                    .drCrFlag(oppositeDrCr)
                    .tranDate(tranDate)
                    .valueDate(valueDate)
                    .amount(original.getLcyAmt())
                    .balanceAfter(newGLBalance)
                    .build();
            
            glMovements.add(glMovement);
        }
        
        // Save all reversal transaction lines
        tranTableRepository.saveAll(reversalTransactions);
        
        // Save all GL movements
        glMovementRepository.saveAll(glMovements);
        
        TransactionResponseDTO response = buildTransactionResponse(reversalTranId, tranDate, valueDate, 
                "REVERSAL: " + reason, reversalTransactions);
        
        log.info("Transaction reversed. Original ID: {}, Reversal ID: {}", tranId, reversalTranId);
        return response;
    }

    /**
     * Get all transactions with pagination
     * Groups transaction lines by base transaction ID
     * 
     * @param pageable The pagination information
     * @return Page of transaction responses
     */
    public Page<TransactionResponseDTO> getAllTransactions(Pageable pageable) {
        // Count distinct logical transactions for pagination metadata (one COUNT query)
        long totalCount = tranTableRepository.countDistinctBaseTranIds();

        if (totalCount == 0) {
            return new PageImpl<>(new ArrayList<>(), pageable, 0);
        }

        // Fetch only the base IDs for this page from DB (sorted newest-first, one query)
        List<String> baseIds = tranTableRepository.findPagedBaseTranIds(
                pageable.getPageSize(), pageable.getOffset());

        if (baseIds.isEmpty()) {
            return new PageImpl<>(new ArrayList<>(), pageable, totalCount);
        }

        // Fetch all lines for this page's base IDs in a single query
        List<TranTable> allLines = tranTableRepository.findAllLinesByBaseTranIds(baseIds);

        // Batch fetch all account names for this page in ONE query (eliminates N+1)
        Set<String> accountNos = allLines.stream()
                .map(TranTable::getAccountNo)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, String> accountNameMap = custAcctMasterRepository.findAllById(accountNos)
                .stream()
                .collect(Collectors.toMap(CustAcctMaster::getAccountNo, CustAcctMaster::getAcctName));

        // Group lines by base transaction ID
        Map<String, List<TranTable>> grouped = allLines.stream()
                .collect(Collectors.groupingBy(t -> extractBaseTranId(t.getTranId())));

        // Build responses in the DB sort order from baseIds
        List<TransactionResponseDTO> responses = baseIds.stream()
                .filter(grouped::containsKey)
                .map(baseId -> {
                    List<TranTable> txLines = grouped.get(baseId);
                    TranTable first = txLines.get(0);
                    return buildTransactionResponse(baseId, first.getTranDate(),
                            first.getValueDate(), first.getNarration(), txLines, accountNameMap);
                })
                .collect(Collectors.toList());

        return new PageImpl<>(responses, pageable, totalCount);
    }

    /**
     * Get a transaction by ID
     * 
     * @param tranId The transaction ID
     * @return The transaction response
     */
    public TransactionResponseDTO getTransaction(String tranId) {
        // Find all transaction lines using PK-index prefix scan (avoids full table scan).
        // Supports both multi-line (-N suffix) and standalone rows (exact match).
        List<TranTable> transactions = tranTableRepository.findByTranIdStartingWith(tranId).stream()
                .filter(t -> matchesTranId(t.getTranId(), tranId))
                .collect(Collectors.toList());
        
        if (transactions.isEmpty()) {
            throw new ResourceNotFoundException("Transaction", "ID", tranId);
        }
        
        // Get the first line to extract common transaction data
        TranTable firstLine = transactions.get(0);
        
        // Create response
        return buildTransactionResponse(tranId, firstLine.getTranDate(), firstLine.getValueDate(), 
                firstLine.getNarration(), transactions);
    }
    
    /**
     * Extract base transaction ID (remove line number suffix)
     * Example: "T20251009123456-1" → "T20251009123456"
     * 
     * @param fullTranId The full transaction ID with line number
     * @return The base transaction ID
     */
    private String extractBaseTranId(String fullTranId) {
        int lastDashIndex = fullTranId.lastIndexOf('-');
        return lastDashIndex > 0 ? fullTranId.substring(0, lastDashIndex) : fullTranId;
    }

    /**
     * Returns true if a stored tran_id belongs to the logical transaction identified by baseTranId.
     *
     * Two formats are supported:
     *   1. Multi-line: baseTranId + "-" + lineNumber  (e.g. T20251009123456-1)
     *   2. Standalone: exactly baseTranId              (e.g. D20260412000006333 from Deal Booking)
     */
    private boolean matchesTranId(String storedId, String baseTranId) {
        return storedId.startsWith(baseTranId + "-") || storedId.equals(baseTranId);
    }

    /**
     * Validate that the transaction is balanced.
     * - If all legs are in the same FCY (e.g. both USD): compare FCY totals only. If FCY debit == FCY credit, allow (LCY may differ due to WAE → settlement gain/loss).
     * - If BDT or mixed currencies: compare LCY totals (debit must equal credit in LCY).
     */
    private void validateTransactionBalance(TransactionRequestDTO transactionRequestDTO) {
        List<TransactionLineDTO> lines = transactionRequestDTO.getLines();
        if (lines.isEmpty()) return;

        boolean allSameFcy = lines.stream().allMatch(l -> l.getTranCcy() != null && !"BDT".equals(l.getTranCcy()))
                && lines.stream().map(TransactionLineDTO::getTranCcy).distinct().count() == 1;
        if (allSameFcy) {
            BigDecimal totalFcyDr = lines.stream().filter(l -> l.getDrCrFlag() == DrCrFlag.D).map(TransactionLineDTO::getFcyAmt).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalFcyCr = lines.stream().filter(l -> l.getDrCrFlag() == DrCrFlag.C).map(TransactionLineDTO::getFcyAmt).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalFcyDr.compareTo(totalFcyCr) != 0) {
                throw new BusinessException("FCY debit total must equal FCY credit total. Please correct the entries.");
            }
            return;
        }

        BigDecimal totalDebits = lines.stream()
                .filter(line -> line.getDrCrFlag() == DrCrFlag.D)
                .map(TransactionLineDTO::getLcyAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = lines.stream()
                .filter(line -> line.getDrCrFlag() == DrCrFlag.C)
                .map(TransactionLineDTO::getLcyAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new BusinessException("Debit amount does not equal credit amount. Please correct the entries.");
        }
    }

    /**
     * Settlement = 2 lines, same FCY (e.g. USD).
     * Triggers:
     *   - Asset CR + Liability DR (any order)
     *   - Liability DR + Liability CR (Liability-to-Liability; only the DR leg triggers)
     *   - Asset DR + Asset CR (Asset-to-Asset; only the CR leg triggers)
     */
    private boolean isSettlementTransaction(List<TransactionLineDTO> lines) {
        if (lines == null || lines.size() != 2) return false;
        String ccy = lines.get(0).getTranCcy();
        if (ccy == null || "BDT".equals(ccy)) return false;
        if (!ccy.equals(lines.get(1).getTranCcy())) return false;
        try {
            UnifiedAccountService.AccountInfo info0 = unifiedAccountService.getAccountInfo(lines.get(0).getAccountNo());
            UnifiedAccountService.AccountInfo info1 = unifiedAccountService.getAccountInfo(lines.get(1).getAccountNo());
            boolean liabilityDr0 = info0.isLiabilityAccount() && lines.get(0).getDrCrFlag() == DrCrFlag.D;
            boolean liabilityDr1 = info1.isLiabilityAccount() && lines.get(1).getDrCrFlag() == DrCrFlag.D;
            boolean assetCr0 = info0.isAssetAccount() && lines.get(0).getDrCrFlag() == DrCrFlag.C;
            boolean assetCr1 = info1.isAssetAccount() && lines.get(1).getDrCrFlag() == DrCrFlag.C;
            // Asset CR + Liability DR (either order)
            if ((liabilityDr0 && assetCr1) || (liabilityDr1 && assetCr0)) return true;
            // Liability to Liability — the DEBIT leg always triggers settlement
            if (info0.isLiabilityAccount() && info1.isLiabilityAccount()
                    && (liabilityDr0 || liabilityDr1)) return true;
            // Asset to Asset — the CREDIT leg always triggers settlement
            if (info0.isAssetAccount() && info1.isAssetAccount()
                    && (assetCr0 || assetCr1)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build FCY settlement rows (gain/loss legs) for each triggered leg.
     *
     * Settlement triggers (one gain/loss row each):
     *   - Liability DR  → WAE of this account vs MID
     *   - Asset CR      → WAE of this account vs MID
     *
     * Non-settlement sides (Liability CR, Asset DR, Asset DR + Liability CR) → no gain/loss row.
     *
     * WAE per triggered leg is read directly from leg.getExchangeRate(), which was already
     * resolved from acc_bal for that specific account in the createTransaction main loop.
     * Using the same WAE that determined the leg's lcyAmt keeps the accounting consistent and
     * avoids a second (potentially different) acc_bal lookup here.
     *
     * Settlement formulas:
     *   LIABILITY DR: mid > WAE → LOSS = (mid − WAE) × FCY;  mid < WAE → GAIN = (WAE − mid) × FCY
     *   ASSET CR:     mid < WAE → LOSS = (WAE − mid) × FCY;  mid > WAE → GAIN = (mid − WAE) × FCY
     *
     * Row IDs are assigned consecutively from suffix -3 onward.
     */
    private List<TranTable> buildFcySettlementRows(String baseTranId, List<TranTable> legs,
                                                   LocalDate tranDate, LocalDate valueDate) {
        List<TranTable> out = new ArrayList<>();
        if (legs.size() != 2) return out;

        String ccy = legs.get(0).getTranCcy();
        if (ccy == null || "BDT".equals(ccy)) return out;

        // Fetch mid rate for the transaction date; fall back to the latest available rate
        // if no rate exists on or before that date (e.g. test environments with date mismatch).
        BigDecimal mid;
        try {
            mid = exchangeRateService.getExchangeRate(ccy, tranDate);
        } catch (BusinessException e) {
            mid = exchangeRateService.getLatestMidRate(ccy);
            if (mid == null) {
                log.warn("No mid rate available for {} at all – skipping settlement rows", ccy);
                return out;
            }
            log.warn("No mid rate found for {} on {} – using latest available rate {} for settlement",
                    ccy, tranDate, mid);
        }

        int nextSuffix = 3;

        log.info("═══ SETTLEMENT ROW GENERATION START ═══");
        log.info("Processing {} legs for settlement row generation", legs.size());

        for (TranTable leg : legs) {
            if (leg.getAccountNo() == null) {
                log.debug("Skipping GL-only row (no account): {}", leg.getTranId());
                continue;
            }

            UnifiedAccountService.AccountInfo info;
            try {
                info = unifiedAccountService.getAccountInfo(leg.getAccountNo());
            } catch (Exception e) {
                log.warn("Cannot resolve account info for {} – skipping settlement computation for this leg",
                        leg.getAccountNo());
                continue;
            }

            boolean isLiabilityDr = info.isLiabilityAccount() && leg.getDrCrFlag() == DrCrFlag.D;
            boolean isAssetCr     = info.isAssetAccount()     && leg.getDrCrFlag() == DrCrFlag.C;

            log.info("SETTLEMENT EVALUATION: account={}, GL={}, isLiability={}, isAsset={}, drCrFlag={}, isLiabilityDr={}, isAssetCr={}",
                    leg.getAccountNo(),
                    info.getGlNum(),
                    info.isLiabilityAccount(),
                    info.isAssetAccount(),
                    leg.getDrCrFlag(),
                    isLiabilityDr,
                    isAssetCr);

            if (!isLiabilityDr && !isAssetCr) {
                // Non-settlement side (Liability CR or Asset DR): no gain/loss row for this leg.
                log.info("SETTLEMENT SKIP: account {} ({} {}) is non-settlement side - NO gain/loss row",
                        leg.getAccountNo(),
                        info.isLiabilityAccount() ? "Liability" : "Asset",
                        leg.getDrCrFlag());
                continue;
            }

            log.info("SETTLEMENT TRIGGER CONFIRMED: account {} ({} {}) - will generate gain/loss row",
                    leg.getAccountNo(),
                    isLiabilityDr ? "Liability DR" : "Asset CR",
                    leg.getTranId());

            // WAE for this triggered leg: the main loop already resolved WAE from acc_bal and stored
            // it as leg.exchangeRate (Liability DR → WAE_DR; Asset CR → WAE_CR).
            // Using leg.getExchangeRate() keeps WAE consistent with the lcyAmt already assigned to
            // this leg, guaranteeing that the gain/loss amount exactly balances the LCY difference.
            BigDecimal wae = leg.getExchangeRate();
            if (wae == null) {
                log.warn("WAE is null on triggered leg {} – cannot compute gain/loss, skipping", leg.getTranId());
                continue;
            }

            log.info("SETTLEMENT ROW CALC: account={}, WAE from leg={}, MID={}", 
                    leg.getAccountNo(), wae, mid);

            BigDecimal fcy = leg.getFcyAmt();
            if (fcy == null || fcy.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("FCY amount is zero or negative for leg {} - skipping", leg.getTranId());
                continue;
            }

            int midVsWae = mid.compareTo(wae);
            if (midVsWae == 0) {
                log.info("SETTLEMENT SKIP: WAE == MID ({}) for leg {} – no gain/loss row needed", wae, leg.getTranId());
                continue;
            }

            // Gain/loss = difference of already-rounded leg LCY amounts.
            // DO NOT use |mid-WAE|×FCY — independent rounding can cause a 0.01 drift vs the leg amounts.
            BigDecimal legLcy = leg.getLcyAmt() != null
                    ? leg.getLcyAmt()
                    : fcy.multiply(wae).setScale(2, RoundingMode.HALF_UP);
            BigDecimal midLcy = fcy.multiply(mid).setScale(2, RoundingMode.HALF_UP);
            BigDecimal amount = legLcy.subtract(midLcy).abs();
            
            log.info("SETTLEMENT AMOUNT CALC: legLcy={}, midLcy={}, diff={}", legLcy, midLcy, amount);
            
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Settlement amount is zero for leg {} - skipping", leg.getTranId());
                continue;
            }

            boolean isGain;
            if (isLiabilityDr) {
                // LIABILITY DR: bank debit is at WAE; if MID > WAE bank paid out more → LOSS
                //               if MID < WAE bank paid out less  → GAIN
                isGain = midVsWae < 0;   // mid < WAE → GAIN
                log.info("LIABILITY DR logic: MID vs WAE = {}, isGain={} (mid<WAE=gain, mid>WAE=loss)",
                        midVsWae < 0 ? "MID<WAE" : "MID>WAE", isGain);
            } else {
                // ASSET CR: bank credit is at WAE; if MID > WAE bank received more → GAIN
                //           if MID < WAE bank received less        → LOSS
                isGain = midVsWae > 0;   // mid > WAE → GAIN
                log.info("ASSET CR logic: MID vs WAE = {}, isGain={} (mid>WAE=gain, mid<WAE=loss)",
                        midVsWae > 0 ? "MID>WAE" : "MID<WAE", isGain);
            }

            log.info("FCY settlement {}: {} leg {}, account={}, WAE={}, MID={}, FCY={}, {} amount={} BDT",
                    isGain ? "GAIN" : "LOSS",
                    isLiabilityDr ? "Liability-DR" : "Asset-CR",
                    leg.getTranId(), leg.getAccountNo(), wae, mid, fcy,
                    isGain ? "GAIN" : "LOSS", amount);

            addSettlementRow(baseTranId, tranDate, valueDate, amount, isGain, nextSuffix++, out);
        }
        
        log.info("═══ SETTLEMENT ROW GENERATION END: {} rows generated ═══", out.size());

        return out;
    }

    /**
     * Add a single settlement row for one trigger leg.
     * account_no is always null for settlement rows; only gl_num is populated.
     * GAIN → CR to FX_GAIN_GL (140203002)
     * LOSS → DR to FX_LOSS_GL (240203002)
     */
    private void addSettlementRow(String baseTranId, LocalDate tranDate, LocalDate valueDate,
                                  BigDecimal amount, boolean isGain, int suffix, List<TranTable> out) {
        String id = baseTranId + "-" + suffix;
        if (isGain) {
            out.add(TranTable.builder()
                    .tranId(id).tranDate(tranDate).valueDate(valueDate)
                    .drCrFlag(DrCrFlag.C).tranStatus(TranStatus.Entry)
                    .accountNo(null)
                    .tranCcy("BDT").fcyAmt(BigDecimal.ZERO).exchangeRate(BigDecimal.ONE)
                    .lcyAmt(amount).debitAmount(BigDecimal.ZERO).creditAmount(amount)
                    .narration("FX Gain on Settlement").udf1(null).glNum(FX_GAIN_GL).build());
        } else {
            out.add(TranTable.builder()
                    .tranId(id).tranDate(tranDate).valueDate(valueDate)
                    .drCrFlag(DrCrFlag.D).tranStatus(TranStatus.Entry)
                    .accountNo(null)
                    .tranCcy("BDT").fcyAmt(BigDecimal.ZERO).exchangeRate(BigDecimal.ONE)
                    .lcyAmt(amount).debitAmount(amount).creditAmount(BigDecimal.ZERO)
                    .narration("FX Loss on Settlement").udf1(null).glNum(FX_LOSS_GL).build());
        }
    }

    /**
     * Resolve the GL number for any account number at transaction creation time.
     * Checks GL_setup first (GL-only lines such as FX Gain/Loss and Position GL),
     * then falls back to the unified account service (customer / office accounts).
     *
     * @param accountNo The account number to resolve
     * @return The GL number, or null if it cannot be resolved (Step 4 fallback will handle it)
     */
    private String resolveGlNumForAccount(String accountNo) {
        if (glSetupRepository.existsById(accountNo)) {
            return accountNo;
        }
        try {
            return unifiedAccountService.getGlNum(accountNo);
        } catch (Exception e) {
            log.warn("Could not resolve GL_Num for account {} at insert time: {}", accountNo, e.getMessage());
            return null;
        }
    }

    /**
     * Generate a unique transaction ID (max 20 characters)
     * Format: TyyyyMMddHHmmssSSS (20 chars max)
     * Example: T20251009120530123
     *
     * @return The transaction ID
     */
    private String generateTransactionId() {
        // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
        LocalDate systemDate = systemDateService.getSystemDate();
        String date = systemDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        // Use sequence-based approach instead of System.currentTimeMillis() for CBS compliance
        // Generate a unique sequence number based on existing transactions for the same date
        long sequenceNumber = generateSequenceNumber(systemDate);
        String sequenceComponent = String.format("%06d", sequenceNumber);
        String randomPart = String.format("%03d", random.nextInt(1000));
        
        // Format: T + yyyyMMdd + 6-digit-sequence + 3-digit-random = 18 characters
        return "T" + date + sequenceComponent + randomPart;
    }
    
    /**
     * Generate a sequence number for transaction ID based on existing transactions for the same date
     * CBS Compliance: Uses System_Date instead of device clock for deterministic ID generation
     */
    private long generateSequenceNumber(LocalDate systemDate) {
        // Count existing transactions for the same date to generate next sequence
        long existingCount = tranTableRepository.countByTranDate(systemDate);
        return existingCount + 1;
    }

    /**
     * Get Position GL for currency exchange transaction
     * Determines the Position GL based on the FCY currency (USD, EUR, GBP, JPY)
     *
     * @param transactions List of transaction lines
     * @return Position GL account number, or null if no FCY found
     */
    private Integer getPositionGLForCurrencyExchange(List<TranTable> transactions) {
        // Position GL mapping (Currency -> GL Account)
        Map<String, Integer> positionGLMap = Map.of(
            "USD", 920101001,
            "EUR", 920102001,
            "GBP", 920103001,
            "JPY", 920104001
        );

        // Find the FCY currency in the transaction (not BDT)
        for (TranTable transaction : transactions) {
            String currency = transaction.getTranCcy();
            if (!"BDT".equals(currency)) {
                // Found FCY currency, return its Position GL
                Integer positionGL = positionGLMap.get(currency);
                if (positionGL != null) {
                    log.debug("Found FCY currency {} with Position GL {}", currency, positionGL);
                    return positionGL;
                }
            }
        }

        log.warn("No FCY currency found in currency exchange transaction");
        return null;
    }

    /**
     * Calculate WAE (Weighted Average Exchange rate) for an account with enhanced diagnostics.
     * 
     * WAE formula:
     *   WAE = currentLCY / currentFCY
     * where:
     *   currentFCY = prev_day_closing + today_credits - today_debits  (from acc_bal)
     *   currentLCY = prev_day_closing_lcy + today_credits_lcy - today_debits_lcy  (from acct_bal_lcy)
     * 
     * CRITICAL: LCY MUST come from acct_bal_lcy table (separate table), NOT from acc_bal.
     * 
     * FALLBACK LOGIC (NEW): If acct_bal_lcy record doesn't exist for current date (same-day credit-then-debit scenario),
     * calculate live WAE directly from acc_bal table using both FCY and LCY balance fields.
     * 
     * @param accountNo The account number
     * @param currency The account currency
     * @param tranDate The transaction date for logging context
     * @return The calculated WAE, or null if calculation fails (caller must handle)
     */
    private BigDecimal calculateWaeWithDiagnostics(String accountNo, String currency, LocalDate tranDate) {
        log.info("═══ WAE CALCULATION START for account {} ═══", accountNo);
        
        if ("BDT".equals(currency)) {
            log.info("BDT account - WAE = 1.0");
            return BigDecimal.ONE;
        }

        // Step 1: Get FCY from acc_bal
        Optional<AcctBal> accBalOpt = acctBalRepository.findByAccountNoAndTranDate(accountNo, tranDate);
        if (accBalOpt.isEmpty()) {
            // Fallback to latest
            accBalOpt = acctBalRepository.findLatestByAccountNo(accountNo);
        }
        
        if (accBalOpt.isEmpty()) {
            log.error("WAE DIAGNOSTIC: No acc_bal record found for account {} on date {}", accountNo, tranDate);
            return null;
        }

        AcctBal accBal = accBalOpt.get();
        log.info("WAE DIAGNOSTIC: acc_bal found for {} - tran_date={}", accountNo, accBal.getTranDate());
        
        BigDecimal openingBal = accBal.getOpeningBal() != null ? accBal.getOpeningBal() : BigDecimal.ZERO;
        BigDecimal crSummation = accBal.getCrSummation() != null ? accBal.getCrSummation() : BigDecimal.ZERO;
        BigDecimal drSummation = accBal.getDrSummation() != null ? accBal.getDrSummation() : BigDecimal.ZERO;
        BigDecimal currentFcy = openingBal.add(crSummation).subtract(drSummation);
        
        log.info("WAE DIAGNOSTIC: FCY calculation for {} - opening={}, CR={}, DR={}, current={}",
                accountNo, openingBal, crSummation, drSummation, currentFcy);

        if (currentFcy.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("WAE DIAGNOSTIC: FCY balance is zero for account {}, cannot calculate WAE", accountNo);
            return null;
        }

        // Step 2: Get LCY from acct_bal_lcy — SEPARATE TABLE
        Optional<AcctBalLcy> acctBalLcyOpt = acctBalLcyRepository.findByAccountNoAndTranDate(accountNo, tranDate);
        if (acctBalLcyOpt.isEmpty()) {
            // Fallback to latest
            acctBalLcyOpt = acctBalLcyRepository.findLatestByAccountNo(accountNo);
        }
        
        if (acctBalLcyOpt.isEmpty()) {
            log.warn("WAE DIAGNOSTIC: No acct_bal_lcy record found for account {} on date {}", accountNo, tranDate);
            log.info("WAE FALLBACK: Attempting to calculate live WAE from acc_bal table for same-day transactions");
            
            // NEW FALLBACK LOGIC: Calculate live WAE from acc_bal table when acct_bal_lcy doesn't exist
            BigDecimal liveWAE = calculateLiveWAEFromAccBal(accountNo, tranDate);
            if (liveWAE != null) {
                log.info("═══ LIVE WAE CALCULATED from acc_bal for account {}: {} ═══", accountNo, liveWAE);
                return liveWAE;
            } else {
                log.error("WAE DIAGNOSTIC: Live WAE calculation failed. This account needs an acct_bal_lcy row! Check EOD Step 8 execution.");
                return null;
            }
        }

        AcctBalLcy acctBalLcy = acctBalLcyOpt.get();
        log.info("WAE DIAGNOSTIC: acct_bal_lcy found for {} - tran_date={}", accountNo, acctBalLcy.getTranDate());
        
        BigDecimal openingBalLcy = acctBalLcy.getOpeningBalLcy() != null ? acctBalLcy.getOpeningBalLcy() : BigDecimal.ZERO;
        BigDecimal crSummationLcy = acctBalLcy.getCrSummationLcy() != null ? acctBalLcy.getCrSummationLcy() : BigDecimal.ZERO;
        BigDecimal drSummationLcy = acctBalLcy.getDrSummationLcy() != null ? acctBalLcy.getDrSummationLcy() : BigDecimal.ZERO;
        BigDecimal currentLcy = openingBalLcy.add(crSummationLcy).subtract(drSummationLcy);
        
        log.info("WAE DIAGNOSTIC: LCY calculation for {} - opening={}, CR={}, DR={}, current={}",
                accountNo, openingBalLcy, crSummationLcy, drSummationLcy, currentLcy);

        BigDecimal wae = currentLcy.divide(currentFcy, 4, RoundingMode.HALF_UP);
        log.info("═══ WAE CALCULATED for account {}: {} / {} = {} ═══", accountNo, currentLcy, currentFcy, wae);
        return wae;
    }

    /**
     * Calculate live WAE from tran_table (same logic as UI - BalanceService.getComputedAccountBalance).
     * This matches the on-the-fly calculation shown to users in the UI.
     * 
     * Formula: WAE = Total_LCY / Total_FCY
     * Where:
     *   Total_FCY = Previous Day Closing FCY + Today's Credits FCY - Today's Debits FCY
     *   Total_LCY = Previous Day Closing LCY + Today's Credits LCY - Today's Debits LCY
     * 
     * Data sources:
     *   - Previous day closing: acct_bal_lcy table (yesterday's snapshot)
     *   - Today's movements: tran_table (live real-time transactions)
     * 
     * CRITICAL: This is the CORRECT source of truth for intraday balances.
     * acc_bal and acct_bal_lcy only update during EOD, NOT after each transaction.
     * 
     * @param accountNo The account number
     * @param tranDate The transaction date
     * @return The calculated live WAE, or null if calculation fails
     */
    private BigDecimal calculateLiveWAEFromAccBal(String accountNo, LocalDate tranDate) {
        log.info("═══ LIVE WAE CALCULATION from tran_table START for account {} ═══", accountNo);
        
        try {
            // Step 1: Get previous day's closing balance from acct_bal_lcy (yesterday's snapshot)
            LocalDate previousDay = tranDate.minusDays(1);
            BigDecimal previousDayFcyClosing = BigDecimal.ZERO;
            BigDecimal previousDayLcyClosing = BigDecimal.ZERO;
            
            Optional<AcctBalLcy> previousDayLcyOpt = acctBalLcyRepository.findByAccountNoAndTranDate(accountNo, previousDay);
            if (previousDayLcyOpt.isPresent()) {
                // Note: acct_bal_lcy stores LCY amounts, need to get FCY from acct_bal
                previousDayLcyClosing = previousDayLcyOpt.get().getClosingBalLcy() != null ? 
                        previousDayLcyOpt.get().getClosingBalLcy() : BigDecimal.ZERO;
                log.info("LIVE WAE: Previous day LCY closing balance: {}", previousDayLcyClosing);
                
                // Get FCY closing from acct_bal for same date
                Optional<AcctBal> previousDayFcyOpt = acctBalRepository.findByAccountNoAndTranDate(accountNo, previousDay);
                if (previousDayFcyOpt.isPresent()) {
                    previousDayFcyClosing = previousDayFcyOpt.get().getClosingBal() != null ?
                            previousDayFcyOpt.get().getClosingBal() : BigDecimal.ZERO;
                    log.info("LIVE WAE: Previous day FCY closing balance: {}", previousDayFcyClosing);
                }
            } else {
                // Fallback: Try to get latest record before current date
                List<AcctBalLcy> previousRecords = acctBalLcyRepository
                        .findByAccountNoAndTranDateBeforeOrderByTranDateDesc(accountNo, tranDate);
                if (!previousRecords.isEmpty()) {
                    AcctBalLcy latestRecord = previousRecords.get(0);
                    previousDayLcyClosing = latestRecord.getClosingBalLcy() != null ? 
                            latestRecord.getClosingBalLcy() : BigDecimal.ZERO;
                    log.info("LIVE WAE: Latest previous LCY closing: {} (date: {})", 
                            previousDayLcyClosing, latestRecord.getTranDate());
                    
                    // Get corresponding FCY balance
                    Optional<AcctBal> latestFcyOpt = acctBalRepository.findByAccountNoAndTranDate(
                            accountNo, latestRecord.getTranDate());
                    if (latestFcyOpt.isPresent()) {
                        previousDayFcyClosing = latestFcyOpt.get().getClosingBal() != null ?
                                latestFcyOpt.get().getClosingBal() : BigDecimal.ZERO;
                        log.info("LIVE WAE: Latest previous FCY closing: {}", previousDayFcyClosing);
                    }
                } else {
                    log.info("LIVE WAE: No previous balance found - new account, starting from 0");
                }
            }
            
            // Step 2: Get today's transaction movements from tran_table (live data)
            // This is the KEY: transactions are posted to tran_table immediately, not to acc_bal
            List<TranTable> todayTransactions = tranTableRepository.findByAccountNoAndTranDate(accountNo, tranDate);
            
            // Sum FCY amounts
            BigDecimal todayFcyCredits = todayTransactions.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
                    .map(TranTable::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal todayFcyDebits = todayTransactions.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.D)
                    .map(TranTable::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // Sum LCY amounts (Debit_Amount and Credit_Amount are in BDT)
            BigDecimal todayLcyCredits = todayTransactions.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.C)
                    .map(t -> t.getCreditAmount() != null ? t.getCreditAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal todayLcyDebits = todayTransactions.stream()
                    .filter(t -> t.getDrCrFlag() == DrCrFlag.D)
                    .map(t -> t.getDebitAmount() != null ? t.getDebitAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            log.info("LIVE WAE: Today's FCY movements - Credits={}, Debits={}", todayFcyCredits, todayFcyDebits);
            log.info("LIVE WAE: Today's LCY movements - Credits={}, Debits={}", todayLcyCredits, todayLcyDebits);
            
            // Step 3: Calculate current live balances (same formula as UI)
            BigDecimal totalFcy = previousDayFcyClosing.add(todayFcyCredits).subtract(todayFcyDebits);
            BigDecimal totalLcy = previousDayLcyClosing.add(todayLcyCredits).subtract(todayLcyDebits);
            
            log.info("LIVE WAE: Total balances - FCY={}, LCY={}", totalFcy, totalLcy);
            
            // Step 4: Calculate WAE
            if (totalFcy.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("LIVE WAE: FCY balance is zero, cannot calculate WAE");
                return null;
            }
            
            BigDecimal liveWae = totalLcy.abs().divide(totalFcy.abs(), 4, RoundingMode.HALF_UP);
            log.info("═══ LIVE WAE CALCULATED from tran_table: {} / {} = {} ═══", totalLcy, totalFcy, liveWae);
            
            return liveWae;
            
        } catch (Exception e) {
            log.error("LIVE WAE: Error calculating live WAE for account {}: {}", accountNo, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Helper method to convert null BigDecimal to ZERO
     */
    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Build a transaction response — uses pre-fetched account name map to avoid N+1 queries.
     * Called from getAllTransactions where account names are batch-loaded for the whole page.
     */
    private TransactionResponseDTO buildTransactionResponse(String tranId, LocalDate tranDate,
            LocalDate valueDate, String narration, List<TranTable> transactions,
            Map<String, String> accountNameCache) {

        List<TransactionLineResponseDTO> lines = transactions.stream()
                .map(tran -> {
                    String accountNo = tran.getAccountNo();
                    String accountName = (accountNo == null) ? "" :
                            accountNameCache.getOrDefault(accountNo, "");

                    return TransactionLineResponseDTO.builder()
                            .tranId(tran.getTranId())
                            .accountNo(accountNo)
                            .accountName(accountName)
                            .drCrFlag(tran.getDrCrFlag())
                            .tranCcy(tran.getTranCcy())
                            .fcyAmt(tran.getFcyAmt())
                            .exchangeRate(tran.getExchangeRate())
                            .lcyAmt(tran.getLcyAmt())
                            .glNum(tran.getGlNum())
                            .udf1(tran.getNarration())
                            .build();
                })
                .collect(Collectors.toList());

        return TransactionResponseDTO.builder()
                .tranId(tranId)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .narration(narration)
                .lines(lines)
                .balanced(true)
                .status(transactions.get(0).getTranStatus().toString())
                .build();
    }

    /**
     * Build a transaction response from the transaction lines.
     * Issues one DB call per line for account name — only use for single-transaction operations
     * (post, verify, reverse, getTransaction) where the line count is small (2-4 lines).
     * For the list page use the overload that accepts a pre-fetched accountNameCache.
     *
     * @param tranId The transaction ID
     * @param tranDate The transaction date
     * @param valueDate The value date
     * @param narration The narration
     * @param transactions The transaction lines
     * @return The transaction response
     */
    private TransactionResponseDTO buildTransactionResponse(String tranId, LocalDate tranDate,
            LocalDate valueDate, String narration, List<TranTable> transactions) {

        List<TransactionLineResponseDTO> lines = transactions.stream()
                .map(tran -> {
                    String accountNo = tran.getAccountNo();
                    String accountName = (accountNo == null) ? "" :
                            custAcctMasterRepository.findById(accountNo)
                                    .map(CustAcctMaster::getAcctName)
                                    .orElse("");

                    return TransactionLineResponseDTO.builder()
                            .tranId(tran.getTranId())
                            .accountNo(accountNo)
                            .accountName(accountName)
                            .drCrFlag(tran.getDrCrFlag())
                            .tranCcy(tran.getTranCcy())
                            .fcyAmt(tran.getFcyAmt())
                            .exchangeRate(tran.getExchangeRate())
                            .lcyAmt(tran.getLcyAmt())
                            .glNum(tran.getGlNum())
                            .udf1(tran.getNarration())
                            .build();
                })
                .collect(Collectors.toList());

        return TransactionResponseDTO.builder()
                .tranId(tranId)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .narration(narration)
                .lines(lines)
                .balanced(true)
                .status(transactions.get(0).getTranStatus().toString())
                .build();
    }
}
