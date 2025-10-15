// Simple test to understand the field mapping issue
// Based on the logs, the schema should be:
// Schema<_id: Utf8, id: Int(32, true), is_active: Bool, employee_name: Utf8, job_title: Utf8, ...>

public class DebugFieldMapping {
    public static void main(String[] args) {
        // From the logs, the schema order is:
        String[] schemaFields = {
            "_id",           // index 0
            "id",            // index 1  
            "is_active",     // index 2  <- This should be the boolean field
            "employee_name", // index 3
            "job_title",     // index 4
            "address",       // index 5
            "join_date",     // index 6
            "timestamp_col", // index 7
            "duration",      // index 8
            "salary",        // index 9
            "bonus",         // index 10
            "hash1",         // index 11
            "hash2",         // index 12
            "code",          // index 13
            "debit",         // index 14
            "count_col",     // index 15
            "amount",        // index 16
            "balance",       // index 17
            "rate",          // index 18
            "difference"     // index 19
        };
        
        System.out.println("Expected field mapping:");
        for (int i = 0; i < schemaFields.length; i++) {
            System.out.println("Index " + i + ": " + schemaFields[i]);
        }
        
        System.out.println("\nThe issue: If field index 2 (is_active) is being resolved as 'id' (index 1),");
        System.out.println("then there's an off-by-one error or incorrect field reference in the Substrait plan.");
    }
}
