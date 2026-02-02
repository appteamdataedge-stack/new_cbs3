package com.example.moneymarket.service;

import com.example.moneymarket.entity.CustAcctMaster;
import com.example.moneymarket.entity.OFAcctMaster;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.CustAcctMasterRepository;
import com.example.moneymarket.repository.OFAcctMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Unified service to handle both customer and office accounts
 * Provides a common interface for account operations regardless of account type
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedAccountService {

    private final CustAcctMasterRepository custAcctMasterRepository;
    private final OFAcctMasterRepository ofAcctMasterRepository;
    private final GLValidationService glValidationService;
    private final GLHierarchyService glHierarchyService;

    /**
     * Unified account information container
     */
    public static class AccountInfo {
        private final String accountNo;
        private final String glNum;
        private final String accountName;
        private final boolean isCustomerAccount;
        private final boolean isAssetAccount;
        private final boolean isLiabilityAccount;
        private final boolean isOverdraftAccount;

        public AccountInfo(String accountNo, String glNum, String accountName, 
                          boolean isCustomerAccount, boolean isAssetAccount, boolean isLiabilityAccount,
                          boolean isOverdraftAccount) {
            this.accountNo = accountNo;
            this.glNum = glNum;
            this.accountName = accountName;
            this.isCustomerAccount = isCustomerAccount;
            this.isAssetAccount = isAssetAccount;
            this.isLiabilityAccount = isLiabilityAccount;
            this.isOverdraftAccount = isOverdraftAccount;
        }

        public String getAccountNo() { return accountNo; }
        public String getGlNum() { return glNum; }
        public String getAccountName() { return accountName; }
        public boolean isCustomerAccount() { return isCustomerAccount; }
        public boolean isAssetAccount() { return isAssetAccount; }
        public boolean isLiabilityAccount() { return isLiabilityAccount; }
        public boolean isOverdraftAccount() { return isOverdraftAccount; }
    }

    /**
     * Get unified account information for any account number
     * 
     * @param accountNo The account number
     * @return AccountInfo containing account details and type information
     * @throws BusinessException if account is not found
     */
    public AccountInfo getAccountInfo(String accountNo) {
        // First try to find as customer account
        CustAcctMaster custAccount = custAcctMasterRepository.findById(accountNo).orElse(null);
        if (custAccount != null) {
            boolean isCustomerAccount = glValidationService.isCustomerAccountGL(custAccount.getGlNum());
            boolean isAssetAccount = glValidationService.isAssetGL(custAccount.getGlNum());
            boolean isLiabilityAccount = glValidationService.isLiabilityGL(custAccount.getGlNum());
            boolean isOverdraftAccount = glHierarchyService.isOverdraftAccount(custAccount.getGlNum());
            
            return new AccountInfo(
                custAccount.getAccountNo(),
                custAccount.getGlNum(),
                custAccount.getAcctName(),
                isCustomerAccount,
                isAssetAccount,
                isLiabilityAccount,
                isOverdraftAccount
            );
        }

        // If not found as customer account, try office account
        OFAcctMaster ofAccount = ofAcctMasterRepository.findById(accountNo).orElse(null);
        if (ofAccount != null) {
            boolean isCustomerAccount = glValidationService.isCustomerAccountGL(ofAccount.getGlNum());
            boolean isAssetAccount = glValidationService.isAssetGL(ofAccount.getGlNum());
            boolean isLiabilityAccount = glValidationService.isLiabilityGL(ofAccount.getGlNum());
            boolean isOverdraftAccount = glHierarchyService.isOverdraftAccount(ofAccount.getGlNum());
            
            return new AccountInfo(
                ofAccount.getAccountNo(),
                ofAccount.getGlNum(),
                ofAccount.getAcctName(),
                isCustomerAccount,
                isAssetAccount,
                isLiabilityAccount,
                isOverdraftAccount
            );
        }

        throw new BusinessException("Account " + accountNo + " does not exist");
    }

    /**
     * Check if an account is a customer account
     * 
     * @param accountNo The account number
     * @return true if customer account, false if office account
     * @throws BusinessException if account is not found
     */
    public boolean isCustomerAccount(String accountNo) {
        return getAccountInfo(accountNo).isCustomerAccount();
    }

    /**
     * Check if an account is an office account
     * 
     * @param accountNo The account number
     * @return true if office account, false if customer account
     * @throws BusinessException if account is not found
     */
    public boolean isOfficeAccount(String accountNo) {
        return !isCustomerAccount(accountNo);
    }

    /**
     * Get the GL number for an account
     * 
     * @param accountNo The account number
     * @return The GL number
     * @throws BusinessException if account is not found
     */
    public String getGlNum(String accountNo) {
        return getAccountInfo(accountNo).getGlNum();
    }

    /**
     * Check if an account is an overdraft account
     * 
     * @param accountNo The account number
     * @return true if overdraft account, false otherwise
     * @throws BusinessException if account is not found
     */
    public boolean isOverdraftAccount(String accountNo) {
        return getAccountInfo(accountNo).isOverdraftAccount();
    }

    /**
     * ✅ FIX ISSUE 4: Get account currency from Product → SubProduct → Account hierarchy
     * Currency flows: Product.currency → SubProduct (inherits) → Account (inherits)
     * 
     * @param accountNo The account number
     * @return The account currency (USD, BDT, etc.)
     * @throws BusinessException if account is not found
     */
    public String getAccountCurrency(String accountNo) {
        // First try to find as customer account
        CustAcctMaster custAccount = custAcctMasterRepository.findById(accountNo).orElse(null);
        if (custAccount != null) {
            // Get currency from account (which inherits from Product via SubProduct)
            String currency = custAccount.getAccountCcy();
            if (currency != null && !currency.isEmpty()) {
                log.debug("Account {} currency: {} (from cust_acct_master)", accountNo, currency);
                return currency;
            }
            
            // Fallback: Get from SubProduct → Product
            if (custAccount.getSubProduct() != null && custAccount.getSubProduct().getProduct() != null) {
                currency = custAccount.getSubProduct().getProduct().getCurrency();
                log.debug("Account {} currency: {} (from product hierarchy)", accountNo, currency);
                return currency;
            }
            
            log.warn("Account {} has no currency set, defaulting to BDT", accountNo);
            return "BDT";
        }

        // If not found as customer account, try office account
        OFAcctMaster ofAccount = ofAcctMasterRepository.findById(accountNo).orElse(null);
        if (ofAccount != null) {
            // Get currency from account
            String currency = ofAccount.getAccountCcy();
            if (currency != null && !currency.isEmpty()) {
                log.debug("Office account {} currency: {} (from of_acct_master)", accountNo, currency);
                return currency;
            }
            
            // Fallback: Get from SubProduct → Product
            if (ofAccount.getSubProduct() != null && ofAccount.getSubProduct().getProduct() != null) {
                currency = ofAccount.getSubProduct().getProduct().getCurrency();
                log.debug("Office account {} currency: {} (from product hierarchy)", accountNo, currency);
                return currency;
            }
            
            log.warn("Office account {} has no currency set, defaulting to BDT", accountNo);
            return "BDT";
        }

        throw new BusinessException("Account " + accountNo + " does not exist");
    }

    /**
     * Check if an account exists (either customer or office)
     * 
     * @param accountNo The account number
     * @return true if account exists, false otherwise
     */
    public boolean accountExists(String accountNo) {
        return custAcctMasterRepository.existsById(accountNo) || 
               ofAcctMasterRepository.existsById(accountNo);
    }
}
