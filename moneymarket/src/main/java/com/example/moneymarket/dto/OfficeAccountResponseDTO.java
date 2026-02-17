package com.example.moneymarket.dto;

import com.example.moneymarket.entity.OFAcctMaster.AccountStatus;
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
public class OfficeAccountResponseDTO {

    private String accountNo;
    private Integer subProductId;
    private String subProductName;
    private String glNum;
    private String acctName;
    private LocalDate dateOpening;
    private LocalDate dateClosure;
    private String branchCode;
    private AccountStatus accountStatus;
    private Boolean reconciliationRequired;

    // Balance & currency info (real-time, includes today's transactions)
    private String accountCcy;
    private BigDecimal currentBalance;
    private BigDecimal availableBalance;
    private BigDecimal computedBalance;
    private BigDecimal availableBalanceLcy;
    private BigDecimal computedBalanceLcy;
    private BigDecimal wae; // Weighted Average Exchange Rate = availableBalanceLcy / availableBalance (FCY only)
}
