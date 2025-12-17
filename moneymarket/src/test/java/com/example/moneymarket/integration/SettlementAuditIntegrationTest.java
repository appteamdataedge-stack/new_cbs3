package com.example.moneymarket.integration;

import com.example.moneymarket.entity.SettlementGainLoss;
import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.entity.WaeMaster;
import com.example.moneymarket.repository.SettlementGainLossRepository;
import com.example.moneymarket.repository.WaeMasterRepository;
import com.example.moneymarket.service.CurrencyValidationService;
import com.example.moneymarket.service.MultiCurrencyTransactionService;
import com.example.moneymarket.service.SettlementAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Settlement Audit Logging
 * Tests the complete flow from transaction to audit record creation and alerting
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SettlementAuditIntegrationTest {

    @Autowired
    private MultiCurrencyTransactionService mctService;

    @Autowired
    private SettlementGainLossRepository settlementRepository;

    @Autowired
    private WaeMasterRepository waeMasterRepository;

    @Autowired
    private SettlementAlertService alertService;

    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        testDate = LocalDate.now();

        // Clean up any existing test data
        settlementRepository.deleteAll();

        // Set up WAE Master with initial position
        WaeMaster waeMaster = WaeMaster.builder()
            .ccyPair("USD/BDT")
            .waeRate(new BigDecimal("110.0000"))
            .fcyBalance(new BigDecimal("5000.00"))
            .lcyBalance(new BigDecimal("550000.00"))
            .sourceGl("920101001")
            .build();
        waeMasterRepository.save(waeMaster);
    }

    @Test
    void testSettlementGainAuditLogging() {
        // Given: A SELL transaction with rate above WAE (Pattern 3 - GAIN)
        TranTable sellTransaction = createSellTransaction(
            new BigDecimal("500.00"),      // FCY amount
            new BigDecimal("110.50"),      // Deal rate (above WAE 110.00)
            new BigDecimal("55250.00")     // LCY amount
        );

        List<TranTable> transactions = List.of(sellTransaction);

        // When: Process multi-currency transaction
        mctService.processMultiCurrencyTransaction(
            transactions,
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );

        // Then: Settlement gain record should be created
        List<SettlementGainLoss> settlements = settlementRepository.findByTranDate(testDate);

        assertFalse(settlements.isEmpty(), "Settlement record should be created");
        assertEquals(1, settlements.size(), "Exactly one settlement record expected");

        SettlementGainLoss settlement = settlements.get(0);

        // Verify settlement details
        assertEquals("USD", settlement.getCurrency());
        assertEquals("GAIN", settlement.getSettlementType());
        assertEquals(new BigDecimal("250.00"), settlement.getSettlementAmt()); // 500 × (110.50 - 110.00)
        assertEquals(new BigDecimal("500.00"), settlement.getFcyAmt());
        assertEquals(new BigDecimal("110.5000"), settlement.getDealRate());
        assertEquals(new BigDecimal("110.0000"), settlement.getWaeRate());
        assertEquals("POSTED", settlement.getStatus());

        // Verify narration contains calculation details
        assertNotNull(settlement.getNarration());
        assertTrue(settlement.getNarration().contains("Settlement GAIN"));
        assertTrue(settlement.getNarration().contains("500.00"));
    }

    @Test
    void testSettlementLossAuditLogging() {
        // Given: A SELL transaction with rate below WAE (Pattern 4 - LOSS)
        TranTable sellTransaction = createSellTransaction(
            new BigDecimal("500.00"),      // FCY amount
            new BigDecimal("109.50"),      // Deal rate (below WAE 110.00)
            new BigDecimal("54750.00")     // LCY amount
        );

        List<TranTable> transactions = List.of(sellTransaction);

        // When: Process multi-currency transaction
        mctService.processMultiCurrencyTransaction(
            transactions,
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );

        // Then: Settlement loss record should be created
        List<SettlementGainLoss> settlements = settlementRepository.findByTranDate(testDate);

        assertFalse(settlements.isEmpty(), "Settlement record should be created");

        SettlementGainLoss settlement = settlements.get(0);

        // Verify settlement details
        assertEquals("LOSS", settlement.getSettlementType());
        assertEquals(new BigDecimal("250.00"), settlement.getSettlementAmt()); // 500 × (110.00 - 109.50)
        assertEquals("POSTED", settlement.getStatus());
    }

    @Test
    void testNoSettlementAtWAERate() {
        // Given: A SELL transaction at exact WAE rate (Pattern 2 - No gain/loss)
        TranTable sellTransaction = createSellTransaction(
            new BigDecimal("500.00"),      // FCY amount
            new BigDecimal("110.00"),      // Deal rate (exactly WAE)
            new BigDecimal("55000.00")     // LCY amount
        );

        List<TranTable> transactions = List.of(sellTransaction);

        // When: Process multi-currency transaction
        mctService.processMultiCurrencyTransaction(
            transactions,
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );

        // Then: No settlement record should be created (no gain/loss)
        List<SettlementGainLoss> settlements = settlementRepository.findByTranDate(testDate);

        assertTrue(settlements.isEmpty(), "No settlement record expected for transaction at WAE rate");
    }

    @Test
    void testNoBuyTransactionSettlement() {
        // Given: A BUY transaction (Pattern 1)
        TranTable buyTransaction = createBuyTransaction(
            new BigDecimal("1000.00"),     // FCY amount
            new BigDecimal("110.50"),      // Deal rate
            new BigDecimal("110500.00")    // LCY amount
        );

        List<TranTable> transactions = List.of(buyTransaction);

        // When: Process multi-currency transaction
        mctService.processMultiCurrencyTransaction(
            transactions,
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );

        // Then: No settlement record (BUY doesn't have settlement gain/loss)
        List<SettlementGainLoss> settlements = settlementRepository.findByTranDate(testDate);

        assertTrue(settlements.isEmpty(), "No settlement record expected for BUY transaction");
    }

    @Test
    void testLargeSettlementAlertGeneration() {
        // Given: A SELL transaction with large gain (above threshold)
        TranTable sellTransaction = createSellTransaction(
            new BigDecimal("10000.00"),    // Large FCY amount
            new BigDecimal("120.00"),      // Deal rate significantly above WAE
            new BigDecimal("1200000.00")   // LCY amount
        );

        List<TranTable> transactions = List.of(sellTransaction);

        // When: Process multi-currency transaction
        mctService.processMultiCurrencyTransaction(
            transactions,
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );

        // Then: Settlement record created and alert generated
        List<SettlementGainLoss> settlements = settlementRepository.findByTranDate(testDate);

        assertFalse(settlements.isEmpty());

        SettlementGainLoss settlement = settlements.get(0);

        // Verify large settlement amount
        BigDecimal expectedGain = new BigDecimal("100000.00"); // 10000 × (120.00 - 110.00)
        assertEquals(expectedGain, settlement.getSettlementAmt());

        // Verify alert would be generated
        SettlementAlertService.SettlementAlert alert = alertService.checkForAlert(settlement);
        assertNotNull(alert, "Alert should be generated for large settlement");
        assertEquals(SettlementAlertService.AlertSeverity.HIGH, alert.getSeverity());
    }

    @Test
    void testMultipleSettlementsInOneDay() {
        // Given: Multiple SELL transactions in one day
        TranTable sell1 = createSellTransaction(
            "T001-1",
            new BigDecimal("300.00"),
            new BigDecimal("111.00"),
            new BigDecimal("33300.00")
        );

        TranTable sell2 = createSellTransaction(
            "T002-1",
            new BigDecimal("200.00"),
            new BigDecimal("109.00"),
            new BigDecimal("21800.00")
        );

        // When: Process both transactions
        mctService.processMultiCurrencyTransaction(
            List.of(sell1),
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );

        mctService.processMultiCurrencyTransaction(
            List.of(sell2),
            CurrencyValidationService.TransactionType.BDT_USD_MIX
        );

        // Then: Two settlement records created
        List<SettlementGainLoss> settlements = settlementRepository.findByTranDate(testDate);

        assertEquals(2, settlements.size(), "Two settlement records expected");

        // Verify one gain and one loss
        long gainCount = settlements.stream()
            .filter(s -> "GAIN".equals(s.getSettlementType()))
            .count();
        long lossCount = settlements.stream()
            .filter(s -> "LOSS".equals(s.getSettlementType()))
            .count();

        assertEquals(1, gainCount, "One GAIN expected");
        assertEquals(1, lossCount, "One LOSS expected");
    }

    // Helper methods

    private TranTable createSellTransaction(BigDecimal fcyAmt, BigDecimal exchangeRate, BigDecimal lcyAmt) {
        return createSellTransaction("TEST-SELL-" + System.currentTimeMillis() + "-1",
            fcyAmt, exchangeRate, lcyAmt);
    }

    private TranTable createSellTransaction(String tranId, BigDecimal fcyAmt,
                                           BigDecimal exchangeRate, BigDecimal lcyAmt) {
        return TranTable.builder()
            .tranId(tranId)
            .tranDate(testDate)
            .valueDate(testDate)
            .drCrFlag(TranTable.DrCrFlag.D)  // DEBIT = SELL
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo("1000000099001")
            .tranCcy("USD")
            .fcyAmt(fcyAmt)
            .exchangeRate(exchangeRate)
            .lcyAmt(lcyAmt)
            .debitAmount(lcyAmt)
            .creditAmount(BigDecimal.ZERO)
            .narration("Test SELL transaction")
            .build();
    }

    private TranTable createBuyTransaction(BigDecimal fcyAmt, BigDecimal exchangeRate, BigDecimal lcyAmt) {
        return TranTable.builder()
            .tranId("TEST-BUY-" + System.currentTimeMillis() + "-1")
            .tranDate(testDate)
            .valueDate(testDate)
            .drCrFlag(TranTable.DrCrFlag.C)  // CREDIT = BUY
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo("1000000099001")
            .tranCcy("USD")
            .fcyAmt(fcyAmt)
            .exchangeRate(exchangeRate)
            .lcyAmt(lcyAmt)
            .debitAmount(BigDecimal.ZERO)
            .creditAmount(lcyAmt)
            .narration("Test BUY transaction")
            .build();
    }
}
