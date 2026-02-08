package com.example.moneymarket.controller;

import com.example.moneymarket.service.GLStatementService;
import com.example.moneymarket.service.GLStatementService.GLOptionDTO;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST Controller for Statement of GL operations
 * ✅ ISSUE 3: New controller for GL statement generation
 */
@RestController
@RequestMapping("/api/gl-statement")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class GLStatementController {

    private final GLStatementService glStatementService;

    /**
     * Generate Statement of GL
     *
     * @param glNum GL account number
     * @param fromDate Start date
     * @param toDate End date
     * @param format File format (default: excel)
     * @return Excel file as downloadable attachment
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateGLStatement(
            @RequestParam String glNum,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "excel") String format) {

        try {
            log.info("Generating GL Statement for GL {} from {} to {}", glNum, fromDate, toDate);

            // Generate statement
            byte[] fileBytes = glStatementService.generateGLStatement(glNum, fromDate, toDate, format);

            // Create filename
            String filename = String.format("GL_Statement_%s_%s_to_%s.xlsx", 
                    glNum, fromDate.toString(), toDate.toString());

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(fileBytes.length);

            log.info("GL Statement generated successfully for GL {}, file size: {} bytes", glNum, fileBytes.length);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(fileBytes);

        } catch (IllegalArgumentException e) {
            log.warn("Validation error generating GL Statement: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Validation Error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);

        } catch (NoSuchElementException e) {
            log.warn("GL account not found: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Not Found");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);

        } catch (Exception e) {
            log.error("Error generating GL Statement for GL {}: {}", glNum, e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Internal Server Error");
            errorResponse.put("message", "An error occurred while generating the GL statement: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get list of all GL accounts for dropdown
     *
     * @return List of GL options
     */
    @GetMapping("/gl-accounts")
    public ResponseEntity<List<GLOptionDTO>> getGLList() {
        try {
            log.debug("Fetching GL account list for GL Statement");
            List<GLOptionDTO> glAccounts = glStatementService.getGLList();
            log.debug("Found {} GL accounts", glAccounts.size());
            return ResponseEntity.ok(glAccounts);

        } catch (Exception e) {
            log.error("Error fetching GL account list: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Validate date range (6-month maximum)
     *
     * @param fromDate Start date
     * @param toDate End date
     * @return Validation result
     */
    @PostMapping("/validate-date-range")
    public ResponseEntity<Map<String, Object>> validateDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        Map<String, Object> result = new HashMap<>();

        try {
            if (fromDate.isAfter(toDate)) {
                result.put("valid", false);
                result.put("message", "From date must be before or equal to To date");
                return ResponseEntity.ok(result);
            }

            // Check 6-month maximum
            LocalDate sixMonthsLater = fromDate.plusMonths(6);
            if (toDate.isAfter(sixMonthsLater)) {
                result.put("valid", false);
                result.put("message", "Date range exceeds 6 months");
                return ResponseEntity.ok(result);
            }

            result.put("valid", true);
            result.put("message", "Valid");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error validating date range: {}", e.getMessage(), e);
            result.put("valid", false);
            result.put("message", "Error validating date range");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }
}
