package link.rdcn.client.dag

import link.rdcn.struct.DataFrame

/**
 * @Author renhao
 * @Description:
 * @Data 2025/7/12 21:07
 * @Modified By:
 */
trait FlowNode

trait Transformer11 extends FlowNode with Serializable {
  def transform(dataFrame: DataFrame): DataFrame
}
case class PythonWhlFunctionNode(
                            functionId: String,
                            functionName: String,
                            whlPath: String
                            ) extends FlowNode
case class JavaCodeNode(
                   javaCode: String,
                   className: String
                   ) extends FlowNode

case class PythonCodeNode(
                     code: String
                     ) extends FlowNode
case class BinNode(
                                  functionId: String,
                                  functionName: String,
                                  binPath: String
                                ) extends FlowNode



//只为DAG执行提供dataFrameName
case class SourceNode (dataFrameName: String) extends FlowNode