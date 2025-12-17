package com.example.moneymarket.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Entity for Value Date Interest Accrual
 *
 * This table captures interest that should be accrued on transactions
 * where the Transaction Date is after the Value Date.
 *
 * Example: If Value_Date = 2025-01-10 and Tran_Date = 2025-01-15 (5 days gap),
 * interest should be calculated on the transaction amount for those 5 days.
 *
 * Processing:
 * - Batch Job 1: Inserts records when processing transactions where Tran_Date > Value_Date
 * - Batch Job 2: Aggregates these records and adds to regular interest accrual
 */
@Entity
@Table(name = "Value_Date_Intt_Accr")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValueDateInttAccr {

    /**
     * Custom ID generation: V + YYYYMMDD + 9-digit-sequential + -row-suffix
     * Example: V20251020000000001-1, V20251020000000001-2
     * Generated programmatically in ValueDateInterestService
     */
    @Id
    @Column(name = "Accr_Tran_Id", length = 20)
    private String accrTranId;

    @Column(name = "Account_No", nullable = false, length = 13)
    private String accountNo;

    @Column(name = "Accrual_Date", nullable = false)
    private LocalDate accrualDate;

    @Column(name = "Interest_Rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "Amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false)
    private AccrualStatus status;

    @Column(name = "Tran_Date", nullable = true)
    private LocalDate tranDate;

    @Column(name = "Value_Date", nullable = true)
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "Dr_Cr_Flag", nullable = true)
    private TranTable.DrCrFlag drCrFlag;

    /**
     * Original transaction's Dr/Cr flag from tran_table
     * Used to determine correct balance impact in acct_bal_accrual
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "Original_Dr_Cr_Flag", nullable = true)
    private TranTable.DrCrFlag originalDrCrFlag;

    @Enumerated(EnumType.STRING)
    @Column(name = "Tran_Status", nullable = true)
    private TranTable.TranStatus tranStatus;

    @Column(name = "GL_Account_No", length = 20, nullable = true)
    private String glAccountNo;

    @Column(name = "Tran_Ccy", length = 3, nullable = true)
    private String tranCcy;

    @Column(name = "FCY_Amt", precision = 20, scale = 2, nullable = true)
    private BigDecimal fcyAmt;

    @Column(name = "Exchange_Rate", precision = 10, scale = 4, nullable = true)
    private BigDecimal exchangeRate;

    @Column(name = "LCY_Amt", precision = 20, scale = 2, nullable = true)
    private BigDecimal lcyAmt;

    @Column(name = "Narration", length = 100, nullable = true)
    private String narration;

    @Column(name = "UDF1", length = 50, nullable = true)
    private String udf1;

    /**
     * Source transaction ID that triggered value date interest
     */
    @Column(name = "Tran_Id", length = 20, nullable = true)
    private String tranId;

    /**
     * Number of days between Value_Date and Tran_Date
     */
    @Column(name = "Day_Gap", nullable = true)
    private Integer dayGap;

    public enum AccrualStatus {
        Pending, Posted, Verified
    }
}
