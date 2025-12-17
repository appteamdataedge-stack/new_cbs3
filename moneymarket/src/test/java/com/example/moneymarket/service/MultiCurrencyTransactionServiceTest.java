package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.entity.TranTable.DrCrFlag;
import com.example.moneymarket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MultiCurrencyTransactionService
 * Tests the core MCT functionality: Position GL, WAE, Settlement Gain/Loss
 */
@ExtendWith(MockitoExtension.class)
class MultiCurrencyTransactionServiceTest {

    @Mock
    private WaeMasterRepository waeMasterRepository;

    @Mock
    private TranTableRepository tranTableRepository;

    @Mock
    private GLSetupRepository glSetupRepository;

    @Mock
    private GLMovementRepository glMovementRepository;

    @Mock
    private BalanceService balanceService;

    @Mock
    private SystemDateService systemDateService;

    @InjectMocks
    private MultiCurrencyTransactionService mctService;

    private TranTable usdTransaction;
    private WaeMaster waeMaster;
    private GLSetup positionGL;

    @BeforeEach
    void setUp() {
        // Create a sample USD transaction
        usdTransaction = TranTable.builder()
            .tranId("TEST-USD-001")
            .tranDate(LocalDate.now())
            .valueDate(LocalDate.now())
            .drCrFlag(DrCrFlag.D)
            .tranStatus(TranTable.TranStatus.Posted)
            .accountNo("1000000099001")
            .tranCcy("USD")
            .fcyAmt(new BigDecimal("1000.00"))
            .exchangeRate(new BigDecimal("110.50"))
            .lcyAmt(new BigDecimal("110500.00"))
            .narration("Test USD Transaction")
            .build();

        // Create WAE Master
        waeMaster = WaeMaster.builder()
            .waeId(1L)
            .ccyPair("USD/BDT")
            .waeRate(new BigDecimal("110.0000"))
            .fcyBalance(new BigDecimal("5000.00"))
            .lcyBalance(new BigDecimal("550000.00"))
            .sourceGl("920101001")
            .build();

        // Create Position GL Setup
        positionGL = GLSetup.builder()
            .glNum("920101001")
            .glName("Position USD")
            .layerId(4)
            .build();
    }

    @Test
    void testGetWAERate_Success() {
        // Arrange
        when(waeMasterRepository.findByCcyPair("USD/BDT"))
            .thenReturn(Optional.of(waeMaster));

        // Act
        BigDecimal waeRate = mctService.getWAERate("USD");

        // Assert
        assertNotNull(waeRate);
        assertEquals(new BigDecimal("110.0000"), waeRate);
        verify(waeMasterRepository).findByCcyPair("USD/BDT");
    }

    @Test
    void testGetWAERate_NotFound() {
        // Arrange
        when(waeMasterRepository.findByCcyPair("EUR/BDT"))
            .thenReturn(Optional.empty());

        // Act
        BigDecimal waeRate = mctService.getWAERate("EUR");

        // Assert
        assertEquals(BigDecimal.ZERO, waeRate);
        verify(waeMasterRepository).findByCcyPair("EUR/BDT");
    }

    @Test
    void testGetPositionGL_USD() {
        // Act
        Optional<String> positionGL = mctService.getPositionGL("USD");

        // Assert
        assertTrue(positionGL.isPresent());
        assertEquals("920101001", positionGL.get());
    }

    @Test
    void testGetPositionGL_EUR() {
        // Act
        Optional<String> positionGL = mctService.getPositionGL("EUR");

        // Assert
        assertTrue(positionGL.isPresent());
        assertEquals("920102001", positionGL.get());
    }

    @Test
    void testGetPositionGL_NotConfigured() {
        // Act
        Optional<String> positionGL = mctService.getPositionGL("CHF");

        // Assert
        assertFalse(positionGL.isPresent());
    }

    @Test
    void testIsFCYTransaction_True() {
        // Act
        boolean isFCY = mctService.isFCYTransaction(usdTransaction);

        // Assert
        assertTrue(isFCY);
    }

    @Test
    void testIsFCYTransaction_False_BDT() {
        // Arrange
        TranTable bdtTransaction = TranTable.builder()
            .tranCcy("BDT")
            .build();

        // Act
        boolean isFCY = mctService.isFCYTransaction(bdtTransaction);

        // Assert
        assertFalse(isFCY);
    }

    @Test
    void testIsFCYTransaction_False_Null() {
        // Act
        boolean isFCY = mctService.isFCYTransaction(null);

        // Assert
        assertFalse(isFCY);
    }

    // Note: Testing the full processMultiCurrencyTransaction method would require
    // extensive mocking of repositories and services. This would be better suited
    // for integration tests. The above tests cover the public utility methods.
}
