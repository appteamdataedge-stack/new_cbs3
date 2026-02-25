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

        // If all legs are in the same FCY (e.g. both USD): validate FCY totals only (LCY may differ → settlement gain/loss).
        // If BDT or mixed: validate LCY totals.
        boolean allSameFcy = lines.stream().allMatch(l -> l.getTranCcy() != null && !"BDT".equals(l.getTranCcy()))
                && lines.stream().map(TransactionLineDTO::getTranCcy).distinct().count() == 1;

        if (allSameFcy) {
            BigDecimal totalDebitFcy = lines.stream()
                    .filter(line -> line.getDrCrFlag() == DrCrFlag.D)
                    .map(TransactionLineDTO::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCreditFcy = lines.stream()
                    .filter(line -> line.getDrCrFlag() == DrCrFlag.C)
                    .map(TransactionLineDTO::getFcyAmt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalDebitFcy.compareTo(totalCreditFcy) != 0) {
                errors.reject("imbalance", "FCY debit total must equal FCY credit total. Please correct the entries.");
            }
        } else {
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
