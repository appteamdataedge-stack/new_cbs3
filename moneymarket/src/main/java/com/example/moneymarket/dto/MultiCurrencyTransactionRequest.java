package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiCurrencyTransactionRequest {

    @NotBlank(message = "Account number is required")
    private String accountNo;

    @NotBlank(message = "Transaction type is required")
    @Pattern(regexp = "DEPOSIT|WITHDRAWAL", message = "Transaction type must be DEPOSIT or WITHDRAWAL")
    private String tranType;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency code must be 3 characters")
    private String currency;

    @NotNull(message = "FCY amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal fcyAmount;

    @NotNull(message = "Exchange rate is required")
    @DecimalMin(value = "0.0001", message = "Exchange rate must be greater than 0")
    private BigDecimal exchangeRate;

    private String narration;

    private String userId;
}
