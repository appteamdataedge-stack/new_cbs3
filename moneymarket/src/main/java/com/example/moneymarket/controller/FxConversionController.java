package com.example.moneymarket.controller;

import com.example.moneymarket.dto.TransactionResponseDTO;
import com.example.moneymarket.entity.AcctBal;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.OFAcctMaster;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.OFAcctMasterRepository;
import com.example.moneymarket.service.FxConversionService;
import com.example.moneymarket.service.SystemDateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/fx")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
@Slf4j
public class FxConversionController {

    private final FxConversionService fxConversionService;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final AcctBalRepository acctBalRepository;
    private final SystemDateService systemDateService;

    /**
     * Get Mid Rate for a currency
     */
    @GetMapping("/rates/{currencyCode}")
    public ResponseEntity<?> getMidRate(@PathVariable String currencyCode) {
        log.info("===========================================");
        log.info("GET /api/fx/rates/{}", currencyCode);
        log.info("===========================================");

        try {
            LocalDate tranDate = systemDateService.getSystemDate();
            BigDecimal midRate = fxConversionService.fetchMidRate(currencyCode, tranDate);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                    "currencyCode", currencyCode,
                    "midRate", midRate,
                    "rateDate", tranDate
            ));

            log.info("SUCCESS: Returned mid rate: {}", midRate);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ERROR in getMidRate for {}: {}", currencyCode, e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch mid rate: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    /**
     * Get WAE Rate for a currency, calculated from tran_table (real-time).
     * Optional ?nostroAccount= param: if provided, calculate WAE for that specific Nostro.
     * If omitted, calculate aggregate WAE across all Nostro accounts.
     * Returns hasWae=false with waeRate=null when Nostro FCY balance is zero (Observation #2).
     */
    @GetMapping("/wae/{currencyCode}")
    public ResponseEntity<?> getWaeRate(@PathVariable String currencyCode,
                                         @RequestParam(required = false) String nostroAccount) {
        log.info("===========================================");
        log.info("GET /api/fx/wae/{} nostroAccount={}", currencyCode, nostroAccount);
        log.info("===========================================");

        try {
            LocalDate tranDate = systemDateService.getSystemDate();
            BigDecimal waeRate;

            if (nostroAccount != null && !nostroAccount.isBlank()) {
                String positionGlNum = fxConversionService.getPositionFcyGlNumForCurrency(currencyCode);
                if (positionGlNum.equals(nostroAccount)) {
                    waeRate = fxConversionService.calculatePositionWae2OnTheFly(currencyCode);
                } else {
                    waeRate = fxConversionService.calculateWaeFromTranTable(nostroAccount, currencyCode);
                }
            } else {
                waeRate = fxConversionService.calculateAggregateWaeFromTranTable(currencyCode);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("currencyCode", currencyCode);
            data.put("calculationDate", tranDate);
            data.put("hasWae", waeRate != null);
            data.put("waeRate", waeRate);
            if (nostroAccount != null && !nostroAccount.isBlank()) {
                data.put("nostroAccount", nostroAccount);
                data.put("positionGlNum", fxConversionService.getPositionFcyGlNumForCurrency(currencyCode));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);

            log.info("SUCCESS: WAE={} hasWae={}", waeRate, waeRate != null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ERROR in getWaeRate for {}: {}", currencyCode, e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to calculate WAE rate: " + e.getMessage());

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    /**
     * Real-time position WAE2 for SELLING (same logic as {@code /api/fx-conversion/position-wae}).
     */
    @GetMapping("/position-wae")
    public ResponseEntity<?> getPositionWae(@RequestParam String currency) {
        log.info("GET /api/fx/position-wae currency={}", currency);
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

    /**
     * Search Customer Accounts (BDT accounts only)
     */
    @GetMapping("/accounts/customer")
    public ResponseEntity<?> searchCustomerAccounts(@RequestParam(required = false, defaultValue = "") String search) {
        log.info("===========================================");
        log.info("GET /api/fx/accounts/customer?search={}", search);
        log.info("===========================================");

        try {
            LocalDate tranDate = systemDateService.getSystemDate();
            
            List<CustAcctMaster> accounts = fxConversionService.searchCustomerAccounts(search);
            log.info("Found {} customer accounts matching '{}'", accounts.size(), search);

            List<Map<String, Object>> accountList = accounts.stream()
                    .map(acc -> {
                        Map<String, Object> accountData = new HashMap<>();
                        accountData.put("accountId", acc.getAccountNo());  // Use accountNo as ID
                        accountData.put("accountNo", acc.getAccountNo());
                        accountData.put("accountTitle", acc.getAcctName());  // CRITICAL: Backend returns accountTitle
                        accountData.put("accountType", acc.getSubProduct() != null ? 
                                acc.getSubProduct().getSubProductCode() : "");
                        accountData.put("currencyCode", acc.getAccountCcy());

                        // Get balance from acc_bal (closing balance for latest available date)
                        BigDecimal balance = acctBalRepository.findLatestByAccountNo(acc.getAccountNo())
                                .map(AcctBal::getClosingBal)
                                .orElse(BigDecimal.ZERO);
                        accountData.put("balance", balance);

                        log.debug("Account: {} - {} ({}) Bal: {}", 
                                acc.getAccountNo(), acc.getAcctName(), 
                                acc.getSubProduct() != null ? acc.getSubProduct().getSubProductCode() : "?",
                                balance);

                        return accountData;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", accountList);

            log.info("SUCCESS: Returning {} customer accounts", accountList.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ERROR in searchCustomerAccounts: {}", e.getMessage(), e);
            
            // Return empty list instead of error to prevent frontend breakage
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", List.of());
            
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Get NOSTRO Accounts for a currency
     */
    @GetMapping("/accounts/nostro")
    public ResponseEntity<?> getNostroAccounts(@RequestParam String currency) {
        log.info("===========================================");
        log.info("GET /api/fx/accounts/nostro?currency={}", currency);
        log.info("===========================================");

        try {
            LocalDate tranDate = systemDateService.getSystemDate();
            
            List<OFAcctMaster> nostros = fxConversionService.getNostroAccounts(currency);
            log.info("Found {} NOSTRO accounts for {}", nostros.size(), currency);

            List<Map<String, Object>> nostroList = nostros.stream()
                    .map(nostro -> {
                        Map<String, Object> nostroData = new HashMap<>();
                        nostroData.put("accountId", nostro.getAccountNo());
                        nostroData.put("accountNo", nostro.getAccountNo());
                        nostroData.put("accountTitle", nostro.getAcctName());
                        nostroData.put("currencyCode", nostro.getAccountCcy());

                        // Get balance from acc_bal (closing balance for latest available date)
                        BigDecimal balance = acctBalRepository.findLatestByAccountNo(nostro.getAccountNo())
                                .map(AcctBal::getClosingBal)
                                .orElse(BigDecimal.ZERO);
                        nostroData.put("balance", balance);

                        return nostroData;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", nostroList);

            log.info("SUCCESS: Returning {} NOSTRO accounts", nostroList.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ERROR in getNostroAccounts: {}", e.getMessage(), e);
            
            // Return empty list instead of error to prevent frontend breakage
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", List.of());
            
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Process FX Conversion transaction
     */
    @PostMapping("/conversion")
    public ResponseEntity<?> processConversion(@RequestBody FxConversionRequest request) {
        log.info("===========================================");
        log.info("POST /api/fx/conversion");
        log.info("Request: {}", request);
        log.info("===========================================");

        try {
            TransactionResponseDTO transaction = fxConversionService.createFxConversion(
                    request.getTransactionType(),
                    request.getCustomerAccountId(),
                    request.getNostroAccountId(),
                    request.getCurrencyCode(),
                    request.getFcyAmount(),
                    request.getDealRate(),
                    request.getParticulars(),
                    request.getUserId()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", transaction);
            response.put("message", "FX Conversion transaction created successfully (Entry status - pending approval)");

            log.info("SUCCESS: Transaction created: {}", transaction.getTranId());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ERROR in processConversion: {}", e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to process FX conversion: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    @lombok.Data
    public static class FxConversionRequest {
        private String transactionType;
        private String customerAccountId;
        private String nostroAccountId;
        private String currencyCode;
        private BigDecimal fcyAmount;
        private BigDecimal dealRate;
        private String particulars;
        private String userId;
    }
}
