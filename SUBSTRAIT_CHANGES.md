# Substrait Integration Changes in Query Federation Branch

## Overview
This document outlines all major changes related to Substrait integration in the query-federation branch, 
enabling advanced query processing capabilities in Amazon Athena Query Federation.

## New Substrait Components

### 1. SubstraitSqlUtils.java
**Location**: `athena-federation-sdk-tools/src/main/java/com/amazonaws/athena/connector/substrait/SubstraitSqlUtils.java`
- **Purpose**: Core utility class for Substrait SQL processing
- **Key Functions**:
  - `deserializeSubstraitPlan()` - Converts base64 encoded Substrait plans to SQL nodes
  - SQL dialect-specific query generation
  - Substrait plan parsing and validation

### 2. SubstraitTypeAndValue.java
**Location**: `athena-jdbc/src/main/java/com/amazonaws/athena/connectors/jdbc/manager/SubstraitTypeAndValue.java`
- **Purpose**: Type mapping and value handling for Substrait operations
- **Features**:
  - Arrow type to Substrait type conversion
  - Value serialization/deserialization
  - Type-safe parameter binding

### 3. SubstraitAccumulatorVisitor.java
**Location**: `athena-jdbc/src/main/java/com/amazonaws/athena/connectors/jdbc/visitor/SubstraitAccumulatorVisitor.java`
- **Purpose**: Visitor pattern implementation for accumulating Substrait query components
- **Functionality**:
  - Query tree traversal
  - Parameter collection and binding
  - Filter condition accumulation

### 4. FilterRemovalVisitor.java
**Location**: `athena-jdbc/src/main/java/com/amazonaws/athena/connectors/jdbc/visitor/FilterRemovalVisitor.java`
- **Purpose**: Query optimization through filter removal and pushdown
- **Features**:
  - Predicate pushdown optimization
  - Filter condition analysis
  - Query plan simplification

## Enhanced JDBC Framework

### JdbcSplitQueryBuilder Enhancements
**Location**: `athena-jdbc/src/main/java/com/amazonaws/athena/connectors/jdbc/manager/JdbcSplitQueryBuilder.java`

#### New Methods Added:
```java
// Core Substrait integration
protected PreparedStatement prepareStatementWithSqlDialect(
    Connection jdbcConnection, 
    Constraints constraints, 
    SqlDialect sqlDialect,
    Split split, 
    String catalog, 
    String schema, 
    String table, 
    String columnNames,
    Schema tableSchema)

// SQL dialect support
protected SqlDialect getSqlDialect()

// Enhanced limit/offset handling
protected String appendLimitOffsetWithValue(String limit, String offset)
```

#### Key Features:
- **Query Plan Detection**: Checks for `constraints.getQueryPlan()` to determine Substrait usage
- **SQL Dialect Integration**: Uses Apache Calcite's SQL dialect system
- **Dynamic Query Building**: Constructs queries based on Substrait plans
- **Parameter Binding**: Handles parameterized queries with type safety

### Query Processing Flow
1. **Plan Detection**: Check if request contains Substrait query plan
2. **Deserialization**: Convert base64 encoded plan to SQL AST
3. **Visitor Processing**: Apply accumulator and filter visitors
4. **SQL Generation**: Generate database-specific SQL using dialect
5. **Parameter Binding**: Bind parameters with proper type conversion

## Connector-Specific Substrait Support

### Snowflake Connector
**Location**: `athena-snowflake/`
- **Enhanced Query Builder**: `SnowflakeQueryStringBuilder.java` with Substrait support
- **Test Coverage**: `SnowflakeSubstraitQueryBuilderTest.java` (330+ lines of tests)
- **SQL Dialect**: Custom Snowflake SQL dialect implementation

### JDBC Base Framework
- **Schema Conversion**: `BaseSchemaAwareConverter.java` for Arrow-to-SQL type mapping
- **Metadata Handling**: Enhanced metadata processing with Substrait awareness
- **Record Processing**: Improved record handling with query plan integration

## Dependencies Added

### Apache Calcite Integration
```xml
<org.apache.calcite.version>1.40.0</org.apache.calcite.version>
```
- SQL parsing and generation
- Dialect-specific query optimization
- AST manipulation and transformation

### Substrait Core
```xml
<io.substrait.version>0.52.0</io.substrait.version>
```
- Substrait plan processing
- Type system integration
- Query plan serialization/deserialization

## Key Imports and Type Mappings

### New Calcite Imports:
```java
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.util.BitString;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.TimestampString;
```

### Type System Support:
```java
import static org.apache.calcite.sql.type.SqlTypeName.BIGINT;
import static org.apache.calcite.sql.type.SqlTypeName.DECIMAL;
import static org.apache.calcite.sql.type.SqlTypeName.DOUBLE;
import static org.apache.calcite.sql.type.SqlTypeName.FLOAT;
```

## Benefits of Substrait Integration

### Performance Improvements
- **Query Optimization**: Advanced predicate pushdown and filter optimization
- **Reduced Data Transfer**: More efficient query execution with better pushdown
- **Parallel Processing**: Enhanced split generation based on query analysis

### Enhanced Functionality
- **Cross-Database Queries**: Better support for federated queries across different databases
- **Type Safety**: Improved type handling and conversion
- **SQL Dialect Support**: Database-specific optimizations

### Developer Experience
- **Standardized Interface**: Consistent query processing across all JDBC connectors
- **Extensibility**: Easy addition of new database-specific optimizations
- **Testing**: Comprehensive test coverage for Substrait functionality

## Migration Impact
- **Backward Compatibility**: Existing queries continue to work without Substrait
- **Opt-in Enhancement**: Substrait features activate only when query plans are present
- **Gradual Adoption**: Connectors can implement Substrait support incrementally

## Future Enhancements
- Additional SQL dialect support for more databases
- Advanced query optimization patterns
- Enhanced cross-connector query federation capabilities
