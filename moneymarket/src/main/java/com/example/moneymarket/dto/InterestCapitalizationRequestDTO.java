package com.example.moneymarket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for Interest Capitalization
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterestCapitalizationRequestDTO {

    @NotBlank(message = "Account number is required")
    private String accountNo;
    
    // Optional narration for the capitalization transaction
    private String narration;
}
