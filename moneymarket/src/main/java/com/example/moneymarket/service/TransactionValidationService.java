package com.example.moneymarket.service;

import com.example.moneymarket.entity.AcctBal;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.exception.ResourceNotFoundException;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.TranTableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Service for transaction validation
 * 
 * Enforces the following business rules:
 * 
 * CUSTOMER ACCOUNTS (cust_flag = 'Y'):
 * - Debit transactions are allowed only up to the available balance
 * - Credit transactions have no restriction beyond standard balance updates
 * 
 * OFFICE ACCOUNTS (cust_flag = 'N'):
 * - "Debit up to available balance" rule does NOT apply
 * - Asset GL accounts: balance must never go into (+) credit
 * - Liability GL accounts: balance must never go into (-) debit
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionValidationService {

    private final AcctBalRepository acctBalRepository;
    private final TranTableRepository tranTableRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final SystemDateService systemDateService;
    private final UnifiedAccountService unifiedAccountService;
    private final GLHierarchyService glHierarchyService;
    private final AccountBalanceUpdateService accountBalanceUpdateService;

    /**
     * Validate if a transaction can be performed on an account
     * 
     * @param accountNo The account number
     * @param drCrFlag The debit/credit flag
     * @param amount The transaction amount
     * @return true if the transaction is valid, false otherwise
     * @throws BusinessException if the account does not exist or validation fails
     */
    @Transactional(readOnly = true)
    public boolean validateTransaction(String accountNo, DrCrFlag drCrFlag, BigDecimal amount) {
        // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
        return validateTransaction(accountNo, drCrFlag, amount, systemDateService.getSystemDate());
    }

    /**
     * Validate if a transaction can be performed on an account for a specific date
     * 
     * @param accountNo The account number
     * @param drCrFlag The debit/credit flag
     * @param amount The transaction amount (in account's currency - FCY for USD, LCY for BDT)
     * @param systemDate The system date to use for calculations
     * @return true if the transaction is valid, false otherwise
     * @throws BusinessException if the account does not exist or validation fails
     */
    @Transactional(readOnly = true)
    public boolean validateTransaction(String accountNo, DrCrFlag drCrFlag, BigDecimal amount, LocalDate systemDate) {
        // Get unified account information
        UnifiedAccountService.AccountInfo accountInfo = unifiedAccountService.getAccountInfo(accountNo);
        
        // ✅ FIX ISSUE 4: Get account currency from Product → SubProduct → Account hierarchy
        String accountCurrency = unifiedAccountService.getAccountCurrency(accountNo);
        log.debug("Validating transaction for account {} with currency: {}", accountNo, accountCurrency);
        
        // Get current account balance
        AcctBal balance = acctBalRepository.findLatestByAccountNo(accountNo)
                .orElseThrow(() -> new BusinessException("Balance for account " + accountNo + " not found"));
        
        // Calculate resulting balance after transaction
        // IMPORTANT: Balance is always in account's currency (USD for USD accounts, BDT for BDT accounts)
        BigDecimal currentBalance = balance.getCurrentBalance();
        BigDecimal resultingBalance;
        
        if (drCrFlag == DrCrFlag.D) {
            resultingBalance = currentBalance.subtract(amount);
        } else {
            resultingBalance = currentBalance.add(amount);
        }
        
        // Apply different validation rules based on account type
        if (accountInfo.isCustomerAccount()) {
            return validateCustomerAccountTransaction(accountNo, drCrFlag, amount, systemDate, accountInfo, balance, accountCurrency);
        } else {
            return validateOfficeAccountTransaction(accountNo, drCrFlag, amount, resultingBalance, accountInfo);
        }
    }

    /**
     * Validate transaction for customer accounts
     * Business Rule: Debit transactions are allowed only up to the available balance
     * Exception: Overdraft accounts (Layer 3 GL_Num = 210201000 or 140101000) can go into negative balance
     * Asset Account Rules:
     *   1. Debit transactions cannot exceed (Available Balance + Loan Limit)
     *   2. UPDATED: Asset accounts CAN have positive balances (e.g., savings, deposits)
     */
    private boolean validateCustomerAccountTransaction(String accountNo, DrCrFlag drCrFlag, BigDecimal amount, 
                                                     LocalDate systemDate, UnifiedAccountService.AccountInfo accountInfo, 
                                                     AcctBal balance, String accountCurrency) {
        BigDecimal currentBalance = balance.getCurrentBalance();
        BigDecimal resultingBalance;
        
        if (drCrFlag == DrCrFlag.D) {
            resultingBalance = currentBalance.subtract(amount);
        } else {
            resultingBalance = currentBalance.add(amount);
        }
        
        // ASSET CUSTOMER ACCOUNTS (GL starting with "2"): Validate debit transactions only
        if (accountInfo.isAssetAccount()) {
            // Rule 1: For DEBIT transactions, check against available balance (includes loan limit)
            if (drCrFlag == DrCrFlag.D) {
                BigDecimal availableBalance = calculateAvailableBalance(accountNo, balance.getCurrentBalance(), systemDate);
                
                if (amount.compareTo(availableBalance) > 0) {
                    log.warn("Customer Asset Account {} (GL: {}) - Debit exceeds available balance (including loan limit). " +
                            "Available: {} {}, Debit amount: {} {}", 
                            accountNo, accountInfo.getGlNum(), availableBalance, accountCurrency, amount, accountCurrency);
                    
                    // ✅ FIX ISSUE 1: Include currency in error message
                    throw new BusinessException(
                        String.format("Insufficient balance for Asset Account %s (GL: %s). " +
                                    "Available balance (including loan limit): %.2f %s, Debit amount: %.2f %s. " +
                                    "Cannot debit more than available balance plus loan limit.",
                                    accountNo, accountInfo.getGlNum(), availableBalance, accountCurrency, amount, accountCurrency)
                    );
                }
                
                log.debug("Customer Asset Account {} (GL: {}) - Debit validation passed. " +
                        "Available balance (with loan limit): {}, Debit amount: {}", 
                        accountNo, accountInfo.getGlNum(), availableBalance, amount);
            }
            
            // REMOVED RESTRICTION: Asset accounts can now have positive balances
            // This allows savings accounts, deposit accounts, Nostro accounts, etc. to hold positive balances
            // Previous validation that blocked positive balances for loan accounts has been removed
            
            log.info("Customer Asset Account {} (GL: {}) - All validations passed. " +
                    "Resulting balance: {} (both positive and negative balances allowed)", 
                    accountNo, accountInfo.getGlNum(), resultingBalance);
            return true;
        }
        
        // LIABILITY CUSTOMER ACCOUNTS (GL starting with "1"): Standard validation
        if (drCrFlag == DrCrFlag.D) {
            // Check if this is an overdraft account (Layer 3 GL_Num = 210201000 or 140101000)
            boolean isOverdraftAccount = glHierarchyService.isOverdraftAccount(accountInfo.getGlNum());
            
            if (isOverdraftAccount) {
                log.info("Overdraft account {} detected (GL_Num: {}). Skipping insufficient balance validation.", 
                        accountNo, accountInfo.getGlNum());
                // Allow negative balance for overdraft accounts
                return true;
            }
            
            // For non-overdraft accounts, check available balance
            BigDecimal availableBalance = calculateAvailableBalance(accountNo, balance.getCurrentBalance(), systemDate);
            
            if (amount.compareTo(availableBalance) > 0) {
                log.warn("Insufficient balance for customer account {}: available balance = {} {}, debit amount = {} {}", 
                        accountNo, availableBalance, accountCurrency, amount, accountCurrency);
                
                // ✅ FIX ISSUE 1: Include currency in error message
                throw new BusinessException(
                    String.format("Insufficient balance for account %s. Available: %.2f %s, Requested: %.2f %s",
                                accountNo, availableBalance, accountCurrency, amount, accountCurrency)
                );
            }
        }
        
        // Credit transactions have no restriction beyond standard balance updates for liability accounts
        return true;
    }

    /**
     * Validate transaction for office accounts
     * 
     * Business Rules (Based on GL Code Classification):
     * 
     * 1. ASSET Office Accounts (GL starting with "2"):
     *    - UPDATED: Can have positive balances (e.g., Nostro accounts with deposits)
     *    - Can go negative (debit balances are normal for assets)
     *    - No balance restrictions applied
     * 
     * 2. LIABILITY Office Accounts (GL starting with "1"):
     *    - MUST validate balance
     *    - Cannot go into negative (debit) balance
     *    - Requires sufficient balance before transaction
     * 
     * This conditional validation allows proper accounting flexibility:
     * - Asset accounts can handle both positive and negative balances
     * - Liability accounts maintain obligation integrity
     */
    private boolean validateOfficeAccountTransaction(String accountNo, DrCrFlag drCrFlag, BigDecimal amount, 
                                                   BigDecimal resultingBalance, UnifiedAccountService.AccountInfo accountInfo) {
        String glNum = accountInfo.getGlNum();
        
        // ASSET OFFICE ACCOUNTS (GL starting with "2"): Allow both positive and negative balances
        if (accountInfo.isAssetAccount()) {
            // REMOVED RESTRICTION: Asset accounts can now have positive balances
            // This allows Nostro accounts, Cash accounts, etc. to hold positive balances
            // Previous validation that blocked positive balances has been removed
            
            log.info("Office Asset Account {} (GL: {}) - Balance validation passed. " +
                    "Resulting balance: {} (both positive and negative balances allowed)", 
                    accountNo, glNum, resultingBalance);
            return true;
        }
        
        // LIABILITY OFFICE ACCOUNTS (GL starting with "1"): APPLY strict validation
        if (accountInfo.isLiabilityAccount()) {
            // Liability accounts cannot go into negative (debit) balance
            if (resultingBalance.compareTo(BigDecimal.ZERO) < 0) {
                log.warn("Office Liability Account {} (GL: {}) - Insufficient balance. " +
                        "Current: {}, Transaction: {} {}, Resulting: {}", 
                        accountNo, glNum, 
                        resultingBalance.subtract(drCrFlag == DrCrFlag.D ? amount.negate() : amount),
                        drCrFlag, amount, resultingBalance);
                
                throw new BusinessException(
                    String.format("Insufficient balance for Office Liability Account %s (GL: %s). " +
                                "Available balance: %s, Required: %s. " +
                                "Liability accounts cannot have negative balances.",
                                accountNo, glNum,
                                resultingBalance.subtract(drCrFlag == DrCrFlag.D ? amount.negate() : amount),
                                amount)
                );
            }
            
            log.info("Office Liability Account {} (GL: {}) - Balance validation passed. " +
                    "Resulting balance: {}", accountNo, glNum, resultingBalance);
            return true;
        }
        
        // Fallback for other account types (Income, Expenditure, etc.)
        // Apply conservative validation: prevent negative balances
        if (resultingBalance.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Office Account {} (GL: {}) of unknown type - Cannot go negative. " +
                    "Resulting balance would be: {}", accountNo, glNum, resultingBalance);
            throw new BusinessException(
                String.format("Insufficient balance for Office Account %s (GL: %s). " +
                            "Resulting balance would be negative: %s",
                            accountNo, glNum, resultingBalance)
            );
        }
        
        return true;
    }

    /**
     * Legacy method for backward compatibility - validates debit transactions only
     * @deprecated Use validateTransaction() instead
     */
    @Deprecated
    @Transactional(readOnly = true)
    public boolean validateDebitTransaction(String accountNo, BigDecimal amount) {
        return validateTransaction(accountNo, DrCrFlag.D, amount);
    }

    /**
     * Legacy method for backward compatibility - validates debit transactions only
     * @deprecated Use validateTransaction() instead
     */
    @Deprecated
    @Transactional(readOnly = true)
    public boolean validateDebitTransaction(String accountNo, BigDecimal amount, LocalDate systemDate) {
        return validateTransaction(accountNo, DrCrFlag.D, amount, systemDate);
    }
    
    /**
     * Calculate available balance for an account
     * Available Balance = Opening_Bal - Today's debits + Today's credits
     * 
     * @param accountNo The account number
     * @param currentBalance The current balance (fallback if Opening_Bal is null)
     * @return The available balance
     */
    private BigDecimal calculateAvailableBalance(String accountNo, BigDecimal currentBalance) {
        // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
        return calculateAvailableBalance(accountNo, currentBalance, systemDateService.getSystemDate());
    }

    /**
     * Calculate available balance for an account for a specific date
     * 
     * For LIABILITY accounts (GL starting with "1"):
     * Available Balance = Opening_Bal + Date's credits - Date's debits
     * 
     * For ASSET accounts (GL starting with "2"):
     * Available Balance = Opening_Bal + Loan Limit + Date's credits - Date's debits
     *
     * Uses 3-tier fallback logic for opening balance retrieval:
     * - Tier 1: Previous day's record (systemDate - 1)
     * - Tier 2: Last transaction date (MAX(Tran_Date) < systemDate)
     * - Tier 3: New account (return 0)
     *
     * @param accountNo The account number
     * @param currentBalance The current balance (fallback if Opening_Bal is null)
     * @param systemDate The system date to use for calculations
     * @return The available balance
     */
    private BigDecimal calculateAvailableBalance(String accountNo, BigDecimal currentBalance, LocalDate systemDate) {
        // Use shared 3-tier fallback logic to get opening balance
        BigDecimal openingBalance = accountBalanceUpdateService.getPreviousDayClosingBalance(accountNo, systemDate);

        // ✅ CRITICAL FIX: Get account currency to sum correct amounts
        String accountCurrency = unifiedAccountService.getAccountCurrency(accountNo);
        
        BigDecimal dateDebits;
        BigDecimal dateCredits;
        
        if ("BDT".equals(accountCurrency)) {
            // BDT account: Sum LCY amounts (default behavior)
            dateDebits = tranTableRepository.sumDebitTransactionsForAccountOnDate(accountNo, systemDate)
                    .orElse(BigDecimal.ZERO);
            dateCredits = tranTableRepository.sumCreditTransactionsForAccountOnDate(accountNo, systemDate)
                    .orElse(BigDecimal.ZERO);
            
            log.debug("BDT account {} - Using LCY amounts. Debits: {}, Credits: {}", 
                    accountNo, dateDebits, dateCredits);
        } else {
            // ✅ USD account: Sum FCY amounts (NOT LCY!)
            List<com.example.moneymarket.entity.TranTable> transactions = 
                    tranTableRepository.findByAccountNoAndTranDate(accountNo, systemDate);
            
            dateDebits = transactions.stream()
                    .filter(t -> t.getDrCrFlag() == com.example.moneymarket.entity.TranTable.DrCrFlag.D)
                    .map(com.example.moneymarket.entity.TranTable::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            dateCredits = transactions.stream()
                    .filter(t -> t.getDrCrFlag() == com.example.moneymarket.entity.TranTable.DrCrFlag.C)
                    .map(com.example.moneymarket.entity.TranTable::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            log.debug("USD account {} - Using FCY amounts. Debits: {} USD, Credits: {} USD", 
                    accountNo, dateDebits, dateCredits);
        }

        // Get account information to determine if asset or liability account
        UnifiedAccountService.AccountInfo accountInfo = unifiedAccountService.getAccountInfo(accountNo);
        BigDecimal loanLimit = BigDecimal.ZERO;
        
        // For ASSET customer accounts (GL starting with "2"), include loan limit in available balance
        if (accountInfo.isCustomerAccount() && accountInfo.isAssetAccount()) {
            try {
                CustAcctMaster customerAccount = custAcctMasterRepository.findById(accountNo)
                        .orElseThrow(() -> new ResourceNotFoundException("Customer Account", "Account Number", accountNo));
                loanLimit = customerAccount.getLoanLimit() != null ? customerAccount.getLoanLimit() : BigDecimal.ZERO;
                
                log.debug("Asset account {} - Including loan limit {} in available balance calculation",
                        accountNo, loanLimit);
            } catch (Exception e) {
                log.warn("Failed to retrieve loan limit for asset account {}: {}", accountNo, e.getMessage());
            }
        }

        // Calculate available balance (all amounts in account's currency)
        // For Asset accounts: Opening + Loan Limit + Credits - Debits
        // For Liability accounts: Opening + Credits - Debits
        BigDecimal availableBalance = openingBalance
                .add(loanLimit)
                .add(dateCredits)
                .subtract(dateDebits);
        
        log.info("=== AVAILABLE BALANCE CALCULATION ===");
        log.info("Account: {} (Currency: {})", accountNo, accountCurrency);
        log.info("Opening Balance: {} {}", openingBalance, accountCurrency);
        log.info("Today's Debits: {} {}", dateDebits, accountCurrency);
        log.info("Today's Credits: {} {}", dateCredits, accountCurrency);
        log.info("Loan Limit: {} {}", loanLimit, accountCurrency);
        log.info("Available Balance: {} {}", availableBalance, accountCurrency);

        if (accountInfo.isAssetAccount() && loanLimit.compareTo(BigDecimal.ZERO) > 0) {
            log.debug("Available Balance for ASSET account {} on {}: Opening={}, Loan Limit={}, Credits={}, Debits={}, Available={}",
                    accountNo, systemDate, openingBalance, loanLimit, dateCredits, dateDebits, availableBalance);
        } else {
            log.debug("Available Balance for LIABILITY account {} on {}: Opening={}, Credits={}, Debits={}, Available={}",
                    accountNo, systemDate, openingBalance, dateCredits, dateDebits, availableBalance);
        }

        return availableBalance;
    }
    
    /**
     * Update account balance after a transaction with validation
     * 
     * @param accountNo The account number
     * @param drCrFlag The debit/credit flag
     * @param amount The transaction amount
     * @throws BusinessException if validation fails
     */
    @Transactional
    public void updateAccountBalanceForTransaction(String accountNo, DrCrFlag drCrFlag, BigDecimal amount) {
        // Validate the transaction before updating balance
        validateTransaction(accountNo, drCrFlag, amount);
        
        // Find the account balance
        AcctBal balance = acctBalRepository.findLatestByAccountNo(accountNo)
                .orElseThrow(() -> new BusinessException("Balance for account " + accountNo + " not found"));
        
        // Calculate the balance change
        BigDecimal balanceChange = (drCrFlag == DrCrFlag.D) ? amount.negate() : amount;
        
        // Update the balance
        balance.setCurrentBalance(balance.getCurrentBalance().add(balanceChange));
        balance.setAvailableBalance(calculateAvailableBalance(accountNo, balance.getCurrentBalance()));
        // Replaced device-based date/time with System_Date (SystemDateService) - CBS Compliance Fix
        balance.setLastUpdated(systemDateService.getSystemDateTime());
        
        // Save the updated balance
        acctBalRepository.save(balance);
        
        log.info("Updated balance for account {}: {} {} {}, new balance = {}", 
                accountNo, drCrFlag, amount, balanceChange, balance.getCurrentBalance());
    }

    /**
     * Legacy method for backward compatibility
     * @deprecated Use updateAccountBalanceForTransaction() instead
     */
    @Deprecated
    @Transactional
    public void updateAccountBalance(String accountNo, BigDecimal amount) {
        // Assume positive amount is credit, negative is debit
        DrCrFlag drCrFlag = amount.compareTo(BigDecimal.ZERO) >= 0 ? DrCrFlag.C : DrCrFlag.D;
        updateAccountBalanceForTransaction(accountNo, drCrFlag, amount.abs());
    }
}
