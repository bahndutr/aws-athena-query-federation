# Oracle Connector: Substrait QueryPlan Implementation Analysis

## Current Implementation Status  

**Development & Unit tests completed** for Substrait QueryPlan integration. 
Changes implemented in aws-athena-query-federation forked Github repo and deployed to production Lambda function.

**Issues/Blockers**: 
- ✅ **Resolved**: ORA-00904 "partition_name": invalid identifier errors from Substrait plans
- ✅ **Resolved**: ORA-00937 GROUP BY clause errors in Substrait processing
- ✅ **Resolved**: Unnecessary column aliasing in Substrait-generated SQL
- ✅ **Resolved**: Parameter binding mismatches in Substrait queries
- ✅ **Resolved**: Schema-unaware Substrait-to-SQL conversion causing column resolution failures

## Connector changes aligned with SDK updates:

### 1. MetadataHandler:
1. **doListSchemaNames**: NA. No QueryPlan consumption required for schema enumeration.
2. **doListTables**: NA. No QueryPlan consumption required for table listing.
3. **doGetTable**: NA. No QueryPlan consumption required for table schema retrieval.

### 2. RecordHandler: 
✅ **Fully Implemented**. Enhanced `doReadRecords` with complete Substrait QueryPlan processing:
- **Primary Integration Point**: `OracleRecordHandler.buildSplitSql()` with Substrait detection
- **Substrait Plan Processing**: Utilizes `JdbcSplitQueryBuilder.buildSql()` for plan conversion
- **Oracle-Specific Optimizations**: `OracleQueryStringBuilder` overrides for Oracle SQL dialect

**Notes:**
- Oracle connector inherits Substrait processing from enhanced `athena-jdbc` package
- Oracle-specific SQL dialect (`OracleSqlDialect.DEFAULT`) ensures proper Oracle syntax generation
- Enhanced logging provides detailed Substrait plan processing visibility for Oracle queries

## Technical Summary

### 1. Type of Platform: **Relational Database (RDBMS)**
Oracle Database connector with full Substrait QueryPlan processing capabilities and Oracle-specific SQL optimizations.

### 2. Details of Substrait QueryPlan Processing Mechanism

- **QueryPlan Detection**: 
  - `OracleRecordHandler.buildSplitSql()` checks `constraints.getQueryPlan() != null`
  - Enhanced logging for Substrait plan presence and size
- **Processing Pipeline**:
  - Delegation to `JdbcSplitQueryBuilder.buildSql()` for Substrait conversion
  - Oracle SQL dialect application via `OracleQueryStringBuilder.getSqlDialect()`
  - Schema-aware processing through `CustomSubstraitToCalcite`
- **Connection Management**:
  - JDBC connection handling with Oracle-specific optimizations
  - HikariCP connection pooling maintained for Substrait queries

### 3. Substrait Features Supported

The Oracle connector supports comprehensive Substrait QueryPlan operations:

- **Column Projections**: `SELECT` with Oracle identifier quoting and clean column names
- **Predicate Pushdown**: `WHERE` clauses with Oracle-specific operator support  
- **Aggregations**: `GROUP BY` operations with Oracle syntax optimization
- **Sorting**: `ORDER BY` clauses with multi-column Oracle sorting
- **Pagination**: Oracle-native `FETCH FIRST n ROWS ONLY` and `OFFSET n ROWS` syntax
- **Parameter Binding**: Oracle data type-specific handling (NUMBER, DATE, TIMESTAMP, VARCHAR2)

### 4. Operators Supported via Substrait

The connector supports comprehensive predicate pushdown through Substrait QueryPlan processing:

- **Comparison Operators**: `=`, `!=`, `<>`, `>`, `>=`, `<`, `<=`
- **Set Operators**: `IN`, `NOT IN`
- **Range Operators**: `BETWEEN`, `NOT BETWEEN`
- **Pattern Matching**: `LIKE`, `NOT LIKE` with Oracle wildcard support
- **Null Handling**: `IS NULL`, `IS NOT NULL`
- **Logical Operators**: `AND`, `OR`, `NOT`
- **Substrait Expressions**: Complex expressions and function calls from Substrait plans

### 5. Query Functionalities Supported via Substrait

- **WHERE Clause**: ✅ **Full Support** with Oracle predicate pushdown optimization
- **GROUP BY**: ✅ **Enhanced Support** with Oracle aggregation syntax
- **ORDER BY**: ✅ **Full Support** with Oracle multi-column sorting
- **LIMIT/OFFSET**: ✅ **Oracle-Optimized** using native Oracle pagination syntax
- **Complex Predicates**: ✅ **Supported** with Oracle operator translation
- **Parameter Binding**: ✅ **Oracle-Specific** type handling and conversion
- **Column Projection**: ✅ **Enhanced** with metadata column filtering and clean naming

### 6. Partitioning Support: **No Changes to Partitioning Behavior**

- **Oracle Native Partitioning**: ✅ **Unchanged**
  - Existing Oracle partition support maintained
  - No changes to partition detection or split generation
  - Partition pruning optimization preserved
- **Athena Split Generation**: ✅ **Unchanged**
  - No modifications to `doGetSplits()` method
  - Split generation logic remains identical
  - Parallel processing across splits maintained
- **Partition Column Filtering**: ✅ **SQL-Level Only**
  - Filters Athena metadata columns from SELECT statements only
  - Does not affect split generation or partition detection
  - Prevents ORA-00904 errors without changing partitioning behavior

## Design Changes Required to consume QueryPlan instead of Constraints:

### **Oracle-Specific Substrait Integration**:

The Oracle connector leverages the enhanced `JdbcSplitQueryBuilder` from the common JDBC package:

```java
// Oracle connector inherits Substrait support from athena-jdbc
@Override
public PreparedStatement buildSplitSql(Connection jdbcConnection, String catalogName, 
                                     TableName tableName, Schema schema, 
                                     Constraints constraints, Split split) throws SQLException {
    
    if (constraints.getQueryPlan() != null) {
        LOGGER.info("=== SUBSTRAIT QUERY PLAN DETECTED ===");
        LOGGER.info("Query plan length: {} bytes", constraints.getQueryPlan().getSubstraitPlan().length());
    }
    
    // Inherits enhanced buildSql() with Substrait support from JdbcSplitQueryBuilder
    PreparedStatement preparedStatement = jdbcSplitQueryBuilder.buildSql(
        jdbcConnection, null, tableName.getSchemaName(), tableName.getTableName(), 
        schema, constraints, split);
    
    return preparedStatement;
}
```

### **Oracle SQL Dialect Integration**:

Oracle connector utilizes `OracleSqlDialect` for Substrait plan conversion through overridden methods:

```java
// OracleQueryStringBuilder overrides getSqlDialect() for Substrait processing
@Override
protected SqlDialect getSqlDialect() {
    return OracleSqlDialect.DEFAULT;  // Oracle-specific SQL dialect for Substrait
}

// Oracle-specific LIMIT/OFFSET syntax for Substrait queries
@Override
protected String appendLimitOffsetWithValue(String limit, String offset) {
    if (offset != null && !offset.equals("0")) {
        return String.format(" OFFSET %s ROWS FETCH FIRST %s ROWS ONLY", offset, limit);
    }
    return String.format(" FETCH FIRST %s ROWS ONLY", limit);
}
```

### **Backwards Compatibility Mechanism**:

Ensures existing constraint-based Oracle queries continue to work:

```java
// Dual-path support in JdbcSplitQueryBuilder (inherited by Oracle)
if (split.getProperty(SUBSTRAIT_PLAN_KEY) != null) {
    try {
        return buildFromSubstraitPlan(constraints.getQueryPlan(), schema, connection);
    } catch (Exception e) {
        LOGGER.warn("Substrait processing failed, falling back to constraints");
        // Graceful fallback to existing Oracle constraint logic
    }
}
// Fallback: Use existing Oracle constraint-based processing
return buildFromConstraints(constraints, schema, connection);
```

### **doGetSplits (OracleMetadataHandler)**:

There is no use of Constraints or QueryPlan in the `doGetSplits` method of Oracle connector and hence, QueryPlan does not have a point of consumption here. Split generation remains unchanged and uses existing Oracle partition-aware logic.
