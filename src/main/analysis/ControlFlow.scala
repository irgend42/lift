package analysis

import analysis.AccessCounts.SubstitutionMap
import lift.arithmetic._
import ir._
import ir.ast._
import opencl.generator._
import opencl.ir.pattern._

object ControlFlow {
 def apply(
    lambda: Lambda,
    localSize: NDRange = NDRange(?,?,?),
    globalSize: NDRange = NDRange(?,?,?),
    valueMap: SubstitutionMap = collection.immutable.Map()
  ) = new ControlFlow(lambda, localSize, globalSize, valueMap)
}

class ControlFlow(
  lambda: Lambda,
  localSize: NDRange,
  globalSize: NDRange,
  valueMap: SubstitutionMap
) extends Analyser(lambda, localSize, globalSize, valueMap) {

  private var ifStatements: ArithExpr = Cst(0)
  private var forStatements: ArithExpr = Cst(0)
  private var currentNesting: ArithExpr = Cst(1)

  ShouldUnroll(lambda)

  count(lambda.body)

  def getIfStatements(exact: Boolean = false) =
    getExact(ifStatements, exact)

  def getForStatements(exact: Boolean = false) =
    getExact(forStatements, exact)

  private def count(
    lambda: Lambda,
    loopVar: Var,
    arithExpr: ArithExpr,
    unrolled: Boolean): Unit = {

    val range = loopVar.range.asInstanceOf[RangeAdd]
    // TODO: Information needed elsewhere. See OpenCLGenerator
    // try to see if we really need a loop
    loopVar.range.numVals match {
      case Cst(0) => return
      case Cst(1) =>

      // TODO: See TestOclFunction.numValues and issue #62
      case numVals if range.start.min.min == Cst(0) && range.stop == Cst(1) =>
        ifStatements += currentNesting
      case _ =>
        (loopVar.range.numVals.min, loopVar.range.numVals.max) match {
          case (Cst(0),Cst(1)) =>
            // one or less iteration
            ifStatements += currentNesting

          case _  if !unrolled =>
            forStatements += currentNesting
          case _ =>
        }
    }

    currentNesting *= arithExpr
    count(lambda.body)
    currentNesting /^= arithExpr
  }

  private def count(expr: Expr): Unit = {
    expr match {
      case call@FunCall(f, args@_*) =>

        args.foreach(count)

        f match {
          case _: MapGlb | _: MapWrg =>
            val map = f.asInstanceOf[AbstractMap]
            val step = map.loopVar.range.asInstanceOf[RangeAdd].step

            val n = Type.getLength(expr.t) /^ step

            count(map.f, map.loopVar, n, unrolled = false)

          case map: MapLcl =>
            val step = map.loopVar.range.asInstanceOf[RangeAdd].step

            val n = Type.getLength(expr.t) /^ step
            val unrolled = map.shouldUnroll

            count(map.f, map.loopVar, n, unrolled)

          case mapSeq: MapSeq =>
            val n = Type.getLength(expr.t)
            count(mapSeq.f, mapSeq.loopVar, n, mapSeq.shouldUnroll)

          case reduceSeq: ReduceSeq =>
            val n = Type.getLength(args(1).t)

            count(reduceSeq.f, reduceSeq.loopVar, n, reduceSeq.shouldUnroll)

          case iterate@Iterate(n, nestedLambda) =>
            count(nestedLambda, iterate.indexVar, n, unrolled = false)

          case l: Lambda => count(l.body)
          case fp: FPattern => count(fp.f.body)

          case _ =>
        }
      case _ =>
    }
  }

}
