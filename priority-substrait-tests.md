# Priority Substrait Edge Cases for Oracle Connector

## High Priority (Implement First)

### 1. Empty/Null Substrait Plan Handling
**Why Critical**: Common edge case that could cause NullPointerException
```java
@Test
public void buildSplitSqlWithEmptySubstraitPlan() throws SQLException {
    // Test empty string vs null handling
    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn("");
    // Should gracefully fallback to traditional constraints
}
```

### 2. Special Characters in Substrait Plans
**Why Critical**: Real-world data often contains Unicode, quotes, escape sequences
```java
@Test
public void buildSplitSqlWithSpecialCharactersInSubstraitPlan() throws SQLException {
    String specialCharPlan = "substrait_plan_with_unicode_\u00E9\u00F1\u00FC_and_quotes_'\"";
    // Test proper escaping and handling
}
```

### 3. Mixed Constraints and Substrait Processing
**Why Critical**: Need to verify precedence when both exist
```java
@Test
public void buildSplitSqlWithMixedConstraintsAndSubstrait() throws SQLException {
    // Test interaction between traditional constraints AND Substrait plans
    // Verify which takes precedence
}
```

## Medium Priority (Implement Second)

### 4. Large Substrait Plan Memory Testing
**Why Important**: Prevents memory issues in production
```java
@Test
public void buildSplitSqlWithLargeSubstraitPlan() throws SQLException {
    String largePlan = "substrait_plan_data_".repeat(65536); // ~1MB
    // Test memory handling
}
```

### 5. Complex Oracle Partitioning with Substrait
**Why Important**: Oracle-specific feature that needs validation
```java
@Test
public void buildSplitSqlWithComplexOraclePartitioning() throws SQLException {
    // Test subpartitioning, range/hash/list partitioning
    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of(
        "partition_name", "SALES_Q1_2024",
        "subpartition_name", "SALES_Q1_2024_REGION_WEST"
    ));
}
```

### 6. Timeout Handling
**Why Important**: Prevents hanging queries
```java
@Test(timeout = 5000)
public void buildSplitSqlWithSubstraitPlanTimeout() throws SQLException {
    // Ensure processing completes within reasonable time
}
```

## Lower Priority (Nice to Have)

### 7. Oracle-Specific Data Types
```java
@Test
public void buildSplitSqlWithOracleSpecificDataTypes() throws SQLException {
    // Test CLOB, BLOB, TIMESTAMP WITH TIME ZONE, etc.
}
```

### 8. Reserved Words and Identifier Quoting
```java
@Test
public void testFromClauseWithReservedWordTableName() {
    // Test Oracle reserved words in identifiers
}
```

### 9. Long Identifier Names
```java
@Test
public void testFromClauseWithVeryLongIdentifiers() {
    // Test Oracle's 128 character identifier limit
}
```

## Implementation Strategy

### Phase 1: Core Substrait Edge Cases (Week 1)
1. Empty/Null Substrait plan handling
2. Special characters in plans
3. Mixed constraints and Substrait processing

### Phase 2: Oracle-Specific Features (Week 2)
1. Complex partitioning scenarios
2. Large plan memory testing
3. Timeout handling

### Phase 3: Advanced Edge Cases (Week 3)
1. Oracle data types
2. Reserved words
3. Long identifiers
4. Performance testing

## Quick Implementation Guide

### To add to OracleRecordHandlerTest.java:
```java
// Add these imports at the top
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Add the test methods from additional-substrait-tests.java
```

### To add to OracleQueryStringBuilderTest.java:
```java
// Add the test methods from additional-query-builder-tests.java
```

### Build and Test Commands:
```bash
# Test specific methods
mvn test -Dtest=OracleRecordHandlerTest#buildSplitSqlWithEmptySubstraitPlan -pl athena-oracle
mvn test -Dtest=OracleQueryStringBuilderTest#testQuoteIdentifiersWithUnicodeCharacters -pl athena-oracle

# Run all Oracle tests
mvn test -pl athena-oracle
```

## Expected Benefits

1. **Robustness**: Handle edge cases that could cause production failures
2. **Oracle Compliance**: Ensure Oracle-specific features work correctly
3. **Performance**: Identify memory and timeout issues early
4. **Security**: Validate proper escaping and SQL injection prevention
5. **Maintainability**: Comprehensive test coverage for future changes

## Metrics to Track

- Test coverage percentage increase
- Number of edge cases covered
- Performance benchmarks for large plans
- Memory usage patterns
- Error handling effectiveness
