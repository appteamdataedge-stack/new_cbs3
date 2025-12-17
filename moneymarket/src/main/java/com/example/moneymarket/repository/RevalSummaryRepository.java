package com.example.moneymarket.repository;

import com.example.moneymarket.entity.RevalSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Revaluation Summary operations
 * Manages daily revaluation summary tracking and reporting
 */
@Repository
public interface RevalSummaryRepository extends JpaRepository<RevalSummary, Long> {

    /**
     * Find revaluation summary by date
     */
    Optional<RevalSummary> findByRevalDate(LocalDate revalDate);

    /**
     * Find all revaluation summaries within a date range
     */
    @Query("SELECT r FROM RevalSummary r WHERE r.revalDate BETWEEN :startDate AND :endDate " +
           "ORDER BY r.revalDate DESC")
    List<RevalSummary> findByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Find all completed revaluation summaries
     */
    List<RevalSummary> findByStatusOrderByRevalDateDesc(String status);

    /**
     * Find all revaluation summaries that have been reversed
     */
    @Query("SELECT r FROM RevalSummary r WHERE r.reversedOn IS NOT NULL ORDER BY r.revalDate DESC")
    List<RevalSummary> findReversedRevaluations();

    /**
     * Find all revaluation summaries that have not been reversed
     */
    @Query("SELECT r FROM RevalSummary r WHERE r.reversedOn IS NULL AND r.status = 'COMPLETED' " +
           "ORDER BY r.revalDate DESC")
    List<RevalSummary> findUnreversedRevaluations();

    /**
     * Get the most recent revaluation summary
     */
    Optional<RevalSummary> findFirstByOrderByRevalDateDesc();

    /**
     * Get the most recent completed revaluation
     */
    Optional<RevalSummary> findFirstByStatusOrderByRevalDateDesc(String status);
}
