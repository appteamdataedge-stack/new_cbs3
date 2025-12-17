package com.example.moneymarket.repository;

import com.example.moneymarket.entity.TranTable;
import com.example.moneymarket.entity.TranTable.TranStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TranTableRepository extends JpaRepository<TranTable, String> {
    
    List<TranTable> findByAccountNo(String accountNo);
    
    List<TranTable> findByTranStatus(TranStatus status);

    /**
     * Count transactions by status (all dates)
     * Used by Batch Job 1 to count all Entry status transactions
     *
     * @param status The transaction status
     * @return Count of transactions with the given status
     */
    Long countByTranStatus(TranStatus status);

    /**
     * Delete all transactions with a specific status (all dates)
     * Used by Batch Job 1 to delete all Entry status transactions at EOD
     *
     * @param status The transaction status
     * @return Number of transactions deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM TranTable t WHERE t.tranStatus = :status")
    int deleteByTranStatus(@Param("status") TranStatus status);

    List<TranTable> findByTranDateBetween(LocalDate startDate, LocalDate endDate);
    
    List<TranTable> findByValueDateBetween(LocalDate startDate, LocalDate endDate);
    
    List<TranTable> findByTranDateAndTranStatus(LocalDate tranDate, TranStatus status);
    
    /**
     * Count transactions for a specific date
     * Used for generating sequence numbers in transaction ID generation
     * 
     * @param tranDate The transaction date
     * @return Count of transactions for the date
     */
    long countByTranDate(LocalDate tranDate);
    
    /**
     * Sum all debit transactions for an account on a specific date
     * 
     * @param accountNo The account number
     * @param tranDate The transaction date
     * @return The sum of debit amounts
     */
    @Query("SELECT COALESCE(SUM(t.debitAmount), 0) FROM TranTable t " +
           "WHERE t.accountNo = :accountNo AND t.tranDate = :tranDate")
    Optional<BigDecimal> sumDebitTransactionsForAccountOnDate(
            @Param("accountNo") String accountNo, 
            @Param("tranDate") LocalDate tranDate);
    
    /**
     * Sum all credit transactions for an account on a specific date
     * 
     * @param accountNo The account number
     * @param tranDate The transaction date
     * @return The sum of credit amounts
     */
    @Query("SELECT COALESCE(SUM(t.creditAmount), 0) FROM TranTable t " +
           "WHERE t.accountNo = :accountNo AND t.tranDate = :tranDate")
    Optional<BigDecimal> sumCreditTransactionsForAccountOnDate(
            @Param("accountNo") String accountNo, 
            @Param("tranDate") LocalDate tranDate);
    
    /**
     * Find transactions by account number and transaction date
     *
     * @param accountNo The account number
     * @param tranDate The transaction date
     * @return List of transactions
     */
    List<TranTable> findByAccountNoAndTranDate(String accountNo, LocalDate tranDate);

    /**
     * Find transactions with value date gap (Tran_Date > Value_Date)
     * Used by Batch Job 1 to calculate value date interest
     *
     * @param systemDate The system date (Tran_Date to match)
     * @return List of transactions where Tran_Date > Value_Date and status is Verified
     */
    @Query("SELECT t FROM TranTable t WHERE " +
           "t.tranDate = :systemDate AND " +
           "t.tranStatus = 'Verified' AND " +
           "t.tranDate > t.valueDate " +
           "ORDER BY t.accountNo")
    List<TranTable> findTransactionsWithValueDateGap(@Param("systemDate") LocalDate systemDate);

    /**
     * Count transactions by date and status
     * Used by Batch Job 1 to count Entry status transactions before deletion
     *
     * @param tranDate The transaction date
     * @param status The transaction status
     * @return Count of transactions matching the criteria
     */
    @Query("SELECT COUNT(t) FROM TranTable t WHERE t.tranDate = :tranDate AND t.tranStatus = :status")
    Long countByTranDateAndTranStatus(@Param("tranDate") LocalDate tranDate, @Param("status") TranStatus status);

    /**
     * Delete transactions by date and status
     * Used by Batch Job 1 to delete Entry status transactions at EOD
     *
     * @param tranDate The transaction date
     * @param status The transaction status
     * @return Number of transactions deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TranTable t WHERE t.tranDate = :tranDate AND t.tranStatus = :status")
    int deleteByTranDateAndTranStatus(@Param("tranDate") LocalDate tranDate, @Param("status") TranStatus status);

    /**
     * Find all transactions starting with a specific transaction ID prefix
     * Used for revaluation reversal to find all related transactions
     *
     * @param tranIdPrefix The transaction ID prefix to search for
     * @return List of transactions with matching prefix
     */
    List<TranTable> findByTranIdStartingWith(String tranIdPrefix);
}
