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
}

