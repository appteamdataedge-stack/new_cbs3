package com.example.moneymarket.repository;

import com.example.moneymarket.entity.FxPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FxPositionRepository extends JpaRepository<FxPosition, Long> {

    Optional<FxPosition> findByTranDateAndPositionGlNumAndPositionCcy(LocalDate tranDate, String positionGlNum, String positionCcy);
    Optional<FxPosition> findTopByPositionGlNumAndPositionCcyAndTranDateLessThanOrderByTranDateDesc(
            String positionGlNum, String positionCcy, LocalDate tranDate);

    List<FxPosition> findByTranDate(LocalDate tranDate);

    @Query("SELECT fp FROM FxPosition fp WHERE fp.tranDate = :tranDate AND fp.positionCcy <> 'BDT'")
    List<FxPosition> findFcyByTranDate(@Param("tranDate") LocalDate tranDate);
}

