package com.example.moneymarket.repository;

import com.example.moneymarket.entity.BodExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface BodExecutionLogRepository extends JpaRepository<BodExecutionLog, Long> {

    Optional<BodExecutionLog> findByExecutionDate(LocalDate executionDate);

    boolean existsByExecutionDateAndStatus(LocalDate executionDate, String status);
}
