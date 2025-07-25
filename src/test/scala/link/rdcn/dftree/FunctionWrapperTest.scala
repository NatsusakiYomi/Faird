package link.rdcn.dftree

import link.rdcn.ConfigLoader
import link.rdcn.TestEmptyProvider.getResourcePath
import link.rdcn.dftree.FunctionWrapper.{JavaCode, PythonBin}
import link.rdcn.struct.ValueType.IntType
import link.rdcn.struct.{DataFrame, LocalDataFrame, Row, StructType}
import link.rdcn.util.AutoClosingIterator
import org.json.JSONObject
import org.junit.jupiter.api.Test

import java.nio.file.Paths
/**
 * @Author renhao
 * @Description:
 * @Data 2025/7/16 15:42
 * @Modified By:
 */
class FunctionWrapperTest {

  @Test
  def javaCodeTest(): Unit = {
    val code =
      """
        |import java.util.*;
        |import link.rdcn.util.*;
        |import link.rdcn.client.dag.UDFFunction;
        |import link.rdcn.struct.*;
        |
        |public class DynamicUDF implements UDFFunction {
        |    public DynamicUDF() {
        |        // 默认构造器，必须显式写出
        |    }
        |    @Override
        |    public link.rdcn.struct.DataFrame transform(final link.rdcn.struct.DataFrame dataFrame) {
        |            final scala.collection.Iterator<Row> iter = ((LocalDataFrame)dataFrame).stream();
        |            final scala.collection.Iterator<Row> rows =  new scala.collection.Iterator<Row>() {
        |            public boolean hasNext() {
        |                return iter.hasNext();
        |            }
        |
        |            public Row next() {
        |                Row row = (Row)iter.next();
        |                return Row.fromJavaList(Arrays.asList(row.get(0), row.get(1), 100));
        |            }
        |        };
        |                return DataUtils.getDataFrameByStream(rows);
        |            }
        |}
        |""".stripMargin
    val jo = new JSONObject()
    jo.put("type", LangType.JAVA_CODE.name)
    jo.put("javaCode", code)
    jo.put("className", "DynamicUDF")
    val javaCode = FunctionWrapper(jo).asInstanceOf[JavaCode]
    val rows = new LocalDataFrame(StructType.empty.add("id",IntType).add("value",IntType),AutoClosingIterator(Seq(Row.fromSeq(Seq(1,2))).iterator)())
    val newDataFrame = javaCode.applyToInput(rows.stream).asInstanceOf[DataFrame]
  }

  @Test
  def pythonBinTest(): Unit = {
    ConfigLoader.init(getResourcePath(""))
    val whlPath = Paths.get(ConfigLoader.fairdConfig.fairdHome, "lib", "link-0.1-py3-none-any.whl").toString
    val jo = new JSONObject()
    jo.put("type", LangType.PYTHON_BIN.name)
    jo.put("functionId", "id1")
    jo.put("functionName", "normalize")
    jo.put("whlPath", whlPath)
    val pythonBin = FunctionWrapper(jo).asInstanceOf[PythonBin]
    val rows = Seq(Row.fromSeq(Seq(1,2))).iterator
    val jep = JepInterpreterManager.getJepInterpreter("id1", whlPath)
    val newRow = pythonBin.applyToInput(rows, Some(jep)).asInstanceOf[Iterator[Row]].next()
    assert(newRow._1 == 0.33)
    assert(newRow._2 == 0.67)
  }
}

