package com.example.moneymarket.exception;

import lombok.Getter;
import java.time.LocalDate;

@Getter
public class BODNotExecutedException extends RuntimeException {

    private final int pendingScheduleCount;
    private final LocalDate scheduleDate;

    public BODNotExecutedException(int pendingScheduleCount, LocalDate scheduleDate) {
        super(String.format(
            "BOD has not been executed for %s. There are %d pending deal schedule(s) that must be " +
            "processed via BOD before any transactions can be posted.",
            scheduleDate, pendingScheduleCount));
        this.pendingScheduleCount = pendingScheduleCount;
        this.scheduleDate = scheduleDate;
    }
}
