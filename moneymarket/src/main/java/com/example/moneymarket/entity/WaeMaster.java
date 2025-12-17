package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity for WAE Master (Weighted Average Exchange)
 * Tracks the weighted average exchange rate for each currency pair
 * Used for settlement gain/loss calculation
 */
@Entity
@Table(name = "wae_master")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaeMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "WAE_Id")
    private Long waeId;

    @Column(name = "Ccy_Pair", nullable = false, unique = true, length = 10)
    private String ccyPair;

    @Column(name = "WAE_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal waeRate;

    @Column(name = "FCY_Balance", nullable = false, precision = 20, scale = 2)
    private BigDecimal fcyBalance;

    @Column(name = "LCY_Balance", nullable = false, precision = 20, scale = 2)
    private BigDecimal lcyBalance;

    @Column(name = "Updated_On", nullable = false)
    private LocalDateTime updatedOn;

    @Column(name = "Source_GL", length = 20)
    private String sourceGl;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedOn = LocalDateTime.now();
    }
}
