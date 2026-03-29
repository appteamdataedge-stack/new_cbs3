-- ══════════════════════════════════════════════════════════
-- DIAGNOSE CUSTOMER ACCOUNT ISSUE
-- ══════════════════════════════════════════════════════════

-- Step 1: Check if ANY customer accounts exist
SELECT '========== TOTAL CUSTOMER ACCOUNTS ==========' AS '';
SELECT COUNT(*) as total_count FROM cust_acct_master;

-- Step 2: Check account status distribution
SELECT '========== ACCOUNT STATUS DISTRIBUTION ==========' AS '';
SELECT 
    account_status,
    COUNT(*) as count
FROM cust_acct_master
GROUP BY account_status;

-- Step 3: Check currency distribution
SELECT '========== CURRENCY DISTRIBUTION ==========' AS '';
SELECT 
    account_ccy,
    COUNT(*) as count
FROM cust_acct_master
GROUP BY account_ccy;

-- Step 4: Check product types (CRITICAL - this is the filter)
SELECT '========== PRODUCT TYPES (via sub_product) ==========' AS '';
SELECT 
    sp.sub_product_code,
    sp.sub_product_name,
    COUNT(*) as account_count
FROM cust_acct_master ca
LEFT JOIN sub_prod_master sp ON ca.sub_product_id = sp.sub_product_id
GROUP BY sp.sub_product_code, sp.sub_product_name
ORDER BY account_count DESC;

-- Step 5: Show sample accounts with all relevant fields
SELECT '========== SAMPLE CUSTOMER ACCOUNTS ==========' AS '';
SELECT 
    ca.account_no,
    ca.acct_name,
    ca.account_ccy,
    ca.account_status,
    sp.sub_product_code,
    sp.sub_product_name,
    ca.gl_num
FROM cust_acct_master ca
LEFT JOIN sub_prod_master sp ON ca.sub_product_id = sp.sub_product_id
LIMIT 10;

-- Step 6: Check for accounts matching FX Conversion criteria
SELECT '========== ACCOUNTS MATCHING FX CRITERIA ==========' AS '';
SELECT 
    ca.account_no,
    ca.acct_name,
    ca.account_ccy,
    ca.account_status,
    sp.sub_product_code,
    sp.sub_product_name
FROM cust_acct_master ca
INNER JOIN sub_prod_master sp ON ca.sub_product_id = sp.sub_product_id
WHERE ca.account_status = 'Active'
  AND ca.account_ccy = 'BDT'
  AND (sp.sub_product_code LIKE 'CA%' OR sp.sub_product_code LIKE 'SB%')
LIMIT 20;

-- Step 7: Count accounts matching FX criteria
SELECT '========== COUNT OF MATCHING ACCOUNTS ==========' AS '';
SELECT COUNT(*) as matching_accounts
FROM cust_acct_master ca
INNER JOIN sub_prod_master sp ON ca.sub_product_id = sp.sub_product_id
WHERE ca.account_status = 'Active'
  AND ca.account_ccy = 'BDT'
  AND (sp.sub_product_code LIKE 'CA%' OR sp.sub_product_code LIKE 'SB%');

-- ══════════════════════════════════════════════════════════
-- INTERPRETATION GUIDE
-- ══════════════════════════════════════════════════════════

/*
SCENARIO 1: Total count is 0
→ Database is empty, need to create customer accounts

SCENARIO 2: Total count > 0, but matching_accounts = 0
→ Accounts exist but don't match criteria:
  - Check if account_status is 'Active' (not 'ACTIVE' or other)
  - Check if account_ccy is 'BDT'
  - Check if sub_product_code starts with 'CA' or 'SB'
  
SCENARIO 3: Matching accounts > 0, but backend returns []
→ Backend filter logic issue:
  - Check FxConversionService.searchCustomerAccounts() filter
  - Check if getSubProduct() returns null
  - Check if subProductCode pattern matching is correct

SCENARIO 4: Backend returns data, but frontend dropdown empty
→ Frontend parsing issue:
  - Check if response is unwrapped correctly
  - Check if field names match (accountTitle vs accountName)
  - Check browser console for errors
*/

-- ══════════════════════════════════════════════════════════
-- QUICK FIX: Insert test customer accounts if needed
-- ══════════════════════════════════════════════════════════

-- First check what sub_product_ids exist for CA and SB
SELECT '========== AVAILABLE SUB PRODUCTS ==========' AS '';
SELECT sub_product_id, sub_product_code, sub_product_name
FROM sub_prod_master
WHERE sub_product_code LIKE 'CA%' OR sub_product_code LIKE 'SB%';

/*
NOTE: Before inserting customer accounts, you need to know:
1. Valid sub_product_id for CA (Current Account)
2. Valid sub_product_id for SB (Savings Bank)
3. Valid cust_id (existing customer)

Then you can INSERT test accounts:

INSERT INTO cust_acct_master (
    account_no, 
    acct_name, 
    sub_product_id, 
    gl_num, 
    account_ccy, 
    cust_id,
    cust_name,
    date_opening,
    branch_code,
    account_status,
    loan_limit
)
VALUES 
  ('1010101010101', 'Test Customer A', [CA_SUB_PRODUCT_ID], '110101001', 'BDT', [CUST_ID], 'Customer A', CURDATE(), '001', 'Active', 0),
  ('1010101010102', 'Test Customer B', [SB_SUB_PRODUCT_ID], '120101001', 'BDT', [CUST_ID], 'Customer B', CURDATE(), '001', 'Active', 0);

Replace [CA_SUB_PRODUCT_ID], [SB_SUB_PRODUCT_ID], [CUST_ID] with actual values from your database.
*/
