#!/usr/bin/env bash

# ══════════════════════════════════════════════════════════
# Quick FX Conversion Backend Test (Linux/Mac/Git Bash)
# ══════════════════════════════════════════════════════════

echo ""
echo "=========================================="
echo "Testing FX Conversion Backend Endpoints"
echo "=========================================="
echo ""

# Check if backend is running
echo "[1/5] Checking if backend is running..."
if ! curl -s http://localhost:8082/actuator/health > /dev/null 2>&1; then
    echo "ERROR: Backend is NOT running on port 8082"
    echo "Please start backend with: mvn spring-boot:run"
    exit 1
fi
echo "SUCCESS: Backend is running"
echo ""

# Test Mid Rate
echo "[2/5] Testing Mid Rate endpoint..."
echo "GET /api/fx/rates/USD"
curl -X GET http://localhost:8082/api/fx/rates/USD
echo ""
echo ""

echo "[2b/5] Testing Mid Rate for EUR..."
echo "GET /api/fx/rates/EUR"
curl -X GET http://localhost:8082/api/fx/rates/EUR
echo ""
echo ""

# Test WAE Rate
echo "[3/5] Testing WAE Rate endpoint..."
echo "GET /api/fx/wae/USD"
curl -X GET http://localhost:8082/api/fx/wae/USD
echo ""
echo ""

# Test Customer Accounts
echo "[4/5] Testing Customer Accounts endpoint..."
echo "GET /api/fx/accounts/customer?search=001"
curl -X GET "http://localhost:8082/api/fx/accounts/customer?search=001"
echo ""
echo ""

echo "[4b/5] Testing Customer Accounts (empty search)..."
echo "GET /api/fx/accounts/customer?search="
curl -X GET "http://localhost:8082/api/fx/accounts/customer?search="
echo ""
echo ""

# Test NOSTRO Accounts
echo "[5/5] Testing NOSTRO Accounts endpoint..."
echo "GET /api/fx/accounts/nostro?currency=USD"
curl -X GET "http://localhost:8082/api/fx/accounts/nostro?currency=USD"
echo ""
echo ""

echo "=========================================="
echo "Testing Complete"
echo "=========================================="
echo ""
echo "Check the responses above for:"
echo "  - success: true"
echo "  - data field present"
echo "  - No error messages"
echo ""
echo "If you see errors, check backend console logs for details."
echo ""
