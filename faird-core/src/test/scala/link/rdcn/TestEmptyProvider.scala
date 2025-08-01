/**
 * @Author Yomi
 * @Description:
 * @Data 2025/7/16 13:34
 * @Modified By:
 */
package link.rdcn
import link.rdcn.TestBase.{getOutputDir, getResourcePath}
import link.rdcn.provider.DataStreamSource
import link.rdcn.server.FlightProducerImpl
import link.rdcn.user.{AuthProvider, AuthenticatedUser, Credentials, DataOperationType}
import org.apache.arrow.flight.Location
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}

trait TestEmptyProvider{

}

/***
 * 用于不需要生成数据的测试的Provider
 */
object TestEmptyProvider {
  ConfigLoader.init(getResourcePath(""))

  val outputDir = getOutputDir("test_output","output")

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

  class TestAuthenticatedUser(userName: String, token: String) extends AuthenticatedUser {
    def getUserName: String = userName
  }


}
