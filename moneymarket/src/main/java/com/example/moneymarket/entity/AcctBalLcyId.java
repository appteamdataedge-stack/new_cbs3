package com.example.moneymarket.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Composite ID class for AcctBalLcy entity
 * Required for JPA composite primary key (Tran_Date, Account_No)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcctBalLcyId implements Serializable {
    
    private LocalDate tranDate;
    private String accountNo;
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AcctBalLcyId that = (AcctBalLcyId) o;
        return tranDate.equals(that.tranDate) && accountNo.equals(that.accountNo);
    }
    
    @Override
    public int hashCode() {
        return tranDate.hashCode() + accountNo.hashCode();
    }
}
