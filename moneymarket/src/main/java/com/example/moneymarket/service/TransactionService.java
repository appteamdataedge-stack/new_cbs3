package com.example.moneymarket.service;

import com.example.moneymarket.dto.TransactionLineDTO;
import com.example.moneymarket.dto.TransactionLineResponseDTO;
import com.example.moneymarket.dto.TransactionRequestDTO;
import com.example.moneymarket.dto.TransactionResponseDTO;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.GLMovement;
import com.example.moneymarket.entity.GLSetup;
import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.exception.ResourceNotFoundException;
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

        // Process each transaction line - create in Entry status
        int lineNumber = 1;
        for (TransactionLineDTO lineDTO : transactionRequestDTO.getLines()) {
            // Validate account exists (customer or office account)
            if (!unifiedAccountService.accountExists(lineDTO.getAccountNo())) {
                throw new ResourceNotFoundException("Account", "Account Number", lineDTO.getAccountNo());
            }
            
            // Get account info for GL number
            unifiedAccountService.getAccountInfo(lineDTO.getAccountNo());
            
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
            
            // Per-account WAE for settlement (FCY) lines; otherwise use request rate
            BigDecimal lcyAmt = lineDTO.getLcyAmt();
            BigDecimal exchangeRate = lineDTO.getExchangeRate();
            if (isSettlement && lineDTO.getTranCcy() != null && !"BDT".equals(lineDTO.getTranCcy())) {
                BigDecimal lineWae = balanceService.getComputedAccountBalance(lineDTO.getAccountNo(), tranDate).getWae();
                if (lineWae == null) {
                    lineWae = exchangeRateService.getExchangeRate(lineDTO.getTranCcy(), tranDate);
                }
                exchangeRate = lineWae;
                lcyAmt = lineDTO.getFcyAmt().multiply(lineWae).setScale(2, RoundingMode.HALF_UP);
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
        // Find all transaction lines with Entry status
        List<TranTable> transactions = tranTableRepository.findAll().stream()
                .filter(t -> t.getTranId().startsWith(tranId + "-") && t.getTranStatus() == TranStatus.Entry)
                .collect(Collectors.toList());

        if (transactions.isEmpty()) {
            throw new ResourceNotFoundException("Transaction", "ID", tranId);
        }

        // Validate again before posting
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

        // Validate all transactions again before posting using new business rules
        for (TranTable transaction : transactions) {
            // Skip balance validation for GL-only lines (FX Gain/Loss, Position GL)
            if (glSetupRepository.findById(transaction.getAccountNo()).isPresent()) {
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

            String glNum;
            Optional<GLSetup> glOpt = glSetupRepository.findById(transaction.getAccountNo());
            if (glOpt.isPresent()) {
                // GL-only line (e.g. FX Gain 140203002, FX Loss 240203002, Position 920101001)
                glNum = transaction.getAccountNo();
            } else {
                glNum = unifiedAccountService.getGlNum(transaction.getAccountNo());
            }
            GLSetup glSetup = glSetupRepository.findById(glNum)
                    .orElseThrow(() -> new ResourceNotFoundException("GL", "GL Number", glNum));

            if (!glOpt.isPresent()) {
                // Customer/Office account: update account balance and GL
                BigDecimal accountBalanceAmount;
                if ("BDT".equals(transaction.getTranCcy())) {
                    accountBalanceAmount = transaction.getLcyAmt();
                } else {
                    accountBalanceAmount = transaction.getFcyAmt();
                }
                validationService.updateAccountBalanceForTransaction(
                        transaction.getAccountNo(), transaction.getDrCrFlag(), accountBalanceAmount);
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

        // Calculate and post interest adjustments for each line (skip GL-only lines)
        for (TranTable transaction : transactions) {
            if (glSetupRepository.findById(transaction.getAccountNo()).isPresent()) {
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
        // Find all transaction lines (any status except Verified)
        List<TranTable> transactions = tranTableRepository.findAll().stream()
                .filter(t -> t.getTranId().startsWith(tranId + "-") && t.getTranStatus() != TranStatus.Verified)
                .collect(Collectors.toList());
        
        if (transactions.isEmpty()) {
            // Check if transaction exists but already verified
            List<TranTable> existingTransactions = tranTableRepository.findAll().stream()
                    .filter(t -> t.getTranId().startsWith(tranId + "-"))
                    .collect(Collectors.toList());
            
            if (!existingTransactions.isEmpty()) {
                throw new BusinessException("Transaction is already verified.");
            } else {
                throw new ResourceNotFoundException("Transaction", "ID", tranId);
            }
        }
        
        // Update status to Verified
        transactions.forEach(t -> t.setTranStatus(TranStatus.Verified));
        tranTableRepository.saveAll(transactions);
        
        // Create transaction history records for each transaction line
        // This populates TXN_HIST_ACCT table for Statement of Accounts
        String verifierUserId = "SYSTEM"; // TODO: Get from security context when authentication is implemented
        for (TranTable transaction : transactions) {
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
        // Find all transaction lines of the original transaction
        List<TranTable> originalTransactions = tranTableRepository.findAll().stream()
                .filter(t -> t.getTranId().startsWith(tranId + "-"))
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
        // Get all transaction lines
        List<TranTable> allTransactions = tranTableRepository.findAll();
        
        // Group by base transaction ID (remove line number suffix)
        Map<String, List<TranTable>> groupedTransactions = allTransactions.stream()
                .collect(Collectors.groupingBy(t -> extractBaseTranId(t.getTranId())));
        
        // Convert to response DTOs
        List<TransactionResponseDTO> allResponses = groupedTransactions.entrySet().stream()
                .map(entry -> {
                    String baseTranId = entry.getKey();
                    List<TranTable> lines = entry.getValue();
                    TranTable firstLine = lines.get(0);
                    return buildTransactionResponse(baseTranId, firstLine.getTranDate(), 
                            firstLine.getValueDate(), firstLine.getNarration(), lines);
                })
                .sorted((a, b) -> b.getTranDate().compareTo(a.getTranDate())) // Sort by date descending
                .collect(Collectors.toList());
        
        // Apply pagination
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), allResponses.size());
        List<TransactionResponseDTO> pageContent = start < allResponses.size() 
                ? allResponses.subList(start, end) 
                : new ArrayList<>();
        
        return new PageImpl<>(pageContent, pageable, allResponses.size());
    }

    /**
     * Get a transaction by ID
     * 
     * @param tranId The transaction ID
     * @return The transaction response
     */
    public TransactionResponseDTO getTransaction(String tranId) {
        // Find all transaction lines with the given transaction ID prefix
        List<TranTable> transactions = tranTableRepository.findAll().stream()
                .filter(t -> t.getTranId().startsWith(tranId + "-"))
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
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build FCY settlement rows when WAE != Mid.
     * Settlement triggers: Liability FCY → DEBIT only; Asset FCY → CREDIT only.
     * Each trigger produces ONE row. IDs assigned consecutively starting at -3.
     */
    private List<TranTable> buildFcySettlementRows(String baseTranId, List<TranTable> legs, LocalDate tranDate, LocalDate valueDate) {
        List<TranTable> out = new ArrayList<>();
        if (legs.size() != 2) return out;
        TranTable leg0 = legs.get(0);
        TranTable leg1 = legs.get(1);
        String ccy = leg0.getTranCcy();
        if (ccy == null || "BDT".equals(ccy)) return out;

        UnifiedAccountService.AccountInfo info0 = unifiedAccountService.getAccountInfo(leg0.getAccountNo());
        UnifiedAccountService.AccountInfo info1 = unifiedAccountService.getAccountInfo(leg1.getAccountNo());
        BigDecimal wae0 = balanceService.getComputedAccountBalance(leg0.getAccountNo(), tranDate).getWae();
        BigDecimal wae1 = balanceService.getComputedAccountBalance(leg1.getAccountNo(), tranDate).getWae();
        if (wae0 == null) wae0 = exchangeRateService.getExchangeRate(ccy, tranDate);
        if (wae1 == null) wae1 = exchangeRateService.getExchangeRate(ccy, tranDate);
        BigDecimal mid = exchangeRateService.getExchangeRate(ccy, tranDate);

        if (wae0 != null && wae1 != null && mid != null
                && wae0.compareTo(mid) == 0 && wae1.compareTo(mid) == 0) {
            return out;
        }

        boolean liability0 = info0.isLiabilityAccount();
        boolean asset0 = info0.isAssetAccount();
        boolean liability1 = info1.isLiabilityAccount();
        boolean asset1 = info1.isAssetAccount();
        boolean dr0 = leg0.getDrCrFlag() == DrCrFlag.D;
        boolean cr0 = leg0.getDrCrFlag() == DrCrFlag.C;
        boolean dr1 = leg1.getDrCrFlag() == DrCrFlag.D;
        boolean cr1 = leg1.getDrCrFlag() == DrCrFlag.C;

        // Trigger: Liability Dr or Asset Cr
        boolean trigger0 = (liability0 && dr0) || (asset0 && cr0);
        boolean trigger1 = (liability1 && dr1) || (asset1 && cr1);
        // Asset Dr + Liability Cr → no settlement
        if (!trigger0 && !trigger1) return out;

        int nextSuffix = 3;
        if (trigger0 && leg0.getFcyAmt() != null && leg0.getFcyAmt().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal wae = wae0;
            BigDecimal fcy = leg0.getFcyAmt();
            BigDecimal amount;
            boolean isGain;
            if (liability0 && dr0) {
                if (mid.compareTo(wae) > 0) {
                    amount = mid.subtract(wae).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = false;
                } else {
                    amount = wae.subtract(mid).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = true;
                }
            } else {
                if (mid.compareTo(wae) < 0) {
                    amount = wae.subtract(mid).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = false;
                } else {
                    amount = mid.subtract(wae).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = true;
                }
            }
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                addSettlementRow(baseTranId, tranDate, valueDate, amount, isGain, nextSuffix, out);
                nextSuffix += 1;
            }
        }
        if (trigger1 && leg1.getFcyAmt() != null && leg1.getFcyAmt().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal wae = wae1;
            BigDecimal fcy = leg1.getFcyAmt();
            BigDecimal amount;
            boolean isGain;
            if (liability1 && dr1) {
                if (mid.compareTo(wae) > 0) {
                    amount = mid.subtract(wae).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = false;
                } else {
                    amount = wae.subtract(mid).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = true;
                }
            } else {
                if (mid.compareTo(wae) < 0) {
                    amount = wae.subtract(mid).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = false;
                } else {
                    amount = mid.subtract(wae).multiply(fcy).setScale(2, RoundingMode.HALF_UP);
                    isGain = true;
                }
            }
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                addSettlementRow(baseTranId, tranDate, valueDate, amount, isGain, nextSuffix, out);
            }
        }
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
                    .narration("FX Gain").udf1(null).glNum(FX_GAIN_GL).build());
        } else {
            out.add(TranTable.builder()
                    .tranId(id).tranDate(tranDate).valueDate(valueDate)
                    .drCrFlag(DrCrFlag.D).tranStatus(TranStatus.Entry)
                    .accountNo(null)
                    .tranCcy("BDT").fcyAmt(BigDecimal.ZERO).exchangeRate(BigDecimal.ONE)
                    .lcyAmt(amount).debitAmount(amount).creditAmount(BigDecimal.ZERO)
                    .narration("FX Loss").udf1(null).glNum(FX_LOSS_GL).build());
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
     * Build a transaction response from the transaction lines
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
                    String accountName = custAcctMasterRepository.findById(accountNo)
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
