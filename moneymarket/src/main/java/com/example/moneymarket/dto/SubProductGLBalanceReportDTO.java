package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for Subproduct-wise Account & GL Balance Report
 * Used in Batch Job 7 to show reconciliation between account balances and GL balances
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubProductGLBalanceReportDTO {

    private String subProductCode;
    private String subProductName;
    private String glNumber;
    private String glName;
    private Long accountCount;
    private BigDecimal totalAccountBalance;
    private BigDecimal totalGLBalance;
    private BigDecimal difference;
    private String status;

    /**
     * Calculate the difference and status
     */
    public void calculateDifferenceAndStatus() {
        if (totalAccountBalance == null) {
            totalAccountBalance = BigDecimal.ZERO;
        }
        if (totalGLBalance == null) {
            totalGLBalance = BigDecimal.ZERO;
        }

        this.difference = totalAccountBalance.subtract(totalGLBalance);
        this.status = difference.compareTo(BigDecimal.ZERO) == 0 ? "MATCHED" : "MISMATCHED";
    }
}
