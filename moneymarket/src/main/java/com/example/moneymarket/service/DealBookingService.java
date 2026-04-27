package com.example.moneymarket.service;

import com.example.moneymarket.dto.DealBookingRequestDTO;
import com.example.moneymarket.dto.DealBookingResponseDTO;
import com.example.moneymarket.dto.DealScheduleDTO;
import com.example.moneymarket.entity.*;
import com.example.moneymarket.entity.CustAcctMaster.AccountStatus;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.exception.BODNotExecutedException;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.exception.InsufficientBalanceException;
import com.example.moneymarket.exception.ResourceNotFoundException;
import com.example.moneymarket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service for Deal Booking operations.
 * Handles account creation, initial funding, and schedule generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DealBookingService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String EVENT_INT_PAY  = "INT_PAY";
    private static final String EVENT_MAT_PAY  = "MAT_PAY";
    private static final String SYSTEM_USER    = "SYSTEM";

    // Sub-product codes per BRD Section 5.1.3
    private static final String SUB_PROD_TDPIP = "TDPIP"; // Liability + Non-compounding
    private static final String SUB_PROD_TDCUM = "TDCUM"; // Liability + Compounding
    private static final String SUB_PROD_ODATD = "ODATD"; // Asset + Non-compounding
    private static final String SUB_PROD_STLTR = "STLTR"; // Asset + Compounding

    private final BODSchedulerService bodSchedulerService;
    private final CustMasterRepository custMasterRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final SubProdMasterRepository subProdMasterRepository;
    private final AcctBalRepository acctBalRepository;
    private final TranTableRepository tranTableRepository;
    private final DealScheduleRepository dealScheduleRepository;
    private final InterestRateMasterRepository interestRateMasterRepository;
    private final AccountNumberService accountNumberService;
    private final BalanceService balanceService;
    private final SystemDateService systemDateService;
    private final TransactionHistoryService transactionHistoryService;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Book a new deal (Term Deposit or Loan).
     * Creates the deal account, posts initial funding, and generates payment schedules.
     */
    @Transactional
    public DealBookingResponseDTO createDeal(DealBookingRequestDTO request) {
        log.info("Creating deal: custId={}, dealType={}, interestType={}, amount={} {}",
                request.getCustId(), request.getDealType(), request.getInterestType(),
                request.getDealAmount(), request.getCurrencyCode());

        // 1. Validate inputs
        validateRequest(request);

        // 1b. Block if deal schedules exist for today AND BOD has not been executed yet.
        LocalDate systemDate = systemDateService.getSystemDate();
        long schedulesForToday = dealScheduleRepository.countByScheduleDate(systemDate);
        if (schedulesForToday > 0 && !bodSchedulerService.isBodExecutedForDate(systemDate)) {
            long pendingCount = dealScheduleRepository.countByScheduleDateAndStatus(systemDate, STATUS_PENDING);
            throw new BODNotExecutedException((int) pendingCount, systemDate);
        }

        // 2. Load customer
        CustMaster customer = custMasterRepository.findById(request.getCustId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "ID", request.getCustId()));

        // 3. Validate operative account
        CustAcctMaster operativeAccount = custAcctMasterRepository.findById(request.getOperativeAccountNo())
                .orElseThrow(() -> new ResourceNotFoundException("Operative Account", "Account Number",
                        request.getOperativeAccountNo()));
        validateOperativeAccount(operativeAccount, customer, request);

        // 4. Determine sub-product
        String subProductCode = determineSubProductCode(request.getDealType(), request.getInterestType());
        SubProdMaster subProduct = subProdMasterRepository.findBySubProductCode(subProductCode)
                .orElseThrow(() -> new BusinessException(
                        "Sub-product not found: " + subProductCode +
                        ". Please ensure sub-products TDPIP, TDCUM, ODATD, STLTR are configured."));

        if (subProduct.getSubProductStatus() != SubProdMaster.SubProductStatus.Active) {
            throw new BusinessException("Sub-product " + subProductCode + " is not active");
        }

        // 5. Calculate maturity date
        LocalDate maturityDate = request.getValueDate().plusDays(request.getTenor());

        // 6. Create deal account
        String dealAccountNo = accountNumberService.generateCustomerAccountNumber(customer, subProduct);
        CustAcctMaster dealAccount = buildDealAccount(request, customer, subProduct, dealAccountNo, maturityDate);
        custAcctMasterRepository.save(dealAccount);
        log.info("Deal account created: {}", dealAccountNo);

        // 7. Initialize account balance
        AcctBal initialBalance = AcctBal.builder()
                .tranDate(systemDate)
                .accountNo(dealAccountNo)
                .accountCcy(request.getCurrencyCode())
                .openingBal(BigDecimal.ZERO)
                .drSummation(BigDecimal.ZERO)
                .crSummation(BigDecimal.ZERO)
                .closingBal(BigDecimal.ZERO)
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .lastUpdated(systemDateService.getSystemDateTime())
                .build();
        acctBalRepository.save(initialBalance);

        // 8. Post initial funding transaction
        postInitialFunding(request, dealAccountNo, systemDate);

        // 9. Generate schedules
        BigDecimal rate = subProduct.getEffectiveInterestRate() != null
                ? subProduct.getEffectiveInterestRate()
                : BigDecimal.ZERO;
        List<DealSchedule> schedules = generateSchedules(request, dealAccountNo, maturityDate, subProduct);
        dealScheduleRepository.saveAll(schedules);
        log.info("Generated {} schedules for deal account {}", schedules.size(), dealAccountNo);

        // 10. Build response
        String custName = buildCustomerName(customer);
        return DealBookingResponseDTO.builder()
                .dealAccountNo(dealAccountNo)
                .subProductCode(subProduct.getSubProductCode())
                .subProductName(subProduct.getSubProductName())
                .custId(customer.getCustId())
                .custName(custName)
                .operativeAccountNo(request.getOperativeAccountNo())
                .dealType(request.getDealType())
                .interestType(request.getInterestType())
                .compoundingFrequency(request.getCompoundingFrequency())
                .dealAmount(request.getDealAmount())
                .currencyCode(request.getCurrencyCode())
                .valueDate(request.getValueDate())
                .maturityDate(maturityDate)
                .tenor(request.getTenor())
                .narration(request.getNarration())
                .effectiveInterestRate(rate)
                .schedules(schedules.stream().map(this::toDTO).toList())
                .build();
    }

    /**
     * Count pending deal schedules for a given business date.
     * Used by the BOD page to show how many schedules will be executed.
     */
    public Map<String, Object> getPendingScheduleCount(LocalDate businessDate) {
        List<DealSchedule> pending = dealScheduleRepository
                .findByScheduleDateAndStatus(businessDate, "PENDING");
        long intPay = pending.stream().filter(s -> "INT_PAY".equals(s.getEventCode())).count();
        long matPay = pending.stream().filter(s -> "MAT_PAY".equals(s.getEventCode())).count();
        return Map.of(
                "businessDate", businessDate.toString(),
                "totalCount",  (long) pending.size(),
                "intPayCount", intPay,
                "matPayCount", matPay
        );
    }

    /**
     * Retrieve schedules for a deal account.
     */
    public List<DealScheduleDTO> getSchedulesByAccount(String accountNumber) {
        return dealScheduleRepository.findByAccountNumberOrderByScheduleDateAsc(accountNumber)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private void validateRequest(DealBookingRequestDTO request) {
        if ("C".equals(request.getInterestType()) && request.getCompoundingFrequency() == null) {
            throw new BusinessException("Compounding Frequency is required when Interest Type is Compounding (C)");
        }
        if ("C".equals(request.getInterestType())
                && request.getCompoundingFrequency() != null
                && request.getCompoundingFrequency() >= request.getTenor()) {
            throw new BusinessException("Compounding Frequency must be less than Tenor");
        }
    }

    private void validateOperativeAccount(CustAcctMaster operativeAccount,
                                          CustMaster customer,
                                          DealBookingRequestDTO request) {
        // Verify the operative account belongs to the customer
        if (!operativeAccount.getCustomer().getCustId().equals(customer.getCustId())) {
            throw new BusinessException("Operative account does not belong to the specified customer");
        }
        if (operativeAccount.getAccountStatus() != AccountStatus.Active) {
            throw new BusinessException("Operative account is not active");
        }
        // Balance check only for Liability (deposit) deals — customer funds the TD
        if ("L".equals(request.getDealType())) {
            // Real-time available balance: currentBalance is updated on every Posted transaction
            AcctBal bal = acctBalRepository.findLatestByAccountNo(request.getOperativeAccountNo())
                    .orElse(null);

            // Liability operative accounts carry NEGATIVE currentBalance (bank owes customer).
            // Available = abs(currentBalance).  If no balance record exists, available = 0.
            BigDecimal available = (bal != null && bal.getCurrentBalance() != null)
                    ? bal.getCurrentBalance().abs()
                    : BigDecimal.ZERO;

            if (available.compareTo(request.getDealAmount()) < 0) {
                String accountName = operativeAccount.getAcctName() != null
                        ? operativeAccount.getAcctName()
                        : (operativeAccount.getCustName() != null
                                ? operativeAccount.getCustName() : "Unknown");
                String currency = operativeAccount.getAccountCcy() != null
                        ? operativeAccount.getAccountCcy() : "BDT";
                throw new InsufficientBalanceException(
                        request.getOperativeAccountNo(), accountName,
                        available, request.getDealAmount(), currency);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Account creation helpers
    // -------------------------------------------------------------------------

    private CustAcctMaster buildDealAccount(DealBookingRequestDTO request,
                                             CustMaster customer,
                                             SubProdMaster subProduct,
                                             String accountNo,
                                             LocalDate maturityDate) {
        String custName = buildCustomerName(customer);
        String acctName = custName + " - " + subProduct.getSubProductCode();

        CustAcctMaster.CustAcctMasterBuilder builder = CustAcctMaster.builder()
                .accountNo(accountNo)
                .subProduct(subProduct)
                .glNum(subProduct.getCumGLNum())
                .accountCcy(request.getCurrencyCode())
                .customer(customer)
                .custName(custName)
                .acctName(acctName)
                .dateOpening(request.getValueDate())
                .tenor(request.getTenor())
                .dateMaturity(maturityDate)
                .branchCode(request.getBranchCode())
                .accountStatus(AccountStatus.Active)
                .loanLimit(BigDecimal.ZERO);

        // For Asset deals, set loan limit = deal amount to allow initial DR
        if ("A".equals(request.getDealType())) {
            builder.loanLimit(request.getDealAmount());
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Initial funding transaction
    // -------------------------------------------------------------------------

    /**
     * Posts the 2-entry initial funding transaction.
     *
     * Liability TD:
     *   Entry -1: DR Operative Account  (customer funds leave operative)
     *   Entry -2: CR Deal (TD) Account  (deposit liability created)
     *
     * Asset Loan:
     *   Entry -1: DR Operative Account  (loan disbursed to customer's operative)
     *   Entry -2: CR Loan Account       (loan asset created for bank)
     *
     * Uses the system-standard tranId + "-N" suffix so that
     * TransactionService verify/post/get logic works correctly.
     */
    private void postInitialFunding(DealBookingRequestDTO request,
                                     String dealAccountNo,
                                     LocalDate systemDate) {
        String narration = (request.getNarration() != null && !request.getNarration().isBlank())
                ? request.getNarration()
                : "Deal Booking - " + dealAccountNo;

        boolean isLiability = "L".equals(request.getDealType());
        BigDecimal amount = request.getDealAmount();
        String operativeAccNo = request.getOperativeAccountNo();

        // Both lines share one base tranId; individual lines get "-1" / "-2" suffix
        String baseTranId = generateDealTranId(systemDate);

        // Liability TD: DR Operative (entry 1), CR Deal Account (entry 2)
        // Asset Loan:   CR Operative (entry 1), DR Loan Account (entry 2)
        //   Operative is CR for Asset because the bank disburses funds to the customer.
        //   Loan account is DR so the account statement shows the customer in debt.
        //   Balance updates: Liability operative = D (less negative = customer paid in);
        //                    Asset operative     = C (more negative = bank owes more to customer).
        DrCrFlag operativeFlag  = isLiability ? DrCrFlag.D : DrCrFlag.C;
        DrCrFlag dealAcctFlag   = isLiability ? DrCrFlag.C : DrCrFlag.D;

        TranTable entry1 = buildTranTableEntry(
                baseTranId + "-1", systemDate, request.getValueDate(),
                operativeFlag, operativeAccNo, null,
                request.getCurrencyCode(), amount, amount, narration, "DEAL", "BOOKING");
        TranTable entry2 = buildTranTableEntry(
                baseTranId + "-2", systemDate, request.getValueDate(),
                dealAcctFlag, dealAccountNo, null,
                request.getCurrencyCode(), amount, amount, narration, "DEAL", "BOOKING");

        // Save and record history BEFORE balance updates so opening balance is read correctly.
        tranTableRepository.save(entry1);
        transactionHistoryService.createTransactionHistory(entry1, SYSTEM_USER);
        tranTableRepository.save(entry2);
        transactionHistoryService.createTransactionHistory(entry2, SYSTEM_USER);

        balanceService.updateAccountBalance(operativeAccNo, operativeFlag, amount);
        balanceService.updateAccountBalance(dealAccountNo,  DrCrFlag.C,    amount);

        log.info("Initial funding posted: baseTranId={} isLiability={} amount={}",
                baseTranId, isLiability, amount);
    }

    // -------------------------------------------------------------------------
    // Schedule generation
    // -------------------------------------------------------------------------

    /**
     * Generates DEAL_SCHEDULE rows based on BRD Section 5.3.2.
     *
     * Non-compounding: 1 INT_PAY + 1 MAT_PAY both on maturity date.
     * Compounding:     Multiple INT_PAY at each frequency interval + 1 MAT_PAY on maturity date.
     */
    private List<DealSchedule> generateSchedules(DealBookingRequestDTO request,
                                                   String dealAccountNo,
                                                   LocalDate maturityDate,
                                                   SubProdMaster subProduct) {
        List<DealSchedule> schedules = new ArrayList<>();
        boolean isCompounding = "C".equals(request.getInterestType());
        String customerId = String.valueOf(request.getCustId());

        if (isCompounding) {
            schedules.addAll(buildCompoundingSchedules(request, dealAccountNo, maturityDate,
                    subProduct, customerId));
        } else {
            schedules.addAll(buildNonCompoundingSchedules(request, dealAccountNo, maturityDate,
                    subProduct, customerId));
        }

        return schedules;
    }

    private List<DealSchedule> buildNonCompoundingSchedules(DealBookingRequestDTO request,
                                                              String dealAccountNo,
                                                              LocalDate maturityDate,
                                                              SubProdMaster subProduct,
                                                              String customerId) {
        List<DealSchedule> schedules = new ArrayList<>();
        BigDecimal annualRate = getApplicableInterestRate(subProduct, maturityDate);
        BigDecimal interest = calculateSimpleInterest(request.getDealAmount(), annualRate, request.getTenor());

        // INT_PAY on maturity date
        schedules.add(DealSchedule.builder()
                .accountNumber(dealAccountNo)
                .operativeAccountNo(request.getOperativeAccountNo())
                .customerId(customerId)
                .dealType(request.getDealType())
                .eventCode(EVENT_INT_PAY)
                .scheduleDate(maturityDate)
                .scheduleAmount(interest)
                .currencyCode(request.getCurrencyCode())
                .status(STATUS_PENDING)
                .createdDateTime(LocalDateTime.now())
                .createdBy(SYSTEM_USER)
                .build());

        // MAT_PAY on maturity date
        schedules.add(DealSchedule.builder()
                .accountNumber(dealAccountNo)
                .operativeAccountNo(request.getOperativeAccountNo())
                .customerId(customerId)
                .dealType(request.getDealType())
                .eventCode(EVENT_MAT_PAY)
                .scheduleDate(maturityDate)
                .scheduleAmount(request.getDealAmount())
                .currencyCode(request.getCurrencyCode())
                .status(STATUS_PENDING)
                .createdDateTime(LocalDateTime.now())
                .createdBy(SYSTEM_USER)
                .build());

        return schedules;
    }

    private List<DealSchedule> buildCompoundingSchedules(DealBookingRequestDTO request,
                                                          String dealAccountNo,
                                                          LocalDate maturityDate,
                                                          SubProdMaster subProduct,
                                                          String customerId) {
        List<DealSchedule> schedules = new ArrayList<>();
        int frequency = request.getCompoundingFrequency();
        BigDecimal runningPrincipal = request.getDealAmount();
        LocalDate periodStart = request.getValueDate();
        LocalDate periodEnd = periodStart.plusDays(frequency);

        // Build INT_PAY records for each compounding period
        while (!periodEnd.isAfter(maturityDate)) {
            long periodDays = java.time.temporal.ChronoUnit.DAYS.between(periodStart, periodEnd);
            BigDecimal annualRate = getApplicableInterestRate(subProduct, periodEnd);
            BigDecimal periodInterest = calculateSimpleInterest(runningPrincipal, annualRate, (int) periodDays);

            schedules.add(DealSchedule.builder()
                    .accountNumber(dealAccountNo)
                    .operativeAccountNo(request.getOperativeAccountNo())
                    .customerId(customerId)
                    .dealType(request.getDealType())
                    .eventCode(EVENT_INT_PAY)
                    .scheduleDate(periodEnd)
                    .scheduleAmount(periodInterest)
                    .currencyCode(request.getCurrencyCode())
                    .status(STATUS_PENDING)
                    .createdDateTime(LocalDateTime.now())
                    .createdBy(SYSTEM_USER)
                    .build());

            runningPrincipal = runningPrincipal.add(periodInterest);
            periodStart = periodEnd;
            periodEnd = periodStart.plusDays(frequency);
        }

        // Handle stub period (if maturity not on a compounding date)
        if (periodStart.isBefore(maturityDate)) {
            long stubDays = java.time.temporal.ChronoUnit.DAYS.between(periodStart, maturityDate);
            BigDecimal annualRate = getApplicableInterestRate(subProduct, maturityDate);
            BigDecimal stubInterest = calculateSimpleInterest(runningPrincipal, annualRate, (int) stubDays);
            runningPrincipal = runningPrincipal.add(stubInterest);

            schedules.add(DealSchedule.builder()
                    .accountNumber(dealAccountNo)
                    .operativeAccountNo(request.getOperativeAccountNo())
                    .customerId(customerId)
                    .dealType(request.getDealType())
                    .eventCode(EVENT_INT_PAY)
                    .scheduleDate(maturityDate)
                    .scheduleAmount(stubInterest)
                    .currencyCode(request.getCurrencyCode())
                    .status(STATUS_PENDING)
                    .createdDateTime(LocalDateTime.now())
                    .createdBy(SYSTEM_USER)
                    .build());
        }

        // MAT_PAY on maturity date — total compounded amount
        schedules.add(DealSchedule.builder()
                .accountNumber(dealAccountNo)
                .operativeAccountNo(request.getOperativeAccountNo())
                .customerId(customerId)
                .dealType(request.getDealType())
                .eventCode(EVENT_MAT_PAY)
                .scheduleDate(maturityDate)
                .scheduleAmount(runningPrincipal)
                .currencyCode(request.getCurrencyCode())
                .status(STATUS_PENDING)
                .createdDateTime(LocalDateTime.now())
                .createdBy(SYSTEM_USER)
                .build());

        return schedules;
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    /** Determines sub-product code from deal type and interest type (BRD Section 5.1.3). */
    private String determineSubProductCode(String dealType, String interestType) {
        if ("L".equals(dealType)) {
            return "C".equals(interestType) ? SUB_PROD_TDCUM : SUB_PROD_TDPIP;
        } else {
            return "C".equals(interestType) ? SUB_PROD_STLTR : SUB_PROD_ODATD;
        }
    }

    /**
     * Simple interest: principal × (rate/100) × days/365, rounded to 2 decimal places.
     */
    private BigDecimal calculateSimpleInterest(BigDecimal principal, BigDecimal annualRatePercent, int days) {
        if (annualRatePercent == null || annualRatePercent.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return principal
                .multiply(annualRatePercent)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
    }

    /**
     * Use the same rate source as BOD posting (InterestRateMaster by inttCode + effective date),
     * and only fall back to sub-product static rate if no master rate exists.
     */
    private BigDecimal getApplicableInterestRate(SubProdMaster subProduct, LocalDate asOfDate) {
        if (subProduct == null) {
            return BigDecimal.ZERO;
        }
        if (subProduct.getInttCode() != null && !subProduct.getInttCode().isBlank()) {
            return interestRateMasterRepository
                    .findTopByInttCodeAndInttEffctvDateLessThanEqualOrderByInttEffctvDateDesc(
                            subProduct.getInttCode(), asOfDate)
                    .map(InterestRateMaster::getInttRate)
                    .orElseGet(() -> subProduct.getEffectiveInterestRate() != null
                            ? subProduct.getEffectiveInterestRate()
                            : BigDecimal.ZERO);
        }
        return subProduct.getEffectiveInterestRate() != null
                ? subProduct.getEffectiveInterestRate()
                : BigDecimal.ZERO;
    }

    /** Generates a deal transaction ID: D + yyyyMMdd + 6-digit-seq + 3-digit-random (18 chars). */
    private String generateDealTranId(LocalDate systemDate) {
        String date = systemDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = tranTableRepository.countByTranDate(systemDate) + 1;
        int rand = ThreadLocalRandom.current().nextInt(1000);
        return String.format("D%s%06d%03d", date, seq, rand);
    }

    private TranTable buildTranTableEntry(String tranId, LocalDate tranDate, LocalDate valueDate,
                                           DrCrFlag drCrFlag, String accountNo, String glNum,
                                           String ccy, BigDecimal fcyAmt, BigDecimal lcyAmt,
                                           String narration, String tranType, String tranSubType) {
        BigDecimal debitAmt  = (drCrFlag == DrCrFlag.D) ? fcyAmt : BigDecimal.ZERO;
        BigDecimal creditAmt = (drCrFlag == DrCrFlag.C) ? fcyAmt : BigDecimal.ZERO;

        return TranTable.builder()
                .tranId(tranId)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(drCrFlag)
                .tranStatus(TranStatus.Posted)
                .accountNo(accountNo)
                .glNum(glNum)
                .tranCcy(ccy)
                .fcyAmt(fcyAmt)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(lcyAmt)
                .debitAmount(debitAmt)
                .creditAmount(creditAmt)
                .narration(narration)
                .tranType(tranType)
                .tranSubType(tranSubType)
                .build();
    }

    private String buildCustomerName(CustMaster customer) {
        if (customer.getCustType() == CustMaster.CustomerType.Individual) {
            return (customer.getFirstName() != null ? customer.getFirstName() : "")
                    + " " + (customer.getLastName() != null ? customer.getLastName() : "");
        }
        return customer.getTradeName() != null ? customer.getTradeName() : "";
    }

    private DealScheduleDTO toDTO(DealSchedule s) {
        return DealScheduleDTO.builder()
                .scheduleId(s.getScheduleId())
                .accountNumber(s.getAccountNumber())
                .operativeAccountNo(s.getOperativeAccountNo())
                .customerId(s.getCustomerId())
                .dealType(s.getDealType())
                .eventCode(s.getEventCode())
                .scheduleDate(s.getScheduleDate())
                .scheduleAmount(s.getScheduleAmount())
                .currencyCode(s.getCurrencyCode())
                .status(s.getStatus())
                .executionDateTime(s.getExecutionDateTime())
                .executedBy(s.getExecutedBy())
                .errorCode(s.getErrorCode())
                .errorMessage(s.getErrorMessage())
                .createdDateTime(s.getCreatedDateTime())
                .build();
    }
}
