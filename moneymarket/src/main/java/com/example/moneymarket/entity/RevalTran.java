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
 * Entity for Revaluation Transactions
 * Tracks EOD revaluation entries for FCY accounts
 * Supports BOD reversal tracking
 */
@Entity
@Table(name = "reval_tran",
    indexes = {
        @Index(name = "idx_reval_date", columnList = "Reval_Date"),
        @Index(name = "idx_acct_num", columnList = "Acct_Num"),
        @Index(name = "idx_status", columnList = "Status"),
        @Index(name = "idx_tran_id", columnList = "Tran_Id")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevalTran {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Reval_Id")
    private Long revalId;

    @Column(name = "Reval_Date", nullable = false)
    private LocalDate revalDate;

    @Column(name = "Acct_Num", nullable = false, length = 20)
    private String acctNum;

    @Column(name = "Ccy_Code", nullable = false, length = 3)
    private String ccyCode;

    @Column(name = "FCY_Balance", nullable = false, precision = 20, scale = 2)
    private BigDecimal fcyBalance;

    @Column(name = "Mid_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal midRate;

    @Column(name = "Booked_LCY", nullable = false, precision = 20, scale = 2)
    private BigDecimal bookedLcy;

    @Column(name = "MTM_LCY", nullable = false, precision = 20, scale = 2)
    private BigDecimal mtmLcy;

    @Column(name = "Reval_Diff", nullable = false, precision = 20, scale = 2)
    private BigDecimal revalDiff;

    @Column(name = "Reval_GL", nullable = false, length = 20)
    private String revalGl;

    @Column(name = "Tran_Id", length = 20)
    private String tranId;

    @Column(name = "Reversal_Tran_Id", length = 20)
    private String reversalTranId;

    @Column(name = "Status", nullable = false, length = 20)
    @Builder.Default
    private String status = "POSTED";

    @Column(name = "Created_On", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "Reversed_On")
    private LocalDateTime reversedOn;

    @PrePersist
    protected void onCreate() {
        this.createdOn = LocalDateTime.now();
        if (this.status == null) {
            this.status = "POSTED";
        }
    }
}
