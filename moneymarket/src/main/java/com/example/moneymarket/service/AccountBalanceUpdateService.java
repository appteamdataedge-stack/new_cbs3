package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Service for Batch Job 1: Account Balance Update (EOD)
 * 
 * Updates account balances from Tran_Table entries for both:
 * 1. Acct_Bal - balances in original account currency
 * 2. Acct_Bal_LCY - balances converted to BDT (Local Currency)
 * 
 * Process Flow:
 * 1. Get all accounts from Cust_Acct_Master
 * 2. For each account:
 *    a. Get Opening_Bal from previous day's Closing_Bal
 *    b. Calculate DR_Summation from Tran_Table (0 if no transactions)
 *    c. Calculate CR_Summation from Tran_Table (0 if no transactions)
 *    d. Calculate Closing_Bal = Opening_Bal + CR_Summation - DR_Summation
 *    e. Insert/Update acct_bal with original currency amounts
 *    f. Convert all amounts to BDT and insert/update acct_bal_lcy
 */
@Service
@Slf4j
public class AccountBalanceUpdateService {

    private final AcctBalRepository acctBalRepository;
    private final AcctBalLcyRepository acctBalLcyRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final TranTableRepository tranTableRepository;
    private final SystemDateService systemDateService;
    private final ExchangeRateService exchangeRateService;

    // Self-reference for Spring AOP proxy
    private final AccountBalanceUpdateService self;

    // EntityManager for clearing persistence context
    @PersistenceContext
    private EntityManager entityManager;

    public AccountBalanceUpdateService(
            AcctBalRepository acctBalRepository,
            AcctBalLcyRepository acctBalLcyRepository,
            CustAcctMasterRepository custAcctMasterRepository,
            OFAcctMasterRepository ofAcctMasterRepository,
            TranTableRepository tranTableRepository,
            SystemDateService systemDateService,
            ExchangeRateService exchangeRateService,
            @org.springframework.context.annotation.Lazy AccountBalanceUpdateService self) {
        this.acctBalRepository = acctBalRepository;
        this.acctBalLcyRepository = acctBalLcyRepository;
        this.custAcctMasterRepository = custAcctMasterRepository;
        this.ofAcctMasterRepository = ofAcctMasterRepository;
        this.tranTableRepository = tranTableRepository;
        this.systemDateService = systemDateService;
        this.exchangeRateService = exchangeRateService;
        this.self = self;
    }

    /**
     * Batch Job 1: Account Balance Update (EOD)
     * 
     * Updates both acct_bal and acct_bal_lcy tables
     * 
     * @param systemDate The system date for processing
     * @return Number of accounts processed
     */
    @Transactional(noRollbackFor = {DataIntegrityViolationException.class})
    public int executeAccountBalanceUpdate(LocalDate systemDate) {
        LocalDate processDate = systemDate != null ? systemDate : systemDateService.getSystemDate();
        log.info("Starting Batch Job 1: Account Balance Update for date: {}", processDate);

        // Step 1: Get all customer and office accounts
        List<CustAcctMaster> customerAccounts = custAcctMasterRepository.findAll();
        List<OFAcctMaster> officeAccounts = ofAcctMasterRepository.findAll();

        if (customerAccounts.isEmpty() && officeAccounts.isEmpty()) {
            log.warn("No customer or office accounts found");
            return 0;
        }

        List<String> accountNumbers = new ArrayList<>();
        customerAccounts.forEach(account -> accountNumbers.add(account.getAccountNo()));
        officeAccounts.forEach(account -> accountNumbers.add(account.getAccountNo()));

        log.info("Found {} customer accounts and {} office accounts to process (total: {})",
                customerAccounts.size(), officeAccounts.size(), accountNumbers.size());

        int recordsProcessed = 0;
        List<String> errors = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();

        // Step 2: Process each account with retry logic
        for (String accountNo : accountNumbers) {
            boolean success = false;
            int attempts = 0;
            final int MAX_ATTEMPTS = 3;

            while (!success && attempts < MAX_ATTEMPTS) {
                attempts++;
                try {
                    // Clear entity manager to prevent session cache issues
                    entityManager.flush();
                    entityManager.clear();

                    self.processAccountBalanceInNewTransaction(accountNo, processDate);
                    recordsProcessed++;
                    success = true;

                    if (attempts > 1) {
                        log.info("Account {} processed successfully on attempt {}", accountNo, attempts);
                    }

                } catch (DataIntegrityViolationException e) {
                    log.warn("Duplicate key error for Account {} on attempt {}: {}", accountNo, attempts, e.getMessage());

                    if (attempts < MAX_ATTEMPTS) {
                        // Cleanup: Delete the duplicate record and retry
                        try {
                            self.cleanupDuplicateAccountBalance(accountNo, processDate);
                            log.info("Cleaned up duplicate record for Account {}, retrying...", accountNo);
                        } catch (Exception cleanupEx) {
                            log.error("Failed to cleanup duplicate for Account {}: {}", accountNo, cleanupEx.getMessage());
                        }
                    } else {
                        log.error("Failed to process Account {} after {} attempts due to duplicate key", accountNo, MAX_ATTEMPTS);
                        errors.add(String.format("Account %s: Duplicate key after %d attempts", accountNo, MAX_ATTEMPTS));
                        failedAccounts.add(accountNo);
                    }

                } catch (Exception e) {
                    log.error("Error processing account balance for Account {} on attempt {}: {}", accountNo, attempts, e.getMessage());

                    if (attempts >= MAX_ATTEMPTS) {
                        errors.add(String.format("Account %s: %s", accountNo, e.getMessage()));
                        failedAccounts.add(accountNo);
                    }
                }
            }
        }

        log.info("Batch Job 1 processed {} accounts", recordsProcessed);

        if (!errors.isEmpty()) {
            log.warn("Account balance update completed with {} errors: {}",
                    errors.size(), String.join("; ", errors));
            log.warn("Failed accounts: {}", String.join(", ", failedAccounts));
        }

        log.info("Batch Job 1 completed successfully. Accounts processed: {}, Failed: {}", recordsProcessed, failedAccounts.size());
        return recordsProcessed;
    }

    /**
     * Process account balance for a single account in a new transaction
     * This ensures that failures in one account don't affect others
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAccountBalanceInNewTransaction(String accountNo, LocalDate systemDate) {
        processAccountBalance(accountNo, systemDate);
    }

    /**
     * Cleanup duplicate account balance record in a new transaction
     * Used when retry logic detects a duplicate key error
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupDuplicateAccountBalance(String accountNo, LocalDate tranDate) {
        log.info("Attempting to cleanup duplicate account balance for Account {} on date {}", accountNo, tranDate);

        Optional<AcctBal> existingBalance = acctBalRepository.findByAccountNoAndTranDate(accountNo, tranDate);
        if (existingBalance.isPresent()) {
            acctBalRepository.delete(existingBalance.get());
            acctBalRepository.flush();
            log.info("Successfully deleted duplicate acct_bal for Account {} on date {}", accountNo, tranDate);
        }

        Optional<AcctBalLcy> existingBalanceLcy = acctBalLcyRepository.findByAccountNoAndTranDate(accountNo, tranDate);
        if (existingBalanceLcy.isPresent()) {
            acctBalLcyRepository.delete(existingBalanceLcy.get());
            acctBalLcyRepository.flush();
            log.info("Successfully deleted duplicate acct_bal_lcy for Account {} on date {}", accountNo, tranDate);
        }
    }

    /**
     * Process account balance for a single account
     * Updates both acct_bal and acct_bal_lcy
     */
    private void processAccountBalance(String accountNo, LocalDate systemDate) {
        // Get account currency for both customer and office accounts
        String accountCcy;
        Optional<CustAcctMaster> custAccountOpt = custAcctMasterRepository.findById(accountNo);
        if (custAccountOpt.isPresent()) {
            accountCcy = custAccountOpt.get().getAccountCcy();
        } else {
            OFAcctMaster ofAccount = ofAcctMasterRepository.findById(accountNo)
                    .orElseThrow(() -> new BusinessException("Account not found: " + accountNo));
            accountCcy = ofAccount.getAccountCcy();
        }

        // Step a: Get Opening Balance from previous day's Closing Balance (original currency)
        BigDecimal openingBal = getOpeningBalance(accountNo, systemDate);

        // Step a (LCY): Derive LCY opening from previous day's acct_bal closing balance
        // If currency = BDT, LCY opening equals closing balance
        // If currency = USD/other FCY, convert closing balance to BDT using mid rate
        BigDecimal openingBalLcy;
        if ("BDT".equals(accountCcy)) {
            openingBalLcy = openingBal;
        } else {
            openingBalLcy = exchangeRateService.convertToLCY(openingBal, accountCcy, systemDate);
        }

        /*
         * Step b & c: Calculate DR and CR Summation from Tran_Table
         *
         * FOR acct_bal (original currency amounts):
         *  - Sum FCY_Amt for DR and CR in the account's original currency
         *
         * FOR acct_bal_lcy (LCY/BDT amounts):
         *  - Sum Debit_Amount and Credit_Amount (already in LCY)
         */
        DRCRSummation summation = calculateDRCRSummation(accountNo, systemDate);
        BigDecimal drSummation = summation.drSummation;
        BigDecimal crSummation = summation.crSummation;

        // LCY DR/CR summations (already in LCY)
        BigDecimal drSummationLcy = summation.drSummationLcy;
        BigDecimal crSummationLcy = summation.crSummationLcy;

        // Step d: Calculate Closing Balance
        // Closing_Bal = Opening_Bal + CR_Summation - DR_Summation
        BigDecimal closingBal = openingBal.add(crSummation).subtract(drSummation);
        BigDecimal closingBalLcy = openingBalLcy.add(crSummationLcy).subtract(drSummationLcy);

        log.debug("Account {}: Currency={}, Opening={}, DR={}, CR={}, Closing={}",
                accountNo, accountCcy, openingBal, drSummation, crSummation, closingBal);
        log.debug("Account {} (LCY): Opening={}, DR={}, CR={}, Closing={}",
                accountNo, openingBalLcy, drSummationLcy, crSummationLcy, closingBalLcy);

        // Step e: Save to acct_bal (original currency)
        saveOrUpdateAcctBal(accountNo, systemDate, accountCcy, openingBal, drSummation, crSummation, closingBal);

        // Step f: Save to acct_bal_lcy (BDT)
        saveOrUpdateAcctBalLcy(accountNo, systemDate, openingBalLcy, drSummationLcy, crSummationLcy, closingBalLcy);
    }

    /**
     * Get opening balance for account using 3-tier fallback logic
     * 
     * Tier 1: Previous day's record (systemDate - 1)
     * Tier 2: Last transaction date (MAX(Tran_Date) < systemDate)
     * Tier 3: New account (return 0)
     */
    private BigDecimal getOpeningBalance(String accountNo, LocalDate systemDate) {
        List<AcctBal> balances = acctBalRepository
                .findByAccountNoAndTranDateBeforeOrderByTranDateDesc(accountNo, systemDate);

        if (balances.isEmpty()) {
            log.info("3-Tier Fallback [Tier 3 - New Account]: Account {} has no previous records. Using Opening_Bal = 0", accountNo);
            return BigDecimal.ZERO;
        }

        LocalDate previousDay = systemDate.minusDays(1);
        Optional<AcctBal> previousDayRecord = balances.stream()
                .filter(bal -> previousDay.equals(bal.getTranDate()))
                .findFirst();

        if (previousDayRecord.isPresent()) {
            BigDecimal closingBal = previousDayRecord.get().getClosingBal();
            log.debug("3-Tier Fallback [Tier 1 - Previous Day]: Account {} found record for {} with Closing_Bal = {}",
                    accountNo, previousDay, closingBal);
            return closingBal != null ? closingBal : BigDecimal.ZERO;
        }

        AcctBal lastRecord = balances.get(0);
        BigDecimal lastClosingBal = lastRecord.getClosingBal();
        log.warn("3-Tier Fallback [Tier 2 - Last Transaction]: Account {} previous day {} not found. " +
                "Using last Closing_Bal from {} = {}",
                accountNo, previousDay, lastRecord.getTranDate(), lastClosingBal);

        return lastClosingBal != null ? lastClosingBal : BigDecimal.ZERO;
    }

    /**
     * Get opening balance in LCY for account using 3-tier fallback logic
     */
    private BigDecimal getOpeningBalanceLcy(String accountNo, LocalDate systemDate) {
        List<AcctBalLcy> balances = acctBalLcyRepository
                .findByAccountNoAndTranDateBeforeOrderByTranDateDesc(accountNo, systemDate);

        if (balances.isEmpty()) {
            log.info("3-Tier Fallback [Tier 3 - New Account]: Account {} has no previous LCY records. Using Opening_Bal_lcy = 0", accountNo);
            return BigDecimal.ZERO;
        }

        LocalDate previousDay = systemDate.minusDays(1);
        Optional<AcctBalLcy> previousDayRecord = balances.stream()
                .filter(bal -> previousDay.equals(bal.getTranDate()))
                .findFirst();

        if (previousDayRecord.isPresent()) {
            BigDecimal closingBalLcy = previousDayRecord.get().getClosingBalLcy();
            log.debug("3-Tier Fallback [Tier 1 - Previous Day]: Account {} found LCY record for {} with Closing_Bal_lcy = {}",
                    accountNo, previousDay, closingBalLcy);
            return closingBalLcy != null ? closingBalLcy : BigDecimal.ZERO;
        }

        AcctBalLcy lastRecord = balances.get(0);
        BigDecimal lastClosingBalLcy = lastRecord.getClosingBalLcy();
        log.warn("3-Tier Fallback [Tier 2 - Last Transaction]: Account {} previous day {} not found. " +
                "Using last Closing_Bal_lcy from {} = {}",
                accountNo, previousDay, lastRecord.getTranDate(), lastClosingBalLcy);

        return lastClosingBalLcy != null ? lastClosingBalLcy : BigDecimal.ZERO;
    }

    /**
     * Calculate DR and CR summation from Tran_Table.
     *
     * FOR acct_bal (original currency amounts):
     *  - Sum FCY_Amt for DR and CR in the account's original currency
     *
     * FOR acct_bal_lcy (LCY/BDT amounts):
     *  - Sum Debit_Amount and Credit_Amount (already in LCY)
     */
    private DRCRSummation calculateDRCRSummation(String accountNo, LocalDate systemDate) {
        // Get all transactions for this account on this date
        List<TranTable> transactions = tranTableRepository.findByAccountNoAndTranDate(accountNo, systemDate);

        BigDecimal drSummation = BigDecimal.ZERO;
        BigDecimal crSummation = BigDecimal.ZERO;
        BigDecimal drSummationLcy = BigDecimal.ZERO;
        BigDecimal crSummationLcy = BigDecimal.ZERO;

        for (TranTable tran : transactions) {
            BigDecimal fcyAmt = tran.getFcyAmt() != null ? tran.getFcyAmt() : BigDecimal.ZERO;
            BigDecimal debitAmt = tran.getDebitAmount() != null ? tran.getDebitAmount() : BigDecimal.ZERO;
            BigDecimal creditAmt = tran.getCreditAmount() != null ? tran.getCreditAmount() : BigDecimal.ZERO;

            if (tran.getDrCrFlag() == TranTable.DrCrFlag.D) {
                // acct_bal: DR summation in original currency (FCY_Amt)
                drSummation = drSummation.add(fcyAmt);
                // acct_bal_lcy: DR summation in LCY (Debit_Amount)
                drSummationLcy = drSummationLcy.add(debitAmt);
            } else if (tran.getDrCrFlag() == TranTable.DrCrFlag.C) {
                // acct_bal: CR summation in original currency (FCY_Amt)
                crSummation = crSummation.add(fcyAmt);
                // acct_bal_lcy: CR summation in LCY (Credit_Amount)
                crSummationLcy = crSummationLcy.add(creditAmt);
            }
        }

        log.debug("Account {}: DR={}, CR={}, DR_LCY={}, CR_LCY={}",
                accountNo, drSummation, crSummation, drSummationLcy, crSummationLcy);

        return new DRCRSummation(drSummation, crSummation, drSummationLcy, crSummationLcy);
    }

    /**
     * Save or update acct_bal record (original currency)
     */
    private void saveOrUpdateAcctBal(String accountNo, LocalDate tranDate, String accountCcy,
                                     BigDecimal openingBal, BigDecimal drSummation,
                                     BigDecimal crSummation, BigDecimal closingBal) {
        Optional<AcctBal> existingBalOpt = acctBalRepository.findByAccountNoAndTranDate(accountNo, tranDate);

        AcctBal acctBal;

        if (existingBalOpt.isPresent()) {
            acctBal = existingBalOpt.get();
            acctBal.setAccountCcy(accountCcy);
            acctBal.setOpeningBal(openingBal);
            acctBal.setDrSummation(drSummation);
            acctBal.setCrSummation(crSummation);
            acctBal.setClosingBal(closingBal);
            acctBal.setCurrentBalance(closingBal);
            acctBal.setAvailableBalance(closingBal);
            acctBal.setLastUpdated(systemDateService.getSystemDateTime());
            log.debug("Updated existing acct_bal for Account {}", accountNo);
        } else {
            acctBal = AcctBal.builder()
                    .tranDate(tranDate)
                    .accountNo(accountNo)
                    .accountCcy(accountCcy)
                    .openingBal(openingBal)
                    .drSummation(drSummation)
                    .crSummation(crSummation)
                    .closingBal(closingBal)
                    .currentBalance(closingBal)
                    .availableBalance(closingBal)
                    .lastUpdated(systemDateService.getSystemDateTime())
                    .build();
            log.debug("Created new acct_bal for Account {}", accountNo);
        }

        acctBalRepository.save(acctBal);
    }

    /**
     * Save or update acct_bal_lcy record (BDT)
     */
    private void saveOrUpdateAcctBalLcy(String accountNo, LocalDate tranDate,
                                        BigDecimal openingBalLcy, BigDecimal drSummationLcy,
                                        BigDecimal crSummationLcy, BigDecimal closingBalLcy) {
        Optional<AcctBalLcy> existingBalLcyOpt = acctBalLcyRepository.findByAccountNoAndTranDate(accountNo, tranDate);

        AcctBalLcy acctBalLcy;

        if (existingBalLcyOpt.isPresent()) {
            acctBalLcy = existingBalLcyOpt.get();
            acctBalLcy.setOpeningBalLcy(openingBalLcy);
            acctBalLcy.setDrSummationLcy(drSummationLcy);
            acctBalLcy.setCrSummationLcy(crSummationLcy);
            acctBalLcy.setClosingBalLcy(closingBalLcy);
            acctBalLcy.setAvailableBalanceLcy(closingBalLcy);
            acctBalLcy.setLastUpdated(systemDateService.getSystemDateTime());
            log.debug("Updated existing acct_bal_lcy for Account {}", accountNo);
        } else {
            acctBalLcy = AcctBalLcy.builder()
                    .tranDate(tranDate)
                    .accountNo(accountNo)
                    .openingBalLcy(openingBalLcy)
                    .drSummationLcy(drSummationLcy)
                    .crSummationLcy(crSummationLcy)
                    .closingBalLcy(closingBalLcy)
                    .availableBalanceLcy(closingBalLcy)
                    .lastUpdated(systemDateService.getSystemDateTime())
                    .build();
            log.debug("Created new acct_bal_lcy for Account {}", accountNo);
        }

        acctBalLcyRepository.save(acctBalLcy);
    }

    /**
     * Get previous day's closing balance for an account
     * Used by BalanceService and TransactionValidationService
     * 
     * @param accountNo The account number
     * @param date The reference date (will get previous day's balance)
     * @return Previous day's closing balance, or ZERO if not found
     */
    public BigDecimal getPreviousDayClosingBalance(String accountNo, LocalDate date) {
        LocalDate previousDay = date.minusDays(1);
        
        log.debug("Getting previous day closing balance for account {} on date {} (previous day: {})", 
                accountNo, date, previousDay);
        
        Optional<AcctBal> previousBalance = acctBalRepository.findByAccountNoAndTranDate(accountNo, previousDay);
        
        if (previousBalance.isEmpty()) {
            log.debug("No balance found for account {} on previous day {}. Returning ZERO", accountNo, previousDay);
            return BigDecimal.ZERO;
        }
        
        BigDecimal closingBal = previousBalance.get().getClosingBal();
        
        if (closingBal == null) {
            log.warn("Closing balance is NULL for account {} on {}. Returning ZERO", accountNo, previousDay);
            return BigDecimal.ZERO;
        }
        
        log.debug("Previous day closing balance for account {}: {}", accountNo, closingBal);
        return closingBal;
    }

    /**
     * Validate that account balance update can proceed for the given date
     * Used by AdminController for pre-validation before running balance updates
     * 
     * @param date The date to validate
     * @return true if validation passes
     * @throws BusinessException if validation fails
     */
    public boolean validateAccountBalanceUpdate(LocalDate date) {
        log.info("Validating account balance update for date: {}", date);
        
        // Validation 1: Check if date is not in the future
        LocalDate systemDate = systemDateService.getSystemDate();
        if (date.isAfter(systemDate)) {
            String errorMsg = String.format("Cannot update balances for future date %s. System date is %s", 
                    date, systemDate);
            log.error(errorMsg);
            throw new BusinessException(errorMsg);
        }
        
        // Validation 2: Check if there are any transactions for this date
        List<TranTable> transactions = tranTableRepository.findByTranDateBetween(date, date);
        if (transactions.isEmpty()) {
            log.warn("No transactions found for date {}. Balance update may not be necessary", date);
        } else {
            log.info("Found {} transactions for date {}", transactions.size(), date);
        }
        
        // Validation 3: Check if there are any customer accounts
        long accountCount = custAcctMasterRepository.count();
        if (accountCount == 0) {
            String errorMsg = "No customer accounts found in the system";
            log.error(errorMsg);
            throw new BusinessException(errorMsg);
        }
        
        log.info("Validation passed for account balance update on date {}. {} accounts will be processed", 
                date, accountCount);
        
        return true;
    }

    /**
     * Helper class to hold DR and CR summation results
     */
    private static class DRCRSummation {
        final BigDecimal drSummation;
        final BigDecimal crSummation;
        final BigDecimal drSummationLcy;
        final BigDecimal crSummationLcy;

        DRCRSummation(BigDecimal drSummation, BigDecimal crSummation, 
                     BigDecimal drSummationLcy, BigDecimal crSummationLcy) {
            this.drSummation = drSummation;
            this.crSummation = crSummation;
            this.drSummationLcy = drSummationLcy;
            this.crSummationLcy = crSummationLcy;
        }
    }
}
