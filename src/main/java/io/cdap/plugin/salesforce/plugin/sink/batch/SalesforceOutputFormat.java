/*
 * Copyright © 2019 Cask Data, Inc.
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
package io.cdap.plugin.salesforce.plugin.sink.batch;

import com.sforce.async.AsyncApiException;
import com.sforce.async.BulkConnection;
import com.sforce.async.JobInfo;
import com.sforce.async.OperationEnum;
import io.cdap.plugin.salesforce.SalesforceBulkUtil;
import io.cdap.plugin.salesforce.SalesforceConnectionUtil;
import io.cdap.plugin.salesforce.authenticator.Authenticator;
import io.cdap.plugin.salesforce.authenticator.AuthenticatorCredentials;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * An OutputFormat that sends the output of a Hadoop job to the Salesforce record writer, also
 * it defines the output committer.
 */
public class SalesforceOutputFormat extends OutputFormat<NullWritable, CSVRecord> {

  private static final Logger LOG = LoggerFactory.getLogger(SalesforceOutputFormat.class);

  @Override
  public RecordWriter<NullWritable, CSVRecord> getRecordWriter(TaskAttemptContext taskAttemptContext)
    throws IOException {

    try {
      return new SalesforceRecordWriter(taskAttemptContext);
    } catch (AsyncApiException e) {
      throw new RuntimeException("There was issue communicating with Salesforce", e);
    }
  }

  @Override
  public void checkOutputSpecs(JobContext jobContext) {
    //no-op
  }

  /**
   * Used to start Salesforce job when Mapreduce job is started,
   * and to close Salesforce job when Mapreduce job is finished.
   */
  @Override
  public OutputCommitter getOutputCommitter(TaskAttemptContext taskAttemptContext) {
    return new OutputCommitter() {
      @Override
      public void setupJob(JobContext jobContext) {
        Configuration conf = jobContext.getConfiguration();
        String sObjectName = conf.get(SalesforceSinkConstants.CONFIG_SOBJECT);
        OperationEnum operationType = OperationEnum.valueOf(
          conf.get(SalesforceSinkConstants.CONFIG_OPERATION).toLowerCase());
        String externalIdField = conf.get(SalesforceSinkConstants.CONFIG_EXTERNAL_ID_FIELD);

        AuthenticatorCredentials credentials = SalesforceConnectionUtil.getAuthenticatorCredentials(conf);

        try {
          BulkConnection bulkConnection = new BulkConnection(Authenticator.createConnectorConfig(credentials));
          JobInfo job = SalesforceBulkUtil.createJob(bulkConnection, sObjectName, operationType, externalIdField);
          conf.set(SalesforceSinkConstants.CONFIG_JOB_ID, job.getId());
          LOG.info("Started Salesforce job with jobId='{}'", job.getId());
        } catch (AsyncApiException e) {
          throw new RuntimeException("There was issue communicating with Salesforce", e);
        }
      }

      @Override
      public void commitJob(JobContext jobContext) {
        Configuration conf = jobContext.getConfiguration();

        AuthenticatorCredentials credentials = SalesforceConnectionUtil.getAuthenticatorCredentials(conf);

        try {
          BulkConnection bulkConnection = new BulkConnection(Authenticator.createConnectorConfig(credentials));
          String jobId = conf.get(SalesforceSinkConstants.CONFIG_JOB_ID);
          SalesforceBulkUtil.closeJob(bulkConnection, jobId);
        } catch (AsyncApiException e) {
          throw new RuntimeException("There was issue communicating with Salesforce", e);
        }
      }

      @Override
      public void setupTask(TaskAttemptContext taskAttemptContext) {

      }

      @Override
      public boolean needsTaskCommit(TaskAttemptContext taskAttemptContext) {
        return true;
      }

      @Override
      public void commitTask(TaskAttemptContext taskAttemptContext) {

      }

      @Override
      public void abortTask(TaskAttemptContext taskAttemptContext) {

      }
    };
  }
}
