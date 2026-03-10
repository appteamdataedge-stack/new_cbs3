package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * EOD Step 8: Consolidated Financial Reports Service
 * Generates comprehensive workbook with:
 * 1. Trial Balance Report
 * 2. Balance Sheet Report
 * 3. Subproduct GL Balance Report
 * 4. Account Balance Report (one sheet per subproduct) - NEW FEATURE
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EODStep8ConsolidatedReportService {

    private final GLBalanceRepository glBalanceRepository;
    private final GLSetupRepository glSetupRepository;
    private final SubProdMasterRepository subProdMasterRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final AcctBalRepository acctBalRepository;
    private final AcctBalLcyRepository acctBalLcyRepository;
    private final SystemDateService systemDateService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    private static final String LCY = "BDT";

    /**
     * Generate complete EOD Step 8 consolidated report workbook
     */
    @Transactional(readOnly = true)
    public byte[] generateConsolidatedReport(LocalDate eodDate) {
        if (eodDate == null) {
            eodDate = systemDateService.getSystemDate();
        }

        log.info("=== Starting EOD Step 8: Consolidated Report Generation for date: {} ===", eodDate);

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // Sheet 1: Trial Balance
            log.info("Generating Sheet 1: Trial Balance");
            generateTrialBalanceSheet(workbook, eodDate);

            // Sheet 2: Balance Sheet
            log.info("Generating Sheet 2: Balance Sheet");
            generateBalanceSheetSheet(workbook, eodDate);

            // Sheet 3: Subproduct GL Balance Report (with hyperlinks to detail sheets)
            log.info("Generating Sheet 3: Subproduct GL Balance Report");
            int subproductSheetIndex = generateSubproductGLBalanceSheet(workbook, eodDate);

            // Sheets 4+: Account Balance Report (one sheet per subproduct)
            log.info("Generating Account Balance Report sheets (one per subproduct)");
            generateAccountBalanceSheets(workbook, eodDate, subproductSheetIndex);

            workbook.write(outputStream);
            log.info("=== EOD Step 8 Consolidated Report completed successfully ({} bytes) ===", outputStream.size());
            
            return outputStream.toByteArray();

        } catch (IOException e) {
            log.error("Error generating EOD Step 8 Consolidated Report: {}", e.getMessage(), e);
            throw new BusinessException("Failed to generate consolidated report: " + e.getMessage());
        }
    }

    /**
     * Sheet 1: Trial Balance Report
     */
    private void generateTrialBalanceSheet(XSSFWorkbook workbook, LocalDate eodDate) {
        Sheet sheet = workbook.createSheet("Trial Balance");

        List<String> activeGLNumbers = glSetupRepository.findActiveGLNumbersWithAccounts();
        List<GLBalance> glBalances = activeGLNumbers.isEmpty()
                ? glBalanceRepository.findByTranDate(eodDate)
                : glBalanceRepository.findByTranDateAndGlNumIn(eodDate, activeGLNumbers);

        ensureFxGLsPresent(glBalances, eodDate);
        glBalances.sort(Comparator.comparing(GLBalance::getGlNum));

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);

        int rowNum = 0;

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("TRIAL BALANCE - " + eodDate.format(DATE_FORMATTER));
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        rowNum++;

        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"GL Code", "GL Name", "Opening Balance", "DR Summation", "CR Summation", "Closing Balance"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(columnHeaderStyle);
        }

        BigDecimal totalOpening = BigDecimal.ZERO;
        BigDecimal totalDR = BigDecimal.ZERO;
        BigDecimal totalCR = BigDecimal.ZERO;
        BigDecimal totalClosing = BigDecimal.ZERO;

        for (GLBalance glBalance : glBalances) {
            Row row = sheet.createRow(rowNum++);
            String glName = getGLName(glBalance.getGlNum());

            BigDecimal opening = nvl(glBalance.getOpeningBal());
            BigDecimal dr = nvl(glBalance.getDrSummation());
            BigDecimal cr = nvl(glBalance.getCrSummation());
            BigDecimal closing = nvl(glBalance.getClosingBal());

            createStyledCell(row, 0, glBalance.getGlNum(), dataStyle);
            createStyledCell(row, 1, glName, dataStyle);
            createStyledNumericCell(row, 2, opening, numberStyle);
            createStyledNumericCell(row, 3, dr, numberStyle);
            createStyledNumericCell(row, 4, cr, numberStyle);
            createStyledNumericCell(row, 5, closing, numberStyle);

            totalOpening = totalOpening.add(opening);
            totalDR = totalDR.add(dr);
            totalCR = totalCR.add(cr);
            totalClosing = totalClosing.add(closing);
        }

        rowNum++;
        Row totalRow = sheet.createRow(rowNum++);
        createStyledCell(totalRow, 0, "TOTAL", totalStyle);
        createStyledNumericCell(totalRow, 2, totalOpening, totalStyle);
        createStyledNumericCell(totalRow, 3, totalDR, totalStyle);
        createStyledNumericCell(totalRow, 4, totalCR, totalStyle);
        createStyledNumericCell(totalRow, 5, totalClosing, totalStyle);

        for (int i = 0; i < 6; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000);
        }

        sheet.createFreezePane(0, 3);

        log.info("Trial Balance sheet generated: {} GL accounts", glBalances.size());
    }

    /**
     * Sheet 2: Balance Sheet Report
     */
    private void generateBalanceSheetSheet(XSSFWorkbook workbook, LocalDate eodDate) {
        Sheet sheet = workbook.createSheet("Balance Sheet");

        List<String> balanceSheetGLs = glSetupRepository.findBalanceSheetGLNumbersWithAccounts();
        List<GLBalance> glBalances = balanceSheetGLs.isEmpty()
                ? new ArrayList<>()
                : glBalanceRepository.findByTranDateAndGlNumIn(eodDate, balanceSheetGLs);

        ensureFxGLsPresent(glBalances, eodDate);

        List<GLBalance> liabilities = glBalances.stream()
                .filter(gl -> gl.getGlNum().startsWith("1"))
                .sorted(Comparator.comparing(GLBalance::getGlNum))
                .collect(Collectors.toList());

        List<GLBalance> assets = glBalances.stream()
                .filter(gl -> gl.getGlNum().startsWith("2"))
                .sorted(Comparator.comparing(GLBalance::getGlNum))
                .collect(Collectors.toList());

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle sectionHeaderStyle = createSectionHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);

        int rowNum = 0;

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("BALANCE SHEET - " + eodDate.format(DATE_FORMATTER));
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

        rowNum++;

        Row sectionRow = sheet.createRow(rowNum++);
        Cell leftHeader = sectionRow.createCell(0);
        leftHeader.setCellValue("=== LIABILITIES ===");
        leftHeader.setCellStyle(sectionHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 2));

        Cell rightHeader = sectionRow.createCell(4);
        rightHeader.setCellValue("=== ASSETS ===");
        rightHeader.setCellStyle(sectionHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 4, 6));

        Row headerRow = sheet.createRow(rowNum++);
        createStyledCell(headerRow, 0, "GL Code", columnHeaderStyle);
        createStyledCell(headerRow, 1, "GL Name", columnHeaderStyle);
        createStyledCell(headerRow, 2, "Closing Balance", columnHeaderStyle);
        headerRow.createCell(3);
        createStyledCell(headerRow, 4, "GL Code", columnHeaderStyle);
        createStyledCell(headerRow, 5, "GL Name", columnHeaderStyle);
        createStyledCell(headerRow, 6, "Closing Balance", columnHeaderStyle);

        int maxRows = Math.max(liabilities.size(), assets.size());
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalAssets = BigDecimal.ZERO;

        for (int i = 0; i < maxRows; i++) {
            Row row = sheet.createRow(rowNum++);

            if (i < liabilities.size()) {
                GLBalance liability = liabilities.get(i);
                String glName = getGLName(liability.getGlNum());
                BigDecimal closing = nvl(liability.getClosingBal());

                createStyledCell(row, 0, liability.getGlNum(), dataStyle);
                createStyledCell(row, 1, glName, dataStyle);
                createStyledNumericCell(row, 2, closing, numberStyle);

                totalLiabilities = totalLiabilities.add(closing);
            }

            row.createCell(3);

            if (i < assets.size()) {
                GLBalance asset = assets.get(i);
                String glName = getGLName(asset.getGlNum());
                BigDecimal closing = nvl(asset.getClosingBal());

                createStyledCell(row, 4, asset.getGlNum(), dataStyle);
                createStyledCell(row, 5, glName, dataStyle);
                createStyledNumericCell(row, 6, closing, numberStyle);

                totalAssets = totalAssets.add(closing);
            }
        }

        rowNum++;
        Row totalRow = sheet.createRow(rowNum);
        createStyledCell(totalRow, 0, "TOTAL LIABILITIES", totalStyle);
        createStyledNumericCell(totalRow, 2, totalLiabilities, totalStyle);
        createStyledCell(totalRow, 4, "TOTAL ASSETS", totalStyle);
        createStyledNumericCell(totalRow, 6, totalAssets, totalStyle);

        sheet.setColumnWidth(0, 15 * 256);
        sheet.setColumnWidth(1, 40 * 256);
        sheet.setColumnWidth(2, 15 * 256);
        sheet.setColumnWidth(3, 2 * 256);
        sheet.setColumnWidth(4, 15 * 256);
        sheet.setColumnWidth(5, 40 * 256);
        sheet.setColumnWidth(6, 15 * 256);

        sheet.createFreezePane(0, 4);

        log.info("Balance Sheet generated: {} Liabilities, {} Assets", liabilities.size(), assets.size());
    }

    /**
     * Sheet 3: Subproduct GL Balance Report (with hyperlinks to detail sheets)
     * Returns the sheet index for reference
     */
    private int generateSubproductGLBalanceSheet(XSSFWorkbook workbook, LocalDate eodDate) {
        Sheet sheet = workbook.createSheet("Subproduct GL Balance Report");
        int sheetIndex = workbook.getSheetIndex(sheet);

        List<SubProdMaster> subProducts = subProdMasterRepository.findAllActiveSubProducts();
        List<SubproductBalanceData> reportData = new ArrayList<>();

        for (SubProdMaster subProduct : subProducts) {
            SubproductBalanceData data = calculateSubproductBalances(subProduct, eodDate);
            reportData.add(data);
        }

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle sectionHeaderStyle = createSectionHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);
        CellStyle hyperlinkStyle = createHyperlinkStyle(workbook);

        int rowNum = 0;

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("SUBPRODUCT GL BALANCE REPORT - " + eodDate.format(DATE_FORMATTER));
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        rowNum++;

        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"Subproduct Code", "Subproduct Name", "GL Number", "GL Name",
                "Account Count", "FCY Account Balance", "LCY Account Balance", "GL Balance"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(columnHeaderStyle);
        }

        Map<String, List<SubproductBalanceData>> bdtAndFcyGroups = reportData.stream()
                .collect(Collectors.groupingBy(d -> d.isFcyAccountsExist() ? "FCY" : "BDT"));

        List<SubproductBalanceData> bdtGroup = bdtAndFcyGroups.getOrDefault("BDT", new ArrayList<>());
        List<SubproductBalanceData> fcyGroup = bdtAndFcyGroups.getOrDefault("FCY", new ArrayList<>());

        int bdtSectionStartRow = rowNum;
        if (!bdtGroup.isEmpty()) {
            Row bdtSectionRow = sheet.createRow(rowNum++);
            Cell bdtSectionCell = bdtSectionRow.createCell(0);
            bdtSectionCell.setCellValue("=== BDT ACCOUNTS ===");
            bdtSectionCell.setCellStyle(sectionHeaderStyle);

            Map<String, List<SubproductBalanceData>> bdtByGL = bdtGroup.stream()
                    .collect(Collectors.groupingBy(SubproductBalanceData::getGlNum));

            for (Map.Entry<String, List<SubproductBalanceData>> glEntry : bdtByGL.entrySet()) {
                BigDecimal glSubtotal = BigDecimal.ZERO;

                for (SubproductBalanceData data : glEntry.getValue()) {
                    Row row = sheet.createRow(rowNum++);
                    createStyledCell(row, 0, data.getSubProductCode(), dataStyle);
                    
                    Cell nameCell = row.createCell(1);
                    nameCell.setCellValue(data.getSubProductName());
                    nameCell.setCellStyle(hyperlinkStyle);
                    addHyperlinkToSheet(workbook, nameCell, data.getSubProductName());

                    createStyledCell(row, 2, data.getGlNum(), dataStyle);
                    createStyledCell(row, 3, data.getGlName(), dataStyle);
                    createStyledNumericCell(row, 4, new BigDecimal(data.getAccountCount()), dataStyle);
                    createStyledCell(row, 5, "N/A", dataStyle);
                    createStyledNumericCell(row, 6, data.getTotalLCYBalance(), numberStyle);
                    createStyledNumericCell(row, 7, data.getGlBalance(), numberStyle);

                    glSubtotal = glSubtotal.add(data.getTotalLCYBalance());
                }

                Row subtotalRow = sheet.createRow(rowNum++);
                createStyledCell(subtotalRow, 5, "GL Subtotal:", totalStyle);
                createStyledNumericCell(subtotalRow, 6, glSubtotal, totalStyle);
            }

            BigDecimal bdtGrandTotal = bdtGroup.stream()
                    .map(SubproductBalanceData::getTotalLCYBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Row bdtTotalRow = sheet.createRow(rowNum++);
            createStyledCell(bdtTotalRow, 5, "BDT Grand Total:", totalStyle);
            createStyledNumericCell(bdtTotalRow, 6, bdtGrandTotal, totalStyle);
            
            rowNum++;
        }

        if (!fcyGroup.isEmpty()) {
            Row fcySectionRow = sheet.createRow(rowNum++);
            Cell fcySectionCell = fcySectionRow.createCell(0);
            fcySectionCell.setCellValue("=== FCY ACCOUNTS ===");
            fcySectionCell.setCellStyle(sectionHeaderStyle);

            Map<String, List<SubproductBalanceData>> fcyByGL = fcyGroup.stream()
                    .collect(Collectors.groupingBy(SubproductBalanceData::getGlNum));

            for (Map.Entry<String, List<SubproductBalanceData>> glEntry : fcyByGL.entrySet()) {
                BigDecimal glSubtotalFCY = BigDecimal.ZERO;
                BigDecimal glSubtotalLCY = BigDecimal.ZERO;

                for (SubproductBalanceData data : glEntry.getValue()) {
                    Row row = sheet.createRow(rowNum++);
                    createStyledCell(row, 0, data.getSubProductCode(), dataStyle);
                    
                    Cell nameCell = row.createCell(1);
                    nameCell.setCellValue(data.getSubProductName());
                    nameCell.setCellStyle(hyperlinkStyle);
                    addHyperlinkToSheet(workbook, nameCell, data.getSubProductName());

                    createStyledCell(row, 2, data.getGlNum(), dataStyle);
                    createStyledCell(row, 3, data.getGlName(), dataStyle);
                    createStyledNumericCell(row, 4, new BigDecimal(data.getAccountCount()), dataStyle);
                    createStyledNumericCell(row, 5, data.getTotalFCYAmount(), numberStyle);
                    createStyledNumericCell(row, 6, data.getTotalLCYBalance(), numberStyle);
                    createStyledNumericCell(row, 7, data.getGlBalance(), numberStyle);

                    glSubtotalFCY = glSubtotalFCY.add(data.getTotalFCYAmount());
                    glSubtotalLCY = glSubtotalLCY.add(data.getTotalLCYBalance());
                }

                Row subtotalRow = sheet.createRow(rowNum++);
                createStyledCell(subtotalRow, 4, "GL Subtotal:", totalStyle);
                createStyledNumericCell(subtotalRow, 5, glSubtotalFCY, totalStyle);
                createStyledNumericCell(subtotalRow, 6, glSubtotalLCY, totalStyle);
            }

            BigDecimal fcyGrandTotalLCY = fcyGroup.stream()
                    .map(SubproductBalanceData::getTotalLCYBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fcyGrandTotalGL = fcyGroup.stream()
                    .map(SubproductBalanceData::getGlBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fcyDifference = fcyGrandTotalLCY.subtract(fcyGrandTotalGL);

            Row fcyTotalRow = sheet.createRow(rowNum++);
            createStyledCell(fcyTotalRow, 5, "FCY Grand Total (LCY):", totalStyle);
            createStyledNumericCell(fcyTotalRow, 6, fcyGrandTotalLCY, totalStyle);

            Row fcyGLTotalRow = sheet.createRow(rowNum++);
            createStyledCell(fcyGLTotalRow, 5, "GL Balance Total:", totalStyle);
            createStyledNumericCell(fcyGLTotalRow, 6, fcyGrandTotalGL, totalStyle);

            Row fcyDiffRow = sheet.createRow(rowNum++);
            createStyledCell(fcyDiffRow, 5, "Difference:", totalStyle);
            createStyledNumericCell(fcyDiffRow, 6, fcyDifference, totalStyle);
        }

        for (int i = 0; i < 8; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000);
        }

        sheet.createFreezePane(0, 3);

        log.info("Subproduct GL Balance Report generated: {} subproducts", reportData.size());
        return sheetIndex;
    }

    /**
     * Sheets 4+: Account Balance Report (one sheet per subproduct)
     */
    private void generateAccountBalanceSheets(XSSFWorkbook workbook, LocalDate eodDate, int subproductSheetIndex) {
        List<SubProdMaster> subProducts = subProdMasterRepository.findAllActiveSubProducts();
        
        log.info("Generating {} Account Balance Report sheets", subProducts.size());

        for (SubProdMaster subProduct : subProducts) {
            String sheetName = truncateSheetName(subProduct.getSubProductName());
            Sheet sheet = workbook.createSheet(sheetName);

            generateAccountBalanceDetailSheet(workbook, sheet, subProduct, eodDate);
        }

        log.info("Account Balance Report sheets generation completed");
    }

    /**
     * Generate individual Account Balance detail sheet for a subproduct
     */
    private void generateAccountBalanceDetailSheet(XSSFWorkbook workbook, Sheet sheet, 
                                                   SubProdMaster subProduct, LocalDate eodDate) {
        
        List<AccountBalanceDetail> accounts = fetchAccountBalances(subProduct, eodDate);

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle sectionHeaderStyle = createSectionHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);

        int rowNum = 0;

        if (accounts.isEmpty()) {
            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(subProduct.getSubProductName() + " - Account Balance Report");
            titleCell.setCellStyle(headerStyle);

            rowNum++;
            Row noDataRow = sheet.createRow(rowNum);
            Cell noDataCell = noDataRow.createCell(0);
            noDataCell.setCellValue("No Data Available for EOD Date: " + eodDate.format(DATE_FORMATTER));
            noDataCell.setCellStyle(dataStyle);
            
            sheet.autoSizeColumn(0);
            log.info("Account Balance sheet for subproduct '{}' created with no data", subProduct.getSubProductName());
            return;
        }

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(subProduct.getSubProductName() + " - Account Balance Report - " + eodDate.format(DATE_FORMATTER));
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        rowNum++;

        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"Subproduct Code", "Subproduct Name", "GL Number", "GL Name",
                "Account No.", "Account Name", "FCY Account Balance", "LCY Account Balance"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(columnHeaderStyle);
        }

        Map<String, List<AccountBalanceDetail>> bdtAndFcyGroups = accounts.stream()
                .collect(Collectors.groupingBy(a -> LCY.equals(a.getCurrency()) ? "BDT" : "FCY"));

        List<AccountBalanceDetail> bdtAccounts = bdtAndFcyGroups.getOrDefault("BDT", new ArrayList<>());
        List<AccountBalanceDetail> fcyAccounts = bdtAndFcyGroups.getOrDefault("FCY", new ArrayList<>());

        if (!bdtAccounts.isEmpty()) {
            Row bdtSectionRow = sheet.createRow(rowNum++);
            Cell bdtSectionCell = bdtSectionRow.createCell(0);
            bdtSectionCell.setCellValue("=== BDT ACCOUNTS ===");
            bdtSectionCell.setCellStyle(sectionHeaderStyle);

            Map<String, List<AccountBalanceDetail>> bdtByGL = bdtAccounts.stream()
                    .collect(Collectors.groupingBy(AccountBalanceDetail::getGlNum));

            for (Map.Entry<String, List<AccountBalanceDetail>> glEntry : bdtByGL.entrySet()) {
                BigDecimal glSubtotal = BigDecimal.ZERO;

                for (AccountBalanceDetail account : glEntry.getValue()) {
                    Row row = sheet.createRow(rowNum++);
                    createStyledCell(row, 0, account.getSubProductCode(), dataStyle);
                    createStyledCell(row, 1, account.getSubProductName(), dataStyle);
                    createStyledCell(row, 2, account.getGlNum(), dataStyle);
                    createStyledCell(row, 3, account.getGlName(), dataStyle);
                    createStyledCell(row, 4, account.getAccountNo(), dataStyle);
                    createStyledCell(row, 5, account.getAccountName(), dataStyle);
                    createStyledCell(row, 6, "N/A", dataStyle);
                    createStyledNumericCell(row, 7, account.getLcyBalance(), numberStyle);

                    glSubtotal = glSubtotal.add(account.getLcyBalance());
                }

                Row subtotalRow = sheet.createRow(rowNum++);
                createStyledCell(subtotalRow, 6, "GL Subtotal:", totalStyle);
                createStyledNumericCell(subtotalRow, 7, glSubtotal, totalStyle);
            }

            BigDecimal bdtGrandTotal = bdtAccounts.stream()
                    .map(AccountBalanceDetail::getLcyBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Row bdtTotalRow = sheet.createRow(rowNum++);
            createStyledCell(bdtTotalRow, 6, "BDT Grand Total:", totalStyle);
            createStyledNumericCell(bdtTotalRow, 7, bdtGrandTotal, totalStyle);

            rowNum++;
        }

        if (!fcyAccounts.isEmpty()) {
            Row fcySectionRow = sheet.createRow(rowNum++);
            Cell fcySectionCell = fcySectionRow.createCell(0);
            fcySectionCell.setCellValue("=== FCY ACCOUNTS ===");
            fcySectionCell.setCellStyle(sectionHeaderStyle);

            Map<String, List<AccountBalanceDetail>> fcyByGL = fcyAccounts.stream()
                    .collect(Collectors.groupingBy(AccountBalanceDetail::getGlNum));

            for (Map.Entry<String, List<AccountBalanceDetail>> glEntry : fcyByGL.entrySet()) {
                BigDecimal glSubtotalFCY = BigDecimal.ZERO;
                BigDecimal glSubtotalLCY = BigDecimal.ZERO;

                for (AccountBalanceDetail account : glEntry.getValue()) {
                    Row row = sheet.createRow(rowNum++);
                    createStyledCell(row, 0, account.getSubProductCode(), dataStyle);
                    createStyledCell(row, 1, account.getSubProductName(), dataStyle);
                    createStyledCell(row, 2, account.getGlNum(), dataStyle);
                    createStyledCell(row, 3, account.getGlName(), dataStyle);
                    createStyledCell(row, 4, account.getAccountNo(), dataStyle);
                    createStyledCell(row, 5, account.getAccountName(), dataStyle);
                    createStyledNumericCell(row, 6, account.getFcyBalance(), numberStyle);
                    createStyledNumericCell(row, 7, account.getLcyBalance(), numberStyle);

                    glSubtotalFCY = glSubtotalFCY.add(account.getFcyBalance());
                    glSubtotalLCY = glSubtotalLCY.add(account.getLcyBalance());
                }

                Row subtotalRow = sheet.createRow(rowNum++);
                createStyledCell(subtotalRow, 5, "GL Subtotal:", totalStyle);
                createStyledNumericCell(subtotalRow, 6, glSubtotalFCY, totalStyle);
                createStyledNumericCell(subtotalRow, 7, glSubtotalLCY, totalStyle);
            }

            BigDecimal fcyGrandTotalLCY = fcyAccounts.stream()
                    .map(AccountBalanceDetail::getLcyBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            String glNum = fcyAccounts.get(0).getGlNum();
            BigDecimal glBalance = getGLBalance(glNum, eodDate);
            BigDecimal difference = fcyGrandTotalLCY.subtract(glBalance);

            Row fcyTotalRow = sheet.createRow(rowNum++);
            createStyledCell(fcyTotalRow, 6, "FCY Grand Total (LCY):", totalStyle);
            createStyledNumericCell(fcyTotalRow, 7, fcyGrandTotalLCY, totalStyle);

            Row glBalanceRow = sheet.createRow(rowNum++);
            createStyledCell(glBalanceRow, 6, "GL Balance:", totalStyle);
            createStyledNumericCell(glBalanceRow, 7, glBalance, totalStyle);

            Row diffRow = sheet.createRow(rowNum++);
            createStyledCell(diffRow, 6, "Difference:", totalStyle);
            createStyledNumericCell(diffRow, 7, difference, totalStyle);
        }

        for (int i = 0; i < 8; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000);
        }

        sheet.createFreezePane(0, 3);

        log.info("Account Balance sheet for subproduct '{}' generated: {} accounts", 
                subProduct.getSubProductName(), accounts.size());
    }

    /**
     * Fetch account balances for a subproduct using set-based query
     */
    private List<AccountBalanceDetail> fetchAccountBalances(SubProdMaster subProduct, LocalDate eodDate) {
        List<CustAcctMaster> customerAccounts = subProduct.getSubProductId() != null
                ? custAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();
        List<OFAcctMaster> officeAccounts = subProduct.getSubProductId() != null
                ? ofAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();

        List<String> allAccountNos = new ArrayList<>();
        customerAccounts.forEach(a -> allAccountNos.add(a.getAccountNo()));
        officeAccounts.forEach(a -> allAccountNos.add(a.getAccountNo()));

        if (allAccountNos.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, AcctBal> acctBalMap = acctBalRepository
                .findByAccountNoInAndTranDate(allAccountNos, eodDate)
                .stream()
                .collect(Collectors.toMap(AcctBal::getAccountNo, ab -> ab));

        Map<String, AcctBalLcy> acctBalLcyMap = acctBalLcyRepository
                .findByAccountNoInAndTranDate(allAccountNos, eodDate)
                .stream()
                .collect(Collectors.toMap(AcctBalLcy::getAccountNo, ab -> ab));

        List<AccountBalanceDetail> details = new ArrayList<>();
        String glName = getGLName(subProduct.getCumGLNum());

        for (CustAcctMaster account : customerAccounts) {
            AcctBal balance = acctBalMap.get(account.getAccountNo());
            if (balance == null) continue;

            String currency = balance.getAccountCcy() != null ? balance.getAccountCcy() : LCY;
            BigDecimal fcyBalance = balance.getCurrentBalance() != null ? balance.getCurrentBalance() : BigDecimal.ZERO;
            BigDecimal lcyBalance;

            if (LCY.equals(currency)) {
                lcyBalance = fcyBalance;
                fcyBalance = BigDecimal.ZERO;
            } else {
                AcctBalLcy lcyRecord = acctBalLcyMap.get(account.getAccountNo());
                lcyBalance = (lcyRecord != null && lcyRecord.getClosingBalLcy() != null)
                        ? lcyRecord.getClosingBalLcy() : BigDecimal.ZERO;
            }

            details.add(AccountBalanceDetail.builder()
                    .subProductCode(subProduct.getSubProductCode())
                    .subProductName(subProduct.getSubProductName())
                    .glNum(subProduct.getCumGLNum())
                    .glName(glName)
                    .accountNo(account.getAccountNo())
                    .accountName(account.getAcctName())
                    .currency(currency)
                    .fcyBalance(fcyBalance)
                    .lcyBalance(lcyBalance)
                    .build());
        }

        for (OFAcctMaster account : officeAccounts) {
            AcctBal balance = acctBalMap.get(account.getAccountNo());
            if (balance == null) continue;

            String currency = balance.getAccountCcy() != null ? balance.getAccountCcy() : LCY;
            BigDecimal fcyBalance = balance.getCurrentBalance() != null ? balance.getCurrentBalance() : BigDecimal.ZERO;
            BigDecimal lcyBalance;

            if (LCY.equals(currency)) {
                lcyBalance = fcyBalance;
                fcyBalance = BigDecimal.ZERO;
            } else {
                AcctBalLcy lcyRecord = acctBalLcyMap.get(account.getAccountNo());
                lcyBalance = (lcyRecord != null && lcyRecord.getClosingBalLcy() != null)
                        ? lcyRecord.getClosingBalLcy() : BigDecimal.ZERO;
            }

            details.add(AccountBalanceDetail.builder()
                    .subProductCode(subProduct.getSubProductCode())
                    .subProductName(subProduct.getSubProductName())
                    .glNum(subProduct.getCumGLNum())
                    .glName(glName)
                    .accountNo(account.getAccountNo())
                    .accountName(account.getAcctName())
                    .currency(currency)
                    .fcyBalance(fcyBalance)
                    .lcyBalance(lcyBalance)
                    .build());
        }

        details.sort(Comparator.comparing(AccountBalanceDetail::getGlNum)
                .thenComparing(AccountBalanceDetail::getAccountNo));

        return details;
    }

    /**
     * Calculate subproduct balances using set-based queries
     */
    private SubproductBalanceData calculateSubproductBalances(SubProdMaster subProduct, LocalDate eodDate) {
        List<CustAcctMaster> customerAccounts = subProduct.getSubProductId() != null
                ? custAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();
        List<OFAcctMaster> officeAccounts = subProduct.getSubProductId() != null
                ? ofAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();

        List<String> allAccountNos = new ArrayList<>();
        customerAccounts.forEach(a -> allAccountNos.add(a.getAccountNo()));
        officeAccounts.forEach(a -> allAccountNos.add(a.getAccountNo()));

        BigDecimal totalLCYBalance = BigDecimal.ZERO;
        BigDecimal totalFCYAmount = BigDecimal.ZERO;
        boolean fcyAccountsExist = false;

        if (!allAccountNos.isEmpty()) {
            Map<String, AcctBal> acctBalMap = acctBalRepository
                    .findByAccountNoInAndTranDate(allAccountNos, eodDate)
                    .stream()
                    .collect(Collectors.toMap(AcctBal::getAccountNo, ab -> ab));

            Map<String, AcctBalLcy> acctBalLcyMap = acctBalLcyRepository
                    .findByAccountNoInAndTranDate(allAccountNos, eodDate)
                    .stream()
                    .collect(Collectors.toMap(AcctBalLcy::getAccountNo, ab -> ab));

            for (String accountNo : allAccountNos) {
                AcctBal balance = acctBalMap.get(accountNo);
                if (balance == null) continue;

                String currency = balance.getAccountCcy() != null ? balance.getAccountCcy() : LCY;
                BigDecimal fcyNative = balance.getCurrentBalance() != null ? balance.getCurrentBalance() : BigDecimal.ZERO;

                if (LCY.equals(currency)) {
                    totalLCYBalance = totalLCYBalance.add(fcyNative);
                } else {
                    AcctBalLcy lcyRecord = acctBalLcyMap.get(accountNo);
                    BigDecimal lcyBalance = (lcyRecord != null && lcyRecord.getClosingBalLcy() != null)
                            ? lcyRecord.getClosingBalLcy() : BigDecimal.ZERO;
                    totalLCYBalance = totalLCYBalance.add(lcyBalance);
                    totalFCYAmount = totalFCYAmount.add(fcyNative);
                    fcyAccountsExist = true;
                }
            }
        }

        BigDecimal glBalance = getGLBalance(subProduct.getCumGLNum(), eodDate);
        String glName = getGLName(subProduct.getCumGLNum());

        return SubproductBalanceData.builder()
                .subProductCode(subProduct.getSubProductCode())
                .subProductName(subProduct.getSubProductName())
                .glNum(subProduct.getCumGLNum())
                .glName(glName)
                .accountCount((long) allAccountNos.size())
                .totalFCYAmount(totalFCYAmount)
                .totalLCYBalance(totalLCYBalance)
                .glBalance(glBalance)
                .fcyAccountsExist(fcyAccountsExist)
                .build();
    }

    private BigDecimal getGLBalance(String glNum, LocalDate eodDate) {
        return glBalanceRepository.findByGlNumAndTranDate(glNum, eodDate)
                .map(GLBalance::getCurrentBalance)
                .orElse(BigDecimal.ZERO);
    }

    private String getGLName(String glNum) {
        return glSetupRepository.findById(glNum)
                .map(GLSetup::getGlName)
                .orElse("Unknown GL");
    }

    public String truncateSheetName(String name) {
        if (name == null || name.isEmpty()) {
            return "Sheet";
        }
        String sanitized = name.replaceAll("[\\\\/:*?\\[\\]]+", "_");
        return sanitized.length() > 31 ? sanitized.substring(0, 31) : sanitized;
    }

    private void addHyperlinkToSheet(XSSFWorkbook workbook, Cell cell, String sheetName) {
        String targetSheetName = truncateSheetName(sheetName);
        XSSFHyperlink hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
        hyperlink.setAddress("'" + targetSheetName + "'!A1");
        cell.setHyperlink(hyperlink);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Ensure FX Gain/Loss GLs are present in the balance list.
     * These GLs are posted by settlement and not linked to sub-products.
     * If absent from gl_balance for the report date, add a zero-balance entry so they always appear.
     */
    private void ensureFxGLsPresent(List<GLBalance> glBalances, LocalDate date) {
        Set<String> existing = glBalances.stream()
                .map(GLBalance::getGlNum)
                .collect(Collectors.toSet());
        List.of("140203002", "240203002").forEach(glNum -> {
            if (!existing.contains(glNum)) {
                glBalances.add(GLBalance.builder()
                        .glNum(glNum)
                        .tranDate(date)
                        .openingBal(BigDecimal.ZERO)
                        .drSummation(BigDecimal.ZERO)
                        .crSummation(BigDecimal.ZERO)
                        .closingBal(BigDecimal.ZERO)
                        .currentBalance(BigDecimal.ZERO)
                        .build());
            }
        });
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createColumnHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createSectionHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createNumberStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createBoldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    private CellStyle createHyperlinkStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setUnderline(Font.U_SINGLE);
        font.setColor(IndexedColors.BLUE.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private void createStyledCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void createStyledNumericCell(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SubproductBalanceData {
        private String subProductCode;
        private String subProductName;
        private String glNum;
        private String glName;
        private Long accountCount;
        private BigDecimal totalFCYAmount;
        private BigDecimal totalLCYBalance;
        private BigDecimal glBalance;
        private boolean fcyAccountsExist;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AccountBalanceDetail {
        private String subProductCode;
        private String subProductName;
        private String glNum;
        private String glName;
        private String accountNo;
        private String accountName;
        private String currency;
        private BigDecimal fcyBalance;
        private BigDecimal lcyBalance;
    }
}
