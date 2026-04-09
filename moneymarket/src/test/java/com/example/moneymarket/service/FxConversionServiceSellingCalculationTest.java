package com.example.moneymarket.service;

import com.example.moneymarket.repository.AcctBalLcyRepository;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.GLSetupRepository;
import com.example.moneymarket.repository.GLBalanceRepository;
import com.example.moneymarket.repository.OFAcctMasterRepository;
import com.example.moneymarket.repository.TranTableRepository;
import com.example.moneymarket.repository.FxPositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxConversionServiceSellingCalculationTest {

    @Mock
    private TranTableRepository tranTableRepository;
    @Mock
    private GLSetupRepository glSetupRepository;
    @Mock
    private CustAcctMasterRepository custAcctMasterRepository;
    @Mock
    private OFAcctMasterRepository ofAcctMasterRepository;
    @Mock
    private AcctBalRepository acctBalRepository;
    @Mock
    private AcctBalLcyRepository acctBalLcyRepository;
    @Mock
    private SystemDateService systemDateService;
    @Mock
    private BalanceService balanceService;
    @Mock
    private ExchangeRateService exchangeRateService;
    @Mock
    private FxPositionRepository fxPositionRepository;
    @Mock
    private GLBalanceRepository glBalanceRepository;

    @InjectMocks
    private FxConversionService fxConversionService;

    @Test
    void calculatePositionWae2OnTheFly_returnsNullWhenFcyZero() {
        LocalDate today = LocalDate.of(2026, 4, 9);
        when(systemDateService.getSystemDate()).thenReturn(today);
        when(fxPositionRepository.findByTranDateAndPositionGlNumAndPositionCcy(today.minusDays(1), "920101002", "USD"))
                .thenReturn(Optional.empty());
        when(tranTableRepository.sumVerifiedDebitFcyByGlAndCcyAndDate("920101002", "USD", today))
                .thenReturn(BigDecimal.ZERO);
        when(tranTableRepository.sumVerifiedCreditFcyByGlAndCcyAndDate("920101002", "USD", today))
                .thenReturn(BigDecimal.ZERO);

        assertNull(fxConversionService.calculatePositionWae2OnTheFly("USD"));
    }

    @Test
    void sellingScenario1_bothGain_calculationsAndBalancedPosting() {
        BigDecimal fcyAmount = new BigDecimal("1000");
        BigDecimal dealRate = new BigDecimal("110.00");
        BigDecimal wae1 = new BigDecimal("108.00");
        BigDecimal wae2 = new BigDecimal("109.00");

        BigDecimal lcyEquiv = fcyAmount.multiply(dealRate).setScale(2);
        BigDecimal lcyEquiv1 = fcyAmount.multiply(wae2).setScale(2);
        BigDecimal lcyEquiv2 = fcyAmount.multiply(wae1).setScale(2);

        FxConversionService.SellingGainLossBreakdown breakdown =
                fxConversionService.calculateSellingGainLossBreakdown(fcyAmount, dealRate, wae1, wae2);
        BigDecimal computedNet = breakdown.net();
        BigDecimal postedNet = fxConversionService.calculateBalancedGainLossForSelling(lcyEquiv, lcyEquiv1, lcyEquiv2);

        assertEquals(new BigDecimal("1000.00"), breakdown.nostroComponent());
        assertEquals(new BigDecimal("1000.00"), breakdown.positionComponent());
        assertEquals(new BigDecimal("2000.00"), computedNet);
        assertEquals(new BigDecimal("2000.00"), postedNet);
    }

    @Test
    void sellingScenario2_bothLoss_calculationsAndBalancedPosting() {
        BigDecimal fcyAmount = new BigDecimal("1000");
        BigDecimal dealRate = new BigDecimal("110.00");
        BigDecimal wae1 = new BigDecimal("112.00");
        BigDecimal wae2 = new BigDecimal("111.00");

        BigDecimal lcyEquiv = fcyAmount.multiply(dealRate).setScale(2);
        BigDecimal lcyEquiv1 = fcyAmount.multiply(wae2).setScale(2);
        BigDecimal lcyEquiv2 = fcyAmount.multiply(wae1).setScale(2);

        FxConversionService.SellingGainLossBreakdown breakdown =
                fxConversionService.calculateSellingGainLossBreakdown(fcyAmount, dealRate, wae1, wae2);
        BigDecimal computedNet = breakdown.net();
        BigDecimal postedNet = fxConversionService.calculateBalancedGainLossForSelling(lcyEquiv, lcyEquiv1, lcyEquiv2);

        assertEquals(new BigDecimal("-1000.00"), breakdown.nostroComponent());
        assertEquals(new BigDecimal("-1000.00"), breakdown.positionComponent());
        assertEquals(new BigDecimal("-2000.00"), computedNet);
        assertEquals(new BigDecimal("-2000.00"), postedNet);
    }

    @Test
    void sellingScenario3_nostroGainPositionLoss_calculationsAndBalancedPosting() {
        BigDecimal fcyAmount = new BigDecimal("1000");
        BigDecimal dealRate = new BigDecimal("110.00");
        BigDecimal wae1 = new BigDecimal("108.00");
        BigDecimal wae2 = new BigDecimal("111.00");

        BigDecimal lcyEquiv = fcyAmount.multiply(dealRate).setScale(2);
        BigDecimal lcyEquiv1 = fcyAmount.multiply(wae2).setScale(2);
        BigDecimal lcyEquiv2 = fcyAmount.multiply(wae1).setScale(2);

        FxConversionService.SellingGainLossBreakdown breakdown =
                fxConversionService.calculateSellingGainLossBreakdown(fcyAmount, dealRate, wae1, wae2);
        BigDecimal computedNet = breakdown.net();
        BigDecimal postedNet = fxConversionService.calculateBalancedGainLossForSelling(lcyEquiv, lcyEquiv1, lcyEquiv2);

        assertEquals(new BigDecimal("3000.00"), breakdown.nostroComponent());
        assertEquals(new BigDecimal("-1000.00"), breakdown.positionComponent());
        assertEquals(new BigDecimal("2000.00"), computedNet);
        assertEquals(new BigDecimal("2000.00"), postedNet);
    }

    @Test
    void sellingScenario4_nostroLossPositionGain_calculationsAndBalancedPosting() {
        BigDecimal fcyAmount = new BigDecimal("1000");
        BigDecimal dealRate = new BigDecimal("110.00");
        BigDecimal wae1 = new BigDecimal("112.00");
        BigDecimal wae2 = new BigDecimal("109.00");

        BigDecimal lcyEquiv = fcyAmount.multiply(dealRate).setScale(2);
        BigDecimal lcyEquiv1 = fcyAmount.multiply(wae2).setScale(2);
        BigDecimal lcyEquiv2 = fcyAmount.multiply(wae1).setScale(2);

        FxConversionService.SellingGainLossBreakdown breakdown =
                fxConversionService.calculateSellingGainLossBreakdown(fcyAmount, dealRate, wae1, wae2);
        BigDecimal computedNet = breakdown.net();
        BigDecimal postedNet = fxConversionService.calculateBalancedGainLossForSelling(lcyEquiv, lcyEquiv1, lcyEquiv2);

        assertEquals(new BigDecimal("-3000.00"), breakdown.nostroComponent());
        assertEquals(new BigDecimal("1000.00"), breakdown.positionComponent());
        assertEquals(new BigDecimal("-2000.00"), computedNet);
        assertEquals(new BigDecimal("-2000.00"), postedNet);
    }
}
