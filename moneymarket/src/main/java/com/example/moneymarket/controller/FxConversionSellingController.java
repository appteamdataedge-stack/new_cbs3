package com.example.moneymarket.controller;

import com.example.moneymarket.dto.FxConversionRequest;
import com.example.moneymarket.dto.TransactionResponseDTO;
import com.example.moneymarket.service.FxConversionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/fx-conversion")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class FxConversionSellingController {

    private final FxConversionService fxConversionService;

    @PostMapping("/selling")
    public ResponseEntity<?> createSelling(@RequestBody FxConversionRequest request) {
        log.info("POST /api/fx-conversion/selling request={}", request);
        try {
            TransactionResponseDTO transaction = fxConversionService.processSelling(request);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", transaction);
            response.put("message", "FX SELLING transaction created (Entry status - pending approval)");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("ERROR in createSelling: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to process FX SELLING: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    /**
     * Real-time position WAE2 for the FX conversion screen (opening + intraday tran_table, signed ratio; response rate is positive).
     */
    @GetMapping("/position-wae")
    public ResponseEntity<?> getPositionWae(@RequestParam String currency) {
        log.info("GET /api/fx-conversion/position-wae currency={}", currency);
        try {
            BigDecimal raw = fxConversionService.calculatePositionWae2OnTheFly(currency);
            BigDecimal wae2 = raw != null ? raw.abs() : null;
            Map<String, Object> data = new HashMap<>();
            data.put("currency", currency);
            data.put("wae2", wae2);
            data.put("wae2Raw", raw);
            data.put("hasWae", wae2 != null);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("ERROR in getPositionWae: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch position WAE: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }
}
