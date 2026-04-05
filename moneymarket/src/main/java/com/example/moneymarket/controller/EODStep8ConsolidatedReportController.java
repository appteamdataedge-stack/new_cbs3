package com.example.moneymarket.controller;

import com.example.moneymarket.service.EODStep8ConsolidatedReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for EOD Step 8: Consolidated Financial Reports
 * 
 * Generates comprehensive workbook containing:
 * 1. Trial Balance Report
 * 2. Balance Sheet Report
 * 3. Subproduct GL Balance Report (with hyperlinks)
 * 4. Account Balance Report (one sheet per subproduct)
 */
@RestController
@RequestMapping("/api/eod-step8")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class EODStep8ConsolidatedReportController {

    private final EODStep8ConsolidatedReportService reportService;

    /**
     * Generate EOD Step 8 Consolidated Report
     *
     * @param eodDate Date for the report (optional, defaults to system date)
     * @return Excel workbook with multiple sheets as downloadable attachment
     */
    @PostMapping("/generate-consolidated-report")
    public ResponseEntity<?> generateConsolidatedReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eodDate) {

        try {
            log.info("API Request: Generate EOD Step 8 Consolidated Report for date: {}", eodDate);

            byte[] fileBytes = reportService.generateConsolidatedReport(eodDate);

            String dateStr = eodDate != null ? eodDate.toString() : LocalDate.now().toString();
            String filename = String.format("EOD_Step8_Consolidated_Report_%s.xlsx", dateStr);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(fileBytes.length);

            log.info("EOD Step 8 Consolidated Report generated successfully, file size: {} bytes", fileBytes.length);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(fileBytes);

        } catch (Exception e) {
            log.error("Error generating EOD Step 8 Consolidated Report: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "ERROR");
            errorResponse.put("error", "Internal Server Error");
            errorResponse.put("errorType", "STEP8_REPORT_GENERATION_FAILED");
            errorResponse.put("exceptionClass", e.getClass().getName());
            errorResponse.put("message", "An error occurred while generating the consolidated report: " + e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now().toString());
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
        response.put("service", "EOD Step 8: Consolidated Financial Reports");
        return ResponseEntity.ok(response);
    }
}
