package com.example.moneymarket.service;

import com.example.moneymarket.dto.AccountBalanceDTO;
import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.OFAcctMaster;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.AcctBalLcyRepository;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.GLSetupRepository;
import com.example.moneymarket.repository.OFAcctMasterRepository;
import com.example.moneymarket.repository.TranTableRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxConversionServiceNostroValidationTest {

    @Mock private TranTableRepository tranTableRepository;
    @Mock private GLSetupRepository glSetupRepository;
    @Mock private CustAcctMasterRepository custAcctMasterRepository;
    @Mock private OFAcctMasterRepository ofAcctMasterRepository;
    @Mock private AcctBalRepository acctBalRepository;
    @Mock private AcctBalLcyRepository acctBalLcyRepository;
    @Mock private SystemDateService systemDateService;
    @Mock private BalanceService balanceService;
    @Mock private ExchangeRateService exchangeRateService;

    @InjectMocks
    private FxConversionService fxConversionService;

    @Test
    void selling_rejectsWhenNostroWouldBecomePositiveCreditBalance() {
        String nostroAcc = "922010001";
        String ccy = "USD";
        String customerAcc = "110101000001";

        OFAcctMaster nostro = new OFAcctMaster();
        nostro.setAccountNo(nostroAcc);
        nostro.setAccountCcy(ccy);
        nostro.setGlNum("922010001");

        when(ofAcctMasterRepository.findById(nostroAcc)).thenReturn(Optional.of(nostro));

        CustAcctMaster cust = new CustAcctMaster();
        cust.setAccountNo(customerAcc);
        cust.setAccountCcy("BDT");
        cust.setGlNum("110101000001");
        when(custAcctMasterRepository.findById(customerAcc)).thenReturn(Optional.of(cust));

        // In this CBS, office asset (Nostro) debit balance is represented as a negative available balance.
        // If current = -500 and we credit 600 (SELLING), resulting = +100 => invalid.
        AccountBalanceDTO bal = new AccountBalanceDTO();
        bal.setAccountCcy(ccy);
        bal.setAvailableBalance(new BigDecimal("-500.00"));
        when(balanceService.getComputedAccountBalance(nostroAcc)).thenReturn(bal);

        assertThrows(BusinessException.class, () ->
                fxConversionService.createFxConversion(
                        "SELLING",
                        customerAcc,
                        nostroAcc,
                        ccy,
                        new BigDecimal("600.00"),
                        new BigDecimal("110.00"),
                        "test",
                        "u1"
                )
        );
    }
}

