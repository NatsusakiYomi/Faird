package org.grapheco

import org.apache.arrow.flight.{FlightServer, Location}
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.spark.sql.Row
import org.grapheco.client.{Blob, CSVSource, DirectorySource, FairdClient}
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}
import org.grapheco.provider.MockDataFrameProvider
import org.grapheco.server.{FairdServer, FlightProducerImpl}
import org.junit.jupiter.api.{AfterAll, BeforeAll, Test}

import java.nio.charset.StandardCharsets
//import scala.sys.process.processInternal.InputStream

/**
 * @Author renhao
 * @Description:
 * @Data 2025/6/18 17:24
 * @Modified By:
 */
object ClientTest extends Logging {
  val location = Location.forGrpcInsecure("0.0.0.0", 33333)
  val allocator: BufferAllocator = new RootAllocator()
  val producer = new FlightProducerImpl(allocator, location, new MockDataFrameProvider)
  val flightServer = FlightServer.builder(allocator, location, producer).build()
  @BeforeAll
  def startServer(): Unit = {
    flightServer.start()
    println(s"Server (Location): Listening on port ${flightServer.getPort}")
  }
  @AfterAll
  def stopServer(): Unit = {
    producer.close()
    flightServer.close()
  }
}

class ClientTest {

  @Test
  def listDataSetTest(): Unit = {
    val dc = FairdClient.connect("dacp://0.0.0.0:33333")
    dc.listDataSetNames().foreach(println)
    println("---------------------------------------------------------------------------")
    dc.listDataFrameNames("unstructured").foreach(println)
    println("---------------------------------------------------------------------------")
    dc.listDataFrameNames("hdfs").foreach(println)
    println("---------------------------------------------------------------------------")
    dc.listDataFrameNames("ldbc").foreach(println)
  }

  @Test
  def dfBinarySchemaTest(): Unit = {
    import org.apache.spark.sql.types._

    val dc = FairdClient.connect("dacp://0.0.0.0:33333")
    val df = dc.open("dacp://10.0.0.1/bindata")
    var totalBytes: Long = 0L
    var realBytes: Long = 0L
    var count: Int = 0
    val batchSize = 1
    val startTime = System.currentTimeMillis()
    var start = System.currentTimeMillis()
    println("SchemaURI:" + df.getSchemaURI)
    println("---------------------------------------------------------------------------")
    println("MetaData:" + df.getMetaData)
    println("---------------------------------------------------------------------------")
    println("Schema:" + df.getSchema)
    println("---------------------------------------------------------------------------")
//    df.limit(10).map(x=>Row("-------"+x.get(0).toString, "#########"+x.get(1).toString)).foreach(row => {
//      println(row)
//    })
    df.limit(10)
      .foreach(println)
//    df.limit(20).foreach(row => {
//      //      计算当前 row 占用的字节数（UTF-8 编码）
//      //      val index = row.get(0).asInstanceOf[Int]
//      val name = row.get(0).asInstanceOf[String]
//      val blob = row.get(6).asInstanceOf[Blob]
//      val bytesLen = blob.size
//      println(row)
//    })
//    df.foreach(row => {
//      //      计算当前 row 占用的字节数（UTF-8 编码）
//      //      val index = row.get(0).asInstanceOf[Int]
//      val name = row.get(0).asInstanceOf[String]
//      val blob = row.get(6).asInstanceOf[Blob]
//      val bytesLen = blob.size
//      println(row)
//  })
  }

  @Test
  def dfApiTest(): Unit = {
    import org.apache.spark.sql.types._

    val dc = FairdClient.connect("dacp://0.0.0.0:33333")
    val df = dc.open("/bindata")
    var totalBytes: Long = 0L
    var realBytes: Long = 0L
    var count: Int = 0
    val batchSize = 1
    val startTime = System.currentTimeMillis()
    var start = System.currentTimeMillis()
    println("SchemaURI:"+df.getSchemaURI)
    println("---------------------------------------------------------------------------")
    println("MetaData:"+df.getMetaData)
    println("---------------------------------------------------------------------------")
    println("Schema:"+df.getSchema)
    println("---------------------------------------------------------------------------")
    df.limit(20).foreach(row => {
      println(row)
    })
//    df.limit(10).map(x=>Row(""+x.get(0).toString, ","+x.get(1).toString,
//      ","+x.get(2).toString, ","+x.get(3).toString,
//      ","+x.get(4).toString, ","+x.get(5).toString, ","+x.get(6).asInstanceOf[Blob].toString),
//
//    ).foreach(println)


    df.foreach(row => {
      //      计算当前 row 占用的字节数（UTF-8 编码）
      //      val index = row.get(0).asInstanceOf[Int]
      val name = row.get(0).asInstanceOf[String]
      val blob = row.get(6).asInstanceOf[Blob]



      //      val bytesLen = blob.length
      val bytesLen = blob.size
//      val in:InputStream = blob.getStream
//      in.close()
//      println(f"Received: ${blob.chunkCount} chunks, name:$name")
      totalBytes += bytesLen
      realBytes += bytesLen

      count += 1

      if (count % batchSize == 0) {
        val endTime = System.currentTimeMillis()
        val real_elapsedSeconds = (endTime - start).toDouble / 1000
        val total_elapsedSeconds = (endTime - startTime).toDouble / 1000
        val real_mbReceived = realBytes.toDouble / (1024 * 1024)
        val total_mbReceived = totalBytes.toDouble / (1024 * 1024)
        val bps = real_mbReceived / real_elapsedSeconds
        val obps = total_mbReceived / total_elapsedSeconds
        println(f"Received: $count rows, total: $total_mbReceived%.2f MB, speed: $bps%.2f MB/s")
        start = System.currentTimeMillis()
        realBytes = 0L
      }
    })
    println(f"total: ${totalBytes/(1024*1024)}%.2f MB, time: ${System.currentTimeMillis() - startTime}")
  }


  @Test
  def binaryFilesTest(): Unit = {
    import org.apache.spark.sql.types._

    val schema = new StructType()
      .add("id", IntegerType, nullable = false)
      .add("name", StringType)
      .add("bin", BinaryType)

    val dc = FairdClient.connect("dacp://0.0.0.0:33333")
    val df = dc.open("C:\\Users\\NatsusakiYomi\\Downloads\\数据")
    var totalBytes: Long = 0L
    var realBytes: Long = 0L
    var count: Int = 0
    val batchSize = 2
    val startTime = System.currentTimeMillis()
    var start = System.currentTimeMillis()
    df.foreach(row => {
      //      计算当前 row 占用的字节数（UTF-8 编码）
//      val index = row.get(0).asInstanceOf[Int]
      val name = row.get(0).asInstanceOf[String]
      val blob = row.get(1).asInstanceOf[Blob]
      //      val bytesLen = blob.length
      val bytesLen = blob.size
//      println(f"Received: ${blob.chunkCount} chunks, name:$name")
      totalBytes += bytesLen
      realBytes += bytesLen

      count += 1

      if (count % batchSize == 0) {
        val endTime = System.currentTimeMillis()
        val real_elapsedSeconds = (endTime - start).toDouble / 1000
        val total_elapsedSeconds = (endTime - startTime).toDouble / 1000
        val real_mbReceived = realBytes.toDouble / (1024 * 1024)
        val total_mbReceived = totalBytes.toDouble / (1024 * 1024)
        val bps = real_mbReceived / real_elapsedSeconds
        val obps = total_mbReceived / total_elapsedSeconds
        println(f"Received: $count rows, total: $total_mbReceived%.2f MB, speed: $bps%.2f MB/s")
        start = System.currentTimeMillis()
        realBytes = 0L
      }
    })
    println(f"total: ${totalBytes/(1024*1024)}%.2f MB, time: ${System.currentTimeMillis() - startTime}")
  }

  @Test
  def bpsTest(): Unit = {
    import org.apache.spark.sql.types._

    val schema = new StructType()
      .add("name", StringType)

    val dc = FairdClient.connect("dacp://0.0.0.0:33333")
    val df = dc.open("/Users/renhao/Downloads/MockData/hdfs")
    var totalBytes: Long = 0L
    var realBytes: Long = 0L
    var count: Int = 0
    val batchSize = 500000
    val startTime = System.currentTimeMillis()
    var start = System.currentTimeMillis()
    df.foreach(row => {
      //      计算当前 row 占用的字节数（UTF-8 编码）
      val bytesLen =
//        row.get(0).asInstanceOf[Array[Byte]].length
              row.get(0).asInstanceOf[String].getBytes(StandardCharsets.UTF_8).length

      //          row.get(1).asInstanceOf[String].getBytes(StandardCharsets.UTF_8).length

      totalBytes += bytesLen
      realBytes += bytesLen

      count += 1

      if (count % batchSize == 0) {
        val endTime = System.currentTimeMillis()
        val real_elapsedSeconds = (endTime - start).toDouble / 1000
        val total_elapsedSeconds = (endTime - startTime).toDouble / 1000
        val real_mbReceived = realBytes.toDouble / (1024 * 1024)
        val total_mbReceived = totalBytes.toDouble / (1024 * 1024)
        val bps = real_mbReceived / real_elapsedSeconds
        val obps = total_mbReceived / total_elapsedSeconds
        println(f"Received: $count rows, total: $total_mbReceived%.2f MB, speed: $bps%.2f MB/s")
        start = System.currentTimeMillis()
        realBytes = 0L
      }
    })
    println(f"total: ${totalBytes/(1024*1024)}%.2f MB, time: ${System.currentTimeMillis() - startTime}")
  }

  @Test
  def csvSourceTest(): Unit = {
    val dc = FairdClient.connect("dacp://0.0.0.0:33333")
    val schema = new StructType()
      .add("col1", StringType)
      .add("col2", StringType)
    val df = dc.open("/Users/renhao/Downloads/MockData/hdfs")
    df.limit(10).foreach(row => {
      println(row)
    })
    df.limit(10).map(x=>Row("-------"+x.get(0).toString, "#########"+x.get(1).toString)).foreach(println)
    df.limit(10).map(x=>Row("-------"+x.get(0).toString, "#########"+x.get(1).toString))
      .filter(x=>x.getString(0).startsWith("###"))
      .foreach(println)
  }
  @Test
  def csvSourceLdbcTest(): Unit = {
    val dc = FairdClient.connect("dacp://0.0.0.0:33333")
//    id|type|name|url
    val schema = new StructType()
      .add("id", StringType)
      .add("type", StringType)
      .add("name", StringType)
      .add("url", StringType)
    val df = dc.open("/Users/renhao/Downloads/MockData/ldbc")
    df.limit(10).foreach(println)
  }
}
