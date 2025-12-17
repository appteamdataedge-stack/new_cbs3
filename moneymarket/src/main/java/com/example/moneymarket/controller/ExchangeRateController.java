package com.example.moneymarket.controller;

import com.example.moneymarket.dto.CreateExchangeRateRequest;
import com.example.moneymarket.dto.ExchangeRateResponse;
import com.example.moneymarket.dto.UpdateExchangeRateRequest;
import com.example.moneymarket.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for Exchange Rate Management
 * Based on PTTP05 specification for Multi-Currency Transactions
 */
@RestController
@RequestMapping("/api/exchange-rates")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    /**
     * Create a new exchange rate
     * POST /api/exchange-rates
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createExchangeRate(
            @RequestBody CreateExchangeRateRequest request) {
        try {
            log.info("POST /api/exchange-rates - Creating exchange rate for {} on {}",
                    request.getCcyPair(), request.getRateDate());

            ExchangeRateResponse response = exchangeRateService.createExchangeRate(request);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Exchange rate created successfully");
            result.put("data", response);

            return ResponseEntity.status(HttpStatus.CREATED).body(result);

        } catch (IllegalArgumentException e) {
            log.error("Validation error creating exchange rate: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("Error creating exchange rate", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to create exchange rate: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get all exchange rates with optional filters
     * GET /api/exchange-rates?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&ccyPair=USD/BDT
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllExchangeRates(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String ccyPair) {
        try {
            log.info("GET /api/exchange-rates - startDate: {}, endDate: {}, ccyPair: {}",
                    startDate, endDate, ccyPair);

            List<ExchangeRateResponse> rates = exchangeRateService.getAllExchangeRates(
                    startDate, endDate, ccyPair);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("count", rates.size());
            result.put("data", rates);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error fetching exchange rates", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to fetch exchange rates: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get exchange rate by ID
     * GET /api/exchange-rates/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getExchangeRateById(@PathVariable Long id) {
        try {
            log.info("GET /api/exchange-rates/{}", id);

            ExchangeRateResponse rate = exchangeRateService.getExchangeRateById(id);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", rate);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.error("Exchange rate not found: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

        } catch (Exception e) {
            log.error("Error fetching exchange rate", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to fetch exchange rate: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get latest exchange rate for a currency pair
     * GET /api/exchange-rates/latest/{ccyPair}
     */
    @GetMapping("/latest/{ccyPair}")
    public ResponseEntity<Map<String, Object>> getLatestExchangeRate(@PathVariable String ccyPair) {
        try {
            log.info("GET /api/exchange-rates/latest/{}", ccyPair);

            ExchangeRateResponse rate = exchangeRateService.getLatestExchangeRate(ccyPair);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", rate);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.error("Latest exchange rate not found: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

        } catch (Exception e) {
            log.error("Error fetching latest exchange rate", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to fetch latest exchange rate: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get exchange rate for specific date and currency pair
     * GET /api/exchange-rates/rate?date=YYYY-MM-DD&ccyPair=USD/BDT
     */
    @GetMapping("/rate")
    public ResponseEntity<Map<String, Object>> getExchangeRateByDateAndPair(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String ccyPair) {
        try {
            log.info("GET /api/exchange-rates/rate - date: {}, ccyPair: {}", date, ccyPair);

            ExchangeRateResponse rate = exchangeRateService.getExchangeRateByDateAndPair(date, ccyPair);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", rate);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.error("Exchange rate not found: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

        } catch (Exception e) {
            log.error("Error fetching exchange rate", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to fetch exchange rate: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Update an existing exchange rate
     * PUT /api/exchange-rates/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateExchangeRate(
            @PathVariable Long id,
            @RequestBody UpdateExchangeRateRequest request) {
        try {
            log.info("PUT /api/exchange-rates/{} - Updating exchange rate", id);

            ExchangeRateResponse response = exchangeRateService.updateExchangeRate(id, request);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Exchange rate updated successfully");
            result.put("data", response);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.error("Validation error updating exchange rate: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("Error updating exchange rate", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to update exchange rate: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Delete an exchange rate
     * DELETE /api/exchange-rates/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteExchangeRate(@PathVariable Long id) {
        try {
            log.info("DELETE /api/exchange-rates/{} - Deleting exchange rate", id);

            exchangeRateService.deleteExchangeRate(id);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Exchange rate deleted successfully");

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            log.error("Exchange rate not found: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

        } catch (IllegalStateException e) {
            log.error("Cannot delete exchange rate: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);

        } catch (Exception e) {
            log.error("Error deleting exchange rate", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to delete exchange rate: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get distinct currency pairs for filter dropdown
     * GET /api/exchange-rates/currency-pairs
     */
    @GetMapping("/currency-pairs")
    public ResponseEntity<Map<String, Object>> getDistinctCurrencyPairs() {
        try {
            log.info("GET /api/exchange-rates/currency-pairs");

            List<String> currencyPairs = exchangeRateService.getDistinctCurrencyPairs();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", currencyPairs);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error fetching currency pairs", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to fetch currency pairs: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
