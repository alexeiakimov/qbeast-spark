package io.qbeast.model.api

import io.qbeast.IISeq
import io.qbeast.model.{QTableID, TableChanges}

trait DataWriter[DATA, DataSchema, FileAction] {

  /**
   * Write the index data to the files
   * @param tableID the table identifier
   * @param schema the schema of the data
   * @param data the data to write
   * @param tableChanges the changes of the index
   * @return the sequence of files written
   */
  def write(
      tableID: QTableID,
      schema: DataSchema,
      data: DATA,
      tableChanges: TableChanges): IISeq[FileAction]

}
