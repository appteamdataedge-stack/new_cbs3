package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealBookingResponseDTO {

    private String dealAccountNo;
    private String subProductCode;
    private String subProductName;
    private Integer custId;
    private String custName;
    private String operativeAccountNo;
    /** L = Liability, A = Asset */
    private String dealType;
    /** C = Compounding, N = Non-compounding */
    private String interestType;
    private Integer compoundingFrequency;
    private BigDecimal dealAmount;
    private String currencyCode;
    private LocalDate valueDate;
    private LocalDate maturityDate;
    private Integer tenor;
    private String narration;
    private BigDecimal effectiveInterestRate;
    private List<DealScheduleDTO> schedules;
}
