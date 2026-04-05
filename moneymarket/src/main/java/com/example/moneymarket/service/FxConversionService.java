package com.example.moneymarket.service;

import com.example.moneymarket.dto.TransactionLineResponseDTO;
import com.example.moneymarket.dto.TransactionResponseDTO;
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

        // 2. Fetch Mid Rate (always needed)
        LocalDate tranDate = systemDateService.getSystemDate();
        BigDecimal midRate = fetchMidRate(currencyCode, tranDate);
        BigDecimal waeRate = null;

        log.info("Mid Rate: {}", midRate);

        // 3. Compute amounts
        BigDecimal lcyEquiv = fcyAmount.multiply(dealRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lcyEquiv1 = null;
        BigDecimal gainLossAmount = null;

        if ("SELLING".equals(transactionType)) {
            // Calculate WAE from tran_table for the specific Nostro account (real-time)
            waeRate = calculateWaeFromTranTable(nostroAccountNo, currencyCode);
            if (waeRate == null) {
                log.warn("WAE for Nostro {} is N/A (zero FCY balance), using MID rate {} as fallback", nostroAccountNo, midRate);
                waeRate = midRate;
            }
            log.info("WAE Rate (from tran_table): {}", waeRate);

            // Observation #1: Office Asset (Nostro) cannot be credited beyond zero
            validateOfficeAssetAccountBalance(nostroAccountNo, currencyCode, fcyAmount);

            lcyEquiv1 = fcyAmount.multiply(waeRate).setScale(2, RoundingMode.HALF_UP);

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

        // Line 1: CR Nostro Account (FCY at WAE)
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

        // Line 2: DR Position FCY GL (at WAE)
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

        // Line 3: CR Position BDT GL (at WAE)
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

        // Line 5 (or 4 if no gain/loss): DR Customer Account (BDT at Deal Rate)
        int lastLineNum = (gainLossAmount != null && gainLossAmount.compareTo(BigDecimal.ZERO) != 0) ? 5 : 4;
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

    /**
     * Observation #1: Validate Office Asset account (Nostro) credit rule.
     *
     * For SELLING:
     * - Nostro account is credited (FCY balance decreases)
     * - Office Asset Accounts can only be credited up to zero available balance
     * - i.e., after credit, balance must not become negative
     */
    private void validateOfficeAssetAccountBalance(String nostroAccountNo, String currencyCode, BigDecimal fcyAmountToSell) {
        log.info("========== VALIDATING OFFICE ASSET ACCOUNT (OBS #1) ==========");
        log.info("Nostro: {}, CCY: {}, Sell Amount (SELLING / CREDIT): {}", nostroAccountNo, currencyCode, fcyAmountToSell);

        // Ensure we only apply this rule to office asset accounts
        OFAcctMaster nostro = ofAcctMasterRepository.findById(nostroAccountNo)
                .orElseThrow(() -> new ResourceNotFoundException("Nostro Account", "Account Number", nostroAccountNo));
        if (nostro.getGlNum() == null || !nostro.getGlNum().startsWith("2")) {
            // Not an Office Asset account: skip this validation
            log.warn("Skipping OBS #1 validation: account {} GL {} is not asset-like (GL should start with '2')",
                    nostroAccountNo, nostro.getGlNum());
            return;
        }

        // Use the exact same "Available Balance" computation as the account-balance UI (BalanceService)
        com.example.moneymarket.dto.AccountBalanceDTO bal = balanceService.getComputedAccountBalance(nostroAccountNo);
        BigDecimal currentAvailable = bal.getAvailableBalance() != null ? bal.getAvailableBalance() : BigDecimal.ZERO;
        // In this system, Office Asset accounts have DEBIT normal balance represented as negative numbers.
        // For SELLING (crediting Nostro), we can only credit up to 0 (cannot become positive).
        // Max sellable = abs(current balance) when current < 0, else 0.
        BigDecimal maximumSellable = currentAvailable.compareTo(BigDecimal.ZERO) < 0
                ? currentAvailable.abs()
                : BigDecimal.ZERO;

        // Defensive: currency mismatch should never happen because earlier validations checked FCY currency.
        if (bal.getAccountCcy() != null && !currencyCode.equalsIgnoreCase(bal.getAccountCcy())) {
            throw new BusinessException(String.format(
                    "Currency mismatch while validating Nostro. Expected %s but balance-service returned %s.",
                    currencyCode, bal.getAccountCcy()));
        }

        // SELLING = CREDITing Nostro in FCY terms.
        // Mirror TransactionValidationService office-asset CREDIT logic:
        // resultingBalance = availableBalance + creditAmount, must be <= 0
        BigDecimal resultingBalance = currentAvailable.add(fcyAmountToSell);
        log.info("Current Available Balance: {} {}; Resulting after SELLING credit: {} {}",
                currentAvailable, currencyCode, resultingBalance, currencyCode);

        // Scenario 5: invalid state (already positive / credit balance for an asset account)
        if (currentAvailable.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(String.format(
                    "VALIDATION FAILED: Invalid Nostro Account State\n\n" +
                            "Office Asset (Nostro) Account %s currently has a CREDIT balance, which is not allowed for Asset accounts.\n\n" +
                            "Current Available Balance: +%.2f %s (INVALID)\n" +
                            "Maximum Sellable Amount: 0.00 %s\n\n" +
                            "Action Required:\n" +
                            "• Investigate and correct the account balance, OR\n" +
                            "• Contact system administrator, OR\n" +
                            "• Select another valid Nostro account",
                    nostroAccountNo,
                    currentAvailable, currencyCode,
                    currencyCode
            ));
        }

        // Scenario 4: zero balance -> cannot sell anything
        if (currentAvailable.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException(String.format(
                    "VALIDATION FAILED: Insufficient Nostro Balance\n\n" +
                            "Office Asset (Nostro) can ONLY be credited up to ZERO available balance.\n\n" +
                            "Account: %s\n" +
                            "Currency: %s\n" +
                            "Current Available Balance: 0.00 %s\n" +
                            "Requested Sell Amount: %.2f %s\n\n" +
                            "Maximum Sellable Amount: 0.00 %s\n\n" +
                            "Action Required:\n" +
                            "• Fund the Nostro account, OR\n" +
                            "• Select another Nostro account with positive debit balance",
                    nostroAccountNo,
                    currencyCode,
                    currencyCode,
                    fcyAmountToSell, currencyCode,
                    currencyCode
            ));
        }

        // Scenario 3: resulting would become positive -> reject
        if (resultingBalance.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(String.format(
                    "VALIDATION FAILED: Insufficient Nostro Balance\n\n" +
                            "Office Asset (Nostro) can ONLY be credited up to ZERO available balance.\n\n" +
                            "Account: %s\n" +
                            "Currency: %s\n" +
                            "Current Available Balance: %.2f %s\n" +
                            "Requested Sell Amount: %.2f %s\n" +
                            "Resulting Balance: +%.2f %s (INVALID - Cannot be positive)\n\n" +
                            "Maximum Sellable Amount: %.2f %s\n\n" +
                            "Office Asset Accounts cannot have positive (Credit) balances.\n\n" +
                            "Action Required:\n" +
                            "• Reduce sell amount to maximum %.2f %s, OR\n" +
                            "• Select another Nostro account with sufficient balance",
                    nostroAccountNo,
                    currencyCode,
                    currentAvailable, currencyCode,
                    fcyAmountToSell, currencyCode,
                    resultingBalance, currencyCode,
                    maximumSellable, currencyCode,
                    maximumSellable, currencyCode
            ));
        }

        log.info("✓ Office Asset (Nostro) SELLING validation passed. Resulting balance: {} {}", resultingBalance, currencyCode);
        log.info("========== OFFICE ASSET VALIDATION PASSED ==========");
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
     * Calculate WAE for a specific Nostro account.
     *
     * Observation #2:
     * - Return null (WAE = N/A) when the selected Nostro account's available balance is zero,
     *   matching the behavior in `BalanceService`.
     */
    public BigDecimal calculateWaeFromTranTable(String nostroAccountNo, String currencyCode) {
        log.info("========== CALCULATE WAE FOR NOSTRO (OBS #2) ==========");
        log.info("Nostro: {}, CCY: {}", nostroAccountNo, currencyCode);

        com.example.moneymarket.dto.AccountBalanceDTO bal = balanceService.getComputedAccountBalance(nostroAccountNo);

        if (bal.getAccountCcy() != null && !currencyCode.equalsIgnoreCase(bal.getAccountCcy())) {
            throw new BusinessException(String.format(
                    "Currency mismatch while calculating WAE. Expected %s but balance-service returned %s.",
                    currencyCode, bal.getAccountCcy()));
        }

        if (bal.getWae() == null) {
            log.warn("Nostro {} available balance is zero — WAE = N/A", nostroAccountNo);
        } else {
            log.info("SUCCESS: WAE for {} = {}", nostroAccountNo, bal.getWae());
        }

        // BalanceService already enforces: if computedBalance is zero -> WAE=null
        return bal.getWae();
    }

    /**
     * Get net FCY balance for a Nostro account from tran_table (real-time).
     * Used for Observation #1 balance validation.
     */
    public BigDecimal getNostroFcyBalance(String nostroAccountNo, String currencyCode) {
        BigDecimal drFcy = tranTableRepository.sumDebitFcyByAccountAndCcy(nostroAccountNo, currencyCode);
        BigDecimal crFcy = tranTableRepository.sumCreditFcyByAccountAndCcy(nostroAccountNo, currencyCode);
        return drFcy.subtract(crFcy);
    }

    /**
     * Calculate aggregate WAE across all Nostro accounts for a currency from tran_table.
     * Returns null if total FCY balance is zero.
     */
    public BigDecimal calculateAggregateWaeFromTranTable(String currencyCode) {
        log.info("========== CALCULATE AGGREGATE WAE FROM TRAN_TABLE: {} ==========", currencyCode);

        List<OFAcctMaster> nostroAccounts = ofAcctMasterRepository.findAll().stream()
                .filter(acc -> acc.getAccountStatus() == OFAcctMaster.AccountStatus.Active
                        && currencyCode.equals(acc.getAccountCcy())
                        && acc.getGlNum() != null && acc.getGlNum().startsWith("22030"))
                .collect(Collectors.toList());

        if (nostroAccounts.isEmpty()) {
            log.warn("No active NOSTRO accounts found for currency: {}", currencyCode);
            return null;
        }

        BigDecimal totalFcy = BigDecimal.ZERO;
        BigDecimal totalLcy = BigDecimal.ZERO;

        for (OFAcctMaster nostro : nostroAccounts) {
            String accountNo = nostro.getAccountNo();
            BigDecimal drFcy = tranTableRepository.sumDebitFcyByAccountAndCcy(accountNo, currencyCode);
            BigDecimal crFcy = tranTableRepository.sumCreditFcyByAccountAndCcy(accountNo, currencyCode);
            BigDecimal drLcy = tranTableRepository.sumDebitLcyByAccountAndCcy(accountNo, currencyCode);
            BigDecimal crLcy = tranTableRepository.sumCreditLcyByAccountAndCcy(accountNo, currencyCode);

            BigDecimal netFcy = drFcy.subtract(crFcy);
            BigDecimal netLcy = drLcy.subtract(crLcy);

            if (netFcy.compareTo(BigDecimal.ZERO) > 0) {
                log.info("  Nostro {}: FCY={}, LCY={}", accountNo, netFcy, netLcy);
                totalFcy = totalFcy.add(netFcy);
                totalLcy = totalLcy.add(netLcy);
            }
        }

        if (totalFcy.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Total {} FCY balance across all Nostros is zero — aggregate WAE = N/A", currencyCode);
            return null;
        }

        BigDecimal wae = totalLcy.divide(totalFcy, 6, RoundingMode.HALF_UP);
        log.info("Aggregate WAE for {}: {} / {} = {}", currencyCode, totalLcy, totalFcy, wae);
        return wae;
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
