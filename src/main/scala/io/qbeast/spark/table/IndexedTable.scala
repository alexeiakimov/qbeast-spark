/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.table

import io.qbeast.core.keeper.Keeper
import io.qbeast.core.model._
import io.qbeast.spark.delta.StagingDataManager
import io.qbeast.spark.delta.StagingResolution
import io.qbeast.spark.internal.sources.QbeastBaseRelation
import io.qbeast.spark.internal.QbeastOptions
import io.qbeast.spark.internal.QbeastOptions.COLUMNS_TO_INDEX
import io.qbeast.spark.internal.QbeastOptions.CUBE_SIZE
import org.apache.spark.qbeast.config.DEFAULT_NUMBER_OF_RETRIES
import org.apache.spark.sql.delta.actions.FileAction
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.AnalysisExceptionFactory
import org.apache.spark.sql.DataFrame

import java.util.ConcurrentModificationException

/**
 * Indexed table represents the tabular data storage indexed with the OTree indexing technology.
 */
trait IndexedTable {

  /**
   * Returns whether the table physically exists.
   * @return
   *   the table physically exists.
   */
  def exists: Boolean

  /**
   * Returns the table id which identifies the table.
   *
   * @return
   *   the table id
   */
  def tableID: QTableID

  /**
   * Saves given data in the table and updates the index. The specified columns are used to define
   * the index when the table is created or overwritten. The append flag defines whether the
   * existing data should be overwritten.
   *
   * @param data
   *   the data to save
   * @param parameters
   *   the parameters to save the data
   * @param append
   *   the data should be appended to the table
   * @return
   *   the base relation to read the saved data
   */
  def save(data: DataFrame, parameters: Map[String, String], append: Boolean): BaseRelation

  /**
   * Loads the table data.
   *
   * @return
   *   the base relation to read the table data
   */
  def load(): BaseRelation

  /**
   * Analyzes the index for a given revision
   * @param revisionID
   *   the identifier of revision to analyze
   * @return
   *   the cubes to analyze
   */
  def analyze(revisionID: RevisionID): Seq[String]

  /**
   * Optimizes the given table for a given revision
   * @param revisionID
   *   the identifier of revision to optimize
   */
  def optimize(revisionID: RevisionID): Unit

  /**
   * Optimizes the table by optimizing the data stored in the specified index files.
   *
   * @param files
   *   the index files to optimize
   */
  def optimize(files: Seq[String]): Unit

  /**
   * Compacts the small files for a given table
   */
  def compact(revisionID: RevisionID): Unit
}

/**
 * IndexedTable factory.
 */
trait IndexedTableFactory {

  /**
   * Returns a IndexedTable for given SQLContext and path. It is not guaranteed that the returned
   * table physically exists, use IndexedTable#exists attribute to verify it.
   *
   * @param tableId
   *   the table path
   * @return
   *   the table
   */
  def getIndexedTable(tableId: QTableID): IndexedTable
}

/**
 * Implementation of IndexedTableFactory.
 * @param keeper
 *   the keeper
 * @param indexManager
 *   the index manager
 * @param metadataManager
 *   the metadata manager
 * @param dataWriter
 *   the data writer
 * @param revisionBuilder
 *   the revision builder
 */
final class IndexedTableFactoryImpl(
    private val keeper: Keeper,
    private val indexManager: IndexManager[DataFrame],
    private val metadataManager: MetadataManager[StructType, FileAction],
    private val dataWriter: DataWriter[DataFrame, StructType, FileAction],
    private val revisionBuilder: RevisionFactory[StructType])
    extends IndexedTableFactory {

  override def getIndexedTable(tableID: QTableID): IndexedTable =
    new IndexedTableImpl(
      tableID,
      keeper,
      indexManager,
      metadataManager,
      dataWriter,
      revisionBuilder)

}

/**
 * Implementation of IndexedTable.
 *
 * @param tableID
 *   the table identifier
 * @param keeper
 *   the keeper
 * @param indexManager
 *   the index manager
 * @param metadataManager
 *   the metadata manager
 * @param dataWriter
 *   the data writer
 * @param revisionBuilder
 *   the revision builder
 */
private[table] class IndexedTableImpl(
    val tableID: QTableID,
    private val keeper: Keeper,
    private val indexManager: IndexManager[DataFrame],
    private val metadataManager: MetadataManager[StructType, FileAction],
    private val dataWriter: DataWriter[DataFrame, StructType, FileAction],
    private val revisionBuilder: RevisionFactory[StructType])
    extends IndexedTable
    with StagingUtils {
  private var snapshotCache: Option[QbeastSnapshot] = None

  override def exists: Boolean = !snapshot.isInitial

  private def isNewRevision(qbeastOptions: QbeastOptions, latestRevision: Revision): Boolean = {
    // TODO feature: columnsToIndex may change between revisions
    checkColumnsToMatchSchema(latestRevision)
    // Checks if the desiredCubeSize is different from the existing one
    val isNewCubeSize = latestRevision.desiredCubeSize != qbeastOptions.cubeSize
    // Checks if the user-provided column boundaries would trigger the creation of
    // a new revision.
    val isNewSpace = qbeastOptions.stats match {
      case None => false
      case Some(stats) =>
        val columnStats = stats.first()
        val transformations = latestRevision.transformations

        val newPossibleTransformations =
          latestRevision.columnTransformers.map(t =>
            t.makeTransformation(columnName => columnStats.getAs[Object](columnName)))

        transformations
          .zip(newPossibleTransformations)
          .forall(t => {
            t._1.isSupersededBy(t._2)
          })
    }

    isNewCubeSize || isNewSpace

  }

  /**
   * Add the required indexing parameters when the SaveMode is Append. The user-provided
   * parameters are respected.
   * @param latestRevision
   *   the latest revision
   * @param parameters
   *   the parameters required for indexing
   */
  private def addRequiredParams(
      latestRevision: Revision,
      parameters: Map[String, String]): Map[String, String] = {
    val columnsToIndex = latestRevision.columnTransformers.map(_.columnName).mkString(",")
    val desiredCubeSize = latestRevision.desiredCubeSize.toString
    (parameters.contains(COLUMNS_TO_INDEX), parameters.contains(CUBE_SIZE)) match {
      case (true, true) => parameters
      case (false, false) =>
        parameters + (COLUMNS_TO_INDEX -> columnsToIndex, CUBE_SIZE -> desiredCubeSize)
      case (true, false) => parameters + (CUBE_SIZE -> desiredCubeSize)
      case (false, true) => parameters + (COLUMNS_TO_INDEX -> columnsToIndex)
    }
  }

  override def save(
      data: DataFrame,
      parameters: Map[String, String],
      append: Boolean): BaseRelation = {
    val indexStatus =
      if (exists && append) {
        // If the table exists and we are appending new data
        // 1. Load existing IndexStatus
        val latestRevision = snapshot.loadLatestRevision
        val updatedParameters = addRequiredParams(latestRevision, parameters)
        if (isStaging(latestRevision)) { // If the existing Revision is Staging
          IndexStatus(revisionBuilder.createNewRevision(tableID, data.schema, updatedParameters))
        } else {
          if (isNewRevision(QbeastOptions(updatedParameters), latestRevision)) {
            // If the new parameters generate a new revision, we need to create another one
            val newPotentialRevision = revisionBuilder
              .createNewRevision(tableID, data.schema, updatedParameters)
            val newRevisionCubeSize = newPotentialRevision.desiredCubeSize
            // Merge new Revision Transformations with old Revision Transformations
            val newRevisionTransformations =
              latestRevision.transformations.zip(newPotentialRevision.transformations).map {
                case (oldTransformation, newTransformation)
                    if oldTransformation.isSupersededBy(newTransformation) =>
                  Some(oldTransformation.merge(newTransformation))
                case _ => None
              }

            // Create a RevisionChange
            val revisionChanges = RevisionChange(
              supersededRevision = latestRevision,
              timestamp = System.currentTimeMillis(),
              desiredCubeSizeChange = Some(newRevisionCubeSize),
              transformationsChanges = newRevisionTransformations)

            // Output the New Revision into the IndexStatus
            IndexStatus(revisionChanges.createNewRevision)
          } else {
            // If the new parameters does not create a different revision,
            // load the latest IndexStatus
            snapshot.loadIndexStatus(latestRevision.revisionID)
          }
        }
      } else {
        IndexStatus(revisionBuilder.createNewRevision(tableID, data.schema, parameters))
      }

    val relation = write(data, indexStatus, append)
    relation
  }

  override def load(): BaseRelation = {
    clearCaches()
    createQbeastBaseRelation()
  }

  private def snapshot = {
    if (snapshotCache.isEmpty) {
      snapshotCache = Some(metadataManager.loadSnapshot(tableID))
    }
    snapshotCache.get
  }

  private def clearCaches(): Unit = {
    snapshotCache = None
  }

  private def checkColumnsToMatchSchema(revision: Revision): Unit = {
    val columnsToIndex = revision.columnTransformers.map(_.columnName)
    if (!snapshot.loadLatestRevision.matchColumns(columnsToIndex)) {
      throw AnalysisExceptionFactory.create(
        s"Columns to index '$columnsToIndex' do not match existing index.")
    }
  }

  /**
   * Creates a QbeastBaseRelation for the given table.
   * @return
   *   the QbeastBaseRelation
   */
  private def createQbeastBaseRelation(): BaseRelation = {
    QbeastBaseRelation.forQbeastTable(this)
  }

  private def write(data: DataFrame, indexStatus: IndexStatus, append: Boolean): BaseRelation = {
    val revision = indexStatus.revision
    keeper.withWrite(tableID, revision.revisionID) { write =>
      var tries = DEFAULT_NUMBER_OF_RETRIES
      while (tries > 0) {
        val announcedSet = write.announcedCubes.map(indexStatus.revision.createCubeId)
        val updatedStatus = indexStatus.addAnnouncements(announcedSet)
        val replicatedSet = updatedStatus.replicatedSet
        val revisionID = updatedStatus.revision.revisionID
        try {
          doWrite(data, updatedStatus, append)
          tries = 0
        } catch {
          case cme: ConcurrentModificationException
              if metadataManager.hasConflicts(
                tableID,
                revisionID,
                replicatedSet,
                announcedSet) || tries == 0 =>
            // Nothing to do, the conflict is unsolvable
            throw cme
          case _: ConcurrentModificationException =>
            // Trying one more time if the conflict is solvable
            tries -= 1
        }

      }
    }
    clearCaches()
    createQbeastBaseRelation()
  }

  private def doWrite(data: DataFrame, indexStatus: IndexStatus, append: Boolean): Unit = {
    val stagingDataManager: StagingDataManager = new StagingDataManager(tableID)
    stagingDataManager.updateWithStagedData(data) match {
      case r: StagingResolution if r.sendToStaging =>
        stagingDataManager.stageData(data, indexStatus, append)

      case StagingResolution(dataToWrite, removeFiles, false) =>
        val schema = dataToWrite.schema
        metadataManager.updateWithTransaction(tableID, schema, append) {
          val (qbeastData, tableChanges) = indexManager.index(dataToWrite, indexStatus)
          val fileActions = dataWriter.write(tableID, schema, qbeastData, tableChanges)
          (tableChanges, fileActions ++ removeFiles)
        }
    }
  }

  override def analyze(revisionID: RevisionID): Seq[String] = {
    val indexStatus = snapshot.loadIndexStatus(revisionID)
    val cubesToAnnounce = indexManager.analyze(indexStatus).map(_.string)
    keeper.announce(tableID, revisionID, cubesToAnnounce)
    cubesToAnnounce

  }

  override def optimize(revisionID: RevisionID): Unit = {
    val files = snapshot.loadIndexFiles(revisionID).map(_.path).toSeq
    optimize(files)
  }

  override def optimize(files: Seq[String]): Unit = {
    val paths = files.toSeq
    val schema = metadataManager.loadCurrentSchema(tableID)
    snapshot.loadAllRevisions.foreach { revision =>
      val indexFiles = snapshot
        .loadIndexFiles(revision.revisionID)
        .filter(file => paths.contains(file.path))
        .toIndexedSeq
      if (indexFiles.nonEmpty) {
        val indexStatus = snapshot.loadIndexStatus(revision.revisionID)
        metadataManager.updateWithTransaction(tableID, schema, append = true) {
          val tableChanges = BroadcastedTableChanges(None, indexStatus, Map.empty, Map.empty)
          val fileActions =
            dataWriter.compact(tableID, schema, revision, indexStatus, indexFiles)
          (tableChanges, fileActions)
        }
      }
    }
  }

  override def compact(revisionID: RevisionID): Unit = optimize(revisionID)

}
