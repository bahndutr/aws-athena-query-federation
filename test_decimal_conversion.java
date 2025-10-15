import java.math.BigDecimal;
import java.util.*;
import org.bson.Document;

// Simple test to verify decimal conversion
public class TestDecimalConversion {
    
    private static Object convert(Object value) {
        if (value instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) value).doubleValue();
        }
        return value;
    }
    
    public static void main(String[] args) {
        // Test BigDecimal conversion
        BigDecimal decimal = new BigDecimal("315002.733");
        Object converted = convert(decimal);
        
        System.out.println("Original: " + decimal + " (type: " + decimal.getClass().getSimpleName() + ")");
        System.out.println("Converted: " + converted + " (type: " + converted.getClass().getSimpleName() + ")");
        
        // Test MongoDB Document creation
        Document doc = new Document("$gt", converted);
        System.out.println("MongoDB Document: " + doc.toJson());
        
        // Expected: {"$gt": 315002.733}
        // Not: {"$gt": {"$numberDecimal": "315002.733"}}
    }
}
