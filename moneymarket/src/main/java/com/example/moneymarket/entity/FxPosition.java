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
@Table(
        name = "fx_position",
        uniqueConstraints = @UniqueConstraint(
                name = "unique_position_date",
                columnNames = {"Tran_Date", "Position_GL_Num", "Position_Ccy"}
        ),
        indexes = {
                @Index(name = "idx_position_gl", columnList = "Position_GL_Num"),
                @Index(name = "idx_position_date", columnList = "Tran_Date"),
                @Index(name = "idx_position_ccy", columnList = "Position_Ccy")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FxPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Id")
    private Long id;

    @Column(name = "Tran_Date", nullable = false)
    private LocalDate tranDate;

    @Column(name = "Position_GL_Num", nullable = false, length = 9)
    private String positionGlNum;

    @Column(name = "Position_Ccy", nullable = false, length = 3)
    private String positionCcy;

    @Column(name = "Opening_Bal", precision = 20, scale = 2)
    private BigDecimal openingBal;

    @Column(name = "DR_Summation", precision = 20, scale = 2)
    private BigDecimal drSummation;

    @Column(name = "CR_Summation", precision = 20, scale = 2)
    private BigDecimal crSummation;

    @Column(name = "Closing_Bal", precision = 20, scale = 2)
    private BigDecimal closingBal;

    @Column(name = "Last_Updated")
    private LocalDateTime lastUpdated;
}

