package com.example.moneymarket.repository;

import com.example.moneymarket.entity.GLMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface GLMovementRepository extends JpaRepository<GLMovement, Long> {
    
    List<GLMovement> findByGlSetupGlNum(String glNum);
    
    List<GLMovement> findByTransactionTranId(String tranId);
    
    List<GLMovement> findByTranDate(LocalDate tranDate);
    
    List<GLMovement> findByValueDate(LocalDate valueDate);
    
    List<GLMovement> findByGlSetupGlNumAndTranDateBetween(String glNum, LocalDate startDate, LocalDate endDate);

    List<GLMovement> findByGlSetupAndTranDate(com.example.moneymarket.entity.GLSetup glSetup, LocalDate tranDate);

    boolean existsByTransactionTranId(String tranId);

    /**
     * FIX: Get unique GL numbers from GL movement records for a given date using native query
     * CHANGED: Replaced JPQL with native SQL to prevent GLSetup join issues with LAZY fetch
     *
     * @param tranDate The transaction date
     * @return List of unique GL numbers
     */
    @Query(value = "SELECT DISTINCT GL_Num FROM gl_movement WHERE Tran_Date = :tranDate", nativeQuery = true)
    List<String> findDistinctGLNumbersByTranDate(@Param("tranDate") LocalDate tranDate);

    /**
     * Get unique GL numbers from GL movement records for a date range
     * Used for GL Statement generation
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @return List of unique GL numbers
     */
    @Query(value = "SELECT DISTINCT GL_Num FROM gl_movement WHERE Tran_Date BETWEEN :fromDate AND :toDate", nativeQuery = true)
    List<String> findDistinctGLNumbersByTranDateBetween(@Param("fromDate") LocalDate fromDate, 
                                                         @Param("toDate") LocalDate toDate);

    /**
     * Find GL movements by GL number and date range
     * Used for GL Statement generation
     *
     * @param glNum The GL account number
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @return List of GL movements
     */
    List<GLMovement> findByGlSetup_GlNumAndTranDateBetween(String glNum, LocalDate fromDate, LocalDate toDate);

    /**
     * FIX: Native query to calculate DR/CR summation without joining GLSetup table
     * This prevents Hibernate duplicate-row assertion errors when GL_Num is not unique
     *
     * CRITICAL FIX: Changed to use LCY_Amt (Local Currency Amount) instead of Amount
     * This ensures correct calculation for multi-currency transactions
     *
     * Returns: [GL_Num, Total_DR_LCY_Amount, Total_CR_LCY_Amount]
     *
     * @param glNum The GL account number
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @return Object array containing [glNum, totalDr, totalCr]
     */
    @Transactional(readOnly = true)
    @Query(value = """
        SELECT
            GL_Num,
            COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'D' THEN LCY_Amt ELSE 0 END), 0) AS totalDr,
            COALESCE(SUM(CASE WHEN Dr_Cr_Flag = 'C' THEN LCY_Amt ELSE 0 END), 0) AS totalCr
        FROM gl_movement
        WHERE GL_Num = :glNum
          AND Tran_Date BETWEEN :fromDate AND :toDate
        GROUP BY GL_Num
        """, nativeQuery = true)
    List<Object[]> findDrCrSummationNative(@Param("glNum") String glNum,
                                           @Param("fromDate") LocalDate fromDate,
                                           @Param("toDate") LocalDate toDate);
}
