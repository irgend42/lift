package ir.ast

import ir.interpreter.Interpreter._
import lift.arithmetic.ArithExpr
import ir._
import sun.reflect.generics.reflectiveObjects.NotImplementedException

/**
 * Slide pattern.
 * Create sliding windows of input
 */
case class Slide(size: ArithExpr, step: ArithExpr) extends Pattern(arity = 1) with isGenerable {
  Slide.cnt += 1
  val id = Slide.cnt

  override def toString: String = "Slide(" + size + "," + step + ")"

  override def checkType(argType: Type,
                         setType: Boolean): Type = {
    argType match {
      case ArrayType(t, n) =>
        // todo check that the sliding window always ends at the last element of the input
        //if (((n - (size - step)) % step) != Cst(0)) throw new TypeException(argType, "slide args not as")
        val innerLength = size
        val outerLength = (n - (size - step)) / step
        ArrayType(ArrayType(t, innerLength), outerLength)
      case _ => throw new TypeException(argType, "ArrayType")
    }
  }

  /**
   * Define equality operator based on ID to be able to insert [[Slide]] instances
   * into a set properly.
   *
   * @param other Another object.
   * @return True if the other object is a [[Slide]] instance with the same ID, false otherwise.
   */
  override def equals(other: Any): Boolean = other match {
    case s: Slide => s.id == id
    case _ => false
  }

  override def eval(valueMap: ValueMap, args: Any*): Vector[_] = {
    assert(args.length == arity)
    args.head match {
      case a: Vector[_] => throw new NotImplementedException()
    }
  }

  /**
   * Define hash based on the ID to identify unique instances in associative containers.
   * @return The hashCode of the id.
   */
  override def hashCode = id.hashCode()
}

object Slide {
  var cnt: Int = -1
}

object Slide2D {
  /** Symmetrical sliding */
  def apply(size: ArithExpr, step: ArithExpr): Lambda = {
    SlideND(2)(size,step)
  }

  /** Asymmetrical sliding */
  def apply(sizeRow: ArithExpr, stepRow: ArithExpr,
            sizeCol: ArithExpr, stepCol: ArithExpr): Lambda = {
    Map(Transpose()) o Slide(sizeRow, stepRow) o Map(Slide(sizeCol, stepCol))
  }
}

object Slide3D {
  /** Symmetrical sliding */
  def apply(size: ArithExpr, step: ArithExpr): Lambda = {
    SlideND(3)(size,step)
  }

  def apply(sizeX: ArithExpr, stepX: ArithExpr,
            sizeY: ArithExpr, stepY: ArithExpr,
            sizeZ: ArithExpr, stepZ: ArithExpr): Lambda = {
    Map(Map(Transpose()) o Transpose()) o
    Slide(sizeZ, stepZ) o
    Map(Map(Transpose()) o Slide(sizeY, stepY) o Map(Slide(sizeX, stepX)))
  }
}

object SlideND {

  def apply(dim: Int)(size: ArithExpr, step: ArithExpr): Lambda = {
    if(dim==1) Slide(size,step)
    else {
      GenerateIR.interleaveDimensions(dim, dim) o
        GenerateIR.applyInEveryDimUntilDimReverse(Slide(size, step), dim)
    }
  }
}

object TiledSlidedND {
  def undoTiling(dim: Int): Lambda = {
    if(dim == 1) Join()
    else GenerateIR.applyInEveryDimUntilDim(Join(), dim) o GenerateIR.interleaveDimensionsReverse(dim)
  }

  def apply(dim: Int)(size: ArithExpr, step: ArithExpr, tileStep: ArithExpr): Lambda = {
    val tileSize = (size - step) + tileStep
    undoTiling(dim) o
      GenerateIR.wrapInMaps(SlideND(dim)(size,step), dim) o
        SlideND(dim)(tileSize, tileStep)
  }
}

