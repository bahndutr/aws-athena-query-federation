/*-
 * #%L
 * athena-example
 * %%
 * Copyright (C) 2019 Amazon Web Services
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

import com.amazonaws.athena.connector.lambda.QueryStatusChecker;
import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockSpiller;
import com.amazonaws.athena.connector.lambda.data.writers.GeneratedRowWriter;
import com.amazonaws.athena.connector.lambda.data.writers.extractors.Extractor;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.predicate.QueryPlan;
import com.amazonaws.athena.connector.lambda.exceptions.AthenaConnectorException;
import com.amazonaws.athena.connector.lambda.handlers.RecordHandler;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsRequest;
import com.amazonaws.athena.connector.substrait.SubstraitRelUtils;
import com.amazonaws.athena.connector.substrait.model.ColumnPredicate;
import com.amazonaws.athena.connector.substrait.model.SubstraitRelModel;
import com.amazonaws.athena.connectors.elasticsearch.qpt.ElasticsearchQueryPassthrough;
import io.substrait.proto.FetchRel;
import io.substrait.proto.Plan;
import org.apache.arrow.util.VisibleForTesting;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.glue.model.ErrorDetails;
import software.amazon.awssdk.services.glue.model.FederationSourceErrorCode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This class is responsible for providing Athena with actual rows level data from your Elasticsearch instance. Athena
 * will call readWithConstraint(...) on this class for each 'Split' you generated in ElasticsearchMetadataHandler.
 */
public class ElasticsearchRecordHandler
        extends RecordHandler
{
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchRecordHandler.class);

    /**
     * used to aid in debugging. Athena will use this name in conjunction with your catalog id
     * to correlate relevant query errors.
     */
    private static final String SOURCE_TYPE = "elasticsearch";

    // Env. variable that indicates whether the service is with Amazon ES Service (true) and thus the domain-
    // names and associated endpoints can be auto-discovered via the AWS ES SDK. Or, the Elasticsearch service
    // is external to Amazon (false), and the domain_mapping environment variable should be used instead.
    private static final String AUTO_DISCOVER_ENDPOINT = "auto_discover_endpoint";

    // Env. variable that holds the query timeout period for the Search queries.
    private static final String QUERY_TIMEOUT_SEARCH = "query_timeout_search";
    // Env. variable that holds the scroll timeout for the Search queries.
    private static final String SCROLL_TIMEOUT = "query_scroll_timeout";

    private final long queryTimeout;
    private final long scrollTimeout;

    // Pagination batch size (100 documents).
    private static final int QUERY_BATCH_SIZE = 100;

    private final AwsRestHighLevelClientFactory clientFactory;
    private final ElasticsearchTypeUtils typeUtils;
    private final ElasticsearchQueryPassthrough queryPassthrough = new ElasticsearchQueryPassthrough();

    public ElasticsearchRecordHandler(Map<String, String> configOptions)
    {
        super(S3Client.create(), SecretsManagerClient.create(),
                AthenaClient.create(), SOURCE_TYPE, configOptions);

        this.typeUtils = new ElasticsearchTypeUtils();
        this.clientFactory = new AwsRestHighLevelClientFactory(configOptions.getOrDefault(AUTO_DISCOVER_ENDPOINT, "").equalsIgnoreCase("true"));
        this.queryTimeout = Long.parseLong(configOptions.getOrDefault(QUERY_TIMEOUT_SEARCH, "720"));
        this.scrollTimeout = Long.parseLong(configOptions.getOrDefault(SCROLL_TIMEOUT, "60"));
    }

    @VisibleForTesting
    protected ElasticsearchRecordHandler(
        S3Client amazonS3,
        SecretsManagerClient secretsManager,
        AthenaClient amazonAthena,
        AwsRestHighLevelClientFactory clientFactory,
        long queryTimeout,
        long scrollTimeout,
        Map<String, String> configOptions)
    {
        super(amazonS3, secretsManager, amazonAthena, SOURCE_TYPE, configOptions);

        this.typeUtils = new ElasticsearchTypeUtils();
        this.clientFactory = clientFactory;
        this.queryTimeout = queryTimeout;
        this.scrollTimeout = scrollTimeout;
    }

    /**
     * Used to read the row data associated with the provided Split.
     *
     * @param spiller A BlockSpiller that should be used to write the row data associated with this Split.
     * The BlockSpiller automatically handles chunking the response, encrypting, and spilling to S3.
     * @param recordsRequest Details of the read request, including:
     * 1. The Split
     * 2. The Catalog, Database, and Table the read request is for.
     * 3. The filtering predicate (if any)
     * 4. The columns required for projection.
     * @param queryStatusChecker A QueryStatusChecker that you can use to stop doing work for a query that has already terminated
     * @throws RuntimeException when an error occurs while attempting to send the query, or the query timed out.
     * @note Avoid writing >10 rows per-call to BlockSpiller.writeRow(...) because this will limit the BlockSpiller's
     * ability to control Block size. The resulting increase in Block size may cause failures and reduced performance.
     */
    @Override
    protected void readWithConstraint(BlockSpiller spiller,
                                      ReadRecordsRequest recordsRequest,
                                      QueryStatusChecker queryStatusChecker)
            throws RuntimeException
    {
        logger.debug("readWithConstraint: enter - catalogName={}, schemaName={}, tableName={}", 
                recordsRequest.getCatalogName(), recordsRequest.getTableName().getSchemaName(), recordsRequest.getTableName().getTableName());
        logger.info("readWithConstraint: processing request for table {}.{}", 
                recordsRequest.getTableName().getSchemaName(), recordsRequest.getTableName().getTableName());
        
        String domain;
        QueryBuilder query;
        String index;
        
        // ---------------------- Substrait Plan extraction ----------------------
        QueryPlan queryPlan = recordsRequest.getConstraints().getQueryPlan();
        Plan plan = null;
        if (queryPlan != null) {
            logger.debug("readWithConstraint: deserializing Substrait plan");
            plan = SubstraitRelUtils.deserializeSubstraitPlan(queryPlan.getSubstraitPlan());
            logger.info("readWithConstraint: Substrait plan found with {} relations", plan.getRelationsCount());
        }
        else {
            logger.debug("readWithConstraint: no Substrait plan found");
        }
        
        // ---------------------- LIMIT pushdown support ----------------------
        Pair<Boolean, Integer> limitPair = getLimit(plan, recordsRequest.getConstraints());
        boolean hasLimit = limitPair.getLeft();
        int limit = limitPair.getRight();
        logger.info("readWithConstraint: limit pushdown - hasLimit={}, limit={}", hasLimit, limit);
        
        if (recordsRequest.getConstraints().isQueryPassThrough()) {
            logger.debug("readWithConstraint: processing query passthrough request");
            Map<String, String> qptArgs = recordsRequest.getConstraints().getQueryPassthroughArguments();
            queryPassthrough.verify(qptArgs);
            domain = qptArgs.get(ElasticsearchQueryPassthrough.SCHEMA);
            index = qptArgs.get(ElasticsearchQueryPassthrough.INDEX);
            query = QueryBuilders.wrapperQuery(qptArgs.get(ElasticsearchQueryPassthrough.QUERY));
            logger.info("readWithConstraint: passthrough - domain={}, index={}", domain, index);
        }
        else {
            domain = recordsRequest.getTableName().getSchemaName();
            index = recordsRequest.getSplit().getProperty(ElasticsearchMetadataHandler.INDEX_KEY);
            logger.debug("readWithConstraint: regular request - domain={}, index={}", domain, index);
            
            // Build query either from Substrait plan or constraints
            Map<String, List<ColumnPredicate>> columnPredicateMap = ElasticsearchQueryUtils.buildFilterPredicatesFromPlan(plan);
            if (!columnPredicateMap.isEmpty()) {
                logger.debug("readWithConstraint: building query from Substrait plan with {} column predicates", columnPredicateMap.size());
                List<String> tableColumns = recordsRequest.getSchema().getFields().stream()
                        .map(field -> field.getName())
                        .collect(java.util.stream.Collectors.toList());
                query = ElasticsearchQueryUtils.makeQueryFromPlan(columnPredicateMap, tableColumns);
            }
            else {
                logger.debug("readWithConstraint: building query from constraints");
                query = ElasticsearchQueryUtils.getQuery(recordsRequest.getConstraints());
            }
        }
        
        String endpoint = recordsRequest.getSplit().getProperty(domain);
        String shard = recordsRequest.getSplit().getProperty(ElasticsearchMetadataHandler.SHARD_KEY);
        String username = recordsRequest.getSplit().getProperty(ElasticsearchMetadataHandler.SECRET_USERNAME);
        String password = recordsRequest.getSplit().getProperty(ElasticsearchMetadataHandler.SECRET_PASSWORD);
        boolean useSecret = StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password);
        
        logger.info("readWithConstraint: connection details - domain={}, index={}, endpoint={}, shard={}, hasCredentials={}", 
                domain, index, endpoint, shard, useSecret);
        logger.info("readWithConstraint: query details - hasLimit={}, limit={}, query={}", 
                hasLimit, hasLimit ? limit : "none", query);
        
        long numRows = 0;
        if (queryStatusChecker.isQueryRunning()) {
            logger.debug("readWithConstraint: query is running, creating Elasticsearch client");
            AwsRestHighLevelClient client = useSecret
                    ? clientFactory.getOrCreateClient(endpoint, username, password)
                    : clientFactory.getOrCreateClient(endpoint);
            try {
                logger.debug("readWithConstraint: creating field extractors");
                GeneratedRowWriter rowWriter = createFieldExtractors(recordsRequest);
                
                // Build search-source with limit pushdown
                int batchSize = hasLimit ? Math.min(limit, QUERY_BATCH_SIZE) : QUERY_BATCH_SIZE;
                logger.debug("readWithConstraint: batch size={}, query timeout={}s, scroll timeout={}s", 
                        batchSize, queryTimeout, scrollTimeout);
                
                SearchSourceBuilder searchSource = new SearchSourceBuilder()
                        .size(batchSize)
                        .timeout(new TimeValue(queryTimeout, TimeUnit.SECONDS))
                        .fetchSource(ElasticsearchQueryUtils.getProjection(recordsRequest.getSchema()))
                        .query(query);
                        
                logger.debug("readWithConstraint: projection fields: {}", 
                        ElasticsearchQueryUtils.getProjection(recordsRequest.getSchema()));
                
                Scroll scroll = new Scroll(TimeValue.timeValueSeconds(this.scrollTimeout));
                SearchRequest searchRequest = new SearchRequest(index)
                        .preference(shard)
                        .scroll(scroll)
                        .source(searchSource.from(0));
                        
                logger.debug("readWithConstraint: executing initial search request");
                SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
//                logger.info("readWithConstraint: initial search response - totalHits={}, hitsInBatch={}, timedOut={}",
                        searchResponse.getHits().getTotalHits().value, 
                        searchResponse.getHits().getHits().length, 
                        searchResponse.isTimedOut());
                
                while (searchResponse.getHits() != null
                        && searchResponse.getHits().getHits() != null
                        && searchResponse.getHits().getHits().length > 0
                        && queryStatusChecker.isQueryRunning()) {
                        
                    logger.debug("readWithConstraint: processing batch with {} hits", searchResponse.getHits().getHits().length);
                    Iterator<SearchHit> finalIterator = searchResponse.getHits().iterator();
                    
                    while (finalIterator.hasNext() && queryStatusChecker.isQueryRunning()) {
                        if (hasLimit && numRows >= limit) {
                            logger.info("readWithConstraint: reached limit of {} rows, exiting scroll iteration", numRows);
                            break;
                        }
                        ++numRows;
                        SearchHit hit = finalIterator.next();
//                        logger.debug("readWithConstraint: processing hit {} from index {}", hit.getId(), hit.getIndex());
                        spiller.writeRows((Block block, int rowNum) ->
                                rowWriter.writeRow(block, rowNum, client.getDocument(hit)) ? 1 : 0);
                    }
                    
                    if (hasLimit && numRows >= limit) {
                        logger.debug("readWithConstraint: limit reached, breaking outer loop");
                        break; // break outer loop
                    }
                    
                    // Scroll to next batch
                    logger.debug("readWithConstraint: scrolling to next batch");
                    SearchScrollRequest scrollRequest = new SearchScrollRequest(searchResponse.getScrollId()).scroll(scroll);
                    searchResponse = client.scroll(scrollRequest, RequestOptions.DEFAULT);
                    
                    if (searchResponse.isTimedOut()) {
                        logger.error("readWithConstraint: scroll request timed out for index {} shard {}", index, shard);
                        throw new AthenaConnectorException("Request for index (" + index + ") " + shard + " timed out.",
                                ErrorDetails.builder()
                                        .errorCode(FederationSourceErrorCode.OPERATION_TIMEOUT_EXCEPTION.toString())
                                        .build());
                    }
                    
                    logger.debug("readWithConstraint: scroll response - hitsInBatch={}, timedOut={}", 
                            searchResponse.getHits().getHits().length, searchResponse.isTimedOut());
                }
                
                // Cleanup scroll
                logger.debug("readWithConstraint: cleaning up scroll");
                ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
                clearScrollRequest.addScrollId(searchResponse.getScrollId());
                client.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
                logger.debug("readWithConstraint: scroll cleanup completed");
            }
            catch (IOException error) {
                logger.error("readWithConstraint: error sending search query: {}", error.getMessage(), error);
                throw new AthenaConnectorException("Error sending search query: " + error.getMessage(),
                        ErrorDetails.builder()
                                .errorCode(FederationSourceErrorCode.INTERNAL_SERVICE_EXCEPTION.toString())
                                .errorMessage(error.getMessage())
                                .build());
            }
        }
        else {
            logger.warn("readWithConstraint: query is not running, skipping data retrieval");
        }
        
        logger.info("readWithConstraint: completed - numRows processed: {}", numRows);
    }

    /**
     * Creates field extractors to aid in extracting values from retrieved documents. Method makeExtractor()
     * is used for creating the extractors for simple data types (e.g. INT, BIGINT, etc...) Complex data types such as
     * LIST and STRUCT, however require the makeFactory() method to create the extractors.
     * @param recordsRequest Details of the read request that include the constraints and list of fields in the schema.
     * @return GeneratedRowWriter which includes all field extractors used for processing of retrieved documents.
     */
    private GeneratedRowWriter createFieldExtractors(ReadRecordsRequest recordsRequest)
    {
        logger.debug("createFieldExtractors: creating extractors for {} fields", recordsRequest.getSchema().getFields().size());
        
        GeneratedRowWriter.RowWriterBuilder builder =
                GeneratedRowWriter.newBuilder(recordsRequest.getConstraints());

        int simpleExtractors = 0;
        int complexExtractors = 0;
        
        for (Field field : recordsRequest.getSchema().getFields()) {
            logger.debug("createFieldExtractors: processing field {} of type {}", field.getName(), field.getType());
            
            Extractor extractor = typeUtils.makeExtractor(field);
            if (extractor != null) {
                // Simple data types (e.g. INT, BIGINT, etc...)
                builder.withExtractor(field.getName(), extractor);
                simpleExtractors++;
                logger.debug("createFieldExtractors: added simple extractor for field {}", field.getName());
            }
            else {
                // Complex data types (e.g. LIST, STRUCT)
                builder.withFieldWriterFactory(field.getName(), typeUtils.makeFactory(field));
                complexExtractors++;
                logger.debug("createFieldExtractors: added complex factory for field {}", field.getName());
            }
        }

        logger.info("createFieldExtractors: created {} simple extractors and {} complex extractors", 
                simpleExtractors, complexExtractors);
        return builder.build();
    }

    /**
     * @return value used for pagination batch size.
     */
    @VisibleForTesting
    protected int getQueryBatchSize()
    {
        return QUERY_BATCH_SIZE;
    }

    Pair<Boolean, Integer> getLimit(Plan plan, Constraints constraints)
    {
        logger.debug("getLimit: evaluating limit pushdown - hasPlan={}, hasConstraintsLimit={}", 
                plan != null, constraints.hasLimit());
        
        SubstraitRelModel substraitRelModel = null;
        boolean useQueryPlan = false;
        if (plan != null) {
            logger.debug("getLimit: building Substrait relation model");
            substraitRelModel = SubstraitRelModel.buildSubstraitRelModel(
                    plan.getRelations(0).getRoot().getInput());
            useQueryPlan = true;
        }
        
        if (canApplyLimit(constraints, substraitRelModel, useQueryPlan)) {
            if (useQueryPlan) {
                int limit = getLimit(substraitRelModel);
                logger.info("getLimit: applying limit from Substrait plan: {}", limit);
                return Pair.of(true, limit);
            }
            else {
                int limit = (int) constraints.getLimit();
                logger.info("getLimit: applying limit from constraints: {}", limit);
                return Pair.of(true, limit);
            }
        }
        
        logger.debug("getLimit: limit pushdown not applicable");
        return Pair.of(false, -1);
    }

    private boolean canApplyLimit(Constraints constraints,
                                  SubstraitRelModel substraitRelModel,
                                  boolean useQueryPlan)
    {
        if (useQueryPlan) {
            // Allow LIMIT only if FetchRel is present and no SortRel
            if (substraitRelModel.getSortRel() == null && substraitRelModel.getFetchRel() != null) {
                int limit = getLimit(substraitRelModel);
                return limit > 0;
            }
            return false;
        }
        // For constraints, don’t apply if order-by exists
        return constraints.hasLimit() && !constraints.hasNonEmptyOrderByClause();
    }

    private int getLimit(SubstraitRelModel substraitRelModel)
    {
        FetchRel fetchRel = substraitRelModel.getFetchRel();
        int limit = (int) fetchRel.getCount();
        logger.debug("getLimit: extracted limit from FetchRel: {}", limit);
        return limit;
    }
}
