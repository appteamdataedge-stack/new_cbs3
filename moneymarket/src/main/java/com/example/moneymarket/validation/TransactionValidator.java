package com.example.moneymarket.validation;

import com.example.moneymarket.dto.TransactionLineDTO;
import com.example.moneymarket.dto.TransactionRequestDTO;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.OFAcctMaster;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.OFAcctMasterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.math.BigDecimal;
import java.util.List;

@Component
public class TransactionValidator implements Validator {

    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;

    @Autowired
    public TransactionValidator(CustAcctMasterRepository custAcctMasterRepository,
                               OFAcctMasterRepository ofAcctMasterRepository) {
        this.custAcctMasterRepository = custAcctMasterRepository;
        this.ofAcctMasterRepository = ofAcctMasterRepository;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return TransactionRequestDTO.class.equals(clazz);
    }

    /**
     * Get GL number for an account (customer or office). Returns null if account not found.
     */
    private String getGLNumber(String accountNo) {
        return custAcctMasterRepository.findById(accountNo)
                .map(CustAcctMaster::getGlNum)
                .orElseGet(() -> ofAcctMasterRepository.findById(accountNo)
                        .map(OFAcctMaster::getGlNum)
                        .orElse(null));
    }

    /**
     * Settlement = 2 lines, same FCY (not BDT), one Liability Debit + one Asset Credit.
     * GL 1xxx = Liability, 2xxx = Asset.
     */
    private boolean isSettlementTransaction(List<TransactionLineDTO> lines) {
        if (lines == null || lines.size() != 2) return false;
        String ccy = lines.get(0).getTranCcy();
        if (ccy == null || "BDT".equals(ccy)) return false;
        if (!ccy.equals(lines.get(1).getTranCcy())) return false;
        String gl0 = getGLNumber(lines.get(0).getAccountNo());
        String gl1 = getGLNumber(lines.get(1).getAccountNo());
        if (gl0 == null || gl1 == null) return false;
        boolean liability0 = gl0.startsWith("1");
        boolean asset0 = gl0.startsWith("2");
        boolean liability1 = gl1.startsWith("1");
        boolean asset1 = gl1.startsWith("2");
        boolean liabilityDr0 = liability0 && lines.get(0).getDrCrFlag() == DrCrFlag.D;
        boolean assetCr0 = asset0 && lines.get(0).getDrCrFlag() == DrCrFlag.C;
        boolean liabilityDr1 = liability1 && lines.get(1).getDrCrFlag() == DrCrFlag.D;
        boolean assetCr1 = asset1 && lines.get(1).getDrCrFlag() == DrCrFlag.C;
        return (liabilityDr0 && assetCr1) || (liabilityDr1 && assetCr0);
    }

    @Override
    public void validate(Object target, Errors errors) {
        TransactionRequestDTO transaction = (TransactionRequestDTO) target;
        List<TransactionLineDTO> lines = transaction.getLines();

        if (lines == null || lines.isEmpty()) {
            errors.rejectValue("lines", "empty.lines", "Transaction must have at least one line");
            return;
        }

        // Validate that all account numbers exist (check both customer and office accounts)
        for (TransactionLineDTO line : lines) {
            boolean accountExists = custAcctMasterRepository.existsById(line.getAccountNo()) ||
                    ofAcctMasterRepository.existsById(line.getAccountNo());
            if (!accountExists) {
                errors.rejectValue("lines", "invalid.accountNo",
                        "Account number " + line.getAccountNo() + " does not exist");
                return;
            }
        }

        // Detect settlement: Liability Dr + Asset Cr, same FCY (e.g. USD)
        boolean isSettlement = isSettlementTransaction(lines);

        if (isSettlement) {
            // For settlement: validate FCY amounts match (LCY may differ → gain/loss)
            BigDecimal totalDebitFcy = lines.stream()
                    .filter(line -> line.getDrCrFlag() == DrCrFlag.D)
                    .map(TransactionLineDTO::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCreditFcy = lines.stream()
                    .filter(line -> line.getDrCrFlag() == DrCrFlag.C)
                    .map(TransactionLineDTO::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalDebitFcy.compareTo(totalCreditFcy) != 0) {
                errors.reject("imbalance", "FCY amounts must match for settlement transactions.");
            }
            // Skip LCY validation for settlement
        } else {
            // Non-settlement: debit must equal credit in LCY
            BigDecimal totalDebits = lines.stream()
                    .filter(line -> line.getDrCrFlag() == DrCrFlag.D)
                    .map(TransactionLineDTO::getLcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredits = lines.stream()
                    .filter(line -> line.getDrCrFlag() == DrCrFlag.C)
                    .map(TransactionLineDTO::getLcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalDebits.compareTo(totalCredits) != 0) {
                errors.reject("imbalance",
                        "Debit amount does not equal credit amount. Please correct the entries.");
            }
        }
    }
}
