package org.apache.spark.rdd.cl

import scala.reflect.ClassTag
import scala.reflect._

import java.nio.BufferOverflowException
import java.nio.DoubleBuffer

import com.amd.aparapi.internal.model.Entrypoint
import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.ClassModel.NameMatcher
import com.amd.aparapi.internal.model.HardCodedClassModels.UnparameterizedMatcher
import com.amd.aparapi.internal.model.ClassModel.FieldNameInfo
import com.amd.aparapi.internal.util.UnsafeWrapper
import com.amd.aparapi.internal.writer.KernelWriter

object PrimitiveArrayInputBufferWrapperConfig {
  val tiling : Int = 1
}

class PrimitiveArrayInputBufferWrapper[T: ClassTag](val vectorElementCapacity : Int,
        val vectorCapacity : Int, val tiling : Int, val entryPoint : Entrypoint,
        val blockingCopies : Boolean, val firstSample : T) extends InputBufferWrapper[T] {
  val classModel : ClassModel =
    entryPoint.getHardCodedClassModels().getClassModelFor(
        "scala.Array", new UnparameterizedMatcher())
  val arrayStructSize = classModel.getTotalStructSize

  var buffered : Int = 0

  val primitiveClass = firstSample.asInstanceOf[Array[_]](0).getClass
  val primitiveElementSize = if (primitiveClass.equals(classOf[Int])) {
            4
          } else if (primitiveClass.equals(classOf[Double])) {
            8
          } else if (primitiveClass.equals(classOf[Float])) {
            4
          } else {
              throw new RuntimeException("Unsupported type " + primitiveClass.getName)
          }

  var tiled : Int = 0
  val to_tile : Array[T] = new Array[T](tiling)
  val to_tile_sizes : Array[Int] = new Array[Int](tiling)

  var nativeBuffers : PrimitiveArrayNativeInputBuffers[T] = null
  var bufferPosition : Int = 0

  val overrun : Array[Option[T]] = new Array[Option[T]](tiling)
  var haveOverrun : Boolean = false

//   var sumVectorLengths : Int = 0 // PROFILE
//   var countVectors : Int = 0 // PROFILE

  override def selfAllocate(dev_ctx : Long) {
    nativeBuffers = generateNativeInputBuffer(dev_ctx).asInstanceOf[PrimitiveArrayNativeInputBuffers[T]]
  }

  override def getCurrentNativeBuffers : NativeInputBuffers[T] = nativeBuffers
  override def setCurrentNativeBuffers(set : NativeInputBuffers[_]) {
    nativeBuffers = set.asInstanceOf[PrimitiveArrayNativeInputBuffers[T]]
  }

  override def flush() {
    if (tiled > 0) {
      val nToSerialize = if (buffered + tiled > vectorCapacity)
          vectorCapacity - buffered else tiled
      val nTiled : Int =
          OpenCLBridge.serializeStridedPrimitiveArraysToNativeBuffer(
                  nativeBuffers.valuesBuffer, bufferPosition,
                  vectorElementCapacity, nativeBuffers.sizesBuffer,
                  nativeBuffers.offsetsBuffer, buffered, vectorCapacity,
                  to_tile.asInstanceOf[Array[java.lang.Object]], to_tile_sizes,
                  nToSerialize, tiling, primitiveElementSize)
      if (nTiled > 0) {
        var newBufferPosition : Int = bufferPosition + 0 +
            (tiling * (to_tile(0).asInstanceOf[Array[_]].size - 1))

        for (i <- 1 until nTiled) {
          val curr : Array[_] = to_tile(i).asInstanceOf[Array[_]]
          var pos : Int = bufferPosition + i + (tiling * (curr.size - 1))
          if (pos > newBufferPosition) {
            newBufferPosition = pos
          }
        }

        bufferPosition = newBufferPosition + 1
      }

      val nFailed = tiled - nTiled
      if (nFailed > 0) {
        for (i <- nTiled until tiled) {
          overrun(i - nTiled) = Some(to_tile(i))
        }
        haveOverrun = true
      }

      buffered += nTiled
      tiled = 0
    }
  }

  override def append(obj : Any) {
    val arr : T = obj.asInstanceOf[T]
    to_tile(tiled) = arr
    to_tile_sizes(tiled) = arr.asInstanceOf[Array[_]].length
    tiled += 1

//     sumVectorLengths += arr.length // PROFILE
//     countVectors += 1 // PROFILE

    if (tiled == tiling) {
        flush
    }
  }

  override def aggregateFrom(iterator : Iterator[T]) {
    assert(!haveOverrun)
    while (iterator.hasNext && !haveOverrun) {
      append(iterator.next)
    }
  }

  override def nBuffered() : Int = {
    if (tiled > 0) {
      flush
    }
    buffered
  }

  override def countArgumentsUsed : Int = { 6 }

  override def haveUnprocessedInputs : Boolean = {
    haveOverrun || buffered > 0
  }

  override def outOfSpace : Boolean = {
    haveOverrun
  }

  override def generateNativeInputBuffer(dev_ctx : Long) : NativeInputBuffers[T] = {
    new PrimitiveArrayNativeInputBuffers(vectorElementCapacity, vectorCapacity,
            blockingCopies, tiling, dev_ctx, primitiveElementSize,
            arrayStructSize)
  }

  override def setupNativeBuffersForCopy(limit : Int) {
    val vectorsToCopy = if (limit == -1) buffered else limit
    assert(vectorsToCopy <= buffered)
    val elementsToCopy = if (vectorsToCopy == buffered) bufferPosition else
        OpenCLBridge.getMaxOffsetOfStridedVectors(vectorsToCopy, nativeBuffers.sizesBuffer,
                nativeBuffers.offsetsBuffer, tiling) + 1
    assert(elementsToCopy <= bufferPosition)

    nativeBuffers.vectorsToCopy = vectorsToCopy
    nativeBuffers.elementsToCopy = elementsToCopy
  }

  override def transferOverflowTo(
          otherAbstract : NativeInputBuffers[_]) :
          NativeInputBuffers[T] = {
    // setupNativeBuffersForCopy must have been called beforehand
    assert(nativeBuffers.vectorsToCopy != -1 && nativeBuffers.elementsToCopy != -1)
    val other : PrimitiveArrayNativeInputBuffers[T] =
        otherAbstract.asInstanceOf[PrimitiveArrayNativeInputBuffers[T]]
    val leftoverVectors = buffered - nativeBuffers.vectorsToCopy
    val leftoverElements = bufferPosition - nativeBuffers.elementsToCopy

    if (leftoverVectors > 0) {
      OpenCLBridge.transferOverflowPrimitiveArrayBuffers(
              other.valuesBuffer, other.sizesBuffer, other.offsetsBuffer,
              nativeBuffers.valuesBuffer, nativeBuffers.sizesBuffer,
              nativeBuffers.offsetsBuffer, nativeBuffers.vectorsToCopy,
              nativeBuffers.elementsToCopy, leftoverVectors, leftoverElements,
              primitiveElementSize)
    }

    // Update number of elements in each native buffer
    other.vectorsToCopy = -1
    other.elementsToCopy = -1

    // Update the number of elements stored in this input buffer
    buffered = leftoverVectors
    bufferPosition = leftoverElements

//     System.err.println("Average vector length = " + // PROFILE
//             (sumVectorLengths.toDouble / countVectors.toDouble)) // PROFILE
//     sumVectorLengths = 0 // PROFILE
//     countVectors = 0 // PROFILE

    // Update the current native buffers
    val oldBuffers = nativeBuffers
    nativeBuffers = other
    return oldBuffers
  }

  override def reset() {
    haveOverrun = false
    var i = 0
    while (i < tiling && !overrun(i).isEmpty) {
      // TODO what if we run out of space while handling the overrun...
      append(overrun(i).get)
      overrun(i) = None
      i += 1
    }
  }

  // Returns # of arguments used
  override def tryCache(id : CLCacheID, ctx : Long, dev_ctx : Long,
      entrypoint : Entrypoint, persistent : Boolean) : Int = {
    if (OpenCLBridge.tryCache(ctx, dev_ctx, 0 + 1, id.broadcast, id.rdd,
        id.partition, id.offset, id.component, 3, persistent)) {
      val nVectors : Int = OpenCLBridge.fetchNLoaded(id.rdd, id.partition, id.offset)
      // Number of vectors
      OpenCLBridge.setIntArg(ctx, 0 + 4, nVectors)
      // Tiling
      OpenCLBridge.setIntArg(ctx, 0 + 5, tiling)

      return countArgumentsUsed
    } else {
      return -1
    }
  }
}
