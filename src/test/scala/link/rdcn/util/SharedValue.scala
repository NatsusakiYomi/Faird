package link.rdcn.util

import link.rdcn.ConfigLoader
import link.rdcn.provider.DataStreamSource
import link.rdcn.server.FlightProducerImpl
import link.rdcn.struct.DataSet
import link.rdcn.user.{AuthProvider, AuthenticatedUser, Credentials, DataOperationType, UsernamePassword}
import org.apache.arrow.flight.Location
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.jena.rdf.model.{Model, ModelFactory}

import java.nio.file.{Files, Path, Paths}
import java.util.UUID

object SharedValue {
  ConfigLoader.init(getResourcePath("/conf/faird.conf"))
  ConfigLoader.init(getResourcePath("/conf/faird.conf"))

  val adminUsername = "admin"
  val adminPassword = "admin"
  val userUsername = "user"
  val userPassword = "user"
  val anonymousUsername = "anonymous"


  val location = Location.forGrpcInsecure(ConfigLoader.fairdConfig.hostPosition, ConfigLoader.fairdConfig.hostPort)
  val allocator: BufferAllocator = new RootAllocator()
  val emptyAuthProvider = new AuthProvider {
    override def authenticate(credentials: Credentials): AuthenticatedUser = {
      null
    }

    /**
     * 判断用户是否具有某项权限
     */
    override def checkPermission(user: AuthenticatedUser, dataFrameName: String, opList: java.util.List[DataOperationType]): Boolean = ???
  }

  val emptyDataProvider: DataProviderImpl = new DataProviderImpl() {
    override val dataSetsScalaList: List[DataSet] = List.empty
    override val dataFramePaths: (String => String) = (relativePath: String) => {
      null
    }

    override def getDataStreamSource(dataFrameName: String): DataStreamSource = ???
  }

  val producer = new FlightProducerImpl(allocator, location, emptyDataProvider, emptyAuthProvider)
  val configCache = ConfigLoader.fairdConfig


  def getOutputDir(subdir: String): Path = {
    val baseDir = Paths.get(System.getProperty("user.dir")) // 项目根路径
    val outDir = baseDir.resolve("target").resolve(subdir)
    Files.createDirectories(outDir)
    outDir
  }

  def getHomeDir: Path = {
    val baseDir = Paths.get(System.getProperty("user.dir")) // 项目根路径
    val outDir = baseDir.resolve("src").resolve("main").resolve("resources")
    Files.createDirectories(outDir)
    outDir
  }

  def getResourcePath(resourceName: String): String = {
    val url = Option(getClass.getClassLoader.getResource(resourceName))
      .orElse(Option(getClass.getResource(resourceName)))
      .getOrElse(throw new RuntimeException(s"Resource not found: $resourceName"))
    url.getPath
  }

  //生成Token
  val genToken = () => UUID.randomUUID().toString


  class TestAuthenticatedUser(userName: String, token: String) extends AuthenticatedUser {
    def getUserName: String = userName
  }

  def genModel: Model = {
    ModelFactory.createDefaultModel()
  }


}
