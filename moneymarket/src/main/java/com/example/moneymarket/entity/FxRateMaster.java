package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing foreign exchange rates
 */
@Entity
@Table(name = "fx_rate_master")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FxRateMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Rate_Id")
    private Long rateId;

    @Column(name = "Rate_Date", nullable = false)
    private LocalDateTime rateDate;

    @Column(name = "Ccy_Pair", nullable = false, length = 10)
    private String ccyPair;  // Format: "USD/BDT", "EUR/BDT", etc.

    @Column(name = "Mid_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal midRate;

    @Column(name = "Buying_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal buyingRate;

    @Column(name = "Selling_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal sellingRate;

    @Column(name = "Source", length = 20)
    private String source;

    @Column(name = "Uploaded_By", length = 20)
    private String uploadedBy;

    @Column(name = "Created_At", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "Last_Updated", nullable = false)
    private LocalDateTime lastUpdated;
}
