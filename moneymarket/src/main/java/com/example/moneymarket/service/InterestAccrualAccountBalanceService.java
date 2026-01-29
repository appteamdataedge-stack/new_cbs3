package com.example.moneymarket.service;

import com.example.moneymarket.entity.AcctBalAccrual;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.SubProdMaster;
import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.AcctBalAccrualRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.InttAccrTranRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Service for Batch Job 6: Interest Accrual Account Balance Update
 * Processes interest accrual transactions into account balance accrual records
 * with GL_Num-based conditional DR/CR summation logic
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterestAccrualAccountBalanceService {

    private final InttAccrTranRepository inttAccrTranRepository;
    private final AcctBalAccrualRepository acctBalAccrualRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final SystemDateService systemDateService;

    /**
     * Batch Job 6: Interest Accrual Account Balance Update
     *
     * Process:
     * 1. Get unique Account_No from intt_accr_tran where Accrual_Date = System_Date
     *
     * 2. For each Account_No:
     *    a. Get GL_Num from account's sub-product
     *    b. Determine account type (Liability=1, Asset=2)
     *    c. Get Opening_Bal from previous day's Closing_Bal
     *    d. Calculate DR_Summation (conditional based on GL_Num)
     *    e. Calculate CR_Summation (conditional based on GL_Num)
     *    f. Calculate Closing_Bal = Opening_Bal + CR_Summation - DR_Summation
     *    g. Insert/Update acct_bal_accrual with GL_Num
     *
     * @param systemDate The system date for processing
     * @return Number of accounts processed
     */
    @Transactional
    public int updateInterestAccrualAccountBalances(LocalDate systemDate) {
        LocalDate processDate = systemDate != null ? systemDate : systemDateService.getSystemDate();
        log.info("Starting Batch Job 6: Interest Accrual Account Balance Update for date: {}", processDate);
        log.info("=== PROCESSING BOTH 'S' (ACCRUAL) AND 'C' (CAPITALIZATION) TRANSACTIONS ===");

        // Step 1: Get unique account numbers from interest accrual transactions
        // This includes ALL transactions: "S" (accrual), "C" (capitalization), etc.
        List<String> accountNumbers = inttAccrTranRepository.findDistinctAccountsByAccrualDate(processDate);

        if (accountNumbers.isEmpty()) {
            log.info("No interest accrual transactions found for date: {}", processDate);
            return 0;
        }

        log.info("Found {} unique accounts with interest accruals", accountNumbers.size());

        int recordsProcessed = 0;
        List<String> errors = new ArrayList<>();

        // Step 2: Process each account
        for (String accountNo : accountNumbers) {
            try {
                processAccountAccrualBalance(accountNo, processDate);
                recordsProcessed++;
            } catch (Exception e) {
                log.error("Error processing accrual balance for account {}: {}", accountNo, e.getMessage(), e);
                errors.add(String.format("Account %s: %s", accountNo, e.getMessage()));
            }
        }

        log.info("Batch Job 6 completed. Records processed: {}, Errors: {}",
                recordsProcessed, errors.size());

        if (!errors.isEmpty()) {
            log.warn("Accrual account balance process completed with {} errors: {}",
                    errors.size(), String.join("; ", errors));
        }

        return recordsProcessed;
    }

    /**
     * Process accrual balance for a single account
     * 
     * CRITICAL: Calculates BOTH DR and CR summations for all account types
     * to support Interest Capitalization ("C" transactions)
     * 
     * Formula: Closing_Bal = Opening_Bal + CR_Summation - DR_Summation
     */
    private void processAccountAccrualBalance(String accountNo, LocalDate systemDate) {

        // Step A: Get GL_Num from account's sub-product
        String glNum = getGLNumberForAccount(accountNo);
        log.debug("Account {}: GL_Num = {}", accountNo, glNum);

        // Step B: Determine account type (for value date interest calculation only)
        boolean isLiability = glNum.startsWith("1");
        boolean isAsset = glNum.startsWith("2");

        if (!isLiability && !isAsset) {
            throw new BusinessException("Invalid GL_Num: " + glNum +
                    " - must start with '1' (Liability) or '2' (Asset)");
        }

        String accountType = isLiability ? "LIABILITY" : "ASSET";
        log.debug("Account {}: Type = {}", accountNo, accountType);

        // Step C: Get Opening Balance
        BigDecimal openingBal = getOpeningBalance(accountNo, systemDate);
        
        log.info("========================================");
        log.info("=== EOD BATCH JOB 6: PROCESSING ACCOUNT ===");
        log.info("========================================");
        log.info("Account Number: {}", accountNo);
        log.info("Account Type: {}", accountType);
        log.info("GL Number: {}", glNum);
        log.info("System Date: {}", systemDate);
        log.info("Opening Balance (from previous day): {}", openingBal);

        // Step D & E: Calculate DR and CR Summations
        // CRITICAL FIX: Filter by BOTH transaction type AND Dr/Cr flag
        // This ensures correct summation for accrual vs capitalization transactions
        // 
        // CR_Summation (Daily Accrual):
        // - Accr_Tran_Id LIKE 'S%' (System interest accrual transactions)
        // - Dr_Cr_Flag = 'C' (Credit)
        // - Original_Dr_Cr_Flag IS NULL (excludes value date interest)
        // - Example: S20260129001 with CR flag = daily interest credit
        //
        // DR_Summation (Capitalization):
        // - Accr_Tran_Id LIKE 'C%' (Interest capitalization transactions)
        // - Dr_Cr_Flag = 'D' (Debit)
        // - Original_Dr_Cr_Flag IS NULL (excludes value date interest)
        // - Example: C20260129001 with DR flag = capitalization debit
        //
        // This prevents cross-contamination between S and C type transactions
        
        // Query repository for DR and CR summations with transaction type filters
        log.info("--- Querying intt_accr_tran for DR and CR summations ---");
        
        BigDecimal crSummation = inttAccrTranRepository.sumCreditAmountsByAccountAndDate(accountNo, systemDate);
        log.info("CR Summation (ONLY S type + CR flag, excludes value date): {}", crSummation);
        
        BigDecimal drSummation = inttAccrTranRepository.sumDebitAmountsByAccountAndDate(accountNo, systemDate);
        log.info("DR Summation (ONLY C type + DR flag, excludes value date): {}", drSummation);
        
        // Get all transactions for debugging
        List<com.example.moneymarket.entity.InttAccrTran> allTransactions = 
            inttAccrTranRepository.findByAccountNoAndAccrualDate(accountNo, systemDate);
        log.info("Total transactions in intt_accr_tran for this account/date: {}", allTransactions.size());
        for (com.example.moneymarket.entity.InttAccrTran tran : allTransactions) {
            log.info("  Transaction: ID={}, DrCrFlag={}, Amount={}, OriginalDrCrFlag={}", 
                tran.getAccrTranId(), tran.getDrCrFlag(), tran.getAmount(), tran.getOriginalDrCrFlag());
        }

        // Step F: Calculate Value Date Interest Impact (NEW LOGIC)
        // Value date interest needs special handling based on original transaction Dr/Cr flag
        BigDecimal valueDateInterestImpact = calculateValueDateInterestImpact(accountNo, systemDate, isLiability);

        log.info("Value Date Interest Impact: {}", valueDateInterestImpact);

        // REMOVED VALIDATION: Both DR and CR can be non-zero when capitalization occurs
        // Example: CR=5 (daily accrual "S"), DR=45 (capitalization "C")
        // This is CORRECT and expected behavior!

        // Step G: Calculate Closing Balance
        // Closing_Bal = Opening_Bal + CR_Summation - DR_Summation + Value_Date_Interest_Impact
        BigDecimal closingBal = openingBal.add(crSummation).subtract(drSummation).add(valueDateInterestImpact);

        // Step H: Calculate Interest Amount
        // Interest_Amount = CR_Summation - DR_Summation + Value_Date_Interest_Impact
        BigDecimal interestAmount = crSummation.subtract(drSummation).add(valueDateInterestImpact);

        log.info("Formula: closing_bal = opening_bal + cr_summation - dr_summation + value_date_impact");
        log.info("Formula: {} = {} + {} - {} + {}", closingBal, openingBal, crSummation, drSummation, valueDateInterestImpact);
        log.info("Calculated Closing Balance: {}", closingBal);
        log.info("Calculated Interest Amount: {}", interestAmount);
        log.info("=== VALUES BEING SAVED TO ACCT_BAL_ACCRUAL ===");
        log.info("opening_bal: {}", openingBal);
        log.info("dr_summation: {} (should ONLY be sum of DR transactions)", drSummation);
        log.info("cr_summation: {} (should ONLY be sum of CR transactions)", crSummation);
        log.info("closing_bal: {}", closingBal);
        log.info("interest_amount: {}", interestAmount);

        // Step H: Save Record with GL_Num
        saveOrUpdateAccrualBalance(accountNo, glNum, systemDate, openingBal, drSummation,
                crSummation, closingBal, interestAmount);
    }

    /**
     * Get GL Number for Account
     * Query path: cust_acct_master -> Sub_Product_Id -> sub_prod_master -> Cum_GL_Num
     *
     * @param accountNo The account number
     * @return GL_Num from sub-product (9 characters)
     */
    private String getGLNumberForAccount(String accountNo) {
        // Step 1: Get account with sub-product
        CustAcctMaster account = custAcctMasterRepository.findById(accountNo)
                .orElseThrow(() -> new BusinessException("Account not found: " + accountNo));

        // Step 2: Get sub-product
        SubProdMaster subProduct = account.getSubProduct();
        if (subProduct == null) {
            throw new BusinessException("Sub-product not found for account: " + accountNo);
        }

        // Step 3: Get Cum_GL_Num
        String cumGlNum = subProduct.getCumGLNum();
        if (cumGlNum == null || cumGlNum.trim().isEmpty()) {
            throw new BusinessException("Cum_GL_Num is null or empty for account: " + accountNo +
                    ", Sub-Product: " + subProduct.getSubProductId());
        }

        // Validate GL_Num length
        if (cumGlNum.length() != 9) {
            throw new BusinessException("Invalid GL_Num length: " + cumGlNum +
                    " (expected 9 characters) for account: " + accountNo);
        }

        return cumGlNum;
    }

    // REMOVED: calculateDRSummation() and calculateCRSummation() methods
    // These methods used conditional logic that set one summation to 0 based on account type
    // This prevented proper handling of Interest Capitalization ("C" transactions)
    // 
    // New approach: Always calculate BOTH DR and CR summations directly from repository
    // This allows:
    // - Liability accounts to process both accrual (CR) and capitalization (DR)
    // - Asset accounts to process both accrual (DR) and capitalization (DR)
    //
    // The repository queries handle filtering correctly:
    // - sumDebitAmountsByAccountAndDate: Sums all DR transactions (both "S" and "C" types)
    // - sumCreditAmountsByAccountAndDate: Sums all CR transactions (mainly "S" types)

    /**
     * Calculate Value Date Interest Impact on Balance
     *
     * Value date interest must be processed differently from regular interest
     * because the balance impact depends on the ORIGINAL transaction's Dr/Cr flag,
     * not just the accrual entry's Dr/Cr flag.
     *
     * IMPORTANT FIX: Only processes records where Dr_Cr_Flag = Original_Dr_Cr_Flag
     * This prevents double counting because value date interest creates TWO records:
     * 1. Balance Sheet record (Dr_Cr_Flag = Original_Dr_Cr_Flag) - affects account balance
     * 2. P&L record (Dr_Cr_Flag != Original_Dr_Cr_Flag) - does not affect account balance
     * 
     * Only the Balance Sheet record should be included in account balance calculation.
     *
     * Logic:
     * LIABILITY Accounts:
     *   - Original transaction Credit (Deposit) → ADD interest (we owe more)
     *   - Original transaction Debit (Withdrawal) → SUBTRACT interest (we owe less)
     *
     * ASSET Accounts:
     *   - Original transaction Debit (Advance) → SUBTRACT interest (they owe more, balance more negative)
     *   - Original transaction Credit (Repayment) → ADD interest (they owe less, balance less negative)
     *
     * @param accountNo The account number
     * @param accrualDate The accrual date
     * @param isLiability True if liability account, false if asset account
     * @return Net balance impact (positive = increase balance, negative = decrease balance)
     */
    private BigDecimal calculateValueDateInterestImpact(String accountNo, LocalDate accrualDate, boolean isLiability) {
        // Query value date interest records for this account and date
        // Filter: Original_Dr_Cr_Flag IS NOT NULL AND Dr_Cr_Flag = Original_Dr_Cr_Flag
        // This ensures we only get the Balance Sheet side record (affects account balance)
        // and exclude the P&L side record (Interest Income/Expense) to prevent double counting
        List<com.example.moneymarket.entity.InttAccrTran> valueDateRecords =
            inttAccrTranRepository.findByAccountNoAndAccrualDateAndOriginalDrCrFlagNotNull(accountNo, accrualDate);

        if (valueDateRecords.isEmpty()) {
            log.debug("No value date interest records found for account {}", accountNo);
            return BigDecimal.ZERO;
        }

        log.debug("Found {} value date interest records for account {}", valueDateRecords.size(), accountNo);

        BigDecimal totalImpact = BigDecimal.ZERO;

        for (com.example.moneymarket.entity.InttAccrTran record : valueDateRecords) {
            TranTable.DrCrFlag originalDrCrFlag = record.getOriginalDrCrFlag();
            BigDecimal interestAmount = record.getAmount();

            // Determine balance impact based on account type and original transaction direction
            BigDecimal impact;

            if (isLiability) {
                // LIABILITY ACCOUNT
                if (originalDrCrFlag == TranTable.DrCrFlag.C) {
                    // Original transaction was Credit (Deposit) → ADD interest
                    impact = interestAmount;
                    log.debug("  Value date interest (LIABILITY/Credit): +{}", interestAmount);
                } else {
                    // Original transaction was Debit (Withdrawal) → SUBTRACT interest
                    impact = interestAmount.negate();
                    log.debug("  Value date interest (LIABILITY/Debit): -{}", interestAmount);
                }
            } else {
                // ASSET ACCOUNT
                if (originalDrCrFlag == TranTable.DrCrFlag.D) {
                    // Original transaction was Debit (Advance) → SUBTRACT interest
                    impact = interestAmount.negate();
                    log.debug("  Value date interest (ASSET/Debit): -{}", interestAmount);
                } else {
                    // Original transaction was Credit (Repayment) → ADD interest
                    impact = interestAmount;
                    log.debug("  Value date interest (ASSET/Credit): +{}", interestAmount);
                }
            }

            totalImpact = totalImpact.add(impact);
        }

        log.info("Value date interest impact for account {}: {} (from {} Balance Sheet records, P&L records excluded)",
                accountNo, totalImpact, valueDateRecords.size());

        return totalImpact;
    }

    /**
     * Get opening balance for account
     * Opening balance = previous day's closing balance
     */
    /**
     * Get opening balance for account using 3-tier fallback logic
     *
     * This method implements the standardized 3-tier fallback logic for opening balance retrieval:
     * - Tier 1: Previous day's record (systemDate - 1)
     * - Tier 2: Last transaction date (MAX(Tran_Date) < systemDate)
     * - Tier 3: New account (return 0)
     *
     * This ensures consistency with Batch Job 1 (Account Balance Update) and Batch Job 5 (GL Balance Update) logic.
     *
     * @param accountNo The account number
     * @param systemDate The system date
     * @return Opening balance (previous day's closing balance or last available closing balance)
     */
    private BigDecimal getOpeningBalance(String accountNo, LocalDate systemDate) {
        // Get all account balance accrual records for this account before the system date
        List<AcctBalAccrual> acctBalances = acctBalAccrualRepository
                .findByAccountAccountNoAndTranDateBeforeOrderByTranDateDesc(accountNo, systemDate);

        // Tier 3: If no previous record exists at all, Opening_Bal = 0 (new account or first accrual)
        if (acctBalances.isEmpty()) {
            log.info("3-Tier Fallback [Tier 3 - New Account/First Accrual]: Account {} has no previous accrual records before {}. Using Opening_Bal = 0",
                    accountNo, systemDate);
            return BigDecimal.ZERO;
        }

        // Tier 1: Try to get the previous day's record
        LocalDate previousDay = systemDate.minusDays(1);
        Optional<AcctBalAccrual> previousDayRecord = acctBalances.stream()
                .filter(acctBal -> previousDay.equals(acctBal.getTranDate()))
                .findFirst();

        if (previousDayRecord.isPresent()) {
            BigDecimal previousDayClosingBal = previousDayRecord.get().getClosingBal();
            if (previousDayClosingBal == null) {
                previousDayClosingBal = BigDecimal.ZERO;
            }
            log.debug("3-Tier Fallback [Tier 1 - Previous Day]: Account {} found accrual record for {} with Closing_Bal = {}",
                    accountNo, previousDay, previousDayClosingBal);
            return previousDayClosingBal;
        }

        // Tier 2: Previous day's record doesn't exist, use last transaction date
        AcctBalAccrual lastRecord = acctBalances.get(0); // First in sorted list (most recent)
        BigDecimal lastClosingBal = lastRecord.getClosingBal();
        if (lastClosingBal == null) {
            lastClosingBal = BigDecimal.ZERO;
        }

        long daysSinceLastRecord = java.time.temporal.ChronoUnit.DAYS.between(lastRecord.getTranDate(), systemDate);
        log.warn("3-Tier Fallback [Tier 2 - Last Transaction]: Account {} has gap of {} days. Previous day {} not found. " +
                "Using last Closing_Bal from {} = {}",
                accountNo, daysSinceLastRecord, previousDay, lastRecord.getTranDate(), lastClosingBal);

        return lastClosingBal;
    }

    /**
     * Save or update account accrual balance record with GL_Num
     */
    private void saveOrUpdateAccrualBalance(String accountNo, String glNum, LocalDate tranDate,
                                           BigDecimal openingBal, BigDecimal drSummation,
                                           BigDecimal crSummation, BigDecimal closingBal,
                                           BigDecimal interestAmount) {
        // Check if balance record already exists for this account and date
        Optional<AcctBalAccrual> existingBalanceOpt = acctBalAccrualRepository
                .findByAccountAccountNoAndTranDate(accountNo, tranDate);

        AcctBalAccrual acctBalAccrual;

        if (existingBalanceOpt.isPresent()) {
            // Update existing record
            acctBalAccrual = existingBalanceOpt.get();
            acctBalAccrual.setGlNum(glNum);
            acctBalAccrual.setOpeningBal(openingBal);
            acctBalAccrual.setDrSummation(drSummation);
            acctBalAccrual.setCrSummation(crSummation);
            acctBalAccrual.setClosingBal(closingBal);
            acctBalAccrual.setInterestAmount(interestAmount);
            acctBalAccrual.setAccrualDate(tranDate);
            log.debug("Updated existing accrual balance for account {} with GL_Num {}", accountNo, glNum);
        } else {
            // Get the account entity for the foreign key relationship
            CustAcctMaster account = custAcctMasterRepository.findById(accountNo)
                    .orElseThrow(() -> new RuntimeException("Account not found: " + accountNo));

            // Create new record
            acctBalAccrual = AcctBalAccrual.builder()
                    .account(account)  // Set the required foreign key
                    .tranCcy(account.getAccountCcy())  // FIX: Set currency from account
                    .glNum(glNum)      // NEW: Set GL_Num
                    .tranDate(tranDate)
                    .accrualDate(tranDate)
                    .openingBal(openingBal)
                    .drSummation(drSummation)
                    .crSummation(crSummation)
                    .closingBal(closingBal)
                    .interestAmount(interestAmount)
                    .build();

            log.debug("Created new accrual balance for account {} with GL_Num {} and currency {}",
                    accountNo, glNum, account.getAccountCcy());
        }

        acctBalAccrualRepository.save(acctBalAccrual);
    }
}
