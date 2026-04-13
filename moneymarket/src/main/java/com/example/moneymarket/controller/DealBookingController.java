package com.example.moneymarket.controller;

import com.example.moneymarket.dto.DealBookingRequestDTO;
import com.example.moneymarket.dto.DealBookingResponseDTO;
import com.example.moneymarket.dto.DealScheduleDTO;
import com.example.moneymarket.service.BODSchedulerService;
import com.example.moneymarket.service.DealBookingService;
import com.example.moneymarket.service.SystemDateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Deal Booking and Schedule inquiry/execution.
 */
@RestController
@RequestMapping("/api/deals")
@RequiredArgsConstructor
@Slf4j
public class DealBookingController {

    private final DealBookingService dealBookingService;
    private final BODSchedulerService bodSchedulerService;
    private final SystemDateService systemDateService;

    /**
     * POST /api/deals/book
     * Books a new Term Deposit (Liability) or Loan (Asset) deal.
     */
    @PostMapping("/book")
    public ResponseEntity<?> bookDeal(@Valid @RequestBody DealBookingRequestDTO request,
                                       BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            Map<String, String> errors = new java.util.LinkedHashMap<>();
            bindingResult.getFieldErrors().forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
            return ResponseEntity.badRequest().body(errors);
        }

        log.info("Deal booking request: custId={}, dealType={}, amount={}",
                request.getCustId(), request.getDealType(), request.getDealAmount());
        DealBookingResponseDTO response = dealBookingService.createDeal(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/deals/schedules/{accountNumber}
     * Returns all payment schedules for a deal account.
     */
    @GetMapping("/schedules/{accountNumber}")
    public ResponseEntity<List<DealScheduleDTO>> getSchedules(@PathVariable String accountNumber) {
        List<DealScheduleDTO> schedules = dealBookingService.getSchedulesByAccount(accountNumber);
        return ResponseEntity.ok(schedules);
    }

    /**
     * GET /api/deals/bod/pending-count
     * Returns the count of pending deal schedules for a given date (default: system date).
     * Used by the BOD page to display a preview before running BOD.
     */
    @GetMapping("/bod/pending-count")
    public ResponseEntity<Map<String, Object>> getPendingScheduleCount(
            @RequestParam(required = false) String date) {

        LocalDate businessDate = date != null
                ? LocalDate.parse(date)
                : systemDateService.getSystemDate();

        return ResponseEntity.ok(dealBookingService.getPendingScheduleCount(businessDate));
    }

    /**
     * POST /api/deals/bod/execute
     * Triggers BOD schedule execution for the current (or specified) business date.
     * Used for testing and manual BOD trigger.
     */
    @PostMapping("/bod/execute")
    public ResponseEntity<Map<String, Object>> executeBod(
            @RequestParam(required = false) String businessDate) {

        LocalDate date = businessDate != null
                ? LocalDate.parse(businessDate)
                : systemDateService.getSystemDate();

        log.info("BOD execution triggered for date: {}", date);
        int[] result = bodSchedulerService.executeDailySchedules(date);

        return ResponseEntity.ok(Map.of(
                "businessDate", date.toString(),
                "totalSchedules", result[0],
                "executed", result[1],
                "failed", result[2]
        ));
    }
}
