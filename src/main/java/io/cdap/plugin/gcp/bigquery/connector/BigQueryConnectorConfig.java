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

import com.google.cloud.ServiceOptions;
import com.google.common.base.Strings;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.plugin.PluginConfig;

import javax.annotation.Nullable;

/**
 * Plugin config for BiqueryConnector
 */
public class BigQueryConnectorConfig extends PluginConfig {
  public static final String NAME_PROJECT = "project";
  public static final String NAME_SERVICE_ACCOUNT_TYPE = "serviceAccountType";
  public static final String NAME_SERVICE_ACCOUNT_FILE_PATH = "serviceFilePath";
  public static final String NAME_SERVICE_ACCOUNT_JSON = "serviceAccountJSON";
  public static final String AUTO_DETECT = "auto-detect";
  public static final String SERVICE_ACCOUNT_FILE_PATH = "filePath";
  public static final String SERVICE_ACCOUNT_JSON = "JSON";
  public static final String NAME_DATASET_PROJECT = "datasetProject";

  @Name(NAME_PROJECT)
  @Description("Google Cloud Project ID, which uniquely identifies a project where BigQuery Job will run. " +
    "It can be found on the Dashboard in the Google Cloud Platform Console.")
  @Macro
  @Nullable
  protected String project;

  @Name(NAME_DATASET_PROJECT)
  @Macro
  @Nullable
  @Description("The project the dataset belongs to. This is only required if the dataset is not " +
    "in the same project that the BigQuery job will run in. If no value is given, it will" +
    " default to the configured " + "project ID.")
  private String datasetProject;

  @Name(NAME_SERVICE_ACCOUNT_TYPE)
  @Description("Service account type, file path where the service account is located or the JSON content of the " +
    "service account.")
  @Macro
  @Nullable
  protected String serviceAccountType;

  @Name(NAME_SERVICE_ACCOUNT_FILE_PATH)
  @Description("Path on the local file system of the service account key used " +
    "for authorization. Can be set to 'auto-detect' when running on a Dataproc cluster. " +
    "When running on other clusters, the file must be present on every node in the cluster.")
  @Macro
  @Nullable
  protected String serviceFilePath;

  @Name(NAME_SERVICE_ACCOUNT_JSON)
  @Description("Content of the service account file.")
  @Macro
  @Nullable
  protected String serviceAccountJson;


  public String getDatasetProject() {
    if (datasetProject == null || AUTO_DETECT.equalsIgnoreCase(datasetProject)) {
      return ServiceOptions.getDefaultProjectId();
    }
    return Strings.isNullOrEmpty(datasetProject) ? getProject() : datasetProject;
  }

  public String getProject() {
    String projectId = tryGetProject();
    if (projectId == null) {
      throw new IllegalArgumentException(
        "Could not detect Google Cloud project id from the environment. Please specify a project id.");
    }
    return projectId;
  }

  @Nullable
  public String tryGetProject() {
    if (containsMacro(NAME_PROJECT) && Strings.isNullOrEmpty(project)) {
      return null;
    }
    String projectId = project;
    if (Strings.isNullOrEmpty(project) || AUTO_DETECT.equals(project)) {
      projectId = ServiceOptions.getDefaultProjectId();
    }
    return projectId;
  }

  @Nullable
  public String getServiceAccountFilePath() {
    if (containsMacro(NAME_SERVICE_ACCOUNT_FILE_PATH) || serviceFilePath == null || serviceFilePath.isEmpty() ||
      AUTO_DETECT.equals(serviceFilePath)) {
      return null;
    }
    return serviceFilePath;
  }

  @Nullable
  public String getServiceAccountJson() {
    if (containsMacro(NAME_SERVICE_ACCOUNT_JSON) || Strings.isNullOrEmpty(serviceAccountJson)) {
      return null;
    }
    return serviceAccountJson;
  }

  /**
   * @return Service Account Type, defaults to filePath.
   */
  @Nullable
  public String getServiceAccountType() {
    if (containsMacro(NAME_SERVICE_ACCOUNT_TYPE)) {
      return null;
    }
    return Strings.isNullOrEmpty(serviceAccountType) ? SERVICE_ACCOUNT_FILE_PATH : serviceAccountType;
  }

  @Nullable
  public Boolean isServiceAccountJson() {
    String serviceAccountType = getServiceAccountType();
    return Strings.isNullOrEmpty(serviceAccountType) ? null : serviceAccountType.equals(SERVICE_ACCOUNT_JSON);
  }

  @Nullable
  public Boolean isServiceAccountFilePath() {
    String serviceAccountType = getServiceAccountType();
    return Strings.isNullOrEmpty(serviceAccountType) ? null : serviceAccountType.equals(SERVICE_ACCOUNT_FILE_PATH);
  }

  @Nullable
  public String getServiceAccount() {
    Boolean serviceAccountJson = isServiceAccountJson();
    if (serviceAccountJson == null) {
      return null;
    }
    return serviceAccountJson ? getServiceAccountJson() : getServiceAccountFilePath();
  }


  /**
   * Returns true if bigquery connection properties don't contain any Macro
   */
  public boolean canConnect() {
    return !containsMacro(NAME_DATASET_PROJECT) && !containsMacro(BigQueryConnectorConfig.NAME_SERVICE_ACCOUNT_TYPE) &&
      !(containsMacro(NAME_SERVICE_ACCOUNT_FILE_PATH) || containsMacro(NAME_SERVICE_ACCOUNT_JSON)) &&
      !containsMacro(NAME_PROJECT);
  }

}
