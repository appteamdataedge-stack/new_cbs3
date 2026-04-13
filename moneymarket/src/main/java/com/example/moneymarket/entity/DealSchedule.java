package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "DEAL_SCHEDULE")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SCHEDULE_ID")
    private Long scheduleId;

    @Column(name = "ACCOUNT_NUMBER", nullable = false, length = 30)
    private String accountNumber;

    @Column(name = "OPERATIVE_ACCOUNT_NO", length = 30)
    private String operativeAccountNo;

    @Column(name = "CUSTOMER_ID", length = 20)
    private String customerId;

    /** L = Liability (Term Deposit), A = Asset (Loan) */
    @Column(name = "DEAL_TYPE", nullable = false, length = 1)
    private String dealType;

    /** INT_PAY or MAT_PAY */
    @Column(name = "EVENT_CODE", nullable = false, length = 20)
    private String eventCode;

    @Column(name = "SCHEDULE_DATE", nullable = false)
    private LocalDate scheduleDate;

    @Column(name = "SCHEDULE_AMOUNT", precision = 18, scale = 2)
    private BigDecimal scheduleAmount;

    @Column(name = "CURRENCY_CODE", nullable = false, length = 3)
    private String currencyCode;

    /** PENDING / EXECUTED / FAILED / CANCELLED */
    @Column(name = "STATUS", nullable = false, length = 15)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "EXECUTION_DATE_TIME")
    private LocalDateTime executionDateTime;

    @Column(name = "EXECUTED_BY", length = 20)
    private String executedBy;

    @Column(name = "ERROR_CODE", length = 50)
    private String errorCode;

    @Column(name = "ERROR_MESSAGE", length = 500)
    private String errorMessage;

    @Column(name = "CREATED_DATE_TIME", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdDateTime = LocalDateTime.now();

    @Column(name = "CREATED_BY", nullable = false, length = 20)
    private String createdBy;

    @Column(name = "LAST_UPDATED_DATE_TIME")
    private LocalDateTime lastUpdatedDateTime;

    @Column(name = "LAST_UPDATED_BY", length = 20)
    private String lastUpdatedBy;
}
