package com.example.moneymarket.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for updating an existing exchange rate
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateExchangeRateRequest {

    @DecimalMin(value = "0.0001", message = "Mid rate must be greater than 0")
    @Digits(integer = 10, fraction = 4, message = "Mid rate must have at most 10 digits and 4 decimal places")
    private BigDecimal midRate;

    @DecimalMin(value = "0.0001", message = "Buying rate must be greater than 0")
    @Digits(integer = 10, fraction = 4, message = "Buying rate must have at most 10 digits and 4 decimal places")
    private BigDecimal buyingRate;

    @DecimalMin(value = "0.0001", message = "Selling rate must be greater than 0")
    @Digits(integer = 10, fraction = 4, message = "Selling rate must have at most 10 digits and 4 decimal places")
    private BigDecimal sellingRate;

    @Size(max = 20, message = "Source must not exceed 20 characters")
    private String source;

    @Size(max = 20, message = "Uploaded by must not exceed 20 characters")
    private String uploadedBy;
}
