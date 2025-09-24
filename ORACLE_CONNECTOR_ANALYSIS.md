# Oracle Connector: Connector Analysis

## Current Implementation Status  

**Development & Unit tests completed**, including comprehensive Substrait implementation with GROUP BY processing, 
parameter handling, and partition column filtering. Changes implemented in aws-athena-query-federation forked Github repo 
and deployed to production Lambda function.

**Issues/Blockers**: 
- ✅ **Resolved**: ORA-00904 "partition_name": invalid identifier errors
- ✅ **Resolved**: ORA-00937 GROUP BY clause errors  
- ✅ **Resolved**: Unnecessary column aliasing in generated SQL
- ✅ **Resolved**: Parameter binding and mismatch issues

**Recent Enhancements**: Complete Substrait integration with enhanced query processing capabilities.

## Connector changes aligned with SDK updates:

### 1. MetadataHandler:
1. **doListSchemaNames**: ✅ **Implemented**. Uses JDBC metadata to query Oracle system tables for schema enumeration.
2. **doListTables**: ✅ **Implemented** for both paginated and non-paginated calls.
   - **Non-paginated**: Direct JDBC metadata queries against Oracle data dictionary
   - **Paginated**: Supports large schema enumeration with pagination tokens
3. **doGetTable**: ✅ **Implemented** for both paginated and non-paginated calls.
   - **Non-paginated**: Full table schema retrieval via JDBC metadata
   - **Paginated**: Handles large column sets with pagination support

### 2. RecordHandler: 
✅ **Fully Implemented**. Enhanced `doReadRecords` with:
- **Substrait Query Processing**: Complete integration with Substrait query plans
- **Advanced Column Projection**: Schema-aware column mapping and null handling
- **Parameter Binding**: Robust parameter handling for prepared statements
- **Partition Filtering**: Intelligent filtering of metadata-only partition columns

## Technical Summary

### 1. Type of Platform: **Relational Database (RDBMS)**
Specifically designed for Oracle Database (11g, 12c, 18c, 19c, 21c) with full JDBC connectivity.

### 2. Details of Each Connection Mechanism Supported

- **JDBC Connection String via Environment Variable**:
  - Each catalog uses a dedicated environment variable for Oracle connection details
  - Format: `oracle://jdbc:oracle:thin:${credentials}@host:port/service`
- **AWS Secrets Manager Integration**:
  - Secure credential management via `${AthenaJdbcFederation/oracle/default}` pattern
  - Automatic credential rotation support
- **Connection Pooling**:
  - HikariCP connection pool for optimal performance
  - Configurable pool size and connection timeout settings
- **SSL/TLS Support**:
  - Encrypted connections with certificate validation
  - Support for Oracle Wallet and custom keystores

### 3. Data Types Supported

The connector provides comprehensive Oracle data type mapping:

- **Numeric Types**: `NUMBER`, `INTEGER`, `FLOAT`, `DOUBLE PRECISION`, `DECIMAL`
- **String Types**: `VARCHAR2`, `CHAR`, `NVARCHAR2`, `NCHAR`, `CLOB`, `NCLOB`
- **Date/Time Types**: `DATE`, `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE`, `TIMESTAMP WITH LOCAL TIME ZONE`
- **Binary Types**: `RAW`, `LONG RAW`, `BLOB`
- **Specialized Types**: `ROWID`, `UROWID`
- **JSON Support**: `JSON` columns (Oracle 12c+)
- **Spatial Types**: Basic support for `SDO_GEOMETRY` (as VARCHAR)

### 4. Operators Supported

The connector supports comprehensive predicate pushdown with Oracle-specific optimizations:

- **Comparison Operators**: `=`, `!=`, `<>`, `>`, `>=`, `<`, `<=`
- **Set Operators**: `IN`, `NOT IN`
- **Range Operators**: `BETWEEN`, `NOT BETWEEN`
- **Pattern Matching**: `LIKE`, `NOT LIKE` with Oracle wildcard support
- **Null Handling**: `IS NULL`, `IS NOT NULL`
- **Logical Operators**: `AND`, `OR`, `NOT`
- **Substrait Operators**: Full support for Substrait expression pushdown

### 5. Query Functionalities Supported

- **WHERE Clause**: ✅ **Full Support** with complex predicates and Substrait integration
- **GROUP BY**: ✅ **Enhanced Support** with Substrait GROUP BY processing
- **ORDER BY**: ✅ **Full Support** with multi-column sorting
- **LIMIT/OFFSET**: ✅ **Oracle-Optimized** using `FETCH FIRST n ROWS ONLY` syntax
- **JOIN Operations**: ✅ **Supported** via Athena's distributed join processing
- **Aggregate Functions**: ✅ **Full Support** - `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `STDDEV`, etc.
- **Window Functions**: ✅ **Supported** for Oracle 11g+ analytical functions
- **Subqueries**: ✅ **Supported** with proper correlation handling
- **Advanced SQL**: ✅ **Oracle-Specific** functions and syntax optimization

### 6. Partitioning Support: **Advanced Partitioning**

- **Oracle Native Partitioning**: ✅ **Full Support**
  - Range, List, Hash, and Composite partitioning
  - Partition pruning optimization
  - Subpartition support
- **Athena Split Generation**: ✅ **Intelligent Splitting**
  - Partition-aware split generation
  - Parallel processing across partitions
  - Metadata-driven split optimization
- **Partition Column Filtering**: ✅ **Enhanced** 
  - Automatic filtering of Athena partition metadata columns
  - Prevents ORA-00904 errors from non-existent partition columns

## Design Changes Implemented for Substrait QueryPlan Integration:

### **Enhanced JdbcSplitQueryBuilder**:
The core query building logic has been completely enhanced to consume Substrait QueryPlan:

```java
// Primary entry point now handles Substrait plans
if (split.getProperty(SUBSTRAIT_PLAN_KEY) != null) {
    return buildFromSubstraitPlan(split, constraints, schema, connection);
}
// Fallback to traditional constraint-based queries
return buildFromConstraints(constraints, schema, connection);
```

### **Substrait Plan Processing**:
- **Enhanced `buildFromSubstraitPlan()` method**:
  - Utilizes centralized Substrait parsing utilities from `athena-federation-sdk`
  - Converts Substrait logical plans into Oracle-optimized SQL
  - Handles complex GROUP BY, aggregations, and filtering operations

### **Partition Column Intelligence**:
```java
// Intelligent partition column filtering
Set<String> partitionColumns = extractPartitionColumns(split);
for (SqlNode selectItem : selectList) {
    if (isPartitionColumn(selectItem, partitionColumns)) {
        LOGGER.info("Skipping partition column: {}", columnName);
        continue; // Skip metadata-only columns
    }
    projectedColumns.add(cleanColumnExpression(selectItem));
}
```

### **Parameter Binding Enhancement**:
- **Robust Parameter Handling**: Enhanced `SubstraitAccumulatorVisitor` for proper parameter collection
- **Type-Safe Binding**: Improved parameter type detection and binding
- **Null Safety**: Enhanced null value handling for optional parameters

### **Backwards Compatibility**:
- **Constraint Fallback**: Maintains full backwards compatibility with existing constraint-based queries
- **Dual Path Support**: Both Substrait and traditional constraint processing coexist
- **Graceful Degradation**: Falls back to constraints if Substrait processing fails

## Performance Optimizations:

### **SQL Generation Improvements**:
- **Clean Column Names**: Eliminated unnecessary aliases (`"column" "column0"` → `"column"`)
- **Oracle-Specific Syntax**: Optimized `FETCH FIRST n ROWS ONLY` instead of generic LIMIT
- **Predicate Pushdown**: Enhanced pushdown of complex Substrait expressions

### **Connection Management**:
- **Connection Pooling**: HikariCP integration for optimal connection reuse
- **Prepared Statement Caching**: Reduced SQL parsing overhead
- **Transaction Optimization**: Proper transaction boundary management

### **Memory Management**:
- **Streaming Results**: Large result set streaming to prevent memory issues
- **Arrow Buffer Optimization**: Efficient Arrow vector allocation and management
- **Spill Handling**: Configurable spill-to-S3 for large datasets

## Monitoring and Observability:

### **Enhanced Logging**:
- **Query Processing**: Detailed logging of Substrait plan processing
- **Performance Metrics**: Connection timing, query execution metrics
- **Error Diagnostics**: Comprehensive error reporting with context
- **Debug Capabilities**: Configurable debug logging for troubleshooting

### **CloudWatch Integration**:
- **Lambda Metrics**: Function duration, memory usage, error rates
- **Custom Metrics**: Query performance, connection pool statistics
- **Alerting**: Configurable alerts for error conditions and performance degradation

## Security Features:

### **Credential Management**:
- **AWS Secrets Manager**: Secure credential storage and rotation
- **IAM Integration**: Role-based access control
- **Encryption**: TLS encryption for data in transit

### **Network Security**:
- **VPC Support**: Private network connectivity
- **Security Groups**: Network-level access control
- **SSL/TLS**: Encrypted database connections

## Future Enhancements:

### **Advanced Features**:
- **Oracle Advanced Security**: Transparent Data Encryption (TDE) support
- **Oracle RAC**: Real Application Clusters connectivity
- **Oracle Exadata**: Optimized queries for Exadata platforms
- **Advanced Analytics**: Enhanced support for Oracle analytical functions

### **Performance Optimizations**:
- **Parallel Query**: Enhanced parallel processing capabilities
- **Result Caching**: Query result caching for frequently accessed data
- **Adaptive Splitting**: Dynamic split generation based on data distribution

---

## Conclusion

The Oracle Connector represents a comprehensive, production-ready solution for integrating Oracle databases with Amazon Athena. The recent Substrait implementation provides:

1. **Advanced Query Processing**: Full Substrait integration with GROUP BY, aggregations, and complex filtering
2. **Robust Error Handling**: Intelligent partition column filtering and parameter binding
3. **Performance Optimization**: Clean SQL generation and Oracle-specific optimizations
4. **Enterprise Features**: Comprehensive security, monitoring, and scalability features
5. **Future-Proof Architecture**: Extensible design for advanced Oracle features

The connector successfully addresses all identified issues while maintaining backwards compatibility and providing a solid foundation for future enhancements.
