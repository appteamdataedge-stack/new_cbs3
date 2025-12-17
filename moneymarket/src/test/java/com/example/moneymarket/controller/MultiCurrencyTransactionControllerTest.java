package com.example.moneymarket.controller;

import com.example.moneymarket.service.MultiCurrencyTransactionService;
import com.example.moneymarket.service.RevaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for MultiCurrencyTransactionController
 * Tests the REST API endpoints for MCT operations
 */
@WebMvcTest(MultiCurrencyTransactionController.class)
class MultiCurrencyTransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MultiCurrencyTransactionService mctService;

    @MockBean
    private RevaluationService revaluationService;

    private RevaluationService.RevaluationResult sampleRevaluationResult;

    @BeforeEach
    void setUp() {
        sampleRevaluationResult = RevaluationService.RevaluationResult.builder()
            .revalDate(LocalDate.now())
            .entriesPosted(5)
            .totalGain(new BigDecimal("1000.00"))
            .totalLoss(new BigDecimal("500.00"))
            .entries(new ArrayList<>())
            .build();
    }

    @Test
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/api/mct/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.service").value("Multi-Currency Transaction Service"))
            .andExpect(jsonPath("$.features").isArray());
    }

    @Test
    void testGetWAERate_Success() throws Exception {
        // Arrange
        when(mctService.getWAERate("USD"))
            .thenReturn(new BigDecimal("110.5000"));

        // Act & Assert
        mockMvc.perform(get("/api/mct/wae/USD"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.waeRate").value(110.5000))
            .andExpect(jsonPath("$.ccyPair").value("USD/BDT"));
    }

    @Test
    void testGetPositionGL_Success() throws Exception {
        // Arrange
        when(mctService.getPositionGL("USD"))
            .thenReturn(Optional.of("920101001"));

        // Act & Assert
        mockMvc.perform(get("/api/mct/position-gl/USD"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.positionGL").value("920101001"));
    }

    @Test
    void testGetPositionGL_NotConfigured() throws Exception {
        // Arrange
        when(mctService.getPositionGL("CHF"))
            .thenReturn(Optional.empty());

        // Act & Assert
        mockMvc.perform(get("/api/mct/position-gl/CHF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.currency").value("CHF"))
            .andExpect(jsonPath("$.positionGL").value("Not configured"));
    }

    @Test
    void testTriggerEodRevaluation_Success() throws Exception {
        // Arrange
        when(revaluationService.performEodRevaluation())
            .thenReturn(sampleRevaluationResult);

        // Act & Assert
        mockMvc.perform(post("/api/mct/revaluation/eod"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.message").value("EOD Revaluation completed successfully"))
            .andExpect(jsonPath("$.entriesPosted").value(5))
            .andExpect(jsonPath("$.totalGain").value(1000.00))
            .andExpect(jsonPath("$.totalLoss").value(500.00));
    }

    @Test
    void testTriggerBodRevaluationReversal_Success() throws Exception {
        // Arrange (void method, no return value to mock)

        // Act & Assert
        mockMvc.perform(post("/api/mct/revaluation/bod"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.message").value("BOD Revaluation Reversal completed successfully"));
    }

    @Test
    void testGetRevaluationSummary_WithoutDate() throws Exception {
        // Arrange
        when(revaluationService.getRevaluationSummary(any(LocalDate.class)))
            .thenReturn(sampleRevaluationResult);

        // Act & Assert
        mockMvc.perform(get("/api/mct/revaluation/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.entriesPosted").value(5));
    }

    @Test
    void testGetRevaluationSummary_WithDate() throws Exception {
        // Arrange
        LocalDate testDate = LocalDate.of(2025, 11, 23);
        when(revaluationService.getRevaluationSummary(testDate))
            .thenReturn(sampleRevaluationResult);

        // Act & Assert
        mockMvc.perform(get("/api/mct/revaluation/summary?date=2025-11-23"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.entriesPosted").value(5));
    }
}
