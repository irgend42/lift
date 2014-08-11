package opencl.ir

import scala.collection.mutable

import ir._
import ir.FunCall

/** represents OpenCL address spaces either: local or global; UndefAddressSpace should be used in case of errors */
abstract class OpenCLAddressSpace

object LocalMemory extends OpenCLAddressSpace {
  override def toString = "local"
}

object GlobalMemory extends OpenCLAddressSpace {
  override def toString = "global"
}

object PrivateMemory extends OpenCLAddressSpace {
  override def toString = "private"
}

object UndefAddressSpace extends OpenCLAddressSpace

/** Represents memory in OpenCL as a raw collection of bytes allocated in an OpenCL address space.
  *
  * @constructor Create a new OpenCLMemory object
  * @param variable The variable associated with the memory
  * @param size The size of the memory as numbers bytes
  * @param addressSpace The address space where the memory has been allocated
  */
case class OpenCLMemory(var variable: Var, size: ArithExpr, addressSpace: OpenCLAddressSpace) extends Memory {

  // size cannot be 0 unless it is the null memory
  try {
    if (size.eval() == 0)
      throw new IllegalArgumentException
  } catch {
    case _: NotEvaluableException => // nothing to do
    case e: Exception => throw e
  }

  // noe type variable allowed in the size
  if (TypeVar.getTypeVars(size).nonEmpty)
    throw new IllegalArgumentException

  /** Debug output */
  override def toString: String = {
    this match {
      case coll: OpenCLMemoryCollection =>
        "coll(" + coll.subMemories.map(_.toString).reduce(_ + ", " + _) + " | " + coll.addressSpace + ")"
      case _ =>
        "size: " + size + "; addressspace: " + addressSpace
    }
  }
}

class OpenCLSubMemory(parent: OpenCLMemory, size: ArithExpr, accessFun: (ArithExpr) => ArithExpr)
  extends OpenCLMemory(parent.variable, size, parent.addressSpace)

class OpenCLMemoryCollection(val subMemories: Array[OpenCLMemory], override val addressSpace: OpenCLAddressSpace)
  extends OpenCLMemory(Var("Tuple"), subMemories.map(_.size).reduce(_+_), addressSpace)

object OpenCLMemoryCollection {
  def apply(addressSpace : OpenCLAddressSpace, subMemories: OpenCLMemory*) =
    new OpenCLMemoryCollection(Array(subMemories:_*), addressSpace)
}

// Allocate memory globally
class GlobalAllocator {

}
/** Represents the NULL OpenCL memory object */
object OpenCLNullMemory extends OpenCLMemory(Var("NULL"), Cst(-1), UndefAddressSpace)


object OpenCLMemory {

  def getMaxSizeInBytes(t: Type): ArithExpr = {
    ArithExpr.max(getSizeInBytes(t))
  }

  private def getSizeInBytes(t: Type): ArithExpr = {
    ExprSimplifier.simplify(
      t match {
        case st: ScalarType => st.size
        case vt: VectorType => vt.len * getSizeInBytes(vt.scalarT)
        case at: ArrayType => at.len * getSizeInBytes(at.elemT)
        case tt: TupleType => tt.elemsT.map(getSizeInBytes).reduce(_ + _)
        case _ => throw new TypeException(t, "??")
      }
    )
  }

  def asOpenCLMemory(m: Memory): OpenCLMemory = {
    m match {
      case oclm: OpenCLMemory => oclm
      case UnallocatedMemory => OpenCLNullMemory
      case _ => throw new IllegalArgumentException
    }
  }

  /** Return newly allocated memory based on the given sizes and the address space of the input memory
    *
    * @param glbOutSize Size in bytes to allocate in global memory
    * @param lclOutSize Size in bytes to allocate in local memory
    * @param addressSpace Address space for allocation
    * @return The newly allocated memory object
    */
  def allocMemory(glbOutSize: ArithExpr, lclOutSize: ArithExpr,
                  addressSpace: OpenCLAddressSpace): OpenCLMemory = {
    assert(addressSpace != UndefAddressSpace)

    addressSpace match {
      case GlobalMemory => allocGlobalMemory(glbOutSize)
      case LocalMemory => allocLocalMemory(lclOutSize)
    }
  }

  //** Return newly allocated global memory */
  def allocGlobalMemory(glbOutSize: ArithExpr): OpenCLMemory = {
    OpenCLMemory(Var(ContinousRange(Cst(0), glbOutSize)), glbOutSize, GlobalMemory)
  }

  //** Return newly allocated local memory */
  def allocLocalMemory(lclOutSize: ArithExpr): OpenCLMemory = {
    OpenCLMemory(Var(ContinousRange(Cst(0), lclOutSize)), lclOutSize, LocalMemory)
  }

  def allocPrivateMemory(size: ArithExpr): OpenCLMemory = {
    OpenCLMemory(Var(ContinousRange(Cst(0), size)), size, PrivateMemory)
  }

  /** Allocate OpenCLMemory objects for a given Fun f
    *
    * @param expr The expression for which memory should be allocated
    * @param numGlb Number of ...
    * @param numLcl Number of ..
    * @param inputMem The OpenCLMemory object to be used as input by f. Can be NULL then memory will be allocated.
    * @param outputMem The OpenCLMemory object to be used as output by f. Can be NULL then memory will be allocated.
    * @return The OpenCLMemory used as output by f
    */
  def alloc(expr: Expr, numGlb: ArithExpr = 1, numLcl: ArithExpr = 1,
            inputMem: OpenCLMemory = OpenCLNullMemory, outputMem: OpenCLMemory = OpenCLNullMemory): OpenCLMemory = {

    val result = expr match {
      case v: Value => allocValue(v)
      case p: Param => allocParam(p, inputMem)
      case call: FunCall => allocFunCall(call, numGlb, numLcl, inputMem, outputMem)
    }
    // set the output
    assert(result != OpenCLNullMemory)
    expr.outM = result

    // finally return the output
    result
  }

  private def allocValue(v: Value): OpenCLMemory = {
    if (v.inM != UnallocatedMemory) {
      v.outM = v.inM
    } else {
      // never use the input mem, always allocate private memory for the value
      v.outM = allocPrivateMemory(getSizeInBytes(v.outT))
    }

    OpenCLMemory.asOpenCLMemory(v.outM)
  }

  private def allocParam(p: Param, inputMem: OpenCLMemory): OpenCLMemory = {
    if (p.inM != UnallocatedMemory) {
      p.outM = p.inM
    } else if (inputMem != OpenCLNullMemory) {
      p.outM = inputMem
    } else throw new IllegalArgumentException("PANIC!")

    OpenCLMemory.asOpenCLMemory(p.outM)
  }

  private def allocFunCall(call: FunCall, numGlb: ArithExpr, numLcl: ArithExpr,
                           inputMem: OpenCLMemory, outputMem: OpenCLMemory): OpenCLMemory = {
    // determine the input memory of f based on the input arguments
    val inMem = determineInputMemory(call, numGlb, numLcl, inputMem)

    // size in bytes necessary to hold the result of f in global and local memory
    val maxGlbOutSize = getMaxSizeInBytes(call.outT) * numGlb
    val maxLclOutSize = getMaxSizeInBytes(call.outT) * numLcl

    // maximum length of the output array
    val maxLen = ArithExpr.max(Type.getLength(call.outT))

    // determine the output memory based on the type of f ...
    call.f match {

      case MapGlb(_) |MapWrg(_)=> allocMapGlb(call.f.asInstanceOf[AbstractMap], numGlb, numLcl, inMem, outputMem, maxLen)
      case MapLcl(_) | MapWarp(_)
           | MapLane(_) =>        allocMapLcl(call.f.asInstanceOf[AbstractMap], numGlb, numLcl, inMem, outputMem, maxLen)

      case tg: toGlobal =>        allocToGlobal(tg,   numGlb, numLcl, inMem, outputMem, maxGlbOutSize)
      case tl: toLocal =>         allocToLocal(tl,    numGlb, numLcl, inMem, outputMem, maxLclOutSize)

      case cf: CompFunDef =>      allocCompFunDef(cf, numGlb, numLcl, inMem)

      case it: Iterate =>         allocIterate(it, call.asInstanceOf[IterateCall], numGlb, numLcl, inMem)

      case l: Lambda =>           alloc(l.body,     numGlb, numLcl, inMem, outputMem)
      case fp: FPattern =>        alloc(fp.f.body,  numGlb, numLcl, inMem, outputMem)
      case r: AbstractPartRed =>  alloc(r.f.body,   numGlb, numLcl, inMem, outputMem)

      case Zip() | Split(_) | Join() | ReorderStride() | asVector(_) | asScalar()  =>
        inMem
      // ... for all remaining functions (e.g. MapSeq and RedSeq) allocate new memory if output is not yet set
      case _ =>
        allocDefault(maxGlbOutSize, maxLclOutSize, outputMem, call.outT, inMem.addressSpace)

    }
  }

  private def determineInputMemory(call: FunCall, numGlb: ArithExpr, numLcl: ArithExpr,
                                   inputMem: OpenCLMemory): OpenCLMemory = {
    val inM = call.f match {

      case r: AbstractPartRed =>
        if (call.args.length != 2) throw new NumberOfArgumentsException
        val initM = alloc(call.args(0), numGlb, numLcl, OpenCLNullMemory)
        val inputM = alloc(call.args(1), numGlb, numLcl, inputMem)
        OpenCLMemoryCollection(inputM.addressSpace, initM, inputM)

      case _ =>
        if (call.args.isEmpty)
          inputMem
        else if (call.args.length == 1)
          alloc(call.args(0), numGlb, numLcl, inputMem)
        else { // more than one argument.
        val inMs = inputMem match {
            // if the input is a collection unpack it
            case coll: OpenCLMemoryCollection =>
              assert(call.args.length == coll.subMemories.length)
              (call.args zip coll.subMemories).map({
                case (a, m) => alloc(a, numGlb, numLcl, m)
              })

            case m: OpenCLMemory => call.args.map( alloc(_, numGlb, numLcl, m) )
          }

          val noPrivateMem = inMs.filterNot(_.addressSpace == PrivateMemory)
          assert(noPrivateMem.nonEmpty)
          val addressSpace =
            if (noPrivateMem.forall(m => m.addressSpace == noPrivateMem(0).addressSpace)) noPrivateMem(0).addressSpace
            else UndefAddressSpace
          OpenCLMemoryCollection(addressSpace, inMs: _*)
        }
    }

    if (call.inM == UnallocatedMemory) {
      call.inM = inM
    }

    OpenCLMemory.asOpenCLMemory(call.inM)
  }

  private def allocMapGlb(am: AbstractMap, numGlb: ArithExpr, numLcl: ArithExpr,
                          inMem: OpenCLMemory, outputMem: OpenCLMemory, maxLen: ArithExpr): OpenCLMemory = {
    alloc(am.f.body, numGlb * maxLen, numLcl, inMem, outputMem)
  }


  private def allocMapLcl(am: AbstractMap, numGlb: ArithExpr, numLcl: ArithExpr,
                          inMem: OpenCLMemory, outputMem: OpenCLMemory, maxLen: ArithExpr): OpenCLMemory = {
    alloc(am.f.body, numGlb * maxLen, numLcl * maxLen, inMem, outputMem)
  }

  private def allocToGlobal(tg: toGlobal, numGlb: ArithExpr, numLcl: ArithExpr,
                            inMem: OpenCLMemory, outputMem: OpenCLMemory, maxGlbOutSize: ArithExpr): OpenCLMemory = {
    val mem = if (outputMem == OpenCLNullMemory || outputMem.addressSpace != GlobalMemory)
      allocGlobalMemory(maxGlbOutSize)
    else
      outputMem
    // ... recurs with fresh allocated 'mem' set as output
    alloc(tg.f.body, numGlb, numLcl, inMem, mem)
  }

  private def allocToLocal(tl: toLocal, numGlb: ArithExpr, numLcl: ArithExpr,
                           inMem: OpenCLMemory, outputMem: OpenCLMemory, maxLclOutSize: ArithExpr): OpenCLMemory = {
    val mem = if (outputMem == OpenCLNullMemory || outputMem.addressSpace != LocalMemory)
      allocLocalMemory(maxLclOutSize)
    else
      outputMem
    // ... recurs with fresh allocated 'mem' set as output
    alloc(tl.f.body, numGlb, numLcl, inMem, mem)
  }

  private def allocCompFunDef(cf: CompFunDef, numGlb: ArithExpr, numLcl: ArithExpr,
                              inMem: OpenCLMemory): OpenCLMemory = {
    // combine the parameter of the first function to call with the type inferred from the argument
    cf.funs.last.params(0).inM = inMem

    cf.funs.foldRight(inMem)((f, mem) => alloc(f.body, numGlb, numLcl, mem))
  }

  private def allocIterate(it: Iterate, call: IterateCall, numGlb: ArithExpr, numLcl: ArithExpr,
                           inMem: OpenCLMemory): OpenCLMemory = {
    // get sizes in bytes necessary to hold the input and output of the function inside the iterate
    val inSize = getMaxSizeInBytes(call.inT)
    val outSize = getMaxSizeInBytes(call.outT)
    // get the max from those two
    val largestSize = ArithExpr.max(inSize, outSize)

    // create a swap buffer
    call.swapBuffer = allocMemory(largestSize, largestSize, inMem.addressSpace)

    // recurs to allocate memory for the function(s) inside
    alloc(it.f.body, numGlb, numLcl, inMem)
  }

  private def allocDefault(maxGlbOutSize: ArithExpr, maxLclOutSize: ArithExpr,
                           outputMem: OpenCLMemory, outT: Type, addressSpace: OpenCLAddressSpace): OpenCLMemory = {
    if (outputMem == OpenCLNullMemory)
      outT match {

        // TODO: could maybe allocated in private memory (need to change slightly the allocator and add toPrivate)
        case ScalarType(_, _) | VectorType(_, _) =>
          allocMemory(maxGlbOutSize, maxLclOutSize, addressSpace)

        case _ =>
          allocMemory(maxGlbOutSize, maxLclOutSize, addressSpace)
      }
    else
      outputMem
  }

}

/** Represents an OpenCLMemory object combined with a type.
  *
  * @constructor Create a new TypedOpenCLMemory object
  * @param mem The underlying memory object
  * @param t The type associated with the memory object
  */
case class TypedOpenCLMemory(mem: OpenCLMemory, t: Type) {
  override def toString = "(" + mem.toString +"; " + t.toString + ")"
}

object TypedOpenCLMemory {
  def apply(mem: Memory, t: Type) = new TypedOpenCLMemory(OpenCLMemory.asOpenCLMemory(mem), t)

  /** Gathers all allocated memory objects of the given Fun f.
    *
    * @param expr Expression for witch the allocated memory objects should be gathered.
    * @return All memory objects which have been allocated for f.
    */
  def getAllocatedMemory(expr: Expr): Array[TypedOpenCLMemory] = {

    // recursively visit all functions and collect input and output (and swap buffer for the iterate)
    val result = Expr.visit(Array[TypedOpenCLMemory]())(expr, (exp, arr) =>
      exp match {
        case call: FunCall =>
          call.f match {
            case it: Iterate =>
              val fIter = call.asInstanceOf[IterateCall]
              arr :+
              TypedOpenCLMemory(fIter.inM, fIter.inT) :+
              TypedOpenCLMemory(fIter.outM, fIter.outT) :+
              TypedOpenCLMemory(fIter.swapBuffer, ArrayType(fIter.inT, ?))

            // exclude the user functions (they don't allocate memory and don't work on array types)
            case _: UserFunDef => arr

            case z: Zip => arr ++ call.args.map( e => TypedOpenCLMemory(e.inM, e.inT) )

            case _ => arr :+ TypedOpenCLMemory(call.inM, call.inT) :+ TypedOpenCLMemory(call.outM, call.outT)

          }
        case p: Param => arr :+ TypedOpenCLMemory(p.inM, p.inT)
        case v: Value => arr
      })

    val seen = mutable.HashSet[OpenCLMemory]()
    // remove null memory and duplicates while preserving the original order
    result.foldLeft(Array[TypedOpenCLMemory]())( (arr, mem) => {
      val m = mem.mem
      if (seen.contains(m) || m == OpenCLNullMemory || m.isInstanceOf[OpenCLMemoryCollection]) {
        arr
      } else {
        seen += m
        arr :+ mem
      }
    })
  }
}
