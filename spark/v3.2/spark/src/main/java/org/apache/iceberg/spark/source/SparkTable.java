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

package org.apache.iceberg.spark.source;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Partitioning;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Evaluator;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Projections;
import org.apache.iceberg.expressions.StrictMetricsEvaluator;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.spark.SparkFilters;
import org.apache.iceberg.spark.SparkReadOptions;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.SparkTableUtil;
import org.apache.iceberg.transforms.Transforms;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.DateTimeUtil;
import org.apache.iceberg.util.SnapshotUtil;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.connector.catalog.MetadataColumn;
import org.apache.spark.sql.connector.catalog.SupportsDelete;
import org.apache.spark.sql.connector.catalog.SupportsMetadataColumns;
import org.apache.spark.sql.connector.catalog.SupportsPartitionManagement;
import org.apache.spark.sql.connector.catalog.SupportsRead;
import org.apache.spark.sql.connector.catalog.SupportsWrite;
import org.apache.spark.sql.connector.catalog.TableCapability;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.connector.iceberg.catalog.SupportsRowLevelOperations;
import org.apache.spark.sql.connector.iceberg.write.RowLevelOperationBuilder;
import org.apache.spark.sql.connector.iceberg.write.RowLevelOperationInfo;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.write.LogicalWriteInfo;
import org.apache.spark.sql.connector.write.WriteBuilder;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StringType;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.apache.spark.unsafe.types.UTF8String;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

public class SparkTable implements org.apache.spark.sql.connector.catalog.Table,
    SupportsRead, SupportsWrite, SupportsDelete, SupportsRowLevelOperations, SupportsMetadataColumns,
        SupportsPartitionManagement {

  private static final Logger LOG = LoggerFactory.getLogger(SparkTable.class);

  private static final Set<String> RESERVED_PROPERTIES =
      ImmutableSet.of("provider", "format", "current-snapshot-id", "location", "sort-order");
  private static final Set<TableCapability> CAPABILITIES = ImmutableSet.of(
      TableCapability.BATCH_READ,
      TableCapability.BATCH_WRITE,
      TableCapability.MICRO_BATCH_READ,
      TableCapability.STREAMING_WRITE,
      TableCapability.OVERWRITE_BY_FILTER,
      TableCapability.OVERWRITE_DYNAMIC);

  private final Table icebergTable;
  private final Long snapshotId;
  private final boolean refreshEagerly;
  private StructType lazyTableSchema = null;
  private SparkSession lazySpark = null;

  public SparkTable(Table icebergTable, boolean refreshEagerly) {
    this(icebergTable, null, refreshEagerly);
  }

  public SparkTable(Table icebergTable, Long snapshotId, boolean refreshEagerly) {
    this.icebergTable = icebergTable;
    this.snapshotId = snapshotId;
    this.refreshEagerly = refreshEagerly;
  }

  private SparkSession sparkSession() {
    if (lazySpark == null) {
      this.lazySpark = SparkSession.active();
    }

    return lazySpark;
  }

  public Table table() {
    return icebergTable;
  }

  @Override
  public String name() {
    return icebergTable.toString();
  }

  private Schema snapshotSchema() {
    return SnapshotUtil.schemaFor(icebergTable, snapshotId, null);
  }

  @Override
  public StructType schema() {
    if (lazyTableSchema == null) {
      this.lazyTableSchema = SparkSchemaUtil.convert(snapshotSchema());
    }

    return lazyTableSchema;
  }

  @Override
  public Transform[] partitioning() {
    return Spark3Util.toTransforms(icebergTable.spec());
  }

  @Override
  public Map<String, String> properties() {
    ImmutableMap.Builder<String, String> propsBuilder = ImmutableMap.builder();

    String fileFormat = icebergTable.properties()
        .getOrDefault(TableProperties.DEFAULT_FILE_FORMAT, TableProperties.DEFAULT_FILE_FORMAT_DEFAULT);
    propsBuilder.put("format", "iceberg/" + fileFormat);
    propsBuilder.put("provider", "iceberg");
    String currentSnapshotId = icebergTable.currentSnapshot() != null ?
        String.valueOf(icebergTable.currentSnapshot().snapshotId()) : "none";
    propsBuilder.put("current-snapshot-id", currentSnapshotId);
    propsBuilder.put("location", icebergTable.location());

    if (!icebergTable.sortOrder().isUnsorted()) {
      propsBuilder.put("sort-order", Spark3Util.describe(icebergTable.sortOrder()));
    }

    icebergTable.properties().entrySet().stream()
        .filter(entry -> !RESERVED_PROPERTIES.contains(entry.getKey()))
        .forEach(propsBuilder::put);

    return propsBuilder.build();
  }

  @Override
  public Set<TableCapability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public MetadataColumn[] metadataColumns() {
    DataType sparkPartitionType = SparkSchemaUtil.convert(Partitioning.partitionType(table()));
    return new MetadataColumn[] {
        new SparkMetadataColumn(MetadataColumns.SPEC_ID.name(), DataTypes.IntegerType, false),
        new SparkMetadataColumn(MetadataColumns.PARTITION_COLUMN_NAME, sparkPartitionType, true),
        new SparkMetadataColumn(MetadataColumns.FILE_PATH.name(), DataTypes.StringType, false),
        new SparkMetadataColumn(MetadataColumns.ROW_POSITION.name(), DataTypes.LongType, false)
    };
  }

  @Override
  public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) {
    if (options.containsKey(SparkReadOptions.FILE_SCAN_TASK_SET_ID)) {
      // skip planning the job and fetch already staged file scan tasks
      return new SparkFilesScanBuilder(sparkSession(), icebergTable, options);
    }

    if (refreshEagerly) {
      icebergTable.refresh();
    }

    CaseInsensitiveStringMap scanOptions = addSnapshotId(options, snapshotId);
    return new SparkScanBuilder(sparkSession(), icebergTable, snapshotSchema(), scanOptions);
  }

  @Override
  public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
    Preconditions.checkArgument(
        snapshotId == null,
        "Cannot write to table at a specific snapshot: %s", snapshotId);

    return new SparkWriteBuilder(sparkSession(), icebergTable, info);
  }

  @Override
  public RowLevelOperationBuilder newRowLevelOperationBuilder(RowLevelOperationInfo info) {
    return new SparkRowLevelOperationBuilder(sparkSession(), icebergTable, info);
  }

  @Override
  public boolean canDeleteWhere(Filter[] filters) {
    Preconditions.checkArgument(
        snapshotId == null,
        "Cannot delete from table at a specific snapshot: %s", snapshotId);

    Expression deleteExpr = Expressions.alwaysTrue();

    for (Filter filter : filters) {
      Expression expr = SparkFilters.convert(filter);
      if (expr != null) {
        deleteExpr = Expressions.and(deleteExpr, expr);
      } else {
        return false;
      }
    }

    return deleteExpr == Expressions.alwaysTrue() || canDeleteUsingMetadata(deleteExpr);
  }

  // a metadata delete is possible iff matching files can be deleted entirely
  private boolean canDeleteUsingMetadata(Expression deleteExpr) {
    boolean caseSensitive = Boolean.parseBoolean(sparkSession().conf().get("spark.sql.caseSensitive"));
    TableScan scan = table().newScan()
        .filter(deleteExpr)
        .caseSensitive(caseSensitive)
        .includeColumnStats()
        .ignoreResiduals();

    try (CloseableIterable<FileScanTask> tasks = scan.planFiles()) {
      Map<Integer, Evaluator> evaluators = Maps.newHashMap();
      StrictMetricsEvaluator metricsEvaluator = new StrictMetricsEvaluator(table().schema(), deleteExpr);

      return Iterables.all(tasks, task -> {
        DataFile file = task.file();
        PartitionSpec spec = task.spec();
        Evaluator evaluator = evaluators.computeIfAbsent(
            spec.specId(),
            specId -> new Evaluator(spec.partitionType(), Projections.strict(spec).project(deleteExpr)));
        return evaluator.eval(file.partition()) || metricsEvaluator.eval(file);
      });

    } catch (IOException ioe) {
      LOG.warn("Failed to close task iterable", ioe);
      return false;
    }
  }

  @Override
  public void deleteWhere(Filter[] filters) {
    Expression deleteExpr = SparkFilters.convert(filters);

    if (deleteExpr == Expressions.alwaysFalse()) {
      LOG.info("Skipping the delete operation as the condition is always false");
      return;
    }

    try {
      icebergTable.newDelete()
          .set("spark.app.id", sparkSession().sparkContext().applicationId())
          .deleteFromRowFilter(deleteExpr)
          .commit();
    } catch (ValidationException e) {
      throw new IllegalArgumentException("Failed to cleanly delete data files matching: " + deleteExpr, e);
    }
  }

  @Override
  public StructType partitionSchema() {
    Types.StructType structType = Partitioning.partitionType(table());
    List<Types.NestedField> structFields = Lists.newArrayListWithExpectedSize(structType.fields().size());
    structType.fields().forEach(nestedField -> {
      if (nestedField.name().endsWith("hour") ||
              nestedField.name().endsWith("month") ||
              nestedField.name().endsWith("year")) {
        structFields.add(Types.NestedField.optional(nestedField.fieldId(), nestedField.name(), Types.StringType.get()));
      } else {
        // do nothing
        structFields.add(nestedField);
      }
    });

    return (StructType) SparkSchemaUtil.convert(Types.StructType.of(structFields));
  }

  @Override
  public void createPartition(InternalRow ident, Map<String, String> properties) throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Cannot explicitly create partitions in Iceberg tables");
  }

  @Override
  public boolean dropPartition(InternalRow ident) {
    throw new UnsupportedOperationException("Cannot explicitly drop partitions in Iceberg tables");
  }

  @Override
  public void replacePartitionMetadata(InternalRow ident, Map<String, String> properties)
          throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Iceberg partitions do not support metadata");
  }

  @Override
  public Map<String, String> loadPartitionMetadata(InternalRow ident) throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Iceberg partitions do not support metadata");
  }

  @Override
  public InternalRow[] listPartitionIdentifiers(String[] names, InternalRow ident) {
    // support show partitions
    List<InternalRow> rows = Lists.newArrayList();
    Dataset<Row> df = SparkTableUtil.loadMetadataTable(sparkSession(), icebergTable, MetadataTableType.PARTITIONS)
            .select("partition");
    StructType schema = partitionSchema();
    if (names.length > 0) {
      df.collectAsList().forEach(row -> {
        GenericRowWithSchema genericRow = (GenericRowWithSchema) row.apply(0);
        Object[] values = transform(schema, genericRow.values());
        boolean exits = true;
        int index = 0;
        while (index < names.length) {
          DataType dataType = schema.apply(names[index]).dataType();
          int fieldIndex = schema.fieldIndex(names[index]);
          if (!values[fieldIndex].equals(ident.get(index, dataType))) {
            exits = false;
            break;
          }
          index += 1;
        }
        if (exits) {
          rows.add(new GenericInternalRow(values));
        }
      });
    } else {
      df.collectAsList().forEach(row -> {
        GenericRowWithSchema genericRow = (GenericRowWithSchema) row.apply(0);
        rows.add(new GenericInternalRow(transform(schema, genericRow.values())));
      });
    }
    return rows.toArray(new InternalRow[0]);
  }

  @Override
  public String toString() {
    return icebergTable.toString();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    } else if (other == null || getClass() != other.getClass()) {
      return false;
    }

    // use only name in order to correctly invalidate Spark cache
    SparkTable that = (SparkTable) other;
    return icebergTable.name().equals(that.icebergTable.name());
  }

  @Override
  public int hashCode() {
    // use only name in order to correctly invalidate Spark cache
    return icebergTable.name().hashCode();
  }

  private static CaseInsensitiveStringMap addSnapshotId(CaseInsensitiveStringMap options, Long snapshotId) {
    if (snapshotId != null) {
      String snapshotIdFromOptions = options.get(SparkReadOptions.SNAPSHOT_ID);
      String value = snapshotId.toString();
      Preconditions.checkArgument(snapshotIdFromOptions == null || snapshotIdFromOptions.equals(value),
              "Cannot override snapshot ID more than once: %s", snapshotIdFromOptions);

      Map<String, String> scanOptions = Maps.newHashMap();
      scanOptions.putAll(options.asCaseSensitiveMap());
      scanOptions.put(SparkReadOptions.SNAPSHOT_ID, value);
      scanOptions.remove(SparkReadOptions.AS_OF_TIMESTAMP);

      return new CaseInsensitiveStringMap(scanOptions);
    }

    return options;
  }

  private static Object[] transform(StructType schema, Object[] values) {
    Object[] resultValues = new Object[values.length];
    JavaConverters.asJavaIterableConverter(schema).asJava().forEach(field -> {
      int fieldIndex = schema.fieldIndex(field.name());
      Object catalystValue = values[fieldIndex];
      if (field.name().endsWith("hour")) {
        org.apache.iceberg.transforms.Transform<Object, Integer> hour = Transforms.hour(Types.TimestampType.withZone());
        catalystValue = UTF8String.fromString(hour.toHumanString((int) catalystValue));
      } else if (field.name().endsWith("day")) {
        catalystValue = DateTimeUtil.daysFromDate(LocalDate.parse(catalystValue.toString()));
      } else if (field.name().endsWith("month")) {
        org.apache.iceberg.transforms.Transform<Object, Integer> month = Transforms.month(Types.DateType.get());
        catalystValue = UTF8String.fromString(month.toHumanString((int) catalystValue));
      } else if (field.name().endsWith("year")) {
        org.apache.iceberg.transforms.Transform<Object, Integer> year = Transforms.year(Types.DateType.get());
        catalystValue = UTF8String.fromString(year.toHumanString((int) catalystValue));
      }

      if (field.dataType() instanceof StringType) {
        catalystValue = UTF8String.fromString(catalystValue.toString());
      }
      resultValues[fieldIndex] = catalystValue;
    });
    return resultValues;
  }
}
