package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for exchange rate data
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateResponse {

    private Long rateId;
    private LocalDateTime rateDate;
    private String ccyPair;
    private BigDecimal midRate;
    private BigDecimal buyingRate;
    private BigDecimal sellingRate;
    private String source;
    private String uploadedBy;
    private LocalDateTime createdAt;
    private LocalDateTime lastUpdated;
}
