package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Preview DTO for Interest Capitalization — returned before the user confirms.
 * Shows FCY/LCY accrual breakdown, WAE, mid rate, and estimated gain/loss for FCY accounts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapitalizationPreviewDTO {
    private String accountNo;
    private String currency;
    private BigDecimal accruedFcy;
    private BigDecimal accruedLcy;
    private BigDecimal waeRate;
    private BigDecimal midRate;
    /** Positive = gain, negative = loss. Zero when WAE == Mid or account is BDT. */
    private BigDecimal estimatedGainLoss;
}
