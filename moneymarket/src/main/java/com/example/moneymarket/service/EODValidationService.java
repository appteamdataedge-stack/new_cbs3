package com.example.moneymarket.service;

import com.example.moneymarket.entity.AcctBal;
import com.example.moneymarket.entity.AcctBalLcy;
import com.example.moneymarket.entity.GLBalance;
import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.repository.AcctBalLcyRepository;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.GLBalanceRepository;
import com.example.moneymarket.repository.ParameterTableRepository;
import com.example.moneymarket.repository.TranTableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for EOD pre-validation checks
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EODValidationService {

    private final ParameterTableRepository parameterTableRepository;
    private final TranTableRepository tranTableRepository;
    private final GLBalanceRepository glBalanceRepository;
    private final AcctBalRepository acctBalRepository;
    private final AcctBalLcyRepository acctBalLcyRepository;

    @Value("${eod.admin.user:ADMIN}")
    private String eodAdminUser;

    /**
     * Perform all pre-EOD validations
     */
    @Transactional(readOnly = true)
    public EODValidationResult performPreEODValidations(String userId, LocalDate systemDate) {
        log.info("Starting pre-EOD validations for user: {}, system date: {}", userId, systemDate);
        
        // Validation 1: Verify only EOD admin user is logged in
        EODValidationResult adminValidation = validateEODAdminUser(userId);
        if (!adminValidation.isValid()) {
            return adminValidation;
        }
        
        // Validation 2: Verify no transactions remain in 'Entry' status
        EODValidationResult entryStatusValidation = validateNoEntryStatusTransactions(systemDate);
        if (!entryStatusValidation.isValid()) {
            return entryStatusValidation;
        }
        
        // Validation 3: Verify debit-credit balance within Tran_Table
        EODValidationResult balanceValidation = validateDebitCreditBalance(systemDate);
        if (!balanceValidation.isValid()) {
            return balanceValidation;
        }
        
        log.info("All pre-EOD validations passed successfully");
        return EODValidationResult.success("All pre-EOD validations passed");
    }

    /**
     * Post-EOD validations (report integrity checks) for a given business date.
     *
     * Uses the same sources as reporting:
     * - Trial Balance / Balance Sheet: gl_balance
     * - WAE checks: acct_bal + acct_bal_lcy
     *
     * NOTE: This does not mutate state; safe to run multiple times.
     */
    @Transactional(readOnly = true)
    public PostEodValidationReport performPostEodValidations(LocalDate businessDate) {
        LocalDate date = businessDate;
        Map<String, PostEodValidationItem> items = new LinkedHashMap<>();

        items.put("trialBalance", validateTrialBalanceFromGlBalance(date));
        items.put("balanceSheet", validateBalanceSheetFromGlBalance(date));
        items.put("wae_position_accounts", validateWaeForAccounts(date, List.of("920101001", "920101002")));
        items.put("wae_nostro_accounts", validateWaeForNostroAccounts(date));

        boolean allPassed = items.values().stream().allMatch(PostEodValidationItem::isPassed);
        return new PostEodValidationReport(date, allPassed, items);
    }

    private PostEodValidationItem validateTrialBalanceFromGlBalance(LocalDate date) {
        List<GLBalance> balances = glBalanceRepository.findByTranDate(date);
        BigDecimal totalDr = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;

        // Trial Balance from gl_balance: use DR/CR summations (already normalized)
        for (GLBalance b : balances) {
            totalDr = totalDr.add(nvl(b.getDrSummation()));
            totalCr = totalCr.add(nvl(b.getCrSummation()));
        }

        BigDecimal diff = totalDr.subtract(totalCr).abs();
        boolean passed = diff.compareTo(new BigDecimal("0.01")) < 0;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalDebits", totalDr);
        details.put("totalCredits", totalCr);
        details.put("difference", diff);

        String message = passed
                ? "✅ Trial Balance balanced (DR = CR)"
                : "❌ Trial Balance NOT balanced (DR ≠ CR)";

        return new PostEodValidationItem(passed, message, details);
    }

    private PostEodValidationItem validateBalanceSheetFromGlBalance(LocalDate date) {
        List<GLBalance> balances = glBalanceRepository.findByTranDate(date);

        BigDecimal totalAssets = BigDecimal.ZERO;      // GL starting with 1
        BigDecimal totalLiabilities = BigDecimal.ZERO; // GL starting with 2
        BigDecimal totalEquity = BigDecimal.ZERO;      // GL starting with 3

        for (GLBalance b : balances) {
            String gl = b.getGlNum();
            BigDecimal closing = nvl(b.getClosingBal());
            if (gl == null || gl.isBlank()) continue;
            if (gl.startsWith("1")) totalAssets = totalAssets.add(closing);
            else if (gl.startsWith("2")) totalLiabilities = totalLiabilities.add(closing);
            else if (gl.startsWith("3")) totalEquity = totalEquity.add(closing);
        }

        BigDecimal liabilitiesPlusEquity = totalLiabilities.add(totalEquity);
        BigDecimal diff = totalAssets.subtract(liabilitiesPlusEquity).abs();
        boolean passed = diff.compareTo(new BigDecimal("0.01")) < 0;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalAssets", totalAssets);
        details.put("totalLiabilities", totalLiabilities);
        details.put("totalEquity", totalEquity);
        details.put("liabilitiesPlusEquity", liabilitiesPlusEquity);
        details.put("difference", diff);

        String message = passed
                ? "✅ Balance Sheet balanced (Assets = Liabilities + Equity)"
                : "❌ Balance Sheet NOT balanced (Assets ≠ Liabilities + Equity)";

        return new PostEodValidationItem(passed, message, details);
    }

    private PostEodValidationItem validateWaeForAccounts(LocalDate date, List<String> accountNos) {
        List<AcctBal> fcy = acctBalRepository.findByAccountNoInAndTranDate(accountNos, date);
        List<AcctBalLcy> lcy = acctBalLcyRepository.findByAccountNoInAndTranDate(accountNos, date);

        Map<String, AcctBal> fcyByAcc = fcy.stream().collect(Collectors.toMap(AcctBal::getAccountNo, a -> a, (a, b) -> a));
        Map<String, AcctBalLcy> lcyByAcc = lcy.stream().collect(Collectors.toMap(AcctBalLcy::getAccountNo, a -> a, (a, b) -> a));

        BigDecimal maxDiff = BigDecimal.ZERO;
        int checked = 0;
        int mismatched = 0;

        for (String acc : accountNos) {
            AcctBal ab = fcyByAcc.get(acc);
            AcctBalLcy abl = lcyByAcc.get(acc);
            if (ab == null || abl == null) continue;

            BigDecimal fcyBal = nvl(ab.getClosingBal());
            BigDecimal lcyBal = nvl(abl.getClosingBalLcy());
            BigDecimal storedWae = ab.getWaeRate();

            if (fcyBal.compareTo(BigDecimal.ZERO) == 0) {
                continue; // WAE undefined; skip strict diff check
            }

            BigDecimal calc = lcyBal.abs().divide(fcyBal.abs(), 4, RoundingMode.HALF_UP);
            BigDecimal diff = storedWae == null ? calc : calc.subtract(storedWae).abs();
            maxDiff = maxDiff.max(diff);
            checked++;

            if (storedWae == null || diff.compareTo(new BigDecimal("0.01")) >= 0) {
                mismatched++;
            }
        }

        boolean passed = mismatched == 0;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accounts", accountNos);
        details.put("checked", checked);
        details.put("mismatched", mismatched);
        details.put("maxDifference", maxDiff);
        details.put("tolerance", new BigDecimal("0.01"));

        String message = passed
                ? "✅ WAE matches (acct_bal_lcy / acct_bal) within tolerance"
                : "❌ WAE mismatch detected for one or more accounts";

        return new PostEodValidationItem(passed, message, details);
    }

    private PostEodValidationItem validateWaeForNostroAccounts(LocalDate date) {
        // “Nostro” is identified by OF accounts whose GL starts with 22030 in FX module,
        // but for EOD WAE validation we only need to validate any FCY account that has WAE stored.
        // Here we target common Nostro prefix 9220*** per your business definition.
        List<AcctBal> todays = acctBalRepository.findByTranDate(date);
        List<String> nostroAcc = todays.stream()
                .map(AcctBal::getAccountNo)
                .filter(a -> a != null && a.startsWith("9220"))
                .distinct()
                .collect(Collectors.toList());

        if (nostroAcc.isEmpty()) {
            return new PostEodValidationItem(true, "✅ No Nostro accounts found to validate for this date", Map.of("count", 0));
        }
        return validateWaeForAccounts(date, nostroAcc);
    }

    private BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Validation 1: Verify only the EOD admin user is logged in
     */
    private EODValidationResult validateEODAdminUser(String userId) {
        log.info("Validating EOD admin user: {}", userId);
        
        Optional<String> adminUser = parameterTableRepository.getEODAdminUser();
        String expectedAdminUser = adminUser.orElse(eodAdminUser);
        
        if (!expectedAdminUser.equals(userId)) {
            String errorMessage = String.format("User '%s' is not authorized to run EOD. Only '%s' is allowed.", 
                    userId, expectedAdminUser);
            log.error(errorMessage);
            return EODValidationResult.failure(errorMessage);
        }
        
        log.info("EOD admin user validation passed");
        return EODValidationResult.success("EOD admin user validation passed");
    }

    /**
     * Validation 2: Verify no transactions remain in status 'Entry' - all must be 'Verified'
     */
    private EODValidationResult validateNoEntryStatusTransactions(LocalDate systemDate) {
        log.info("Validating no transactions in 'Entry' status for system date: {}", systemDate);
        
        List<TranTable> entryStatusTransactions = tranTableRepository.findByTranDateBetween(systemDate, systemDate)
                .stream()
                .filter(t -> t.getTranStatus() == TranTable.TranStatus.Entry)
                .collect(Collectors.toList());
        
        if (!entryStatusTransactions.isEmpty()) {
            List<String> tranIds = entryStatusTransactions.stream()
                    .map(TranTable::getTranId)
                    .collect(Collectors.toList());
            
            String errorMessage = String.format("Found %d transactions in 'Entry' status that must be verified before EOD. " +
                    "Transaction IDs: %s", entryStatusTransactions.size(), String.join(", ", tranIds));
            log.error(errorMessage);
            return EODValidationResult.failure(errorMessage);
        }
        
        log.info("No transactions in 'Entry' status found - validation passed");
        return EODValidationResult.success("No transactions in 'Entry' status found");
    }

    /**
     * Validation 3: Verify debit-credit balance within Tran_Table for System_Date
     */
    private EODValidationResult validateDebitCreditBalance(LocalDate systemDate) {
        log.info("Validating debit-credit balance for system date: {}", systemDate);
        
        List<TranTable> transactions = tranTableRepository.findByTranDateBetween(systemDate, systemDate)
                .stream()
                .filter(t -> t.getTranStatus() == TranTable.TranStatus.Verified)
                .collect(Collectors.toList());
        
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        
        for (TranTable transaction : transactions) {
            if (transaction.getDrCrFlag() == TranTable.DrCrFlag.D) {
                totalDebit = totalDebit.add(transaction.getLcyAmt());
            } else if (transaction.getDrCrFlag() == TranTable.DrCrFlag.C) {
                totalCredit = totalCredit.add(transaction.getLcyAmt());
            }
        }
        
        log.info("Total Debit: {}, Total Credit: {}", totalDebit, totalCredit);
        
        if (totalDebit.compareTo(totalCredit) != 0) {
            String errorMessage = String.format("Debit-Credit balance mismatch for system date %s. " +
                    "Total Debit: %s, Total Credit: %s, Difference: %s", 
                    systemDate, totalDebit, totalCredit, totalDebit.subtract(totalCredit));
            log.error(errorMessage);
            return EODValidationResult.failure(errorMessage);
        }
        
        log.info("Debit-credit balance validation passed");
        return EODValidationResult.success("Debit-credit balance validation passed");
    }

    /**
     * EOD Validation Result class
     */
    public static class EODValidationResult {
        private final boolean valid;
        private final String message;

        private EODValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static EODValidationResult success(String message) {
            return new EODValidationResult(true, message);
        }

        public static EODValidationResult failure(String message) {
            return new EODValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public String getErrorMessage() {
            return valid ? null : message;
        }
    }

    public static class PostEodValidationReport {
        private final LocalDate businessDate;
        private final boolean passed;
        private final Map<String, PostEodValidationItem> items;

        public PostEodValidationReport(LocalDate businessDate, boolean passed, Map<String, PostEodValidationItem> items) {
            this.businessDate = businessDate;
            this.passed = passed;
            this.items = items;
        }

        public LocalDate getBusinessDate() { return businessDate; }
        public boolean isPassed() { return passed; }
        public Map<String, PostEodValidationItem> getItems() { return items; }
    }

    public static class PostEodValidationItem {
        private final boolean passed;
        private final String message;
        private final Map<String, Object> details;

        public PostEodValidationItem(boolean passed, String message, Map<String, Object> details) {
            this.passed = passed;
            this.message = message;
            this.details = details;
        }

        public boolean isPassed() { return passed; }
        public String getMessage() { return message; }
        public Map<String, Object> getDetails() { return details; }
    }
}
