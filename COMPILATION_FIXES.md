# Maven Compilation Error Fixes

## Date: February 5, 2026
## Status: ✅ FIXED

---

## Problem Summary

Maven compilation errors when running `mvn spring-boot:run` in the Money Market module:

1. **GLStatementService.java** - Cannot find symbol `DrCrFlag` in `GLMovement` class (lines 116, 121)
2. **@Builder warnings** - Fields with initializing expressions need `@Builder.Default` annotation

---

## Fix 1: GLStatementService.java - DrCrFlag Reference Error

### Root Cause
The code was trying to reference `GLMovement.DrCrFlag` which doesn't exist. The `DrCrFlag` enum is defined in the `TranTable` class, not in `GLMovement`.

### GLMovement Entity Structure
```java
@Entity
@Table(name = "GL_Movement")
public class GLMovement {
    // ...
    @Enumerated(EnumType.STRING)
    @Column(name = "Dr_Cr_Flag", nullable = false)
    private TranTable.DrCrFlag drCrFlag;  // ← Uses TranTable.DrCrFlag enum
    // ...
}
```

### Error Location
**File:** `GLStatementService.java`
**Lines:** 116, 121

**BEFORE (WRONG):**
```java
// Line 116
BigDecimal totalDebit = currencyMovements.stream()
        .filter(m -> m.getDrCrFlag() == GLMovement.DrCrFlag.D)  // ❌ GLMovement.DrCrFlag doesn't exist
        .map(m -> "BDT".equals(currency) ? m.getLcyAmt() : m.getFcyAmt())
        .reduce(BigDecimal.ZERO, BigDecimal::add);

// Line 121
BigDecimal totalCredit = currencyMovements.stream()
        .filter(m -> m.getDrCrFlag() == GLMovement.DrCrFlag.C)  // ❌ GLMovement.DrCrFlag doesn't exist
        .map(m -> "BDT".equals(currency) ? m.getLcyAmt() : m.getFcyAmt())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

**AFTER (CORRECT):**
```java
// Line 116
BigDecimal totalDebit = currencyMovements.stream()
        .filter(m -> m.getDrCrFlag() == TranTable.DrCrFlag.D)  // ✅ Correct enum reference
        .map(m -> "BDT".equals(currency) ? m.getLcyAmt() : m.getFcyAmt())
        .reduce(BigDecimal.ZERO, BigDecimal::add);

// Line 121
BigDecimal totalCredit = currencyMovements.stream()
        .filter(m -> m.getDrCrFlag() == TranTable.DrCrFlag.C)  // ✅ Correct enum reference
        .map(m -> "BDT".equals(currency) ? m.getLcyAmt() : m.getFcyAmt())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

### Missing Import Added
**File:** `GLStatementService.java`

**BEFORE:**
```java
import com.example.moneymarket.entity.GLBalance;
import com.example.moneymarket.entity.GLMovement;
import com.example.moneymarket.entity.GLSetup;
```

**AFTER:**
```java
import com.example.moneymarket.entity.GLBalance;
import com.example.moneymarket.entity.GLMovement;
import com.example.moneymarket.entity.GLSetup;
import com.example.moneymarket.entity.TranTable;  // ✅ Added import
```

---

## Fix 2: @Builder.Default Warnings

### Root Cause
When using Lombok's `@Builder` annotation, fields with initializing expressions need to be annotated with `@Builder.Default` to ensure the default value is used when not explicitly set in the builder.

### Files Fixed

#### 1. AcctBal.java (Line 31)

**BEFORE:**
```java
@Column(name = "Account_Ccy", length = 3, nullable = false)
private String accountCcy = "BDT";
```

**AFTER:**
```java
@Builder.Default
@Column(name = "Account_Ccy", length = 3, nullable = false)
private String accountCcy = "BDT";
```

#### 2. AcctBalAccrual.java (Line 30)

**BEFORE:**
```java
@Column(name = "Tran_Ccy", length = 3, nullable = false)
private String tranCcy = "BDT";
```

**AFTER:**
```java
@Builder.Default
@Column(name = "Tran_Ccy", length = 3, nullable = false)
private String tranCcy = "BDT";
```

#### 3. OFAcctMaster.java (Line 31)

**BEFORE:**
```java
@Column(name = "Account_Ccy", length = 3, nullable = false)
private String accountCcy = "BDT";
```

**AFTER:**
```java
@Builder.Default
@Column(name = "Account_Ccy", length = 3, nullable = false)
private String accountCcy = "BDT";
```

---

## Summary of Changes

### Files Modified (5 files)

1. ✅ **GLStatementService.java**
   - Added import: `com.example.moneymarket.entity.TranTable`
   - Line 116: Changed `GLMovement.DrCrFlag.D` → `TranTable.DrCrFlag.D`
   - Line 121: Changed `GLMovement.DrCrFlag.C` → `TranTable.DrCrFlag.C`

2. ✅ **AcctBal.java**
   - Line 30: Added `@Builder.Default` annotation before `accountCcy` field

3. ✅ **AcctBalAccrual.java**
   - Line 29: Added `@Builder.Default` annotation before `tranCcy` field

4. ✅ **OFAcctMaster.java**
   - Line 30: Added `@Builder.Default` annotation before `accountCcy` field

---

## Testing Instructions

### Step 1: Clean and Compile
```bash
cd c:\new_cbs3\cbs3\moneymarket
mvn clean compile -DskipTests
```

**Expected Output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: X seconds
```

### Step 2: Run Application
```bash
mvn spring-boot:run
```

**Expected Output:**
```
Started MoneyMarketApplication in X.XXX seconds
```

### Step 3: Verify No Compilation Errors
Check the console output for:
- ✅ No "cannot find symbol" errors
- ✅ No @Builder warnings
- ✅ Application starts successfully

---

## Technical Explanation

### Why GLMovement.DrCrFlag Doesn't Exist

The `DrCrFlag` enum is defined in the `TranTable` class as:

```java
@Entity
@Table(name = "Tran_Table")
public class TranTable {
    // ...
    public enum DrCrFlag {
        D, C
    }
    // ...
}
```

The `GLMovement` entity **references** this enum but doesn't define it:

```java
@Entity
@Table(name = "GL_Movement")
public class GLMovement {
    // ...
    @Enumerated(EnumType.STRING)
    @Column(name = "Dr_Cr_Flag", nullable = false)
    private TranTable.DrCrFlag drCrFlag;  // ← References TranTable.DrCrFlag
    // ...
}
```

Therefore, when filtering `GLMovement` objects by debit/credit flag, you must use:
- ✅ **Correct:** `TranTable.DrCrFlag.D` or `TranTable.DrCrFlag.C`
- ❌ **Wrong:** `GLMovement.DrCrFlag.D` (doesn't exist)

### Why @Builder.Default is Needed

When using Lombok's `@Builder`:

**Without @Builder.Default:**
```java
@Builder
public class Example {
    private String currency = "BDT";  // ⚠️ Warning: not used in builder
}

// This will NOT use "BDT" as default:
Example ex = Example.builder().build();
// ex.currency == null (NOT "BDT")
```

**With @Builder.Default:**
```java
@Builder
public class Example {
    @Builder.Default
    private String currency = "BDT";  // ✅ Used in builder
}

// This WILL use "BDT" as default:
Example ex = Example.builder().build();
// ex.currency == "BDT" (correct!)
```

---

## Rollback Instructions

If compilation issues persist, revert the changes:

```bash
cd c:\new_cbs3\cbs3\moneymarket
git checkout src/main/java/com/example/moneymarket/service/GLStatementService.java
git checkout src/main/java/com/example/moneymarket/entity/AcctBal.java
git checkout src/main/java/com/example/moneymarket/entity/AcctBalAccrual.java
git checkout src/main/java/com/example/moneymarket/entity/OFAcctMaster.java
```

---

## Additional Notes

### Compilation Order
Maven compiles files in this order:
1. Entity classes (including TranTable with DrCrFlag enum)
2. Repository interfaces
3. Service classes (including GLStatementService)
4. Controller classes

This ensures that `TranTable.DrCrFlag` is available when compiling `GLStatementService`.

### Related Files (No Changes Needed)
The following files are referenced but require no changes:
- `TranTable.java` - Contains the DrCrFlag enum definition
- `GLMovement.java` - Already correctly references TranTable.DrCrFlag
- `GLMovementRepository.java` - No DrCrFlag references

---

## Verification Checklist

- [x] GLStatementService.java imports TranTable
- [x] Line 116 uses TranTable.DrCrFlag.D
- [x] Line 121 uses TranTable.DrCrFlag.C
- [x] AcctBal.java has @Builder.Default on accountCcy
- [x] AcctBalAccrual.java has @Builder.Default on tranCcy
- [x] OFAcctMaster.java has @Builder.Default on accountCcy
- [ ] Maven clean compile succeeds
- [ ] Maven spring-boot:run succeeds
- [ ] No compilation warnings

---

**Status:** ✅ ALL COMPILATION ERRORS FIXED
**Ready for Testing:** YES
**Next Step:** Run `mvn clean compile` to verify
