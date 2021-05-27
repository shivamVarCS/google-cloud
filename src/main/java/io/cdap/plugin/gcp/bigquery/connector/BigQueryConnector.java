/*
 *
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.gcp.bigquery.connector;

import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.connector.BrowseDetail;
import io.cdap.cdap.etl.api.connector.BrowseEntity;
import io.cdap.cdap.etl.api.connector.BrowseRequest;
import io.cdap.cdap.etl.api.connector.Connector;
import io.cdap.cdap.etl.api.connector.ConnectorSpec;
import io.cdap.cdap.etl.api.connector.ConnectorSpecRequest;
import io.cdap.cdap.etl.api.connector.DirectConnector;
import io.cdap.cdap.etl.api.connector.SampleRequest;
import io.cdap.cdap.etl.api.validation.ValidationException;
import io.cdap.plugin.gcp.bigquery.source.BigQuerySourceConfig;
import io.cdap.plugin.gcp.bigquery.util.BigQueryDataParser;
import io.cdap.plugin.gcp.common.GCPUtils;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * BigQuery Connector Plugin
 */
@Plugin(type = Connector.PLUGIN_TYPE)
@Name(BigQueryConnector.NAME)
@Description("This connector creates connections to BigQuery, browses BigQuery datasets and tables, sample BigQuery " +
  "tables. BigQuery is Google's serverless, highly scalable, enterprise data warehouse.")
public final class BigQueryConnector implements DirectConnector {
  public static final String NAME = "BigQuery";
  public static final String ENTITY_TYPE_DATASET = "dataset";
  public static final String ENTITY_TYPE_TABLE = "table";
  private static final int ERROR_CODE_NOT_FOUND = 404;
  private BigQueryConnectorConfig config;

  BigQueryConnector(BigQueryConnectorConfig config) {
    this.config = config;
  }

  @Override
  public List<StructuredRecord> sample(SampleRequest sampleRequest) throws IOException {
    BigQueryPath path = new BigQueryPath(sampleRequest.getPath());
    String dataset = path.getDataset();
    String table = path.getTable();
    if (table == null) {
      throw new IllegalArgumentException("Path should contain both dataset and table name.");
    }
    return getTableData(getBigQuery(), dataset, table, sampleRequest.getLimit());
  }

  @Override
  public void test(FailureCollector failureCollector) throws ValidationException {
    // Should not happen
    if (config == null) {
      failureCollector.addFailure("Connector config is null!", "Please instantiate connector with a valid config.");
      return;
    }
    // validate project ID
    String project = config.tryGetProject();
    if (project == null) {
      failureCollector
        .addFailure("Could not detect Google Cloud project id from the environment.", "Please specify a project id.");
    }

    GoogleCredentials credentials = null;
    //validate service account
    if (config.isServiceAccountJson() || config.getServiceAccountFilePath() != null) {
      try {
        credentials =
          GCPUtils.loadServiceAccountCredentials(config.getServiceAccount(), config.isServiceAccountFilePath());
      } catch (Exception e) {
        failureCollector.addFailure(String.format("Service account key provided is not valid: %s.", e.getMessage()),
          "Please provide a valid service account key.");
      }
    }
    // if either project or credentials cannot be loaded , no need to continue
    if (!failureCollector.getValidationFailures().isEmpty()) {
      return;
    }

    try {
      BigQuery bigQuery = GCPUtils.getBigQuery(config.getDatasetProject(), credentials);
      bigQuery.listDatasets(BigQuery.DatasetListOption.pageSize(1));
    } catch (Exception e) {
      failureCollector.addFailure(String.format("Could not connect to BigQuery: %s", e.getMessage()),
        "Please specify correct connection properties.");
    }
  }


  @Override
  public BrowseDetail browse(BrowseRequest browseRequest) throws IOException {
    BigQueryPath path = new BigQueryPath(browseRequest.getPath());

    BigQuery bigQuery = getBigQuery();
    if (path.isRoot()) {
      // browse project to list all datasets
      return listDatasets(bigQuery, browseRequest.getLimit());
    }
    String dataset = path.getDataset();
    String table = path.getTable();
    if (table == null) {
      return listTables(bigQuery, dataset, browseRequest.getLimit());
    }
    return getTable(bigQuery, dataset, table);
  }

  private BrowseDetail getTable(BigQuery bigQuery, String dataset, String table) {
    Table result = bigQuery.getTable(TableId.of(dataset, table));
    if (result == null) {
      throw new IllegalArgumentException(String.format("Cannot find table: %s.%s.", dataset, table));
    }
    return BrowseDetail.builder()
      .addEntity(BrowseEntity.builder(table, "/" + dataset + "/" + table, ENTITY_TYPE_TABLE).canSample(true).build())
      .setTotalCount(1).build();
  }

  private BrowseDetail listTables(BigQuery bigQuery, String dataset, @Nullable Integer limit) {
    int countLimit = limit == null ? Integer.MAX_VALUE : limit;
    int count = 0;
    BrowseDetail.Builder browseDetailBuilder = BrowseDetail.builder();
    DatasetId datasetId = DatasetId.of(dataset);
    Page<Table> tablePage = null;
    try {
      tablePage = bigQuery.listTables(datasetId);
    } catch (BigQueryException e) {
      if (e.getCode() == ERROR_CODE_NOT_FOUND) {
        throw new IllegalArgumentException(String.format("Cannot find dataset: %s.", dataset), e);
      }
      throw e;
    }
    String parentPath = "/" + dataset + "/";
    for (Table table : tablePage.iterateAll()) {
      String name = table.getTableId().getTable();
      browseDetailBuilder
        .addEntity(BrowseEntity.builder(name, parentPath + name, ENTITY_TYPE_TABLE).canSample(true).build());
      count++;
      if (count == countLimit) {
        break;
      }
    }
    return browseDetailBuilder.setTotalCount(count).build();
  }

  private BrowseDetail listDatasets(BigQuery bigQuery, Integer limit) {
    Page<Dataset> datasetPage = bigQuery.listDatasets(BigQuery.DatasetListOption.all());
    int countLimit = limit == null ? Integer.MAX_VALUE : limit;
    int count = 0;
    BrowseDetail.Builder browseDetailBuilder = BrowseDetail.builder();
    for (Dataset dataset : datasetPage.iterateAll()) {
      String name = dataset.getDatasetId().getDataset();
      browseDetailBuilder
        .addEntity(BrowseEntity.builder(name, "/" + name, ENTITY_TYPE_DATASET).canBrowse(true).build());
      count++;
      if (count == countLimit) {
        break;
      }
    }
    return browseDetailBuilder.setTotalCount(count).build();
  }

  private BigQuery getBigQuery() throws IOException {
    GoogleCredentials credentials = null;
    //validate service account
    if (config.isServiceAccountJson() || config.getServiceAccountFilePath() != null) {
      credentials =
        GCPUtils.loadServiceAccountCredentials(config.getServiceAccount(), config.isServiceAccountFilePath());
    }
    return GCPUtils.getBigQuery(config.getDatasetProject(), credentials);
  }

  private List<StructuredRecord> getTableData(BigQuery bigQuery, String dataset, String table, int limit)
    throws IOException {
    String query =
      String.format("SELECT * FROM `%s.%s.%s` LIMIT %d", config.getDatasetProject(), dataset, table, limit);
    QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
    String id = UUID.randomUUID().toString();
    JobId jobId = JobId.of(id);
    Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
    // Wait for the job to finish
    try {
      queryJob = queryJob.waitFor();
    } catch (InterruptedException e) {
      throw new IOException(String.format("Query job %s interrupted.", id), e);
    }

    // check for errors
    if (queryJob == null) {
      throw new IOException(String.format("Job %s no longer exists.", id));
    } else if (queryJob.getStatus().getError() != null) {
      throw new IOException(String.format("Failed to query table : %s", queryJob.getStatus().getError().toString()));
    }

    // Get the results
    TableResult result = null;
    try {
      result = queryJob.getQueryResults();
    } catch (InterruptedException e) {
      throw new IOException("Query results interrupted.", e);
    }
    return BigQueryDataParser.parse(result);
  }


  @Override
  public ConnectorSpec generateSpec(ConnectorSpecRequest connectorSpecRequest) {
    BigQueryPath path = new BigQueryPath(connectorSpecRequest.getPath());
    String dataset = path.getDataset();
    String table = path.getTable();
    if (table == null) {
      throw new IllegalArgumentException("Path should contain both dataset and table name.");
    }
    return ConnectorSpec.builder().addProperty(BigQuerySourceConfig.NAME_DATASET, dataset)
      .addProperty(BigQuerySourceConfig.NAME_TABLE, table).build();
  }
}
