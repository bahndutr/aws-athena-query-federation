// IMPROVED APPROACH: Proper SQL parsing instead of string splitting

// Instead of string parsing:
// String[] selectItems = substraitSelectList.split(",");

// Use proper Calcite SQL parsing:
SqlNodeList selectList = select.getSelectList();
List<String> projectedColumns = new ArrayList<>();
Set<String> partitionColumns = split.getProperties().keySet();

for (SqlNode selectItem : selectList) {
    String columnExpression;
    
    if (selectItem instanceof SqlIdentifier) {
        // Simple column reference: column_name
        SqlIdentifier identifier = (SqlIdentifier) selectItem;
        String columnName = identifier.getSimple().toLowerCase();
        
        // Skip partition columns that don't exist in actual table
        if (!partitionColumns.contains(columnName)) {
            columnExpression = identifier.toSqlString(sqlDialect).getSql();
            projectedColumns.add(columnExpression);
        } else {
            LOGGER.info("Skipping partition column: {}", columnName);
        }
    } else {
        // Complex expression: functions, calculations, etc.
        // Keep as-is since these are computed expressions
        columnExpression = selectItem.toSqlString(sqlDialect).getSql();
        projectedColumns.add(columnExpression);
        LOGGER.info("Including complex expression: {}", columnExpression);
    }
}

String finalProjection = projectedColumns.isEmpty() ? 
    "1" : // Fallback for edge cases
    String.join(", ", projectedColumns);

sql.append(finalProjection);
