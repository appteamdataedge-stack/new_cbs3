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

    private final Random random = new Random();

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
            
            // Validate transaction with correct amount
            try {
                validationService.validateTransaction(
                        lineDTO.getAccountNo(), lineDTO.getDrCrFlag(), validationAmount);
            } catch (BusinessException e) {
                throw new BusinessException("Transaction validation failed for account " +
                        lineDTO.getAccountNo() + ": " + e.getMessage());
            }
            
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
                    .exchangeRate(lineDTO.getExchangeRate())
                    .lcyAmt(lineDTO.getLcyAmt())
                    .debitAmount(lineDTO.getDrCrFlag() == DrCrFlag.D ? lineDTO.getLcyAmt() : BigDecimal.ZERO)
                    .creditAmount(lineDTO.getDrCrFlag() == DrCrFlag.C ? lineDTO.getLcyAmt() : BigDecimal.ZERO)
                    .narration(lineDTO.getUdf1())
                    .udf1(null)
                    .build();
            
            transactions.add(transaction);
        }
        
        // Save all transaction lines
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

            // Get GL number from account (customer or office)
            String glNum = unifiedAccountService.getGlNum(transaction.getAccountNo());
            GLSetup glSetup = glSetupRepository.findById(glNum)
                    .orElseThrow(() -> new ResourceNotFoundException("GL", "GL Number", glNum));

            // Determine the correct amount for account balance update
            // For BDT accounts: Use LCY_Amt
            // For FCY accounts (USD, EUR, etc.): Use FCY_Amt
            BigDecimal accountBalanceAmount;
            if ("BDT".equals(transaction.getTranCcy())) {
                // BDT transaction: Use LCY amount (same as FCY for BDT)
                accountBalanceAmount = transaction.getLcyAmt();
            } else {
                // FCY transaction: Use FCY amount (account balance is in account's currency)
                accountBalanceAmount = transaction.getFcyAmt();
            }

            // Update account balance with validation
            validationService.updateAccountBalanceForTransaction(
                    transaction.getAccountNo(), transaction.getDrCrFlag(), accountBalanceAmount);

            // Update GL balance (ALWAYS in BDT)
            BigDecimal newGLBalance = balanceService.updateGLBalance(
                    glNum, transaction.getDrCrFlag(), transaction.getLcyAmt());

            // Create GL movement record
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
        multiCurrencyTransactionService.processMultiCurrencyTransaction(transactions, transactionType);

        TranTable firstLine = transactions.get(0);
        TransactionResponseDTO response = buildTransactionResponse(tranId, firstLine.getTranDate(),
                firstLine.getValueDate(), firstLine.getNarration(), transactions);

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

        // Calculate and post interest adjustments for each line
        for (TranTable transaction : transactions) {
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
     * Validate that the transaction is balanced (debit equals credit)
     * 
     * @param transactionRequestDTO The transaction data
     */
    private void validateTransactionBalance(TransactionRequestDTO transactionRequestDTO) {
        BigDecimal totalDebits = transactionRequestDTO.getLines().stream()
                .filter(line -> line.getDrCrFlag() == DrCrFlag.D)
                .map(TransactionLineDTO::getLcyAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
                
        BigDecimal totalCredits = transactionRequestDTO.getLines().stream()
                .filter(line -> line.getDrCrFlag() == DrCrFlag.C)
                .map(TransactionLineDTO::getLcyAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
                
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new BusinessException("Debit amount does not equal credit amount. Please correct the entries.");
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
