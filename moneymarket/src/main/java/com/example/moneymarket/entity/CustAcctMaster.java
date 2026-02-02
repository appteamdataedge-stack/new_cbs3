package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "Cust_Acct_Master")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustAcctMaster {

    @Id
    @Column(name = "Account_No", length = 13)
    private String accountNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "Sub_Product_Id", nullable = false)
    private SubProdMaster subProduct;

    @Column(name = "GL_Num", nullable = false, length = 20)
    private String glNum;

    @Column(name = "Account_Ccy", nullable = false, length = 3)
    @Builder.Default
    private String accountCcy = "BDT";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "Cust_Id", nullable = false)
    private CustMaster customer;

    @Column(name = "Cust_Name", length = 100)
    private String custName;

    @Column(name = "Acct_Name", nullable = false, length = 100)
    private String acctName;

    @Column(name = "Date_Opening", nullable = false)
    private LocalDate dateOpening;

    @Column(name = "Tenor")
    private Integer tenor;

    @Column(name = "Date_Maturity")
    private LocalDate dateMaturity;

    @Column(name = "Date_Closure")
    private LocalDate dateClosure;

    @Column(name = "Branch_Code", nullable = false, length = 10)
    private String branchCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "Account_Status", nullable = false)
    private AccountStatus accountStatus;

    @Column(name = "Loan_Limit", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal loanLimit = BigDecimal.ZERO;

    @Column(name = "Last_Interest_Payment_Date")
    private LocalDate lastInterestPaymentDate;

    // ✅ TEMPORARY FIX: Use @Transient until database columns are added
    // These fields are NOT persisted to database yet (columns don't exist)
    // After running database migration, change @Transient to @Column
    @Transient
    private String makerId;

    @Transient
    private LocalDate entryDate;

    @Transient
    private LocalTime entryTime;

    public enum AccountStatus {
        Active, Inactive, Closed, Dormant
    }
}
