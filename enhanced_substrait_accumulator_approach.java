/**
 * ENHANCED APPROACH: Smart Parameter Accumulation for Substrait
 * 
 * This approach makes the accumulator conditionally useful based on:
 * 1. Parameter size/complexity thresholds
 * 2. Security considerations (prevent SQL injection)
 * 3. Performance optimization for repeated values
 * 4. Database-specific parameter limits
 */

public class EnhancedSubstraitAccumulatorVisitor extends SqlShuttle {
    
    private List<SubstraitTypeAndValue> accumulator;
    private Map<String, String> splitProperties;
    private boolean useParameterization;
    private int literalSizeThreshold;
    private Set<String> sensitiveColumns;
    
    public EnhancedSubstraitAccumulatorVisitor(List<SubstraitTypeAndValue> accumulator, 
                                             Map<String, String> splitProperties,
                                             boolean useParameterization,
                                             int literalSizeThreshold) {
        this.accumulator = accumulator;
        this.splitProperties = splitProperties;
        this.useParameterization = useParameterization;
        this.literalSizeThreshold = literalSizeThreshold;
        this.sensitiveColumns = Set.of("password", "token", "key", "secret");
    }

    @Override
    public SqlNode visit(SqlLiteral literal) {
        
        // STRATEGY 1: Always parameterize large literals (performance)
        if (shouldParameterizeLargeLiteral(literal)) {
            return convertToParameter(literal);
        }
        
        // STRATEGY 2: Parameterize sensitive data (security)
        if (shouldParameterizeSensitiveData(literal)) {
            return convertToParameter(literal);
        }
        
        // STRATEGY 3: Parameterize repeated values (optimization)
        if (shouldParameterizeRepeatedValue(literal)) {
            return convertToParameter(literal);
        }
        
        // STRATEGY 4: Keep small, safe literals as-is (current behavior)
        return literal;
    }
    
    private boolean shouldParameterizeLargeLiteral(SqlLiteral literal) {
        if (literal.getValue() instanceof NlsString) {
            String value = ((NlsString) literal.getValue()).getValue();
            return value.length() > literalSizeThreshold;
        }
        return false;
    }
    
    private boolean shouldParameterizeSensitiveData(SqlLiteral literal) {
        // Check if this literal appears in a sensitive context
        // This would require context tracking from the parent SQL nodes
        return false; // Simplified for now
    }
    
    private boolean shouldParameterizeRepeatedValue(SqlLiteral literal) {
        // Check if this literal value appears multiple times
        // Could maintain a frequency map
        return false; // Simplified for now
    }
    
    private SqlNode convertToParameter(SqlLiteral literal) {
        if (literal.getValue() instanceof NlsString) {
            accumulator.add(new SubstraitTypeAndValue(
                literal.getTypeName(), 
                ((NlsString) literal.getValue()).getValue()
            ));
        } else {
            accumulator.add(new SubstraitTypeAndValue(
                literal.getTypeName(), 
                literal.getValue()
            ));
        }
        return new SqlDynamicParam(accumulator.size() - 1, literal.getParserPosition());
    }
}

/**
 * USAGE SCENARIOS WHERE ACCUMULATOR IS USEFUL:
 * 
 * 1. LARGE TEXT VALUES:
 *    - Long strings > 1000 chars
 *    - JSON/XML documents
 *    - Base64 encoded data
 * 
 * 2. SECURITY SENSITIVE:
 *    - User input that might contain injection attempts
 *    - Dynamic values from external sources
 * 
 * 3. PERFORMANCE OPTIMIZATION:
 *    - Repeated literal values in complex queries
 *    - Values that benefit from prepared statement caching
 * 
 * 4. DATABASE LIMITS:
 *    - Oracle: 4000 char limit for literals
 *    - SQL Server: 8000 char limit
 *    - MySQL: max_allowed_packet considerations
 */

/**
 * CONFIGURATION OPTIONS:
 */
public class SubstraitParameterizationConfig {
    
    public static final int DEFAULT_LITERAL_SIZE_THRESHOLD = 1000;
    public static final boolean DEFAULT_USE_PARAMETERIZATION = false;
    
    // Environment-based configuration
    public static SubstraitParameterizationConfig fromEnvironment() {
        return new SubstraitParameterizationConfig(
            Boolean.parseBoolean(System.getenv("SUBSTRAIT_USE_PARAMETERIZATION")),
            Integer.parseInt(System.getenv().getOrDefault("SUBSTRAIT_LITERAL_THRESHOLD", "1000"))
        );
    }
    
    // Database-specific configurations
    public static SubstraitParameterizationConfig forOracle() {
        return new SubstraitParameterizationConfig(true, 3000); // Below 4000 char limit
    }
    
    public static SubstraitParameterizationConfig forPostgreSQL() {
        return new SubstraitParameterizationConfig(false, 8000); // More permissive
    }
}
