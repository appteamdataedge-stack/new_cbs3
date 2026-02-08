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

/**
 * Service for GL Balance Reconciliation Report
 * ✅ ISSUE 4: New feature to reconcile SubProduct account balances with GL balances
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GLReconciliationService {

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

    /**
     * Generate GL Reconciliation Report
     *
     * @param reportDate Date for the report
     * @return Excel file as byte array
     */
    @Transactional(readOnly = true)
    public byte[] generateReconciliationReport(LocalDate reportDate) {
        if (reportDate == null) {
            reportDate = systemDateService.getSystemDate();
        }

        log.info("Generating GL Reconciliation Report for date: {}", reportDate);

        // Get all active subproducts
        List<SubProdMaster> subProducts = subProdMasterRepository.findAllActiveSubProducts();
        log.info("Found {} active subproducts", subProducts.size());

        List<ReconciliationEntry> entries = new ArrayList<>();

        // Build reconciliation data for each subproduct
        for (SubProdMaster subProduct : subProducts) {
            ReconciliationEntry entry = buildReconciliationEntry(subProduct, reportDate);
            entries.add(entry);
        }

        // Sort by status (Unmatched first) then by difference (largest first)
        entries.sort(Comparator
                .comparing((ReconciliationEntry e) -> "Matched".equals(e.getStatus()) ? 1 : 0)
                .thenComparing((ReconciliationEntry e) -> e.getDifference().abs(), Comparator.reverseOrder()));

        log.info("Reconciliation complete: {} total subproducts, {} matched, {} unmatched",
                entries.size(),
                entries.stream().filter(e -> "Matched".equals(e.getStatus())).count(),
                entries.stream().filter(e -> "Unmatched".equals(e.getStatus())).count());

        // Generate Excel file
        try {
            return generateReconciliationExcel(entries, reportDate);
        } catch (IOException e) {
            log.error("Error generating Reconciliation Report Excel: {}", e.getMessage(), e);
            throw new BusinessException("Failed to generate reconciliation report: " + e.getMessage());
        }
    }

    /**
     * Build reconciliation entry for a subproduct
     */
    private ReconciliationEntry buildReconciliationEntry(SubProdMaster subProduct, LocalDate reportDate) {
        String subProductCode = subProduct.getSubProductCode();
        String subProductName = subProduct.getSubProductName();
        String glNum = subProduct.getCumGLNum();

        // Get GL name
        String glName = glSetupRepository.findById(glNum)
                .map(GLSetup::getGlName)
                .orElse("Unknown");

        // Calculate total account balance
        BigDecimal totalAccountBalance = calculateTotalAccountBalance(subProduct, reportDate);

        // Get GL balance
        BigDecimal glBalance = getGLBalance(glNum, reportDate);

        // Calculate difference and status
        BigDecimal difference = totalAccountBalance.subtract(glBalance);
        String status = difference.compareTo(BigDecimal.ZERO) == 0 ? "Matched" : "Unmatched";

        return ReconciliationEntry.builder()
                .subProductCode(subProductCode)
                .subProductName(subProductName)
                .glNum(glNum)
                .glName(glName)
                .totalAccountBalance(totalAccountBalance)
                .glBalance(glBalance)
                .difference(difference)
                .status(status)
                .build();
    }

    /**
     * Calculate total account balance for a subproduct
     */
    private BigDecimal calculateTotalAccountBalance(SubProdMaster subProduct, LocalDate reportDate) {
        BigDecimal total = BigDecimal.ZERO;

        // Get customer accounts
        List<CustAcctMaster> customerAccounts = subProduct.getSubProductId() != null
                ? custAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();

        // Get office accounts
        List<OFAcctMaster> officeAccounts = subProduct.getSubProductId() != null
                ? ofAcctMasterRepository.findBySubProductSubProductId(subProduct.getSubProductId())
                : new ArrayList<>();

        // Sum customer account balances
        for (CustAcctMaster account : customerAccounts) {
            Optional<AcctBal> balanceOpt = acctBalRepository.findByAccountNoAndTranDate(
                    account.getAccountNo(), reportDate);
            if (balanceOpt.isPresent()) {
                BigDecimal balance = balanceOpt.get().getCurrentBalance();
                if (balance != null) {
                    total = total.add(balance);
                }
            }
        }

        // Sum office account balances
        for (OFAcctMaster account : officeAccounts) {
            Optional<AcctBal> balanceOpt = acctBalRepository.findByAccountNoAndTranDate(
                    account.getAccountNo(), reportDate);
            if (balanceOpt.isPresent()) {
                BigDecimal balance = balanceOpt.get().getCurrentBalance();
                if (balance != null) {
                    total = total.add(balance);
                }
            }
        }

        return total;
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
    public DrillDownDetails getDrillDownDetails(String subProductCode, LocalDate reportDate) {
        if (reportDate == null) {
            reportDate = systemDateService.getSystemDate();
        }

        log.info("Getting drill-down details for subproduct {} on date {}", subProductCode, reportDate);

        // Find subproduct
        SubProdMaster subProduct = subProdMasterRepository.findBySubProductCode(subProductCode)
                .orElseThrow(() -> new BusinessException("SubProduct not found: " + subProductCode));

        DrillDownDetails details = new DrillDownDetails();
        details.setSubProductCode(subProductCode);
        details.setSubProductName(subProduct.getSubProductName());
        details.setGlNum(subProduct.getCumGLNum());
        details.setReportDate(reportDate);

        // Get account details
        List<AccountBalanceDetail> accountDetails = new ArrayList<>();
        BigDecimal totalAccountBalance = BigDecimal.ZERO;

        // Customer accounts
        List<CustAcctMaster> customerAccounts = custAcctMasterRepository
                .findBySubProductSubProductId(subProduct.getSubProductId());
        
        for (CustAcctMaster account : customerAccounts) {
            Optional<AcctBal> balanceOpt = acctBalRepository.findByAccountNoAndTranDate(
                    account.getAccountNo(), reportDate);
            
            BigDecimal balance = balanceOpt.map(AcctBal::getCurrentBalance).orElse(BigDecimal.ZERO);
            totalAccountBalance = totalAccountBalance.add(balance);
            
            accountDetails.add(AccountBalanceDetail.builder()
                    .accountNo(account.getAccountNo())
                    .accountName(account.getAcctName())
                    .accountType("Customer")
                    .balance(balance)
                    .build());
        }

        // Office accounts
        List<OFAcctMaster> officeAccounts = ofAcctMasterRepository
                .findBySubProductSubProductId(subProduct.getSubProductId());
        
        for (OFAcctMaster account : officeAccounts) {
            Optional<AcctBal> balanceOpt = acctBalRepository.findByAccountNoAndTranDate(
                    account.getAccountNo(), reportDate);
            
            BigDecimal balance = balanceOpt.map(AcctBal::getCurrentBalance).orElse(BigDecimal.ZERO);
            totalAccountBalance = totalAccountBalance.add(balance);
            
            accountDetails.add(AccountBalanceDetail.builder()
                    .accountNo(account.getAccountNo())
                    .accountName(account.getAcctName())
                    .accountType("Office")
                    .balance(balance)
                    .build());
        }

        details.setAccountDetails(accountDetails);
        details.setTotalAccountBalance(totalAccountBalance);

        // Get GL postings
        List<GLPostingDetail> glPostings = getGLPostings(subProduct.getCumGLNum(), reportDate);
        details.setGlPostings(glPostings);

        // Get GL balance
        BigDecimal glBalance = getGLBalance(subProduct.getCumGLNum(), reportDate);
        details.setGlBalance(glBalance);

        // Calculate difference
        details.setDifference(totalAccountBalance.subtract(glBalance));

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
    private byte[] generateReconciliationExcel(List<ReconciliationEntry> entries, LocalDate reportDate) 
            throws IOException {
        
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("GL Reconciliation");

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
            titleCell.setCellValue("GL BALANCE RECONCILIATION REPORT");
            titleCell.setCellStyle(headerStyle);

            // Date
            Row dateRow = sheet.createRow(rowNum++);
            Cell dateLabelCell = dateRow.createCell(0);
            dateLabelCell.setCellValue("Report Date:");
            Cell dateValueCell = dateRow.createCell(1);
            dateValueCell.setCellValue(reportDate.format(DATE_FORMATTER));

            // Empty row
            rowNum++;

            // Column headers
            Row headerRow = sheet.createRow(rowNum++);
            String[] headers = {"Sub-Product Code", "Sub-Product Name", "GL Number", "GL Name", 
                    "Total Account Balance", "GL Balance", "Difference", "Status"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(columnHeaderStyle);
            }

            // Data rows
            BigDecimal grandTotalAcctBal = BigDecimal.ZERO;
            BigDecimal grandTotalGLBal = BigDecimal.ZERO;
            BigDecimal grandTotalDiff = BigDecimal.ZERO;
            int matchedCount = 0;
            int unmatchedCount = 0;

            for (ReconciliationEntry entry : entries) {
                Row dataRow = sheet.createRow(rowNum++);

                // Determine row style based on status
                CellStyle statusStyle = "Matched".equals(entry.getStatus()) ? matchedStyle : unmatchedStyle;

                // Sub-Product Code
                Cell codeCell = dataRow.createCell(0);
                codeCell.setCellValue(entry.getSubProductCode());
                codeCell.setCellStyle(dataStyle);

                // Sub-Product Name
                Cell nameCell = dataRow.createCell(1);
                nameCell.setCellValue(entry.getSubProductName());
                nameCell.setCellStyle(dataStyle);

                // GL Number
                Cell glNumCell = dataRow.createCell(2);
                glNumCell.setCellValue(entry.getGlNum());
                glNumCell.setCellStyle(dataStyle);

                // GL Name
                Cell glNameCell = dataRow.createCell(3);
                glNameCell.setCellValue(entry.getGlName());
                glNameCell.setCellStyle(dataStyle);

                // Total Account Balance
                Cell acctBalCell = dataRow.createCell(4);
                acctBalCell.setCellValue(entry.getTotalAccountBalance().doubleValue());
                acctBalCell.setCellStyle(amountStyle);

                // GL Balance
                Cell glBalCell = dataRow.createCell(5);
                glBalCell.setCellValue(entry.getGlBalance().doubleValue());
                glBalCell.setCellStyle(amountStyle);

                // Difference
                Cell diffCell = dataRow.createCell(6);
                diffCell.setCellValue(entry.getDifference().doubleValue());
                diffCell.setCellStyle(amountStyle);

                // Status
                Cell statusCell = dataRow.createCell(7);
                statusCell.setCellValue(entry.getStatus());
                statusCell.setCellStyle(statusStyle);

                // Accumulate totals
                grandTotalAcctBal = grandTotalAcctBal.add(entry.getTotalAccountBalance());
                grandTotalGLBal = grandTotalGLBal.add(entry.getGlBalance());
                grandTotalDiff = grandTotalDiff.add(entry.getDifference());
                
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

            Cell totalAcctCell = summaryRow.createCell(4);
            totalAcctCell.setCellValue(grandTotalAcctBal.doubleValue());
            totalAcctCell.setCellStyle(createBoldAmountStyle(workbook));

            Cell totalGLCell = summaryRow.createCell(5);
            totalGLCell.setCellValue(grandTotalGLBal.doubleValue());
            totalGLCell.setCellStyle(createBoldAmountStyle(workbook));

            Cell totalDiffCell = summaryRow.createCell(6);
            totalDiffCell.setCellValue(grandTotalDiff.doubleValue());
            totalDiffCell.setCellStyle(createBoldAmountStyle(workbook));

            Cell statusSummaryCell = summaryRow.createCell(7);
            statusSummaryCell.setCellValue(String.format("%d Matched, %d Unmatched", matchedCount, unmatchedCount));
            statusSummaryCell.setCellStyle(createBoldStyle(workbook));

            // Auto-size columns
            for (int i = 0; i < 8; i++) {
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
        font.setFontHeightInPoints((short) 16);
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
    public static class ReconciliationEntry {
        private String subProductCode;
        private String subProductName;
        private String glNum;
        private String glName;
        private BigDecimal totalAccountBalance;
        private BigDecimal glBalance;
        private BigDecimal difference;
        private String status; // "Matched" or "Unmatched"
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DrillDownDetails {
        private String subProductCode;
        private String subProductName;
        private String glNum;
        private LocalDate reportDate;
        private List<AccountBalanceDetail> accountDetails;
        private BigDecimal totalAccountBalance;
        private List<GLPostingDetail> glPostings;
        private BigDecimal glBalance;
        private BigDecimal difference;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AccountBalanceDetail {
        private String accountNo;
        private String accountName;
        private String accountType;
        private BigDecimal balance;
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
