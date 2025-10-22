/*-
 * #%L
 * athena-elasticsearch
 * %%
 * Copyright (C) 2019 - 2020 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.elasticsearch;

import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.predicate.EquatableValueSet;
import com.amazonaws.athena.connector.lambda.domain.predicate.Range;
import com.amazonaws.athena.connector.lambda.domain.predicate.ValueSet;
import com.amazonaws.athena.connector.substrait.SubstraitFunctionParser;
import com.amazonaws.athena.connector.substrait.SubstraitMetadataParser;
import com.amazonaws.athena.connector.substrait.model.ColumnPredicate;
import com.amazonaws.athena.connector.substrait.model.SubstraitOperator;
import com.amazonaws.athena.connector.substrait.model.SubstraitRelModel;
import io.substrait.proto.Plan;
import io.substrait.proto.SimpleExtensionDeclaration;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class has interfaces used for the generation of projections and predicates used for document search queries.
 */
class ElasticsearchQueryUtils
{
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchQueryUtils.class);

    // Predicate conjunctions.
    private static final String AND_OPER = " AND ";
    private static final String OR_OPER = " OR ";
    private static final String NOT_OPER = "NOT ";
    private static final String RANGE_OPER = " TO ";
    private static final String LOWER_UNBOUNDED_RANGE = "[*";
    private static final String LOWER_INCLUSIVE_RANGE = "[";
    private static final String LOWER_EXCLUSIVE_RANGE = "{";
    private static final String UPPER_UNBOUNDED_RANGE = "*]";
    private static final String UPPER_INCLUSIVE_RANGE = "]";
    private static final String UPPER_EXCLUSIVE_RANGE = "}";
    private static final String EMPTY_PREDICATE = "";

    // Existence predicates.
    private static final String existsPredicate(boolean exists, String fieldName)
    {
        if (exists) {
            // (_exists:field)
            return "(_exists_:" + fieldName + ")";
        }
        else {
            // (NOT _exists_:field)
            return "(" + NOT_OPER + "_exists_:" + fieldName + ")";
        }
    }

    private ElasticsearchQueryUtils() {}

    /**
     * Parses Substrait plan and extracts filter predicates per column.
     */
    public static Map<String, List<ColumnPredicate>> buildFilterPredicatesFromPlan(Plan plan)
    {
        logger.debug("buildFilterPredicatesFromPlan: processing Substrait plan");
        
        if (plan == null || plan.getRelationsList().isEmpty()) {
            logger.debug("buildFilterPredicatesFromPlan: plan is null or empty, returning empty map");
            return new HashMap<>();
        }
        
        logger.debug("buildFilterPredicatesFromPlan: building Substrait relation model");
        SubstraitRelModel substraitRelModel = SubstraitRelModel.buildSubstraitRelModel(
                plan.getRelations(0).getRoot().getInput());
                
        if (substraitRelModel.getFilterRel() == null) {
            logger.debug("buildFilterPredicatesFromPlan: no FilterRel found, returning empty map");
            return new HashMap<>();
        }
        
        logger.debug("buildFilterPredicatesFromPlan: extracting column predicates from FilterRel");
        List<SimpleExtensionDeclaration> extensionDeclarations = plan.getExtensionsList();
        List<String> tableColumns = SubstraitMetadataParser.getTableColumns(substraitRelModel);
        
        Map<String, List<ColumnPredicate>> predicates = SubstraitFunctionParser.getColumnPredicatesMap(
                extensionDeclarations,
                substraitRelModel.getFilterRel().getCondition(),
                tableColumns);
                
        logger.info("buildFilterPredicatesFromPlan: extracted {} column predicates", predicates.size());
        logger.debug("buildFilterPredicatesFromPlan: predicate columns: {}", predicates.keySet());
        
        return predicates;
    }
    /**
     * Converts Substrait column predicates to an Elasticsearch query string query.
     */
    public static QueryBuilder makeQueryFromPlan(Map<String, List<ColumnPredicate>> predicates, List<String> tableColumns)
    {
        logger.debug("makeQueryFromPlan: converting {} column predicates to Elasticsearch query", 
                predicates != null ? predicates.size() : 0);
        
        if (predicates == null || predicates.isEmpty()) {
            logger.info("makeQueryFromPlan: no predicates formed from Substrait plan, using match_all query");
            return QueryBuilders.matchAllQuery();
        }
        
        List<String> predicateStrings = new ArrayList<>();
        for (Map.Entry<String, List<ColumnPredicate>> entry : predicates.entrySet()) {
            logger.debug("makeQueryFromPlan: processing column {} with {} predicates", 
                    entry.getKey(), entry.getValue().size());
            String clause = convertColumnPredicatesToString(entry.getKey(), entry.getValue(), tableColumns);
            if (!clause.isEmpty()) {
                predicateStrings.add(clause);
                logger.debug("makeQueryFromPlan: added clause for column {}: {}", entry.getKey(), clause);
            }
        }
        
        if (predicateStrings.isEmpty()) {
            logger.debug("makeQueryFromPlan: no valid predicates found, using match_all query");
            return QueryBuilders.matchAllQuery();
        }
        
        // Join predicates with AND
        String combined = Strings.collectionToDelimitedString(predicateStrings, AND_OPER);
        logger.info("makeQueryFromPlan: formed QueryPlan predicates: {}", combined);
        return QueryBuilders.queryStringQuery(combined).queryName(combined);
    }

    /**
     * Creates a projection (using the schema) on which fields should be included in the search index request. For
     * complex type STRUCT, there is no need to include each individual nested field in the projection. Since the
     * schema contains all nested fields in the STRUCT, only the name of the STRUCT field is added to the projection
     * allowing Elasticsearch to return the entire object including all nested fields.
     * @param schema is the schema containing the requested projection.
     * @return a projection wrapped in a FetchSourceContext object.
     */
    protected static FetchSourceContext getProjection(Schema schema)
    {
        logger.debug("getProjection: creating projection for schema with {} fields", schema.getFields().size());
        
        List<String> includedFields = new ArrayList<>();

        for (Field field : schema.getFields()) {
            includedFields.add(field.getName());
            logger.debug("getProjection: added field to projection: {}", field.getName());
        }

        logger.info("getProjection: included fields: {}", includedFields);

        return new FetchSourceContext(true, Strings.toStringArray(includedFields), Strings.EMPTY_ARRAY);
    }

    /**
     * Given a set of Constraints, create the query that can push predicates into the Elasticsearch data-source.
     * @param constraints is a map containing the constraints used to form the predicate for predicate push-down.
     * @return the query builder that will be injected into the query.
     */
    protected static QueryBuilder getQuery(Constraints constraints)
    {
        logger.debug("getQuery: processing constraints with {} summary entries", constraints.getSummary().size());
        
        Map<String, ValueSet> constraintSummary = constraints.getSummary();
        List<String> predicates = new ArrayList<>();

        constraintSummary.forEach((fieldName, constraint) -> {
            logger.debug("getQuery: processing constraint for field: {}", fieldName);
            String predicate = getPredicate(fieldName, constraint);
            if (!predicate.isEmpty()) {
                // predicate1, predicate2, predicate3...
                predicates.add(predicate);
                logger.debug("getQuery: added predicate for field {}: {}", fieldName, predicate);
            }
        });

        if (predicates.isEmpty()) {
            logger.debug("getQuery: no predicates formed from constraints, using match_all query");
            // No predicates formed.
            logger.info("getQuery: predicates are NOT formed");
            return QueryBuilders.matchAllQuery();
        }

        // predicate1 AND predicate2 AND predicate3...
        String formedPredicates = Strings.collectionToDelimitedString(predicates, AND_OPER);
        logger.info("getQuery: formed predicates: {}", formedPredicates);

        return QueryBuilders.queryStringQuery(formedPredicates).queryName(formedPredicates);
    }

    /**
     * Converts a single field constraint into a predicate to use in an Elasticsearch query.
     * @param fieldName The name of the field for the given ValueSet constraint.
     * @param constraint The constraint to apply to the given field.
     * @return A string describing the constraint for pushing down into Elasticsearch.
     */
    private static String getPredicate(String fieldName, ValueSet constraint)
    {
        if (constraint.isNone()) {
            // (NOT _exists_:field)
            return existsPredicate(false, fieldName);
        }

        if (constraint.isAll()) {
            // (_exists_:field)
            return existsPredicate(true, fieldName);
        }

        List<String> predicateParts = new ArrayList<>();

        if (!constraint.isNullAllowed()) {
            // null value should not be included in set of returned values => Include existence predicate.
            predicateParts.add(existsPredicate(true, fieldName));
        }

        if (constraint instanceof EquatableValueSet) {
            EquatableValueSet equatableValueSet = (EquatableValueSet) constraint;
            List<String> singleValues = new ArrayList<>();
            for (int pos = 0; pos < equatableValueSet.getValueBlock().getRowCount(); pos++) {
                singleValues.add(equatableValueSet.getValue(pos).toString());
            }
            if (equatableValueSet.isWhiteList()) {
                // field:(value1 OR value2 OR value3...)
                predicateParts.add(fieldName + ":(" +
                        Strings.collectionToDelimitedString(singleValues, OR_OPER) + ")");
            }
            else {
                // NOT field:(value1 OR value2 OR value3...)
                predicateParts.add(NOT_OPER + fieldName + ":(" +
                        Strings.collectionToDelimitedString(singleValues, OR_OPER) + ")");
            }
        }
        else {
            String rangedPredicate = getPredicateFromRange(fieldName, constraint);
            if (!rangedPredicate.isEmpty()) {
                predicateParts.add(rangedPredicate);
            }
        }

        return predicateParts.isEmpty() ? EMPTY_PREDICATE : Strings.collectionToDelimitedString(predicateParts, AND_OPER);
    }

    /**
     * Converts a range constraint into a predicate to use in an Elasticsearch query.
     * @param fieldName The name of the field for the given ValueSet constraint.
     * @param constraint The constraint to apply to the given field.
     * @return A string describing the constraint for pushing down into Elasticsearch.
     */
    private static String getPredicateFromRange(String fieldName, ValueSet constraint)
    {
        List<String> singleValues = new ArrayList<>();
        List<String> disjuncts = new ArrayList<>();
        for (Range range : constraint.getRanges().getOrderedRanges()) {
            if (range.isSingleValue()) {
                String singleValue = range.getSingleValue().toString();
                if (range.getType() instanceof ArrowType.Date) {
                    // Wrap a single date in quotes, e.g. my-birthday:("2000-11-11T06:57:44.123")
                    singleValues.add("\"" + singleValue + "\"");
                }
                else {
                    singleValues.add(singleValue);
                }
            }
            else {
                String rangeConjuncts;
                if (range.getLow().isLowerUnbounded()) {
                    rangeConjuncts = LOWER_UNBOUNDED_RANGE;
                }
                else {
                    switch (range.getLow().getBound()) {
                        case EXACTLY:
                            rangeConjuncts = LOWER_INCLUSIVE_RANGE + range.getLow().getValue().toString();
                            break;
                        case ABOVE:
                            rangeConjuncts = LOWER_EXCLUSIVE_RANGE + range.getLow().getValue().toString();
                            break;
                        case BELOW:
                            logger.warn("Low Marker should never use BELOW bound: " + range);
                            continue;
                        default:
                            logger.warn("Unhandled bound: " + range.getLow().getBound());
                            continue;
                    }
                }
                rangeConjuncts += RANGE_OPER;
                if (range.getHigh().isUpperUnbounded()) {
                    rangeConjuncts += UPPER_UNBOUNDED_RANGE;
                }
                else {
                    switch (range.getHigh().getBound()) {
                        case EXACTLY:
                            rangeConjuncts += range.getHigh().getValue().toString() + UPPER_INCLUSIVE_RANGE;
                            break;
                        case BELOW:
                            rangeConjuncts += range.getHigh().getValue().toString() + UPPER_EXCLUSIVE_RANGE;
                            break;
                        case ABOVE:
                            logger.warn("High Marker should never use ABOVE bound: " + range);
                            continue;
                        default:
                            logger.warn("Unhandled bound: " + range.getHigh().getBound());
                            continue;
                    }
                }
                disjuncts.add(rangeConjuncts);
            }
        }

        if (!singleValues.isEmpty()) {
            // value1 OR value2 OR value3...
            disjuncts.add(Strings.collectionToDelimitedString(singleValues, OR_OPER));
        }

        if (disjuncts.isEmpty()) {
            // There are no ranges stored.
            return EMPTY_PREDICATE;
        }

        // field:([value1 TO value2] OR value3 OR value4 OR value5...)
        return fieldName + ":(" + Strings.collectionToDelimitedString(disjuncts, OR_OPER) + ")";
    }

    /**
     * Converts a list of ColumnPredicates into an ES-compatible query string.
     */
    private static String convertColumnPredicatesToString(String column, List<ColumnPredicate> colPreds, List<String> tableColumns)
    {
        logger.info("Converting {} predicates for column '{}'", colPreds.size(), column);
        
        // Find the original field name from schema (case-sensitive match)
        String esFieldName = findOriginalFieldName(column, tableColumns);
        logger.info("Converted Substrait field name '{}' to ES field name '{}'", column, esFieldName);
        
        // Group EQUAL operations for OR logic, others for AND logic
        List<String> equalValues = new ArrayList<>();
        List<String> otherParts = new ArrayList<>();
        
        for (int i = 0; i < colPreds.size(); i++) {
            ColumnPredicate predicate = colPreds.get(i);
            Object value = predicate.getValue();
            SubstraitOperator op = predicate.getOperator();
            
            logger.info("Predicate #{} for column '{}': operator={}, value={}", i + 1, esFieldName, op, value);
            
            // Handle NOR separately due to potential enum compilation issues
            if ("NOR".equals(op.name())) {
                // NOR: NOT (A OR B OR C) = NOT A AND NOT B AND NOT C (De Morgan's law)
                if (value instanceof List) {
                    List<String> norParts = new ArrayList<>();
                    for (Object childObj : (List<?>) value) {
                        if (childObj instanceof ColumnPredicate) {
                            ColumnPredicate child = (ColumnPredicate) childObj;
                            String childFieldName = findOriginalFieldName(child.getColumn(), tableColumns);
                            
                            // Convert each child to its negation
                            String negatedPredicate = convertToNegation(child, childFieldName);
                            if (!negatedPredicate.isEmpty()) {
                                norParts.add(negatedPredicate);
                            }
                        }
                    }
                    if (!norParts.isEmpty()) {
                        // Combine negated predicates with AND
                        String predicatePart = String.join(AND_OPER, norParts);
                        otherParts.add(predicatePart);
                    }
                }
                continue;
            }
            
            // Handle NAND separately due to potential enum compilation issues
            if ("NAND".equals(op.name())) {
                // NAND: NOT (A AND B AND C) = NOT A OR NOT B OR NOT C (De Morgan's law)
                if (value instanceof List) {
                    List<String> nandParts = new ArrayList<>();
                    for (Object childObj : (List<?>) value) {
                        if (childObj instanceof ColumnPredicate) {
                            ColumnPredicate child = (ColumnPredicate) childObj;
                            String childFieldName = findOriginalFieldName(child.getColumn(), tableColumns);
                            
                            // Convert each child to its negation
                            String negatedPredicate = convertToNegation(child, childFieldName);
                            if (!negatedPredicate.isEmpty()) {
                                nandParts.add(negatedPredicate);
                            }
                        }
                    }
                    if (!nandParts.isEmpty()) {
                        // Combine negated predicates with OR
                        String predicatePart = String.join(OR_OPER, nandParts);
                        otherParts.add(predicatePart);
                    }
                }
                continue;
            }
            
            // Handle NOT separately for unary NOT operations
            if ("NOT".equals(op.name())) {
                // NOT is a unary operator - negate the field existence or value
                if (value == null) {
                    // Simple NOT on field existence
                    String predicatePart = existsPredicate(false, esFieldName);
                    otherParts.add(predicatePart);
                } else {
                    // NOT with a value - treat as NOT_EQUAL
                    String predicatePart = existsPredicate(true, esFieldName) + " AND " + esFieldName + ":([* TO " + formatValueForES(value, esFieldName) + "} OR {" + formatValueForES(value, esFieldName) + " TO *])";
                    otherParts.add(predicatePart);
                }
                continue;
            }
            
            String predicatePart;
            switch (op) {
                case EQUAL:
                    equalValues.add(value.toString());
                    break;
                case NOT_EQUAL:
                    predicatePart = existsPredicate(true, esFieldName) + " AND " + esFieldName + ":([* TO " + formatValueForES(value, esFieldName) + "} OR {" + formatValueForES(value, esFieldName) + " TO *])";
                    otherParts.add(predicatePart);
                    break;
                case GREATER_THAN:
                    predicatePart = esFieldName + ":{" + formatValueForES(value, esFieldName) + " TO *}";
                    otherParts.add(predicatePart);
                    break;
                case GREATER_THAN_OR_EQUAL_TO:
                    predicatePart = esFieldName + ":[" + formatValueForES(value, esFieldName) + " TO *]";
                    otherParts.add(predicatePart);
                    break;
                case LESS_THAN:
                    predicatePart = esFieldName + ":{* TO " + formatValueForES(value, esFieldName) + "}";
                    otherParts.add(predicatePart);
                    break;
                case LESS_THAN_OR_EQUAL_TO:
                    predicatePart = esFieldName + ":[* TO " + formatValueForES(value, esFieldName) + "]";
                    otherParts.add(predicatePart);
                    break;
                case IS_NULL:
                    predicatePart = existsPredicate(false, esFieldName);
                    otherParts.add(predicatePart);
                    break;
                case IS_NOT_NULL:
                    predicatePart = existsPredicate(true, esFieldName);
                    otherParts.add(predicatePart);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported operator for ES QueryPlan: " + op);
            }
        }
        
        // Build the final result
        List<String> allParts = new ArrayList<>();
        
        // Add EQUAL values as OR group with existence check
        if (!equalValues.isEmpty()) {
            String valuesString = String.join(OR_OPER, equalValues);
            String equalsPart = existsPredicate(true, esFieldName) + " AND " + esFieldName + ":(" + valuesString + ")";
            allParts.add(equalsPart);
        }
        
        // Add other operations
        allParts.addAll(otherParts);
        
        String result = allParts.isEmpty() ? EMPTY_PREDICATE : Strings.collectionToDelimitedString(allParts, AND_OPER);
        logger.info("Final combined predicate for column '{}': '{}'", esFieldName, result);
        return result;
    }

    /**
     * Finds the original field name from the schema by case-insensitive matching.
     */
    private static String findOriginalFieldName(String substraitFieldName, List<String> tableColumns)
    {
        // First try exact match
        if (tableColumns.contains(substraitFieldName)) {
            return substraitFieldName;
        }
        
        // Then try case-insensitive match
        for (String originalField : tableColumns) {
            if (originalField.equalsIgnoreCase(substraitFieldName)) {
                return originalField;
            }
        }
        
        // If no match found, return the original (fallback)
        return substraitFieldName;
    }

    /**
     * Converts a ColumnPredicate to its negation for NAND operations.
     */
    private static String convertToNegation(ColumnPredicate predicate, String fieldName)
    {
        SubstraitOperator op = predicate.getOperator();
        Object value = predicate.getValue();
        
        switch (op) {
            case EQUAL:
                // NOT (field = value) becomes field != value
                return existsPredicate(true, fieldName) + " AND " + fieldName + ":([* TO " + formatValueForES(value, fieldName) + "} OR {" + formatValueForES(value, fieldName) + " TO *])";
            case NOT_EQUAL:
                // NOT (field != value) becomes field = value
                return existsPredicate(true, fieldName) + " AND " + fieldName + ":(" + value + ")";
            case GREATER_THAN:
                // NOT (field > value) becomes field <= value
                return fieldName + ":[* TO " + formatValueForES(value, fieldName) + "]";
            case GREATER_THAN_OR_EQUAL_TO:
                // NOT (field >= value) becomes field < value
                return fieldName + ":{* TO " + formatValueForES(value, fieldName) + "}";
            case LESS_THAN:
                // NOT (field < value) becomes field >= value
                return fieldName + ":[" + formatValueForES(value, fieldName) + " TO *]";
            case LESS_THAN_OR_EQUAL_TO:
                // NOT (field <= value) becomes field > value
                return fieldName + ":({" + formatValueForES(value, fieldName) + " TO *})";
            case IS_NULL:
                // NOT (field IS NULL) becomes field IS NOT NULL
                return existsPredicate(true, fieldName);
            case IS_NOT_NULL:
                // NOT (field IS NOT NULL) becomes field IS NULL
                return existsPredicate(false, fieldName);
            default:
                return "";
        }
    }

    /**
     * Formats values for Elasticsearch queries, handling timestamp conversion.
     */
    private static String formatValueForES(Object value, String fieldName)
    {
        // Handle timestamp fields - convert microseconds to proper format
        if ("timestamp".equalsIgnoreCase(fieldName) && value instanceof Number) {
            long microseconds = ((Number) value).longValue();
            long milliseconds = microseconds / 1000; // Convert to milliseconds
            String timestamp = java.time.Instant.ofEpochMilli(milliseconds).toString().replace("Z", "");
            // Remove seconds to match traditional format: 2025-09-30T00:00 instead of 2025-09-30T00:00:00
            if (timestamp.endsWith(":00")) {
                timestamp = timestamp.substring(0, timestamp.length() - 3);
            }
            return timestamp;
        }
        return value.toString();
    }
}
