package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.repository.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EODStep8ConsolidatedReportService
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EODStep8ConsolidatedReportServiceTest {

    @Mock
    private GLBalanceRepository glBalanceRepository;

    @Mock
    private GLSetupRepository glSetupRepository;

    @Mock
    private SubProdMasterRepository subProdMasterRepository;

    @Mock
    private CustAcctMasterRepository custAcctMasterRepository;

    @Mock
    private OFAcctMasterRepository ofAcctMasterRepository;

    @Mock
    private AcctBalRepository acctBalRepository;

    @Mock
    private AcctBalLcyRepository acctBalLcyRepository;

    @Mock
    private TranTableRepository tranTableRepository;

    @Mock
    private FxPositionRepository fxPositionRepository;

    @Mock
    private InterestRateMasterRepository interestRateMasterRepository;

    @Mock
    private SystemDateService systemDateService;

    @InjectMocks
    private EODStep8ConsolidatedReportService reportService;

    private LocalDate testDate;
    private SubProdMaster testSubProduct;
    private GLSetup testGLSetup;

    @BeforeEach
    void setUp() {
        testDate = LocalDate.of(2024, 3, 15);

        testSubProduct = new SubProdMaster();
        testSubProduct.setSubProductId(1);
        testSubProduct.setSubProductCode("CM001");
        testSubProduct.setSubProductName("Call Money Overnight");
        testSubProduct.setCumGLNum("110101001");

        testGLSetup = new GLSetup();
        testGLSetup.setGlNum("110101001");
        testGLSetup.setGlName("Call Money - Overnight");
    }

    @Test
    void testGenerateConsolidatedReport_Success() throws Exception {
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(fxPositionRepository.findFcyByTranDate(testDate)).thenReturn(new ArrayList<>());
        when(glSetupRepository.findActiveGLNumbersWithAccounts()).thenReturn(Arrays.asList("110101001"));
        when(glSetupRepository.findBalanceSheetGLNumbersWithAccounts()).thenReturn(Arrays.asList("110101001"));
        when(glSetupRepository.findById(anyString())).thenReturn(Optional.of(testGLSetup));
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));

        GLBalance glBalance = new GLBalance();
        glBalance.setGlNum("110101001");
        glBalance.setTranDate(testDate);
        glBalance.setOpeningBal(BigDecimal.valueOf(1000000));
        glBalance.setDrSummation(BigDecimal.valueOf(500000));
        glBalance.setCrSummation(BigDecimal.valueOf(200000));
        glBalance.setClosingBal(BigDecimal.valueOf(1300000));
        glBalance.setCurrentBalance(BigDecimal.valueOf(1300000));

        when(glBalanceRepository.findByTranDateAndGlNumIn(eq(testDate), anyList()))
                .thenReturn(new ArrayList<>(Arrays.asList(glBalance)));
        when(glBalanceRepository.findByGlNumAndTranDate(eq("110101001"), eq(testDate))).thenReturn(Optional.of(glBalance));
        // getInterestBalanceReportData calls findByTranDate; no interest GLs in test data so empty is fine
        when(glBalanceRepository.findByTranDate(testDate)).thenReturn(new ArrayList<>());

        CustAcctMaster custAcct = new CustAcctMaster();
        custAcct.setAccountNo("CM001001");
        custAcct.setAcctName("Test Customer Account");
        custAcct.setSubProduct(testSubProduct);

        when(custAcctMasterRepository.findBySubProductSubProductId(1)).thenReturn(Arrays.asList(custAcct));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1)).thenReturn(new ArrayList<>());

        AcctBal acctBal = new AcctBal();
        acctBal.setAccountNo("CM001001");
        acctBal.setTranDate(testDate);
        acctBal.setAccountCcy("BDT");
        acctBal.setCurrentBalance(BigDecimal.valueOf(1300000));

        when(acctBalRepository.findByAccountNoInAndTranDate(anyList(), eq(testDate))).thenReturn(Arrays.asList(acctBal));
        when(acctBalLcyRepository.findByAccountNoInAndTranDate(anyList(), eq(testDate))).thenReturn(new ArrayList<>());

        byte[] result = reportService.generateConsolidatedReport(testDate);

        assertNotNull(result, "Consolidated report should not be null");
        assertTrue(result.length > 0, "Consolidated report should have content");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            // Sheets: Trial Balance, Balance Sheet, Subproduct GL Balance, Account Balance Report,
            //         FX Position Report, Interest Balance Report (new), + 1 per-subproduct detail
            assertEquals(7, workbook.getNumberOfSheets(), "Workbook should have 7 sheets");

            Sheet trialBalanceSheet = workbook.getSheet("Trial Balance");
            assertNotNull(trialBalanceSheet, "Trial Balance sheet should exist");

            Sheet balanceSheetSheet = workbook.getSheet("Balance Sheet");
            assertNotNull(balanceSheetSheet, "Balance Sheet sheet should exist");

            Sheet subproductSheet = workbook.getSheet("Subproduct GL Balance Report");
            assertNotNull(subproductSheet, "Subproduct GL Balance Report sheet should exist");

            Sheet fxPositionSheet = workbook.getSheet("FX Position Report");
            assertNotNull(fxPositionSheet, "FX Position Report sheet should exist");

            Sheet interestBalanceSheet = workbook.getSheet("Interest Balance Report");
            assertNotNull(interestBalanceSheet, "Interest Balance Report sheet should exist");

            Sheet accountBalanceSheet = workbook.getSheetAt(4);
            assertNotNull(accountBalanceSheet, "Account Balance detail sheet should exist");

            Row titleRow = trialBalanceSheet.getRow(0);
            assertNotNull(titleRow, "Trial Balance should have a title row");
            assertTrue(titleRow.getCell(0).getStringCellValue().contains("TRIAL BALANCE"), 
                    "Title should contain 'TRIAL BALANCE'");

            verify(glBalanceRepository, atLeastOnce()).findByTranDateAndGlNumIn(eq(testDate), anyList());
            verify(subProdMasterRepository, atLeastOnce()).findAllActiveSubProducts();
            verify(custAcctMasterRepository, atLeastOnce()).findBySubProductSubProductId(1);
        }
    }

    @Test
    void testGenerateConsolidatedReport_WithMultipleSubproducts() throws Exception {
        SubProdMaster subProduct2 = new SubProdMaster();
        subProduct2.setSubProductId(2);
        subProduct2.setSubProductCode("TM001");
        subProduct2.setSubProductName("Term Money Monthly");
        subProduct2.setCumGLNum("210101001");

        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(fxPositionRepository.findFcyByTranDate(testDate)).thenReturn(new ArrayList<>());
        when(glSetupRepository.findActiveGLNumbersWithAccounts()).thenReturn(Arrays.asList("110101001", "210101001"));
        when(glSetupRepository.findBalanceSheetGLNumbersWithAccounts()).thenReturn(Arrays.asList("110101001", "210101001"));
        when(glSetupRepository.findById("110101001")).thenReturn(Optional.of(testGLSetup));

        GLSetup glSetup2 = new GLSetup();
        glSetup2.setGlNum("210101001");
        glSetup2.setGlName("Term Money - Monthly");
        when(glSetupRepository.findById("210101001")).thenReturn(Optional.of(glSetup2));

        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct, subProduct2));

        GLBalance glBalance1 = createGLBalance("110101001", testDate, BigDecimal.valueOf(1000000));
        GLBalance glBalance2 = createGLBalance("210101001", testDate, BigDecimal.valueOf(2000000));

        when(glBalanceRepository.findByTranDateAndGlNumIn(eq(testDate), anyList()))
                .thenReturn(new ArrayList<>(Arrays.asList(glBalance1, glBalance2)));
        when(glBalanceRepository.findByGlNumAndTranDate(eq("110101001"), eq(testDate))).thenReturn(Optional.of(glBalance1));
        when(glBalanceRepository.findByGlNumAndTranDate(eq("210101001"), eq(testDate))).thenReturn(Optional.of(glBalance2));

        when(custAcctMasterRepository.findBySubProductSubProductId(anyInt())).thenReturn(new ArrayList<>());
        when(ofAcctMasterRepository.findBySubProductSubProductId(anyInt())).thenReturn(new ArrayList<>());
        when(acctBalRepository.findByAccountNoInAndTranDate(anyList(), eq(testDate))).thenReturn(new ArrayList<>());
        when(acctBalLcyRepository.findByAccountNoInAndTranDate(anyList(), eq(testDate))).thenReturn(new ArrayList<>());
        when(glBalanceRepository.findByTranDate(testDate)).thenReturn(new ArrayList<>());

        byte[] result = reportService.generateConsolidatedReport(testDate);

        assertNotNull(result);
        assertTrue(result.length > 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            assertEquals(8, workbook.getNumberOfSheets(),
                    "Workbook should have 8 sheets (6 base + 2 subproduct detail sheets)");

            Sheet subproductSheet = workbook.getSheet("Subproduct GL Balance Report");
            assertNotNull(subproductSheet);
        }
    }

    @Test
    void testGenerateConsolidatedReport_WithFCYAccounts() throws Exception {
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(fxPositionRepository.findFcyByTranDate(testDate)).thenReturn(new ArrayList<>());
        when(glSetupRepository.findActiveGLNumbersWithAccounts()).thenReturn(Arrays.asList("110101001"));
        when(glSetupRepository.findBalanceSheetGLNumbersWithAccounts()).thenReturn(Arrays.asList("110101001"));
        when(glSetupRepository.findById(anyString())).thenReturn(Optional.of(testGLSetup));
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));

        GLBalance glBalance = createGLBalance("110101001", testDate, BigDecimal.valueOf(1000000));
        when(glBalanceRepository.findByTranDateAndGlNumIn(eq(testDate), anyList()))
                .thenReturn(new ArrayList<>(Arrays.asList(glBalance)));
        when(glBalanceRepository.findByGlNumAndTranDate(eq("110101001"), eq(testDate))).thenReturn(Optional.of(glBalance));
        when(glBalanceRepository.findByTranDate(testDate)).thenReturn(new ArrayList<>());

        CustAcctMaster custAcct = new CustAcctMaster();
        custAcct.setAccountNo("CM001001");
        custAcct.setAcctName("Test USD Account");
        custAcct.setSubProduct(testSubProduct);

        when(custAcctMasterRepository.findBySubProductSubProductId(1)).thenReturn(Arrays.asList(custAcct));
        when(ofAcctMasterRepository.findBySubProductSubProductId(1)).thenReturn(new ArrayList<>());

        AcctBal acctBal = new AcctBal();
        acctBal.setAccountNo("CM001001");
        acctBal.setTranDate(testDate);
        acctBal.setAccountCcy("USD");
        acctBal.setCurrentBalance(BigDecimal.valueOf(10000));

        AcctBalLcy acctBalLcy = new AcctBalLcy();
        acctBalLcy.setAccountNo("CM001001");
        acctBalLcy.setTranDate(testDate);
        acctBalLcy.setClosingBalLcy(BigDecimal.valueOf(1000000));

        when(acctBalRepository.findByAccountNoInAndTranDate(anyList(), eq(testDate))).thenReturn(Arrays.asList(acctBal));
        when(acctBalLcyRepository.findByAccountNoInAndTranDate(anyList(), eq(testDate))).thenReturn(Arrays.asList(acctBalLcy));

        byte[] result = reportService.generateConsolidatedReport(testDate);

        assertNotNull(result);
        assertTrue(result.length > 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet subproductSheet = workbook.getSheet("Subproduct GL Balance Report");
            assertNotNull(subproductSheet);

            assertTrue(subproductSheet.getLastRowNum() >= 3, "Subproduct GL Balance sheet should contain report rows");
        }
    }

    @Test
    void testGenerateConsolidatedReport_WithNoData() throws Exception {
        when(systemDateService.getSystemDate()).thenReturn(testDate);
        when(fxPositionRepository.findFcyByTranDate(testDate)).thenReturn(new ArrayList<>());
        when(glSetupRepository.findActiveGLNumbersWithAccounts()).thenReturn(new ArrayList<>());
        when(glSetupRepository.findBalanceSheetGLNumbersWithAccounts()).thenReturn(new ArrayList<>());
        when(subProdMasterRepository.findAllActiveSubProducts()).thenReturn(Arrays.asList(testSubProduct));
        when(glSetupRepository.findById(anyString())).thenReturn(Optional.of(testGLSetup));

        when(glBalanceRepository.findByTranDate(testDate)).thenReturn(new ArrayList<>());
        when(glBalanceRepository.findByTranDateAndGlNumIn(eq(testDate), anyList())).thenReturn(new ArrayList<>());
        when(glBalanceRepository.findByGlNumAndTranDate(anyString(), eq(testDate))).thenReturn(Optional.empty());

        when(custAcctMasterRepository.findBySubProductSubProductId(1)).thenReturn(new ArrayList<>());
        when(ofAcctMasterRepository.findBySubProductSubProductId(1)).thenReturn(new ArrayList<>());

        byte[] result = reportService.generateConsolidatedReport(testDate);

        assertNotNull(result);
        assertTrue(result.length > 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            assertEquals(7, workbook.getNumberOfSheets(), "Workbook should have 7 sheets even with no data");

            Sheet accountBalanceSheet = workbook.getSheetAt(4);
            assertNotNull(accountBalanceSheet);

            Row noDataRow = accountBalanceSheet.getRow(2);
            if (noDataRow != null && noDataRow.getCell(0) != null) {
                String cellValue = noDataRow.getCell(0).getStringCellValue();
                assertTrue(cellValue.contains("No Data Available"), 
                        "Sheet should show 'No Data Available' message");
            }
        }
    }

    @Test
    void testTruncateSheetName() {
        String longName = "Very Long Subproduct Name That Exceeds Thirty One Characters Limit";
        
        EODStep8ConsolidatedReportService service = new EODStep8ConsolidatedReportService(
                glBalanceRepository, glSetupRepository, subProdMasterRepository,
                custAcctMasterRepository, ofAcctMasterRepository, acctBalRepository,
                acctBalLcyRepository, tranTableRepository, fxPositionRepository,
                interestRateMasterRepository, systemDateService);

        String result = service.truncateSheetName(longName);
        
        assertTrue(result.length() <= 31, "Sheet name should be truncated to 31 characters");
    }

    private GLBalance createGLBalance(String glNum, LocalDate date, BigDecimal balance) {
        GLBalance glBalance = new GLBalance();
        glBalance.setGlNum(glNum);
        glBalance.setTranDate(date);
        glBalance.setOpeningBal(balance);
        glBalance.setDrSummation(BigDecimal.ZERO);
        glBalance.setCrSummation(BigDecimal.ZERO);
        glBalance.setClosingBal(balance);
        glBalance.setCurrentBalance(balance);
        return glBalance;
    }
}
