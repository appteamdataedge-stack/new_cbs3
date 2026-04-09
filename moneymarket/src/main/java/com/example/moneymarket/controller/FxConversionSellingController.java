package com.example.moneymarket.controller;

import com.example.moneymarket.dto.FxConversionRequest;
import com.example.moneymarket.dto.TransactionResponseDTO;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.service.FxConversionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            response.put("message", "FX SELLING transaction created successfully (Entry status - pending approval)");
            return ResponseEntity.ok(response);
        } catch (BusinessException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        } catch (Exception e) {
            log.error("ERROR in createSelling: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Failed to process FX SELLING: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}

