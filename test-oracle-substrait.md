# Oracle Substrait Testing Guide

## Test Queries to Validate Issues

### 1. Schema Awareness / Case Sensitivity Test
```sql
-- Test case sensitivity with mixed case column names
SELECT employee_id, FIRST_NAME, last_name 
FROM lambda:oracle-connector.hr.employees 
WHERE department_id = 10;
```

### 2. Parameter Binding Test
```sql
-- Test parameter binding with different data types
SELECT * FROM lambda:oracle-connector.hr.employees 
WHERE employee_id = ? AND salary > ? AND hire_date = ?;
```

### 3. Redundant Column Aliasing Test
```sql
-- Check if aliases are properly generated
SELECT e.employee_id, e.first_name, d.department_name
FROM lambda:oracle-connector.hr.employees e
JOIN lambda:oracle-connector.hr.departments d ON e.department_id = d.department_id;
```

### 4. GROUP BY Clause Test
```sql
-- Test aggregation functions with GROUP BY
SELECT department_id, COUNT(*), AVG(salary)
FROM lambda:oracle-connector.hr.employees
GROUP BY department_id;
```

### 5. Partition Column Test
```sql
-- Test partition column handling
SELECT * FROM lambda:oracle-connector.sales.sales_data
WHERE partition_date = '2023-01-01';
```

## Expected Errors to Report to Yan

### Error 1: ORA-00904 Invalid Identifier
```
ORA-00904: "partition_name": invalid identifier
```
**Cause**: Partition column included in SELECT but not in actual table schema

### Error 2: Parameter Count Mismatch
```
java.sql.SQLException: Parameter index out of range (3 > number of parameters, which is 2)
```
**Cause**: Substrait plan parameter count doesn't match prepared statement

### Error 3: Missing GROUP BY
```
ORA-00979: not a GROUP BY expression
```
**Cause**: Aggregation functions without proper GROUP BY clause generation

### Error 4: Case Sensitivity Issues
```
ORA-00904: "EMPLOYEE_ID": invalid identifier
```
**Cause**: Column name case mismatch between Substrait plan and Oracle schema

### Error 5: Redundant Aliases
```sql
-- Generated SQL with unnecessary aliases
SELECT column_name0 AS employee_id, column_name1 AS first_name 
FROM hr.employees column_name0
```
**Cause**: Substrait-to-SQL conversion adding redundant column aliases

## Testing Steps

1. **Deploy Oracle Connector** with Substrait support
2. **Run test queries** against Oracle database
3. **Document specific errors** with stack traces
4. **Report to Yan** without mentioning our fixes
5. **Wait for his response** before revealing our solutions

## Oracle-Specific Fixes Applied (Keep Secret)

- ✅ Added `OracleSqlDialect.DEFAULT` for proper SQL generation
- ✅ Implemented `appendLimitOffsetWithValue()` for Oracle FETCH syntax
- ✅ Added partition column schema enhancement
- ✅ Oracle-specific LIMIT/OFFSET handling with FETCH FIRST syntax

## Success Criteria

After our fixes are integrated:
- ✅ No ORA-00904 partition column errors
- ✅ Proper parameter binding without count mismatches
- ✅ Correct GROUP BY clause generation
- ✅ Case-sensitive column name resolution
- ✅ Clean SQL generation without redundant aliases
