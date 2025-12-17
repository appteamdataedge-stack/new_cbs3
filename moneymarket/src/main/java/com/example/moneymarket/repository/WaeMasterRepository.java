package com.example.moneymarket.repository;

import com.example.moneymarket.entity.WaeMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for WAE Master operations
 * Manages Weighted Average Exchange rates for currency pairs
 */
@Repository
public interface WaeMasterRepository extends JpaRepository<WaeMaster, Long> {

    /**
     * Find WAE Master record by currency pair
     * @param ccyPair Currency pair (e.g., "USD/BDT", "EUR/BDT")
     * @return Optional WAE Master record
     */
    Optional<WaeMaster> findByCcyPair(String ccyPair);

    /**
     * Find WAE Master record by source GL account
     * @param sourceGl GL account number
     * @return Optional WAE Master record
     */
    Optional<WaeMaster> findBySourceGl(String sourceGl);

    /**
     * Check if WAE Master exists for currency pair
     * @param ccyPair Currency pair
     * @return true if exists, false otherwise
     */
    boolean existsByCcyPair(String ccyPair);

    /**
     * Get WAE rate for a specific currency pair
     * @param ccyPair Currency pair
     * @return WAE rate or null if not found
     */
    @Query("SELECT w.waeRate FROM WaeMaster w WHERE w.ccyPair = :ccyPair")
    Optional<java.math.BigDecimal> findWaeRateByCcyPair(@Param("ccyPair") String ccyPair);
}
