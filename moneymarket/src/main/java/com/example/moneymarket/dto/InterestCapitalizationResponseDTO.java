package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for Interest Capitalization
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterestCapitalizationResponseDTO {

    private String accountNo;
    private String accountName;
    private BigDecimal oldBalance;
    private BigDecimal accruedInterest;
    private BigDecimal newBalance;
    private String transactionId;
    private LocalDate capitalizationDate;
    private String message;
}
