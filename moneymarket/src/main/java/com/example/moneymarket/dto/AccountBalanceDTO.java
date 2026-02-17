package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for account balance information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountBalanceDTO {

    private String accountNo;
    private String accountName;
    private String accountCcy;           // Account currency (BDT, USD, EUR, etc.)
    private BigDecimal previousDayOpeningBalance; // Previous day's closing balance (static, does not change during the day)
    private BigDecimal availableBalance; // Available balance (includes loan limit for Asset accounts, may include today's transactions)
    private BigDecimal currentBalance;   // Current day balance from acct_bal (in account's currency)
    private BigDecimal todayDebits;      // Current day debit transactions (in account's currency)
    private BigDecimal todayCredits;     // Current day credit transactions (in account's currency)
    private BigDecimal computedBalance;  // Previous day opening + current day credits - current day debits (REAL-TIME BALANCE in account's currency)
    private BigDecimal interestAccrued;  // Latest closing balance from acct_bal_accrual table (in account's currency)
    
    // LCY (BDT) equivalent amounts - for multi-currency reporting
    private BigDecimal currentBalanceLcy;   // Current balance in BDT (from acct_bal_lcy)
    private BigDecimal availableBalanceLcy; // Available balance in BDT (from acct_bal_lcy)
    private BigDecimal computedBalanceLcy;  // Computed balance in BDT (converted from account currency)

    // Weighted Average Exchange Rate (WAE) for FCY accounts
    // Formula: Available_Balance_LCY / Available_Balance_FCY
    private BigDecimal wae;
}

