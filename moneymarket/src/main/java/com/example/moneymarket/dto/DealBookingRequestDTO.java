package com.example.moneymarket.dto;

import jakarta.validation.constraints.*;
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
public class DealBookingRequestDTO {

    @NotNull(message = "Customer ID is mandatory")
    private Integer custId;

    @NotBlank(message = "Operative Account Number is mandatory")
    @Size(max = 30, message = "Operative Account Number cannot exceed 30 characters")
    private String operativeAccountNo;

    /** L = Liability (Term Deposit), A = Asset (Loan) */
    @NotBlank(message = "Deal Type is mandatory (L=Liability, A=Asset)")
    @Pattern(regexp = "[LA]", message = "Deal Type must be L (Liability) or A (Asset)")
    private String dealType;

    /** C = Compounding, N = Non-compounding */
    @NotBlank(message = "Interest Type is mandatory (C=Compounding, N=Non-compounding)")
    @Pattern(regexp = "[CN]", message = "Interest Type must be C (Compounding) or N (Non-compounding)")
    private String interestType;

    /** Required when interestType = C. Interval in days between compounding events. */
    @Min(value = 1, message = "Compounding Frequency must be at least 1 day")
    private Integer compoundingFrequency;

    @NotNull(message = "Deal Amount is mandatory")
    @DecimalMin(value = "0.01", message = "Deal Amount must be greater than zero")
    @Digits(integer = 16, fraction = 2, message = "Deal Amount format invalid")
    private BigDecimal dealAmount;

    @NotBlank(message = "Currency Code is mandatory")
    @Size(min = 3, max = 3, message = "Currency Code must be exactly 3 characters")
    private String currencyCode;

    @NotNull(message = "Value Date is mandatory")
    private LocalDate valueDate;

    @NotNull(message = "Tenor is mandatory")
    @Min(value = 1, message = "Tenor must be at least 1 day")
    @Max(value = 3650, message = "Tenor cannot exceed 3650 days (10 years)")
    private Integer tenor;

    @Size(max = 100, message = "Narration cannot exceed 100 characters")
    private String narration;

    @NotBlank(message = "Branch Code is mandatory")
    @Size(max = 10, message = "Branch Code cannot exceed 10 characters")
    private String branchCode;
}
