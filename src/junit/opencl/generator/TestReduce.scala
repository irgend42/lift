package junit.opencl.generator

import org.junit._
import org.junit.Assert._
import opencl.generator._
import opencl.ir._
import ir._

import opencl.executor._

object TestReduce {
  @BeforeClass def before() {
    Executor.loadLibrary()
    println("Initialize the executor")
    Executor.init()
  }

  @AfterClass def after() {
    println("Shutdown the executor")
    Executor.shutdown()
  }
}

class TestReduce {

  val sumUp = UserFun("sumUp", "float sumUp(float x, float y) { return x+y; }", TupleType(Float, Float), Float)

  val id = UserFun("id", "float id(float x) { return x; }", Float, Float)

  val N = Var("N")
  val input = Input(Var("x"), ArrayType(Float, N))

  val M = Var("M")
  val tmp = Input(Var("tmp"), ArrayType(Float, M))


  @Test def SIMPLE_REDUCE_FIRST() {

    val kernel = Join() o MapWrg(
      Join() o MapLcl(ReduceSeq(sumUp)) o Split(2048)
    ) o Split(262144) o input

    val inputSize = 4194304
    val outputSize = inputSize / 2048

    val (output, runtime) = execute(kernel, Array.fill(inputSize)(1.0f), outputSize)

    assertEquals(output.size, outputSize)
    assertEquals(output.reduce(_ + _), inputSize, 0.0)
    output.map( assertEquals(_, 2048, 0.0) )

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }

  @Test def SIMPLE_REDUCE_SECOND() {

    val kernel = Join() o Join() o  MapWrg(
      MapLcl(ReduceSeq(sumUp))
    ) o Split(128) o Split(2048) o input

    val inputSize = 4194304
    val outputSize = inputSize / 2048

    val (output, runtime) = execute(kernel, Array.fill(inputSize)(1.0f), outputSize)

    assertEquals(output.size, outputSize)
    assertEquals(output.reduce(_ + _), inputSize, 0.0)
    output.map( assertEquals(_, 2048, 0.0) )

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }

  @Test def REDUCE_HOST() {

    val kernel = ReduceHost(sumUp) o input

    val inputSize = 128
    val outputSize = 1

    val inputData = Array.fill(inputSize)(util.Random.nextInt(2).toFloat)

    val (output, runtime) = execute(kernel, inputData, outputSize, 1)

    assertEquals(output.size, outputSize)
    assertEquals(output.reduce(_ + _), inputData.reduce(_ + _), 0.0)

    println("output(0) = " + output(0))
    println("runtime = " + runtime)
  }


  @Test def NVIDIA_A() {

    val firstKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
      Iterate(7)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2) ) o
      Join() o toLocal(MapLcl(MapSeq(id))) o Split(1)
    ) o Split(128) o input

    val inputSize = 1024

    val (firstOutput, firstRuntime) = {
      val outputSize = inputSize / 128

      val (output, runtime) = execute(firstKernel, Array.fill(inputSize)(1.0f), outputSize)

      assertEquals(output.size, outputSize)
      assertEquals(output.reduce(_ + _), inputSize, 0.0)

      println("first output(0) = " + output(0))
      println("first runtime = " + runtime)

      (output, runtime)
    }

    val secondKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Iterate(3)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(MapSeq(id))) o Split(1)
    ) o Split(8) o tmp

    val (secondOutput, secondRuntime) = {
      val tmpSize = firstOutput.size
      val outputSize = tmpSize / 8

      val (output, runtime) = execute(secondKernel, firstOutput, outputSize, 8)

      assertEquals(output.size, outputSize)
      assertEquals(output.reduce(_ + _), inputSize, 0.0)

      println("second output(0) = " + output(0))
      println("second runtime = " + runtime)

      (output, runtime)
    }

  }


  @Test def NVIDIA_B() {

    val kernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
      Iterate(7)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
      Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o Split(2)
    ) o Split(256) o input

    val inputSize = 1024
    val outputSize = inputSize / 256

    val (output, runtime) = execute(kernel, Array.fill(inputSize)(1.0f), outputSize)

    println("outputArray(0) = " + output(0))
    println("Runtime = " + runtime)
  }

  @Test def NVIDIA_C() {

    val firstKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Join() o MapWarp(Iterate(5)(Join() o MapLane(ReduceSeq(sumUp)) o Split(2))) o Split(32) o
        Iterate(2)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o ReorderStride() o Split(2048)
    ) o Split(262144) o input

    val inputSize = 4194304

    val (firstOutput, firstRuntime) = {
      val outputSize = inputSize / 262144

      val (output, runtime) = execute(firstKernel, Array.fill(inputSize)(1.0f), outputSize)

      assertEquals(output.size, outputSize)
      assertEquals(output.reduce(_ + _), inputSize, 0.0)

      println("first output(0) = " + output(0))
      println("first runtime = " + runtime)

      (output, runtime)
    }

    val secondKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Iterate(3)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o Split(2)
    ) o Split(16) o tmp

    val (secondOutput, secondRuntime) = {
      val tmpSize = firstOutput.size
      val outputSize = 1

      val (output, runtime) = execute(secondKernel, firstOutput, outputSize)

      assertEquals(output.size, outputSize)
      assertEquals(output.reduce(_ + _), inputSize, 0.0)

      println("second output(0) = " + output(0))
      println("second runtime = " + runtime)

      (output, runtime)
    }

  }

  @Test def NVIDIA_C_NO_WARPS() {

    val firstKernel = Join() o MapWrg(
        Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Iterate(7)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o ReorderStride() o Split(2048)
    ) o Split(262144) o input

    val secondKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Iterate(3)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o Split(2)
    ) o Split(16) o tmp

    val inputSize = 4194304
    val inputData = Array.fill(inputSize)(util.Random.nextInt(5).toFloat)

    val (firstOutput, firstRuntime) = {
      val outputSize = inputSize / 262144

      val (output, runtime) = execute(firstKernel, inputData, outputSize)

      assertEquals(output.size, outputSize)
      assertEquals(output.reduce(_ + _), inputData.reduce(_ + _), 0.1)

      println("first output(0) = " + output(0))
      println("first runtime = " + runtime)

      (output, runtime)
    }

    val (secondOutput, secondRuntime) = {
      val outputSize = 1

      val (output, runtime) = execute(secondKernel, firstOutput, 1, 16)

      assertEquals(outputSize, output.size)
      assertEquals(output.reduce(_ + _), inputData.reduce(_ + _), 0.1)

      println("second output(0) = " + output(0))
      println("second runtime = " + runtime)

      (output, runtime)
    }

  }

  @Test def AMD_A() {

    val firstKernel = Join() o asScalar() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(Vectorize(4)(id)))) o Split(1) o
      Iterate(8)(Join() o MapLcl(ReduceSeq(Vectorize(4)(sumUp))) o Split(2)) o
      Join() o toLocal(MapLcl(ReduceSeq(Vectorize(4)(sumUp)))) o Split(2)
    ) o asVector(4) o Split(2048) o input

    val secondKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Iterate(8)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o Split(2)
    ) o Split(512) o tmp

    val inputSize = 4194304

    val (firstOutput, firstRuntime) = {
      val outputSize = inputSize / 8192

      val (output, runtime) = execute(firstKernel, Array.fill(inputSize)(1.0f), outputSize)

      assertEquals(output.reduce(_ + _), inputSize, 0.0)

      println("first output(0) = " + output(0))
      println("first runtime = " + runtime)

      (output, runtime)
    }

    val (secondOutput, secondRuntime) = {
      val outputSize = 1

      val (output, runtime) = execute(secondKernel, firstOutput, 1, 16)

      assertEquals(inputSize, output.reduce(_ + _), 0.0)

      println("second output(0) = " + output(0))
      println("second runtime = " + runtime)

      (output, runtime)
    }

  }

  @Test def NVIDIA_DERIVED() {

    val firstKernel = Join() o Join() o MapWrg(
      MapLcl(ReduceSeq(sumUp)) //o ReduceStride()
      //toGlobal(MapLcl(Iterate(7)(MapSeq(id) o ReduceSeq(sumUp)) o ReduceSeq(sumUp))) o ReorderStride()
    ) o Split(128) o Split(2048) o input


    val secondKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Iterate(10)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o Split(2)
    ) o Split(2048) o tmp

    val inputSize = 4194304

    val (firstOutput, firstRuntime) = {
      val outputSize = inputSize / 2048

      val (output, runtime) = execute(firstKernel, Array.fill(inputSize)(1.0f), outputSize)

      assertEquals(output.reduce(_ + _), inputSize, 0.0)

      println("first output(0) = " + output(0))
      println("first runtime = " + runtime)

      (output, runtime)
    }

    val (secondOutput, secondRuntime) = {
      val outputSize = 1

      val (output, runtime) = execute(secondKernel, firstOutput, 1)

      assertEquals(inputSize, output.reduce(_ + _), 0.0)

      println("second output(0) = " + output(0))
      println("second runtime = " + runtime)

      (output, runtime)
    }

  }

  @Test def AMD_DERIVED() {

    val firstKernel = Join() o asScalar() o Join() o MapWrg(
      MapLcl(MapSeq(Vectorize(2)(id)) o ReduceSeq(Vectorize(2)(sumUp)) o ReorderStride())
    ) o Split(128) o asVector(2) o Split(4096) o input

    val tmp = Input(Var("tmp"), ArrayType(Float, N / 2048))

    val secondKernel = Join() o MapWrg(
      Join() o toGlobal(MapLcl(MapSeq(id))) o Split(1) o
        Iterate(6)(Join() o MapLcl(ReduceSeq(sumUp)) o Split(2)) o
        Join() o toLocal(MapLcl(ReduceSeq(sumUp))) o Split(128)
    ) o Split(8192) o tmp

    Type.check(firstKernel, NoType)
    Type.check(secondKernel, NoType)

    val firstKernelCode = OpenCLGenerator.generate(firstKernel)
    val secondKernelCode = OpenCLGenerator.generate(secondKernel)

  }

  @Test def INTEL_DERIVED() {

    val firstKernel = Join() o MapWrg(
      Join() o asScalar() o Join() o MapWarp(
        MapLane(MapSeq(Vectorize(4)(id)) o ReduceSeq(Vectorize(4)(sumUp)))
      ) o Split(1) o asVector(4) o Split(32768)
    ) o Split(32768) o input

    val tmp = Input(Var("tmp"), ArrayType(Float, N / 8192))

    val secondKernel = Join() o MapWrg(
      Join() o MapLcl(
        ReduceSeq(sumUp)
      ) o Split(2048)
    ) o Split(2048) o tmp

    Type.check(firstKernel, NoType)
    Type.check(secondKernel, NoType)

    val firstKernelCode = OpenCLGenerator.generate(firstKernel)
    val secondKernelCode = OpenCLGenerator.generate(secondKernel)

  }

  @Test def INTEL_DERIVED_NO_WARP() {

    val firstKernel = Join() o MapWrg(
      Join() o asScalar() o MapLcl(
        MapSeq(Vectorize(4)(id)) o ReduceSeq(Vectorize(4)(sumUp))
      ) o asVector(4) o Split(32768)
    ) o Split(32768) o input

    val tmp = Input(Var("tmp"), ArrayType(Float, N / 8192))

    val secondKernel = Join() o MapWrg(
      Join() o MapLcl(
        ReduceSeq(sumUp)
      ) o Split(2048)
    ) o Split(2048) o tmp

    Type.check(firstKernel, NoType)
    Type.check(secondKernel, NoType)

    val firstKernelCode = OpenCLGenerator.generate(firstKernel)
    val secondKernelCode = OpenCLGenerator.generate(secondKernel)

  }

  private def execute(kernel: CompFun, inputArray: Array[Float], outputSize: Int, wgSize: Int = 128) = {
    Type.check(kernel, NoType)

    val kernelCode = OpenCLGenerator.generate(kernel)
    println("Kernel code:")
    println(kernelCode)

    val inputSize = inputArray.size
    val inputData = global.input(inputArray)
    val outputData = global.output[Float](outputSize)

    val memArgs = OpenCLGenerator.Kernel.memory.map( mem => {
      val m = mem.mem
      if (m == kernel.funs.last.outM) inputData // this assumes that the last fun of the kernel is the input
      else if (m == kernel.outM) outputData
      else m.addressSpace match {
        case LocalMemory => local(m.size.eval())
        case GlobalMemory => global(m.size.eval())
      }
    })

    val args = memArgs :+ value(inputSize)

    val runtime = Executor.execute(kernelCode, wgSize, inputSize, args)

    val outputArray = outputData.asFloatArray()

    args.foreach(_.dispose)

    (outputArray, runtime)
  }

}
