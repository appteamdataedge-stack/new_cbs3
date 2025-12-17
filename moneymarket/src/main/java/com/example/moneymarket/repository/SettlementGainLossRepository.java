package com.example.moneymarket.repository;

import com.example.moneymarket.entity.SettlementGainLoss;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Settlement Gain/Loss operations
 * Manages settlement gain/loss tracking and reporting
 */
@Repository
public interface SettlementGainLossRepository extends JpaRepository<SettlementGainLoss, Long> {

    /**
     * Find all settlement records for a specific transaction
     */
    List<SettlementGainLoss> findByTranId(String tranId);

    /**
     * Find all settlement records for a specific account
     */
    List<SettlementGainLoss> findByAccountNo(String accountNo);

    /**
     * Find all settlement records for a specific date
     */
    List<SettlementGainLoss> findByTranDate(LocalDate tranDate);

    /**
     * Find all settlement records for a specific currency
     */
    List<SettlementGainLoss> findByCurrency(String currency);

    /**
     * Find all settlement records by type (GAIN or LOSS)
     */
    List<SettlementGainLoss> findBySettlementType(String settlementType);

    /**
     * Find all settlement records for a specific date and type
     */
    List<SettlementGainLoss> findByTranDateAndSettlementType(LocalDate tranDate, String settlementType);

    /**
     * Get total settlement gain for a specific date
     */
    @Query("SELECT SUM(s.settlementAmt) FROM SettlementGainLoss s " +
           "WHERE s.tranDate = :tranDate AND s.settlementType = 'GAIN' AND s.status = 'POSTED'")
    Optional<BigDecimal> getTotalGainByDate(@Param("tranDate") LocalDate tranDate);

    /**
     * Get total settlement loss for a specific date
     */
    @Query("SELECT SUM(s.settlementAmt) FROM SettlementGainLoss s " +
           "WHERE s.tranDate = :tranDate AND s.settlementType = 'LOSS' AND s.status = 'POSTED'")
    Optional<BigDecimal> getTotalLossByDate(@Param("tranDate") LocalDate tranDate);

    /**
     * Get total settlement for an account
     */
    @Query("SELECT SUM(CASE WHEN s.settlementType = 'GAIN' THEN s.settlementAmt ELSE -s.settlementAmt END) " +
           "FROM SettlementGainLoss s WHERE s.accountNo = :accountNo AND s.status = 'POSTED'")
    Optional<BigDecimal> getNetSettlementByAccount(@Param("accountNo") String accountNo);

    /**
     * Find settlement records within a date range
     */
    @Query("SELECT s FROM SettlementGainLoss s WHERE s.tranDate BETWEEN :startDate AND :endDate " +
           "AND s.status = 'POSTED' ORDER BY s.tranDate DESC, s.createdOn DESC")
    List<SettlementGainLoss> findByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Get settlement statistics by currency for a date range
     */
    @Query("SELECT s.currency, s.settlementType, COUNT(*), SUM(s.settlementAmt) " +
           "FROM SettlementGainLoss s WHERE s.tranDate BETWEEN :startDate AND :endDate " +
           "AND s.status = 'POSTED' GROUP BY s.currency, s.settlementType")
    List<Object[]> getSettlementStatsByCurrency(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Find by transaction date and status
     */
    List<SettlementGainLoss> findByTranDateAndStatus(LocalDate tranDate, String status);

    /**
     * Find by transaction date range and status
     */
    List<SettlementGainLoss> findByTranDateBetweenAndStatus(
        LocalDate startDate,
        LocalDate endDate,
        String status
    );

    /**
     * Find by currency, date range, and status
     */
    List<SettlementGainLoss> findByCurrencyAndTranDateBetweenAndStatus(
        String currency,
        LocalDate startDate,
        LocalDate endDate,
        String status
    );

    /**
     * Find by account number, date range, and status
     */
    List<SettlementGainLoss> findByAccountNoAndTranDateBetweenAndStatus(
        String accountNo,
        LocalDate startDate,
        LocalDate endDate,
        String status
    );
}
