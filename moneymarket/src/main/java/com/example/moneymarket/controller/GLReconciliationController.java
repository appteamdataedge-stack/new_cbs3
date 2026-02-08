package com.example.moneymarket.controller;

import com.example.moneymarket.service.GLReconciliationService;
import com.example.moneymarket.service.GLReconciliationService.DrillDownDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for GL Reconciliation Report operations
 * ✅ ISSUE 4: New controller for GL reconciliation report
 */
@RestController
@RequestMapping("/api/gl-reconciliation")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class GLReconciliationController {

    private final GLReconciliationService glReconciliationService;

    /**
     * Generate GL Reconciliation Report
     *
     * @param reportDate Date for the report (optional, defaults to system date)
     * @return Excel file as downloadable attachment
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateReconciliationReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate) {

        try {
            log.info("Generating GL Reconciliation Report for date: {}", reportDate);

            // Generate report
            byte[] fileBytes = glReconciliationService.generateReconciliationReport(reportDate);

            // Create filename
            String dateStr = reportDate != null ? reportDate.toString() : LocalDate.now().toString();
            String filename = String.format("GL_Reconciliation_%s.xlsx", dateStr);

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(fileBytes.length);

            log.info("GL Reconciliation Report generated successfully, file size: {} bytes", fileBytes.length);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(fileBytes);

        } catch (Exception e) {
            log.error("Error generating GL Reconciliation Report: {}", e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Internal Server Error");
            errorResponse.put("message", "An error occurred while generating the reconciliation report: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get drill-down details for a specific subproduct
     *
     * @param subProductCode Sub-product code
     * @param reportDate Date for the report (optional)
     * @return Drill-down details including accounts and GL postings
     */
    @GetMapping("/drill-down/{subProductCode}")
    public ResponseEntity<?> getDrillDownDetails(
            @PathVariable String subProductCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate) {

        try {
            log.info("Getting drill-down details for subproduct {} on date {}", subProductCode, reportDate);

            DrillDownDetails details = glReconciliationService.getDrillDownDetails(subProductCode, reportDate);

            return ResponseEntity.ok(details);

        } catch (Exception e) {
            log.error("Error getting drill-down details for subproduct {}: {}", subProductCode, e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Internal Server Error");
            errorResponse.put("message", "An error occurred while retrieving drill-down details: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "GL Reconciliation");
        return ResponseEntity.ok(response);
    }
}
