package com.example.moneymarket.repository;

import com.example.moneymarket.entity.InttAccrTran;
import com.example.moneymarket.entity.InttAccrTran.AccrualStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InttAccrTranRepository extends JpaRepository<InttAccrTran, String> {
    
    List<InttAccrTran> findByAccountNo(String accountNo);
    
    List<InttAccrTran> findByStatus(AccrualStatus status);
    
    List<InttAccrTran> findByAccrualDate(LocalDate accrualDate);
    
    List<InttAccrTran> findByAccrualDateAndStatus(LocalDate accrualDate, AccrualStatus status);
    
    List<InttAccrTran> findByAccountNoAndAccrualDateBetween(String accountNo, LocalDate startDate, LocalDate endDate);
    
    /**
     * Count interest accrual transactions for a specific date
     * Used for generating sequence numbers in interest accrual ID generation
     *
     * @param tranDate The transaction date
     * @return Count of interest accrual transactions for the date
     */
    long countByTranDate(LocalDate tranDate);

    /**
     * Count interest accrual transactions for a specific accrual date
     */
    long countByAccrualDate(LocalDate accrualDate);

    /**
     * Find the maximum sequential number used for a specific accrual date
     * Extracts the 9-digit sequential part from IDs like S20251020000000001-1
     *
     * @param accrualDate The accrual date to check
     * @return Optional containing the maximum sequential number, or empty if no records exist
     */
    @Query(value = "SELECT MAX(CAST(SUBSTRING(Accr_Tran_Id, 10, 9) AS UNSIGNED)) " +
                   "FROM intt_accr_tran " +
                   "WHERE Accrual_Date = :accrualDate " +
                   "AND Accr_Tran_Id LIKE CONCAT('S', DATE_FORMAT(:accrualDate, '%Y%m%d'), '%')",
           nativeQuery = true)
    Optional<Integer> findMaxSequentialByAccrualDate(@Param("accrualDate") LocalDate accrualDate);

    /**
     * Find the maximum sequential number used for a specific transaction date and prefix
     * Extracts the 9-digit sequential part from IDs like V20251020000000001-1
     *
     * @param tranDate The transaction date to check
     * @param prefix The prefix character (e.g., 'V' for value date interest, 'S' for regular interest)
     * @return Optional containing the maximum sequential number, or empty if no records exist
     */
    @Query(value = "SELECT MAX(CAST(SUBSTRING(Accr_Tran_Id, 10, 9) AS UNSIGNED)) " +
                   "FROM intt_accr_tran " +
                   "WHERE Tran_Date = :tranDate " +
                   "AND Accr_Tran_Id LIKE CONCAT(:prefix, DATE_FORMAT(:tranDate, '%Y%m%d'), '%')",
           nativeQuery = true)
    Optional<Integer> findMaxSequentialByTranDateAndPrefix(@Param("tranDate") LocalDate tranDate,
                                                            @Param("prefix") String prefix);

    /**
     * Sum debit amounts for CAPITALIZATION transactions (C type) only
     * CRITICAL: Only sums transactions where:
     * - Accr_Tran_Id starts with 'C' (Interest Capitalization)
     * - Dr_Cr_Flag = 'D' (Debit)
     * - Original_Dr_Cr_Flag IS NULL (excludes value date interest)
     * 
     * This ensures dr_summation ONLY includes capitalization debits, NOT accrual debits.
     * 
     * @param accountNo The account number
     * @param accrualDate The accrual date
     * @return Sum of C type debit amounts only
     */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM InttAccrTran i " +
           "WHERE i.accountNo = :accountNo " +
           "AND i.accrualDate = :accrualDate " +
           "AND i.accrTranId LIKE 'C%' " +
           "AND i.drCrFlag = 'D' " +
           "AND i.originalDrCrFlag IS NULL")
    BigDecimal sumDebitAmountsByAccountAndDate(@Param("accountNo") String accountNo,
                                                @Param("accrualDate") LocalDate accrualDate);

    /**
     * Sum credit amounts for ACCRUAL transactions (S type) only
     * CRITICAL: Only sums transactions where:
     * - Accr_Tran_Id starts with 'S' (System interest accrual)
     * - Dr_Cr_Flag = 'C' (Credit)
     * - Original_Dr_Cr_Flag IS NULL (excludes value date interest)
     * 
     * This ensures cr_summation ONLY includes daily accrual credits, NOT capitalization credits.
     * 
     * @param accountNo The account number
     * @param accrualDate The accrual date
     * @return Sum of S type credit amounts only
     */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM InttAccrTran i " +
           "WHERE i.accountNo = :accountNo " +
           "AND i.accrualDate = :accrualDate " +
           "AND i.accrTranId LIKE 'S%' " +
           "AND i.drCrFlag = 'C' " +
           "AND i.originalDrCrFlag IS NULL")
    BigDecimal sumCreditAmountsByAccountAndDate(@Param("accountNo") String accountNo,
                                                 @Param("accrualDate") LocalDate accrualDate);

    /**
     * Find distinct account numbers for a specific accrual date
     *
     * @param accrualDate The accrual date
     * @return List of unique account numbers
     */
    @Query("SELECT DISTINCT i.accountNo FROM InttAccrTran i WHERE i.accrualDate = :accrualDate")
    List<String> findDistinctAccountsByAccrualDate(@Param("accrualDate") LocalDate accrualDate);

    /**
     * Find value date interest records for a specific account and accrual date
     * Value date interest records are identified by having a non-null Original_Dr_Cr_Flag
     * 
     * IMPORTANT: Only returns records where Dr_Cr_Flag = Original_Dr_Cr_Flag
     * This filters to only the Balance Sheet side record (affects account balance)
     * and excludes the P&L side record (Interest Income/Expense) to prevent double counting.
     * 
     * For value date interest, two records are created:
     * 1. Balance Sheet record: Dr_Cr_Flag = Original_Dr_Cr_Flag (affects account balance)
     * 2. P&L record: Dr_Cr_Flag != Original_Dr_Cr_Flag (does not affect account balance)
     * 
     * Only the Balance Sheet record should be included in account balance calculation.
     *
     * @param accountNo The account number
     * @param accrualDate The accrual date
     * @return List of value date interest records where Dr_Cr_Flag = Original_Dr_Cr_Flag
     */
    @Query("SELECT i FROM InttAccrTran i " +
           "WHERE i.accountNo = :accountNo " +
           "AND i.accrualDate = :accrualDate " +
           "AND i.originalDrCrFlag IS NOT NULL " +
           "AND i.drCrFlag = i.originalDrCrFlag")
    List<InttAccrTran> findByAccountNoAndAccrualDateAndOriginalDrCrFlagNotNull(
            @Param("accountNo") String accountNo,
            @Param("accrualDate") LocalDate accrualDate);
    
    /**
     * Find all transactions for a specific account and accrual date (for debugging)
     */
    List<InttAccrTran> findByAccountNoAndAccrualDate(String accountNo, LocalDate accrualDate);
}
