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
 * Entity for Acct_Bal_LCY table
 * 
 * Purpose: Stores all account balances in Local Currency (BDT)
 * - Mirror of Acct_Bal table but ALL amounts in BDT
 * - For BDT accounts: values same as acct_bal
 * - For USD/FCY accounts: convert amounts to BDT using exchange rates
 * 
 * This table is essential for:
 * - Consolidated reporting across all currencies
 * - Regulatory compliance (all reports in BDT)
 * - Financial analysis in base currency
 */
@Entity
@Table(name = "Acct_Bal_LCY")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(AcctBalLcyId.class)
public class AcctBalLcy {

    @Id
    @Column(name = "Tran_Date", nullable = false)
    private LocalDate tranDate;

    @Id
    @Column(name = "Account_No", length = 13, nullable = false)
    private String accountNo;

    @Column(name = "Opening_Bal_lcy", precision = 20, scale = 2)
    private BigDecimal openingBalLcy;

    @Column(name = "DR_Summation_lcy", precision = 20, scale = 2)
    private BigDecimal drSummationLcy;

    @Column(name = "CR_Summation_lcy", precision = 20, scale = 2)
    private BigDecimal crSummationLcy;

    @Column(name = "Closing_Bal_lcy", precision = 20, scale = 2)
    private BigDecimal closingBalLcy;

    @Column(name = "Available_Balance_lcy", nullable = false, precision = 20, scale = 2)
    private BigDecimal availableBalanceLcy;

    @Column(name = "Last_Updated", nullable = false)
    private LocalDateTime lastUpdated;
}
