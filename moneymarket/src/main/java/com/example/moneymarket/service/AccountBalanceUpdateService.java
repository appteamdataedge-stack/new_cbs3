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
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
        LocalDateTime lastUpdated = systemDateService.getSystemDateTime();
        log.info("Starting Batch Job 1: Account Balance Update for date: {}", processDate);

        // Step 1: Get all customer and office accounts
        List<CustAcctMaster> customerAccounts = custAcctMasterRepository.findAll();
        List<OFAcctMaster> officeAccounts = ofAcctMasterRepository.findAll();

        if (customerAccounts.isEmpty() && officeAccounts.isEmpty()) {
            log.warn("No customer or office accounts found");
            return 0;
        }

        List<String> accountNumbers = new ArrayList<>();
        Map<String, String> accountCcyMap = new HashMap<>();
        customerAccounts.forEach(a -> {
            if (a.getAccountNo() != null) {
                accountNumbers.add(a.getAccountNo());
                accountCcyMap.put(a.getAccountNo(), a.getAccountCcy() != null ? a.getAccountCcy() : "BDT");
            }
        });
        officeAccounts.forEach(a -> {
            if (a.getAccountNo() != null) {
                accountNumbers.add(a.getAccountNo());
                accountCcyMap.put(a.getAccountNo(), a.getAccountCcy() != null ? a.getAccountCcy() : "BDT");
            }
        });

        log.info("Found {} customer accounts and {} office accounts to process (total: {})",
                customerAccounts.size(), officeAccounts.size(), accountNumbers.size());

        // Step 2: Batch-fetch all data (one query each instead of per-account)
        // Filter null accountNo (e.g. FX gain/loss GL legs) to avoid "element cannot be mapped to a null key"
        List<TranTable> allTransactionsForDate = tranTableRepository.findByTranDateBetween(processDate, processDate);
        Map<String, List<TranTable>> transactionsByAccount = allTransactionsForDate.stream()
                .filter(t -> t.getAccountNo() != null)
                .collect(Collectors.groupingBy(TranTable::getAccountNo));

        List<AcctBal> allBalancesBefore = acctBalRepository.findByTranDateLessThanOrderByAccountNoAscTranDateDesc(processDate);
        Map<String, BigDecimal> openingBalMap = buildOpeningBalanceMap(allBalancesBefore, processDate);

        List<AcctBalLcy> allLcyBefore = acctBalLcyRepository.findByTranDateLessThanOrderByAccountNoAscTranDateDesc(processDate);
        Map<String, BigDecimal> openingBalLcyMap = buildOpeningBalanceLcyMap(allLcyBefore, processDate);

        List<AcctBal> existingAcctBalForDate = acctBalRepository.findByTranDate(processDate);
        Map<String, AcctBal> existingAcctBalMap = existingAcctBalForDate.stream()
                .filter(b -> b.getAccountNo() != null)
                .collect(Collectors.toMap(AcctBal::getAccountNo, b -> b, (a, b) -> a));

        List<AcctBalLcy> existingAcctBalLcyForDate = acctBalLcyRepository.findByTranDate(processDate);
        Map<String, AcctBalLcy> existingAcctBalLcyMap = existingAcctBalLcyForDate.stream()
                .filter(b -> b.getAccountNo() != null)
                .collect(Collectors.toMap(AcctBalLcy::getAccountNo, b -> b, (a, b) -> a));

        BatchContext ctx = new BatchContext(openingBalMap, openingBalLcyMap, existingAcctBalMap, existingAcctBalLcyMap,
                transactionsByAccount, accountCcyMap, lastUpdated);

        int recordsProcessed = 0;
        List<String> errors = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();

        // Step 3: Process each account in same transaction (no per-account DB fetch)
        for (String accountNo : accountNumbers) {
            if (accountNo == null) {
                log.warn("Null accountNo found in account list — skipping");
                continue;
            }
            try {
                processAccountBalanceWithContext(accountNo, processDate, ctx);
                recordsProcessed++;
            } catch (Exception e) {
                log.error("Error processing account balance for Account {}: {}", accountNo, e.getMessage());
                errors.add(String.format("Account %s: %s", accountNo, e.getMessage()));
                failedAccounts.add(accountNo);
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

    private Map<String, BigDecimal> buildOpeningBalanceMap(List<AcctBal> balancesBefore, LocalDate processDate) {
        LocalDate previousDay = processDate.minusDays(1);
        Map<String, BigDecimal> map = new HashMap<>();
        Map<String, List<AcctBal>> byAccount = balancesBefore.stream()
                .filter(b -> b.getAccountNo() != null)
                .collect(Collectors.groupingBy(AcctBal::getAccountNo));
        for (Map.Entry<String, List<AcctBal>> e : byAccount.entrySet()) {
            List<AcctBal> list = e.getValue();
            Optional<AcctBal> prevDay = list.stream().filter(b -> previousDay.equals(b.getTranDate())).findFirst();
            if (prevDay.isPresent()) {
                map.put(e.getKey(), prevDay.get().getClosingBal() != null ? prevDay.get().getClosingBal() : BigDecimal.ZERO);
            } else if (!list.isEmpty()) {
                map.put(e.getKey(), list.get(0).getClosingBal() != null ? list.get(0).getClosingBal() : BigDecimal.ZERO);
            } else {
                map.put(e.getKey(), BigDecimal.ZERO);
            }
        }
        return map;
    }

    private Map<String, BigDecimal> buildOpeningBalanceLcyMap(List<AcctBalLcy> balancesBefore, LocalDate processDate) {
        LocalDate previousDay = processDate.minusDays(1);
        Map<String, BigDecimal> map = new HashMap<>();
        Map<String, List<AcctBalLcy>> byAccount = balancesBefore.stream()
                .filter(b -> b.getAccountNo() != null)
                .collect(Collectors.groupingBy(AcctBalLcy::getAccountNo));
        for (Map.Entry<String, List<AcctBalLcy>> e : byAccount.entrySet()) {
            List<AcctBalLcy> list = e.getValue();
            Optional<AcctBalLcy> prevDay = list.stream().filter(b -> previousDay.equals(b.getTranDate())).findFirst();
            if (prevDay.isPresent()) {
                map.put(e.getKey(), prevDay.get().getClosingBalLcy() != null ? prevDay.get().getClosingBalLcy() : BigDecimal.ZERO);
            } else if (!list.isEmpty()) {
                map.put(e.getKey(), list.get(0).getClosingBalLcy() != null ? list.get(0).getClosingBalLcy() : BigDecimal.ZERO);
            } else {
                map.put(e.getKey(), BigDecimal.ZERO);
            }
        }
        return map;
    }

    /**
     * Process one account using pre-fetched batch context (no per-account DB reads for balance/transactions).
     */
    private void processAccountBalanceWithContext(String accountNo, LocalDate systemDate, BatchContext ctx) {
        if (accountNo == null) {
            log.warn("Skipping account with null accountNo during batch processing");
            return;
        }
        String accountCcy = ctx.accountCcyMap.getOrDefault(accountNo, "BDT");
        BigDecimal openingBal = ctx.openingBalMap.getOrDefault(accountNo, BigDecimal.ZERO);
        BigDecimal openingBalLcy = "BDT".equals(accountCcy)
                ? openingBal
                : ctx.openingBalLcyMap.getOrDefault(accountNo, BigDecimal.ZERO);

        List<TranTable> transactions = ctx.transactionsByAccount.getOrDefault(accountNo, Collections.emptyList());
        DRCRSummation summation = calculateDRCRSummationFromList(transactions);
        BigDecimal closingBal = openingBal.add(summation.crSummation).subtract(summation.drSummation);
        BigDecimal closingBalLcy = openingBalLcy.add(summation.crSummationLcy).subtract(summation.drSummationLcy);

        saveOrUpdateAcctBalWithContext(accountNo, systemDate, accountCcy, openingBal, summation.drSummation,
                summation.crSummation, closingBal, ctx.existingAcctBalMap.get(accountNo), ctx.lastUpdated);
        saveOrUpdateAcctBalLcyWithContext(accountNo, systemDate, openingBalLcy, summation.drSummationLcy,
                summation.crSummationLcy, closingBalLcy, ctx.existingAcctBalLcyMap.get(accountNo), ctx.lastUpdated);
    }

    private DRCRSummation calculateDRCRSummationFromList(List<TranTable> transactions) {
        BigDecimal drSummation = BigDecimal.ZERO;
        BigDecimal crSummation = BigDecimal.ZERO;
        BigDecimal drSummationLcy = BigDecimal.ZERO;
        BigDecimal crSummationLcy = BigDecimal.ZERO;
        for (TranTable tran : transactions) {
            BigDecimal fcyAmt = tran.getFcyAmt() != null ? tran.getFcyAmt() : BigDecimal.ZERO;
            BigDecimal debitAmt = tran.getDebitAmount() != null ? tran.getDebitAmount() : BigDecimal.ZERO;
            BigDecimal creditAmt = tran.getCreditAmount() != null ? tran.getCreditAmount() : BigDecimal.ZERO;
            if (tran.getDrCrFlag() == TranTable.DrCrFlag.D) {
                drSummation = drSummation.add(fcyAmt);
                drSummationLcy = drSummationLcy.add(debitAmt);
            } else if (tran.getDrCrFlag() == TranTable.DrCrFlag.C) {
                crSummation = crSummation.add(fcyAmt);
                crSummationLcy = crSummationLcy.add(creditAmt);
            }
        }
        return new DRCRSummation(drSummation, crSummation, drSummationLcy, crSummationLcy);
    }

    private void saveOrUpdateAcctBalWithContext(String accountNo, LocalDate tranDate, String accountCcy,
                                                 BigDecimal openingBal, BigDecimal drSummation,
                                                 BigDecimal crSummation, BigDecimal closingBal,
                                                 AcctBal existing, LocalDateTime lastUpdated) {
        AcctBal acctBal;
        if (existing != null) {
            acctBal = existing;
            acctBal.setAccountCcy(accountCcy);
            acctBal.setOpeningBal(openingBal);
            acctBal.setDrSummation(drSummation);
            acctBal.setCrSummation(crSummation);
            acctBal.setClosingBal(closingBal);
            acctBal.setCurrentBalance(closingBal);
            acctBal.setAvailableBalance(closingBal);
            acctBal.setLastUpdated(lastUpdated);
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
                    .lastUpdated(lastUpdated)
                    .build();
        }
        acctBalRepository.save(acctBal);
    }

    private void saveOrUpdateAcctBalLcyWithContext(String accountNo, LocalDate tranDate,
                                                    BigDecimal openingBalLcy, BigDecimal drSummationLcy,
                                                    BigDecimal crSummationLcy, BigDecimal closingBalLcy,
                                                    AcctBalLcy existing, LocalDateTime lastUpdated) {
        AcctBalLcy acctBalLcy;
        if (existing != null) {
            acctBalLcy = existing;
            acctBalLcy.setOpeningBalLcy(openingBalLcy);
            acctBalLcy.setDrSummationLcy(drSummationLcy);
            acctBalLcy.setCrSummationLcy(crSummationLcy);
            acctBalLcy.setClosingBalLcy(closingBalLcy);
            acctBalLcy.setAvailableBalanceLcy(closingBalLcy);
            acctBalLcy.setLastUpdated(lastUpdated);
        } else {
            acctBalLcy = AcctBalLcy.builder()
                    .tranDate(tranDate)
                    .accountNo(accountNo)
                    .openingBalLcy(openingBalLcy)
                    .drSummationLcy(drSummationLcy)
                    .crSummationLcy(crSummationLcy)
                    .closingBalLcy(closingBalLcy)
                    .availableBalanceLcy(closingBalLcy)
                    .lastUpdated(lastUpdated)
                    .build();
        }
        acctBalLcyRepository.save(acctBalLcy);
    }

    private static class BatchContext {
        final Map<String, BigDecimal> openingBalMap;
        final Map<String, BigDecimal> openingBalLcyMap;
        final Map<String, AcctBal> existingAcctBalMap;
        final Map<String, AcctBalLcy> existingAcctBalLcyMap;
        final Map<String, List<TranTable>> transactionsByAccount;
        final Map<String, String> accountCcyMap;
        final LocalDateTime lastUpdated;

        BatchContext(Map<String, BigDecimal> openingBalMap, Map<String, BigDecimal> openingBalLcyMap,
                     Map<String, AcctBal> existingAcctBalMap, Map<String, AcctBalLcy> existingAcctBalLcyMap,
                     Map<String, List<TranTable>> transactionsByAccount, Map<String, String> accountCcyMap,
                     LocalDateTime lastUpdated) {
            this.openingBalMap = openingBalMap;
            this.openingBalLcyMap = openingBalLcyMap;
            this.existingAcctBalMap = existingAcctBalMap;
            this.existingAcctBalLcyMap = existingAcctBalLcyMap;
            this.transactionsByAccount = transactionsByAccount;
            this.accountCcyMap = accountCcyMap;
            this.lastUpdated = lastUpdated;
        }
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

        // Step a (LCY): Carry forward previous day's LCY closing balance (historical cost basis).
        // For BDT accounts: LCY opening equals FCY opening (same currency).
        // For FCY accounts: use the previous day's actual closingBalLcy — NOT MID-converted —
        // so that WAE = closingBalLcy / closingBal reflects true acquisition cost, not current MID rate.
        BigDecimal openingBalLcy;
        if ("BDT".equals(accountCcy)) {
            openingBalLcy = openingBal;
        } else {
            openingBalLcy = getOpeningBalanceLcy(accountNo, systemDate);
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
