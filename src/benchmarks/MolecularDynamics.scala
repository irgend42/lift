package benchmarks

import ir._
import opencl.ir._

class MolecularDynamics {

}

object MolecularDynamics {
  val mdCompute = UserFunDef("updateF",
    Array("f", "ipos", "jpos", "cutsq", "lj1", "lj2"),
    "{\n" +
      "  // Calculate distance\n" +
      "  float delx = ipos.x - jpos.x;\n" +
      "  float dely = ipos.y - jpos.y;\n" +
      "  float delz = ipos.z - jpos.z;\n" +
      "  float r2inv = delx*delx + dely*dely + delz*delz;\n" +
      "  // If distance is less than cutoff, calculate force\n" +
      "  if (r2inv < cutsq) {\n" +
      "    r2inv = 1.0f/r2inv;\n" +
      "    float r6inv = r2inv * r2inv * r2inv;\n" +
      "    float forceC = r2inv*r6inv*(lj1*r6inv - lj2);\n" +
      "    f.x += delx * forceC;\n" +
      "    f.y += dely * forceC;\n" +
      "    f.z += delz * forceC;\n" +
      "  }\n" +
      "  return f;\n" +
      "}\n",
    Seq(Float4, Float4, Float4, Float, Float, Float),
    Float4)

  val N = new Var("N")
  val M = new Var("M")

  val shoc = fun(
    ArrayType(Float4, N),
    ArrayType(ArrayType(Int, M), N),
    Float,
    Float,
    Float,
    (particles, neighbourIds, cutsq, lj1, lj2) =>
      Join() o MapWrg(
        MapLcl(fun(p =>
          ReduceSeq(fun((force, n) =>
            MolecularDynamics.mdCompute.apply(force, Get(p, 0), n, cutsq, lj1, lj2)
          ), Value("{0.0f, 0.0f, 0.0f, 0.0f}", Float4)) $ Filter(particles, Get(p, 1))
        ))
      ) o Split(128) $ Zip(particles, neighbourIds)
  )

  def mdScala(position: Array[(Float, Float, Float, Float)], neighbours: Array[Array[Int]], cutsq: Float, lj1: Float, lj2:Float): Array[(Float, Float, Float, Float)] = {
    val result = Array.ofDim[(Float, Float, Float, Float)](position.length)

    for (i <- 0 until position.length) {
      val ipos = position(i)
      var f = (0.0f, 0.0f, 0.0f, 0.0f)

      for (j <- 0 until neighbours(i).length) {
        val jidx = neighbours(i)(j)
        val jpos = position(jidx)

        // Calculate distance
        val delx = ipos._1 - jpos._1
        val dely = ipos._2 - jpos._2
        val delz = ipos._3 - jpos._3

        var r2inv = delx * delx + dely * dely + delz * delz

        // If distance is less than cutoff, calculate force
        if (r2inv < cutsq) {

          r2inv = 1.0f / r2inv
          val r6inv = r2inv * r2inv * r2inv
          val force = r2inv * r6inv * (lj1 * r6inv - lj2)

          f = (f._1 + delx * force, f._2 + dely * force, f._3 + delz * force, 0.0f)
        }
      }

      result(i) = f
    }
    result
  }

  def buildNeighbourList(position: Array[(Float, Float, Float, Float)], maxNeighbours: Int): Array[Array[Int]] = {

    val neighbourList = Array.ofDim[Int](position.length, maxNeighbours)

    for (i <- 0 until position.length) {
      var currDist = List[(Int, Float)]()

      for (j <- 0 until position.length) {
        if (i != j) {

          val ipos = position(i)
          val jpos = position(j)

          val delx = ipos._1 - jpos._1
          val dely = ipos._2 - jpos._2
          val delz = ipos._3 - jpos._3

          val distIJ = delx * delx + dely * dely + delz * delz

          currDist =  (j, distIJ) :: currDist
        }
      }

      currDist = currDist.sortBy(x => x._2)

      for (j <- 0 until maxNeighbours) {
        neighbourList(i)(j) = currDist(j)._1
      }
    }

    neighbourList
  }
}