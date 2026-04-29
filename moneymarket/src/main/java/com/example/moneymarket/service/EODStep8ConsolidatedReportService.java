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
    private final TranTableRepository tranTableRepository;
    private final FxPositionRepository fxPositionRepository;
    private final InterestRateMasterRepository interestRateMasterRepository;
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

            // Sheet 4: Account Balance Report (all accounts in one sheet)
            log.info("Generating Sheet 4: Account Balance Report");
            generateAccountBalanceReport(workbook, eodDate);

            // Sheet 5: FX Position Report
            log.info("Generating Sheet 5: FX Position Report");
            createFxPositionReportSheet(workbook, eodDate);

            // Sheet 6: Interest Balance Report
            log.info("Generating Sheet 6: Interest Balance Report");
            generateInterestBalanceReportSheet(workbook, eodDate);

            // Sheets 7+: Account Balance Report (one sheet per subproduct)
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
     * Reset FX Gain/Loss and Position GL balances to zero for dates with no activity.
     * Prevents stale carry-over values from prior FX days polluting reports on non-FX days.
     * Only zeroes openingBal/closingBal when both drSummation and crSummation are zero.
     */
    @Transactional
    public void resetStaleGLBalances(LocalDate eodDate) {
        String[] glCodesToReset = {"140203001", "140203002", "240203001", "240203002", "920101001", "920101002"};

        for (String glCode : glCodesToReset) {
            Optional<GLBalance> glBalOpt = glBalanceRepository.findByGlNumAndTranDate(glCode, eodDate);
            if (glBalOpt.isPresent()) {
                GLBalance glBal = glBalOpt.get();
                if (nvl(glBal.getDrSummation()).compareTo(BigDecimal.ZERO) == 0 &&
                        nvl(glBal.getCrSummation()).compareTo(BigDecimal.ZERO) == 0) {
                    glBal.setOpeningBal(BigDecimal.ZERO);
                    glBal.setClosingBal(BigDecimal.ZERO);
                    glBalanceRepository.save(glBal);
                    log.info("Reset stale GL balance to zero: {} on {}", glCode, eodDate);
                }
            }
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
        // Exclude 920xxx position accounts — data is kept in gl_balance for historical tracking
        // but these off-balance-sheet FCY inventory accounts are not shown in Trial Balance.
        glBalances.removeIf(gl -> gl.getGlNum().startsWith("920"));
        glBalances.sort(Comparator.comparing(GLBalance::getGlNum));

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);
        CellStyle statusStyle = createStatusStyle(workbook);

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

        int dataStartRow = rowNum; // 0-based index

        for (GLBalance glBalance : glBalances) {
            Row row = sheet.createRow(rowNum++);
            String glName = getGLName(glBalance.getGlNum());

            BigDecimal opening = nvl(glBalance.getOpeningBal());
            BigDecimal dr = nvl(glBalance.getDrSummation()).abs();
            BigDecimal cr = nvl(glBalance.getCrSummation()).abs();
            BigDecimal closing = nvl(glBalance.getClosingBal());

            createStyledCell(row, 0, glBalance.getGlNum(), dataStyle);
            createStyledCell(row, 1, glName, dataStyle);
            createStyledNumericCell(row, 2, opening, numberStyle);
            createStyledNumericCell(row, 3, dr, numberStyle);
            createStyledNumericCell(row, 4, cr, numberStyle);
            createStyledNumericCell(row, 5, closing, numberStyle);
        }
        int dataEndRow = rowNum - 1; // 0-based index

        // Blank separator row
        rowNum++;

        // TOTAL row (formula-based)
        int totalRowIdx = rowNum;
        Row totalRow = sheet.createRow(rowNum++);
        createStyledCell(totalRow, 0, "TOTAL", totalStyle);
        sheet.addMergedRegion(new CellRangeAddress(totalRowIdx, totalRowIdx, 0, 1));
        setFormula(totalRow, 2, buildSumFormula("C", dataStartRow, dataEndRow), totalStyle);
        setFormula(totalRow, 3, buildSumFormula("D", dataStartRow, dataEndRow), totalStyle);
        setFormula(totalRow, 4, buildSumFormula("E", dataStartRow, dataEndRow), totalStyle);
        setFormula(totalRow, 5, buildSumFormula("F", dataStartRow, dataEndRow), totalStyle);

        // DIFFERENCE / balance check row
        int diffRowIdx = rowNum;
        Row diffRow = sheet.createRow(rowNum++);
        createStyledCell(diffRow, 0, "DIFFERENCE (Debit - Credit)", totalStyle);
        setFormula(diffRow, 1, String.format("D%d-E%d", totalRowIdx + 1, totalRowIdx + 1), totalStyle);
        createStyledCell(diffRow, 3, "Status:", totalStyle);
        setFormula(
                diffRow,
                4,
                String.format("IF(ABS(B%d)<0.01,\"✅ BALANCED\",\"❌ NOT BALANCED\")", diffRowIdx + 1),
                statusStyle
        );

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
        // Position accounts (920xxx) are off-balance-sheet; do not include them here.

        List<GLBalance> liabilities = glBalances.stream()
                .filter(gl -> gl.getGlNum().startsWith("1") && !gl.getGlNum().startsWith("920"))
                .sorted(Comparator.comparing(GLBalance::getGlNum))
                .collect(Collectors.toList());

        List<GLBalance> assets = glBalances.stream()
                .filter(gl -> gl.getGlNum().startsWith("2") && !gl.getGlNum().startsWith("920"))
                .sorted(Comparator.comparing(GLBalance::getGlNum))
                .collect(Collectors.toList());

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle sectionHeaderStyle = createSectionHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);
        CellStyle statusStyle = createStatusStyle(workbook);

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
        int dataStartRow = rowNum; // 0-based index

        for (int i = 0; i < maxRows; i++) {
            Row row = sheet.createRow(rowNum++);

            if (i < liabilities.size()) {
                GLBalance liability = liabilities.get(i);
                String glName = getGLName(liability.getGlNum());
                BigDecimal closing = nvl(liability.getClosingBal());

                createStyledCell(row, 0, liability.getGlNum(), dataStyle);
                createStyledCell(row, 1, glName, dataStyle);
                createStyledNumericCell(row, 2, closing, numberStyle);
            }

            row.createCell(3);

            if (i < assets.size()) {
                GLBalance asset = assets.get(i);
                String glName = getGLName(asset.getGlNum());
                BigDecimal closing = nvl(asset.getClosingBal());

                createStyledCell(row, 4, asset.getGlNum(), dataStyle);
                createStyledCell(row, 5, glName, dataStyle);
                createStyledNumericCell(row, 6, closing, numberStyle);
            }
        }
        int dataEndRow = rowNum - 1; // 0-based index

        // Blank separator row
        rowNum++;

        // TOTAL row (formula-based)
        int totalRowIdx = rowNum;
        Row totalRow = sheet.createRow(rowNum++);
        createStyledCell(totalRow, 0, "TOTAL LIABILITIES", totalStyle);
        setFormula(totalRow, 2, buildSumFormula("C", dataStartRow, dataEndRow), totalStyle);
        createStyledCell(totalRow, 4, "TOTAL ASSETS", totalStyle);
        setFormula(totalRow, 6, buildSumFormula("G", dataStartRow, dataEndRow), totalStyle);

        // DIFFERENCE / balance check row — balanced books: Liabilities + Assets = 0
        // (Liability closingBals are positive, Asset closingBals are negative in gl_balance)
        int diffRowIdx = rowNum;
        Row diffRow = sheet.createRow(rowNum++);
        createStyledCell(diffRow, 0, "DIFFERENCE (Liabilities + Assets)", totalStyle);
        setFormula(diffRow, 1, String.format("G%d+C%d", totalRowIdx + 1, totalRowIdx + 1), totalStyle);
        createStyledCell(diffRow, 4, "Status:", totalStyle);
        setFormula(
                diffRow,
                6,
                String.format("IF(ABS(B%d)<0.01,\"✅ BALANCED\",\"❌ NOT BALANCED\")", diffRowIdx + 1),
                statusStyle
        );

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
     * Sheet 3: Subproduct GL Balance Report (flat table with status column)
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
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);
        CellStyle hyperlinkStyle = createHyperlinkStyle(workbook);

        int rowNum = 0;

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("SUBPRODUCT GL BALANCE REPORT - " + eodDate.format(DATE_FORMATTER));
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 10));

        rowNum++;

        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"Sub Product Code", "Sub Product Name", "GL Number", "GL Name",
                "Account Count", "Total Account Balance (FCY)", "Total Account Balance (LCY)", 
                "Total GL Balance", "Difference", "Status", "Detail"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(columnHeaderStyle);
        }

        BigDecimal totalAccountBalanceFCY = BigDecimal.ZERO;
        BigDecimal totalAccountBalanceLCY = BigDecimal.ZERO;
        BigDecimal totalGLBalance = BigDecimal.ZERO;
        long totalAccountCount = 0;
        int matchedCount = 0;
        int mismatchedCount = 0;

        for (SubproductBalanceData data : reportData) {
            Row row = sheet.createRow(rowNum++);
            
            BigDecimal difference = data.getTotalLCYBalance().subtract(data.getGlBalance());
            String status = difference.compareTo(BigDecimal.ZERO) == 0 ? "MATCHED" : "MISMATCHED";
            
            if ("MATCHED".equals(status)) {
                matchedCount++;
            } else {
                mismatchedCount++;
            }

            createStyledCell(row, 0, data.getSubProductCode(), dataStyle);
            createStyledCell(row, 1, data.getSubProductName(), dataStyle);
            createStyledCell(row, 2, data.getGlNum(), dataStyle);
            createStyledCell(row, 3, data.getGlName(), dataStyle);
            createStyledNumericCell(row, 4, new BigDecimal(data.getAccountCount()), numberStyle);
            createStyledNumericCell(row, 5, data.getTotalFCYAmount(), numberStyle);
            createStyledNumericCell(row, 6, data.getTotalLCYBalance(), numberStyle);
            createStyledNumericCell(row, 7, data.getGlBalance(), numberStyle);
            createStyledNumericCell(row, 8, difference, numberStyle);
            createStyledCell(row, 9, status, dataStyle);
            
            Cell detailCell = row.createCell(10);
            detailCell.setCellValue("View Detail");
            detailCell.setCellStyle(hyperlinkStyle);
            addHyperlinkToSheet(workbook, detailCell, data.getSubProductName());

            totalAccountBalanceFCY = totalAccountBalanceFCY.add(data.getTotalFCYAmount());
            totalAccountBalanceLCY = totalAccountBalanceLCY.add(data.getTotalLCYBalance());
            totalGLBalance = totalGLBalance.add(data.getGlBalance());
            totalAccountCount += data.getAccountCount();
        }

        rowNum++;
        Row totalRow = sheet.createRow(rowNum);
        createStyledCell(totalRow, 0, "TOTAL", totalStyle);
        createStyledCell(totalRow, 1, reportData.size() + " Subproducts", totalStyle);
        createStyledNumericCell(totalRow, 4, new BigDecimal(totalAccountCount), totalStyle);
        createStyledNumericCell(totalRow, 5, totalAccountBalanceFCY, totalStyle);
        createStyledNumericCell(totalRow, 6, totalAccountBalanceLCY, totalStyle);
        createStyledNumericCell(totalRow, 7, totalGLBalance, totalStyle);
        createStyledNumericCell(totalRow, 8, totalAccountBalanceLCY.subtract(totalGLBalance), totalStyle);
        createStyledCell(totalRow, 9, matchedCount + " Matched, " + mismatchedCount + " Mismatched", totalStyle);

        for (int i = 0; i < 11; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000);
        }

        sheet.createFreezePane(0, 3);

        log.info("Subproduct GL Balance Report generated: {} subproducts ({} matched, {} mismatched)", 
                reportData.size(), matchedCount, mismatchedCount);
        return sheetIndex;
    }

    /**
     * Sheet 4: Account Balance Report (all accounts in one sheet)
     */
    private void generateAccountBalanceReport(XSSFWorkbook workbook, LocalDate eodDate) {
        Sheet sheet = workbook.createSheet("Account Balance Report");

        List<CustAcctMaster> allCustomerAccounts = custAcctMasterRepository.findAll();
        List<OFAcctMaster> allOfficeAccounts = ofAcctMasterRepository.findAll();

        List<String> allAccountNos = new ArrayList<>();
        allCustomerAccounts.forEach(a -> allAccountNos.add(a.getAccountNo()));
        allOfficeAccounts.forEach(a -> allAccountNos.add(a.getAccountNo()));

        Map<String, AcctBal> acctBalMap = new HashMap<>();
        Map<String, AcctBalLcy> acctBalLcyMap = new HashMap<>();

        if (!allAccountNos.isEmpty()) {
            acctBalMap = acctBalRepository
                    .findByAccountNoInAndTranDate(allAccountNos, eodDate)
                    .stream()
                    .collect(Collectors.toMap(AcctBal::getAccountNo, ab -> ab));

            acctBalLcyMap = acctBalLcyRepository
                    .findByAccountNoInAndTranDate(allAccountNos, eodDate)
                    .stream()
                    .collect(Collectors.toMap(AcctBalLcy::getAccountNo, ab -> ab));
        }

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle totalStyle = createBoldStyle(workbook);

        int rowNum = 0;

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Account Balance Report");
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        Row subtitleRow = sheet.createRow(rowNum++);
        Cell subtitleCell = subtitleRow.createCell(0);
        subtitleCell.setCellValue("As of: " + eodDate.format(DATE_FORMATTER));
        subtitleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 7));

        rowNum++;

        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"Account Number", "Account Name", "Currency", "Sub Product Code", 
                           "Sub Product Name", "FCY Balance", "LCY Balance", "Status"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(columnHeaderStyle);
        }

        BigDecimal totalFcyBalance = BigDecimal.ZERO;
        BigDecimal totalLcyBalance = BigDecimal.ZERO;

        for (CustAcctMaster account : allCustomerAccounts) {
            AcctBal balance = acctBalMap.get(account.getAccountNo());
            AcctBalLcy lcyBalance = acctBalLcyMap.get(account.getAccountNo());
            
            String currency = balance != null && balance.getAccountCcy() != null 
                    ? balance.getAccountCcy() : LCY;
            boolean isBdt = LCY.equals(currency);

            BigDecimal fcyBal = isBdt ? BigDecimal.ZERO 
                    : nvl(balance != null ? balance.getClosingBal() : null);
            BigDecimal lcyBal = isBdt 
                    ? nvl(balance != null ? balance.getClosingBal() : null)
                    : nvl(lcyBalance != null ? lcyBalance.getClosingBalLcy() : null);

            Row row = sheet.createRow(rowNum++);
            createStyledCell(row, 0, account.getAccountNo(), dataStyle);
            createStyledCell(row, 1, account.getAcctName() != null ? account.getAcctName() : "", dataStyle);
            createStyledCell(row, 2, currency, dataStyle);
            createStyledCell(row, 3, account.getSubProduct() != null 
                    ? account.getSubProduct().getSubProductCode() : "", dataStyle);
            createStyledCell(row, 4, account.getSubProduct() != null 
                    ? account.getSubProduct().getSubProductName() : "", dataStyle);
            createStyledNumericCell(row, 5, fcyBal, numberStyle);
            createStyledNumericCell(row, 6, lcyBal, numberStyle);
            createStyledCell(row, 7, account.getAccountStatus() != null 
                    ? account.getAccountStatus().name() : "", dataStyle);

            totalFcyBalance = totalFcyBalance.add(fcyBal);
            totalLcyBalance = totalLcyBalance.add(lcyBal);
        }

        for (OFAcctMaster account : allOfficeAccounts) {
            AcctBal balance = acctBalMap.get(account.getAccountNo());
            AcctBalLcy lcyBalance = acctBalLcyMap.get(account.getAccountNo());
            
            String currency = balance != null && balance.getAccountCcy() != null 
                    ? balance.getAccountCcy() : LCY;
            boolean isBdt = LCY.equals(currency);

            BigDecimal fcyBal = isBdt ? BigDecimal.ZERO 
                    : nvl(balance != null ? balance.getClosingBal() : null);
            BigDecimal lcyBal = isBdt 
                    ? nvl(balance != null ? balance.getClosingBal() : null)
                    : nvl(lcyBalance != null ? lcyBalance.getClosingBalLcy() : null);

            Row row = sheet.createRow(rowNum++);
            createStyledCell(row, 0, account.getAccountNo(), dataStyle);
            createStyledCell(row, 1, account.getAcctName() != null ? account.getAcctName() : "", dataStyle);
            createStyledCell(row, 2, currency, dataStyle);
            createStyledCell(row, 3, account.getSubProduct() != null 
                    ? account.getSubProduct().getSubProductCode() : "", dataStyle);
            createStyledCell(row, 4, account.getSubProduct() != null 
                    ? account.getSubProduct().getSubProductName() : "", dataStyle);
            createStyledNumericCell(row, 5, fcyBal, numberStyle);
            createStyledNumericCell(row, 6, lcyBal, numberStyle);
            createStyledCell(row, 7, account.getAccountStatus() != null 
                    ? account.getAccountStatus().name() : "", dataStyle);

            totalFcyBalance = totalFcyBalance.add(fcyBal);
            totalLcyBalance = totalLcyBalance.add(lcyBal);
        }

        rowNum++;
        Row totalRow = sheet.createRow(rowNum);
        createStyledCell(totalRow, 4, "TOTAL", totalStyle);
        createStyledNumericCell(totalRow, 5, totalFcyBalance, totalStyle);
        createStyledNumericCell(totalRow, 6, totalLcyBalance, totalStyle);

        for (int i = 0; i < 8; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000);
        }

        sheet.createFreezePane(0, 4);

        log.info("Account Balance Report generated: {} customer accounts, {} office accounts",
                allCustomerAccounts.size(), allOfficeAccounts.size());
    }

    /**
     * Sheets 5+: Account Balance Report (one sheet per subproduct)
     */
    private void generateAccountBalanceSheets(XSSFWorkbook workbook, LocalDate eodDate, int subproductSheetIndex) {
        List<SubProdMaster> subProducts = subProdMasterRepository.findAllActiveSubProducts();
        
        log.info("Generating {} Account Balance Report sheets", subProducts.size());

        for (SubProdMaster subProduct : subProducts) {
            String sheetName = accBalSheetName(subProduct.getSubProductName());
            Sheet sheet = workbook.createSheet(sheetName);

            generateAccountBalanceDetailSheet(workbook, sheet, subProduct, eodDate);
        }

        log.info("Account Balance Report sheets generation completed");
    }

    /**
     * Generate individual detail sheet for a subproduct.
     * Section 1 — Account Balances (7 cols, flat Customer+Office list with Account Type column).
     * Section 2 — Transactions on EOD date (12 cols, from tran_table).
     * Empty sections show an italic "No data" row.
     */
    private void generateAccountBalanceDetailSheet(XSSFWorkbook workbook, Sheet sheet,
                                                   SubProdMaster subProduct, LocalDate eodDate) {

        // ── Fetch data ─────────────────────────────────────────────────────
        List<AccountBalanceDetail> accounts = fetchAccountBalances(subProduct, eodDate);

        // Account name lookup map (used to resolve names in transaction rows)
        Map<String, String> acctNameMap = accounts.stream()
                .filter(a -> a.getAccountNo() != null)
                .collect(Collectors.toMap(AccountBalanceDetail::getAccountNo,
                        AccountBalanceDetail::getAccountName, (x, y) -> x));

        // All account numbers for this subproduct (to query transactions)
        List<String> allAccountNos = accounts.stream()
                .map(AccountBalanceDetail::getAccountNo)
                .filter(no -> no != null && !no.isBlank())
                .collect(Collectors.toList());

        List<TranTable> transactions = allAccountNos.isEmpty()
                ? List.of()
                : tranTableRepository.findByAccountNoInAndTranDate(allAccountNos, eodDate);
        transactions = transactions.stream()
                .sorted(Comparator.comparing(TranTable::getTranId)
                        .thenComparing(t -> t.getDrCrFlag().name()))
                .collect(Collectors.toList());

        // ── Styles ─────────────────────────────────────────────────────────
        CellStyle headerStyle       = createHeaderStyle(workbook);
        CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
        CellStyle sectionHeaderStyle = createSectionHeaderStyle(workbook);
        CellStyle dataStyle         = createDataStyle(workbook);
        CellStyle numberStyle       = createNumberStyle(workbook);
        CellStyle totalStyle        = createBoldStyle(workbook);
        CellStyle italicStyle       = createItalicStyle(workbook);

        int rowNum = 0;

        // Title — spans widest section (12 cols for transactions)
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(subProduct.getSubProductName() + " - Detail Report - " + eodDate.format(DATE_FORMATTER));
        titleCell.setCellStyle(headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 11));

        rowNum++; // blank

        // ══════════════════════════════════════════════════════════════════
        // SECTION 1 — Account Balances as of <eodDate>
        // Cols: Account No. | Account Name | Account Type | GL Number | GL Name | FCY Balance | LCY Balance
        // ══════════════════════════════════════════════════════════════════
        Row balSectionRow = sheet.createRow(rowNum++);
        Cell balSectionCell = balSectionRow.createCell(0);
        balSectionCell.setCellValue("Account Balances as of " + eodDate.format(DATE_FORMATTER));
        balSectionCell.setCellStyle(sectionHeaderStyle);

        String[] balHeaders = {"Account No.", "Account Name", "Account Type",
                "GL Number", "GL Name", "FCY Balance", "LCY Balance"};
        Row balHeaderRow = sheet.createRow(rowNum++);
        for (int i = 0; i < balHeaders.length; i++) {
            Cell c = balHeaderRow.createCell(i);
            c.setCellValue(balHeaders[i]);
            c.setCellStyle(columnHeaderStyle);
        }

        if (accounts.isEmpty()) {
            Row noAcctRow = sheet.createRow(rowNum++);
            Cell noAcctCell = noAcctRow.createCell(0);
            noAcctCell.setCellValue("No accounts found");
            noAcctCell.setCellStyle(italicStyle);
        } else {
            BigDecimal totalFcy = BigDecimal.ZERO;
            BigDecimal totalLcy = BigDecimal.ZERO;

            for (AccountBalanceDetail acct : accounts) {
                Row row = sheet.createRow(rowNum++);
                createStyledCell(row, 0, acct.getAccountNo(), dataStyle);
                createStyledCell(row, 1, acct.getAccountName(), dataStyle);
                // Render "CUSTOMER" → "Customer", "OFFICE" → "Office"
                String typeLabel = "CUSTOMER".equals(acct.getAccountType()) ? "Customer" : "Office";
                createStyledCell(row, 2, typeLabel, dataStyle);
                createStyledCell(row, 3, acct.getGlNum(), dataStyle);
                createStyledCell(row, 4, acct.getGlName(), dataStyle);
                createStyledNumericCell(row, 5, acct.getRunningFcyBalance(), numberStyle);
                createStyledNumericCell(row, 6, acct.getRunningLcyBalance(), numberStyle);

                totalFcy = totalFcy.add(acct.getRunningFcyBalance());
                totalLcy = totalLcy.add(acct.getRunningLcyBalance());
            }

            Row balTotalRow = sheet.createRow(rowNum++);
            createStyledCell(balTotalRow, 4, "Total:", totalStyle);
            createStyledNumericCell(balTotalRow, 5, totalFcy, totalStyle);
            createStyledNumericCell(balTotalRow, 6, totalLcy, totalStyle);
        }

        rowNum++; // blank separator

        // ══════════════════════════════════════════════════════════════════
        // SECTION 2 — Transactions on EOD Date
        // Cols: Transaction ID | Transaction Date | Dr/Cr | Account No. | Account Name |
        //        GL Number | GL Name | Currency | FCY Amount | Exchange Rate | LCY Amount | Narration
        // ══════════════════════════════════════════════════════════════════
        Row tranSectionRow = sheet.createRow(rowNum++);
        Cell tranSectionCell = tranSectionRow.createCell(0);
        tranSectionCell.setCellValue("Transactions on " + eodDate.format(DATE_FORMATTER));
        tranSectionCell.setCellStyle(sectionHeaderStyle);

        String[] tranHeaders = {"Transaction ID", "Transaction Date", "Dr/Cr",
                "Account No.", "Account Name", "GL Number", "GL Name",
                "Currency", "FCY Amount", "Exchange Rate", "LCY Amount", "Narration"};
        Row tranHeaderRow = sheet.createRow(rowNum++);
        for (int i = 0; i < tranHeaders.length; i++) {
            Cell c = tranHeaderRow.createCell(i);
            c.setCellValue(tranHeaders[i]);
            c.setCellStyle(columnHeaderStyle);
        }

        if (transactions.isEmpty()) {
            Row noTranRow = sheet.createRow(rowNum++);
            Cell noTranCell = noTranRow.createCell(0);
            noTranCell.setCellValue("No transactions on this date");
            noTranCell.setCellStyle(italicStyle);
        } else {
            for (TranTable tran : transactions) {
                Row row = sheet.createRow(rowNum++);
                String acctName = tran.getAccountNo() != null
                        ? acctNameMap.getOrDefault(tran.getAccountNo(), "") : "";
                String glName = tran.getGlNum() != null ? getGLName(tran.getGlNum()) : "";

                createStyledCell(row, 0, tran.getTranId(), dataStyle);
                createStyledCell(row, 1, tran.getTranDate() != null
                        ? tran.getTranDate().format(DATE_FORMATTER) : "", dataStyle);
                createStyledCell(row, 2, tran.getDrCrFlag() != null
                        ? tran.getDrCrFlag().name() : "", dataStyle);
                createStyledCell(row, 3, tran.getAccountNo() != null ? tran.getAccountNo() : "", dataStyle);
                createStyledCell(row, 4, acctName, dataStyle);
                createStyledCell(row, 5, tran.getGlNum() != null ? tran.getGlNum() : "", dataStyle);
                createStyledCell(row, 6, glName, dataStyle);
                createStyledCell(row, 7, tran.getTranCcy() != null ? tran.getTranCcy() : "", dataStyle);
                createStyledNumericCell(row, 8, nvl(tran.getFcyAmt()), numberStyle);
                createStyledNumericCell(row, 9, nvl(tran.getExchangeRate()), numberStyle);
                createStyledNumericCell(row, 10, nvl(tran.getLcyAmt()), numberStyle);
                createStyledCell(row, 11, tran.getNarration() != null ? tran.getNarration() : "", dataStyle);
            }
        }

        // Auto-size all 12 columns
        for (int i = 0; i < 12; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000);
        }

        sheet.createFreezePane(0, 3);

        log.info("Detail sheet for subproduct '{}' generated: {} balance rows, {} transactions",
                subProduct.getSubProductName(), accounts.size(), transactions.size());
    }

    /**
     * Fetch account balances for a subproduct.
     * FIXED: accounts with no acc_bal row for eodDate now appear with zero balances
     * instead of being silently dropped (was: if (balance == null) continue).
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
            AcctBalLcy lcyRecord = acctBalLcyMap.get(account.getAccountNo());
            String currency = balance != null && balance.getAccountCcy() != null ? balance.getAccountCcy() : LCY;
            boolean isBdt = LCY.equals(currency);

            BigDecimal openingFcy = isBdt ? BigDecimal.ZERO : nvl(balance != null ? balance.getOpeningBal() : null);
            BigDecimal runningFcy = isBdt ? BigDecimal.ZERO : nvl(balance != null ? balance.getClosingBal() : null);
            BigDecimal openingLcy = isBdt ? nvl(balance != null ? balance.getOpeningBal() : null)
                    : nvl(lcyRecord != null ? lcyRecord.getOpeningBalLcy() : null);
            BigDecimal runningLcy = isBdt ? nvl(balance != null ? balance.getClosingBal() : null)
                    : nvl(lcyRecord != null ? lcyRecord.getClosingBalLcy() : null);

            details.add(AccountBalanceDetail.builder()
                    .subProductCode(subProduct.getSubProductCode())
                    .subProductName(subProduct.getSubProductName())
                    .glNum(subProduct.getCumGLNum())
                    .glName(glName)
                    .accountNo(account.getAccountNo())
                    .accountName(account.getAcctName())
                    .currency(currency)
                    .accountType("CUSTOMER")
                    .openingFcyBalance(openingFcy)
                    .openingLcyBalance(openingLcy)
                    .runningFcyBalance(runningFcy)
                    .runningLcyBalance(runningLcy)
                    .build());
        }

        for (OFAcctMaster account : officeAccounts) {
            AcctBal balance = acctBalMap.get(account.getAccountNo());
            AcctBalLcy lcyRecord = acctBalLcyMap.get(account.getAccountNo());
            String currency = balance != null && balance.getAccountCcy() != null ? balance.getAccountCcy() : LCY;
            boolean isBdt = LCY.equals(currency);

            BigDecimal openingFcy = isBdt ? BigDecimal.ZERO : nvl(balance != null ? balance.getOpeningBal() : null);
            BigDecimal runningFcy = isBdt ? BigDecimal.ZERO : nvl(balance != null ? balance.getClosingBal() : null);
            BigDecimal openingLcy = isBdt ? nvl(balance != null ? balance.getOpeningBal() : null)
                    : nvl(lcyRecord != null ? lcyRecord.getOpeningBalLcy() : null);
            BigDecimal runningLcy = isBdt ? nvl(balance != null ? balance.getClosingBal() : null)
                    : nvl(lcyRecord != null ? lcyRecord.getClosingBalLcy() : null);

            details.add(AccountBalanceDetail.builder()
                    .subProductCode(subProduct.getSubProductCode())
                    .subProductName(subProduct.getSubProductName())
                    .glNum(subProduct.getCumGLNum())
                    .glName(glName)
                    .accountNo(account.getAccountNo())
                    .accountName(account.getAcctName())
                    .currency(currency)
                    .accountType("OFFICE")
                    .openingFcyBalance(openingFcy)
                    .openingLcyBalance(openingLcy)
                    .runningFcyBalance(runningFcy)
                    .runningLcyBalance(runningLcy)
                    .build());
        }

        details.sort(Comparator.comparing(AccountBalanceDetail::getAccountType)
                .thenComparing(AccountBalanceDetail::getGlNum)
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
                BigDecimal fcyClosing = balance.getClosingBal() != null ? balance.getClosingBal() : BigDecimal.ZERO;

                if (LCY.equals(currency)) {
                    totalLCYBalance = totalLCYBalance.add(fcyClosing);
                } else {
                    AcctBalLcy lcyRecord = acctBalLcyMap.get(accountNo);
                    BigDecimal lcyBalance = (lcyRecord != null && lcyRecord.getClosingBalLcy() != null)
                            ? lcyRecord.getClosingBalLcy() : BigDecimal.ZERO;
                    totalLCYBalance = totalLCYBalance.add(lcyBalance);
                    totalFCYAmount = totalFCYAmount.add(fcyClosing);
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
                .map(GLBalance::getClosingBal)
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

    private void addHyperlinkToSheet(XSSFWorkbook workbook, Cell cell, String subProductName) {
        String targetSheetName = accBalSheetName(subProductName);
        XSSFHyperlink hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
        hyperlink.setAddress("'" + targetSheetName + "'!A1");
        cell.setHyperlink(hyperlink);
    }

    /**
     * Build AccBal sheet name: "AccBal_<name>" with spaces→underscore, truncated to 31 chars.
     * Illegal Excel sheet-name characters are also replaced with underscore.
     */
    public String accBalSheetName(String subProductName) {
        if (subProductName == null || subProductName.isEmpty()) return "AccBal_Sheet";
        String sanitized = "AccBal_" + subProductName.replaceAll("[\\\\/:*?\\[\\] ]+", "_");
        return sanitized.length() > 31 ? sanitized.substring(0, 31) : sanitized;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Ensure FX Gain/Loss GLs are present in the balance list.
     * These GLs are posted by settlement and FX Conversion module, not linked to sub-products.
     * If absent from the filtered list, fetch the LATEST balance from gl_balance table.
     * 
     * NOTE: Forex GLs may have balances on historical dates (e.g., Feb 6) but report is for current date.
     * We fetch the most recent balance <= reportDate to show cumulative forex gains/losses.
     * 
     * Includes:
     * - 140203001: Realised Forex Gain (FX Conversion)
     * - 140203002: Un-Realised Forex Gain (MCT)
     * - 240203001: Realised Forex Loss (FX Conversion)
     * - 240203002: Unrealised Forex Loss (MCT)
     */
    private void ensureFxGLsPresent(List<GLBalance> glBalances, LocalDate date) {
        Set<String> existing = glBalances.stream()
                .map(GLBalance::getGlNum)
                .collect(Collectors.toSet());
        
        List<String> fxGlCodes = List.of("140203001", "140203002", "240203001", "240203002");
        
        fxGlCodes.forEach(glNum -> {
            if (!existing.contains(glNum)) {
                log.info("FX GL {} not in active GL list, fetching latest balance from database...", glNum);
                
                // First, try to find balance for exact report date
                List<GLBalance> fxBalances = glBalanceRepository.findByTranDateAndGlNumIn(date, List.of(glNum));
                
                if (!fxBalances.isEmpty()) {
                    // Found balance for exact date
                    GLBalance actualBalance = fxBalances.get(0);
                    log.info("Found balance for {} on {}: Opening={}, DR={}, CR={}, Closing={}", 
                            glNum, date,
                            actualBalance.getOpeningBal(),
                            actualBalance.getDrSummation(),
                            actualBalance.getCrSummation(),
                            actualBalance.getClosingBal());
                    glBalances.add(actualBalance);
                } else {
                    // No balance for exact date, fetch most recent balance (any date)
                    log.info("No balance for {} on {}, fetching most recent balance...", glNum, date);
                    Optional<GLBalance> latestBalance = glBalanceRepository.findLatestByGlNum(glNum);
                    
                    if (latestBalance.isPresent()) {
                        GLBalance latest = latestBalance.get();
                        
                        // Only use if the balance date is <= report date
                        if (!latest.getTranDate().isAfter(date)) {
                            log.info("Found latest balance for {} on {}: Closing={}", 
                                    glNum, latest.getTranDate(), latest.getClosingBal());
                            
                            // Create a synthetic balance entry for the report date using the latest balance
                            // This shows cumulative forex gains/losses as of report date
                            glBalances.add(GLBalance.builder()
                                    .glNum(glNum)
                                    .tranDate(date)  // Use report date for consistency
                                    .openingBal(latest.getClosingBal())  // Carry forward closing balance
                                    .drSummation(BigDecimal.ZERO)  // No new transactions since then
                                    .crSummation(BigDecimal.ZERO)
                                    .closingBal(latest.getClosingBal())  // Same as opening
                                    .currentBalance(latest.getClosingBal())
                                    .build());
                        } else {
                            log.info("Latest balance for {} is dated {}, which is after report date {}, adding zero-balance", 
                                    glNum, latest.getTranDate(), date);
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
                    } else {
                        // No balance record at all, add zero entry
                        log.info("No balance record found for {}, adding zero-balance entry", glNum);
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
                }
            }
        });
    }

    /**
     * Ensures Position GL accounts (920101001, 920101002) are present in the report
     * even if they're not in the active GL list.
     * Position accounts are critical for FX inventory tracking.
     */
    private void ensurePositionGLsPresent(List<GLBalance> glBalances, LocalDate date) {
        Set<String> existing = glBalances.stream()
                .map(GLBalance::getGlNum)
                .collect(Collectors.toSet());
        
        List<String> positionGlCodes = List.of("920101001", "920101002");
        
        positionGlCodes.forEach(glNum -> {
            if (!existing.contains(glNum)) {
                log.info("Position GL {} not in result list, fetching from database...", glNum);
                
                // First, try to find balance for exact report date
                List<GLBalance> positionBalances = glBalanceRepository.findByTranDateAndGlNumIn(date, List.of(glNum));
                
                if (!positionBalances.isEmpty()) {
                    // Found balance(s) for exact date - add all currencies
                    new ArrayList<>(positionBalances).forEach(balance -> {
                        log.info("Found Position account {} on {}: Opening={}, DR={}, CR={}, Closing={}", 
                                glNum, date,
                                balance.getOpeningBal(),
                                balance.getDrSummation(),
                                balance.getCrSummation(),
                                balance.getClosingBal());
                        glBalances.add(balance);
                    });
                } else {
                    // No balance for exact date, fetch most recent balance (any date)
                    log.info("No balance for Position account {} on {}, fetching latest...", glNum, date);
                    Optional<GLBalance> latestBalance = glBalanceRepository.findLatestByGlNum(glNum);
                    
                    if (latestBalance.isPresent() && !latestBalance.get().getTranDate().isAfter(date)) {
                        log.info("Using latest balance for {} from date {}", glNum, latestBalance.get().getTranDate());
                        glBalances.add(latestBalance.get());
                    } else {
                        log.warn("No historical balance found for Position account {} on or before {}", glNum, date);
                        // Add zero-balance placeholder
                        GLBalance zeroBalance = GLBalance.builder()
                                .glNum(glNum)
                                .tranDate(date)
                                .openingBal(BigDecimal.ZERO)
                                .drSummation(BigDecimal.ZERO)
                                .crSummation(BigDecimal.ZERO)
                                .closingBal(BigDecimal.ZERO)
                                .currentBalance(BigDecimal.ZERO)
                                .build();
                        glBalances.add(zeroBalance);
                        log.info("Added zero-balance placeholder for Position account {}", glNum);
                    }
                }
            } else {
                log.debug("Position account {} already in result list", glNum);
            }
        });
    }

    private void injectFxPositionRows(List<GLBalance> glBalances, LocalDate eodDate) {
        List<FxPosition> fxPositions = fxPositionRepository.findFcyByTranDate(eodDate);
        for (FxPosition fp : fxPositions) {
            glBalances.add(GLBalance.builder()
                    .glNum(fp.getPositionGlNum())
                    .tranDate(eodDate)
                    .openingBal(nvl(fp.getOpeningBal()))
                    .drSummation(nvl(fp.getDrSummation()))
                    .crSummation(nvl(fp.getCrSummation()))
                    .closingBal(nvl(fp.getClosingBal()))
                    .currentBalance(nvl(fp.getClosingBal()))
                    .build());
        }
        if (!fxPositions.isEmpty()) {
            log.info("Injected {} trial-balance Position FCY rows from fx_position", fxPositions.size());
        }
    }

    public List<InterestBalanceReportRow> getInterestBalanceReportData(LocalDate eodDate) {
        if (eodDate == null) eodDate = systemDateService.getSystemDate();

        List<SubProdMaster> subProducts = subProdMasterRepository.findAllActiveSubProducts();

        Map<String, SubProdMaster> glToSubProd = new HashMap<>();
        for (SubProdMaster sp : subProducts) {
            if (sp.getCumGLNum() != null) glToSubProd.put(sp.getCumGLNum(), sp);
            if (sp.getInterestIncomePayableGLNum() != null) glToSubProd.put(sp.getInterestIncomePayableGLNum(), sp);
        }

        List<GLBalance> interestGlBalances = glBalanceRepository.findByTranDate(eodDate).stream()
                .filter(gl -> isInterestGL(gl.getGlNum()))
                .sorted(Comparator.comparing(GLBalance::getGlNum))
                .collect(Collectors.toList());

        Map<String, BigDecimal> fcyBalByGl = new HashMap<>();
        Map<String, BigDecimal> lcyBalByGl = new HashMap<>();
        Map<String, String> currencyByGl = new HashMap<>();

        for (SubProdMaster sp : subProducts) {
            String glNum = sp.getCumGLNum();
            if (glNum == null || !isInterestGL(glNum)) continue;

            List<CustAcctMaster> custAccts = custAcctMasterRepository.findBySubProductSubProductId(sp.getSubProductId());
            List<OFAcctMaster> ofAccts = ofAcctMasterRepository.findBySubProductSubProductId(sp.getSubProductId());

            List<String> accountNos = new ArrayList<>();
            custAccts.forEach(a -> accountNos.add(a.getAccountNo()));
            ofAccts.forEach(a -> accountNos.add(a.getAccountNo()));

            if (accountNos.isEmpty()) continue;

            Map<String, AcctBal> acctBalMap = acctBalRepository.findByAccountNoInAndTranDate(accountNos, eodDate)
                    .stream().collect(Collectors.toMap(AcctBal::getAccountNo, ab -> ab));
            Map<String, AcctBalLcy> acctBalLcyMap = acctBalLcyRepository.findByAccountNoInAndTranDate(accountNos, eodDate)
                    .stream().collect(Collectors.toMap(AcctBalLcy::getAccountNo, ab -> ab));

            BigDecimal totalFcy = BigDecimal.ZERO;
            BigDecimal totalLcy = BigDecimal.ZERO;
            String glCurrency = LCY;

            for (String accNo : accountNos) {
                AcctBal ab = acctBalMap.get(accNo);
                if (ab == null) continue;
                String ccy = ab.getAccountCcy() != null ? ab.getAccountCcy() : LCY;
                BigDecimal fcyClosing = ab.getClosingBal() != null ? ab.getClosingBal().abs() : BigDecimal.ZERO;

                if (LCY.equals(ccy)) {
                    totalLcy = totalLcy.add(fcyClosing);
                } else {
                    AcctBalLcy lcyRec = acctBalLcyMap.get(accNo);
                    BigDecimal lcyAmt = (lcyRec != null && lcyRec.getClosingBalLcy() != null)
                            ? lcyRec.getClosingBalLcy().abs() : BigDecimal.ZERO;
                    totalFcy = totalFcy.add(fcyClosing);
                    totalLcy = totalLcy.add(lcyAmt);
                    glCurrency = ccy;
                }
            }

            fcyBalByGl.merge(glNum, totalFcy, BigDecimal::add);
            lcyBalByGl.merge(glNum, totalLcy, BigDecimal::add);
            currencyByGl.put(glNum, totalFcy.compareTo(BigDecimal.ZERO) > 0 ? glCurrency : LCY);
        }

        List<InterestBalanceReportRow> rows = new ArrayList<>();
        for (GLBalance glBal : interestGlBalances) {
            String glNum = glBal.getGlNum();
            BigDecimal glBalance = nvl(glBal.getClosingBal()).abs();
            BigDecimal fcyBal = fcyBalByGl.getOrDefault(glNum, BigDecimal.ZERO);
            BigDecimal lcyBal = lcyBalByGl.getOrDefault(glNum, glBalance);
            String currency = currencyByGl.getOrDefault(glNum, LCY);
            String interestRate = getInterestRateDisplay(glToSubProd.get(glNum), eodDate);

            rows.add(InterestBalanceReportRow.builder()
                    .glCode(glNum)
                    .glName(getGLName(glNum))
                    .interestRate(interestRate)
                    .fcyBalance(fcyBal)
                    .lcyBalance(lcyBal)
                    .glBalance(glBalance)
                    .currency(currency)
                    .build());
        }

        return rows;
    }

    private void generateInterestBalanceReportSheet(XSSFWorkbook workbook, LocalDate eodDate) {
        Sheet sheet = workbook.createSheet("Interest Balance Report");
        CellStyle titleStyle = createHeaderStyle(workbook);
        CellStyle headerStyle = createColumnHeaderStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("INTEREST BALANCE REPORT - " + eodDate.format(DATE_FORMATTER));
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

        Row headerRow = sheet.createRow(2);
        String[] headers = {"GL Code", "GL Name", "Interest Rate", "FCY Balance", "LCY Balance", "GL Balance", "Currency"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        List<InterestBalanceReportRow> rows = getInterestBalanceReportData(eodDate);
        int rowNum = 3;
        for (InterestBalanceReportRow row : rows) {
            Row excelRow = sheet.createRow(rowNum++);
            createStyledCell(excelRow, 0, row.getGlCode(), dataStyle);
            createStyledCell(excelRow, 1, row.getGlName(), dataStyle);
            createStyledCell(excelRow, 2, row.getInterestRate(), dataStyle);
            createStyledNumericCell(excelRow, 3, row.getFcyBalance(), numberStyle);
            createStyledNumericCell(excelRow, 4, row.getLcyBalance(), numberStyle);
            createStyledNumericCell(excelRow, 5, row.getGlBalance(), numberStyle);
            createStyledCell(excelRow, 6, row.getCurrency(), dataStyle);
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 800);
        }
        log.info("Interest Balance Report sheet generated: {} rows", rows.size());
    }

    private boolean isInterestGL(String glNum) {
        if (glNum == null || glNum.length() < 3) return false;
        String prefix = glNum.substring(0, 3);
        return "130".equals(prefix) || "140".equals(prefix) || "230".equals(prefix) || "240".equals(prefix);
    }

    private String getInterestRateDisplay(SubProdMaster subProd, LocalDate eodDate) {
        if (subProd == null || subProd.getInttCode() == null) return "N/A";
        return interestRateMasterRepository
                .findTopByInttCodeAndInttEffctvDateLessThanEqualOrderByInttEffctvDateDesc(
                        subProd.getInttCode(), eodDate)
                .map(rate -> rate.getInttRate().toPlainString() + "%")
                .orElse("N/A");
    }

    private void createFxPositionReportSheet(XSSFWorkbook workbook, LocalDate eodDate) {
        Sheet sheet = workbook.createSheet("FX Position Report");
        CellStyle headerStyle = createColumnHeaderStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        Row headerRow = sheet.createRow(0);
        String[] headers = {"GL Code", "GL Name", "Currency", "Opening Balance", "DR Summation", "CR Summation", "Closing Balance"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        List<FxPosition> fxRows = fxPositionRepository.findFcyByTranDate(eodDate);
        fxRows.sort(Comparator.comparing(FxPosition::getPositionGlNum).thenComparing(FxPosition::getPositionCcy));

        int rowNum = 1;
        for (FxPosition fp : fxRows) {
            Row row = sheet.createRow(rowNum++);
            createStyledCell(row, 0, fp.getPositionGlNum(), dataStyle);
            createStyledCell(row, 1, getGLName(fp.getPositionGlNum()), dataStyle);
            createStyledCell(row, 2, fp.getPositionCcy(), dataStyle);
            createStyledNumericCell(row, 3, nvl(fp.getOpeningBal()), numberStyle);
            createStyledNumericCell(row, 4, nvl(fp.getDrSummation()), numberStyle);
            createStyledNumericCell(row, 5, nvl(fp.getCrSummation()), numberStyle);
            createStyledNumericCell(row, 6, nvl(fp.getClosingBal()), numberStyle);
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 800);
        }
        log.info("FX Position Report sheet generated: {} rows", fxRows.size());
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

    private CellStyle createStatusStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createItalicStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setItalic(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
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

    private void setFormula(Row row, int column, String formula, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellFormula(formula);
        cell.setCellStyle(style);
    }

    private String buildSumFormula(String excelColumn, int dataStartRowZeroBased, int dataEndRowZeroBased) {
        if (dataEndRowZeroBased < dataStartRowZeroBased) {
            return "0";
        }
        int start = dataStartRowZeroBased + 1; // Excel row number
        int end = dataEndRowZeroBased + 1;     // Excel row number
        return String.format("SUM(%s%d:%s%d)", excelColumn, start, excelColumn, end);
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
    public static class InterestBalanceReportRow {
        private String glCode;
        private String glName;
        private String interestRate;
        private BigDecimal fcyBalance;
        private BigDecimal lcyBalance;
        private BigDecimal glBalance;
        private String currency;
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
        /** "CUSTOMER" or "OFFICE" */
        private String accountType;
        private BigDecimal openingFcyBalance;
        private BigDecimal openingLcyBalance;
        private BigDecimal runningFcyBalance;
        private BigDecimal runningLcyBalance;
    }
}
