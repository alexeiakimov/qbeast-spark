/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.delta

import io.qbeast.core.model.{ReplicatedSet, Revision, StagingUtils, TableChanges, mapper}
import io.qbeast.spark.utils.MetadataConfig
import io.qbeast.spark.utils.MetadataConfig.{lastRevisionID, revision}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.delta.schema.{ImplicitMetadataOperation, SchemaMergingUtils}
import org.apache.spark.sql.delta.{
  DeltaErrors,
  MetadataMismatchErrorBuilder,
  OptimisticTransaction
}
import org.apache.spark.sql.types.{ArrayType, DataType, MapType, StructType}

/**
 * Qbeast metadata changes on a Delta Table.
 */
private[delta] class QbeastMetadataOperation extends ImplicitMetadataOperation with StagingUtils {

  type Configuration = Map[String, String]

  /**
   * Returns the same data type but set all nullability fields are true
   * (ArrayType.containsNull, and MapType.valueContainsNull)
   * @param dataType the data type
   * @return same data type set to null
   */
  private def asNullable(dataType: DataType): DataType = {
    dataType match {
      case array: ArrayType => array.copy(containsNull = true)
      case map: MapType => map.copy(valueContainsNull = true)
      case other => other
    }
  }

  /**
   * Updates Delta Metadata Configuration with new replicated set
   * for given revision
   * @param baseConfiguration Delta Metadata Configuration
   * @param revision the new revision to persist
   * @param deltaReplicatedSet the new set of replicated cubes
   */

  private def updateQbeastReplicatedSet(
      baseConfiguration: Configuration,
      revision: Revision,
      deltaReplicatedSet: ReplicatedSet): Configuration = {

    val revisionID = revision.revisionID
    assert(baseConfiguration.contains(s"${MetadataConfig.revision}.$revisionID"))

    val newReplicatedSet = deltaReplicatedSet.map(_.string)
    // Save the replicated set of cube id's as String representation

    baseConfiguration.updated(
      s"${MetadataConfig.replicatedSet}.$revisionID",
      mapper.writeValueAsString(newReplicatedSet))

  }

  private def overwriteQbeastConfiguration(baseConfiguration: Configuration): Configuration = {
    val revisionKeys = baseConfiguration.keys.filter(_.startsWith(MetadataConfig.revision))
    val replicatedSetKeys = {
      baseConfiguration.keys.filter(_.startsWith(MetadataConfig.replicatedSet))
    }
    val other = baseConfiguration.keys.filter(_ == MetadataConfig.lastRevisionID)
    val qbeastKeys = revisionKeys ++ replicatedSetKeys ++ other
    baseConfiguration -- qbeastKeys
  }

  /**
   * Update metadata with new Qbeast Revision
   * @param baseConfiguration the base configuration
   * @param newRevision the new revision
   */
  private def updateQbeastRevision(
      baseConfiguration: Configuration,
      newRevision: Revision): Configuration = {
    val newRevisionID = newRevision.revisionID

    // Add staging revision, if necessary. The qbeast metadata configuration
    // should always have a revision with RevisionID = stagingID.
    val stagingRevisionKey = s"$revision.$stagingID"
    val addStagingRevision =
      newRevisionID == 1 && !baseConfiguration.contains(stagingRevisionKey)
    val configuration =
      if (!addStagingRevision) baseConfiguration
      else {
        // Create staging revision with EmptyTransformers (and EmptyTransformations).
        // We modify its timestamp to secure loadRevisionAt
        val stagingRev =
          stagingRevision(
            newRevision.tableID,
            newRevision.desiredCubeSize,
            newRevision.columnTransformers.map(_.columnName))
            .copy(timestamp = newRevision.timestamp - 1)

        // Add the staging revision to the revisionMap without overwriting
        // the latestRevisionID
        baseConfiguration
          .updated(stagingRevisionKey, mapper.writeValueAsString(stagingRev))
      }

    // Update latest revision id and add new revision to metadata
    configuration
      .updated(lastRevisionID, newRevisionID.toString)
      .updated(s"$revision.$newRevisionID", mapper.writeValueAsString(newRevision))
  }

  def updateQbeastMetadata(
      txn: OptimisticTransaction,
      schema: StructType,
      isOverwriteMode: Boolean,
      isOptimizeOperation: Boolean,
      rearrangeOnly: Boolean,
      tableChanges: TableChanges): Unit = {

    val spark = SparkSession.active

    val dataSchema = StructType(schema.fields.map(field =>
      field.copy(nullable = true, dataType = asNullable(field.dataType))))

    val mergedSchema =
      if (isOverwriteMode && canOverwriteSchema) dataSchema
      else SchemaMergingUtils.mergeSchemas(txn.metadata.schema, dataSchema)

    val normalizedPartitionCols = Seq.empty

    // Merged schema will contain additional columns at the end
    val isNewSchema: Boolean = txn.metadata.schema != mergedSchema
    // Either the data triggered a new revision or the user specified options to amplify the ranges
    val containsQbeastMetadata: Boolean = txn.metadata.configuration.contains(lastRevisionID)

    // TODO This whole class is starting to be messy, and contains a lot of IF then ELSE
    // TODO We should refactor QbeastMetadataOperation to make it more readable and usable
    // TODO Ideally, we should delegate this process to the underlying format

    // Append on an empty table
    val isNewWriteAppend = (!isOverwriteMode && txn.readVersion == -1)
    // If the table exists, but the user added a new revision, we need to create a new revision
    val isUserUpdatedMetadata =
      containsQbeastMetadata &&
        tableChanges.updatedRevision.revisionID == txn.metadata
          .configuration(lastRevisionID)
          .toInt + 1

    // Whether:
    // 1. Data Triggered a New Revision
    // 2. User added a columnStats that triggered a new Revision
    // 3. User made an APPEND on a NEW TABLE with columnStats that triggered a new Revision
    val isNewRevision: Boolean =
      tableChanges.isNewRevision || isUserUpdatedMetadata || isNewWriteAppend

    val latestRevision = tableChanges.updatedRevision
    val baseConfiguration: Configuration =
      if (txn.readVersion == -1) Map.empty
      else if (isOverwriteMode) overwriteQbeastConfiguration(txn.metadata.configuration)
      else txn.metadata.configuration

    // Qbeast configuration metadata
    val configuration =
      if (isNewRevision || isOverwriteMode) {
        updateQbeastRevision(baseConfiguration, latestRevision)
      } else if (isOptimizeOperation) {
        updateQbeastReplicatedSet(
          baseConfiguration,
          latestRevision,
          tableChanges.announcedOrReplicatedSet)
      } else baseConfiguration

    if (txn.readVersion == -1) {
      super.updateMetadata(
        spark,
        txn,
        schema,
        Seq.empty,
        configuration,
        isOverwriteMode,
        rearrangeOnly)
    } else if (isOverwriteMode && canOverwriteSchema && isNewSchema) {
      // Can define new partitioning in overwrite mode
      val newMetadata = txn.metadata.copy(
        schemaString = dataSchema.json,
        partitionColumns = normalizedPartitionCols,
        configuration = configuration)
      recordDeltaEvent(txn.deltaLog, "delta.ddl.overwriteSchema")
      if (rearrangeOnly) {
        throw DeltaErrors.unexpectedDataChangeException(
          "Overwrite the Delta table schema or " +
            "change the partition schema")
      }
      txn.updateMetadata(newMetadata)
    } else if (isNewSchema && canMergeSchema) {
      logInfo(s"New merged schema: ${mergedSchema.treeString}")
      recordDeltaEvent(txn.deltaLog, "delta.ddl.mergeSchema")
      if (rearrangeOnly) {
        throw DeltaErrors.unexpectedDataChangeException("Change the Delta table schema")
      }
      txn.updateMetadata(
        txn.metadata.copy(schemaString = mergedSchema.json, configuration = configuration))
    } else if (isNewSchema) {
      recordDeltaEvent(txn.deltaLog, "delta.schemaValidation.failure")
      val errorBuilder = new MetadataMismatchErrorBuilder
      if (isOverwriteMode) errorBuilder.addOverwriteBit()
      else {
        errorBuilder.addSchemaMismatch(txn.metadata.schema, dataSchema, txn.metadata.id)
      }
      errorBuilder.finalizeAndThrow(spark.sessionState.conf)
    } else txn.updateMetadata(txn.metadata.copy(configuration = configuration))
  }

  override protected val canMergeSchema: Boolean = true
  override protected val canOverwriteSchema: Boolean = true
}
