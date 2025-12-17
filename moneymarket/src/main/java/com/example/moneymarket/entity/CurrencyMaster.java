package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity for Currency Master
 * Maintains all supported currencies in the system
 */
@Entity
@Table(name = "currency_master")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyMaster {

    @Id
    @Column(name = "Ccy_Code", length = 3)
    private String ccyCode;

    @Column(name = "Ccy_Name", nullable = false, length = 50)
    private String ccyName;

    @Column(name = "Ccy_Symbol", length = 5)
    private String ccySymbol;

    @Column(name = "Is_Base_Ccy", nullable = false)
    @Builder.Default
    private Boolean isBaseCcy = false;

    @Column(name = "Decimal_Places", nullable = false)
    @Builder.Default
    private Integer decimalPlaces = 2;

    @Column(name = "Is_Active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "Created_At", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "Updated_At", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
