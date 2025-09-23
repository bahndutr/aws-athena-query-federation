/*-
 * #%L
 * athena-oracle
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
package com.amazonaws.athena.connectors.oracle;

import com.amazonaws.athena.connector.lambda.domain.Split;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connectors.jdbc.manager.FederationExpressionParser;
import com.amazonaws.athena.connectors.jdbc.manager.JdbcSplitQueryBuilder;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Extends {@link JdbcSplitQueryBuilder} and implements ORACLE specific SQL clauses for split.
 *
 * Oracle provides named partitions which can be used in a FROM clause.
 */
public class OracleQueryStringBuilder
        extends JdbcSplitQueryBuilder
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OracleQueryStringBuilder.class);
    public OracleQueryStringBuilder(final String quoteCharacter, final FederationExpressionParser federationExpressionParser)
    {
        super(quoteCharacter, federationExpressionParser);
    }

    @Override
    protected String getFromClauseWithSplit(String catalog, String schema, String table, Split split)
    {
        LOGGER.info("=== ORACLE FROM CLAUSE GENERATION ===");
        LOGGER.info("Building FROM clause for table: {}.{}.{}", catalog, schema, table);
        
        StringBuilder tableName = new StringBuilder();
        if (!Strings.isNullOrEmpty(catalog)) {
            tableName.append(quote(catalog)).append('.');
        }
        if (!Strings.isNullOrEmpty(schema)) {
            tableName.append(quote(schema)).append('.');
        }
        tableName.append(quote(table));

        String partitionName = split.getProperty(OracleMetadataHandler.BLOCK_PARTITION_COLUMN_NAME);
        LOGGER.info("Partition name from split: {}", partitionName);

        if (OracleMetadataHandler.ALL_PARTITIONS.equals(partitionName)) {
            // No partitions
            String fromClause = String.format(" FROM %s ", tableName);
            LOGGER.info("Oracle FROM clause (no partitions): {}", fromClause);
            return fromClause;
        }

        Set<String> partitionVals = split.getProperties().keySet();
        String partValue = split.getProperty(partitionVals.iterator().next());
        String fromClause = String.format(" FROM %s ", tableName + " " + "PARTITION " + "(" + partValue + ")");
        LOGGER.info("Oracle FROM clause (with partition): {}", fromClause);
        return fromClause;
    }

    @Override
    protected List<String> getPartitionWhereClauses(final Split split)
    {
        return Collections.emptyList();
    }

    //Returning empty string as Oracle does not support LIMIT clause
    @Override
    protected String appendLimitOffset(Split split, Constraints constraints)
    {
        return String.format(" FETCH FIRST %d ROWS ONLY ", constraints.getLimit());
    }
}
