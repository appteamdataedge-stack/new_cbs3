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
    private BigDecimal totalAccountBalance;     // SUM(acc_bal)     — native/FCY currency
    private BigDecimal totalAccountBalanceLcy;  // SUM(acc_bal_lcy) — BDT equivalent (NEW)
    private BigDecimal totalGLBalance;
    private BigDecimal difference;
    private String status;

    /**
     * Calculate the difference and status.
     * Difference = totalAccountBalanceLcy - totalGLBalance (both in BDT — currency-matched)
     */
    public void calculateDifferenceAndStatus() {
        if (totalAccountBalance == null) totalAccountBalance = BigDecimal.ZERO;
        if (totalAccountBalanceLcy == null) totalAccountBalanceLcy = BigDecimal.ZERO;
        if (totalGLBalance == null) totalGLBalance = BigDecimal.ZERO;

        this.difference = totalAccountBalanceLcy.subtract(totalGLBalance);
        this.status = difference.compareTo(BigDecimal.ZERO) == 0 ? "MATCHED" : "MISMATCHED";
    }
}
