# EXISTS Operator Analysis - Athena Query Federation

## Executive Summary

The EXISTS operator support in Athena Query Federation varies significantly between connector types. 
**NoSQL connectors have native EXISTS implementations**, while **JDBC-based connectors lack EXISTS support** due to 
framework limitations.

## Framework Architecture Impact

### JDBC-Based Connectors (SQL Databases)
- **Architecture**: Depend on `JdbcSplitQueryBuilder` for SQL generation
- **Limitation**: Framework doesn't support subquery parsing/generation
- **EXISTS Status**: ❌ **Not Supported** - requires framework enhancement

### NoSQL/API-Based Connectors  
- **Architecture**: Custom query builders with native API calls
- **Advantage**: Full control over query translation
- **EXISTS Status**: ✅ **Connector-specific implementations available**

---

## Detailed Connector Analysis

### 1. DocumentDB (MongoDB) - ✅ FULL EXISTS SUPPORT

**Implementation Location**: `athena-docdb/src/main/java/com/amazonaws/athena/connectors/docdb/QueryUtils.java`

**Native Operators Used**:
```java
private static final String EXISTS_OP = "$exists";
private static final String NOT_EQ_OP = "$ne";
private static final String EQ_OP = "$eq";
```

**Functionality**:
- **Field Existence**: `{"field": {"$exists": true}}`
- **Field Non-Existence**: `{"field": {"$exists": false}}`
- **Not Null Check**: `{"field": {"$ne": null}}`
- **Null Check**: `{"field": {"$eq": null}}`

**Implementation Details**:
```java
// For constraint.isAll() - field exists and not null
private static Document isNotNullPredicate() {
    return documentOf(NOT_EQ_OP, null);
}

// For constraint.isNone() - field doesn't exist or is null  
private static Document isNullPredicate() {
    return documentOf(EXISTS_OP, true).append(EQ_OP, null);
}
```

**Capabilities**: ✅ Most comprehensive EXISTS support using MongoDB's native `$exists` operator

---

### 2. DynamoDB - ✅ ATTRIBUTE EXISTS SUPPORT

**Implementation Location**: `athena-dynamodb/src/main/java/com/amazonaws/athena/connectors/dynamodb/util/DDBPredicateUtils.java`

**Native Functions Used**:
- `attribute_exists(field_name)`
- `attribute_not_exists(field_name)`

**Implementation Examples**:
```java
// Field exists check
return "attribute_exists(" + columnName + ")";

// Field doesn't exist check  
return "attribute_not_exists(" + columnName + ")";

// Combined with other conditions
return "(attribute_exists(" + columnName + ") AND " + 
       toPredicate(originalColumnName, "=", null, accumulator, valueNameProducer.getNext(), recordMetadata) + ")";
```

**Usage Patterns**:
- **IS_NOT_NULL**: `attribute_exists(field) AND field = value`
- **IS_NULL**: `attribute_not_exists(field) OR field <> value`
- **Null Allowed**: `attribute_not_exists(field) OR field = value`

**Capabilities**: ✅ Robust attribute existence checking using DynamoDB's native functions

---

### 3. Elasticsearch - ✅ FIELD EXISTS SUPPORT

**Implementation Location**: `athena-elasticsearch/src/main/java/com/amazonaws/athena/connectors/elasticsearch/ElasticsearchQueryUtils.java`

**Native Query Syntax**:
```java
private static final String existsPredicate(boolean exists, String fieldName) {
    if (exists) {
        return "(_exists_:" + fieldName + ")";
    } else {
        return "(NOT _exists_:" + fieldName + ")";
    }
}
```

**Query Generation**:
- **Field Exists**: `(_exists_:fieldname)`
- **Field Doesn't Exist**: `(NOT _exists_:fieldname)`

**Integration with Constraints**:
```java
if (constraint.isNone()) {
    return existsPredicate(false, fieldName);  // NOT _exists_
}

if (constraint.isAll()) {
    return existsPredicate(true, fieldName);   // _exists_
}
```

**Capabilities**: ✅ Native Elasticsearch `_exists_` query support

---

### 4. JDBC-Based Connectors - ❌ NO EXISTS SUPPORT (Both Traditional & Substrait)

**Affected Connectors**:
- Oracle (`athena-oracle`)
- MySQL (`athena-mysql`) 
- PostgreSQL (`athena-postgresql`)
- SQL Server (`athena-sqlserver`)
- Redshift (`athena-redshift`)
- And all other JDBC-based connectors

**Framework Limitation**: Both traditional constraints and Substrait approaches use `JdbcSplitQueryBuilder`

**Traditional Constraints Path**:
```java
// Uses StandardFunctions → JdbcSplitQueryBuilder → Simple SQL
// ❌ No EXISTS in StandardFunctions enum
// ❌ No subquery generation in framework
```

**Substrait Path**:
```java
// Uses Calcite parsing → JdbcSplitQueryBuilder → Complex SQL  
// ❌ No EXISTS subquery parsing in Substrait processing
// ❌ Same JdbcSplitQueryBuilder limitations
```

**Current Supported Operations**:
```java
// From StandardFunctions.java - NO EXISTS listed
AND_FUNCTION_NAME, OR_FUNCTION_NAME, NOT_FUNCTION_NAME,
EQUAL_OPERATOR_FUNCTION_NAME, NOT_EQUAL_OPERATOR_FUNCTION_NAME,
LESS_THAN_OPERATOR_FUNCTION_NAME, GREATER_THAN_OPERATOR_FUNCTION_NAME,
IN_PREDICATE_FUNCTION_NAME, LIKE_PATTERN_FUNCTION_NAME
```

**SqlKind Support**: Only basic operations
```java
// From FilterRemovalVisitor.java
if (kind == SqlKind.AND || kind == SqlKind.OR) {
    // Handle basic logical operations
}
// No SqlKind.EXISTS handling
```

**Missing Capabilities**:
- ❌ Subquery parsing in Substrait processing
- ❌ EXISTS SQL generation in query builder
- ❌ Correlated subquery support
- ❌ Cross-table query execution

---

## Technical Analysis

### Why NoSQL Connectors Support EXISTS

**1. Architectural Independence**:
```java
// NoSQL connectors have full control
public class DocumentDBRecordHandler extends RecordHandler {
    @Override
    public void readWithConstraint(...) {
        // Direct constraint → native query conversion
        Document query = QueryUtils.makeQuery(schema, constraints);
        // Execute using MongoDB driver
    }
}
```

**2. Native API Capabilities**:
- **MongoDB**: `$exists` operator built-in
- **DynamoDB**: `attribute_exists()` functions available
- **Elasticsearch**: `_exists_` queries supported

### Why JDBC Connectors Don't Support EXISTS

**1. Framework Dependency**:
```java
// JDBC connectors depend on shared framework
public class OracleRecordHandler extends JdbcRecordHandler {
    @Override
    public PreparedStatement buildSplitSql(...) {
        // Must use framework's SQL generation
        return jdbcSplitQueryBuilder.buildSql(...);
    }
}
```

**2. Framework Limitations**:
- **Substrait Processing**: No subquery parsing
- **SQL Generation**: No EXISTS clause generation
- **Query Planning**: Single-table focus, no cross-table operations

---

## Implementation Requirements

### For NoSQL Connectors (✅ Feasible)

**Requirements**: Connector-specific implementation only
- ✅ Parse constraints in RecordHandler
- ✅ Convert to native query syntax  
- ✅ Execute using connector's API
- ❌ **No framework changes needed**

**Example Implementation Pattern**:
```java
if (constraint.isAll()) {
    // Field exists and not null
    return nativeExistsQuery(fieldName, true);
}
if (constraint.isNone()) {
    // Field doesn't exist or is null
    return nativeExistsQuery(fieldName, false);
}
```

### For JDBC Connectors (❌ Complex)

**Requirements**: Major framework enhancements needed
- ❌ Enhance `StandardFunctions` enum
- ❌ Add subquery parsing to Substrait processing
- ❌ Extend `JdbcSplitQueryBuilder` for EXISTS SQL generation
- ❌ Implement correlated subquery parameter binding
- ❌ Add cross-table query execution capabilities

**Framework Changes Required**:
```java
// Would need additions like:
EXISTS_FUNCTION_NAME(new FunctionName("$exists"), OperatorType.SUBQUERY),

// And SqlKind handling:
if (kind == SqlKind.EXISTS) {
    // Generate EXISTS (SELECT ...) SQL
}
```

---

## Current Status Summary

| Connector Type | EXISTS Support | Implementation | Framework Changes |
|---------------|----------------|----------------|-------------------|
| **DocumentDB** | ✅ Full | Native `$exists` | ❌ None needed |
| **DynamoDB** | ✅ Attribute | `attribute_exists()` | ❌ None needed |
| **Elasticsearch** | ✅ Field | `_exists_:field` | ❌ None needed |
| **Oracle** | ❌ None | N/A | ✅ Required |
| **MySQL** | ❌ None | N/A | ✅ Required |
| **PostgreSQL** | ❌ None | N/A | ✅ Required |
| **All JDBC** | ❌ None | N/A | ✅ Required |

---

## Recommendations

### Short Term (NoSQL Connectors)
1. **Enhance existing implementations** where needed
2. **Add EXISTS support to connectors** that lack it (HBase, Kafka, etc.)
3. **Document EXISTS capabilities** for each NoSQL connector

### Long Term (JDBC Connectors)  
1. **Framework enhancement project** for subquery support
2. **Phased implementation**: Start with simple EXISTS, expand to correlated subqueries
3. **Cross-connector query engine** for complex operations

### Priority Assessment
- **High**: NoSQL connector enhancements (low effort, high value)
- **Medium**: Framework subquery architecture design
- **Low**: Full JDBC EXISTS implementation (high complexity)

---

## Conclusion

**EXISTS operator support is fundamentally limited by connector architecture, not constraint processing approach**:

- **NoSQL connectors**: ✅ Can implement EXISTS independently using native APIs (both traditional & Substrait)
- **JDBC connectors**: ❌ Blocked by framework limitations in both traditional constraints and Substrait approaches

**Key Insight**: Both traditional constraints and Substrait processing paths converge at the same `JdbcSplitQueryBuilder` framework, which lacks subquery generation capabilities. This means JDBC connectors cannot support EXISTS regardless of whether the query uses traditional constraints or Substrait processing.

**The architectural difference between connector types creates a clear divide in EXISTS support capabilities, with NoSQL connectors having significant advantages due to their implementation independence from the JDBC framework.**
