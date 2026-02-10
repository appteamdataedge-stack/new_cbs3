package com.example.moneymarket.repository;

import com.example.moneymarket.entity.AcctBalLcy;
import com.example.moneymarket.entity.AcctBalLcyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Acct_Bal_LCY table
 * Manages account balances in Local Currency (BDT)
 */
@Repository
public interface AcctBalLcyRepository extends JpaRepository<AcctBalLcy, AcctBalLcyId> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT abl FROM AcctBalLcy abl WHERE abl.accountNo = ?1 AND abl.tranDate = ?2")
    Optional<AcctBalLcy> findByAccountNoAndTranDateWithLock(String accountNo, LocalDate tranDate);
    
    @Query("SELECT abl FROM AcctBalLcy abl WHERE abl.accountNo = ?1 AND abl.tranDate = ?2")
    Optional<AcctBalLcy> findByAccountNoAndTranDate(String accountNo, LocalDate tranDate);
    
    @Query("SELECT abl FROM AcctBalLcy abl WHERE abl.accountNo = ?1 ORDER BY abl.tranDate DESC")
    List<AcctBalLcy> findByAccountNoOrderByTranDateDesc(String accountNo);
    
    @Query("SELECT abl FROM AcctBalLcy abl WHERE abl.accountNo = ?1 AND abl.tranDate < ?2 ORDER BY abl.tranDate DESC")
    List<AcctBalLcy> findByAccountNoAndTranDateBeforeOrderByTranDateDesc(String accountNo, LocalDate tranDate);
    
    @Query("SELECT abl FROM AcctBalLcy abl WHERE abl.tranDate = ?1")
    List<AcctBalLcy> findByTranDate(LocalDate tranDate);
    
    @Query("SELECT abl FROM AcctBalLcy abl WHERE abl.tranDate = ?1 AND abl.accountNo = ?2")
    Optional<AcctBalLcy> findByTranDateAndAccountNo(LocalDate tranDate, String accountNo);
    
    /**
     * Get the latest balance record for an account (for current balance)
     */
    default Optional<AcctBalLcy> findLatestByAccountNo(String accountNo) {
        List<AcctBalLcy> balances = findByAccountNoOrderByTranDateDesc(accountNo);
        return balances.isEmpty() ? Optional.empty() : Optional.of(balances.get(0));
    }
}
