package com.example.moneymarket.repository;

import com.example.moneymarket.entity.ValueDateInttAccr;
import com.example.moneymarket.entity.ValueDateInttAccr.AccrualStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ValueDateInttAccrRepository extends JpaRepository<ValueDateInttAccr, String> {

    /**
     * Find value date interest accruals by account number
     */
    List<ValueDateInttAccr> findByAccountNo(String accountNo);

    /**
     * Find value date interest accruals by status
     */
    List<ValueDateInttAccr> findByStatus(AccrualStatus status);

    /**
     * Find value date interest accruals by transaction date
     */
    List<ValueDateInttAccr> findByTranDate(LocalDate tranDate);

    /**
     * Find value date interest accruals by accrual date
     */
    List<ValueDateInttAccr> findByAccrualDate(LocalDate accrualDate);

    /**
     * Find value date interest accruals by transaction date and status
     */
    List<ValueDateInttAccr> findByTranDateAndStatus(LocalDate tranDate, AccrualStatus status);

    /**
     * Check if value date interest already exists for a specific transaction
     * Prevents duplicate processing
     *
     * @param accountNo The account number
     * @param tranDate The transaction date
     * @param tranId The transaction ID
     * @return true if already processed, false otherwise
     */
    boolean existsByAccountNoAndTranDateAndTranId(String accountNo, LocalDate tranDate, String tranId);

    /**
     * Find the maximum sequential number used for a specific transaction date
     * Extracts the 9-digit sequential part from IDs like V20251020000000001-1
     *
     * @param tranDate The transaction date to check
     * @return Optional containing the maximum sequential number, or empty if no records exist
     */
    @Query(value = "SELECT MAX(CAST(SUBSTRING(Accr_Tran_Id, 10, 9) AS UNSIGNED)) " +
                   "FROM value_date_intt_accr " +
                   "WHERE Tran_Date = :tranDate " +
                   "AND Accr_Tran_Id LIKE CONCAT('V', DATE_FORMAT(:tranDate, '%Y%m%d'), '%')",
           nativeQuery = true)
    Optional<Integer> findMaxSequentialByTranDate(@Param("tranDate") LocalDate tranDate);

    /**
     * Find distinct account numbers for a specific transaction date
     *
     * @param tranDate The transaction date
     * @return List of unique account numbers
     */
    @Query("SELECT DISTINCT v.accountNo FROM ValueDateInttAccr v WHERE v.tranDate = :tranDate")
    List<String> findDistinctAccountsByTranDate(@Param("tranDate") LocalDate tranDate);

    /**
     * Count value date interest accrual records for a specific transaction date
     * Used for generating sequence numbers in ID generation
     *
     * @param tranDate The transaction date
     * @return Count of value date interest records for the date
     */
    long countByTranDate(LocalDate tranDate);
}
