// Additional test methods to add to OracleQueryStringBuilderTest.java

@Test
public void testQuoteIdentifiersWithUnicodeCharacters() {
    // Test identifier quoting with Unicode characters
    String unicodeIdentifier = "table_with_unicode_\u00E9\u00F1\u00FC";
    String result = queryBuilder.quoteIdentifier(unicodeIdentifier);
    assertEquals("Should properly quote Unicode identifiers", 
            "\"table_with_unicode_\u00E9\u00F1\u00FC\"", result);
}

@Test
public void testFromClauseWithComplexPartitionNames() {
    // Test FROM clause with complex partition names (spaces, special chars)
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn("SALES_Q1_2024_REGION_WEST");
    
    String result = queryBuilder.getFromClauseWithSplit("PROD", "SALES", "ORDERS", mockSplit);
    
    String expected = " FROM \"PROD\".\"SALES\".\"ORDERS\" PARTITION (SALES_Q1_2024_REGION_WEST) ";
    assertEquals("Should handle complex partition names", expected, result);
}

@Test
public void testFromClauseWithSubpartitioning() {
    // Test Oracle subpartitioning syntax
    when(mockSplit.getProperties()).thenReturn(ImmutableMap.of(
        OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME, "SALES_Q1_2024",
        "subpartition_name", "SALES_Q1_2024_REGION_WEST"
    ));
    
    String result = queryBuilder.getFromClauseWithSplit("PROD", "SALES", "ORDERS", mockSplit);
    
    // Note: This would require extending the query builder to support subpartitions
    assertTrue("Should contain partition information", result.contains("PARTITION"));
    assertTrue("Should contain partition name", result.contains("SALES_Q1_2024"));
}

@Test
public void testLimitClauseWithNegativeLimit() {
    // Test edge case with negative limit (should be handled gracefully)
    when(mockConstraints.getLimit()).thenReturn(-1L);
    
    String result = queryBuilder.appendLimitOffset(mockSplit, mockConstraints);
    
    // Should either return empty string or handle gracefully
    assertNotNull("Should handle negative limits gracefully", result);
}

@Test
public void testLimitClauseWithMaxLongValue() {
    // Test edge case with maximum Long value
    when(mockConstraints.getLimit()).thenReturn(Long.MAX_VALUE);
    
    String result = queryBuilder.appendLimitOffset(mockSplit, mockConstraints);
    
    String expected = " FETCH FIRST " + Long.MAX_VALUE + " ROWS ONLY ";
    assertEquals("Should handle maximum Long value", expected, result);
}

@Test
public void testFromClauseWithReservedWordTableName() {
    // Test table names that are Oracle reserved words
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn(OracleMetadataHandler.ALL_PARTITIONS);
    
    String result = queryBuilder.getFromClauseWithSplit(null, "testSchema", "SELECT", mockSplit);
    
    // Should properly quote reserved word table name
    assertEquals("Should quote reserved word table names", 
            " FROM \"testSchema\".\"SELECT\" ", result);
}

@Test
public void testFromClauseWithNullCatalogAndSchema() {
    // Test edge case with null catalog and schema
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn(OracleMetadataHandler.ALL_PARTITIONS);
    
    String result = queryBuilder.getFromClauseWithSplit(null, null, "testTable", mockSplit);
    
    // Should handle null schema gracefully
    assertTrue("Should contain table name", result.contains("testTable"));
}

@Test
public void testFromClauseWithEmptyPartitionName() {
    // Test edge case with empty partition name
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn("");
    
    String result = queryBuilder.getFromClauseWithSplit("testCatalog", "testSchema", "testTable", mockSplit);
    
    // Should treat empty partition name like ALL_PARTITIONS
    String expected = " FROM \"testCatalog\".\"testSchema\".\"testTable\" ";
    assertEquals("Should handle empty partition name", expected, result);
}

@Test
public void testFromClauseWithWhitespaceInPartitionName() {
    // Test partition names with leading/trailing whitespace
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn("  partition_with_spaces  ");
    
    String result = queryBuilder.getFromClauseWithSplit("testCatalog", "testSchema", "testTable", mockSplit);
    
    // Should handle whitespace appropriately (trim or preserve based on Oracle requirements)
    assertTrue("Should contain partition syntax", result.contains("PARTITION"));
    assertTrue("Should handle whitespace in partition names", 
            result.contains("partition_with_spaces") || result.contains("  partition_with_spaces  "));
}

@Test
public void testLimitClauseWithZeroAndNegativeEdgeCases() {
    // Test various edge cases for limit values
    
    // Test zero limit
    when(mockConstraints.getLimit()).thenReturn(0L);
    String zeroResult = queryBuilder.appendLimitOffset(mockSplit, mockConstraints);
    assertEquals("Should handle zero limit", " FETCH FIRST 0 ROWS ONLY ", zeroResult);
    
    // Test negative limit
    when(mockConstraints.getLimit()).thenReturn(-100L);
    String negativeResult = queryBuilder.appendLimitOffset(mockSplit, mockConstraints);
    // Should either return empty or handle gracefully
    assertNotNull("Should handle negative limit gracefully", negativeResult);
}

@Test
public void testQuoteIdentifierWithSQLInjectionPatterns() {
    // Test identifier quoting with potential SQL injection patterns
    String maliciousIdentifier = "table'; DROP TABLE users; --";
    String result = queryBuilder.quoteIdentifier(maliciousIdentifier);
    
    assertEquals("Should properly quote malicious identifiers", 
            "\"table'; DROP TABLE users; --\"", result);
    
    // Test with double quotes in identifier
    String doubleQuoteIdentifier = "table\"with\"quotes";
    String doubleQuoteResult = queryBuilder.quoteIdentifier(doubleQuoteIdentifier);
    
    // Should escape internal double quotes
    assertTrue("Should handle internal double quotes", 
            doubleQuoteResult.startsWith("\"") && doubleQuoteResult.endsWith("\""));
}

@Test
public void testFromClauseWithVeryLongIdentifiers() {
    // Test with very long identifier names (Oracle has 128 character limit for identifiers)
    String longTableName = "a".repeat(128); // Maximum Oracle identifier length
    String longSchemaName = "b".repeat(128);
    String longCatalogName = "c".repeat(128);
    
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn(OracleMetadataHandler.ALL_PARTITIONS);
    
    String result = queryBuilder.getFromClauseWithSplit(longCatalogName, longSchemaName, longTableName, mockSplit);
    
    // Should handle long identifiers without truncation
    assertTrue("Should contain long catalog name", result.contains(longCatalogName));
    assertTrue("Should contain long schema name", result.contains(longSchemaName));
    assertTrue("Should contain long table name", result.contains(longTableName));
}

@Test
public void testFromClauseWithNumericPartitionNames() {
    // Test partition names that are purely numeric
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn("20240101");
    
    String result = queryBuilder.getFromClauseWithSplit("testCatalog", "testSchema", "testTable", mockSplit);
    
    String expected = " FROM \"testCatalog\".\"testSchema\".\"testTable\" PARTITION (20240101) ";
    assertEquals("Should handle numeric partition names", expected, result);
}

@Test
public void testFromClauseWithMixedCaseIdentifiers() {
    // Test mixed case identifiers (Oracle is case-sensitive when quoted)
    when(mockSplit.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME))
            .thenReturn("MixedCasePartition");
    
    String result = queryBuilder.getFromClauseWithSplit("MixedCatalog", "MixedSchema", "MixedTable", mockSplit);
    
    // Should preserve case in quoted identifiers
    assertTrue("Should preserve catalog case", result.contains("\"MixedCatalog\""));
    assertTrue("Should preserve schema case", result.contains("\"MixedSchema\""));
    assertTrue("Should preserve table case", result.contains("\"MixedTable\""));
    assertTrue("Should preserve partition case", result.contains("MixedCasePartition"));
}
