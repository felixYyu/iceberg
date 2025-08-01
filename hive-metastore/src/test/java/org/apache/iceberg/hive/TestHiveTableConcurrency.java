/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.hive;

import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Table;
import org.apache.iceberg.relocated.com.google.common.util.concurrent.MoreExecutors;
import org.apache.iceberg.util.Tasks;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

public class TestHiveTableConcurrency extends HiveTableTestBase {

  @Test
  public synchronized void testConcurrentFastAppends() {
    Table icebergTable = catalog.loadTable(TABLE_IDENTIFIER);

    String fileName = UUID.randomUUID().toString();
    DataFile file =
        DataFiles.builder(icebergTable.spec())
            .withPath(FileFormat.PARQUET.addExtension(fileName))
            .withRecordCount(2)
            .withFileSizeInBytes(0)
            .build();

    ExecutorService executorService =
        MoreExecutors.getExitingExecutorService(
            (ThreadPoolExecutor) Executors.newFixedThreadPool(2));

    AtomicInteger barrier = new AtomicInteger(0);
    int threadsCount = 2;
    Tasks.range(threadsCount)
        .stopOnFailure()
        .throwFailureWhenFinished()
        .executeWith(executorService)
        .run(
            index -> {
              for (int numCommittedFiles = 0; numCommittedFiles < 10; numCommittedFiles++) {
                final int currentFilesCount = numCommittedFiles;
                Awaitility.await()
                    .pollInterval(Duration.ofMillis(10))
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> barrier.get() >= currentFilesCount * threadsCount);
                icebergTable.newFastAppend().appendFile(file).commit();
                barrier.incrementAndGet();
              }
            });

    icebergTable.refresh();
    assertThat(icebergTable.currentSnapshot().allManifests(icebergTable.io())).hasSize(20);
  }

  @Test
  public synchronized void testConcurrentConnections() throws InterruptedException {
    Table icebergTable = catalog.loadTable(TABLE_IDENTIFIER);

    icebergTable
        .updateProperties()
        .set(COMMIT_NUM_RETRIES, "20")
        .set(COMMIT_MIN_RETRY_WAIT_MS, "25")
        .set(COMMIT_MAX_RETRY_WAIT_MS, "25")
        .commit();

    String fileName = UUID.randomUUID().toString();
    DataFile file =
        DataFiles.builder(icebergTable.spec())
            .withPath(FileFormat.PARQUET.addExtension(fileName))
            .withRecordCount(2)
            .withFileSizeInBytes(0)
            .build();

    ExecutorService executorService =
        MoreExecutors.getExitingExecutorService(
            (ThreadPoolExecutor) Executors.newFixedThreadPool(7));

    for (int i = 0; i < 7; i++) {
      executorService.submit(() -> icebergTable.newAppend().appendFile(file).commit());
    }

    executorService.shutdown();
    assertThat(executorService.awaitTermination(3, TimeUnit.MINUTES)).as("Timeout").isTrue();
    assertThat(icebergTable.snapshots()).hasSize(7);
  }
}
