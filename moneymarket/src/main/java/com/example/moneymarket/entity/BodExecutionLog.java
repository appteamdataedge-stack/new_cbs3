package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "BOD_EXECUTION_LOG")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BodExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "EXECUTION_DATE", nullable = false, unique = true)
    private LocalDate executionDate;

    @Column(name = "EXECUTION_TIMESTAMP", nullable = false)
    private LocalDateTime executionTimestamp;

    /** SUCCESS, PARTIAL (some failed), FAILED (all failed) */
    @Column(name = "STATUS", nullable = false, length = 20)
    private String status;

    @Column(name = "SCHEDULES_EXECUTED", nullable = false)
    private int schedulesExecuted;

    @Column(name = "SCHEDULES_FAILED", nullable = false)
    private int schedulesFailed;

    @Column(name = "TRANSACTIONS_POSTED", nullable = false)
    private int transactionsPosted;

    @Column(name = "EXECUTED_BY", length = 50)
    private String executedBy;

    @Column(name = "ERROR_MESSAGE", columnDefinition = "TEXT")
    private String errorMessage;
}
