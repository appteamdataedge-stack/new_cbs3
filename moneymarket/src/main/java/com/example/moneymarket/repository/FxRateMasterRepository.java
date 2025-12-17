package com.example.moneymarket.repository;

import com.example.moneymarket.entity.FxRateMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FxRateMasterRepository extends JpaRepository<FxRateMaster, Long> {

    /**
     * Find the latest exchange rate for a currency pair on or before a given date
     * Query matches Rate_Date by date only (ignoring time component)
     *
     * @param ccyPair Currency pair format "USD/BDT", "EUR/BDT", etc.
     * @param rateDate The date to find the exchange rate for
     * @return The latest FX rate record
     */
    @Query(value = "SELECT * FROM fx_rate_master f WHERE f.Ccy_Pair = :ccyPair " +
           "AND DATE(f.Rate_Date) <= :rateDate " +
           "ORDER BY f.Rate_Date DESC, f.Rate_Id DESC LIMIT 1", nativeQuery = true)
    Optional<FxRateMaster> findLatestByCcyPairAndDate(
            @Param("ccyPair") String ccyPair,
            @Param("rateDate") LocalDate rateDate);

    /**
     * Find the absolute latest exchange rate for a currency pair
     * Used by RevaluationService
     *
     * @param ccyPair Currency pair format "USD/BDT", "EUR/BDT", etc.
     * @return The latest FX rate record
     */
    Optional<FxRateMaster> findFirstByCcyPairOrderByRateDateDesc(String ccyPair);

    /**
     * Find exchange rates within a date range
     */
    @Query("SELECT f FROM FxRateMaster f WHERE DATE(f.rateDate) BETWEEN :startDate AND :endDate ORDER BY f.rateDate DESC")
    List<FxRateMaster> findByRateDateBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Find exchange rates for a specific currency pair within a date range
     */
    @Query("SELECT f FROM FxRateMaster f WHERE f.ccyPair = :ccyPair " +
           "AND DATE(f.rateDate) BETWEEN :startDate AND :endDate ORDER BY f.rateDate DESC")
    List<FxRateMaster> findByCcyPairAndRateDateBetween(
            @Param("ccyPair") String ccyPair,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Find all exchange rates for a specific currency pair
     */
    List<FxRateMaster> findByCcyPairOrderByRateDateDesc(String ccyPair);

    /**
     * Find exchange rate by exact date and currency pair
     */
    @Query("SELECT f FROM FxRateMaster f WHERE f.ccyPair = :ccyPair " +
           "AND DATE(f.rateDate) = :rateDate ORDER BY f.rateDate DESC")
    Optional<FxRateMaster> findByCcyPairAndRateDate(
            @Param("ccyPair") String ccyPair,
            @Param("rateDate") LocalDate rateDate);

    /**
     * Get distinct currency pairs
     */
    @Query("SELECT DISTINCT f.ccyPair FROM FxRateMaster f ORDER BY f.ccyPair")
    List<String> findDistinctCcyPairs();

    /**
     * Check if exchange rate exists for date and currency pair
     */
    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END FROM FxRateMaster f " +
           "WHERE f.ccyPair = :ccyPair AND DATE(f.rateDate) = :rateDate")
    boolean existsByCcyPairAndRateDate(
            @Param("ccyPair") String ccyPair,
            @Param("rateDate") LocalDate rateDate);
}
