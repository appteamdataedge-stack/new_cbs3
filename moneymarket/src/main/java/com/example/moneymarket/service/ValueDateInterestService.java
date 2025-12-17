package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for Value Date Interest Accrual
 *
 * This service handles interest calculation on transactions where Tran_Date > Value_Date.
 * The gap between value date and transaction date represents a period where interest
 * should be accrued on the transaction amount.
 *
 * Processing Flow:
 * 1. Batch Job 1 calls processValueDateInterest() after account balance update
 * 2. Service identifies transactions with Tran_Date > Value_Date
 * 3. Calculates interest for the gap period
 * 4. Stores in value_date_intt_accr table
 * 5. Batch Job 2 retrieves and includes in regular interest accrual
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ValueDateInterestService {

    private final TranTableRepository tranTableRepository;
    private final ValueDateInttAccrRepository valueDateInttAccrRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final SubProdMasterRepository subProdMasterRepository;
    private final InterestRateMasterRepository interestRateMasterRepository;
    private final SystemDateService systemDateService;

    @Value("${interest.default.divisor:36500}")
    private String interestDivisor;

    /**
     * Process value date interest accrual for a specific system date
     * Called by Batch Job 1 after account balance update
     *
     * Logic:
     * 1. Query transactions where Tran_Date = systemDate AND Tran_Status = 'Verified' AND Tran_Date > Value_Date
     * 2. For each transaction, calculate interest on gap period
     * 3. Store in value_date_intt_accr table
     *
     * @param systemDate The system date for processing
     * @return Number of value date interest records created
     */
    @Transactional
    public int processValueDateInterest(LocalDate systemDate) {
        log.info("Starting value date interest accrual for System_Date: {}", systemDate);

        // Query transactions with value date gap
        List<TranTable> transactionsWithGap = tranTableRepository.findTransactionsWithValueDateGap(systemDate);

        if (transactionsWithGap.isEmpty()) {
            log.info("No transactions with value date gap found for date: {}", systemDate);
            return 0;
        }

        log.info("Found {} transactions with value date gap", transactionsWithGap.size());

        int recordsCreated = 0;
        int recordsSkipped = 0;
        List<String> errors = new ArrayList<>();

        // Get next sequential number for ID generation
        int currentSequential = getNextSequentialNumber(systemDate);

        for (TranTable transaction : transactionsWithGap) {
            try {
                // Check critical condition: Tran_Date > Value_Date
                if (!transaction.getTranDate().isAfter(transaction.getValueDate())) {
                    log.debug("Skipping transaction {} - Tran_Date not after Value_Date",
                            transaction.getTranId());
                    recordsSkipped++;
                    continue;
                }

                // Check if already processed
                if (valueDateInttAccrRepository.existsByAccountNoAndTranDateAndTranId(
                        transaction.getAccountNo(), transaction.getTranDate(), transaction.getTranId())) {
                    log.debug("Skipping duplicate value date interest for transaction {}",
                            transaction.getTranId());
                    recordsSkipped++;
                    continue;
                }

                // Process value date interest for this transaction
                boolean created = processTransactionValueDateInterest(transaction, systemDate, currentSequential);
                if (created) {
                    recordsCreated++;
                    currentSequential++;
                }

            } catch (Exception e) {
                log.error("Error processing value date interest for transaction {}: {}",
                        transaction.getTranId(), e.getMessage(), e);
                errors.add(String.format("Tran_ID %s: %s", transaction.getTranId(), e.getMessage()));
            }
        }

        log.info("Value date interest accrual completed. Processed: {}, Created: {}, Skipped: {}, Errors: {}",
                transactionsWithGap.size(), recordsCreated, recordsSkipped, errors.size());

        if (!errors.isEmpty()) {
            log.warn("Value date interest processing completed with {} errors: {}",
                    errors.size(), String.join("; ", errors));
        }

        return recordsCreated;
    }

    /**
     * Process value date interest for a single transaction
     * Creates TWO records (double-entry): Payable/Receivable + Expense/Income
     *
     * @param transaction The transaction with value date gap
     * @param systemDate The system date
     * @param sequential The sequential number for ID generation
     * @return true if records were created, false otherwise
     */
    private boolean processTransactionValueDateInterest(TranTable transaction, LocalDate systemDate,
                                                       int sequential) {
        String accountNo = transaction.getAccountNo();
        String tranId = transaction.getTranId();
        LocalDate tranDate = transaction.getTranDate();
        LocalDate valueDate = transaction.getValueDate();
        BigDecimal lcyAmt = transaction.getLcyAmt();
        String currencyCode = transaction.getTranCcy();

        // Calculate day gap
        long dayGap = ChronoUnit.DAYS.between(valueDate, tranDate);

        if (dayGap <= 0) {
            log.warn("Invalid day gap {} for transaction {}", dayGap, tranId);
            return false;
        }

        log.debug("Processing transaction {} for account {}: Gap = {} days", tranId, accountNo, dayGap);

        // Get account's sub-product and interest rate
        SubProdMaster subProduct = getSubProductForAccount(accountNo);
        if (subProduct == null) {
            log.debug("Skipping value date interest for account {} - sub-product not found", accountNo);
            return false;
        }

        BigDecimal interestRate = getEffectiveInterestRate(subProduct, systemDate);
        if (interestRate == null || interestRate.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Skipping value date interest for account {} - no interest rate configured", accountNo);
            return false;
        }

        // Calculate interest accrued on transaction amount
        // Formula: Interest = |LCY_Amt| × Interest_Rate × Day_Gap / Interest_Rule
        BigDecimal divisor = new BigDecimal(interestDivisor);
        BigDecimal interestAccrued = lcyAmt.abs()
                .multiply(interestRate)
                .multiply(BigDecimal.valueOf(dayGap))
                .divide(divisor, 2, RoundingMode.HALF_UP);

        if (interestAccrued.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Skipping value date interest for transaction {} - zero interest amount", tranId);
            return false;
        }

        // Get Interest GL numbers for double-entry
        String debitGL = subProduct.getInterestReceivableExpenditureGLNum();
        String creditGL = subProduct.getInterestIncomePayableGLNum();

        if (debitGL == null || creditGL == null) {
            log.error("Missing Interest GL mapping for account {}: debitGL={}, creditGL={}",
                    accountNo, debitGL, creditGL);
            return false;
        }

        // Determine if liability or asset account
        String accountGL = getGLNumForAccount(accountNo);
        boolean isLiability = accountGL.startsWith("1");

        // Get original transaction's Dr_Cr_Flag to determine accrual or reversal
        TranTable.DrCrFlag originalDrCrFlag = transaction.getDrCrFlag();

        // Determine Dr/Cr flags for the two records based on account type and transaction direction
        TranTable.DrCrFlag balanceSheetDrCrFlag;
        TranTable.DrCrFlag plDrCrFlag;
        String accrualType;

        if (isLiability) {
            // LIABILITY ACCOUNT (Deposits, Savings)
            if (originalDrCrFlag == TranTable.DrCrFlag.C) {
                // Credit transaction (Deposit) - Normal accrual
                balanceSheetDrCrFlag = TranTable.DrCrFlag.C;  // Credit Interest Payable
                plDrCrFlag = TranTable.DrCrFlag.D;             // Debit Interest Expenditure
                accrualType = "Accrual";
            } else {
                // Debit transaction (Withdrawal) - Reversal
                balanceSheetDrCrFlag = TranTable.DrCrFlag.D;  // Debit Interest Payable (reversal)
                plDrCrFlag = TranTable.DrCrFlag.C;             // Credit Interest Expenditure (reversal)
                accrualType = "Reversal";
            }
        } else {
            // ASSET ACCOUNT (Loans, Overdrafts)
            if (originalDrCrFlag == TranTable.DrCrFlag.D) {
                // Debit transaction (Advance) - Normal accrual
                balanceSheetDrCrFlag = TranTable.DrCrFlag.D;  // Debit Interest Receivable
                plDrCrFlag = TranTable.DrCrFlag.C;             // Credit Interest Income
                accrualType = "Accrual";
            } else {
                // Credit transaction (Repayment) - Reversal
                balanceSheetDrCrFlag = TranTable.DrCrFlag.C;  // Credit Interest Receivable (reversal)
                plDrCrFlag = TranTable.DrCrFlag.D;             // Debit Interest Income (reversal)
                accrualType = "Reversal";
            }
        }

        // Generate IDs for TWO records (double-entry)
        String record1AccrTranId = generateAccrTranId(systemDate, sequential, 1);
        String record2AccrTranId = generateAccrTranId(systemDate, sequential, 2);

        try {
            if (isLiability) {
                // LIABILITY ACCOUNT (e.g., Deposits, Savings)
                // Record 1: Interest Payable (Balance Sheet)
                createValueDateInterestRecord(record1AccrTranId, accountNo, systemDate, tranDate, valueDate,
                        tranId, interestRate, (int) dayGap, interestAccrued, currencyCode,
                        transaction.getExchangeRate(), balanceSheetDrCrFlag, creditGL,
                        "Interest Payable " + accrualType + " - " + accountNo,
                        originalDrCrFlag);  // Pass original transaction's Dr/Cr flag

                // Record 2: Interest Expenditure (P&L)
                createValueDateInterestRecord(record2AccrTranId, accountNo, systemDate, tranDate, valueDate,
                        tranId, interestRate, (int) dayGap, interestAccrued, currencyCode,
                        transaction.getExchangeRate(), plDrCrFlag, debitGL,
                        "Interest Expenditure " + accrualType + " - " + accountNo,
                        originalDrCrFlag);  // Pass original transaction's Dr/Cr flag

                log.info("Value date interest (LIABILITY - {}) for account {}, Tran_ID {}, Original Dr/Cr: {}, Interest: {}, Days: {}, Payable GL={} ({}), Expense GL={} ({})",
                        accrualType, accountNo, tranId, originalDrCrFlag, interestAccrued, dayGap,
                        creditGL, balanceSheetDrCrFlag, debitGL, plDrCrFlag);

            } else {
                // ASSET ACCOUNT (e.g., Loans, Overdrafts)
                // Record 1: Interest Receivable (Balance Sheet)
                createValueDateInterestRecord(record1AccrTranId, accountNo, systemDate, tranDate, valueDate,
                        tranId, interestRate, (int) dayGap, interestAccrued, currencyCode,
                        transaction.getExchangeRate(), balanceSheetDrCrFlag, debitGL,
                        "Interest Receivable " + accrualType + " - " + accountNo,
                        originalDrCrFlag);  // Pass original transaction's Dr/Cr flag

                // Record 2: Interest Income (P&L)
                createValueDateInterestRecord(record2AccrTranId, accountNo, systemDate, tranDate, valueDate,
                        tranId, interestRate, (int) dayGap, interestAccrued, currencyCode,
                        transaction.getExchangeRate(), plDrCrFlag, creditGL,
                        "Interest Income " + accrualType + " - " + accountNo,
                        originalDrCrFlag);  // Pass original transaction's Dr/Cr flag

                log.info("Value date interest (ASSET - {}) for account {}, Tran_ID {}, Original Dr/Cr: {}, Interest: {}, Days: {}, Receivable GL={} ({}), Income GL={} ({})",
                        accrualType, accountNo, tranId, originalDrCrFlag, interestAccrued, dayGap,
                        debitGL, balanceSheetDrCrFlag, creditGL, plDrCrFlag);
            }

            return true;

        } catch (Exception e) {
            log.error("Error creating value date interest records for transaction {}: {}", tranId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Helper method to create a single value date interest record
     */
    private void createValueDateInterestRecord(String accrTranId, String accountNo, LocalDate accrualDate,
                                               LocalDate tranDate, LocalDate valueDate, String tranId,
                                               BigDecimal interestRate, int dayGap, BigDecimal interestAccrued,
                                               String currencyCode, BigDecimal exchangeRate,
                                               TranTable.DrCrFlag drCrFlag, String glAccountNo, String narration,
                                               TranTable.DrCrFlag originalDrCrFlag) {
        ValueDateInttAccr record = ValueDateInttAccr.builder()
                .accrTranId(accrTranId)
                .accountNo(accountNo)
                .accrualDate(accrualDate)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .tranId(tranId)
                .interestRate(interestRate)
                .dayGap(dayGap)
                .lcyAmt(interestAccrued)  // Store interest amount (not transaction amount)
                .fcyAmt(interestAccrued)  // Store interest amount (not transaction amount)
                .amount(interestAccrued)
                .drCrFlag(drCrFlag)
                .originalDrCrFlag(originalDrCrFlag)  // Store original transaction's Dr/Cr flag
                .tranCcy(currencyCode)
                .exchangeRate(exchangeRate)
                .glAccountNo(glAccountNo)
                .narration(narration)
                .status(ValueDateInttAccr.AccrualStatus.Pending)
                .tranStatus(TranTable.TranStatus.Verified)
                .build();

        valueDateInttAccrRepository.save(record);
    }

    /**
     * Get sub-product for an account
     */
    private SubProdMaster getSubProductForAccount(String accountNo) {
        // Try customer account first
        Optional<CustAcctMaster> custAcctOpt = custAcctMasterRepository.findById(accountNo);
        if (custAcctOpt.isPresent()) {
            return custAcctOpt.get().getSubProduct();
        }

        // Try office account
        Optional<OFAcctMaster> ofAcctOpt = ofAcctMasterRepository.findById(accountNo);
        if (ofAcctOpt.isPresent()) {
            return ofAcctOpt.get().getSubProduct();
        }

        return null;
    }

    /**
     * Get interest rate for an account
     * Uses same logic as InterestAccrualService
     */
    private BigDecimal getInterestRateForAccount(String accountNo, LocalDate asOfDate) {
        // Try customer account first
        Optional<CustAcctMaster> custAcctOpt = custAcctMasterRepository.findById(accountNo);
        if (custAcctOpt.isPresent()) {
            SubProdMaster subProduct = custAcctOpt.get().getSubProduct();
            if (subProduct != null) {
                return getEffectiveInterestRate(subProduct, asOfDate);
            }
        }

        // Try office account
        Optional<OFAcctMaster> ofAcctOpt = ofAcctMasterRepository.findById(accountNo);
        if (ofAcctOpt.isPresent()) {
            SubProdMaster subProduct = ofAcctOpt.get().getSubProduct();
            if (subProduct != null) {
                return getEffectiveInterestRate(subProduct, asOfDate);
            }
        }

        log.warn("Could not find interest rate for account: {}", accountNo);
        return BigDecimal.ZERO;
    }

    /**
     * Get effective interest rate from sub-product
     * Simplified version of InterestAccrualService logic
     */
    private BigDecimal getEffectiveInterestRate(SubProdMaster subProduct, LocalDate asOfDate) {
        String inttCode = subProduct.getInttCode();

        if (inttCode == null || inttCode.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }

        // For deal accounts with fixed rate
        if (subProduct.getEffectiveInterestRate() != null) {
            return subProduct.getEffectiveInterestRate();
        }

        // Lookup from interest_rate_master
        Optional<InterestRateMaster> rateOpt = interestRateMasterRepository
                .findTopByInttCodeAndInttEffctvDateLessThanEqualOrderByInttEffctvDateDesc(inttCode, asOfDate);

        if (rateOpt.isEmpty()) {
            log.warn("No interest rate found for code {} as of {}", inttCode, asOfDate);
            return BigDecimal.ZERO;
        }

        BigDecimal baseRate = rateOpt.get().getInttRate();
        BigDecimal interestIncrement = subProduct.getInterestIncrement();

        if (interestIncrement == null) {
            interestIncrement = BigDecimal.ZERO;
        }

        return baseRate.add(interestIncrement);
    }

    /**
     * Get GL_Num for account
     */
    private String getGLNumForAccount(String accountNo) {
        // Try customer account first
        Optional<CustAcctMaster> custAcctOpt = custAcctMasterRepository.findById(accountNo);
        if (custAcctOpt.isPresent()) {
            SubProdMaster subProduct = custAcctOpt.get().getSubProduct();
            if (subProduct != null && subProduct.getCumGLNum() != null) {
                return subProduct.getCumGLNum();
            }
        }

        // Try office account
        Optional<OFAcctMaster> ofAcctOpt = ofAcctMasterRepository.findById(accountNo);
        if (ofAcctOpt.isPresent()) {
            return ofAcctOpt.get().getGlNum();
        }

        throw new BusinessException("GL_Num not found for account: " + accountNo);
    }

    /**
     * Get next sequential number for ID generation
     */
    private int getNextSequentialNumber(LocalDate tranDate) {
        try {
            Optional<Integer> maxSeqOpt = valueDateInttAccrRepository.findMaxSequentialByTranDate(tranDate);
            int nextSeq = maxSeqOpt.map(max -> max + 1).orElse(1);

            if (nextSeq > 999999999) {
                log.warn("Sequential number {} exceeds maximum (999999999) for date {}", nextSeq, tranDate);
            }

            log.debug("Next sequential number for value date interest on {}: {}", tranDate, nextSeq);
            return nextSeq;
        } catch (Exception e) {
            log.error("Error retrieving max sequential number for date {}: {}", tranDate, e.getMessage());
            throw new BusinessException("Failed to generate sequential number: " + e.getMessage());
        }
    }

    /**
     * Generate Accr_Tran_Id for value date interest
     * Format: V + YYYYMMDD + 9-digit-sequential
     * Example: V20251020000000001
     */
    private String generateAccrTranId(LocalDate tranDate, int sequential) {
        if (tranDate == null) {
            throw new BusinessException("Transaction date cannot be null for ID generation");
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String formattedDate = tranDate.format(formatter);
        String paddedSeq = String.format("%09d", sequential);
        String accrTranId = "V" + formattedDate + paddedSeq;

        // Validate final ID length
        if (accrTranId.length() != 18) {
            throw new BusinessException("Generated Accr_Tran_Id has invalid length: " + accrTranId +
                    " (expected 18, got " + accrTranId.length() + ")");
        }

        return accrTranId;
    }

    /**
     * Generate Accr_Tran_Id for value date interest with suffix for double-entry
     * Format: V + YYYYMMDD + 9-digit-sequential + "-" + suffix
     * Example: V20251020000000001-1, V20251020000000001-2
     *
     * @param tranDate The transaction date
     * @param sequential The sequential number
     * @param suffix The suffix (1 for debit entry, 2 for credit entry)
     * @return Generated Accr_Tran_Id with suffix
     */
    private String generateAccrTranId(LocalDate tranDate, int sequential, int suffix) {
        String baseId = generateAccrTranId(tranDate, sequential);
        return baseId + "-" + suffix;
    }
}
