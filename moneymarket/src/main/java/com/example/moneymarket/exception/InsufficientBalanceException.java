package com.example.moneymarket.exception;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * Thrown when an operative account has insufficient balance to fund a deal booking.
 * Carries structured fields so the REST layer can return a rich error response.
 */
@Getter
public class InsufficientBalanceException extends RuntimeException {

    private final String accountNumber;
    private final String accountName;
    private final BigDecimal currentBalance;
    private final BigDecimal requiredAmount;
    private final BigDecimal shortfall;
    private final String currency;

    public InsufficientBalanceException(String accountNumber,
                                        String accountName,
                                        BigDecimal currentBalance,
                                        BigDecimal requiredAmount,
                                        String currency) {
        super(String.format(
            "Insufficient balance in account %s (%s). " +
            "Available: %s %,.2f | Required: %s %,.2f | Shortfall: %s %,.2f",
            accountNumber, accountName,
            currency, currentBalance,
            currency, requiredAmount,
            currency, requiredAmount.subtract(currentBalance)));
        this.accountNumber = accountNumber;
        this.accountName   = accountName;
        this.currentBalance  = currentBalance;
        this.requiredAmount  = requiredAmount;
        this.shortfall       = requiredAmount.subtract(currentBalance);
        this.currency        = currency;
    }
}
