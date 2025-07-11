package link.rdcn.client


import link.rdcn.SimpleSerializer
import link.rdcn.struct.Row
import link.rdcn.user.Credentials
import link.rdcn.util.DataUtils
import org.apache.arrow.flight.{Action, AsyncPutListener, FlightClient, FlightDescriptor, FlightInfo, FlightRuntimeException, Location, Result}
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, VarBinaryVector, VarCharVector, VectorSchemaRoot}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}

import java.util.UUID
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, FileOutputStream, InputStream}
import java.nio.file.Paths
import scala.collection.JavaConverters.{asScalaBufferConverter, asScalaIteratorConverter, seqAsJavaListConverter}
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.collection.mutable

/**
 * @Author renhao
 * @Description:
 * @Data 2025/6/16 14:45
 * @Modified By:
 */

trait ProtocolClient{
  def listDataSetNames(): Seq[String]
  def listDataFrameNames(dsName: String): Seq[String]
  def getDataSetMetaData(dsName: String): String
  def close(): Unit
  def getRows(dataFrameName: String, ops: String): Iterator[Row]
  def getDataFrameSize(dataFrameName: String): Long
  def getHostInfo(): String
  def getServerResourceInfo(): String
}
class ArrowFlightProtocolClient(url: String, port:Int) extends ProtocolClient{

  val location = Location.forGrpcInsecure(url, port)
  val allocator: BufferAllocator = new RootAllocator()
  private val flightClient = FlightClient.builder(allocator, location).build()
  private val userToken = UUID.randomUUID().toString

  def login(credentials: Credentials): Unit = {
      val paramFields: Seq[Field] = List(
        new Field("credentials", FieldType.nullable(new ArrowType.Binary()), null),
      )
      val schema = new Schema(paramFields.asJava)
      val vectorSchemaRoot = VectorSchemaRoot.create(schema, allocator)
      val credentialsVector = vectorSchemaRoot.getVector("credentials").asInstanceOf[VarBinaryVector]
      credentialsVector.allocateNew(1)
      credentialsVector.set(0, SimpleSerializer.serialize(credentials))
      vectorSchemaRoot.setRowCount(1)
      val body = DataUtils.getBytesFromVectorSchemaRoot(vectorSchemaRoot)
      val result = flightClient.doAction(new Action(s"login.$userToken",body)).asScala
      result.hasNext
  }

  def listDataSetNames(): Seq[String] = {
      val dataSetNames = flightClient.doAction(new Action("listDataSetNames")).asScala
      getListStringByResult(dataSetNames)
  }
  def listDataFrameNames(dsName: String): Seq[String] = {
    val dataFrameNames = flightClient.doAction(new Action(s"listDataFrameNames.$dsName")).asScala
    getListStringByResult(dataFrameNames)
  }

  def getSchema(dataFrameName: String): String = {
    val schema = flightClient.doAction(new Action(s"getSchema.$dataFrameName")).asScala
      getSingleStringByResult(schema)
  }

  override def getDataSetMetaData(dataSetName: String): String = {
    val dataSetMetaData = flightClient.doAction(new Action(s"getDataSetMetaData.$dataSetName")).asScala
    getSingleStringByResult(dataSetMetaData)
  }

  override def getDataFrameSize(dataFrameName: String): Long = {
    val dataFrameSize = flightClient.doAction(new Action(s"getDataFrameSize.$dataFrameName")).asScala
    getSingleLongByResult(dataFrameSize)
  }

  override def getHostInfo(): String = {
    val hostInfo = flightClient.doAction(new Action(s"getHostInfo")).asScala
    getSingleStringByResult(hostInfo)
  }

  override def getServerResourceInfo(): String = {
    val serverResourceInfo = flightClient.doAction(new Action(s"getServerResourceInfo")).asScala
    getSingleStringByResult(serverResourceInfo)
  }

  def getSchemaURI(dataFrameName: String): String = {
    val schemaURI = flightClient.doAction(new Action(s"getSchemaURI.$dataFrameName")).asScala
    getSingleStringByResult(schemaURI)
  }

  def close(): Unit = {
    flightClient.close()
  }

  def getRows(dataFrameName: String, operationNode: String): Iterator[Row]  = {
    //上传参数
    val paramFields: Seq[Field] = List(
      new Field("dfName", FieldType.nullable(new ArrowType.Utf8()), null),
      new Field("userToken", FieldType.nullable(new ArrowType.Utf8()), null),
      new Field("DFOperation", FieldType.nullable(new ArrowType.Utf8()), null)
    )
    val schema = new Schema(paramFields.asJava)
    val vectorSchemaRoot = VectorSchemaRoot.create(schema, allocator)
    val dfNameCharVector = vectorSchemaRoot.getVector("dfName").asInstanceOf[VarCharVector]
    val tokenCharVector = vectorSchemaRoot.getVector("userToken").asInstanceOf[VarCharVector]
    val dfOperationVector = vectorSchemaRoot.getVector("DFOperation").asInstanceOf[VarCharVector]
    dfNameCharVector.allocateNew(1)
    dfNameCharVector.set(0, dataFrameName.getBytes("UTF-8"))
    tokenCharVector.allocateNew(1)
    tokenCharVector.set(0, userToken.getBytes("UTF-8"))
    dfOperationVector.allocateNew(1)
    dfOperationVector.set(0, operationNode.getBytes("UTF-8"))
    vectorSchemaRoot.setRowCount(1)

    val requestSchemaId = UUID.randomUUID().toString
    val listener = flightClient.startPut(FlightDescriptor.path(requestSchemaId), vectorSchemaRoot, new AsyncPutListener())
    listener.putNext()
    listener.completed()
    listener.getResult()

    val flightInfo = flightClient.getInfo(FlightDescriptor.path(requestSchemaId))
    val flightInfoSchema = flightInfo.getSchema
    val isBinaryColumn = flightInfoSchema.getFields.last.getType match {
      case _: ArrowType.Binary => true
      case _ => false
    }
    val flightStream = flightClient.getStream(flightInfo.getEndpoints.get(0).getTicket)
    val iter: Iterator[Seq[Seq[Any]]] = new Iterator[Seq[Seq[Any]]] {
      override def hasNext: Boolean = flightStream.next()

      override def next(): Seq[Seq[Any]] = {
        val vectorSchemaRootReceived = flightStream.getRoot
        val rowCount = vectorSchemaRootReceived.getRowCount
        val fieldVectors = vectorSchemaRootReceived.getFieldVectors.asScala
        Seq.range(0, rowCount).map(index => {
          val rowMap = mutable.LinkedHashMap(fieldVectors.map(vec => {
            if (vec.isNull(index)) (vec.getName, null)
            else vec match {
              case v: org.apache.arrow.vector.IntVector => (vec.getName, v.get(index))
              case v: org.apache.arrow.vector.BigIntVector => (vec.getName, v.get(index))
              case v: org.apache.arrow.vector.VarCharVector => (vec.getName, new String(v.get(index)))
              case v: org.apache.arrow.vector.Float8Vector => (vec.getName, v.get(index))
              case v: org.apache.arrow.vector.BitVector => (vec.getName, v.get(index) == 1)
              case v: org.apache.arrow.vector.VarBinaryVector => (vec.getName, v.get(index))
              case _ => throw new UnsupportedOperationException(s"Unsupported vector type: ${vec.getClass}")
            }
          }):_*)
          val r: Seq[Any] = rowMap.values.toList
          r
        })
      }
    }
    val flatIter: Iterator[Seq[Any]] = iter.flatMap(rows => rows)

    if (!isBinaryColumn) {
      // 第三列不是binary类型，直接返回Row(Seq[Any])
      flatIter.map(seq => Row.fromSeq(seq))
    } else {

      var isFirstChunk: Boolean = true
      var currentSeq: Seq[Any] = if(flatIter.hasNext) flatIter.next() else Seq.empty[Any]
      var cachedSeq: Seq[Any] = currentSeq
      var currentChunk: Array[Byte] = Array[Byte]()
      var cachedChunk: Array[Byte] = currentSeq.last.asInstanceOf[Array[Byte]]
      var cachedName: String = currentSeq(0).asInstanceOf[String]
      var currentName: String = currentSeq(0).asInstanceOf[String]
      new Iterator[Row] {
        override def hasNext: Boolean = flatIter.hasNext || cachedChunk.nonEmpty

        override def next(): Row = {

          val blobIter: Iterator[Array[Byte]] = new Iterator[Array[Byte]] {

            private var isExhausted: Boolean = false // flatIter 是否耗尽

            // 预读取下一块的 index 和 struct（如果存在）
            private def readNextChunk(): Unit = {
              if (flatIter.hasNext) {
                if (!isFirstChunk) {
                  val nextSeq: Seq[Any] = if(flatIter.hasNext) flatIter.next() else Seq.empty[Any]
                  val nextName: String = nextSeq(0).asInstanceOf[String]
                  val nextChunk: Array[Byte] = nextSeq.last.asInstanceOf[Array[Byte]]
                  if (nextName != currentName) {
                    // index 变化，结束当前块
                    isExhausted = true
                    isFirstChunk = true
                    cachedSeq = nextSeq
                    cachedName = nextName
                    cachedChunk = nextChunk
                  } else {

                    currentChunk = nextChunk
                    currentName = nextName
                  }
                  currentSeq = nextSeq
                  currentName = nextName
                } else {
                  currentSeq = cachedSeq
                  currentName = cachedName
                  currentChunk = cachedChunk
                  isExhausted = false
                  isFirstChunk = false
                }

              } else {
                // flatIter 耗尽
                if (cachedChunk.nonEmpty)
                  currentChunk = cachedChunk
                isExhausted = true
              }
            }

            // hasNext: 检查是否还有块（可能预读取）
            override def hasNext: Boolean = {
              if (currentChunk.isEmpty && !isExhausted) {
                readNextChunk() // 如果当前块为空且迭代器未结束，尝试预读取
              }
              !isExhausted || currentChunk.nonEmpty
            }

            // next: 返回当前块（已由 hasNext 预加载）
            override def next(): Array[Byte] = {

              if (!isFirstChunk) {
                val chunk = currentChunk
                currentChunk = Array.empty[Byte]
                if (!flatIter.hasNext)
                  cachedChunk = Array.empty[Byte]
                chunk
                // 手动清空
              } else {
                val chunk = cachedChunk
                cachedChunk = Array.empty[Byte]
                currentChunk = Array.empty[Byte]
                chunk
              }

            }
          }
          Row(currentSeq.init:+new Blob(blobIter,currentSeq(0).asInstanceOf[String]):_*)
        }
      }
    }
  }

  private def getListStringByFlightInfo(flightInfo: FlightInfo): Seq[String] = {
    val flightStream = flightClient.getStream(flightInfo.getEndpoints.get(0).getTicket)
    if(flightStream.next()){
      val vectorSchemaRootReceived = flightStream.getRoot
      val rowCount = vectorSchemaRootReceived.getRowCount
      val fieldVectors = vectorSchemaRootReceived.getFieldVectors.asScala
      Seq.range(0, rowCount).map(index  => {
        val rowMap = fieldVectors.map(vec =>{
          vec.asInstanceOf[VarCharVector].getObject(index).toString
        }).head
        rowMap
      })
    }else null
  }

  private def getSingleStringByFlightInfo(flightInfo: FlightInfo): String = {
      val flightStream = flightClient.getStream(flightInfo.getEndpoints.get(0).getTicket)
      if(flightStream.next()){
        val vectorSchemaRootReceived = flightStream.getRoot
        val fieldVectors = vectorSchemaRootReceived.getFieldVectors.asScala
        fieldVectors.head.asInstanceOf[VarCharVector].getObject(0).toString
      }else null
    }
  private def getSingleLongByFlightInfo(flightInfo: FlightInfo): Long = {
    val flightStream = flightClient.getStream(flightInfo.getEndpoints.get(0).getTicket)
    if(flightStream.next()){
      val vectorSchemaRootReceived = flightStream.getRoot
      val fieldVectors = vectorSchemaRootReceived.getFieldVectors.asScala
      fieldVectors.head.asInstanceOf[BigIntVector].getObject(0)
    }else 0L
  }

  private def getListStringByResult(resultIterator: Iterator[Result]): Seq[String] = {
    if(resultIterator.hasNext){
      val result = resultIterator.next
      val vectorSchemaRootReceived = DataUtils.getVectorSchemaRootFromBytes(result.getBody,allocator)
      val rowCount = vectorSchemaRootReceived.getRowCount
      val fieldVectors = vectorSchemaRootReceived.getFieldVectors.asScala
      Seq.range(0, rowCount).map(index  => {
        val rowMap = fieldVectors.map(vec =>{
          vec.asInstanceOf[VarCharVector].getObject(index).toString
        }).head
        rowMap
      })
    }else null
  }

  private def getSingleStringByResult(resultIterator: Iterator[Result]): String = {
    if(resultIterator.hasNext){
      val result = resultIterator.next
      val vectorSchemaRootReceived = DataUtils.getVectorSchemaRootFromBytes(result.getBody,allocator)
      val fieldVectors = vectorSchemaRootReceived.getFieldVectors.asScala
      fieldVectors.head.asInstanceOf[VarCharVector].getObject(0).toString
    }else null
  }
  private def getSingleLongByResult(resultIterator: Iterator[Result]): Long = {
    if(resultIterator.hasNext){
      val result = resultIterator.next
      val vectorSchemaRootReceived = DataUtils.getVectorSchemaRootFromBytes(result.getBody,allocator)
      val fieldVectors = vectorSchemaRootReceived.getFieldVectors.asScala
      fieldVectors.head.asInstanceOf[BigIntVector].getObject(0)
    }else 0L
  }


}

// 表示完整的二进制文件
class Blob( val chunkIterator:Iterator[Array[Byte]], val name: String) extends Serializable {
  // 缓存加载后的完整数据
  private var _content: Option[Array[Byte]] = None
  // 缓存文件大小（独立于_content，避免获取大数组长度）
  private var _size: Option[Long] = None

  private var _chunkCount: Option[Int] = None

  private var _memoryReleased: Boolean = false

  private def loadLazily(): Unit = {
//    println("loadLazily")
    if (_content.isEmpty && _size.isEmpty && _chunkCount.isEmpty) {
      val byteStream = new ByteArrayOutputStream()
      var totalSize: Long = 0L
      var chunkCount = 0

      while (chunkIterator.hasNext) {
        val chunk = chunkIterator.next()
//        println(chunk.mkString("Array(", ", ", ")"))
        totalSize += chunk.length
        chunkCount+=1
        byteStream.write(chunk)
//        byteStream.reset()

      }
//      println("loaded Lazily")
      _content = Some(byteStream.toByteArray)
      _size = Some(totalSize)
      _chunkCount = Some(chunkCount)
      byteStream.close()
    }
  }


  /** 获取完整的文件内容 */
  def content: Array[Byte] = {
    if (_memoryReleased) {
      throw new IllegalStateException("Blob content memory has been released")
    }
    if (_content.isEmpty) loadLazily()
    _content.get
  }


  /** 获取文件大小 */
  def size: Long = {
    if (_size.isEmpty) loadLazily()
    _size.get
  }

  /** 获取分块数量 */
  def chunkCount: Int = {
    if (_chunkCount.isEmpty) loadLazily()
    _chunkCount.get
  }

  /** 释放content占用的内存 */
  def releaseContentMemory(): Unit = {
    _content = None
    _memoryReleased = true
    System.gc()
  }

  // 获得 `InputStream`（适合流式读取 `content`）
  def getInputStream: InputStream = {
    if (_memoryReleased) throw new IllegalStateException("Blob content memory has been released")
    if (_content.isEmpty) loadLazily()
    new ByteArrayInputStream(_content.get)
  }
  // 将 Blob 内容写入指定文件（返回写入的字节数）
  def writeToFile(pathString: String): Long = {
    val path = Paths.get(pathString+name)
    if (_memoryReleased) throw new IllegalStateException("Blob content memory has been released")
    if (_size.isEmpty) loadLazily()
    val inputStream = getInputStream
    val outputStream = new FileOutputStream(path.toFile)
    try {
      var bytesWritten: Long = 0L
      val buffer = new Array[Byte](4096) // 4KB 缓冲区
      var bytesRead: Int = 0
      // **流式写入，避免全部加载到内存**
      while ( {
        bytesRead = inputStream.read(buffer)
        bytesRead != -1
      }) {
        outputStream.write(buffer, 0, bytesRead)
        bytesWritten += bytesRead
      }
      bytesWritten // 返回实际写入的字节数
    } finally {
      inputStream.close()
      outputStream.close()
    }
  }


  /** 获取分块迭代器 */
//  def chunkIterator: Iterator[Array[Byte]] = chunkIterator

  override def toString: String = {
    loadLazily()
    s"Blob[$name]"
  }
}