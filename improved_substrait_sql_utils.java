/**
 * IMPROVED SubstraitSqlUtils with better error handling and design
 */

public final class ImprovedSubstraitSqlUtils {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SubstraitSqlUtils.class);
    
    // IMPROVEMENT 1: Better error handling with specific exceptions
    public static SqlNode deserializeSubstraitPlan(String planString, SqlDialect sqlDialect, 
                                                  String schemaName, String tableName, 
                                                  org.apache.arrow.vector.types.pojo.Schema tableSchema) {
        // IMPROVEMENT 2: Input validation
        validateInputs(planString, sqlDialect, tableName, tableSchema);
        
        try {
            LOGGER.debug("Deserializing Substrait plan with schema-aware converter for table: {}", tableName);
            
            // IMPROVEMENT 3: Extract common logic to reduce duplication
            SubstraitToCalcite converter = createSchemaAwareConverter(sqlDialect, tableName, tableSchema);
            return deserializeWithConverter(planString, sqlDialect, converter);
            
        } catch (InvalidProtocolBufferException e) {
            throw new SubstraitDeserializationException("Invalid Substrait plan format", e);
        } catch (IllegalArgumentException e) {
            throw new SubstraitDeserializationException("Invalid plan structure: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error during Substrait plan deserialization for table {}", tableName, e);
            throw new SubstraitDeserializationException("Failed to deserialize Substrait plan", e);
        }
    }
    
    public static SqlNode deserializeSubstraitPlan(String planString, SqlDialect sqlDialect) {
        validateInputs(planString, sqlDialect, null, null);
        
        try {
            LOGGER.debug("Deserializing Substrait plan with standard converter");
            
            SubstraitToCalcite converter = createStandardConverter(sqlDialect);
            return deserializeWithConverter(planString, sqlDialect, converter);
            
        } catch (InvalidProtocolBufferException e) {
            throw new SubstraitDeserializationException("Invalid Substrait plan format", e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error during Substrait plan deserialization", e);
            throw new SubstraitDeserializationException("Failed to deserialize Substrait plan", e);
        }
    }
    
    // IMPROVEMENT 4: Extract common deserialization logic
    private static SqlNode deserializeWithConverter(String planString, SqlDialect sqlDialect, 
                                                   SubstraitToCalcite converter) throws Exception {
        ProtoPlanConverter protoPlanConverter = new ProtoPlanConverter();
        
        byte[] planBytes = Base64.getDecoder().decode(planString);
        Plan substraitPlan = Plan.parseFrom(planBytes);
        
        io.substrait.plan.Plan root = protoPlanConverter.from(substraitPlan);
        
        if (root.getRoots().isEmpty()) {
            throw new IllegalArgumentException("Substrait plan contains no root relations");
        }
        
        RelNode node = converter.convert(root.getRoots().get(0).getInput());
        RelToSqlConverter sqlConverter = new RelToSqlConverter(sqlDialect);
        
        return sqlConverter.visitRoot(node).asStatement();
    }
    
    // IMPROVEMENT 5: Factory methods for converters
    private static SubstraitToCalcite createSchemaAwareConverter(SqlDialect sqlDialect, 
                                                               String tableName, 
                                                               Schema tableSchema) {
        // IMPROVEMENT 6: Only use custom converter when actually needed
        if (tableSchema != null && !tableSchema.getFields().isEmpty()) {
            return new CustomSubstraitToCalcite(
                SimpleExtension.loadDefaults(),
                new SqlTypeFactoryImpl(sqlDialect.getTypeSystem()),
                TypeConverter.DEFAULT,
                tableName,
                tableSchema
            );
        } else {
            // Fallback to standard converter if schema is empty/null
            LOGGER.debug("Table schema is empty, using standard converter");
            return createStandardConverter(sqlDialect);
        }
    }
    
    private static SubstraitToCalcite createStandardConverter(SqlDialect sqlDialect) {
        return new SubstraitToCalcite(
            SimpleExtension.loadDefaults(),
            new SqlTypeFactoryImpl(sqlDialect.getTypeSystem())
        );
    }
    
    // IMPROVEMENT 7: Proper input validation
    private static void validateInputs(String planString, SqlDialect sqlDialect, 
                                     String tableName, Schema tableSchema) {
        if (planString == null || planString.trim().isEmpty()) {
            throw new IllegalArgumentException("Substrait plan string cannot be null or empty");
        }
        
        if (sqlDialect == null) {
            throw new IllegalArgumentException("SQL dialect cannot be null");
        }
        
        // Validate base64 format
        try {
            Base64.getDecoder().decode(planString);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 encoded Substrait plan", e);
        }
    }
}

/**
 * IMPROVEMENT 8: Custom exception for better error handling
 */
public class SubstraitDeserializationException extends RuntimeException {
    
    public SubstraitDeserializationException(String message) {
        super(message);
    }
    
    public SubstraitDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * IMPROVEMENT 9: Simplified CustomSubstraitToCalcite (only when needed)
 */
public class OptimizedCustomSubstraitToCalcite extends SubstraitToCalcite {
    
    private final String tableName;
    private final Schema tableSchema;
    
    public OptimizedCustomSubstraitToCalcite(SimpleExtension.ExtensionCollection extensions, 
                                           RelDataTypeFactory typeFactory, 
                                           TypeConverter typeConverter,
                                           String tableName,
                                           Schema tableSchema) {
        super(extensions, typeFactory, typeConverter);
        this.tableName = tableName;
        this.tableSchema = tableSchema;
    }
    
    @Override
    protected CalciteSchema toSchema(Rel rel) {
        // IMPROVEMENT 10: Only override when we have meaningful schema info
        if (tableSchema == null || tableSchema.getFields().isEmpty()) {
            return super.toSchema(rel);
        }
        
        // Custom schema logic only when needed
        return createCustomSchema();
    }
    
    private CalciteSchema createCustomSchema() {
        // Existing custom schema creation logic
        // ... (same as before but with better error handling)
    }
}
