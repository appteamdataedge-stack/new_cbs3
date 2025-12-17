package com.example.moneymarket.repository;

import com.example.moneymarket.entity.RevalTran;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Revaluation Transaction operations
 * Manages EOD revaluation entries and BOD reversals
 */
@Repository
public interface RevalTranRepository extends JpaRepository<RevalTran, Long> {

    /**
     * Find all revaluation transactions for a specific date and status
     * @param revalDate Revaluation date
     * @param status Status (POSTED or REVERSED)
     * @return List of revaluation transactions
     */
    List<RevalTran> findByRevalDateAndStatus(LocalDate revalDate, String status);

    /**
     * Find all posted revaluation transactions for a specific date
     * Used for BOD reversal process
     * @param revalDate Revaluation date
     * @return List of posted revaluation transactions
     */
    @Query("SELECT r FROM RevalTran r WHERE r.revalDate = :revalDate AND r.status = 'POSTED'")
    List<RevalTran> findPostedRevaluationsForDate(@Param("revalDate") LocalDate revalDate);

    /**
     * Find all revaluation transactions for a specific account
     * @param acctNum Account number
     * @return List of revaluation transactions
     */
    List<RevalTran> findByAcctNum(String acctNum);

    /**
     * Find all revaluation transactions for a specific account and status
     * @param acctNum Account number
     * @param status Status (POSTED or REVERSED)
     * @return List of revaluation transactions
     */
    List<RevalTran> findByAcctNumAndStatus(String acctNum, String status);

    /**
     * Find all revaluation transactions for a specific account and status, ordered by date descending
     * Used to get the most recent revaluation for baseline calculation
     * @param acctNum Account number
     * @param status Status (POSTED or REVERSED)
     * @return List of revaluation transactions ordered by revaluation date descending
     */
    List<RevalTran> findByAcctNumAndStatusOrderByRevalDateDesc(String acctNum, String status);

    /**
     * Find revaluation transaction by transaction ID
     * @param tranId Transaction ID from tran_table
     * @return Optional revaluation transaction
     */
    Optional<RevalTran> findByTranId(String tranId);

    /**
     * Find all unreversed revaluation transactions up to a specific date
     * Used to identify revaluations that need reversal
     * @param upToDate Upper date limit
     * @return List of unreversed revaluation transactions
     */
    @Query("SELECT r FROM RevalTran r WHERE r.revalDate < :upToDate AND r.status = 'POSTED' AND r.reversalTranId IS NULL")
    List<RevalTran> findUnreversedRevaluationsBeforeDate(@Param("upToDate") LocalDate upToDate);

    /**
     * Get total revaluation difference for an account
     * @param acctNum Account number
     * @param status Status filter
     * @return Total revaluation difference
     */
    @Query("SELECT SUM(r.revalDiff) FROM RevalTran r WHERE r.acctNum = :acctNum AND r.status = :status")
    Optional<java.math.BigDecimal> getTotalRevalDiffByAcctAndStatus(
        @Param("acctNum") String acctNum,
        @Param("status") String status
    );
}
