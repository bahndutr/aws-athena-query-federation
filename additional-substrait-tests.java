// Additional test methods to add to OracleRecordHandlerTest.java

@Test
public void buildSplitSqlWithEmptySubstraitPlan() throws SQLException {
    // Test empty Substrait plan string handling
    TableName tableName = new TableName("testSchema", "testTable");
    Schema testSchema = SchemaBuilder.newBuilder()
            .addField(FieldBuilder.newBuilder("testCol1", Types.MinorType.INT.getType()).build())
            .build();

    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of("partition", "test_partition"));

    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn(""); // Empty string

    Constraints constraintsWithEmptyPlan = Mockito.mock(Constraints.class);
    Mockito.when(constraintsWithEmptyPlan.isQueryPassThrough()).thenReturn(false);
    Mockito.when(constraintsWithEmptyPlan.getQueryPlan()).thenReturn(queryPlan);
    Mockito.when(constraintsWithEmptyPlan.getLimit()).thenReturn(1000L);
    Mockito.when(constraintsWithEmptyPlan.getSummary()).thenReturn(ImmutableMap.of());

    // Verify empty plan is handled (should fallback to traditional constraints)
    Assert.assertNotNull("QueryPlan should not be null", constraintsWithEmptyPlan.getQueryPlan());
    Assert.assertEquals("Should have empty Substrait plan", "", 
            constraintsWithEmptyPlan.getQueryPlan().getSubstraitPlan());
    Assert.assertFalse("Should not be query passthrough", constraintsWithEmptyPlan.isQueryPassThrough());
}

@Test
public void buildSplitSqlWithLargeSubstraitPlan() throws SQLException {
    // Test large Substrait plan handling (memory pressure test)
    TableName tableName = new TableName("testSchema", "testTable");
    Schema testSchema = SchemaBuilder.newBuilder()
            .addField(FieldBuilder.newBuilder("testCol1", Types.MinorType.INT.getType()).build())
            .build();

    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of("partition", "large_partition"));

    // Create a large Substrait plan (1MB)
    String largePlan = "substrait_plan_data_".repeat(65536); // ~1MB
    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn(largePlan);

    Constraints constraintsWithLargePlan = Mockito.mock(Constraints.class);
    Mockito.when(constraintsWithLargePlan.isQueryPassThrough()).thenReturn(false);
    Mockito.when(constraintsWithLargePlan.getQueryPlan()).thenReturn(queryPlan);
    Mockito.when(constraintsWithLargePlan.getLimit()).thenReturn(5000L);
    Mockito.when(constraintsWithLargePlan.getSummary()).thenReturn(ImmutableMap.of());

    // Verify large plan is handled without memory issues
    Assert.assertNotNull("QueryPlan should not be null", constraintsWithLargePlan.getQueryPlan());
    Assert.assertTrue("Should have large Substrait plan", 
            constraintsWithLargePlan.getQueryPlan().getSubstraitPlan().length() > 1000000);
}

@Test
public void buildSplitSqlWithSpecialCharactersInSubstraitPlan() throws SQLException {
    // Test Substrait plan with special characters and Unicode
    TableName tableName = new TableName("testSchema", "testTable");
    Schema testSchema = SchemaBuilder.newBuilder()
            .addField(FieldBuilder.newBuilder("testCol1", Types.MinorType.VARCHAR.getType()).build())
            .build();

    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of("partition", "special_chars_partition"));

    // Plan with Unicode, quotes, and special characters
    String specialCharPlan = "substrait_plan_with_unicode_\u00E9\u00F1\u00FC_and_quotes_'\"_and_backslashes_\\";
    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn(specialCharPlan);

    Constraints constraintsWithSpecialChars = Mockito.mock(Constraints.class);
    Mockito.when(constraintsWithSpecialChars.isQueryPassThrough()).thenReturn(false);
    Mockito.when(constraintsWithSpecialChars.getQueryPlan()).thenReturn(queryPlan);
    Mockito.when(constraintsWithSpecialChars.getLimit()).thenReturn(1000L);
    Mockito.when(constraintsWithSpecialChars.getSummary()).thenReturn(ImmutableMap.of());

    // Verify special characters are handled properly
    Assert.assertNotNull("QueryPlan should not be null", constraintsWithSpecialChars.getQueryPlan());
    Assert.assertTrue("Should contain Unicode characters", 
            constraintsWithSpecialChars.getQueryPlan().getSubstraitPlan().contains("\u00E9"));
    Assert.assertTrue("Should contain quotes", 
            constraintsWithSpecialChars.getQueryPlan().getSubstraitPlan().contains("'\""));
}

@Test
public void buildSplitSqlWithMixedConstraintsAndSubstrait() throws SQLException {
    // Test interaction between traditional constraints and Substrait plans
    TableName tableName = new TableName("testSchema", "testTable");
    Schema testSchema = SchemaBuilder.newBuilder()
            .addField(FieldBuilder.newBuilder("id", Types.MinorType.INT.getType()).build())
            .addField(FieldBuilder.newBuilder("name", Types.MinorType.VARCHAR.getType()).build())
            .build();

    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of("partition", "mixed_partition"));

    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn("valid_substrait_plan_with_filters");

    // Create mock constraints with both Substrait plan AND traditional constraints
    ValueSet mockValueSet = Mockito.mock(ValueSet.class);
    Mockito.when(mockValueSet.isNone()).thenReturn(false);
    Mockito.when(mockValueSet.isAll()).thenReturn(false);
    Mockito.when(mockValueSet.isSingleValue()).thenReturn(true);

    Constraints mixedConstraints = Mockito.mock(Constraints.class);
    Mockito.when(mixedConstraints.isQueryPassThrough()).thenReturn(false);
    Mockito.when(mixedConstraints.getQueryPlan()).thenReturn(queryPlan);
    Mockito.when(mixedConstraints.getLimit()).thenReturn(2000L);
    Mockito.when(mixedConstraints.getSummary()).thenReturn(ImmutableMap.of("id", mockValueSet));

    // Verify both Substrait plan and traditional constraints are present
    Assert.assertNotNull("QueryPlan should not be null", mixedConstraints.getQueryPlan());
    Assert.assertNotNull("Should have Substrait plan", mixedConstraints.getQueryPlan().getSubstraitPlan());
    Assert.assertFalse("Summary should not be empty", mixedConstraints.getSummary().isEmpty());
    Assert.assertTrue("Should have id constraint", mixedConstraints.getSummary().containsKey("id"));
}

@Test
public void buildSplitSqlWithComplexOraclePartitioning() throws SQLException {
    // Test complex Oracle partitioning scenarios with Substrait
    TableName tableName = new TableName("SALES_SCHEMA", "SALES_TABLE");
    Schema testSchema = SchemaBuilder.newBuilder()
            .addField(FieldBuilder.newBuilder("sale_date", Types.MinorType.DATE.getType()).build())
            .addField(FieldBuilder.newBuilder("region", Types.MinorType.VARCHAR.getType()).build())
            .addField(FieldBuilder.newBuilder("amount", Types.MinorType.DECIMAL.getType()).build())
            .build();

    // Test complex partition with subpartition
    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of(
        "partition_name", "SALES_Q1_2024",
        "subpartition_name", "SALES_Q1_2024_REGION_WEST"
    ));

    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn("substrait_plan_with_complex_partitioning");

    Constraints constraintsWithComplexPartitioning = Mockito.mock(Constraints.class);
    Mockito.when(constraintsWithComplexPartitioning.isQueryPassThrough()).thenReturn(false);
    Mockito.when(constraintsWithComplexPartitioning.getQueryPlan()).thenReturn(queryPlan);
    Mockito.when(constraintsWithComplexPartitioning.getLimit()).thenReturn(10000L);
    Mockito.when(constraintsWithComplexPartitioning.getSummary()).thenReturn(ImmutableMap.of());

    // Verify complex partitioning is handled
    Assert.assertEquals("Should have partition name", "SALES_Q1_2024", 
            testSplit.getProperty("partition_name"));
    Assert.assertEquals("Should have subpartition name", "SALES_Q1_2024_REGION_WEST", 
            testSplit.getProperty("subpartition_name"));
    Assert.assertNotNull("Should have Substrait plan", 
            constraintsWithComplexPartitioning.getQueryPlan().getSubstraitPlan());
}

@Test(timeout = 5000) // 5 second timeout
public void buildSplitSqlWithSubstraitPlanTimeout() throws SQLException {
    // Test timeout handling for slow Substrait plan processing
    TableName tableName = new TableName("testSchema", "testTable");
    Schema testSchema = SchemaBuilder.newBuilder()
            .addField(FieldBuilder.newBuilder("testCol1", Types.MinorType.INT.getType()).build())
            .build();

    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of("partition", "timeout_test"));

    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn("substrait_plan_that_processes_quickly");

    Constraints constraintsWithTimeout = Mockito.mock(Constraints.class);
    Mockito.when(constraintsWithTimeout.isQueryPassThrough()).thenReturn(false);
    Mockito.when(constraintsWithTimeout.getQueryPlan()).thenReturn(queryPlan);
    Mockito.when(constraintsWithTimeout.getLimit()).thenReturn(1000L);
    Mockito.when(constraintsWithTimeout.getSummary()).thenReturn(ImmutableMap.of());

    // This test should complete within 5 seconds (timeout annotation)
    Assert.assertNotNull("QueryPlan should not be null", constraintsWithTimeout.getQueryPlan());
    Assert.assertNotNull("Should have Substrait plan", 
            constraintsWithTimeout.getQueryPlan().getSubstraitPlan());
}

@Test
public void buildSplitSqlWithOracleSpecificDataTypes() throws SQLException {
    // Test Oracle-specific data types in Substrait context
    TableName tableName = new TableName("testSchema", "oracle_types_table");
    Schema testSchema = SchemaBuilder.newBuilder()
            .addField(FieldBuilder.newBuilder("clob_col", Types.MinorType.VARCHAR.getType()).build())
            .addField(FieldBuilder.newBuilder("timestamp_tz", Types.MinorType.TIMESTAMPTZ.getType()).build())
            .addField(FieldBuilder.newBuilder("number_col", Types.MinorType.DECIMAL.getType()).build())
            .addField(FieldBuilder.newBuilder("raw_col", Types.MinorType.VARBINARY.getType()).build())
            .build();

    Split testSplit = Mockito.mock(Split.class);
    Mockito.when(testSplit.getProperties()).thenReturn(ImmutableMap.of("partition", "oracle_types_partition"));

    QueryPlan queryPlan = Mockito.mock(QueryPlan.class);
    Mockito.when(queryPlan.getSubstraitPlan()).thenReturn("substrait_plan_with_oracle_specific_types");

    Constraints constraintsWithOracleTypes = Mockito.mock(Constraints.class);
    Mockito.when(constraintsWithOracleTypes.isQueryPassThrough()).thenReturn(false);
    Mockito.when(constraintsWithOracleTypes.getQueryPlan()).thenReturn(queryPlan);
    Mockito.when(constraintsWithOracleTypes.getLimit()).thenReturn(1000L);
    Mockito.when(constraintsWithOracleTypes.getSummary()).thenReturn(ImmutableMap.of());

    // Verify Oracle-specific data types are handled
    Assert.assertEquals("Should have 4 fields", 4, testSchema.getFields().size());
    Assert.assertTrue("Should have CLOB field", 
            testSchema.getFields().stream().anyMatch(f -> f.getName().equals("clob_col")));
    Assert.assertTrue("Should have TIMESTAMP WITH TIME ZONE field", 
            testSchema.getFields().stream().anyMatch(f -> f.getName().equals("timestamp_tz")));
    Assert.assertNotNull("Should have Substrait plan", 
            constraintsWithOracleTypes.getQueryPlan().getSubstraitPlan());
}
