package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.*;
import com.example.moneymarket.service.SubProductGLReconciliationService.SubProductDrillDownDetails;
import com.example.moneymarket.service.SubProductGLReconciliationService.SubProductReconciliationEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SubProductGLReconciliationService
 */
class SubProductGLReconciliationServiceTest {

    @Mock
    private SubProdMasterRepository subProdMasterRepository;

    @Mock
    private CustAcctMasterRepository custAcctMasterRepository;

    @Mock
    private OFAcctMasterRepository ofAcctMasterRepository;

    @Mock
    private AcctBalRepository acctBalRepository;

    @Mock
    private GLBalanceRepository glBalanceRepository;

    @Mock
    private GLSetupRepository glSetupRepository;

    @Mock
    private GLMovementRepository glMovementRepository;

    @Mock
    private GLMovementAccrualRepository glMovementAccrualRepository;

    @Mock
    private SystemDateService systemDateService;

    @InjectMocks
    private SubProductGLReconciliationService reconciliationService;

    private LocalDate testDate;
    private SubProdMaster testSubProduct;
    private CustAcctMaster testCustomerAccount;
    private OFAcctMaster testOfficeAccount;
    private AcctBal testAccountBalance;
    private GLBalance testGLBalance;
    private GLSetup testGLSetup;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        testDate = LocalDate.of(2024, 1, 15);

        // Setup test sub-product
        testSubProduct = SubProdMaster.builder()
                .subProductId(1)
                .subProductCode("SP001")
                .subProductName("Savings Account")
                .cumGLNum("1001001")
                .subProductStatus(SubProdMaster.SubProductStatus.Active)
                .build();

        // Setup test customer account
        testCustomerAccount = CustAcctMaster.builder()
                .accountNo("1234567890123")
                .acctName("John Doe Savings")
                .subProduct(testSubProduct)
                .accountCcy("BDT")
                .accountStatus(CustAcctMaster.AccountStatus.Active)
                .build();

        // Setup test office account
        testOfficeAccount = OFAcctMaster.builder()
                .accountNo("9876543210987")
                .acctName("Branch Operating Account")
                .subProduct(testSubProduct)
                .accountCcy("BDT")
                .accountStatus(OFAcctMaster.AccountStatus.Active)
                .build();

        // Setup test account balance
        testAccountBalance = AcctBal.builder()
                .accountNo("1234567890123")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("50000.00"))
                .accountCcy("BDT")
                .build();

        // Setup test GL balance
        testGLBalance = GLBalance.builder()
                .glNum("1001001")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("50000.00"))
                .build();

        // Setup test GL setup
        testGLSetup = GLSetup.builder()
                .glNum("1001001")
                .glName("Customer Savings Accounts")
                .build();
    }

    @Test
    void testGenerateReconciliationReport_MatchedBalance() {
        // Given
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));
        when(glSetupRepository.findById("1001001")).thenReturn(Optional.of(testGLSetup));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList());
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.of(testGLBalance));

        // When
        byte[] report = reconciliationService.generateReconciliationReport(testDate);

        // Then
        assertNotNull(report);
        assertTrue(report.length > 0);
        verify(subProdMasterRepository).findAllActiveSubProducts();
        verify(glBalanceRepository).findByGlNumAndTranDate("1001001", testDate);
    }

    @Test
    void testGenerateReconciliationReport_UnmatchedBalance() {
        // Given
        GLBalance unmatchedGLBalance = GLBalance.builder()
                .glNum("1001001")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("45000.00")) // Different from account balance
                .build();

        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));
        when(glSetupRepository.findById("1001001")).thenReturn(Optional.of(testGLSetup));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList());
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.of(unmatchedGLBalance));

        // When
        byte[] report = reconciliationService.generateReconciliationReport(testDate);

        // Then
        assertNotNull(report);
        assertTrue(report.length > 0);
    }

    @Test
    void testGenerateReconciliationReport_WithFCYAccounts() {
        // Given
        CustAcctMaster fcyAccount = CustAcctMaster.builder()
                .accountNo("1234567890124")
                .acctName("Jane Doe USD Account")
                .subProduct(testSubProduct)
                .accountCcy("USD")
                .accountStatus(CustAcctMaster.AccountStatus.Active)
                .build();

        AcctBal fcyAccountBalance = AcctBal.builder()
                .accountNo("1234567890124")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("120000.00")) // LCY equivalent
                .accountCcy("USD")
                .build();

        GLBalance totalGLBalance = GLBalance.builder()
                .glNum("1001001")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("170000.00")) // 50000 + 120000
                .build();

        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));
        when(glSetupRepository.findById("1001001")).thenReturn(Optional.of(testGLSetup));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount, fcyAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList());
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890124", testDate))
                .thenReturn(Optional.of(fcyAccountBalance));
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.of(totalGLBalance));

        // When
        byte[] report = reconciliationService.generateReconciliationReport(testDate);

        // Then
        assertNotNull(report);
        assertTrue(report.length > 0);
    }

    @Test
    void testGetDrillDownDetails_Success() {
        // Given
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findBySubProductCode("SP001"))
                .thenReturn(Optional.of(testSubProduct));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testOfficeAccount));
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        
        AcctBal officeAccountBalance = AcctBal.builder()
                .accountNo("9876543210987")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("30000.00"))
                .accountCcy("BDT")
                .build();
        when(acctBalRepository.findByAccountNoAndTranDate("9876543210987", testDate))
                .thenReturn(Optional.of(officeAccountBalance));
        
        GLBalance totalGLBalance = GLBalance.builder()
                .glNum("1001001")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("80000.00"))
                .build();
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.of(totalGLBalance));
        when(glMovementRepository.findByGlSetup_GlNumAndTranDateBetween("1001001", testDate, testDate))
                .thenReturn(Arrays.asList());
        when(glMovementAccrualRepository.findByGlSetupGlNumAndAccrualDateBetween("1001001", testDate, testDate))
                .thenReturn(Arrays.asList());

        // When
        SubProductDrillDownDetails details = reconciliationService.getDrillDownDetails("SP001", testDate);

        // Then
        assertNotNull(details);
        assertEquals("SP001", details.getSubProductCode());
        assertEquals("Savings Account", details.getSubProductName());
        assertEquals("1001001", details.getGlNum());
        assertEquals(testDate, details.getReportDate());
        assertEquals(2, details.getAccountBalances().size());
        assertEquals(new BigDecimal("80000.00"), details.getTotalAccountBalance());
        assertEquals(new BigDecimal("80000.00"), details.getGlBalance());
        assertEquals(BigDecimal.ZERO, details.getDifference());
    }

    @Test
    void testGetDrillDownDetails_SubProductNotFound() {
        // Given
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findBySubProductCode("INVALID"))
                .thenReturn(Optional.empty());

        // When & Then
        assertThrows(BusinessException.class, () -> {
            reconciliationService.getDrillDownDetails("INVALID", testDate);
        });
    }

    @Test
    void testGetDrillDownDetails_WithFCYAccounts() {
        // Given
        CustAcctMaster fcyAccount = CustAcctMaster.builder()
                .accountNo("1234567890124")
                .acctName("Jane Doe EUR Account")
                .subProduct(testSubProduct)
                .accountCcy("EUR")
                .accountStatus(CustAcctMaster.AccountStatus.Active)
                .build();

        AcctBal fcyAccountBalance = AcctBal.builder()
                .accountNo("1234567890124")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("150000.00")) // LCY equivalent
                .accountCcy("EUR")
                .build();

        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findBySubProductCode("SP001"))
                .thenReturn(Optional.of(testSubProduct));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount, fcyAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList());
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890124", testDate))
                .thenReturn(Optional.of(fcyAccountBalance));
        
        GLBalance totalGLBalance = GLBalance.builder()
                .glNum("1001001")
                .tranDate(testDate)
                .currentBalance(new BigDecimal("200000.00"))
                .build();
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.of(totalGLBalance));
        when(glMovementRepository.findByGlSetup_GlNumAndTranDateBetween("1001001", testDate, testDate))
                .thenReturn(Arrays.asList());
        when(glMovementAccrualRepository.findByGlSetupGlNumAndAccrualDateBetween("1001001", testDate, testDate))
                .thenReturn(Arrays.asList());

        // When
        SubProductDrillDownDetails details = reconciliationService.getDrillDownDetails("SP001", testDate);

        // Then
        assertNotNull(details);
        assertTrue(details.isFcyAccountsExist());
        assertNotNull(details.getFcyBalances());
        assertTrue(details.getFcyBalances().contains("EUR"));
        assertEquals(2, details.getAccountBalances().size());
        
        // Verify one account is BDT and one is EUR
        long bdtCount = details.getAccountBalances().stream()
                .filter(ab -> "BDT".equals(ab.getCurrency()))
                .count();
        long eurCount = details.getAccountBalances().stream()
                .filter(ab -> "EUR".equals(ab.getCurrency()))
                .count();
        assertEquals(1, bdtCount);
        assertEquals(1, eurCount);
    }

    @Test
    void testGenerateReconciliationReport_NoActiveSubProducts() {
        // Given
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList());

        // When
        byte[] report = reconciliationService.generateReconciliationReport(testDate);

        // Then
        assertNotNull(report);
        assertTrue(report.length > 0);
        verify(subProdMasterRepository).findAllActiveSubProducts();
    }

    @Test
    void testGenerateReconciliationReport_MissingGLBalance() {
        // Given
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));
        when(glSetupRepository.findById("1001001")).thenReturn(Optional.of(testGLSetup));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList());
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.empty()); // No GL balance

        // When
        byte[] report = reconciliationService.generateReconciliationReport(testDate);

        // Then
        assertNotNull(report);
        assertTrue(report.length > 0);
        // Should handle missing GL balance gracefully (default to zero)
    }

    @Test
    void testGenerateReconciliationReport_WithNullDate() {
        // Given
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));
        when(glSetupRepository.findById("1001001")).thenReturn(Optional.of(testGLSetup));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList());
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.of(testGLBalance));

        // When
        byte[] report = reconciliationService.generateReconciliationReport(null);

        // Then
        assertNotNull(report);
        assertTrue(report.length > 0);
        verify(systemDateService).getSystemDate(); // Should use system date when null
    }

    @Test
    void testGetDrillDownDetails_WithNullDate() {
        // Given
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(subProdMasterRepository.findBySubProductCode("SP001"))
                .thenReturn(Optional.of(testSubProduct));
        when(custAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList(testCustomerAccount));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1))
                .thenReturn(Arrays.asList());
        when(acctBalRepository.findByAccountNoAndTranDate("1234567890123", testDate))
                .thenReturn(Optional.of(testAccountBalance));
        when(glBalanceRepository.findByGlNumAndTranDate("1001001", testDate))
                .thenReturn(Optional.of(testGLBalance));
        when(glMovementRepository.findByGlSetup_GlNumAndTranDateBetween("1001001", testDate, testDate))
                .thenReturn(Arrays.asList());
        when(glMovementAccrualRepository.findByGlSetupGlNumAndAccrualDateBetween("1001001", testDate, testDate))
                .thenReturn(Arrays.asList());

        // When
        SubProductDrillDownDetails details = reconciliationService.getDrillDownDetails("SP001", null);

        // Then
        assertNotNull(details);
        verify(systemDateService).getSystemDate(); // Should use system date when null
    }
}
