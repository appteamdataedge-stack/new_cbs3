package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealScheduleDTO {

    private Long scheduleId;
    private String accountNumber;
    private String operativeAccountNo;
    private String customerId;
    private String dealType;
    private String eventCode;
    private LocalDate scheduleDate;
    private BigDecimal scheduleAmount;
    private String currencyCode;
    private String status;
    private LocalDateTime executionDateTime;
    private String executedBy;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdDateTime;
}
