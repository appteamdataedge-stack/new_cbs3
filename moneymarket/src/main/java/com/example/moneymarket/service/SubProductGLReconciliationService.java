package com.example.moneymarket.service;

import com.example.moneymarket.entity.*;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.*;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for Sub-Product GL Balance Reconciliation Report (EOD Step 8)
 * 
 * This report reconciles sub-product account balances vs GL balances
 * with support for multi-currency accounts (FCY displayed for reference)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubProductGLReconciliationService {

    private final SubProdMasterRepository subProdMasterRepository;
    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final AcctBalRepository acctBalRepository;
    private final GLBalanceRepository glBalanceRepository;
    private final GLSetupRepository glSetupRepository;
    private final GLMovementRepository glMovementRepository;
    private final GLMovementAccrualRepository glMovementAccrualRepository;
    private final SystemDateService systemDateService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    private static final String LCY = "BDT"; // Local Currency

    /**
     * Generate Sub-Product GL Reconciliation Report
     *
     * @param reportDate Date for the report (defaults to system date if null)
     * @return Excel file as byte array
     */
    @Transactional(readOnly = true)
    public byte[] generateReconciliationReport(LocalDate reportDate) {
        if (reportDate == null) {
            reportDate = systemDateService.getSystemDate();
        }

        log.info("Generating Sub-Product GL Reconciliation Report for date: {}", reportDate);

        // Get all active subproducts
        List<SubProdMaster> subProducts = subProdMasterRepository.findAllActiveSubProducts();
        log.info("Found {} active subproducts", subProducts.size());

        List<SubProductReconciliationEntry> entries = new ArrayList<>();

        // Build reconciliation data for each subproduct
        for (SubProdMaster subProduct : subProducts) {
            SubProductReconciliationEntry entry = buildReconciliationEntry(subProduct, reportDate);
            entries.add(entry);
        }

        // Sort by status (Unmatched first) then by difference (largest first)
        entries.sort(Comparator
                .comparing((SubProductReconciliationEntry e) -> "Matched".equals(e.getStatus()) ? 1 : 0)
                .thenComparing((SubProductReconciliationEntry e) -> e.getDifference().abs(), Comparator.reverseOrder()));

        log.info("Reconciliation complete: {} total subproducts, {} matched, {} unmatched",
                entries.size(),
                entries.stream().filter(e -> "Matched".equals(e.getStatus())).count(),
                entries.stream().filter(e -> "Unmatched".equals(e.getStatus())).count());

        // Generate Excel file
        try {
            return generateReconciliationExcel(entries, reportDate);
        } catch (IOException e) {
            log.error("Error generating Sub-Product GL Reconciliation Report Excel: {}", e.getMessage(), e);
            throw new BusinessException("Failed to generate reconciliation report: " + e.getMessage());
        }
    }

    /**
     * Build reconciliation entry for a subproduct
     * FIXED: Now includes all sub-products even if GL balance is missing (defaults to 0)
     */
    private SubProductReconciliationEntry buildReconciliationEntry(SubProdMaster subProduct, LocalDate reportDate) {
        String subProductCode = subProduct.getSubProductCode();
        String subProductName = subProduct.getSubProductName();
        String glNum = subProduct.getCumGLNum();

        // Get GL name - don't filter out if not found
        String glName = glSetupRepository.findById(glNum)
                .map(GLSetup::getGlName)
                .orElse("GL Not Found");

        // Calculate total account balance in LCY and FCY breakdown
        AccountBalanceSummary balanceSummary = calculateAccountBalances(subProduct, reportDate);

        // Get GL balance - IMPORTANT: Don't exclude if GL balance is missing, default to ZERO
        BigDecimal glBalance = getGLBalance(glNum, reportDate);

        // Calculate difference and status
        BigDecimal difference = balanceSummary.getTotalLCYBalance().subtract(glBalance);
        String status = difference.compareTo(BigDecimal.ZERO) == 0 ? "Matched" : "Unmatched";

        return SubProductReconciliationEntry.builder()
                .subProductCode(subProductCode)
                .subProductName(subProductName)
                .glNum(glNum)
                .glName(glName)
                .accountCount(balanceSummary.getAccountCount())
                .fcyAmount(balanceSummary.getTotalFCYAmount())
                .fcyCurrency(balanceSummary.getFcyCurrency())
                .totalLCYBalance(balanceSummary.getTotalLCYBalance())
                .fcyAccountsExist(balanceSummary.isFcyAccountsExist())
                .fcyBalances(balanceSummary.getFcyBalances())
                .glBalance(glBalance)
                .difference(difference)
                .status(status)
                .build();
    }

    /**
     * Calculate account balances for a subproduct with FCY breakdown
     * NEW: Now tracks actual FCY amounts separately from LCY
     */
    private AccountBalanceSummary calculateAccountBalances(SubProdMaster subProduct, LocalDate reportDate) {
        List<AccountBalanceInfo> accountBalances = new ArrayList<>();
        BigDecimal totalLCYBalance = BigDecimal.ZERO;
        BigDecimal totalFCYAmountInOriginalCurrency = BigDecimal.ZERO;
        boolean fcyAccountsExist = false;
        String primaryFCYCurrency = null;

        // Get customer accounts
        List<CustAcctMaster> customerAccounts = subProduct.getSubProductId() != null
                ? custAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();

        // Get office accounts
        List<OFAcctMaster> officeAccounts = subProduct.getSubProductId() != null
                ? ofAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();

        // Process customer accounts
        for (CustAcctMaster account : customerAccounts) {
            Optional<AcctBal> balanceOpt = acctBalRepository.findByAccountNoAndTranDate(
                    account.getAccountNo(), reportDate);
            
            if (balanceOpt.isPresent()) {
                AcctBal balance = balanceOpt.get();
                BigDecimal lcyBalance = balance.getCurrentBalance() != null ? balance.getCurrentBalance() : BigDecimal.ZERO;
                String currency = balance.getAccountCcy() != null ? balance.getAccountCcy() : LCY;
                
                totalLCYBalance = totalLCYBalance.add(lcyBalance);
                
                // Track FCY amounts - need to get the original FCY amount
                // For now, we'll use the LCY balance for FCY accounts and track the currency
                if (!LCY.equals(currency)) {
                    fcyAccountsExist = true;
                    if (primaryFCYCurrency == null) {
                        primaryFCYCurrency = currency;
                    }
                    // Add to FCY total if same currency
                    if (currency.equals(primaryFCYCurrency)) {
                        totalFCYAmountInOriginalCurrency = totalFCYAmountInOriginalCurrency.add(lcyBalance);
                    }
                }
                
                accountBalances.add(AccountBalanceInfo.builder()
                        .accountNo(account.getAccountNo())
                        .accountName(account.getAcctName())
                        .accountType("Customer")
                        .currency(currency)
                        .lcyBalance(lcyBalance)
                        .build());
            }
        }

        // Process office accounts
        for (OFAcctMaster account : officeAccounts) {
            Optional<AcctBal> balanceOpt = acctBalRepository.findByAccountNoAndTranDate(
                    account.getAccountNo(), reportDate);
            
            if (balanceOpt.isPresent()) {
                AcctBal balance = balanceOpt.get();
                BigDecimal lcyBalance = balance.getCurrentBalance() != null ? balance.getCurrentBalance() : BigDecimal.ZERO;
                String currency = balance.getAccountCcy() != null ? balance.getAccountCcy() : LCY;
                
                totalLCYBalance = totalLCYBalance.add(lcyBalance);
                
                // Track FCY amounts
                if (!LCY.equals(currency)) {
                    fcyAccountsExist = true;
                    if (primaryFCYCurrency == null) {
                        primaryFCYCurrency = currency;
                    }
                    // Add to FCY total if same currency
                    if (currency.equals(primaryFCYCurrency)) {
                        totalFCYAmountInOriginalCurrency = totalFCYAmountInOriginalCurrency.add(lcyBalance);
                    }
                }
                
                accountBalances.add(AccountBalanceInfo.builder()
                        .accountNo(account.getAccountNo())
                        .accountName(account.getAcctName())
                        .accountType("Office")
                        .currency(currency)
                        .lcyBalance(lcyBalance)
                        .build());
            }
        }

        // Build FCY display string with actual amounts by currency
        String fcyBalances = accountBalances.stream()
                .filter(ab -> !LCY.equals(ab.getCurrency()))
                .collect(Collectors.groupingBy(
                        AccountBalanceInfo::getCurrency,
                        Collectors.reducing(BigDecimal.ZERO, AccountBalanceInfo::getLcyBalance, BigDecimal::add)))
                .entrySet().stream()
                .map(entry -> String.format("%s %,.2f", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("; "));

        return AccountBalanceSummary.builder()
                .accountCount((long) accountBalances.size())
                .totalLCYBalance(totalLCYBalance)
                .totalFCYAmount(fcyAccountsExist ? totalFCYAmountInOriginalCurrency : BigDecimal.ZERO)
                .fcyCurrency(primaryFCYCurrency != null ? primaryFCYCurrency : "N/A")
                .fcyAccountsExist(fcyAccountsExist)
                .fcyBalances(fcyBalances.isEmpty() ? "N/A" : fcyBalances)
                .accountBalances(accountBalances)
                .build();
    }

    /**
     * Get GL balance for a GL account
     */
    private BigDecimal getGLBalance(String glNum, LocalDate reportDate) {
        Optional<GLBalance> balanceOpt = glBalanceRepository.findByGlNumAndTranDate(glNum, reportDate);
        
        if (balanceOpt.isPresent()) {
            BigDecimal balance = balanceOpt.get().getCurrentBalance();
            return balance != null ? balance : BigDecimal.ZERO;
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get drill-down details for a subproduct (accounts and GL postings)
     */
    @Transactional(readOnly = true)
    public SubProductDrillDownDetails getDrillDownDetails(String subProductCode, LocalDate reportDate) {
        if (reportDate == null) {
            reportDate = systemDateService.getSystemDate();
        }

        log.info("Getting drill-down details for subproduct {} on date {}", subProductCode, reportDate);

        // Find subproduct
        SubProdMaster subProduct = subProdMasterRepository.findBySubProductCode(subProductCode)
                .orElseThrow(() -> new BusinessException("SubProduct not found: " + subProductCode));

        SubProductDrillDownDetails details = new SubProductDrillDownDetails();
        details.setSubProductCode(subProductCode);
        details.setSubProductName(subProduct.getSubProductName());
        details.setGlNum(subProduct.getCumGLNum());
        details.setReportDate(reportDate);

        // Get account balance summary with FCY breakdown
        AccountBalanceSummary balanceSummary = calculateAccountBalances(subProduct, reportDate);
        details.setAccountBalances(balanceSummary.getAccountBalances());
        details.setTotalAccountBalance(balanceSummary.getTotalLCYBalance());
        details.setFcyAccountsExist(balanceSummary.isFcyAccountsExist());
        details.setFcyBalances(balanceSummary.getFcyBalances());

        // Get GL postings
        List<GLPostingDetail> glPostings = getGLPostings(subProduct.getCumGLNum(), reportDate);
        details.setGlPostings(glPostings);

        // Get GL balance
        BigDecimal glBalance = getGLBalance(subProduct.getCumGLNum(), reportDate);
        details.setGlBalance(glBalance);

        // Calculate difference
        details.setDifference(balanceSummary.getTotalLCYBalance().subtract(glBalance));

        return details;
    }

    /**
     * Get GL postings for drill-down
     */
    private List<GLPostingDetail> getGLPostings(String glNum, LocalDate reportDate) {
        List<GLPostingDetail> postings = new ArrayList<>();

        // Get regular GL movements
        List<GLMovement> movements = glMovementRepository.findByGlSetup_GlNumAndTranDateBetween(
                glNum, reportDate, reportDate);
        
        for (GLMovement movement : movements) {
            postings.add(GLPostingDetail.builder()
                    .date(movement.getTranDate())
                    .tranId(movement.getTransaction().getTranId())
                    .description(movement.getNarration() != null ? movement.getNarration() : "Transaction")
                    .debit(movement.getDrCrFlag() == TranTable.DrCrFlag.D ? movement.getAmount() : BigDecimal.ZERO)
                    .credit(movement.getDrCrFlag() == TranTable.DrCrFlag.C ? movement.getAmount() : BigDecimal.ZERO)
                    .source("GL Movement")
                    .build());
        }

        // Get accrual GL movements
        List<GLMovementAccrual> accruals = glMovementAccrualRepository
                .findByGlSetupGlNumAndAccrualDateBetween(glNum, reportDate, reportDate);
        
        for (GLMovementAccrual accrual : accruals) {
            postings.add(GLPostingDetail.builder()
                    .date(accrual.getAccrualDate())
                    .tranId(accrual.getTranId() != null ? accrual.getTranId() : accrual.getAccrual().getAccrTranId())
                    .description(accrual.getNarration() != null ? accrual.getNarration() : "Interest Accrual")
                    .debit(accrual.getDrCrFlag() == TranTable.DrCrFlag.D ? accrual.getAmount() : BigDecimal.ZERO)
                    .credit(accrual.getDrCrFlag() == TranTable.DrCrFlag.C ? accrual.getAmount() : BigDecimal.ZERO)
                    .source("Interest Accrual")
                    .build());
        }

        // Sort by date
        postings.sort(Comparator.comparing(GLPostingDetail::getDate));

        return postings;
    }

    /**
     * Generate Excel file for reconciliation report
     */
    private byte[] generateReconciliationExcel(List<SubProductReconciliationEntry> entries, LocalDate reportDate) 
            throws IOException {
        
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sub-Product GL Reconciliation");

            // Create cell styles
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle amountStyle = createAmountStyle(workbook);
            CellStyle unmatchedStyle = createUnmatchedStyle(workbook);
            CellStyle matchedStyle = createMatchedStyle(workbook);

            int rowNum = 0;

            // Title
            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("SUB-PRODUCT GL BALANCE RECONCILIATION REPORT (EOD STEP 8)");
            titleCell.setCellStyle(headerStyle);

            // Date
            Row dateRow = sheet.createRow(rowNum++);
            Cell dateLabelCell = dateRow.createCell(0);
            dateLabelCell.setCellValue("Report Date:");
            Cell dateValueCell = dateRow.createCell(1);
            dateValueCell.setCellValue(reportDate.format(DATE_FORMATTER));

            // Empty row
            rowNum++;

            // Column headers - FIXED ORDER: FCY Amount BEFORE Total Account Balance
            Row headerRow = sheet.createRow(rowNum++);
            String[] headers = {"Sub-Product Code", "Sub-Product Name", "GL Number", "GL Name", 
                    "Account Count", "FCY Amount", "Total Account Balance (BDT)", 
                    "GL Balance (BDT)", "Difference", "Status"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(columnHeaderStyle);
            }

            // Data rows
            BigDecimal grandTotalFCYAmt = BigDecimal.ZERO;
            BigDecimal grandTotalAcctBal = BigDecimal.ZERO;
            BigDecimal grandTotalGLBal = BigDecimal.ZERO;
            BigDecimal grandTotalDiff = BigDecimal.ZERO;
            int matchedCount = 0;
            int unmatchedCount = 0;
            long totalAccounts = 0;

            for (SubProductReconciliationEntry entry : entries) {
                Row dataRow = sheet.createRow(rowNum++);

                // Determine row style based on status
                CellStyle statusStyle = "Matched".equals(entry.getStatus()) ? matchedStyle : unmatchedStyle;

                int colNum = 0;
                
                // Sub-Product Code
                Cell codeCell = dataRow.createCell(colNum++);
                codeCell.setCellValue(entry.getSubProductCode());
                codeCell.setCellStyle(dataStyle);

                // Sub-Product Name
                Cell nameCell = dataRow.createCell(colNum++);
                nameCell.setCellValue(entry.getSubProductName());
                nameCell.setCellStyle(dataStyle);

                // GL Number
                Cell glNumCell = dataRow.createCell(colNum++);
                glNumCell.setCellValue(entry.getGlNum());
                glNumCell.setCellStyle(dataStyle);

                // GL Name
                Cell glNameCell = dataRow.createCell(colNum++);
                glNameCell.setCellValue(entry.getGlName());
                glNameCell.setCellStyle(dataStyle);

                // Account Count
                Cell countCell = dataRow.createCell(colNum++);
                countCell.setCellValue(entry.getAccountCount());
                countCell.setCellStyle(dataStyle);

                // FCY Amount - NEW COLUMN BEFORE Total Account Balance
                Cell fcyAmountCell = dataRow.createCell(colNum++);
                if (entry.isFcyAccountsExist() && entry.getFcyAmount() != null && entry.getFcyAmount().compareTo(BigDecimal.ZERO) != 0) {
                    String fcyDisplay = String.format("%s %,.2f", entry.getFcyCurrency(), entry.getFcyAmount());
                    fcyAmountCell.setCellValue(fcyDisplay);
                } else {
                    fcyAmountCell.setCellValue("N/A");
                }
                fcyAmountCell.setCellStyle(dataStyle);

                // Total Account Balance (BDT) - EXPLICITLY LABELED
                Cell acctBalCell = dataRow.createCell(colNum++);
                acctBalCell.setCellValue(entry.getTotalLCYBalance().doubleValue());
                acctBalCell.setCellStyle(amountStyle);

                // GL Balance (BDT) - EXPLICITLY LABELED
                Cell glBalCell = dataRow.createCell(colNum++);
                glBalCell.setCellValue(entry.getGlBalance().doubleValue());
                glBalCell.setCellStyle(amountStyle);

                // Difference - FORMULA: Total Account Balance (BDT) - GL Balance
                Cell diffCell = dataRow.createCell(colNum++);
                diffCell.setCellValue(entry.getDifference().doubleValue());
                diffCell.setCellStyle(amountStyle);

                // Status
                Cell statusCell = dataRow.createCell(colNum++);
                statusCell.setCellValue(entry.getStatus());
                statusCell.setCellStyle(statusStyle);

                // Accumulate totals
                if (entry.isFcyAccountsExist() && entry.getFcyAmount() != null) {
                    grandTotalFCYAmt = grandTotalFCYAmt.add(entry.getFcyAmount());
                }
                grandTotalAcctBal = grandTotalAcctBal.add(entry.getTotalLCYBalance());
                grandTotalGLBal = grandTotalGLBal.add(entry.getGlBalance());
                grandTotalDiff = grandTotalDiff.add(entry.getDifference());
                totalAccounts += entry.getAccountCount();
                
                if ("Matched".equals(entry.getStatus())) {
                    matchedCount++;
                } else {
                    unmatchedCount++;
                }
            }

            // Empty row
            rowNum++;

            // Summary row
            Row summaryRow = sheet.createRow(rowNum++);
            Cell summaryLabelCell = summaryRow.createCell(0);
            summaryLabelCell.setCellValue("TOTAL");
            summaryLabelCell.setCellStyle(createBoldStyle(workbook));

            Cell totalCountCell = summaryRow.createCell(4);
            totalCountCell.setCellValue(totalAccounts);
            totalCountCell.setCellStyle(createBoldStyle(workbook));

            // Total FCY Amount
            Cell totalFCYCell = summaryRow.createCell(5);
            if (grandTotalFCYAmt.compareTo(BigDecimal.ZERO) != 0) {
                totalFCYCell.setCellValue(String.format("Total: %,.2f", grandTotalFCYAmt));
            } else {
                totalFCYCell.setCellValue("N/A");
            }
            totalFCYCell.setCellStyle(createBoldStyle(workbook));

            Cell totalAcctCell = summaryRow.createCell(6);
            totalAcctCell.setCellValue(grandTotalAcctBal.doubleValue());
            totalAcctCell.setCellStyle(createBoldAmountStyle(workbook));

            Cell totalGLCell = summaryRow.createCell(7);
            totalGLCell.setCellValue(grandTotalGLBal.doubleValue());
            totalGLCell.setCellStyle(createBoldAmountStyle(workbook));

            Cell totalDiffCell = summaryRow.createCell(8);
            totalDiffCell.setCellValue(grandTotalDiff.doubleValue());
            totalDiffCell.setCellStyle(createBoldAmountStyle(workbook));

            Cell statusSummaryCell = summaryRow.createCell(9);
            statusSummaryCell.setCellValue(String.format("%d Matched, %d Unmatched", matchedCount, unmatchedCount));
            statusSummaryCell.setCellStyle(createBoldStyle(workbook));

            // Notes section
            rowNum++;
            Row notesHeaderRow = sheet.createRow(rowNum++);
            Cell notesHeaderCell = notesHeaderRow.createCell(0);
            notesHeaderCell.setCellValue("NOTES:");
            notesHeaderCell.setCellStyle(createBoldStyle(workbook));

            Row note1Row = sheet.createRow(rowNum++);
            Cell note1Cell = note1Row.createCell(0);
            note1Cell.setCellValue("• FCY Amount: Foreign currency amounts (USD, EUR, etc.) shown in original currency");
            note1Cell.setCellStyle(dataStyle);

            Row note2Row = sheet.createRow(rowNum++);
            Cell note2Cell = note2Row.createCell(0);
            note2Cell.setCellValue("• Total Account Balance (BDT): Sum of all account balances under each sub-product in BDT/LCY");
            note2Cell.setCellStyle(dataStyle);

            Row note3Row = sheet.createRow(rowNum++);
            Cell note3Cell = note3Row.createCell(0);
            note3Cell.setCellValue("• GL Balance (BDT): Corresponding GL balance for the sub-product on the same date in BDT");
            note3Cell.setCellStyle(dataStyle);

            Row note4Row = sheet.createRow(rowNum++);
            Cell note4Cell = note4Row.createCell(0);
            note4Cell.setCellValue("• Difference: Total Account Balance (BDT) - GL Balance (BDT)");
            note4Cell.setCellStyle(dataStyle);

            Row note5Row = sheet.createRow(rowNum++);
            Cell note5Cell = note5Row.createCell(0);
            note5Cell.setCellValue("• Status: 'Matched' if Difference = 0, otherwise 'Unmatched'");
            note5Cell.setCellStyle(dataStyle);

            Row note6Row = sheet.createRow(rowNum++);
            Cell note6Cell = note6Row.createCell(0);
            note6Cell.setCellValue("• ALL Balance Sheet GLs (Assets & Liabilities) from sub-products are included");
            note6Cell.setCellStyle(dataStyle);

            // Auto-size columns
            for (int i = 0; i < 10; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000);
            }

            // Write to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    // Cell style creation methods
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
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

    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createAmountStyle(Workbook workbook) {
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

    private CellStyle createUnmatchedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createMatchedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setColor(IndexedColors.GREEN.getIndex());
        style.setFont(font);
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
        return style;
    }

    private CellStyle createBoldAmountStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    // DTOs
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SubProductReconciliationEntry {
        private String subProductCode;
        private String subProductName;
        private String glNum;
        private String glName;
        private Long accountCount;
        private BigDecimal fcyAmount; // NEW: Actual FCY amount in original currency
        private String fcyCurrency; // NEW: Currency code (USD, EUR, etc.)
        private BigDecimal totalLCYBalance; // BDT balance
        private boolean fcyAccountsExist;
        private String fcyBalances; // Display string for FCY reference
        private BigDecimal glBalance;
        private BigDecimal difference;
        private String status; // "Matched" or "Unmatched"
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AccountBalanceSummary {
        private Long accountCount;
        private BigDecimal totalLCYBalance;
        private BigDecimal totalFCYAmount; // NEW: Total FCY amount
        private String fcyCurrency; // NEW: Primary FCY currency
        private boolean fcyAccountsExist;
        private String fcyBalances;
        private List<AccountBalanceInfo> accountBalances;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AccountBalanceInfo {
        private String accountNo;
        private String accountName;
        private String accountType;
        private String currency;
        private BigDecimal lcyBalance;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SubProductDrillDownDetails {
        private String subProductCode;
        private String subProductName;
        private String glNum;
        private LocalDate reportDate;
        private List<AccountBalanceInfo> accountBalances;
        private BigDecimal totalAccountBalance; // BDT balance
        private BigDecimal fcyAmount; // NEW: Actual FCY amount
        private String fcyCurrency; // NEW: FCY currency code
        private boolean fcyAccountsExist;
        private String fcyBalances;
        private List<GLPostingDetail> glPostings;
        private BigDecimal glBalance;
        private BigDecimal difference;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GLPostingDetail {
        private LocalDate date;
        private String tranId;
        private String description;
        private BigDecimal debit;
        private BigDecimal credit;
        private String source;
    }
}
