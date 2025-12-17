package com.example.moneymarket.controller;

import com.example.moneymarket.service.SettlementReportService;
import com.example.moneymarket.service.SettlementReportService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * REST API Controller for Settlement Gain/Loss Reports
 * Provides endpoints for settlement reporting and analytics
 */
@RestController
@RequestMapping("/api/settlement-reports")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {
    "http://localhost:3000",
    "http://localhost:4173",
    "http://localhost:5173",
    "http://localhost:5174",
    "http://localhost:5175",
    "http://localhost:5176",
    "http://localhost:5177",
    "http://localhost:5178",
    "https://cbs3.vercel.app",
    "https://moneymarket.duckdns.org"
})
public class SettlementReportController {

    private final SettlementReportService settlementReportService;

    /**
     * Get daily settlement report
     *
     * GET /api/settlement-reports/daily?date=2025-11-27
     *
     * @param date Report date (optional, defaults to today)
     * @return Daily settlement summary
     */
    @GetMapping("/daily")
    public ResponseEntity<DailySettlementReport> getDailyReport(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {

        log.info("API Request: Get daily settlement report for date: {}", date);

        DailySettlementReport report = settlementReportService.generateDailyReport(date);

        return ResponseEntity.ok(report);
    }

    /**
     * Get settlement report for a date range
     *
     * GET /api/settlement-reports/period?startDate=2025-11-01&endDate=2025-11-27
     *
     * @param startDate Start date (required)
     * @param endDate End date (required)
     * @return Period settlement summary
     */
    @GetMapping("/period")
    public ResponseEntity<PeriodSettlementReport> getPeriodReport(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate) {

        log.info("API Request: Get period settlement report from {} to {}", startDate, endDate);

        if (startDate.isAfter(endDate)) {
            log.error("Invalid date range: startDate {} is after endDate {}", startDate, endDate);
            return ResponseEntity.badRequest().build();
        }

        PeriodSettlementReport report = settlementReportService.generatePeriodReport(startDate, endDate);

        return ResponseEntity.ok(report);
    }

    /**
     * Get settlement report by currency
     *
     * GET /api/settlement-reports/currency/USD?startDate=2025-11-01&endDate=2025-11-27
     *
     * @param currency Currency code (e.g., USD, EUR)
     * @param startDate Start date (required)
     * @param endDate End date (required)
     * @return Currency-specific settlement summary
     */
    @GetMapping("/currency/{currency}")
    public ResponseEntity<CurrencySettlementReport> getCurrencyReport(
            @PathVariable String currency,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate) {

        log.info("API Request: Get currency settlement report for {} from {} to {}",
            currency, startDate, endDate);

        if (startDate.isAfter(endDate)) {
            log.error("Invalid date range: startDate {} is after endDate {}", startDate, endDate);
            return ResponseEntity.badRequest().build();
        }

        CurrencySettlementReport report = settlementReportService.generateCurrencyReport(
            currency.toUpperCase(),
            startDate,
            endDate
        );

        return ResponseEntity.ok(report);
    }

    /**
     * Get settlement report by account
     *
     * GET /api/settlement-reports/account/1000000099001?startDate=2025-11-01&endDate=2025-11-27
     *
     * @param accountNo Account number
     * @param startDate Start date (required)
     * @param endDate End date (required)
     * @return Account-specific settlement summary
     */
    @GetMapping("/account/{accountNo}")
    public ResponseEntity<AccountSettlementReport> getAccountReport(
            @PathVariable String accountNo,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate) {

        log.info("API Request: Get account settlement report for {} from {} to {}",
            accountNo, startDate, endDate);

        if (startDate.isAfter(endDate)) {
            log.error("Invalid date range: startDate {} is after endDate {}", startDate, endDate);
            return ResponseEntity.badRequest().build();
        }

        AccountSettlementReport report = settlementReportService.generateAccountReport(
            accountNo,
            startDate,
            endDate
        );

        return ResponseEntity.ok(report);
    }

    /**
     * Get top gainers and losers
     *
     * GET /api/settlement-reports/top?startDate=2025-11-01&endDate=2025-11-27&topN=10
     *
     * @param startDate Start date (required)
     * @param endDate End date (required)
     * @param topN Number of top entries (optional, default 10)
     * @return Top gainers and losers
     */
    @GetMapping("/top")
    public ResponseEntity<TopSettlementsReport> getTopSettlements(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,

            @RequestParam(defaultValue = "10")
            int topN) {

        log.info("API Request: Get top {} settlements from {} to {}", topN, startDate, endDate);

        if (startDate.isAfter(endDate)) {
            log.error("Invalid date range: startDate {} is after endDate {}", startDate, endDate);
            return ResponseEntity.badRequest().build();
        }

        if (topN < 1 || topN > 100) {
            log.error("Invalid topN value: {}. Must be between 1 and 100", topN);
            return ResponseEntity.badRequest().build();
        }

        TopSettlementsReport report = settlementReportService.getTopSettlements(startDate, endDate, topN);

        return ResponseEntity.ok(report);
    }

    /**
     * Get monthly settlement summary
     *
     * GET /api/settlement-reports/monthly?year=2025&month=11
     *
     * @param year Year (required)
     * @param month Month (1-12, required)
     * @return Monthly settlement summary
     */
    @GetMapping("/monthly")
    public ResponseEntity<PeriodSettlementReport> getMonthlyReport(
            @RequestParam int year,
            @RequestParam int month) {

        log.info("API Request: Get monthly settlement report for {}-{}", year, month);

        if (month < 1 || month > 12) {
            log.error("Invalid month: {}. Must be between 1 and 12", month);
            return ResponseEntity.badRequest().build();
        }

        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);

        PeriodSettlementReport report = settlementReportService.generatePeriodReport(startDate, endDate);

        return ResponseEntity.ok(report);
    }

    /**
     * Health check endpoint
     *
     * GET /api/settlement-reports/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Settlement Report API is running");
    }
}
