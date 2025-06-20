package org.grapheco.provider

/**
 * @Author renhao
 * @Description:
 * @Data 2025/6/16 17:46
 * @Modified By:
 */

import org.apache.arrow.vector.{BigIntVector, BitVector, Float4Vector, Float8Vector, IntVector, VarBinaryVector, VarCharVector, VectorSchemaRoot, VectorUnloader}
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import org.apache.arrow.vector.types.pojo.Schema
import org.apache.jena.rdf.model.{Literal, Resource}
import org.apache.spark.sql.Row
import org.grapheco.Logging
import org.grapheco.client.{CSVSource, DFOperation, DirectorySource, StructuredSource}
import org.grapheco.server.RemoteDataFrame
import org.grapheco.util.DataUtils

import java.io.{File, FileInputStream}
import scala.collection.{Seq, mutable}
import scala.io.Source
import scala.jdk.CollectionConverters.asScalaBufferConverter

trait DataFrameSource {
  def getArrowRecordBatch(root: VectorSchemaRoot): Iterator[ArrowRecordBatch]
}

trait DataFrameSourceFactory {
  def createFileListDataFrameSource(remoteDataFrame: RemoteDataFrame): DataFrameSource
}

case class DataFrameSourceImpl(iter: Iterator[Seq[Row]]) extends DataFrameSource {

  override def getArrowRecordBatch(root: VectorSchemaRoot): Iterator[ArrowRecordBatch] = {
    iter.map(rows => createDummyBatch(root, rows))
  }

  private def createDummyBatch(arrowRoot: VectorSchemaRoot, rows: Seq[Row]): ArrowRecordBatch = {
    arrowRoot.allocateNew()
    val fieldVectors = arrowRoot.getFieldVectors.asScala
    var i = 0
    rows.foreach(row => {
      var j = 0
      fieldVectors.foreach(vec => {
        val value = row.get(j)
        value match {
          case v: Int => vec.asInstanceOf[IntVector].setSafe(i, v)
          case v: Long => vec.asInstanceOf[BigIntVector].setSafe(i, v)
          case v: Double => vec.asInstanceOf[Float8Vector].setSafe(i, v)
          case v: Float => vec.asInstanceOf[Float4Vector].setSafe(i, v)
          case v: String =>
            val bytes = v.getBytes("UTF-8")
            vec.asInstanceOf[VarCharVector].setSafe(i, bytes)
          case v: Boolean => vec.asInstanceOf[BitVector].setSafe(i, if (v) 1 else 0)
          case v: Array[Byte] => vec.asInstanceOf[VarBinaryVector].setSafe(i, v)
          case null => vec.setNull(i)
          case _ => throw new UnsupportedOperationException("Type not supported")
        }
        j += 1
      })
      i += 1
    })
    arrowRoot.setRowCount(rows.length)
    val unloader = new VectorUnloader(arrowRoot)
    unloader.getRecordBatch
  }
}

class DataFrameSourceFactoryImpl extends DataFrameSourceFactory with Logging {
  val batchSize = 10

  override def createFileListDataFrameSource(remoteDataFrame: RemoteDataFrame): DataFrameSource = {
    //    /Users/renhao/Downloads
    val dataSet = remoteDataFrame.source.datasetId
    val dataFrameName = remoteDataFrame.source.dataFrames
    val rdfModel = remoteDataFrame.getRDFModel
    log.info(s"create dataFrame from $dataSet/$dataFrameName")
    //根据dataSet dataFrameName拉取数据，目前这里dataSet代表路径 dataFrameName代表文件名称
    val stream: Iterator[Row] = remoteDataFrame.source.inputSource match {
      case CSVSource(delimiter) => DataUtils.getFileLines(s"$dataSet/$dataFrameName").map(line => {
        Row(line.split(delimiter): _*)
      })
      case StructuredSource() => DataUtils.getFileLines(s"$dataSet/$dataFrameName").map(line => {
        Row(line)
      })
      case DirectorySource(false) =>
        val chunkSize: Int = 5 * 1024 * 1024
        DataUtils.listFiles(s"$dataSet/$dataFrameName").toIterator.zipWithIndex.map {
          case (file, index) =>
            (index, file.getName, DataUtils.readFileInChunks(file, chunkSize))
        }.flatMap { case (index, filename, chunks) =>
          chunks.map(chunk => (index, filename, chunk))
        }.map(Row.fromTuple(_))
    }
    val result = applyOperations(stream, remoteDataFrame.ops)
    DataFrameSourceImpl(result.grouped(batchSize))
  }

  private def applyOperations(stream: Iterator[Row], ops: List[DFOperation]): Iterator[Row] = {
    ops.foldLeft(stream) { (acc, op) => op.transform(acc) }
  }

}

class DynamicDataFrameSourceFactory(provider: DataFrameProvider) extends DataFrameSourceFactory {


  override def createFileListDataFrameSource(remoteDataFrame: RemoteDataFrame): DataFrameSource = {
    val propertiesMap: Map[String, Any] = remoteDataFrame.getPropertiesMap
    propertiesMap("http://example.org/dataset/"+"format") match {
      case "csv" => new CSVSource(remoteDataFrame,provider)
      case "structured" => new StructuredSource(remoteDataFrame,provider)
      case _ => new DirectorySource(remoteDataFrame,provider)
      //      case other => throw new IllegalArgumentException(s"Unsupported format: $other")
    }
  }
}


class CSVSource(remoteDataFrame: RemoteDataFrame, provider: DataFrameProvider) extends DataFrameSource with Logging {
  val batchSize = 10000
  private val _dataFrameSourceImpl: DataFrameSource = {
    val dataSet = remoteDataFrame.source.datasetId
    val dataFrameName = remoteDataFrame.source.dataFrames
    log.info(s"create dataFrame from $dataSet/$dataFrameName")

    val delimiter: String = remoteDataFrame.getPropertiesMap.get("http://example.org/dataset/"+"delimiter").map(_.toString).getOrElse(",")
    val stream: Iterator[Row] = DataUtils.getFileLines(s"$dataSet/$dataFrameName").map(line => {
      Row(line.split(delimiter): _*)
    })

    val result = applyOperations(stream, remoteDataFrame.ops)
    DataFrameSourceImpl(result.grouped(batchSize))
  }

  private def applyOperations(stream: Iterator[Row], ops: List[DFOperation]): Iterator[Row] = {
    ops.foldLeft(stream) { (acc, op) => op.transform(acc) }
  }

  override def getArrowRecordBatch(root: VectorSchemaRoot): Iterator[ArrowRecordBatch] = _dataFrameSourceImpl.getArrowRecordBatch(root)
}

class StructuredSource(remoteDataFrame: RemoteDataFrame, provider: DataFrameProvider) extends DataFrameSource with Logging {
  val batchSize = 10000
  private val _dataFrameSourceImpl: DataFrameSource = {
    val dataSet = remoteDataFrame.source.datasetId
    val dataFrameName = remoteDataFrame.source.dataFrames
    log.info(s"create dataFrame from $dataSet/$dataFrameName")
    val stream: Iterator[Row] = DataUtils.getFileLines(s"$dataSet/$dataFrameName").map(line => {
      Row(line)
    })

    val result = applyOperations(stream, remoteDataFrame.ops)
    DataFrameSourceImpl(result.grouped(batchSize))
  }

  private def applyOperations(stream: Iterator[Row], ops: List[DFOperation]): Iterator[Row] = {
    ops.foldLeft(stream) { (acc, op) => op.transform(acc) }
  }

  override def getArrowRecordBatch(root: VectorSchemaRoot): Iterator[ArrowRecordBatch] = _dataFrameSourceImpl.getArrowRecordBatch(root)
}

class DirectorySource(remoteDataFrame: RemoteDataFrame, provider: DataFrameProvider) extends DataFrameSource with Logging {
  val batchSize = 10
  private val _dataFrameSourceImpl: DataFrameSource = {
    val dataSet = remoteDataFrame.source.datasetId
    val dataFrameName = remoteDataFrame.source.dataFrames
    val path = provider.getPath(remoteDataFrame)
    val format = remoteDataFrame.getPropertiesMap("http://example.org/dataset/"+"format")
    val fileType = remoteDataFrame.getPropertiesMap("http://example.org/dataset/"+"fileType")
    val size =  remoteDataFrame.getPropertiesMap("http://example.org/dataset/"+"size")
    val lastModified =  remoteDataFrame.getPropertiesMap("http://example.org/dataset/"+"lastModified")
    log.info(s"create dataFrame from $path")
    val chunkSize: Int = 5 * 1024 * 1024
    val stream: Iterator[Row] = DataUtils.listFiles(s"$path").toIterator.zipWithIndex.map {
      case (file, index) =>
        (index, file.getName, DataUtils.readFileInChunks(file, chunkSize))
    }.flatMap { case (index, filename, chunks) =>
      chunks.map(chunk => (filename, path, format, fileType, size, lastModified, chunk))
    }.map(Row.fromTuple(_))


  val result = applyOperations(stream, remoteDataFrame.ops)
  DataFrameSourceImpl(result.grouped(batchSize))
}

private def applyOperations(stream: Iterator[Row], ops: List[DFOperation]): Iterator[Row] = {
  ops.foldLeft(stream) { (acc, op) => op.transform(acc) }
}

override def getArrowRecordBatch(root: VectorSchemaRoot): Iterator[ArrowRecordBatch] = _dataFrameSourceImpl.getArrowRecordBatch(root)
}



