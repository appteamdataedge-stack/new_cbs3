# Account Creation Currency Fix - Implementation Summary

## Problem Statement

When creating a new customer account at `Home > Account > New`, the system was failing with error:
```
"Failed to create account, server error occurred. Please try again later."
```

**Root Cause:** The system was using the default "BDT" currency for all accounts instead of getting the currency from the Product (prod_master table) based on the selected product.

## Solution Implemented

### Files Modified

1. **CustomerAccountService.java** - Account creation service
   - Location: `moneymarket/src/main/java/com/example/moneymarket/service/CustomerAccountService.java`

### Changes Made

#### 1. Fixed Currency Assignment in `mapToEntity()` Method

**BEFORE (Lines 241-267):**
```java
private CustAcctMaster mapToEntity(CustomerAccountRequestDTO dto, CustMaster customer, 
                                  SubProdMaster subProduct, String accountNo, String glNum) {
    // Set loan limit only for Asset accounts (GL starting with "2")
    BigDecimal loanLimit = BigDecimal.ZERO;
    if (glNum != null && glNum.startsWith("2") && dto.getLoanLimit() != null) {
        loanLimit = dto.getLoanLimit();
        log.info("Setting loan limit {} for Asset account {} (GL: {})", loanLimit, accountNo, glNum);
    } else if (dto.getLoanLimit() != null && dto.getLoanLimit().compareTo(BigDecimal.ZERO) > 0) {
        log.warn("Loan limit {} provided for non-Asset account {} (GL: {}). Ignoring and setting to 0.", 
                dto.getLoanLimit(), accountNo, glNum);
    }
    
    return CustAcctMaster.builder()
            .accountNo(accountNo)
            .subProduct(subProduct)
            .glNum(glNum)
            // ❌ NO CURRENCY SET - defaults to "BDT" in entity
            .customer(customer)
            .custName(dto.getCustName())
            .acctName(dto.getAcctName())
            .dateOpening(dto.getDateOpening())
            .tenor(dto.getTenor())
            .dateMaturity(dto.getDateMaturity())
            .dateClosure(dto.getDateClosure())
            .branchCode(dto.getBranchCode())
            .accountStatus(dto.getAccountStatus())
            .loanLimit(loanLimit)
            .build();
}
```

**AFTER (Lines 241-278):**
```java
private CustAcctMaster mapToEntity(CustomerAccountRequestDTO dto, CustMaster customer, 
                                  SubProdMaster subProduct, String accountNo, String glNum) {
    // Set loan limit only for Asset accounts (GL starting with "2")
    BigDecimal loanLimit = BigDecimal.ZERO;
    if (glNum != null && glNum.startsWith("2") && dto.getLoanLimit() != null) {
        loanLimit = dto.getLoanLimit();
        log.info("Setting loan limit {} for Asset account {} (GL: {})", loanLimit, accountNo, glNum);
    } else if (dto.getLoanLimit() != null && dto.getLoanLimit().compareTo(BigDecimal.ZERO) > 0) {
        log.warn("Loan limit {} provided for non-Asset account {} (GL: {}). Ignoring and setting to 0.", 
                dto.getLoanLimit(), accountNo, glNum);
    }
    
    // ✅ Get currency from Product (via SubProduct relationship)
    String productCurrency = subProduct.getProduct().getCurrency();
    log.info("Creating account {} with currency: {} from product: {} ({})", 
            accountNo, productCurrency, 
            subProduct.getProduct().getProductName(), 
            subProduct.getProduct().getProductCode());
    
    return CustAcctMaster.builder()
            .accountNo(accountNo)
            .subProduct(subProduct)
            .glNum(glNum)
            .accountCcy(productCurrency) // ✅ Set currency from Product, not default BDT
            .customer(customer)
            .custName(dto.getCustName())
            .acctName(dto.getAcctName())
            .dateOpening(dto.getDateOpening())
            .tenor(dto.getTenor())
            .dateMaturity(dto.getDateMaturity())
            .dateClosure(dto.getDateClosure())
            .branchCode(dto.getBranchCode())
            .accountStatus(dto.getAccountStatus())
            .loanLimit(loanLimit)
            .build();
}
```

**Key Changes:**
1. Added logic to fetch currency from Product: `subProduct.getProduct().getCurrency()`
2. Set `accountCcy` field explicitly with product's currency
3. Added detailed logging showing currency source and product details

#### 2. Enhanced Error Logging in `createAccount()` Method

**BEFORE (Lines 41-93):**
```java
@Transactional
public CustomerAccountResponseDTO createAccount(CustomerAccountRequestDTO accountRequestDTO) {
    // Validate customer exists
    CustMaster customer = custMasterRepository.findById(accountRequestDTO.getCustId())
            .orElseThrow(() -> new ResourceNotFoundException("Customer", "ID", accountRequestDTO.getCustId()));

    // Validate sub-product exists and is active (with Product relationship loaded)
    SubProdMaster subProduct = subProdMasterRepository.findByIdWithProduct(accountRequestDTO.getSubProductId())
            .orElseThrow(() -> new ResourceNotFoundException("Sub-Product", "ID", accountRequestDTO.getSubProductId()));

    if (subProduct.getSubProductStatus() != SubProdMaster.SubProductStatus.Active) {
        throw new BusinessException("Sub-Product is not active");
    }

    // Apply Tenor and Date of Maturity logic
    applyTenorAndMaturityLogic(accountRequestDTO, subProduct);

    // Generate customer account number using the new format
    String accountNo = accountNumberService.generateCustomerAccountNumber(customer, subProduct);
    String glNum = subProduct.getCumGLNum();

    // Map DTO to entity
    CustAcctMaster account = mapToEntity(accountRequestDTO, customer, subProduct, accountNo, glNum);

    // Save the account
    CustAcctMaster savedAccount = custAcctMasterRepository.save(account);
    
    // Initialize account balance
    AcctBal accountBalance = AcctBal.builder()
            .tranDate(systemDateService.getSystemDate())
            .accountNo(savedAccount.getAccountNo())
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .lastUpdated(systemDateService.getSystemDateTime())
            .build();
    
    acctBalRepository.save(accountBalance);

    log.info("Customer Account created with account number: {}", savedAccount.getAccountNo());

    // Return the response with success message
    CustomerAccountResponseDTO response = mapToResponse(savedAccount, accountBalance);
    response.setMessage("Account Number " + savedAccount.getAccountNo() + " created");
    
    return response;
}
```

**AFTER (Lines 41-121):**
```java
@Transactional
public CustomerAccountResponseDTO createAccount(CustomerAccountRequestDTO accountRequestDTO) {
    try {
        log.info("Starting account creation - Customer ID: {}, Sub-Product ID: {}", 
                accountRequestDTO.getCustId(), accountRequestDTO.getSubProductId());
        
        // Validate customer exists
        CustMaster customer = custMasterRepository.findById(accountRequestDTO.getCustId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "ID", accountRequestDTO.getCustId()));
        log.debug("Customer found: {} - {}", customer.getCustId(), customer.getCustName());

        // Validate sub-product exists and is active (with Product relationship loaded)
        SubProdMaster subProduct = subProdMasterRepository.findByIdWithProduct(accountRequestDTO.getSubProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Sub-Product", "ID", accountRequestDTO.getSubProductId()));
        log.debug("Sub-Product found: {} - {}, Product: {} - {}, Currency: {}", 
                subProduct.getSubProductId(), subProduct.getSubProductName(),
                subProduct.getProduct().getProductId(), subProduct.getProduct().getProductName(),
                subProduct.getProduct().getCurrency());

        if (subProduct.getSubProductStatus() != SubProdMaster.SubProductStatus.Active) {
            throw new BusinessException("Sub-Product is not active");
        }

        // Apply Tenor and Date of Maturity logic
        applyTenorAndMaturityLogic(accountRequestDTO, subProduct);

        // Generate customer account number using the new format
        String accountNo = accountNumberService.generateCustomerAccountNumber(customer, subProduct);
        String glNum = subProduct.getCumGLNum();
        log.debug("Generated account number: {}, GL: {}", accountNo, glNum);

        // Map DTO to entity
        CustAcctMaster account = mapToEntity(accountRequestDTO, customer, subProduct, accountNo, glNum);
        log.debug("Account entity created with currency: {}", account.getAccountCcy());

        // Save the account
        CustAcctMaster savedAccount = custAcctMasterRepository.save(account);
        log.info("Account saved to database: {}", savedAccount.getAccountNo());
        
        // Initialize account balance
        AcctBal accountBalance = AcctBal.builder()
                .tranDate(systemDateService.getSystemDate())
                .accountNo(savedAccount.getAccountNo())
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .lastUpdated(systemDateService.getSystemDateTime())
                .build();
        
        acctBalRepository.save(accountBalance);
        log.info("Account balance initialized for account: {}", savedAccount.getAccountNo());

        log.info("✅ Customer Account created successfully - Account: {}, Currency: {}, Product: {}", 
                savedAccount.getAccountNo(), savedAccount.getAccountCcy(), 
                subProduct.getProduct().getProductName());

        // Return the response with success message
        CustomerAccountResponseDTO response = mapToResponse(savedAccount, accountBalance);
        response.setMessage("Account Number " + savedAccount.getAccountNo() + " created");
        
        return response;
        
    } catch (ResourceNotFoundException e) {
        log.error("❌ Resource not found during account creation - Customer: {}, Sub-Product: {}, Error: {}", 
                accountRequestDTO.getCustId(), accountRequestDTO.getSubProductId(), e.getMessage());
        throw e;
    } catch (BusinessException e) {
        log.error("❌ Business rule violation during account creation - Customer: {}, Sub-Product: {}, Error: {}", 
                accountRequestDTO.getCustId(), accountRequestDTO.getSubProductId(), e.getMessage());
        throw e;
    } catch (Exception e) {
        log.error("❌ Unexpected error during account creation - Customer: {}, Sub-Product: {}", 
                accountRequestDTO.getCustId(), accountRequestDTO.getSubProductId(), e);
        log.error("Error details - Type: {}, Message: {}", e.getClass().getName(), e.getMessage());
        throw new BusinessException("Failed to create account: " + e.getMessage(), e);
    }
}
```

**Key Enhancements:**
1. Added try-catch block for comprehensive error handling
2. Added step-by-step logging at each stage of account creation
3. Logs show customer details, product details, and **currency** at each step
4. Separate error logging for different exception types
5. Success logging includes currency confirmation

## How It Works Now

### Data Flow

```
1. User selects Product/Sub-Product in UI
   ↓
2. Frontend sends account creation request with Sub-Product ID
   ↓
3. Backend: CustomerAccountService.createAccount()
   ↓
4. Fetch SubProduct with Product relationship loaded
   (using SubProdMasterRepository.findByIdWithProduct())
   ↓
5. Extract currency from Product:
   subProduct.getProduct().getCurrency()
   ↓
6. Set account currency = product currency
   account.setAccountCcy(productCurrency)
   ↓
7. Save account with correct currency
```

### Database Schema

```
Prod_Master
├── Product_Id (PK)
├── Product_Code
├── Product_Name
├── Currency ← Source of Truth
└── ...

Sub_Prod_Master
├── Sub_Product_Id (PK)
├── Product_Id (FK → Prod_Master)
├── Sub_Product_Code
└── ...

Cust_Acct_Master
├── Account_No (PK)
├── Sub_Product_Id (FK → Sub_Prod_Master)
├── Account_Ccy ← Now set from Prod_Master.Currency
└── ...
```

## Test Scenarios

### Test Case 1: Savings Account (BDT)

**Product:**
- Product_Id: 25
- Product_Code: SB-101
- Product_Name: Savings Money
- Currency: BDT

**Expected Result:**
- Account created with `Account_Ccy = "BDT"` ✅

**Log Output:**
```
Creating account 100000001001 with currency: BDT from product: Savings Money (SB-101)
✅ Customer Account created successfully - Account: 100000001001, Currency: BDT, Product: Savings Money
```

### Test Case 2: Term Deposit FCY (USD)

**Product:**
- Product_Id: 30
- Product_Code: TDPFU
- Product_Name: Term Deposit PIP FCY USD
- Currency: USD

**Expected Result:**
- Account created with `Account_Ccy = "USD"` ✅

**Log Output:**
```
Creating account 110201003001 with currency: USD from product: Term Deposit PIP FCY USD (TDPFU)
✅ Customer Account created successfully - Account: 110201003001, Currency: USD, Product: Term Deposit PIP FCY USD
```

### Test Case 3: Any Sub-Product under USD Product

**Product:**
- Product with Currency: USD
- Any Sub-Product under this Product

**Expected Result:**
- Account created with `Account_Ccy = "USD"` ✅

## Verification Steps

### 1. Check Backend Logs

When creating an account, you should see logs like:

```log
2026-02-01 11:00:00 INFO  Starting account creation - Customer ID: 1, Sub-Product ID: 5
2026-02-01 11:00:00 DEBUG Customer found: 1 - John Doe
2026-02-01 11:00:00 DEBUG Sub-Product found: 5 - USD Savings, Product: 30 - Term Deposit PIP FCY USD, Currency: USD
2026-02-01 11:00:00 DEBUG Generated account number: 110201003001, GL: 110201
2026-02-01 11:00:00 INFO  Creating account 110201003001 with currency: USD from product: Term Deposit PIP FCY USD (TDPFU)
2026-02-01 11:00:00 DEBUG Account entity created with currency: USD
2026-02-01 11:00:00 INFO  Account saved to database: 110201003001
2026-02-01 11:00:00 INFO  Account balance initialized for account: 110201003001
2026-02-01 11:00:00 INFO  ✅ Customer Account created successfully - Account: 110201003001, Currency: USD, Product: Term Deposit PIP FCY USD
```

### 2. Check Database

```sql
-- Verify account currency matches product currency
SELECT 
    ca.Account_No,
    ca.Account_Ccy,
    sp.Sub_Product_Name,
    p.Product_Name,
    p.Currency as Product_Currency
FROM Cust_Acct_Master ca
JOIN Sub_Prod_Master sp ON ca.Sub_Product_Id = sp.Sub_Product_Id
JOIN Prod_Master p ON sp.Product_Id = p.Product_Id
WHERE ca.Account_No = 'YOUR_ACCOUNT_NUMBER';

-- Expected: Account_Ccy = Product_Currency ✅
```

### 3. Test in UI

1. Navigate to `Home > Account > New`
2. Select Customer
3. Select Product: "Term Deposit PIP FCY USD"
4. Fill in other required fields
5. Click "Create Account"
6. **Expected:** Account created successfully with USD currency
7. Check account details - currency should show USD

## Error Debugging

If account creation still fails, check the logs for:

### Error Pattern 1: Resource Not Found
```log
❌ Resource not found during account creation - Customer: 1, Sub-Product: 999, Error: Sub-Product with ID 999 not found
```
**Solution:** Verify the Sub-Product ID exists in database

### Error Pattern 2: Business Rule Violation
```log
❌ Business rule violation during account creation - Customer: 1, Sub-Product: 5, Error: Sub-Product is not active
```
**Solution:** Verify the Sub-Product status is 'Active'

### Error Pattern 3: Unexpected Error
```log
❌ Unexpected error during account creation - Customer: 1, Sub-Product: 5
Error details - Type: java.sql.SQLException, Message: Column 'Required_Field' cannot be null
```
**Solution:** Check for missing required fields in the request DTO

## Benefits of This Fix

1. ✅ **Correct Currency Handling**
   - Accounts now use the product's currency instead of hardcoded BDT
   - Supports multi-currency accounts (BDT, USD, EUR, etc.)

2. ✅ **Better Debugging**
   - Comprehensive logging at every step
   - Clear error messages identifying the exact issue
   - Currency shown in logs for verification

3. ✅ **CBS Compliance**
   - Currency comes from product master data (single source of truth)
   - Consistent with banking standards

4. ✅ **No Breaking Changes**
   - Existing BDT accounts continue to work
   - Only changes how new accounts are created

## Files to Monitor

When testing, monitor these files for detailed logs:

1. **Backend Logs:** Check the terminal running the Spring Boot application
2. **Database:** Query `Cust_Acct_Master` table to verify `Account_Ccy` field
3. **Browser Console:** Check for any frontend errors

## Next Steps

1. ✅ Fix implemented and code updated
2. ⏳ Test account creation with BDT product
3. ⏳ Test account creation with USD product
4. ⏳ Test account creation with other currencies (if available)
5. ⏳ Verify logs show correct currency at each step
6. ⏳ Verify database shows correct currency in `Account_Ccy` field

---

**Implementation Date:** February 1, 2026  
**Modified By:** Cursor AI Assistant  
**Status:** ✅ IMPLEMENTED - Ready for Testing
