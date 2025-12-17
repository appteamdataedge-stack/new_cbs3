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
 * Entity for Settlement Gain/Loss tracking
 * Tracks all settlement gain/loss calculations for MCT SELL transactions
 * Provides complete audit trail for settlement calculations
 */
@Entity
@Table(name = "settlement_gain_loss",
    indexes = {
        @Index(name = "idx_settlement_tran_id", columnList = "Tran_Id"),
        @Index(name = "idx_settlement_account", columnList = "Account_No"),
        @Index(name = "idx_settlement_date", columnList = "Tran_Date"),
        @Index(name = "idx_settlement_currency", columnList = "Currency"),
        @Index(name = "idx_settlement_status", columnList = "Status"),
        @Index(name = "idx_settlement_type", columnList = "Settlement_Type")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementGainLoss {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Settlement_Id")
    private Long settlementId;

    // Transaction identification
    @Column(name = "Tran_Id", nullable = false, length = 20)
    private String tranId;

    @Column(name = "Tran_Date", nullable = false)
    private LocalDate tranDate;

    @Column(name = "Value_Date", nullable = false)
    private LocalDate valueDate;

    // Account and currency details
    @Column(name = "Account_No", nullable = false, length = 20)
    private String accountNo;

    @Column(name = "Account_Name", length = 100)
    private String accountName;

    @Column(name = "Currency", nullable = false, length = 3)
    private String currency;

    // Amount and rate details
    @Column(name = "FCY_Amt", nullable = false, precision = 20, scale = 2)
    private BigDecimal fcyAmt;

    @Column(name = "Deal_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal dealRate;

    @Column(name = "WAE_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal waeRate;

    // Settlement calculation
    @Column(name = "Settlement_Amt", nullable = false, precision = 20, scale = 2)
    private BigDecimal settlementAmt;

    @Column(name = "Settlement_Type", nullable = false, length = 4)
    private String settlementType; // GAIN or LOSS

    // GL account used for posting
    @Column(name = "Settlement_GL", nullable = false, length = 20)
    private String settlementGl;

    @Column(name = "Position_GL", nullable = false, length = 20)
    private String positionGl;

    // Posting details
    @Column(name = "Entry5_Tran_Id", length = 20)
    private String entry5TranId;

    @Column(name = "Entry6_Tran_Id", length = 20)
    private String entry6TranId;

    @Column(name = "Posted_By", nullable = false, length = 20)
    private String postedBy;

    @Column(name = "Posted_On", nullable = false)
    private LocalDateTime postedOn;

    // Status and audit
    @Column(name = "Status", nullable = false, length = 20)
    @Builder.Default
    private String status = "POSTED";

    @Column(name = "Reversal_Id")
    private Long reversalId;

    @Column(name = "Reversed_On")
    private LocalDateTime reversedOn;

    // Additional info
    @Column(name = "Narration", length = 500)
    private String narration;

    @Column(name = "Created_On", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "Last_Updated")
    private LocalDateTime lastUpdated;

    @PrePersist
    protected void onCreate() {
        this.createdOn = LocalDateTime.now();
        this.postedOn = LocalDateTime.now();
        if (this.status == null) {
            this.status = "POSTED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.lastUpdated = LocalDateTime.now();
    }
}
