package com.example.moneymarket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseDTO {

    private String tranId;
    private LocalDate tranDate;
    private LocalDate valueDate;
    private String narration;
    private List<TransactionLineResponseDTO> lines;
    private boolean balanced;
    private String status;

    /** Settlement gain/loss in LCY (BDT) when posting FCY settlement. Positive = gain, negative = loss. */
    private BigDecimal settlementGainLoss;
    /** "GAIN" or "LOSS" when settlementGainLoss is non-zero. */
    private String settlementGainLossType;
}
