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
import java.util.Random;

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
        log.info("Starting interest capitalization for account: {}", accountNo);

        // 1. Fetch and validate account
        CustAcctMaster account = custAcctMasterRepository.findById(accountNo)
                .orElseThrow(() -> new BusinessException("Account not found: " + accountNo));

        // 2. Validate account is interest-bearing
        validateInterestBearing(account);

        // 3. Get system date
        LocalDate systemDate = systemDateService.getSystemDate();

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
     */
    private BigDecimal getAccruedBalance(String accountNo) {
        return acctBalAccrualRepository.findLatestByAccountNo(accountNo)
                .map(AcctBalAccrual::getInterestAmount)
                .orElse(BigDecimal.ZERO);
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
     */
    private BigDecimal getCurrentBalance(String accountNo, LocalDate systemDate) {
        return acctBalRepository.findByTranDateAndAccountAccountNo(systemDate, accountNo)
                .map(AcctBal::getCurrentBalance)
                .orElse(BigDecimal.ZERO);
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
     */
    private void createDebitEntry(CustAcctMaster account, String transactionId, 
                                   LocalDate systemDate, BigDecimal amount, String narration) {
        SubProdMaster subProduct = account.getSubProduct();
        String interestExpenseGL = getInterestExpenseGL(account);

        InttAccrTran debitEntry = InttAccrTran.builder()
                .accrTranId(transactionId + "-1")  // Suffix for debit entry
                .accountNo(interestExpenseGL)  // Interest Expense GL
                .accrualDate(systemDate)
                .tranDate(systemDate)
                .valueDate(systemDate)
                .drCrFlag(TranTable.DrCrFlag.D)
                .tranStatus(TranTable.TranStatus.Posted)
                .glAccountNo(interestExpenseGL)
                .tranCcy(account.getAccountCcy())
                .fcyAmt(amount)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(amount)
                .amount(amount)
                .interestRate(subProduct.getEffectiveInterestRate() != null ? 
                             subProduct.getEffectiveInterestRate() : BigDecimal.ZERO)
                .status(InttAccrTran.AccrualStatus.Posted)
                .narration(narration != null ? narration : "Interest Capitalization - Expense")
                .build();

        inttAccrTranRepository.save(debitEntry);
        log.debug("Created debit entry: {} for GL: {}", transactionId + "-1", interestExpenseGL);
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
                .tranStatus(TranTable.TranStatus.Posted)
                .accountNo(account.getAccountNo())
                .tranCcy(account.getAccountCcy())
                .fcyAmt(amount)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(amount)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(amount)
                .narration(narration != null ? narration : "Interest Capitalization - Credit")
                .build();

        tranTableRepository.save(creditEntry);
        log.debug("Created credit entry: {} for account: {}", transactionId + "-2", account.getAccountNo());
    }

    /**
     * Update account balance after capitalization
     * Updates current balance and resets accrued balance to zero
     * Uses latest balance record with fallback logic (same pattern as BalanceService)
     */
    private void updateAccountAfterCapitalization(String accountNo, LocalDate systemDate, BigDecimal accruedInterest) {
        log.debug("Updating account balance after capitalization for account: {} on date: {}", accountNo, systemDate);
        
        // Get account balance - try specific date first, then fall back to latest
        // This matches the pattern used in BalanceService.getComputedAccountBalance()
        AcctBal acctBal = acctBalRepository.findByAccountNoAndTranDate(accountNo, systemDate)
                .or(() -> acctBalRepository.findLatestByAccountNo(accountNo))
                .orElseThrow(() -> new BusinessException("Account balance record not found for account: " + accountNo));

        log.debug("Found balance record for account {}: Tran_Date={}, Current_Balance={}", 
                  accountNo, acctBal.getTranDate(), acctBal.getCurrentBalance());

        // Add accrued interest to current balance
        BigDecimal oldBalance = acctBal.getCurrentBalance();
        BigDecimal newCurrentBalance = oldBalance.add(accruedInterest);
        acctBal.setCurrentBalance(newCurrentBalance);
        
        // Update available balance (same as current for simplicity)
        acctBal.setAvailableBalance(newCurrentBalance);
        
        // Update last updated timestamp to business date/time
        acctBal.setLastUpdated(systemDateService.getSystemDateTime());
        
        acctBalRepository.save(acctBal);
        
        log.info("Account balance updated for {}: {} + {} = {}", 
                 accountNo, oldBalance, accruedInterest, newCurrentBalance);

        // Reset accrued balance to zero
        AcctBalAccrual acctBalAccrual = acctBalAccrualRepository.findLatestByAccountNo(accountNo)
                .orElseThrow(() -> new BusinessException("Accrued balance record not found"));

        acctBalAccrual.setInterestAmount(BigDecimal.ZERO);
        acctBalAccrualRepository.save(acctBalAccrual);

        log.debug("Reset accrued balance to 0 for account: {}", accountNo);
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
