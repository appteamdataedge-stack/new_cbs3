package com.example.moneymarket.service;

import com.example.moneymarket.entity.InttAccrTran.AccrualStatus;
import com.example.moneymarket.entity.TranTable.TranStatus;
import com.example.moneymarket.repository.InttAccrTranRepository;
import com.example.moneymarket.repository.TranTableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to check for unverified records before EOD.
 * EOD is blocked only if there are unverified transactions. Interest capitalizations are ignored for this check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EODVerificationService {

    private final TranTableRepository tranTableRepository;
    private final InttAccrTranRepository inttAccrTranRepository;

    /**
     * Result of the pre-EOD verification check.
     */
    public static class VerificationStatusDTO {
        private long unverifiedTransactions;
        private long unverifiedCustomerAccounts;
        private long unverifiedOfficeAccounts;
        private long unverifiedInterestCapitalizations;
        private boolean canProceedWithEOD;

        public static VerificationStatusDTO of(
                long unverifiedTransactions,
                long unverifiedCustomerAccounts,
                long unverifiedOfficeAccounts,
                long unverifiedInterestCapitalizations) {
            VerificationStatusDTO dto = new VerificationStatusDTO();
            dto.unverifiedTransactions = unverifiedTransactions;
            dto.unverifiedCustomerAccounts = unverifiedCustomerAccounts;
            dto.unverifiedOfficeAccounts = unverifiedOfficeAccounts;
            dto.unverifiedInterestCapitalizations = unverifiedInterestCapitalizations;
            // Block EOD only on unverified transactions; ignore interest capitalizations
            dto.canProceedWithEOD = (unverifiedTransactions == 0
                    && unverifiedCustomerAccounts == 0
                    && unverifiedOfficeAccounts == 0);
            return dto;
        }

        public long getUnverifiedTransactions() {
            return unverifiedTransactions;
        }

        public void setUnverifiedTransactions(long unverifiedTransactions) {
            this.unverifiedTransactions = unverifiedTransactions;
        }

        public long getUnverifiedCustomerAccounts() {
            return unverifiedCustomerAccounts;
        }

        public void setUnverifiedCustomerAccounts(long unverifiedCustomerAccounts) {
            this.unverifiedCustomerAccounts = unverifiedCustomerAccounts;
        }

        public long getUnverifiedOfficeAccounts() {
            return unverifiedOfficeAccounts;
        }

        public void setUnverifiedOfficeAccounts(long unverifiedOfficeAccounts) {
            this.unverifiedOfficeAccounts = unverifiedOfficeAccounts;
        }

        public long getUnverifiedInterestCapitalizations() {
            return unverifiedInterestCapitalizations;
        }

        public void setUnverifiedInterestCapitalizations(long unverifiedInterestCapitalizations) {
            this.unverifiedInterestCapitalizations = unverifiedInterestCapitalizations;
        }

        public boolean isCanProceedWithEOD() {
            return canProceedWithEOD;
        }

        public void setCanProceedWithEOD(boolean canProceedWithEOD) {
            this.canProceedWithEOD = canProceedWithEOD;
        }
    }

    /**
     * Counts all unverified items across segments. EOD should not run if any count &gt; 0.
     */
    @Transactional(readOnly = true)
    public VerificationStatusDTO getVerificationStatus() {
        long unverifiedTransactions = tranTableRepository.countByTranStatusNot(TranStatus.Verified);
        long unverifiedInterest = inttAccrTranRepository.countByStatusNot(AccrualStatus.Verified);
        // Cust_Acct_Master / OF_Acct_Master do not have Verified status in current schema; use 0 until workflow exists
        long unverifiedCustAccounts = 0L;
        long unverifiedOfAccounts = 0L;

        VerificationStatusDTO dto = VerificationStatusDTO.of(
                unverifiedTransactions,
                unverifiedCustAccounts,
                unverifiedOfAccounts,
                unverifiedInterest);

        if (!dto.isCanProceedWithEOD()) {
            log.warn("EOD verification check: blocking due to unverified transactions: {}",
                    unverifiedTransactions);
        }
        return dto;
    }

    /**
     * Returns true if EOD can proceed (no unverified transactions; interest capitalizations are not checked).
     */
    @Transactional(readOnly = true)
    public boolean canProceedWithEOD() {
        return getVerificationStatus().isCanProceedWithEOD();
    }
}
