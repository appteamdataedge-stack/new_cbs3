package com.example.moneymarket.repository;

import com.example.moneymarket.entity.DealSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DealScheduleRepository extends JpaRepository<DealSchedule, Long> {

    List<DealSchedule> findByAccountNumberOrderByScheduleDateAsc(String accountNumber);

    List<DealSchedule> findByScheduleDateAndStatus(LocalDate scheduleDate, String status);

    List<DealSchedule> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumberAndEventCodeAndScheduleDate(
            String accountNumber, String eventCode, LocalDate scheduleDate);

    long countByScheduleDate(LocalDate scheduleDate);

    long countByScheduleDateAndStatus(LocalDate scheduleDate, String status);

    @Query("SELECT ds FROM DealSchedule ds WHERE ds.scheduleDate <= :businessDate AND ds.status = 'PENDING'")
    List<DealSchedule> findPendingSchedulesUpTo(@org.springframework.data.repository.query.Param("businessDate") LocalDate businessDate);
}
