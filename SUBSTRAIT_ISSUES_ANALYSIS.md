# Substrait Conversion Issues Analysis

Based on Leo Yan's query-federation implementation, here's how these common Substrait issues are being addressed:

## 1. Schema Awareness in Substrait Conversion

### Current Framework Approach
Leo's implementation includes `BaseSchemaAwareConverter.java` that provides:

```java
public static AbstractTable makeCalciteTableFromBaseSchema(final NamedStruct schema) {
    return new AbstractTable() {
        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            List<String> colNames = schema.getNamesList();
            List<RelDataType> colTypes = new ArrayList<>();
            
            for (Type t : schema.getStruct().getTypesList()) {
                colTypes.add(substraitTypeToCalcite(typeFactory, t));
            }
            
            return typeFactory.createStructType(colTypes, colNames);
        }
    };
}
```

### Case Sensitivity Handling
- **Framework Level**: The `BaseSchemaAwareConverter` preserves original column names from Substrait schema
- **Connector Level**: Individual connectors override `getSqlDialect()` to handle database-specific case sensitivity
- **Expected Approach**: Connectors should implement database-specific case handling in their SQL dialect

### Recommendation
```java
// In your connector's JdbcSplitQueryBuilder
@Override
protected SqlDialect getSqlDialect() {
    return OracleDialect.DEFAULT; // Handles Oracle's case sensitivity rules
}
```

## 2. Parameter Binding Mismatches

### Current Solution in Framework
The `SubstraitAccumulatorVisitor.java` handles parameter mapping:

```java
public class SubstraitAccumulatorVisitor extends SqlShuttle {
    private List<SubstraitTypeAndValue> accumulator;
    private Map<String, String> splitProperties;
    private final Schema schema;
    
    // Accumulates parameters with proper type mapping
}
```

### Parameter Management Strategy
- **Type Mapping**: `SubstraitTypeAndValue.java` ensures type consistency between Substrait and JDBC
- **Parameter Collection**: Visitor pattern collects all parameters during SQL traversal
- **Binding Validation**: Framework validates parameter count before statement execution

### Best Practice for Connectors
```java
// In prepareStatementWithSqlDialect method
List<SubstraitTypeAndValue> accumulator = new ArrayList<>();
SubstraitAccumulatorVisitor visitor = new SubstraitAccumulatorVisitor(accumulator, split.getProperties(), tableSchema);

// Validate parameter count matches
if (accumulator.size() != expectedParamCount) {
    throw new RuntimeException("Parameter count mismatch");
}
```

## 3. Redundant Column Aliasing

### Current Behavior
The framework generates aliases like `column_name0` during Substrait-to-SQL conversion.

### Framework Position
- **Expected Behavior**: Yes, this is current expected behavior
- **Reason**: Ensures unique column references in complex queries
- **Performance Impact**: Minimal - databases optimize away unnecessary aliases

### Connector Optimization Options
```java
// Override in connector if needed
@Override
protected String getColumnProjection(Schema schema, String tableName) {
    // Custom logic to avoid redundant aliases
    return schema.getFields().stream()
        .map(Field::getName)
        .collect(Collectors.joining(", "));
}
```

## 4. Missing GROUP BY Clauses

### Known Issue Status
This appears to be a **known limitation** in the current Substrait implementation.

### Current Handling
- **Detection**: Framework detects aggregation functions in SELECT clause
- **Connector Responsibility**: Individual connectors must add GROUP BY logic
- **Workaround**: Override query building logic in connector

### Recommended Solution
```java
// In your connector's prepareStatementWithSqlDialect
if (hasAggregationFunctions(sqlNode) && !hasGroupBy(sqlNode)) {
    // Add GROUP BY clause based on non-aggregated columns
    String groupByClause = generateGroupByClause(sqlNode, tableSchema);
    sql.append(" ").append(groupByClause);
}
```

## 5. Partition Column Filtering

### Framework Solution
Leo's implementation specifically addresses this in `SnowflakeMetadataHandler.java`:

```java
public void enhancePartitionSchema(SchemaBuilder partitionSchemaBuilder, GetTableLayoutRequest request) {
    // Always ensure the partition column exists in the schema
    if (partitionSchemaBuilder.getField(BLOCK_PARTITION_COLUMN_NAME) == null) {
        partitionSchemaBuilder.addField(BLOCK_PARTITION_COLUMN_NAME, Types.MinorType.VARCHAR.getType());
    }
}
```

### Known Issue Across Connectors
- **Status**: Known issue, addressed in framework
- **Root Cause**: Partition columns are metadata, not actual table columns
- **Solution**: Framework ensures partition columns exist in schema before query generation

### Connector Implementation
```java
@Override
public void enhancePartitionSchema(SchemaBuilder partitionSchemaBuilder, GetTableLayoutRequest request) {
    // Ensure partition column exists
    if (partitionSchemaBuilder.getField(BLOCK_PARTITION_COLUMN_NAME) == null) {
        partitionSchemaBuilder.addField(BLOCK_PARTITION_COLUMN_NAME, Types.MinorType.VARCHAR.getType());
    }
    // Add other connector-specific partition fields
}
```

## Framework vs Connector Responsibilities

### Framework Provides
- `BaseSchemaAwareConverter` for schema mapping
- `SubstraitAccumulatorVisitor` for parameter collection
- `FilterRemovalVisitor` for query optimization
- Base partition column handling

### Connector Must Handle
- Database-specific SQL dialect (`getSqlDialect()`)
- Case sensitivity rules
- Custom GROUP BY logic for aggregations
- Database-specific partition column naming
- Parameter type conversions for specific databases

## Migration Strategy

### For Existing Connectors
1. **Extend JdbcSplitQueryBuilder** with Substrait support
2. **Override getSqlDialect()** for database-specific handling
3. **Implement partition schema enhancement**
4. **Add aggregation detection and GROUP BY generation**
5. **Test with Substrait-enabled queries**

### Testing Approach
```java
// Example test structure from SnowflakeSubstraitQueryBuilderTest.java
@Test
public void testSubstraitQueryWithAggregation() {
    // Test GROUP BY generation
    // Test parameter binding
    // Test partition column handling
}
```

## Conclusion

Leo Yan's implementation provides a solid framework foundation, but **connectors are expected to handle database-specific nuances individually**. The framework handles common patterns while allowing connector-level customization for database-specific requirements.

Most issues you've encountered are **known and have established patterns** for resolution within the framework.
