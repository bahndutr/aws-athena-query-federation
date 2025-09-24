/**
 * IMPROVED FilterRemovalVisitor - Completely removes partition filters instead of replacing with boolean literals
 */

public class ImprovedFilterRemovalVisitor extends SqlShuttle {
    
    private final Set<String> partitionColumns;
    
    public ImprovedFilterRemovalVisitor(Set<String> partitionColumns) {
        this.partitionColumns = partitionColumns;
    }
    
    @Override
    public SqlNode visit(SqlCall call) {
        SqlKind kind = call.getKind();
        
        // Handle AND/OR operations by filtering out partition conditions
        if (kind == SqlKind.AND || kind == SqlKind.OR) {
            List<SqlNode> filteredOperands = new ArrayList<>();
            
            for (SqlNode operand : call.getOperandList()) {
                SqlNode processedOperand = operand.accept(this);
                
                // Only keep non-null operands (null means partition filter was removed)
                if (processedOperand != null && !isAlwaysTrue(processedOperand)) {
                    filteredOperands.add(processedOperand);
                }
            }
            
            // Handle different cases based on remaining operands
            if (filteredOperands.isEmpty()) {
                return null; // All conditions were partition filters
            } else if (filteredOperands.size() == 1) {
                return filteredOperands.get(0); // Single condition remaining
            } else {
                return call.getOperator().createCall(SqlParserPos.ZERO, filteredOperands);
            }
        }
        
        // Check if this is a partition column condition
        if (isPartitionColumnCondition(call)) {
            return null; // Remove partition conditions completely
        }
        
        return super.visit(call);
    }
    
    private boolean isPartitionColumnCondition(SqlCall call) {
        if (call.operandCount() < 1) {
            return false;
        }
        
        SqlNode first = call.operand(0);
        if (first instanceof SqlIdentifier) {
            String columnName = ((SqlIdentifier) first).getSimple();
            return partitionColumns.stream()
                    .anyMatch(partitionCol -> partitionCol.equalsIgnoreCase(columnName));
        }
        
        return false;
    }
    
    private boolean isAlwaysTrue(SqlNode node) {
        return node instanceof SqlLiteral && 
               Boolean.TRUE.equals(((SqlLiteral) node).getValue());
    }
}

/**
 * COMPARISON: Current vs Improved
 */

// CURRENT APPROACH:
// WHERE partition_year = 2023 AND status = 'active'
// BECOMES: WHERE TRUE AND status = 'active'

// IMPROVED APPROACH:  
// WHERE partition_year = 2023 AND status = 'active'
// BECOMES: WHERE status = 'active'

// CURRENT PROBLEM:
// WHERE partition_year = 2023
// BECOMES: WHERE TRUE  (selects all rows!)

// IMPROVED SOLUTION:
// WHERE partition_year = 2023  
// BECOMES: (no WHERE clause - handled properly by caller)
