package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FxConversionRequest {
    private String transactionType; // "SELLING" (and optionally "BUYING" for reuse)
    private String nostroAccountId;
    private String customerAccountId;
    private String currencyCode;
    private BigDecimal fcyAmount;
    private BigDecimal dealRate;
    private String particulars;
    private String userId;

    // Optional frontend-calculated fields (intraday projection / transparency)
    private BigDecimal wae1;
    private BigDecimal wae2;
    private BigDecimal nostroFcyBalance;
    private BigDecimal nostroLcyBalance;
    private BigDecimal positionFcyBalance;
    private BigDecimal positionLcyBalance;
}

