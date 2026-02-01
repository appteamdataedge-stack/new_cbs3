package com.example.moneymarket.service;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Service for Interest Capitalization
 * Handles manual posting of accrued interest to customer accounts
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterestCapitalizationService {

    private final CustAcctMasterRepository custAcctMasterRepository;
    private final AcctBalAccrualRepository acctBalAccrualRepository;
    private final AcctBalRepository acctBalRepository;
    private final TranTableRepository tranTableRepository;
    private final InttAccrTranRepository inttAccrTranRepository;
    private final SubProdMasterRepository subProdMasterRepository;
    private final SystemDateService systemDateService;
    private final Random random = new Random();

    /**
     * Process interest capitalization for an account
     * 
     * @param request The capitalization request
     * @return The capitalization response with transaction details
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
        log.info("System Date (Business Date) from SystemDateService: {}", systemDate);
        log.info("LocalDate.now() (Device Date - NOT USED): {}", LocalDate.now());

        // 4. Validate no duplicate payment (last interest payment date < system date)
        validateNoDuplicatePayment(account, systemDate);

        // 5. Get accrued interest balance
        BigDecimal accruedBalance = getAccruedBalance(accountNo);

        // 6. Validate accrued balance > 0
        validateAccruedBalance(accruedBalance);

        // 7. Get current balance
        BigDecimal currentBalance = getCurrentBalance(accountNo, systemDate);
        BigDecimal oldBalance = currentBalance;

        // 8. Calculate new balance
        BigDecimal newBalance = oldBalance.add(accruedBalance);

        // 9. Generate transaction ID with 'C' prefix
        String transactionId = generateCapitalizationTransactionId(systemDate);

        // 10. Create debit entry in Intt_Accr_Tran (Interest Expense)
        createDebitEntry(account, transactionId, systemDate, accruedBalance, request.getNarration());

        // 11. Create credit entry in Tran_Table (Customer Account)
        createCreditEntry(account, transactionId, systemDate, accruedBalance, request.getNarration());

        // 12. Update account: balance and last interest payment date
        updateAccountAfterCapitalization(accountNo, systemDate, accruedBalance);

        // 13. Update account entity's last interest payment date
        account.setLastInterestPaymentDate(systemDate);
        custAcctMasterRepository.save(account);

        log.info("Interest capitalization completed for account: {}. Transaction ID: {}", accountNo, transactionId);

        // 14. Build and return response
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
     * Validate that the account's product is interest-bearing
     */
    private void validateInterestBearing(CustAcctMaster account) {
        SubProdMaster subProduct = account.getSubProduct();
        ProdMaster product = subProduct.getProduct();
        
       Boolean isInterestBearing = product.getInterestBearingFlag();
        if (isInterestBearing == null || !isInterestBearing) {
            throw new BusinessException("The account is Non-Interest bearing");
        }
    }

    /**
     * Validate that interest hasn't already been capitalized today
     */
    private void validateNoDuplicatePayment(CustAcctMaster account, LocalDate systemDate) {
        LocalDate lastPaymentDate = account.getLastInterestPaymentDate();
        if (lastPaymentDate != null && !lastPaymentDate.isBefore(systemDate)) {
            throw new BusinessException("Interest has already been capitalized");
        }
    }

    /**
     * Get the accrued interest balance for the account
     * Uses closing_bal (total accumulated interest) instead of cr_summation (daily accrual)
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
        BigDecimal crSummation = acctBalAccrual.getCrSummation() != null ? acctBalAccrual.getCrSummation() : BigDecimal.ZERO;
        BigDecimal interestAmount = acctBalAccrual.getInterestAmount() != null ? acctBalAccrual.getInterestAmount() : BigDecimal.ZERO;
        
        log.info("Account: {}", accountNo);
        log.info("Closing Balance (Total Accumulated Interest): {}", closingBal);
        log.info("CR Summation (Today's Daily Accrual): {}", crSummation);
        log.info("Interest Amount (Old field): {}", interestAmount);
        log.info("Using Closing Balance for capitalization: {}", closingBal);
        
        return closingBal;  // FIXED: Use closing_bal (total accumulated) instead of interestAmount
    }

    /**
     * Validate that accrued balance is greater than zero
     */
    private void validateAccruedBalance(BigDecimal accruedBalance) {
        if (accruedBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("There is no accrued interest");
        }
    }

    /**
     * Get current (real-time) balance for the account
     * Uses latest balance record with fallback logic (same as BalanceService)
     */
    private BigDecimal getCurrentBalance(String accountNo, LocalDate systemDate) {
        log.info("=== GETTING CURRENT BALANCE - AUDIT ===");
        log.info("Account Number: {}", accountNo);
        log.info("System Date (Business Date): {}", systemDate);
        log.info("LocalDate.now() (Device Date): {}", LocalDate.now());
        
        // Try to get balance for the specific system date first
        Optional<AcctBal> balanceForSystemDate = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate);
        if (balanceForSystemDate.isPresent()) {
            log.info("Found balance record for system date {}: Balance = {}", 
                    systemDate, balanceForSystemDate.get().getCurrentBalance());
            return balanceForSystemDate.get().getCurrentBalance();
        }
        
        log.warn("No balance record found for system date {}. Trying latest record...", systemDate);
        
        // Fall back to latest balance record (same pattern as BalanceService)
        Optional<AcctBal> latestBalance = acctBalRepository.findLatestByAccountNo(accountNo);
        if (latestBalance.isPresent()) {
            log.info("Found latest balance record: Date = {}, Balance = {}", 
                    latestBalance.get().getTranDate(), latestBalance.get().getCurrentBalance());
            return latestBalance.get().getCurrentBalance();
        }
        
        // Show what dates DO exist for this account
        List<AcctBal> allBalances = acctBalRepository.findByAccountNoOrderByTranDateDesc(accountNo);
        log.error("NO balance records found for account {}. Total records in acct_bal: {}", 
                accountNo, allBalances.size());
        if (!allBalances.isEmpty()) {
            log.error("Available dates for this account: {}", 
                    allBalances.stream()
                            .map(AcctBal::getTranDate)
                            .collect(java.util.stream.Collectors.toList()));
        }
        
        return BigDecimal.ZERO;
    }

    /**
     * Generate transaction ID with 'C' prefix for capitalization
     * Format: C + yyyyMMdd + 6-digit-sequence + 3-digit-random (18 characters)
     */
    private String generateCapitalizationTransactionId(LocalDate systemDate) {
        String date = systemDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        // Count existing capitalization transactions for the same date
        long sequenceNumber = tranTableRepository.countByTranDateAndTranIdStartingWith(systemDate, "C") + 1;
        String sequenceComponent = String.format("%06d", sequenceNumber);
        String randomPart = String.format("%03d", random.nextInt(1000));
        
        return "C" + date + sequenceComponent + randomPart;
    }

    /**
     * Create debit entry in Intt_Accr_Tran (Interest Expense GL)
     * CRITICAL: accountNo must be customer account (FK constraint), glAccountNo is the GL account
     */
    private void createDebitEntry(CustAcctMaster account, String transactionId, 
                                   LocalDate systemDate, BigDecimal amount, String narration) {
        SubProdMaster subProduct = account.getSubProduct();
        String interestExpenseGL = getInterestExpenseGL(account);

        log.info("=== CREATING DEBIT ENTRY IN INTT_ACCR_TRAN ===");
        log.info("Customer Account Number: '{}'", account.getAccountNo());
        log.info("Interest Expense GL Number: '{}'", interestExpenseGL);
        log.info("Account Number length: {}", account.getAccountNo().length());
        log.info("GL Account Number length: {}", interestExpenseGL != null ? interestExpenseGL.length() : "null");

        InttAccrTran debitEntry = InttAccrTran.builder()
                .accrTranId(transactionId + "-1")  // Suffix for debit entry
                .accountNo(account.getAccountNo())  // FIXED: Use customer account number (FK constraint)
                .accrualDate(systemDate)
                .tranDate(systemDate)
                .valueDate(systemDate)
                .drCrFlag(TranTable.DrCrFlag.D)
                .tranStatus(TranTable.TranStatus.Verified)  // Changed from Posted to Verified
                .glAccountNo(interestExpenseGL)  // GL account for movement tracking
                .tranCcy(account.getAccountCcy() != null ? account.getAccountCcy() : "BDT")
                .fcyAmt(amount)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(amount)
                .amount(amount)
                .interestRate(subProduct.getEffectiveInterestRate() != null ? 
                             subProduct.getEffectiveInterestRate() : BigDecimal.ZERO)
                .status(InttAccrTran.AccrualStatus.Pending)  // ✅ FIX: Set to Pending so EOD Step 3 processes it
                .narration(narration != null ? narration : "Interest Capitalization - Expense")
                .udf1("Frontend_user")  // Set verifier field
                .build();

        log.info("Saving debit entry with Account_No='{}', GL_Account_No='{}'", 
                 debitEntry.getAccountNo(), debitEntry.getGlAccountNo());

        inttAccrTranRepository.save(debitEntry);
        log.info("Created debit entry: {} for customer account: {}, GL: {} with amount: {}", 
                 transactionId + "-1", account.getAccountNo(), interestExpenseGL, amount);
    }

    /**
     * Create credit entry in Tran_Table (Customer Account)
     */
    private void createCreditEntry(CustAcctMaster account, String transactionId, 
                                    LocalDate systemDate, BigDecimal amount, String narration) {
        TranTable creditEntry = TranTable.builder()
                .tranId(transactionId + "-2")  // Suffix for credit entry
                .tranDate(systemDate)
                .valueDate(systemDate)
                .drCrFlag(TranTable.DrCrFlag.C)
                .tranStatus(TranTable.TranStatus.Verified)  // Changed from Posted to Verified
                .accountNo(account.getAccountNo())
                .tranCcy(account.getAccountCcy() != null ? account.getAccountCcy() : "BDT")
                .fcyAmt(amount)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(amount)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(amount)
                .narration(narration != null ? narration : "Interest Capitalization - Credit")
                .udf1("Frontend_user")  // Set verifier field
                .build();

        tranTableRepository.save(creditEntry);
        log.info("Created credit entry: {} for account: {} with amount: {}", 
                 transactionId + "-2", account.getAccountNo(), amount);
    }

    /**
     * Update account balance after capitalization
     * Updates current balance and resets accrued balance to zero
     * Uses latest balance record with fallback logic (same pattern as BalanceService)
     */
    private void updateAccountAfterCapitalization(String accountNo, LocalDate systemDate, BigDecimal accruedInterest) {
        log.info("=== UPDATING ACCOUNT BALANCE - AUDIT ===");
        log.info("Account Number: {}", accountNo);
        log.info("System Date (Business Date): {}", systemDate);
        log.info("LocalDate.now() (Device Date): {}", LocalDate.now());
        log.info("Accrued Interest to Add: {}", accruedInterest);
        
        // Try to get balance for the specific system date first
        Optional<AcctBal> balanceForSystemDate = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate);
        if (balanceForSystemDate.isPresent()) {
            log.info("Found balance record for system date {}", systemDate);
        } else {
            log.warn("No balance record found for system date {}. Trying latest record...", systemDate);
        }
        
        // Get account balance - try specific date first, then fall back to latest
        // This matches the pattern used in BalanceService.getComputedAccountBalance()
        Optional<AcctBal> acctBalOpt = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate)
                .or(() -> acctBalRepository.findLatestByAccountNo(accountNo));
        
        if (acctBalOpt.isEmpty()) {
            // Show what dates DO exist for this account
            List<AcctBal> allBalances = acctBalRepository.findByAccountNoOrderByTranDateDesc(accountNo);
            log.error("=== BALANCE RECORD NOT FOUND - DETAILED AUDIT ===");
            log.error("Account Number: {}", accountNo);
            log.error("System Date searched: {}", systemDate);
            log.error("Total balance records for this account: {}", allBalances.size());
            
            if (!allBalances.isEmpty()) {
                log.error("Available dates for this account:");
                allBalances.forEach(bal -> 
                    log.error("  - Date: {}, Balance: {}", bal.getTranDate(), bal.getCurrentBalance())
                );
            } else {
                log.error("NO balance records exist for this account in acct_bal table!");
            }
            
            throw new BusinessException(String.format(
                "Account balance record not found for system date. Account: %s, System Date: %s, Device Date: %s. " +
                "Available dates: %s",
                accountNo, 
                systemDate, 
                LocalDate.now(),
                allBalances.isEmpty() ? "NONE" : 
                    allBalances.stream()
                        .map(AcctBal::getTranDate)
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.joining(", "))
            ));
        }
        
        AcctBal acctBal = acctBalOpt.get();
        log.info("Found balance record: Tran_Date={}, Current_Balance={}", 
                  acctBal.getTranDate(), acctBal.getCurrentBalance());

        // Add accrued interest to current balance
        BigDecimal oldBalance = acctBal.getCurrentBalance();
        BigDecimal newCurrentBalance = oldBalance.add(accruedInterest);
        acctBal.setCurrentBalance(newCurrentBalance);
        
        // Update available balance (same as current for simplicity)
        acctBal.setAvailableBalance(newCurrentBalance);
        
        // Update last updated timestamp to business date/time
        acctBal.setLastUpdated(systemDateService.getSystemDateTime());
        
        acctBalRepository.save(acctBal);
        
        log.info("Account balance updated successfully: {} + {} = {}", 
                 oldBalance, accruedInterest, newCurrentBalance);

        // DO NOT directly update acct_bal_accrual here!
        // The acct_bal_accrual update will be handled by EOD Batch Job 6
        // which will process the "C" (Capitalization) debit transaction created above
        
        log.info("=== ACCT_BAL_ACCRUAL UPDATE DEFERRED TO EOD ===");
        log.info("Capitalization debit transaction created in intt_accr_tran (amount: {})", accruedInterest);
        log.info("EOD Batch Job 6 will process this 'C' transaction and update acct_bal_accrual");
        log.info("Formula: closing_bal = prev_closing_bal + cr_summation - dr_summation");
        log.info("Expected result after EOD: closing_bal will be reduced by {}", accruedInterest);
    }

    /**
     * Get the appropriate Interest Expense GL for the account
     * For Liability accounts: Interest Payable GL
     * For Asset accounts: Interest Receivable GL
     */
    private String getInterestExpenseGL(CustAcctMaster account) {
        SubProdMaster subProduct = account.getSubProduct();
        String glNum = account.getGlNum();

        // Determine if it's an asset or liability account based on GL
        boolean isAssetAccount = glNum != null && glNum.startsWith("2");

        if (isAssetAccount) {
            // Asset account: use Interest Receivable GL
            return subProduct.getInterestReceivableExpenditureGLNum();
        } else {
            // Liability account: use Interest Payable GL
            return subProduct.getInterestIncomePayableGLNum();
        }
    }
}
