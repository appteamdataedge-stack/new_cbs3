package com.example.moneymarket.repository;

import com.example.moneymarket.entity.CurrencyMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Currency Master
 */
@Repository
public interface CurrencyMasterRepository extends JpaRepository<CurrencyMaster, String> {

    /**
     * Find currency by code
     */
    Optional<CurrencyMaster> findByCcyCode(String ccyCode);

    /**
     * Find all active currencies
     */
    List<CurrencyMaster> findByIsActiveTrue();

    /**
     * Find base currency
     */
    Optional<CurrencyMaster> findByIsBaseCcyTrue();

    /**
     * Check if currency is active
     */
    @Query("SELECT c.isActive FROM CurrencyMaster c WHERE c.ccyCode = :ccyCode")
    Boolean isCurrencyActive(String ccyCode);
}
