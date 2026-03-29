package com.example.moneymarket.service;

import com.example.moneymarket.dto.TransactionLineResponseDTO;
import com.example.moneymarket.dto.TransactionResponseDTO;
import com.example.moneymarket.entity.AcctBal;
import com.example.moneymarket.entity.AcctBalLcy;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.GLSetup;
import com.example.moneymarket.entity.OFAcctMaster;
import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.exception.ResourceNotFoundException;
import com.example.moneymarket.repository.AcctBalLcyRepository;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.GLSetupRepository;
import com.example.moneymarket.repository.OFAcctMasterRepository;
import com.example.moneymarket.repository.TranTableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FxConversionService {

    private final TranTableRepository tranTableRepository;
    private final GLSetupRepository glSetupRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final AcctBalRepository acctBalRepository;
    private final AcctBalLcyRepository acctBalLcyRepository;
    private final SystemDateService systemDateService;
    private final BalanceService balanceService;
    private final ExchangeRateService exchangeRateService;

    private final Random random = new Random();

    @Transactional
    public TransactionResponseDTO createFxConversion(String transactionType, String customerAccountNo,
                                                      String nostroAccountNo, String currencyCode,
                                                      BigDecimal fcyAmount, BigDecimal dealRate,
                                                      String particulars, String userId) {
        log.info("Creating FX Conversion: type={}, customer={}, nostro={}, ccy={}, fcy={}, rate={}",
                transactionType, customerAccountNo, nostroAccountNo, currencyCode, fcyAmount, dealRate);

        // 1. Validations
        validateFxConversionRequest(transactionType, customerAccountNo, nostroAccountNo,
                currencyCode, fcyAmount, dealRate);

        // 2. Fetch Mid Rate and WAE Rate (server-side only)
        LocalDate tranDate = systemDateService.getSystemDate();
        BigDecimal midRate = fetchMidRate(currencyCode, tranDate);
        BigDecimal waeRate = calculateWAE(currencyCode, tranDate);

        log.info("Rates fetched - Mid: {}, WAE: {}", midRate, waeRate);

        // 3. Compute amounts
        BigDecimal lcyEquiv = fcyAmount.multiply(dealRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lcyEquiv1 = null;
        BigDecimal gainLossAmount = null;

        if ("SELLING".equals(transactionType)) {
            lcyEquiv1 = fcyAmount.multiply(waeRate).setScale(2, RoundingMode.HALF_UP);
            
            // Validate SELLING-specific rules
            validateSellingTransaction(customerAccountNo, nostroAccountNo, currencyCode, fcyAmount, lcyEquiv);

            // Calculate gain/loss
            int comparison = dealRate.compareTo(waeRate);
            if (comparison > 0) {
                gainLossAmount = fcyAmount.multiply(dealRate.subtract(waeRate))
                        .setScale(2, RoundingMode.HALF_UP);
                log.info("GAIN scenario: deal_rate > wae_rate, gain={}", gainLossAmount);
            } else if (comparison < 0) {
                gainLossAmount = fcyAmount.multiply(waeRate.subtract(dealRate))
                        .negate()
                        .setScale(2, RoundingMode.HALF_UP);
                log.info("LOSS scenario: deal_rate < wae_rate, loss={}", gainLossAmount);
            } else {
                log.info("NO GAIN/LOSS: deal_rate = wae_rate");
            }
        }

        // 4. Build transaction lines
        List<TranTable> lines = new ArrayList<>();
        String tranId = generateTransactionId();
        LocalDate valueDate = tranDate;

        if ("BUYING".equals(transactionType)) {
            lines = buildBuyingTransactionLines(tranId, tranDate, valueDate, customerAccountNo, nostroAccountNo,
                    currencyCode, fcyAmount, dealRate, midRate, waeRate, lcyEquiv, particulars);
        } else {
            lines = buildSellingTransactionLines(tranId, tranDate, valueDate, customerAccountNo, nostroAccountNo,
                    currencyCode, fcyAmount, dealRate, midRate, waeRate, lcyEquiv, lcyEquiv1, gainLossAmount, particulars);
        }

        // 5. Verify ledger balance
        verifyLedgerBalance(lines);

        // 6. Save all transaction lines with status = Entry (maker-checker workflow)
        tranTableRepository.saveAll(lines);

        log.info("FX Conversion transaction created with ID: {} in Entry status (pending approval)", tranId);

        // 7. Build response
        return buildResponse(tranId, tranDate, valueDate, particulars, lines, lcyEquiv, lcyEquiv1, gainLossAmount);
    }

    private List<TranTable> buildBuyingTransactionLines(String baseTranId, LocalDate tranDate, LocalDate valueDate,
                                                          String customerAccountNo, String nostroAccountNo,
                                                          String currencyCode, BigDecimal fcyAmount, BigDecimal dealRate,
                                                          BigDecimal midRate, BigDecimal waeRate, BigDecimal lcyEquiv,
                                                          String particulars) {
        List<TranTable> lines = new ArrayList<>();

        // Get GL accounts
        String nostroGlNum = getAccountGlNum(nostroAccountNo);
        String customerGlNum = getAccountGlNum(customerAccountNo);
        String positionFcyGl = getGlNumByName("PSUSD EQIV");
        String positionBdtGl = getGlNumByName("PSUSD");

        // Line 1: DR Nostro Account (FCY)
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-1")
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.D)
                .tranStatus(TranStatus.Entry)
                .accountNo(nostroAccountNo)
                .glNum(nostroGlNum)
                .tranCcy(currencyCode)
                .fcyAmt(fcyAmount)
                .exchangeRate(dealRate)
                .lcyAmt(lcyEquiv)
                .debitAmount(lcyEquiv)
                .creditAmount(BigDecimal.ZERO)
                .narration(particulars != null ? particulars : "FX Buying - Nostro DR")
                .tranType("FXC")
                .tranSubType("BUYING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .gainLossAmt(null)
                .build());

        // Line 2: CR Position FCY GL
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-2")
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.C)
                .tranStatus(TranStatus.Entry)
                .accountNo(positionFcyGl)
                .glNum(positionFcyGl)
                .tranCcy(currencyCode)
                .fcyAmt(fcyAmount)
                .exchangeRate(dealRate)
                .lcyAmt(lcyEquiv)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(lcyEquiv)
                .narration("FX Buying - Position FCY CR")
                .tranType("FXC")
                .tranSubType("BUYING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .build());

        // Line 3: DR Position BDT GL
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-3")
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.D)
                .tranStatus(TranStatus.Entry)
                .accountNo(positionBdtGl)
                .glNum(positionBdtGl)
                .tranCcy("BDT")
                .fcyAmt(lcyEquiv)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(lcyEquiv)
                .debitAmount(lcyEquiv)
                .creditAmount(BigDecimal.ZERO)
                .narration("FX Buying - Position BDT DR")
                .tranType("FXC")
                .tranSubType("BUYING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .build());

        // Line 4: CR Customer Account (BDT)
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-4")
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.C)
                .tranStatus(TranStatus.Entry)
                .accountNo(customerAccountNo)
                .glNum(customerGlNum)
                .tranCcy("BDT")
                .fcyAmt(lcyEquiv)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(lcyEquiv)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(lcyEquiv)
                .narration(particulars != null ? particulars : "FX Buying - Customer CR")
                .tranType("FXC")
                .tranSubType("BUYING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .build());

        return lines;
    }

    private List<TranTable> buildSellingTransactionLines(String baseTranId, LocalDate tranDate, LocalDate valueDate,
                                                           String customerAccountNo, String nostroAccountNo,
                                                           String currencyCode, BigDecimal fcyAmount, BigDecimal dealRate,
                                                           BigDecimal midRate, BigDecimal waeRate, BigDecimal lcyEquiv,
                                                           BigDecimal lcyEquiv1, BigDecimal gainLossAmount, String particulars) {
        List<TranTable> lines = new ArrayList<>();

        String nostroGlNum = getAccountGlNum(nostroAccountNo);
        String customerGlNum = getAccountGlNum(customerAccountNo);
        String positionFcyGl = getGlNumByName("PSUSD EQIV");
        String positionBdtGl = getGlNumByName("PSUSD");

        // Line 1: CR Nostro Account (FCY)
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-1")
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.C)
                .tranStatus(TranStatus.Entry)
                .accountNo(nostroAccountNo)
                .glNum(nostroGlNum)
                .tranCcy(currencyCode)
                .fcyAmt(fcyAmount)
                .exchangeRate(waeRate)
                .lcyAmt(lcyEquiv1)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(lcyEquiv1)
                .narration(particulars != null ? particulars : "FX Selling - Nostro CR")
                .tranType("FXC")
                .tranSubType("SELLING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .gainLossAmt(gainLossAmount)
                .build());

        // Line 2: DR Position FCY GL
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-2")
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.D)
                .tranStatus(TranStatus.Entry)
                .accountNo(positionFcyGl)
                .glNum(positionFcyGl)
                .tranCcy(currencyCode)
                .fcyAmt(fcyAmount)
                .exchangeRate(waeRate)
                .lcyAmt(lcyEquiv1)
                .debitAmount(lcyEquiv1)
                .creditAmount(BigDecimal.ZERO)
                .narration("FX Selling - Position FCY DR")
                .tranType("FXC")
                .tranSubType("SELLING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .build());

        // Line 3: CR Position BDT GL
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-3")
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.C)
                .tranStatus(TranStatus.Entry)
                .accountNo(positionBdtGl)
                .glNum(positionBdtGl)
                .tranCcy("BDT")
                .fcyAmt(lcyEquiv1)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(lcyEquiv1)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(lcyEquiv1)
                .narration("FX Selling - Position BDT CR")
                .tranType("FXC")
                .tranSubType("SELLING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .build());

        // Line 4 (conditional): Gain/Loss
        if (gainLossAmount != null && gainLossAmount.compareTo(BigDecimal.ZERO) != 0) {
            boolean isGain = gainLossAmount.compareTo(BigDecimal.ZERO) > 0;
            String gainLossGl = isGain ? getGlNumByName("Realised Forex Gain") : getGlNumByName("Realised Forex Loss");
            DrCrFlag drCr = isGain ? DrCrFlag.C : DrCrFlag.D;
            BigDecimal absAmount = gainLossAmount.abs();

            lines.add(TranTable.builder()
                    .tranId(baseTranId + "-4")
                    .tranDate(tranDate)
                    .valueDate(valueDate)
                    .drCrFlag(drCr)
                    .tranStatus(TranStatus.Entry)
                    .accountNo(gainLossGl)
                    .glNum(gainLossGl)
                    .tranCcy("BDT")
                    .fcyAmt(absAmount)
                    .exchangeRate(BigDecimal.ONE)
                    .lcyAmt(absAmount)
                    .debitAmount(isGain ? BigDecimal.ZERO : absAmount)
                    .creditAmount(isGain ? absAmount : BigDecimal.ZERO)
                    .narration(isGain ? "FX Selling - Realised Gain" : "FX Selling - Realised Loss")
                    .tranType("FXC")
                    .tranSubType("SELLING")
                    .dealRate(dealRate)
                    .midRate(midRate)
                    .waeRate(waeRate)
                    .build());
        }

        // Line 5 (or 4 if no gain/loss): DR Customer Account (BDT)
        int lastLineNum = gainLossAmount != null && gainLossAmount.compareTo(BigDecimal.ZERO) != 0 ? 5 : 4;
        lines.add(TranTable.builder()
                .tranId(baseTranId + "-" + lastLineNum)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .drCrFlag(DrCrFlag.D)
                .tranStatus(TranStatus.Entry)
                .accountNo(customerAccountNo)
                .glNum(customerGlNum)
                .tranCcy("BDT")
                .fcyAmt(lcyEquiv)
                .exchangeRate(BigDecimal.ONE)
                .lcyAmt(lcyEquiv)
                .debitAmount(lcyEquiv)
                .creditAmount(BigDecimal.ZERO)
                .narration(particulars != null ? particulars : "FX Selling - Customer DR")
                .tranType("FXC")
                .tranSubType("SELLING")
                .dealRate(dealRate)
                .midRate(midRate)
                .waeRate(waeRate)
                .gainLossAmt(gainLossAmount)
                .build());

        return lines;
    }

    private void validateFxConversionRequest(String transactionType, String customerAccountNo,
                                              String nostroAccountNo, String currencyCode,
                                              BigDecimal fcyAmount, BigDecimal dealRate) {
        // 1. Validate Nostro account currency matches selected currency
        OFAcctMaster nostroAccount = ofAcctMasterRepository.findById(nostroAccountNo)
                .orElseThrow(() -> new ResourceNotFoundException("Nostro Account", "Account Number", nostroAccountNo));

        if (!currencyCode.equals(nostroAccount.getAccountCcy())) {
            throw new BusinessException(String.format(
                    "Nostro account currency %s does not match selected currency %s.",
                    nostroAccount.getAccountCcy(), currencyCode));
        }

        // 2. Validate Customer account is BDT
        CustAcctMaster customerAccount = custAcctMasterRepository.findById(customerAccountNo)
                .orElseThrow(() -> new ResourceNotFoundException("Customer Account", "Account Number", customerAccountNo));

        if (!"BDT".equals(customerAccount.getAccountCcy())) {
            throw new BusinessException("Customer account must be a BDT (LCY) account.");
        }

        // 3. Validate amounts
        if (fcyAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("FCY Amount must be greater than zero.");
        }

        if (dealRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Deal Rate must be greater than zero.");
        }
    }

    private void validateSellingTransaction(String customerAccountNo, String nostroAccountNo,
                                              String currencyCode, BigDecimal fcyAmount, BigDecimal lcyEquiv) {
        log.info("========== VALIDATING SELLING TRANSACTION ==========");
        
        // SELLING Ledger Structure:
        // Line 1: CR Nostro (FCY) - CREDIT, no validation needed
        // Line 2: DR Position FCY - DEBIT, MUST validate
        // Line 3: CR Position BDT - CREDIT, no validation needed
        // Line 4: CR/DR Gain/Loss - GL account, no validation needed
        // Line 5: DR Customer (BDT) - Customer receives BDT, no validation needed
        
        // CRITICAL: In SELLING, customer RECEIVES BDT (not pays), so we validate they're 
        // selling enough FCY from Position account, not their BDT balance
        
        // Validate Position FCY balance (will be debited in Line 2)
        String positionFcyGlCode = getGlNumByName("PSUSD EQIV");
        log.info("Checking Position FCY balance for GL: {}", positionFcyGlCode);
        
        try {
            // Position accounts are GL accounts, use getGLBalance instead of getComputedAccountBalance
            BigDecimal positionFcyBalance = balanceService.getGLBalance(positionFcyGlCode);
            
            log.info("Position FCY balance: {}, Required: {}", positionFcyBalance, fcyAmount);
            
            if (positionFcyBalance.compareTo(fcyAmount) < 0) {
                throw new BusinessException(String.format(
                        "Insufficient Position %s balance. Available: %s. Required: %s.",
                        currencyCode, positionFcyBalance, fcyAmount));
            }
            
            log.info("✓ Position FCY balance sufficient");
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error validating Position FCY balance: ", e);
            throw new BusinessException("Failed to validate Position FCY balance: " + e.getMessage());
        }
        
        // NOTE: We do NOT validate:
        // - Nostro account (Line 1 is CREDIT - credits always increase balance)
        // - Customer BDT account (In SELLING, customer RECEIVES BDT, not pays)
        // - Position BDT account (Line 3 is CREDIT)
        
        log.info("========== SELLING VALIDATION PASSED ==========");
    }

    private void verifyLedgerBalance(List<TranTable> lines) {
        BigDecimal totalDr = lines.stream()
                .filter(l -> l.getDrCrFlag() == DrCrFlag.D)
                .map(TranTable::getLcyAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCr = lines.stream()
                .filter(l -> l.getDrCrFlag() == DrCrFlag.C)
                .map(TranTable::getLcyAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDr.compareTo(totalCr) != 0) {
            throw new BusinessException(String.format(
                    "Ledger imbalance. DR: %s ≠ CR: %s.", totalDr, totalCr));
        }
    }

    /**
     * Fetch Mid Rate from FxRateMaster table (existing exchange_rate table)
     */
    public BigDecimal fetchMidRate(String currencyCode, LocalDate tranDate) {
        log.info("========== FETCH MID RATE ==========");
        log.info("Currency: {}, Date: {}", currencyCode, tranDate);
        
        try {
            BigDecimal rate = exchangeRateService.getExchangeRate(currencyCode, tranDate);
            log.info("SUCCESS: Mid rate = {}", rate);
            return rate;
        } catch (BusinessException e) {
            log.warn("No exchange rate found for {} on {}, trying latest rate", currencyCode, tranDate);
            BigDecimal latestRate = exchangeRateService.getLatestMidRate(currencyCode);
            if (latestRate == null) {
                log.error("FAILED: No exchange rate found at all for {}", currencyCode);
                throw new ResourceNotFoundException("Exchange Rate", "Currency", currencyCode);
            }
            log.info("Using latest rate: {}", latestRate);
            return latestRate;
        }
    }

    /**
     * Calculate WAE (Weighted Average Exchange Rate) dynamically from NOSTRO account balances.
     * 
     * Formula: WAE = SUM(LCY Balance) / SUM(FCY Balance) for all NOSTRO accounts of same currency
     */
    public BigDecimal calculateWAE(String currencyCode, LocalDate tranDate) {
        log.info("========== CALCULATE WAE ==========");
        log.info("Currency: {}, Date: {}", currencyCode, tranDate);

        // Get all NOSTRO accounts for this currency (GL starts with '22030' = NOSTRO)
        List<OFAcctMaster> allAccounts = ofAcctMasterRepository.findAll();
        log.info("Total office accounts in database: {}", allAccounts.size());
        
        List<OFAcctMaster> nostroAccounts = allAccounts.stream()
                .filter(acc -> {
                    boolean statusOk = acc.getAccountStatus() == OFAcctMaster.AccountStatus.Active;
                    boolean currencyOk = currencyCode.equals(acc.getAccountCcy());
                    boolean glOk = acc.getGlNum() != null && acc.getGlNum().startsWith("22030");
                    
                    log.debug("Account {}: status={}, currency={}, GL={} (pattern match: {})", 
                            acc.getAccountNo(), statusOk, currencyOk, acc.getGlNum(), glOk);
                    
                    return statusOk && currencyOk && glOk;
                })
                .collect(Collectors.toList());

        if (nostroAccounts.isEmpty()) {
            log.error("FAILED: No active NOSTRO accounts found for currency: {}", currencyCode);
            log.error("Debug - Showing all office accounts:");
            allAccounts.forEach(acc -> 
                log.error("  Account: {}, GL: {}, Currency: {}, Status: {}", 
                        acc.getAccountNo(), acc.getGlNum(), acc.getAccountCcy(), acc.getAccountStatus()));
            throw new BusinessException("No active NOSTRO accounts found for currency: " + currencyCode);
        }

        log.info("Found {} NOSTRO accounts for {}", nostroAccounts.size(), currencyCode);

        BigDecimal totalFcy = BigDecimal.ZERO;
        BigDecimal totalLcy = BigDecimal.ZERO;

        for (OFAcctMaster nostro : nostroAccounts) {
            String accountNo = nostro.getAccountNo();
            log.info("Processing NOSTRO account: {}", accountNo);

            // Get FCY balance from acc_bal
            BigDecimal fcyBalance = getAccountFcyBalance(accountNo, tranDate);
            log.info("  FCY Balance: {}", fcyBalance);

            // Get LCY balance from acct_bal_lcy
            BigDecimal lcyBalance = getAccountLcyBalance(accountNo, tranDate);
            log.info("  LCY Balance: {}", lcyBalance);

            totalFcy = totalFcy.add(fcyBalance);
            totalLcy = totalLcy.add(lcyBalance);
        }

        log.info("Total FCY: {}, Total LCY: {}", totalFcy, totalLcy);

        if (totalFcy.compareTo(BigDecimal.ZERO) == 0) {
            log.error("FAILED: Cannot calculate WAE - Total FCY balance is zero");
            throw new BusinessException(String.format(
                    "Cannot calculate WAE for %s: Total FCY balance is zero across all NOSTRO accounts", 
                    currencyCode));
        }

        BigDecimal wae = totalLcy.abs().divide(totalFcy.abs(), 6, RoundingMode.HALF_UP);
        log.info("SUCCESS: Calculated WAE = {}", wae);

        return wae;
    }

    /**
     * Get FCY balance for an account from acc_bal table
     */
    private BigDecimal getAccountFcyBalance(String accountNo, LocalDate tranDate) {
        // Try to get balance for transaction date
        return acctBalRepository.findByAccountNoAndTranDate(accountNo, tranDate)
                .map(AcctBal::getClosingBal)
                .orElseGet(() -> {
                    log.debug("No acc_bal for {} on {}, trying latest", accountNo, tranDate);
                    // Fallback to latest balance
                    return acctBalRepository.findLatestByAccountNo(accountNo)
                            .map(AcctBal::getClosingBal)
                            .orElse(BigDecimal.ZERO);
                });
    }

    /**
     * Get LCY balance for an account from acct_bal_lcy table
     */
    private BigDecimal getAccountLcyBalance(String accountNo, LocalDate tranDate) {
        // Try to get balance for transaction date
        return acctBalLcyRepository.findByAccountNoAndTranDate(accountNo, tranDate)
                .map(AcctBalLcy::getClosingBalLcy)
                .orElseGet(() -> {
                    log.debug("No acct_bal_lcy for {} on {}, trying latest", accountNo, tranDate);
                    // Fallback to latest balance
                    return acctBalLcyRepository.findLatestByAccountNo(accountNo)
                            .map(AcctBalLcy::getClosingBalLcy)
                            .orElse(BigDecimal.ZERO);
                });
    }

    public List<CustAcctMaster> searchCustomerAccounts(String search) {
        log.info("========== SEARCH CUSTOMER ACCOUNTS ==========");
        log.info("Search term: '{}'", search);
        
        List<CustAcctMaster> allAccounts = custAcctMasterRepository.findAll();
        log.info("Total customer accounts in database: {}", allAccounts.size());
        
        List<CustAcctMaster> filtered = allAccounts.stream()
                .filter(acc -> {
                    // Check status (Active only)
                    boolean statusOk = acc.getAccountStatus() == CustAcctMaster.AccountStatus.Active;
                    if (!statusOk) {
                        log.debug("Account {} filtered out: status={}", acc.getAccountNo(), acc.getAccountStatus());
                        return false;
                    }
                    
                    // Check currency (BDT only)
                    boolean currencyOk = "BDT".equalsIgnoreCase(acc.getAccountCcy());
                    if (!currencyOk) {
                        log.debug("Account {} filtered out: currency={}", acc.getAccountNo(), acc.getAccountCcy());
                        return false;
                    }
                    
                    // Check sub-product code (CA or SB)
                    String subProductCode = null;
                    try {
                        if (acc.getSubProduct() != null) {
                            subProductCode = acc.getSubProduct().getSubProductCode();
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load SubProduct for account {}: {}", acc.getAccountNo(), e.getMessage());
                    }
                    
                    if (subProductCode == null || subProductCode.trim().isEmpty()) {
                        log.debug("Account {} filtered out: sub-product code is null/empty", acc.getAccountNo());
                        return false;
                    }
                    
                    boolean typeOk = subProductCode.startsWith("CA") || subProductCode.startsWith("SB");
                    if (!typeOk) {
                        log.debug("Account {} filtered out: sub-product code '{}' doesn't start with CA/SB", 
                                acc.getAccountNo(), subProductCode);
                        return false;
                    }
                    
                    // Check search term
                    boolean searchOk = search == null || search.trim().isEmpty() ||
                            (acc.getAccountNo() != null && acc.getAccountNo().toLowerCase().contains(search.toLowerCase())) ||
                            (acc.getAcctName() != null && acc.getAcctName().toLowerCase().contains(search.toLowerCase()));
                    
                    if (!searchOk) {
                        log.debug("Account {} filtered out: doesn't match search term '{}'", acc.getAccountNo(), search);
                        return false;
                    }
                    
                    log.info("✓ Account {} PASSED all filters (status={}, currency={}, sub-product={}, name={})", 
                            acc.getAccountNo(), acc.getAccountStatus(), acc.getAccountCcy(), subProductCode, acc.getAcctName());
                    
                    return true;
                })
                .collect(Collectors.toList());
        
        log.info("========== FILTER RESULT: {} accounts matched ==========", filtered.size());
        return filtered;
    }

    public List<OFAcctMaster> getNostroAccounts(String currencyCode) {
        log.info("========== GET NOSTRO ACCOUNTS ==========");
        log.info("Currency: {}", currencyCode);
        
        List<OFAcctMaster> allAccounts = ofAcctMasterRepository.findAll();
        log.info("Total office accounts in database: {}", allAccounts.size());
        
        List<OFAcctMaster> filtered = allAccounts.stream()
                .filter(acc -> {
                    boolean statusOk = acc.getAccountStatus() == OFAcctMaster.AccountStatus.Active;
                    boolean currencyOk = currencyCode.equals(acc.getAccountCcy());
                    boolean glOk = acc.getGlNum() != null && acc.getGlNum().startsWith("22030");
                    
                    log.debug("Office Account {}: status={}, currency={}, GL={} (NOSTRO: {})", 
                            acc.getAccountNo(), statusOk, currencyOk, acc.getGlNum(), glOk);
                    
                    return statusOk && currencyOk && glOk;
                })
                .collect(Collectors.toList());
        
        log.info("Returning {} NOSTRO accounts", filtered.size());
        return filtered;
    }

    private String getAccountGlNum(String accountNo) {
        CustAcctMaster custAcct = custAcctMasterRepository.findById(accountNo).orElse(null);
        if (custAcct != null) {
            return custAcct.getGlNum();
        }

        OFAcctMaster ofAcct = ofAcctMasterRepository.findById(accountNo).orElse(null);
        if (ofAcct != null) {
            return ofAcct.getGlNum();
        }

        throw new ResourceNotFoundException("Account", "Account Number", accountNo);
    }

    private String getGlNumByName(String glName) {
        List<GLSetup> glList = glSetupRepository.findByGlName(glName);
        if (glList.isEmpty()) {
            throw new ResourceNotFoundException("GL Account", "GL Name", glName);
        }
        return glList.get(0).getGlNum();
    }

    private String generateTransactionId() {
        LocalDate systemDate = systemDateService.getSystemDate();
        String date = systemDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long sequenceNumber = tranTableRepository.countByTranDate(systemDate) + 1;
        String sequenceComponent = String.format("%06d", sequenceNumber);
        String randomPart = String.format("%03d", random.nextInt(1000));
        return "F" + date + sequenceComponent + randomPart;
    }

    private TransactionResponseDTO buildResponse(String tranId, LocalDate tranDate, LocalDate valueDate,
                                                   String narration, List<TranTable> lines,
                                                   BigDecimal lcyEquiv, BigDecimal lcyEquiv1, BigDecimal gainLossAmount) {
        List<TransactionLineResponseDTO> lineResponses = lines.stream()
                .map(line -> TransactionLineResponseDTO.builder()
                        .tranId(line.getTranId())
                        .accountNo(line.getAccountNo())
                        .drCrFlag(line.getDrCrFlag())
                        .tranCcy(line.getTranCcy())
                        .fcyAmt(line.getFcyAmt())
                        .exchangeRate(line.getExchangeRate())
                        .lcyAmt(line.getLcyAmt())
                        .glNum(line.getGlNum())
                        .udf1(line.getNarration())
                        .build())
                .collect(Collectors.toList());

        TransactionResponseDTO response = TransactionResponseDTO.builder()
                .tranId(tranId)
                .tranDate(tranDate)
                .valueDate(valueDate)
                .narration(narration)
                .lines(lineResponses)
                .balanced(true)
                .status("Entry")
                .build();

        if (gainLossAmount != null && gainLossAmount.compareTo(BigDecimal.ZERO) != 0) {
            response.setSettlementGainLoss(gainLossAmount.abs());
            response.setSettlementGainLossType(gainLossAmount.compareTo(BigDecimal.ZERO) > 0 ? "GAIN" : "LOSS");
        }

        return response;
    }

    /**
     * Update FX Position tracking when FXC transaction is approved.
     * NOTE: FX position is tracked dynamically from NOSTRO account balances.
     * This method is kept for compatibility but doesn't use fx_position table.
     */
    @Transactional
    public void updateFxPositionOnApproval(TranTable transaction) {
        if (!"FXC".equals(transaction.getTranType())) {
            return;
        }

        String currencyCode = transaction.getTranCcy();
        if ("BDT".equals(currencyCode)) {
            return;
        }

        // FX position is tracked dynamically from NOSTRO balances via calculateWAE()
        // No need to update separate fx_position table
        log.info("FXC transaction {} posted. FX position tracked via NOSTRO balances.", 
                transaction.getTranId());
    }
}
