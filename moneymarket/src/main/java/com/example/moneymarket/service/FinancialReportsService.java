package com.example.moneymarket.service;

import com.example.moneymarket.dto.SubProductGLBalanceReportDTO;
import com.example.moneymarket.entity.GLBalance;
import com.example.moneymarket.entity.GLSetup;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.AcctBalRepository;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.OFAcctMasterRepository;
import com.example.moneymarket.repository.GLBalanceRepository;
import com.example.moneymarket.repository.GLSetupRepository;
import com.example.moneymarket.repository.SubProdMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
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
 * Service for Batch Job 7: Financial Reports Generation
 * Generates Trial Balance, Balance Sheet, and Subproduct-wise Account & GL Balance reports
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialReportsService {

    private final GLBalanceRepository glBalanceRepository;
    private final GLSetupRepository glSetupRepository;
    private final SubProdMasterRepository subProdMasterRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final AcctBalRepository acctBalRepository;
    private final SystemDateService systemDateService;

    /**
     * Batch Job 7: Financial Reports Generation
     *
     * Generates report metadata (reports are generated on-demand when downloaded)
     *
     * @param systemDate The system date for reporting
     * @return Map with report metadata
     */
    @Transactional(readOnly = true)
    public Map<String, String> generateFinancialReports(LocalDate systemDate) {
        LocalDate reportDate = systemDate != null ? systemDate : systemDateService.getSystemDate();
        log.info("Starting Batch Job 7: Financial Reports Generation for date: {}", reportDate);

        String reportDateStr = reportDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Map<String, String> result = new HashMap<>();
        result.put("success", "true");
        result.put("reportDate", reportDateStr);
        result.put("message", "Reports can be downloaded on-demand (Trial Balance, Balance Sheet, Subproduct GL Balance)");

        log.info("Batch Job 7 completed successfully. Reports can be downloaded for date: {}", reportDateStr);
        return result;
    }

    /**
     * Generate Trial Balance Report as byte array (in memory)
     *
     * @param systemDate The system date for reporting
     * @return byte array containing CSV content
     */
    @Transactional(readOnly = true)
    public byte[] generateTrialBalanceReportAsBytes(LocalDate systemDate) throws IOException {
        LocalDate reportDate = systemDate != null ? systemDate : systemDateService.getSystemDate();
        
        log.info("Generating Trial Balance Report in memory for date: {}", reportDate);

        // Get only active GL numbers (those used in account creation through sub-products)
        List<String> activeGLNumbers = glSetupRepository.findActiveGLNumbersWithAccounts();
        
        List<GLBalance> glBalances;
        if (activeGLNumbers.isEmpty()) {
            log.warn("No active GL numbers found with accounts");
            // Return all GLs as fallback
            glBalances = glBalanceRepository.findByTranDate(reportDate);
        } else {
            log.info("Found {} active GL numbers with accounts", activeGLNumbers.size());
            // Get GL balances only for active GLs
            glBalances = glBalanceRepository.findByTranDateAndGlNumIn(reportDate, activeGLNumbers);
        }

        return generateTrialBalanceReportFromBalancesAsBytes(glBalances, reportDate);
    }

    /**
     * Generate Balance Sheet Report as byte array (in memory)
     *
     * @param systemDate The system date for reporting
     * @return byte array containing Excel content
     */
    @Transactional(readOnly = true)
    public byte[] generateBalanceSheetReportAsBytes(LocalDate systemDate) throws IOException {
        LocalDate reportDate = systemDate != null ? systemDate : systemDateService.getSystemDate();
        String reportDateStr = reportDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        log.info("Generating Balance Sheet Report in memory for date: {}", reportDate);

        // Get Balance Sheet GL numbers only (excludes Income 14* and Expenditure 24*)
        List<String> balanceSheetGLNumbers = glSetupRepository.findBalanceSheetGLNumbersWithAccounts();
        
        if (balanceSheetGLNumbers.isEmpty()) {
            log.warn("No Balance Sheet GL numbers found with accounts");
            return createEmptyBalanceSheetAsBytes(reportDateStr);
        }
        
        log.info("Found {} Balance Sheet GL numbers with accounts", balanceSheetGLNumbers.size());
        
        // Get GL balances for Balance Sheet GLs only
        List<GLBalance> glBalances = glBalanceRepository.findByTranDateAndGlNumIn(reportDate, balanceSheetGLNumbers);

        if (glBalances.isEmpty()) {
            log.warn("No GL balances found for Balance Sheet GLs on date: {}", reportDate);
            return createEmptyBalanceSheetAsBytes(reportDateStr);
        }

        // Separate Liabilities and Assets
        List<GLBalance> liabilities = glBalances.stream()
                .filter(gl -> gl.getGlNum().startsWith("1"))
                .sorted(Comparator.comparing(GLBalance::getGlNum))
                .collect(Collectors.toList());

        List<GLBalance> assets = glBalances.stream()
                .filter(gl -> gl.getGlNum().startsWith("2"))
                .sorted(Comparator.comparing(GLBalance::getGlNum))
                .collect(Collectors.toList());

        return generateBalanceSheetExcelAsBytes(reportDateStr, liabilities, assets);
    }

    /**
     * Generate Trial Balance Report from GL balances as byte array (in memory)
     * 
     * Format:
     * GL_Code, GL_Name, Opening_Bal, DR_Summation, CR_Summation, Closing_Bal
     *
     * Footer row: "TOTAL", sum(Opening_Bal), sum(DR_Summation), sum(CR_Summation), sum(Closing_Bal)
     * Validation: Total DR_Summation must equal Total CR_Summation
     */
    private byte[] generateTrialBalanceReportFromBalancesAsBytes(List<GLBalance> glBalances, LocalDate reportDate)
            throws IOException {
        
        if (glBalances.isEmpty()) {
            log.warn("No GL balances found for date: {}", reportDate);
        }

        // Sort by GL Code
        glBalances.sort(Comparator.comparing(GLBalance::getGlNum));

        StringBuilder csvContent = new StringBuilder();
        
        // Write header
        csvContent.append("GL_Code,GL_Name,Opening_Bal,DR_Summation,CR_Summation,Closing_Bal\n");

        // Initialize totals
        BigDecimal totalOpeningBal = BigDecimal.ZERO;
        BigDecimal totalDRSummation = BigDecimal.ZERO;
        BigDecimal totalCRSummation = BigDecimal.ZERO;
        BigDecimal totalClosingBal = BigDecimal.ZERO;

        // Write data rows
        for (GLBalance glBalance : glBalances) {
            String glNum = glBalance.getGlNum();
            String glName = getGLName(glNum);

            BigDecimal openingBal = nvl(glBalance.getOpeningBal());
            BigDecimal drSummation = nvl(glBalance.getDrSummation());
            BigDecimal crSummation = nvl(glBalance.getCrSummation());
            BigDecimal closingBal = nvl(glBalance.getClosingBal());

            csvContent.append(String.format("%s,%s,%s,%s,%s,%s\n",
                    glNum, glName, openingBal, drSummation, crSummation, closingBal));

            // Accumulate totals
            totalOpeningBal = totalOpeningBal.add(openingBal);
            totalDRSummation = totalDRSummation.add(drSummation);
            totalCRSummation = totalCRSummation.add(crSummation);
            totalClosingBal = totalClosingBal.add(closingBal);
        }

        // Write footer row with totals
        csvContent.append(String.format("TOTAL,,%s,%s,%s,%s\n",
                totalOpeningBal, totalDRSummation, totalCRSummation, totalClosingBal));

        log.info("Trial Balance Report generated: {} GL accounts, Total DR={}, Total CR={}",
                glBalances.size(), totalDRSummation, totalCRSummation);

        // Validation: Check if Total DR equals Total CR
        BigDecimal difference = totalDRSummation.subtract(totalCRSummation);
        if (difference.compareTo(BigDecimal.ZERO) != 0) {
            String warningMsg = String.format(
                    "WARNING: Trial Balance is UNBALANCED! Total DR (%s) != Total CR (%s). Difference: %s",
                    totalDRSummation, totalCRSummation, difference);
            log.warn(warningMsg);

            // Add warning row to CSV instead of throwing exception
            csvContent.append("\n");
            csvContent.append(String.format("WARNING: TRIAL BALANCE IS UNBALANCED!,,,,,\n"));
            csvContent.append(String.format("Difference (DR - CR),,%s,,,\n", difference));
            csvContent.append(String.format("Please investigate and correct GL balances,,,,,\n"));
        } else {
            log.info("Trial Balance validation passed: DR = CR = {}", totalDRSummation);
        }

        return csvContent.toString().getBytes("UTF-8");
    }


    /**
     * Generate Balance Sheet Excel file with side-by-side layout as byte array (in memory)
     * Liabilities on left, Assets on right
     */
    private byte[] generateBalanceSheetExcelAsBytes(String reportDateStr,
                                                    List<GLBalance> liabilities, List<GLBalance> assets) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Balance Sheet");

            // Create cell styles
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle sectionHeaderStyle = createSectionHeaderStyle(workbook);
            CellStyle columnHeaderStyle = createHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle totalStyle = createTotalStyle(workbook);
            CellStyle numberStyle = createNumberStyle(workbook);

            int currentRow = 0;

            // Row 0: Title (merged across all columns)
            Row titleRow = sheet.createRow(currentRow++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("BALANCE SHEET - " + reportDateStr);
            titleCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 6));

            // Row 1: Empty spacer row
            sheet.createRow(currentRow++);

            // Row 2: Section headers
            Row sectionRow = sheet.createRow(currentRow++);
            Cell leftSectionHeader = sectionRow.createCell(0);
            leftSectionHeader.setCellValue("=== LIABILITIES ===");
            leftSectionHeader.setCellStyle(sectionHeaderStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(2, 2, 0, 2)); // Merge A3:C3

            Cell rightSectionHeader = sectionRow.createCell(4);
            rightSectionHeader.setCellValue("=== ASSETS ===");
            rightSectionHeader.setCellStyle(sectionHeaderStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(2, 2, 4, 6)); // Merge E3:G3

            // Row 3: Column Headers
            Row headerRow = sheet.createRow(currentRow++);

            // Liability headers (columns 0-2: A, B, C)
            createStyledCell(headerRow, 0, "GL_Code", columnHeaderStyle);
            createStyledCell(headerRow, 1, "GL_Name", columnHeaderStyle);
            createStyledCell(headerRow, 2, "Closing_Bal", columnHeaderStyle);

            // Empty column (column 3: D)
            headerRow.createCell(3);

            // Asset headers (columns 4-6: E, F, G)
            createStyledCell(headerRow, 4, "GL_Code", columnHeaderStyle);
            createStyledCell(headerRow, 5, "GL_Name", columnHeaderStyle);
            createStyledCell(headerRow, 6, "Closing_Bal", columnHeaderStyle);

            // Data rows - populate side by side
            int maxRows = Math.max(liabilities.size(), assets.size());
            BigDecimal totalLiabilities = BigDecimal.ZERO;
            BigDecimal totalAssets = BigDecimal.ZERO;

            for (int i = 0; i < maxRows; i++) {
                Row row = sheet.createRow(currentRow++);

                // Liability side (columns 0-2: A, B, C)
                if (i < liabilities.size()) {
                    GLBalance liability = liabilities.get(i);
                    String glName = getGLName(liability.getGlNum());
                    BigDecimal closingBal = nvl(liability.getClosingBal());

                    createStyledCell(row, 0, liability.getGlNum(), dataStyle);
                    createStyledCell(row, 1, glName, dataStyle);
                    createStyledNumericCell(row, 2, closingBal, numberStyle);

                    totalLiabilities = totalLiabilities.add(closingBal);
                }

                // Empty column (column 3: D)
                row.createCell(3);

                // Asset side (columns 4-6: E, F, G)
                if (i < assets.size()) {
                    GLBalance asset = assets.get(i);
                    String glName = getGLName(asset.getGlNum());
                    BigDecimal closingBal = nvl(asset.getClosingBal());

                    createStyledCell(row, 4, asset.getGlNum(), dataStyle);
                    createStyledCell(row, 5, glName, dataStyle);
                    createStyledNumericCell(row, 6, closingBal, numberStyle);

                    totalAssets = totalAssets.add(closingBal);
                }
            }

            // Empty row before totals
            currentRow++;

            // Totals row
            Row totalRow = sheet.createRow(currentRow);
            createStyledCell(totalRow, 0, "TOTAL LIABILITIES", totalStyle);
            createStyledNumericCell(totalRow, 2, totalLiabilities, totalStyle);

            createStyledCell(totalRow, 4, "TOTAL ASSETS", totalStyle);
            createStyledNumericCell(totalRow, 6, totalAssets, totalStyle);

            // Set column widths
            sheet.setColumnWidth(0, 15 * 256); // GL_Code left
            sheet.setColumnWidth(1, 40 * 256); // GL_Name left
            sheet.setColumnWidth(2, 15 * 256); // Closing_Bal left
            sheet.setColumnWidth(3, 2 * 256);  // Separator (narrow)
            sheet.setColumnWidth(4, 15 * 256); // GL_Code right
            sheet.setColumnWidth(5, 40 * 256); // GL_Name right
            sheet.setColumnWidth(6, 15 * 256); // Closing_Bal right

            workbook.write(outputStream);
            log.info("Balance Sheet Excel file generated in memory ({} bytes)", outputStream.size());
            
            // Calculate totals for logging
            log.info("Balance Sheet Report (Excel): {} Liabilities (Total: {}), {} Assets (Total: {})",
                    liabilities.size(), totalLiabilities, assets.size(), totalAssets);
            
            return outputStream.toByteArray();
        }
    }

    /**
     * Create empty Balance Sheet when no data available as byte array (in memory)
     */
    private byte[] createEmptyBalanceSheetAsBytes(String reportDateStr) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            Sheet sheet = workbook.createSheet("Balance Sheet");
            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue("BALANCE SHEET - " + reportDateStr);
            Row messageRow = sheet.createRow(2);
            messageRow.createCell(0).setCellValue("No Balance Sheet data available for this date.");
            
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    // Excel style helper methods
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
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
        return style;
    }

    private CellStyle createTotalStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setBorderTop(BorderStyle.DOUBLE);
        return style;
    }

    private CellStyle createNumberStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
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


    /**
     * Get GL name from GL setup
     */
    private String getGLName(String glNum) {
        Optional<GLSetup> glSetupOpt = glSetupRepository.findById(glNum);
        return glSetupOpt.map(GLSetup::getGlName).orElse("Unknown GL");
    }

    /**
     * Null-safe BigDecimal conversion
     */
    private BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Generate Subproduct-wise Account & GL Balance Report as byte array (in memory)
     *
     * @param systemDate The system date for reporting
     * @return byte array containing CSV content
     */
    @Transactional(readOnly = true)
    public byte[] generateSubProductGLBalanceReportAsBytes(LocalDate systemDate) throws IOException {
        LocalDate reportDate = systemDate != null ? systemDate : systemDateService.getSystemDate();

        log.info("Generating Subproduct-wise Account & GL Balance Report for date: {}", reportDate);

        // Get all active subproducts
        List<com.example.moneymarket.entity.SubProdMaster> subProducts = subProdMasterRepository.findAllActiveSubProducts();
        log.info("Found {} active subproducts", subProducts.size());

        List<SubProductGLBalanceReportDTO> reportData = new ArrayList<>();

        // Build report data for each subproduct
        for (com.example.moneymarket.entity.SubProdMaster subProduct : subProducts) {
            SubProductGLBalanceReportDTO dto = new SubProductGLBalanceReportDTO();
            dto.setSubProductCode(subProduct.getSubProductCode());
            dto.setSubProductName(subProduct.getSubProductName());
            dto.setGlNumber(subProduct.getCumGLNum());

            // Get GL name
            String glName = getGLName(subProduct.getCumGLNum());
            dto.setGlName(glName);

            // Get customer accounts for this subproduct
            List<com.example.moneymarket.entity.CustAcctMaster> customerAccounts =
                subProduct.getSubProductId() != null ?
                    custAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId()) :
                    new ArrayList<>();

            // Get office accounts for this subproduct
            List<com.example.moneymarket.entity.OFAcctMaster> officeAccounts =
                subProduct.getSubProductId() != null ?
                    ofAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId()) :
                    new ArrayList<>();

            // Total account count (customer + office)
            int totalAccountCount = customerAccounts.size() + officeAccounts.size();
            dto.setAccountCount((long) totalAccountCount);

            // Calculate total account balance from Acct_Bal table
            BigDecimal totalAccountBalance = BigDecimal.ZERO;

            // Add customer account balances
            for (com.example.moneymarket.entity.CustAcctMaster account : customerAccounts) {
                java.util.Optional<com.example.moneymarket.entity.AcctBal> acctBalOpt =
                    acctBalRepository.findByAccountNoAndTranDate(account.getAccountNo(), reportDate);
                if (acctBalOpt.isPresent()) {
                    BigDecimal currentBalance = acctBalOpt.get().getCurrentBalance();
                    if (currentBalance != null) {
                        totalAccountBalance = totalAccountBalance.add(currentBalance);
                    }
                }
            }

            // Add office account balances
            for (com.example.moneymarket.entity.OFAcctMaster account : officeAccounts) {
                java.util.Optional<com.example.moneymarket.entity.AcctBal> acctBalOpt =
                    acctBalRepository.findByAccountNoAndTranDate(account.getAccountNo(), reportDate);
                if (acctBalOpt.isPresent()) {
                    BigDecimal currentBalance = acctBalOpt.get().getCurrentBalance();
                    if (currentBalance != null) {
                        totalAccountBalance = totalAccountBalance.add(currentBalance);
                    }
                }
            }

            dto.setTotalAccountBalance(totalAccountBalance);

            // Get GL balance for this GL number
            BigDecimal glBalance = glBalanceRepository.findByTranDateAndGlNumIn(reportDate, List.of(subProduct.getCumGLNum()))
                    .stream()
                    .findFirst()
                    .map(GLBalance::getCurrentBalance)
                    .orElse(BigDecimal.ZERO);
            dto.setTotalGLBalance(glBalance);

            // Calculate difference and status
            dto.calculateDifferenceAndStatus();

            reportData.add(dto);
        }

        log.info("Generated report data for {} subproducts", reportData.size());
        return generateSubProductGLBalanceCSV(reportData, reportDate);
    }

    /**
     * Generate Subproduct-wise Account & GL Balance Report CSV content
     *
     * Format:
     * Sub Product Code, Sub Product Name, GL Number, GL Name, Account Count,
     * Total Account Balance, Total GL Balance, Difference, Status
     */
    private byte[] generateSubProductGLBalanceCSV(List<SubProductGLBalanceReportDTO> reportData, LocalDate reportDate)
            throws IOException {

        StringBuilder csvContent = new StringBuilder();

        // Write title
        csvContent.append("Subproduct-wise Account & GL Balance Report\n");
        csvContent.append(String.format("As of: %s\n\n", reportDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));

        // Write header
        csvContent.append("Sub Product Code,Sub Product Name,GL Number,GL Name,Account Count,");
        csvContent.append("Total Account Balance,Total GL Balance,Difference,Status\n");

        // Initialize totals
        long totalAccountCount = 0;
        BigDecimal grandTotalAccountBalance = BigDecimal.ZERO;
        BigDecimal grandTotalGLBalance = BigDecimal.ZERO;
        BigDecimal grandTotalDifference = BigDecimal.ZERO;
        int matchedCount = 0;
        int mismatchedCount = 0;

        // Write data rows
        for (SubProductGLBalanceReportDTO row : reportData) {
            csvContent.append(String.format("%s,%s,%s,%s,%d,%s,%s,%s,%s\n",
                    row.getSubProductCode(),
                    row.getSubProductName(),
                    row.getGlNumber(),
                    row.getGlName(),
                    row.getAccountCount(),
                    formatAmount(row.getTotalAccountBalance()),
                    formatAmount(row.getTotalGLBalance()),
                    formatAmount(row.getDifference()),
                    row.getStatus()));

            // Accumulate totals
            totalAccountCount += row.getAccountCount();
            grandTotalAccountBalance = grandTotalAccountBalance.add(row.getTotalAccountBalance());
            grandTotalGLBalance = grandTotalGLBalance.add(row.getTotalGLBalance());
            grandTotalDifference = grandTotalDifference.add(row.getDifference());

            if ("MATCHED".equals(row.getStatus())) {
                matchedCount++;
            } else {
                mismatchedCount++;
            }
        }

        // Write separator
        csvContent.append("---,---,---,---,---,---,---,---,---\n");

        // Write footer row with totals
        csvContent.append(String.format("TOTAL,,%d Subproducts,,%d,%s,%s,%s,\"%d Matched, %d Mismatched\"\n",
                reportData.size(),
                totalAccountCount,
                formatAmount(grandTotalAccountBalance),
                formatAmount(grandTotalGLBalance),
                formatAmount(grandTotalDifference),
                matchedCount,
                mismatchedCount));

        log.info("Subproduct GL Balance Report generated: {} subproducts, {} accounts, {} matched, {} mismatched",
                reportData.size(), totalAccountCount, matchedCount, mismatchedCount);

        // Validation warning if there are mismatches
        if (mismatchedCount > 0) {
            csvContent.append("\n");
            csvContent.append("WARNING: There are mismatches between Account Balances and GL Balances!\n");
            csvContent.append("Please investigate and reconcile the mismatched subproducts.\n");
            log.warn("Subproduct GL Balance Report has {} mismatches!", mismatchedCount);
        }

        return csvContent.toString().getBytes("UTF-8");
    }

    /**
     * Format amount for CSV output
     */
    private String formatAmount(BigDecimal amount) {
        return amount != null ? amount.toPlainString() : "0.00";
    }

}
