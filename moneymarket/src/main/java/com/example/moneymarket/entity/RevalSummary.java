package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity for Revaluation Summary
 * Tracks daily EOD revaluation summary for reporting and audit
 */
@Entity
@Table(name = "reval_summary",
    indexes = {
        @Index(name = "idx_reval_summary_date", columnList = "Reval_Date"),
        @Index(name = "idx_reval_summary_status", columnList = "Status")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevalSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Summary_Id")
    private Long summaryId;

    // Revaluation date
    @Column(name = "Reval_Date", nullable = false, unique = true)
    private LocalDate revalDate;

    // Accounts revalued
    @Column(name = "Total_Accounts", nullable = false)
    @Builder.Default
    private Integer totalAccounts = 0;

    @Column(name = "GL_Accounts", nullable = false)
    @Builder.Default
    private Integer glAccounts = 0;

    @Column(name = "Customer_Accounts", nullable = false)
    @Builder.Default
    private Integer customerAccounts = 0;

    // Gain/Loss summary
    @Column(name = "Total_Gain", nullable = false, precision = 20, scale = 2)
    @Builder.Default
    private BigDecimal totalGain = BigDecimal.ZERO;

    @Column(name = "Total_Loss", nullable = false, precision = 20, scale = 2)
    @Builder.Default
    private BigDecimal totalLoss = BigDecimal.ZERO;

    @Column(name = "Net_Reval", nullable = false, precision = 20, scale = 2)
    @Builder.Default
    private BigDecimal netReval = BigDecimal.ZERO;

    // Processing details
    @Column(name = "Start_Time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "End_Time")
    private LocalDateTime endTime;

    @Column(name = "Duration_Seconds")
    private Integer durationSeconds;

    // Status
    @Column(name = "Status", nullable = false, length = 20)
    @Builder.Default
    private String status = "COMPLETED";

    @Column(name = "Error_Message", columnDefinition = "TEXT")
    private String errorMessage;

    // BOD reversal tracking
    @Column(name = "Reversed_On")
    private LocalDateTime reversedOn;

    @Column(name = "Reversal_Duration_Seconds")
    private Integer reversalDurationSeconds;

    // Audit
    @Column(name = "Executed_By", nullable = false, length = 20)
    private String executedBy;

    @Column(name = "Created_On", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "Last_Updated")
    private LocalDateTime lastUpdated;

    @PrePersist
    protected void onCreate() {
        this.createdOn = LocalDateTime.now();
        if (this.status == null) {
            this.status = "COMPLETED";
        }
        if (this.startTime == null) {
            this.startTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.lastUpdated = LocalDateTime.now();
    }
}
