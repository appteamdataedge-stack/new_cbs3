package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for GL Statement Line
 * Displays GL-level balance summary (not transaction details)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLStatementLineDTO {
    
    /**
     * GL Account Code (e.g., "220302001")
     */
    private String glCode;
    
    /**
     * GL Account Name (e.g., "Nostro USD - Chase")
     */
    private String glName;
    
    /**
     * Currency Code (USD, BDT, EUR, etc.)
     */
    private String currency;
    
    /**
     * Opening Balance for the period
     */
    private BigDecimal openingBalance;
    
    /**
     * Total Debit amount for the period
     */
    private BigDecimal totalDebit;
    
    /**
     * Total Credit amount for the period
     */
    private BigDecimal totalCredit;
    
    /**
     * Closing Balance for the period
     * Formula: Opening Balance + Total Credit - Total Debit
     */
    private BigDecimal closingBalance;
    
    /**
     * GL Type (Asset, Liability, Income, Expenditure)
     */
    private String glType;
}
