package com.example.moneymarket.service;

import com.example.moneymarket.entity.SettlementGainLoss;
import com.example.moneymarket.repository.SettlementGainLossRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for Settlement Gain/Loss Reporting
 * Generates various reports for settlement gain/loss analysis and audit
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementReportService {

    private final SettlementGainLossRepository settlementGainLossRepository;
    private final SystemDateService systemDateService;

    /**
     * Generate daily settlement gain/loss summary report
     *
     * @param reportDate Date for the report (null = today)
     * @return Daily settlement summary
     */
    @Transactional(readOnly = true)
    public DailySettlementReport generateDailyReport(LocalDate reportDate) {
        LocalDate effectiveDate = reportDate != null ? reportDate : systemDateService.getSystemDate();
        log.info("Generating daily settlement report for date: {}", effectiveDate);

        List<SettlementGainLoss> settlements = settlementGainLossRepository
            .findByTranDateAndStatus(effectiveDate, "POSTED");

        return buildDailyReport(effectiveDate, settlements);
    }

    /**
     * Generate settlement report for a date range
     *
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return Period settlement summary
     */
    @Transactional(readOnly = true)
    public PeriodSettlementReport generatePeriodReport(LocalDate startDate, LocalDate endDate) {
        log.info("Generating settlement report for period: {} to {}", startDate, endDate);

        List<SettlementGainLoss> settlements = settlementGainLossRepository
            .findByTranDateBetweenAndStatus(startDate, endDate, "POSTED");

        return buildPeriodReport(startDate, endDate, settlements);
    }

    /**
     * Generate settlement report by currency
     *
     * @param currency Currency code (e.g., "USD")
     * @param startDate Start date
     * @param endDate End date
     * @return Currency-specific settlement summary
     */
    @Transactional(readOnly = true)
    public CurrencySettlementReport generateCurrencyReport(String currency,
                                                          LocalDate startDate,
                                                          LocalDate endDate) {
        log.info("Generating settlement report for currency: {} from {} to {}",
            currency, startDate, endDate);

        List<SettlementGainLoss> settlements = settlementGainLossRepository
            .findByCurrencyAndTranDateBetweenAndStatus(currency, startDate, endDate, "POSTED");

        return buildCurrencyReport(currency, startDate, endDate, settlements);
    }

    /**
     * Generate settlement report by account
     *
     * @param accountNo Account number
     * @param startDate Start date
     * @param endDate End date
     * @return Account-specific settlement summary
     */
    @Transactional(readOnly = true)
    public AccountSettlementReport generateAccountReport(String accountNo,
                                                         LocalDate startDate,
                                                         LocalDate endDate) {
        log.info("Generating settlement report for account: {} from {} to {}",
            accountNo, startDate, endDate);

        List<SettlementGainLoss> settlements = settlementGainLossRepository
            .findByAccountNoAndTranDateBetweenAndStatus(accountNo, startDate, endDate, "POSTED");

        return buildAccountReport(accountNo, startDate, endDate, settlements);
    }

    /**
     * Get top gainers and losers for a period
     *
     * @param startDate Start date
     * @param endDate End date
     * @param topN Number of top entries to return
     * @return Top gainers and losers
     */
    @Transactional(readOnly = true)
    public TopSettlementsReport getTopSettlements(LocalDate startDate, LocalDate endDate, int topN) {
        log.info("Getting top {} settlements from {} to {}", topN, startDate, endDate);

        List<SettlementGainLoss> allSettlements = settlementGainLossRepository
            .findByTranDateBetweenAndStatus(startDate, endDate, "POSTED");

        // Separate gains and losses
        List<SettlementGainLoss> gains = allSettlements.stream()
            .filter(s -> "GAIN".equals(s.getSettlementType()))
            .sorted((a, b) -> b.getSettlementAmt().compareTo(a.getSettlementAmt()))
            .limit(topN)
            .collect(Collectors.toList());

        List<SettlementGainLoss> losses = allSettlements.stream()
            .filter(s -> "LOSS".equals(s.getSettlementType()))
            .sorted((a, b) -> b.getSettlementAmt().compareTo(a.getSettlementAmt()))
            .limit(topN)
            .collect(Collectors.toList());

        return TopSettlementsReport.builder()
            .startDate(startDate)
            .endDate(endDate)
            .topGains(gains)
            .topLosses(losses)
            .build();
    }

    /**
     * Build daily settlement report
     */
    private DailySettlementReport buildDailyReport(LocalDate date, List<SettlementGainLoss> settlements) {
        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        int gainCount = 0;
        int lossCount = 0;

        Map<String, BigDecimal> currencyBreakdown = new HashMap<>();
        Map<String, Integer> currencyCount = new HashMap<>();

        for (SettlementGainLoss settlement : settlements) {
            if ("GAIN".equals(settlement.getSettlementType())) {
                totalGain = totalGain.add(settlement.getSettlementAmt());
                gainCount++;
            } else {
                totalLoss = totalLoss.add(settlement.getSettlementAmt());
                lossCount++;
            }

            // Currency breakdown
            String currency = settlement.getCurrency();
            BigDecimal currentAmount = currencyBreakdown.getOrDefault(currency, BigDecimal.ZERO);
            if ("GAIN".equals(settlement.getSettlementType())) {
                currencyBreakdown.put(currency, currentAmount.add(settlement.getSettlementAmt()));
            } else {
                currencyBreakdown.put(currency, currentAmount.subtract(settlement.getSettlementAmt()));
            }
            currencyCount.put(currency, currencyCount.getOrDefault(currency, 0) + 1);
        }

        BigDecimal netAmount = totalGain.subtract(totalLoss);

        return DailySettlementReport.builder()
            .reportDate(date)
            .totalGain(totalGain)
            .totalLoss(totalLoss)
            .netAmount(netAmount)
            .gainCount(gainCount)
            .lossCount(lossCount)
            .totalTransactions(settlements.size())
            .currencyBreakdown(currencyBreakdown)
            .currencyCount(currencyCount)
            .settlements(settlements)
            .build();
    }

    /**
     * Build period settlement report
     */
    private PeriodSettlementReport buildPeriodReport(LocalDate startDate, LocalDate endDate,
                                                    List<SettlementGainLoss> settlements) {
        // Group by date
        Map<LocalDate, List<SettlementGainLoss>> dailySettlements = settlements.stream()
            .collect(Collectors.groupingBy(SettlementGainLoss::getTranDate));

        // Build daily summaries
        Map<LocalDate, DailySettlementReport> dailyReports = new TreeMap<>();
        for (Map.Entry<LocalDate, List<SettlementGainLoss>> entry : dailySettlements.entrySet()) {
            dailyReports.put(entry.getKey(), buildDailyReport(entry.getKey(), entry.getValue()));
        }

        // Calculate period totals
        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        int totalGainCount = 0;
        int totalLossCount = 0;

        for (SettlementGainLoss settlement : settlements) {
            if ("GAIN".equals(settlement.getSettlementType())) {
                totalGain = totalGain.add(settlement.getSettlementAmt());
                totalGainCount++;
            } else {
                totalLoss = totalLoss.add(settlement.getSettlementAmt());
                totalLossCount++;
            }
        }

        return PeriodSettlementReport.builder()
            .startDate(startDate)
            .endDate(endDate)
            .totalGain(totalGain)
            .totalLoss(totalLoss)
            .netAmount(totalGain.subtract(totalLoss))
            .gainCount(totalGainCount)
            .lossCount(totalLossCount)
            .totalTransactions(settlements.size())
            .dailyReports(dailyReports)
            .build();
    }

    /**
     * Build currency-specific settlement report
     */
    private CurrencySettlementReport buildCurrencyReport(String currency, LocalDate startDate,
                                                        LocalDate endDate,
                                                        List<SettlementGainLoss> settlements) {
        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        BigDecimal totalFcyGain = BigDecimal.ZERO;
        BigDecimal totalFcyLoss = BigDecimal.ZERO;

        for (SettlementGainLoss settlement : settlements) {
            if ("GAIN".equals(settlement.getSettlementType())) {
                totalGain = totalGain.add(settlement.getSettlementAmt());
                totalFcyGain = totalFcyGain.add(settlement.getFcyAmt());
            } else {
                totalLoss = totalLoss.add(settlement.getSettlementAmt());
                totalFcyLoss = totalFcyLoss.add(settlement.getFcyAmt());
            }
        }

        return CurrencySettlementReport.builder()
            .currency(currency)
            .startDate(startDate)
            .endDate(endDate)
            .totalGain(totalGain)
            .totalLoss(totalLoss)
            .netAmount(totalGain.subtract(totalLoss))
            .totalFcyGain(totalFcyGain)
            .totalFcyLoss(totalFcyLoss)
            .transactionCount(settlements.size())
            .settlements(settlements)
            .build();
    }

    /**
     * Build account-specific settlement report
     */
    private AccountSettlementReport buildAccountReport(String accountNo, LocalDate startDate,
                                                      LocalDate endDate,
                                                      List<SettlementGainLoss> settlements) {
        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        Map<String, BigDecimal> currencyBreakdown = new HashMap<>();

        for (SettlementGainLoss settlement : settlements) {
            if ("GAIN".equals(settlement.getSettlementType())) {
                totalGain = totalGain.add(settlement.getSettlementAmt());
            } else {
                totalLoss = totalLoss.add(settlement.getSettlementAmt());
            }

            // Currency breakdown
            String currency = settlement.getCurrency();
            BigDecimal currentAmount = currencyBreakdown.getOrDefault(currency, BigDecimal.ZERO);
            if ("GAIN".equals(settlement.getSettlementType())) {
                currencyBreakdown.put(currency, currentAmount.add(settlement.getSettlementAmt()));
            } else {
                currencyBreakdown.put(currency, currentAmount.subtract(settlement.getSettlementAmt()));
            }
        }

        return AccountSettlementReport.builder()
            .accountNo(accountNo)
            .startDate(startDate)
            .endDate(endDate)
            .totalGain(totalGain)
            .totalLoss(totalLoss)
            .netAmount(totalGain.subtract(totalLoss))
            .transactionCount(settlements.size())
            .currencyBreakdown(currencyBreakdown)
            .settlements(settlements)
            .build();
    }

    // ========================================
    // DTOs
    // ========================================

    @Data
    @Builder
    public static class DailySettlementReport {
        private LocalDate reportDate;
        private BigDecimal totalGain;
        private BigDecimal totalLoss;
        private BigDecimal netAmount;
        private int gainCount;
        private int lossCount;
        private int totalTransactions;
        private Map<String, BigDecimal> currencyBreakdown;
        private Map<String, Integer> currencyCount;
        private List<SettlementGainLoss> settlements;
    }

    @Data
    @Builder
    public static class PeriodSettlementReport {
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal totalGain;
        private BigDecimal totalLoss;
        private BigDecimal netAmount;
        private int gainCount;
        private int lossCount;
        private int totalTransactions;
        private Map<LocalDate, DailySettlementReport> dailyReports;
    }

    @Data
    @Builder
    public static class CurrencySettlementReport {
        private String currency;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal totalGain;
        private BigDecimal totalLoss;
        private BigDecimal netAmount;
        private BigDecimal totalFcyGain;
        private BigDecimal totalFcyLoss;
        private int transactionCount;
        private List<SettlementGainLoss> settlements;
    }

    @Data
    @Builder
    public static class AccountSettlementReport {
        private String accountNo;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal totalGain;
        private BigDecimal totalLoss;
        private BigDecimal netAmount;
        private int transactionCount;
        private Map<String, BigDecimal> currencyBreakdown;
        private List<SettlementGainLoss> settlements;
    }

    @Data
    @Builder
    public static class TopSettlementsReport {
        private LocalDate startDate;
        private LocalDate endDate;
        private List<SettlementGainLoss> topGains;
        private List<SettlementGainLoss> topLosses;
    }
}
