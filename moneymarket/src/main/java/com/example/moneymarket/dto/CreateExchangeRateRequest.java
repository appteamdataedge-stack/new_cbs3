package com.example.moneymarket.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request DTO for creating a new exchange rate
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateExchangeRateRequest {

    @NotNull(message = "Rate date is required")
    private LocalDateTime rateDate;

    @NotBlank(message = "Currency pair is required")
    @Pattern(regexp = "^[A-Z]{3}/[A-Z]{3}$", message = "Currency pair must be in format XXX/XXX (e.g., USD/BDT)")
    private String ccyPair;

    @NotNull(message = "Mid rate is required")
    @DecimalMin(value = "0.0001", message = "Mid rate must be greater than 0")
    @Digits(integer = 10, fraction = 4, message = "Mid rate must have at most 10 digits and 4 decimal places")
    private BigDecimal midRate;

    @NotNull(message = "Buying rate is required")
    @DecimalMin(value = "0.0001", message = "Buying rate must be greater than 0")
    @Digits(integer = 10, fraction = 4, message = "Buying rate must have at most 10 digits and 4 decimal places")
    private BigDecimal buyingRate;

    @NotNull(message = "Selling rate is required")
    @DecimalMin(value = "0.0001", message = "Selling rate must be greater than 0")
    @Digits(integer = 10, fraction = 4, message = "Selling rate must have at most 10 digits and 4 decimal places")
    private BigDecimal sellingRate;

    @Size(max = 20, message = "Source must not exceed 20 characters")
    private String source;

    @Size(max = 20, message = "Uploaded by must not exceed 20 characters")
    private String uploadedBy;
}
