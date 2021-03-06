import lift.arithmetic._
import rewriting.Rules._


package object rewriting {
  val mapLoweringRules =
    Seq(
      mapSeq,
      mapGlb,
      mapWrg,
      mapLcl,
      mapWarp,
      mapLane
    )

  val reduceLoweringRule = Seq(reduceSeq)

  val addressSpaceRules =
    Seq(
      privateMemory,
      localMemory,
      globalMemory
    )

  val simplificationRules =
    Seq(
      iterateId,
      iterate1,
      asScalarAsVectorId,
      asVectorAsScalarId,
      transposeTransposeId,
      joinSplitId,
      MacroRules.splitJoinId,
      gatherScatterId,
      scatterGatherId,
      removeEmptyMap,
      Rules.transposeMapTransposeReorder
    )

  val reduceRules =
    Seq(
      partialReduceToReduce,
      partialReduceReorder,
      partialReduce,
      partialReduceSplitJoin
    )

  val fusionRules =
    Seq(
      mapFusion,
      mapFusionWithZip,
      MacroRules.reduceMapFusion,
      reduceSeqMapSeqFusion
    )

  val fissionRules =
    Seq(
      mapFission,
      mapFissionWithZipInside,
      mapFissionWithZipOutside
    )

  val interchangeRules =
    Seq(
      mapReduceInterchange,
      mapReduceInterchangeWithZipOutside,
      mapReducePartialReduce,
      mapMapTransposeZipInside,
      mapMapTransposeZipOutside,
      mapMapInterchange,
      transposeBothSides
    )

  val idRules =
    Seq(
      addId,
      addIdForCurrentValueInReduce,
      addCopy,
      implementOneLevelOfId,
      implementIdAsDeepCopy,
      dropId
    )

  val transposeRules =
    Seq(
      mapSplitTranspose,
      mapTransposeSplit,
      transposeMapSplit,
      splitTranspose,
      mapTransposeTransposeMapTranspose
    )

  val tupleRules =
    Seq(
      tupleMap,
      tupleFission
    )

  val otherRules =
    Seq(
      gatherToScatter,
      scatterToGather,
      splitJoin,
      vectorize,
      reorderBothSidesWithStride,
      splitZip
    )

  val allRules =
        otherRules ++
        tupleRules ++
        idRules ++
        interchangeRules ++
        fissionRules ++
        fusionRules++
        reduceRules ++
        simplificationRules ++
        addressSpaceRules ++
        mapLoweringRules ++
        reduceLoweringRule

  val allRulesWithoutLowering =
    otherRules ++
      tupleRules ++
      idRules ++
      interchangeRules ++
      fissionRules ++
      fusionRules++
      reduceRules ++
      simplificationRules ++
      Seq(mapSeq,reduceSeq)



  val allRulesWithoutMapsLowering:Seq[Rule] = allRulesWithoutMapsLowering(?,?,?)
  def allRulesWithoutMapsLowering(split:ArithExpr,vectorWidth:ArithExpr,stride:ArithExpr):Seq[Rule] ={
    val rulesWithOutVariable =
      tupleRules ++
        //idRules ++
    interchangeRules ++
    fissionRules ++
    fusionRules ++
    simplificationRules ++
    Seq(partialReduce,
      gatherToScatter,
      scatterToGather,
      splitZip,
      partialReduceToReduce,
      reduceSeq,
      implementIdAsDeepCopy,
      dropId,
      addIdForCurrentValueInReduce
    )

    rulesWithOutVariable ++
    Seq(splitJoin(split),
      vectorize(vectorWidth),
      reorderBothSidesWithStride(stride),
      partialReduceReorder(stride),
      partialReduceSplitJoin(split)
    )

  }

}
