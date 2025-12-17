package com.example.moneymarket.service;

import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for validating currency combinations in multi-currency transactions
 * Ensures transactions only use allowed currency pairs
 */
@Service
@Slf4j
public class CurrencyValidationService {

    // Allowed currencies
    private static final Set<String> ALLOWED_CURRENCIES = Set.of("BDT", "USD");

    /**
     * Validate transaction currency combinations
     * Allowed combinations:
     * - BDT-BDT: All lines in BDT
     * - USD-USD: All lines in USD
     * - BDT-USD: Mix of BDT and USD lines
     *
     * @param transactions List of transaction lines
     * @throws BusinessException if invalid currency combination detected
     */
    public void validateCurrencyCombination(List<TranTable> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        // Get all unique currencies in this transaction
        Set<String> currencies = transactions.stream()
            .map(TranTable::getTranCcy)
            .collect(Collectors.toSet());

        // Validate all currencies are allowed
        for (String currency : currencies) {
            if (!ALLOWED_CURRENCIES.contains(currency)) {
                throw new BusinessException(
                    String.format("Currency '%s' is not allowed. Only BDT and USD are supported.", currency));
            }
        }

        // Validate currency combination
        if (currencies.size() > 2) {
            throw new BusinessException(
                "Invalid currency combination. Transactions can only contain BDT, USD, or a mix of BDT and USD.");
        }

        // If mix of currencies, must be BDT and USD only
        if (currencies.size() == 2) {
            if (!(currencies.contains("BDT") && currencies.contains("USD"))) {
                throw new BusinessException(
                    "Invalid currency combination. Mixed currency transactions must be BDT-USD only.");
            }
        }

        log.debug("Currency validation passed for transaction with currencies: {}", currencies);
    }

    /**
     * Determine transaction type based on currencies used
     *
     * @param transactions List of transaction lines
     * @return Transaction type: BDT_ONLY, USD_ONLY, or BDT_USD_MIX
     */
    public TransactionType getTransactionType(List<TranTable> transactions) {
        Set<String> currencies = transactions.stream()
            .map(TranTable::getTranCcy)
            .collect(Collectors.toSet());

        if (currencies.size() == 1) {
            String currency = currencies.iterator().next();
            if ("BDT".equals(currency)) {
                return TransactionType.BDT_ONLY;
            } else if ("USD".equals(currency)) {
                return TransactionType.USD_ONLY;
            }
        } else if (currencies.size() == 2 &&
                   currencies.contains("BDT") &&
                   currencies.contains("USD")) {
            return TransactionType.BDT_USD_MIX;
        }

        return TransactionType.INVALID;
    }

    /**
     * Check if a currency is allowed
     */
    public boolean isCurrencyAllowed(String currency) {
        return ALLOWED_CURRENCIES.contains(currency);
    }

    /**
     * Get list of allowed currencies
     */
    public Set<String> getAllowedCurrencies() {
        return ALLOWED_CURRENCIES;
    }

    /**
     * Transaction type enumeration
     */
    public enum TransactionType {
        BDT_ONLY,      // All lines in BDT (no MCT processing)
        USD_ONLY,      // All lines in USD (Position GL only, no WAE/Settlement)
        BDT_USD_MIX,   // Mix of BDT and USD (full MCT processing)
        INVALID        // Invalid combination
    }
}
