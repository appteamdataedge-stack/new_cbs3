package com.example.moneymarket.service;

import com.example.moneymarket.entity.GLBalance;
import com.example.moneymarket.entity.GLMovement;
import com.example.moneymarket.entity.GLMovementAccrual;
import com.example.moneymarket.entity.GLSetup;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.exception.ResourceNotFoundException;
import com.example.moneymarket.repository.GLBalanceRepository;
import com.example.moneymarket.repository.GLMovementAccrualRepository;
import com.example.moneymarket.repository.GLMovementRepository;
import com.example.moneymarket.repository.GLSetupRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service for Statement of GL (General Ledger) generation
 * ✅ ISSUE 3: New feature to download GL account statements
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GLStatementService {

    private final GLBalanceRepository glBalanceRepository;
    private final GLMovementRepository glMovementRepository;
    private final GLMovementAccrualRepository glMovementAccrualRepository;
    private final GLSetupRepository glSetupRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    /**
     * Generate Statement of GL as Excel file
     *
     * @param glNum GL account number
     * @param fromDate Start date
     * @param toDate End date
     * @param format File format (currently only "excel" supported)
     * @return Excel file as byte array
     */
    @Transactional(readOnly = true)
    public byte[] generateGLStatement(String glNum, LocalDate fromDate, LocalDate toDate, String format) {
        log.info("Generating Statement of GL for GL {} from {} to {}", glNum, fromDate, toDate);

        // Step 1: Validate inputs
        validateGLStatementRequest(glNum, fromDate, toDate);

        // Step 2: Get GL details
        GLSetup glSetup = glSetupRepository.findById(glNum)
                .orElseThrow(() -> new ResourceNotFoundException("GL Account", "GL Number", glNum));

        // Step 3: Get opening balance
        BigDecimal openingBalance = getGLOpeningBalance(glNum, fromDate);

        // Step 4: Query all GL movements in date range (both regular and accrual)
        List<GLMovementEntry> movements = getAllGLMovements(glNum, fromDate, toDate);

        log.info("Found {} GL movements for GL {} in date range", movements.size(), glNum);

        // Step 5: Calculate closing balance
        BigDecimal closingBalance = calculateClosingBalance(openingBalance, movements);

        // Step 6: Generate Excel file
        try {
            return generateExcelGLStatement(glSetup, openingBalance, closingBalance, movements, fromDate, toDate);
        } catch (IOException e) {
            log.error("Error generating Excel GL Statement for GL {}: {}", glNum, e.getMessage(), e);
            throw new BusinessException("Failed to generate GL statement: " + e.getMessage());
        }
    }

    /**
     * Validate GL statement request parameters
     */
    private void validateGLStatementRequest(String glNum, LocalDate fromDate, LocalDate toDate) {
        if (glNum == null || glNum.trim().isEmpty()) {
            throw new IllegalArgumentException("GL number is required");
        }

        if (fromDate == null) {
            throw new IllegalArgumentException("From date is required");
        }

        if (toDate == null) {
            throw new IllegalArgumentException("To date is required");
        }

        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("From date must be before or equal to To date");
        }

        if (fromDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("From date cannot be in the future");
        }

        if (toDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("To date cannot be in the future");
        }

        // Check 6-month maximum range
        long monthsBetween = ChronoUnit.MONTHS.between(fromDate, toDate);
        if (monthsBetween > 6) {
            throw new IllegalArgumentException("Date range cannot exceed 6 months");
        }

        // Verify GL exists
        if (!glSetupRepository.existsById(glNum)) {
            throw new ResourceNotFoundException("GL Account", "GL Number", glNum);
        }
    }

    /**
     * Get opening balance for GL account
     */
    private BigDecimal getGLOpeningBalance(String glNum, LocalDate fromDate) {
        // Try to get balance from previous day
        LocalDate previousDay = fromDate.minusDays(1);
        Optional<GLBalance> previousDayBalance = glBalanceRepository.findByGlNumAndTranDate(glNum, previousDay);
        
        if (previousDayBalance.isPresent()) {
            return previousDayBalance.get().getClosingBal() != null 
                    ? previousDayBalance.get().getClosingBal() 
                    : BigDecimal.ZERO;
        }

        // Try to get last available balance before fromDate
        List<GLBalance> balances = glBalanceRepository
                .findByGlNumAndTranDateBeforeOrderByTranDateDesc(glNum, fromDate);
        
        if (!balances.isEmpty()) {
            return balances.get(0).getClosingBal() != null 
                    ? balances.get(0).getClosingBal() 
                    : BigDecimal.ZERO;
        }

        log.debug("No opening balance found for GL {}, defaulting to 0", glNum);
        return BigDecimal.ZERO;
    }

    /**
     * Get all GL movements (regular + accrual) for the date range
     */
    private List<GLMovementEntry> getAllGLMovements(String glNum, LocalDate fromDate, LocalDate toDate) {
        List<GLMovementEntry> allMovements = new ArrayList<>();

        // Get regular GL movements
        List<GLMovement> regularMovements = glMovementRepository
                .findByGlSetup_GlNumAndTranDateBetween(glNum, fromDate, toDate);
        
        for (GLMovement movement : regularMovements) {
            allMovements.add(GLMovementEntry.builder()
                    .date(movement.getTranDate())
                    .valueDate(movement.getValueDate())
                    .description(movement.getNarration() != null 
                            ? movement.getNarration() 
                            : "Transaction " + movement.getTransaction().getTranId())
                    .tranId(movement.getTransaction().getTranId())
                    .debit(movement.getDrCrFlag() == com.example.moneymarket.entity.TranTable.DrCrFlag.D 
                            ? movement.getAmount() 
                            : BigDecimal.ZERO)
                    .credit(movement.getDrCrFlag() == com.example.moneymarket.entity.TranTable.DrCrFlag.C 
                            ? movement.getAmount() 
                            : BigDecimal.ZERO)
                    .source("GL Movement")
                    .build());
        }

        // Get accrual GL movements
        List<GLMovementAccrual> accrualMovements = glMovementAccrualRepository
                .findByGlSetupGlNumAndAccrualDateBetween(glNum, fromDate, toDate);
        
        for (GLMovementAccrual accrual : accrualMovements) {
            allMovements.add(GLMovementEntry.builder()
                    .date(accrual.getAccrualDate())
                    .valueDate(accrual.getAccrualDate()) // Accruals don't have separate value date
                    .description(accrual.getNarration() != null 
                            ? accrual.getNarration() 
                            : "Interest Accrual " + accrual.getAccrual().getAccrTranId())
                    .tranId(accrual.getTranId() != null 
                            ? accrual.getTranId() 
                            : accrual.getAccrual().getAccrTranId())
                    .debit(accrual.getDrCrFlag() == com.example.moneymarket.entity.TranTable.DrCrFlag.D 
                            ? accrual.getAmount() 
                            : BigDecimal.ZERO)
                    .credit(accrual.getDrCrFlag() == com.example.moneymarket.entity.TranTable.DrCrFlag.C 
                            ? accrual.getAmount() 
                            : BigDecimal.ZERO)
                    .source("Interest Accrual")
                    .build());
        }

        // Sort by date (and value date as secondary)
        allMovements.sort(Comparator
                .comparing(GLMovementEntry::getDate)
                .thenComparing(GLMovementEntry::getValueDate)
                .thenComparing(GLMovementEntry::getTranId));

        return allMovements;
    }

    /**
     * Calculate closing balance from opening balance and movements
     */
    private BigDecimal calculateClosingBalance(BigDecimal openingBalance, List<GLMovementEntry> movements) {
        BigDecimal balance = openingBalance;
        
        for (GLMovementEntry movement : movements) {
            balance = balance.add(movement.getCredit()).subtract(movement.getDebit());
        }
        
        return balance;
    }

    /**
     * Generate Excel file for Statement of GL
     */
    private byte[] generateExcelGLStatement(GLSetup glSetup, BigDecimal openingBalance,
                                             BigDecimal closingBalance, List<GLMovementEntry> movements,
                                             LocalDate fromDate, LocalDate toDate) throws IOException {

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("GL Statement");

            // Create cell styles
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle amountStyle = createAmountStyle(workbook);
            CellStyle dateStyle = createDateStyle(workbook);
            CellStyle boldStyle = createBoldStyle(workbook);

            int rowNum = 0;

            // Write header section
            rowNum = writeHeaderSection(sheet, glSetup, fromDate, toDate, openingBalance, 
                    headerStyle, boldStyle, rowNum);

            // Write column headers
            rowNum = writeColumnHeaders(sheet, columnHeaderStyle, rowNum);

            // Write data rows with running balance
            BigDecimal runningBalance = openingBalance;
            for (GLMovementEntry movement : movements) {
                runningBalance = runningBalance.add(movement.getCredit()).subtract(movement.getDebit());
                movement.setBalance(runningBalance);
            }
            
            rowNum = writeDataRows(sheet, movements, dataStyle, amountStyle, dateStyle, rowNum);

            // Write footer section
            writeFooterSection(sheet, openingBalance, closingBalance, movements, boldStyle, amountStyle, rowNum);

            // Auto-size columns
            for (int i = 0; i < 8; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 1000); // Add padding
            }

            // Write to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    /**
     * Write header section of the Excel file
     */
    private int writeHeaderSection(Sheet sheet, GLSetup glSetup, LocalDate fromDate,
                                    LocalDate toDate, BigDecimal openingBalance, CellStyle headerStyle,
                                    CellStyle boldStyle, int rowNum) {
        // Row 1: Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("STATEMENT OF GENERAL LEDGER");
        titleCell.setCellStyle(headerStyle);

        // Row 2: Generated Date
        Row genDateRow = sheet.createRow(rowNum++);
        Cell genDateLabelCell = genDateRow.createCell(0);
        genDateLabelCell.setCellValue("Generated Date:");
        genDateLabelCell.setCellStyle(boldStyle);
        Cell genDateValueCell = genDateRow.createCell(1);
        genDateValueCell.setCellValue(LocalDate.now().format(DATE_FORMATTER));

        // Row 3: Empty
        rowNum++;

        // Row 4: GL Code
        Row glCodeRow = sheet.createRow(rowNum++);
        Cell glCodeLabelCell = glCodeRow.createCell(0);
        glCodeLabelCell.setCellValue("GL Code:");
        glCodeLabelCell.setCellStyle(boldStyle);
        Cell glCodeValueCell = glCodeRow.createCell(1);
        glCodeValueCell.setCellValue(glSetup.getGlNum());

        // Row 5: GL Name
        Row glNameRow = sheet.createRow(rowNum++);
        Cell glNameLabelCell = glNameRow.createCell(0);
        glNameLabelCell.setCellValue("GL Name:");
        glNameLabelCell.setCellStyle(boldStyle);
        Cell glNameValueCell = glNameRow.createCell(1);
        glNameValueCell.setCellValue(glSetup.getGlName());

        // Row 6: Currency
        Row currencyRow = sheet.createRow(rowNum++);
        Cell currencyLabelCell = currencyRow.createCell(0);
        currencyLabelCell.setCellValue("Currency:");
        currencyLabelCell.setCellStyle(boldStyle);
        Cell currencyValueCell = currencyRow.createCell(1);
        currencyValueCell.setCellValue("BDT"); // GL balances are always in BDT

        // Row 7: Period
        Row periodRow = sheet.createRow(rowNum++);
        Cell periodLabelCell = periodRow.createCell(0);
        periodLabelCell.setCellValue("Period:");
        periodLabelCell.setCellStyle(boldStyle);
        Cell periodValueCell = periodRow.createCell(1);
        periodValueCell.setCellValue(fromDate.format(DATE_FORMATTER) + " to " + toDate.format(DATE_FORMATTER));

        // Row 8: Opening Balance
        Row openingBalRow = sheet.createRow(rowNum++);
        Cell openingBalLabelCell = openingBalRow.createCell(0);
        openingBalLabelCell.setCellValue("Opening Balance:");
        openingBalLabelCell.setCellStyle(boldStyle);
        Cell openingBalValueCell = openingBalRow.createCell(1);
        openingBalValueCell.setCellValue(formatAmount(openingBalance));

        // Row 9: Empty
        rowNum++;

        return rowNum;
    }

    /**
     * Write column headers
     */
    private int writeColumnHeaders(Sheet sheet, CellStyle columnHeaderStyle, int rowNum) {
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"Date", "Value Date", "Transaction ID", "Description", "Source", "Debit", "Credit", "Balance"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(columnHeaderStyle);
        }
        return rowNum;
    }

    /**
     * Write data rows
     */
    private int writeDataRows(Sheet sheet, List<GLMovementEntry> movements, CellStyle dataStyle,
                               CellStyle amountStyle, CellStyle dateStyle, int rowNum) {
        for (GLMovementEntry movement : movements) {
            Row dataRow = sheet.createRow(rowNum++);

            // Date
            Cell dateCell = dataRow.createCell(0);
            dateCell.setCellValue(movement.getDate().format(DATE_FORMATTER));
            dateCell.setCellStyle(dateStyle);

            // Value Date
            Cell valueDateCell = dataRow.createCell(1);
            valueDateCell.setCellValue(movement.getValueDate().format(DATE_FORMATTER));
            valueDateCell.setCellStyle(dateStyle);

            // Transaction ID
            Cell tranIdCell = dataRow.createCell(2);
            tranIdCell.setCellValue(movement.getTranId());
            tranIdCell.setCellStyle(dataStyle);

            // Description
            Cell descCell = dataRow.createCell(3);
            descCell.setCellValue(movement.getDescription() != null ? movement.getDescription() : "");
            descCell.setCellStyle(dataStyle);

            // Source
            Cell sourceCell = dataRow.createCell(4);
            sourceCell.setCellValue(movement.getSource());
            sourceCell.setCellStyle(dataStyle);

            // Debit
            Cell debitCell = dataRow.createCell(5);
            if (movement.getDebit().compareTo(BigDecimal.ZERO) > 0) {
                debitCell.setCellValue(formatAmount(movement.getDebit()));
            } else {
                debitCell.setCellValue("");
            }
            debitCell.setCellStyle(amountStyle);

            // Credit
            Cell creditCell = dataRow.createCell(6);
            if (movement.getCredit().compareTo(BigDecimal.ZERO) > 0) {
                creditCell.setCellValue(formatAmount(movement.getCredit()));
            } else {
                creditCell.setCellValue("");
            }
            creditCell.setCellStyle(amountStyle);

            // Balance
            Cell balanceCell = dataRow.createCell(7);
            balanceCell.setCellValue(formatAmount(movement.getBalance()));
            balanceCell.setCellStyle(amountStyle);
        }
        return rowNum;
    }

    /**
     * Write footer section
     */
    private void writeFooterSection(Sheet sheet, BigDecimal openingBalance, BigDecimal closingBalance,
                                     List<GLMovementEntry> movements, CellStyle boldStyle,
                                     CellStyle amountStyle, int rowNum) {
        // Calculate totals
        BigDecimal totalDebits = movements.stream()
                .map(GLMovementEntry::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = movements.stream()
                .map(GLMovementEntry::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Empty row
        rowNum++;

        // Closing Balance
        Row closingBalRow = sheet.createRow(rowNum++);
        Cell closingBalLabelCell = closingBalRow.createCell(0);
        closingBalLabelCell.setCellValue("Closing Balance:");
        closingBalLabelCell.setCellStyle(boldStyle);
        Cell closingBalValueCell = closingBalRow.createCell(1);
        closingBalValueCell.setCellValue(formatAmount(closingBalance));
        closingBalValueCell.setCellStyle(amountStyle);

        // Total Debits
        Row totalDebitsRow = sheet.createRow(rowNum++);
        Cell totalDebitsLabelCell = totalDebitsRow.createCell(0);
        totalDebitsLabelCell.setCellValue("Total Debits:");
        totalDebitsLabelCell.setCellStyle(boldStyle);
        Cell totalDebitsValueCell = totalDebitsRow.createCell(1);
        totalDebitsValueCell.setCellValue(formatAmount(totalDebits));
        totalDebitsValueCell.setCellStyle(amountStyle);

        // Total Credits
        Row totalCreditsRow = sheet.createRow(rowNum++);
        Cell totalCreditsLabelCell = totalCreditsRow.createCell(0);
        totalCreditsLabelCell.setCellValue("Total Credits:");
        totalCreditsLabelCell.setCellStyle(boldStyle);
        Cell totalCreditsValueCell = totalCreditsRow.createCell(1);
        totalCreditsValueCell.setCellValue(formatAmount(totalCredits));
        totalCreditsValueCell.setCellStyle(amountStyle);
    }

    /**
     * Get list of all GL accounts for dropdown
     */
    @Transactional(readOnly = true)
    public List<GLOptionDTO> getGLList() {
        List<GLSetup> glAccounts = glSetupRepository.findAll();
        
        List<GLOptionDTO> options = new ArrayList<>();
        for (GLSetup gl : glAccounts) {
            options.add(GLOptionDTO.builder()
                    .glNum(gl.getGlNum())
                    .glName(gl.getGlName())
                    .build());
        }
        
        // Sort by GL number
        options.sort(Comparator.comparing(GLOptionDTO::getGlNum));
        
        return options;
    }

    /**
     * Format amount with 2 decimal places
     */
    private String formatAmount(BigDecimal amount) {
        return String.format("%,.2f", amount);
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
        font.setFontHeightInPoints((short) 11);
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
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createDateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
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

    /**
     * Inner class for GL movement entries (unified view of regular + accrual movements)
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GLMovementEntry {
        private LocalDate date;
        private LocalDate valueDate;
        private String description;
        private String tranId;
        private BigDecimal debit;
        private BigDecimal credit;
        private String source;
        private BigDecimal balance; // Running balance
    }

    /**
     * DTO for GL dropdown options
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GLOptionDTO {
        private String glNum;
        private String glName;
    }
}
