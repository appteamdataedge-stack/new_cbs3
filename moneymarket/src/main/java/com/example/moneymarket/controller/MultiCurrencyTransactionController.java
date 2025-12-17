package com.example.moneymarket.controller;

import com.example.moneymarket.dto.MultiCurrencyTransactionRequest;
import com.example.moneymarket.service.MultiCurrencyTransactionService;
import com.example.moneymarket.service.RevaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for Multi-Currency Transaction operations
 * Provides endpoints for testing and triggering MCT functionality
 */
@RestController
@RequestMapping("/api/mct")
@RequiredArgsConstructor
@Slf4j
public class MultiCurrencyTransactionController {

    private final MultiCurrencyTransactionService mctService;
    private final RevaluationService revaluationService;

    /**
     * Trigger EOD Revaluation manually
     * GET /api/mct/revaluation/eod
     */
    @PostMapping("/revaluation/eod")
    public ResponseEntity<Map<String, Object>> triggerEodRevaluation() {
        log.info("Manual EOD Revaluation triggered via API");

        try {
            RevaluationService.RevaluationResult result = revaluationService.performEodRevaluation();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "EOD Revaluation completed successfully");
            response.put("revalDate", result.getRevalDate());
            response.put("entriesPosted", result.getEntriesPosted());
            response.put("totalGain", result.getTotalGain());
            response.put("totalLoss", result.getTotalLoss());
            response.put("entries", result.getEntries());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during EOD Revaluation: {}", e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "EOD Revaluation failed: " + e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Trigger BOD Revaluation Reversal manually
     * POST /api/mct/revaluation/bod
     */
    @PostMapping("/revaluation/bod")
    public ResponseEntity<Map<String, Object>> triggerBodRevaluationReversal() {
        log.info("Manual BOD Revaluation Reversal triggered via API");

        try {
            revaluationService.performBodRevaluationReversal();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "BOD Revaluation Reversal completed successfully");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during BOD Revaluation Reversal: {}", e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "BOD Revaluation Reversal failed: " + e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get revaluation summary for a specific date
     * GET /api/mct/revaluation/summary?date=2025-11-23
     */
    @GetMapping("/revaluation/summary")
    public ResponseEntity<Map<String, Object>> getRevaluationSummary(
            @RequestParam(required = false) LocalDate date) {

        LocalDate revalDate = (date != null) ? date : LocalDate.now();
        log.info("Getting revaluation summary for date: {}", revalDate);

        try {
            RevaluationService.RevaluationResult result = revaluationService.getRevaluationSummary(revalDate);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("revalDate", result.getRevalDate());
            response.put("entriesPosted", result.getEntriesPosted());
            response.put("totalGain", result.getTotalGain());
            response.put("totalLoss", result.getTotalLoss());
            response.put("entries", result.getEntries());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting revaluation summary: {}", e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to get revaluation summary: " + e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get current WAE rate for a currency
     * GET /api/mct/wae/USD
     */
    @GetMapping("/wae/{currency}")
    public ResponseEntity<Map<String, Object>> getWAERate(@PathVariable String currency) {
        log.info("Getting WAE rate for currency: {}", currency);

        try {
            java.math.BigDecimal waeRate = mctService.getWAERate(currency);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("currency", currency);
            response.put("waeRate", waeRate);
            response.put("ccyPair", currency + "/BDT");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting WAE rate: {}", e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to get WAE rate: " + e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get Position GL for a currency
     * GET /api/mct/position-gl/USD
     */
    @GetMapping("/position-gl/{currency}")
    public ResponseEntity<Map<String, Object>> getPositionGL(@PathVariable String currency) {
        log.info("Getting Position GL for currency: {}", currency);

        try {
            String positionGL = mctService.getPositionGL(currency)
                .orElse("Not configured");

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("currency", currency);
            response.put("positionGL", positionGL);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting Position GL: {}", e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to get Position GL: " + e.getMessage());

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Health check endpoint for MCT services
     * GET /api/mct/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Multi-Currency Transaction Service");
        response.put("features", new String[]{
            "Position GL Auto-Posting",
            "WAE Calculation",
            "Settlement Gain/Loss",
            "EOD Revaluation",
            "BOD Revaluation Reversal"
        });

        return ResponseEntity.ok(response);
    }
}
