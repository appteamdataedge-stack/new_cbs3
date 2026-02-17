package com.example.moneymarket.dto;

import com.example.moneymarket.entity.CustAcctMaster.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountResponseDTO {

    private String accountNo;
    private Integer subProductId;
    private String subProductName;
    private String glNum;
    private Integer custId;
    private String custName;
    private String acctName;
    private LocalDate dateOpening;
    private Integer tenor;
    private LocalDate dateMaturity;
    private LocalDate dateClosure;
    private String branchCode;
    private AccountStatus accountStatus;
    private BigDecimal currentBalance;      // Static balance from acct_bal table
    private BigDecimal availableBalance;    // Previous day opening balance (for Liability) or includes loan limit (for Asset)
    private BigDecimal computedBalance;     // Real-time computed balance (Prev Day + Credits - Debits)
    private BigDecimal interestAccrued;     // Latest closing balance from acct_bal_accrual
    private BigDecimal loanLimit;           // Loan/Limit Amount for Asset-side accounts (GL starting with "2")
    private LocalDate lastInterestPaymentDate;  // Date when interest was last capitalized
    private Boolean interestBearing;        // Whether the account's product is interest-bearing
    private String productName;             // Product name
    
    // ✅ FIX: Added missing fields for Account Details page
    private String accountCcy;              // Account currency (USD, BDT, etc.) from Product
    private BigDecimal interestRate;        // Interest rate from Sub-Product
    private String makerId;                 // Created by (Maker ID) from cust_acct_master

    // LCY (BDT) real-time amounts + WAE for FCY accounts
    private BigDecimal availableBalanceLcy; // Available balance in BDT (real-time)
    private BigDecimal computedBalanceLcy;  // Computed balance in BDT (Prev Day LCY + Credits LCY - Debits LCY)
    private BigDecimal wae;                 // Weighted Average Exchange Rate = availableBalanceLcy / availableBalance
    
    // Message for UI display (e.g., confirmation messages)
    private String message;
}
