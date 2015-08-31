import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.cl._
import Array._
import scala.math._
import org.apache.spark.rdd._
import java.net._

import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.Vectors

object SparkNN {
    def main(args : Array[String]) {
        if (args.length < 1) {
            println("usage: SparkNN cmd")
            return;
        }

        val cmd = args(0)

        if (cmd == "convert") {
            convert(args.slice(1, args.length))
        } else if (cmd == "run") {
            run_nn(args.slice(2, args.length), args(1).toBoolean)
        } else if (cmd == "check") {
            // TODO
            val correct = run_nn(args.slice(1, args.length), false)
            val actual = run_nn(args.slice(1, args.length), true)
            System.err.println("PASSED")
        }
    }

    def get_spark_context(appName : String) : SparkContext = {
        val conf = new SparkConf()
        conf.setAppName(appName)

        val localhost = InetAddress.getLocalHost
        conf.setMaster("spark://" + localhost.getHostName + ":7077") // 7077 is the default port

        return new SparkContext(conf)
    }

    def sigmoid(z : Double) : Double = {
      1.0 / (1.0 + scala.math.exp(-z))
    }

    def inv_sigmoid(a : Double) : Double = {
      -1.0 * scala.math.log((1.0 / a) - 1.0)
    }

    def sigmoid_prime(z : Double) : Double = {
      sigmoid(z) * (1.0 - sigmoid(z))
    }

    def get_nabla_w(delta : RDD[Tuple2[Int, DenseVector]],
            activation : RDD[Tuple2[Int, DenseVector]]) : RDD[Tuple2[Int, DenseVector]] = {
      delta.join(activation).map(d_with_a => {
        val id = d_with_a._1
        val d : DenseVector = d_with_a._2._1
        val a : DenseVector = d_with_a._2._2
        val layerSize = d.size
        val prevLayerSize = a.size
        val new_w : Array[Double] = new Array[Double](layerSize * prevLayerSize)

        var i = 0
        while (i < layerSize * prevLayerSize) {
          new_w(i) = 0.0
          i += 1
        }

        i = 0
        while (i < layerSize) {
            var j = 0
            while (j < prevLayerSize) {
                new_w(i * prevLayerSize + j)  += (d(i) * a(j))
                j += 1
            }
            i += 1
        }
        (id, Vectors.dense(new_w).asInstanceOf[DenseVector])
      })
    }

    def reduce_sum(rdd : RDD[Tuple2[Int, DenseVector]]) : DenseVector = {
      rdd.map(pair => pair._2).reduce(
        (a : DenseVector, b : DenseVector) => {
          val size = a.size
          val combined : Array[Double] = new Array[Double](size)
          var i = 0
          while (i < size) {
            combined(i) = a(i) + b(i)
            i += 1
          }
          Vectors.dense(combined).asInstanceOf[DenseVector]
        })
    }

    def feedForwardOneLayer(targetLayer : Int,
            srcLayer : RDD[Tuple2[Int, DenseVector]], layerSize : Int,
            prevLayerSize : Int,
            broadcastedWeights : Broadcast[Array[DenseVector]],
            broadcastedBiases : Broadcast[Array[DenseVector]]) :
            RDD[Tuple2[Int, DenseVector]] = {
      srcLayer.map(pair => {
          val id : Int = pair._1
          val datapoint : DenseVector = pair._2

          val new_arr : Array[Double] = new Array[Double](layerSize)
          var i = 0
          /*
           * For each neuron in the current layer we are computing the
           * activation for, and for each row in the weights matrix.
           */
          while (i < layerSize) {
              var acc = 0.0
              var j = 0
              /*
               * For each neuron in the previously calculated layer, for each
               * column in the weights matrix.
               */
              while (j < prevLayerSize) {
                  val weight = broadcastedWeights.value(targetLayer - 1)(i * prevLayerSize + j)
                  acc += (weight * datapoint(j))
                  j += 1
              }

              acc += broadcastedBiases.value(targetLayer - 1)(i) // bias
              // z is the value of acc here
              new_arr(i) = sigmoid(acc)
              i += 1
          }
          (id, Vectors.dense(new_arr).asInstanceOf[DenseVector])
      })
    }

    def printRDD(rdd : RDD[Tuple2[Int, DenseVector]], lbl : String) {
      val collected = rdd.collect
      System.err.println(lbl)
      for (pair <- collected) {
          System.err.println("  Input " + pair._1)
          val vec = pair._2
          System.err.print("    ")
          for (i <- 0 until vec.size) {
              System.err.print(vec(i) + " ")
          }
          System.err.println()
      }
    }

    // Return the weights and biases of each layer?
    def run_nn(args : Array[String], useSwat : Boolean) :
          Tuple2[Array[DenseVector], Array[DenseVector]] = {
        if (args.length != 7) {
            System.err.println("usage: SparkNN run info-file " +
                    "training-data-path training-correct-data-path " +
                    "testing-data-path testing-correct-data-path niters learning-rate")
            return (new Array[DenseVector](0), new Array[DenseVector](0))
        }
        /*
         * infoFilename should have one line for each layer in the neural net,
         * containing a single integer that is the number of neurons in that
         * layer. This includes the input layer and output layer.
         */
        val infoFilename = args(0)
        /*
         * Path to the input training data to use. This should be in object file
         * format and consists of DenseVector inputs, one for each input data
         * point. The dimensionality of these input vectors must equal the value
         * on the first line of infoFilename.
         */
        val trainingDataPath = args(1)
        /*
         * The expected output for each of the input points in trainingDataPath.
         * Also in object file format and containing DenseVectors, the size of
         * each of these vectors should equal the value on the last line of
         * infoFilename.
         */
        val correctDataPath = args(2)

        // Same format as trainingDataPath
        val testingDataPath = args(3)
        // Sam format as correctDataPath
        val testingCorrectDataPath = args(4)

        // Number of iters to train the neural net over
        val iters = args(5).toInt
        val learning_rate = args(6).toDouble

        val sc = get_spark_context("Spark NN");

        var rand : java.util.Random = new java.util.Random(345)

        val infoLines = scala.io.Source.fromFile(infoFilename).getLines()
        val layerDimensionalitiesList : java.util.List[Integer] = new java.util.LinkedList[Integer]()
        for (line <- infoLines) {
          layerDimensionalitiesList.add(line.toInt)
        }
        val nlayers = layerDimensionalitiesList.size
        val layerDimensionalities : Array[Int] = new Array[Int](nlayers)
        for (i <- 0 until layerDimensionalitiesList.size) {
          layerDimensionalities(i) = layerDimensionalitiesList.get(i)
        }

        /*
         * Represent a L - 1 x M x N matrix where:
         *   L = # of layers
         *   M = # of neurons in layer l
         *   N = # of neurons in layer l - 1
         * The first layer is ignored because it has no inputs, which therefore
         * have no weights.
         */
        val weights : Array[DenseVector] = new Array[DenseVector](nlayers - 1)
        for (i <- 0 until weights.length) { // for each non-input layer
            val layerMatrixSize = layerDimensionalities(i + 1) *
                layerDimensionalities(i)
            val arr : Array[Double] = new Array[Double](layerMatrixSize)
            for (j <- 0 until layerMatrixSize) {
                arr(j) = rand.nextGaussian
            }

            weights(i) = Vectors.dense(arr).asInstanceOf[DenseVector]
        }

        /*
         * Output biases for all but the first input layer (should the first
         * input layer have an output bias?)
         */
        val biases : Array[DenseVector] = new Array[DenseVector](nlayers - 1)
        for (i <- 0 until biases.length) {
            val arr : Array[Double] = new Array[Double](layerDimensionalities(i + 1))
            for (j <- 0 until layerDimensionalities(i + 1)) {
                arr(j) = rand.nextGaussian
            }
            biases(i) = Vectors.dense(arr).asInstanceOf[DenseVector]
        }

        /*
         * Element i in raw_y corresponds to the expected output for element i
         * in raw_inputs.
         */
        val raw_inputs = sc.objectFile[Tuple2[Int, DenseVector]](trainingDataPath).cache
        // no z for the input layer as its outputs are constant
        val zs = new Array[RDD[Tuple2[Int, DenseVector]]](nlayers - 1)
        val activations = new Array[RDD[Tuple2[Int, DenseVector]]](nlayers)
        activations(0) = if (useSwat)
            CLWrapper.cl[Tuple2[Int, DenseVector]](raw_inputs) else
            raw_inputs
        val raw_y = sc.objectFile[Tuple2[Int, DenseVector]](correctDataPath)
        var y = raw_y
        val n_training_datapoints = raw_inputs.count

        val testing_data = sc.objectFile[Tuple2[Int, DenseVector]](testingDataPath)
        val testing_y = sc.objectFile[Tuple2[Int, DenseVector]](testingCorrectDataPath)

        val startTime = System.currentTimeMillis

        var iter = 0
        while (iter < iters) {
          val iterStartTime = System.currentTimeMillis

          val broadcastedWeights = sc.broadcast(weights)
          val broadcastedBiases = sc.broadcast(biases)

          // Feed forward, skip first input layer
          var l = 1
          while (l < nlayers) {
              val prevLayerSize = layerDimensionalities(l - 1)
              val layerSize = layerDimensionalities(l)
              val new_activations : RDD[Tuple2[Int, DenseVector]] =
                feedForwardOneLayer(l, activations(l - 1), layerSize,
                        prevLayerSize, broadcastedWeights, broadcastedBiases).cache
              activations(l) = if (useSwat)
                    CLWrapper.cl[Tuple2[Int, DenseVector]](new_activations) else
                    new_activations
              zs(l - 1) = activations(l).map(pair => {
                  val id : Int = pair._1
                  val datapoint : DenseVector = pair._2

                  val new_arr : Array[Double] = new Array[Double](layerSize)
                  var i = 0
                  while (i < layerSize) {
                    new_arr(i) = inv_sigmoid(datapoint(i))
                    i += 1
                  }
                  (id, Vectors.dense(new_arr).asInstanceOf[DenseVector])
              })
              l += 1
          }

          // printRDD(activations(nlayers - 1), "Final layers")

          // L x M where M is the size of layer l
          val nabla_b : Array[RDD[Tuple2[Int, DenseVector]]] =
              new Array[RDD[Tuple2[Int, DenseVector]]](nlayers)
          // L x M x N where M is the size of layer l and N is the size of layer l + 1
          val nabla_w : Array[RDD[Tuple2[Int, DenseVector]]] =
              new Array[RDD[Tuple2[Int, DenseVector]]](nlayers)

          var delta : RDD[Tuple2[Int, DenseVector]] = activations(nlayers - 1)
            .join(y).map(joined => {
              val id = joined._1
              val activation : DenseVector = joined._2._1
              val y : DenseVector = joined._2._2
              val size : Int = activation.size

              var arr : Array[Double] = new Array[Double](size)
              var i : Int = 0
              while (i < size) {
                arr(i) = activation(i) - y(i) // delta
                i += 1
              }
              (id, Vectors.dense(arr).asInstanceOf[DenseVector])
            })

          // printRDD(delta, "Initial delta 1")
          // printRDD(zs(nlayers - 2), "Last Zs")

          delta = delta.join(zs(nlayers - 2)).map(joined => {
              val id = joined._1
              val inner_delta = joined._2._1
              val z = joined._2._2
              val size = inner_delta.size
              val new_arr : Array[Double] = new Array[Double](size)
              var i = 0
              while (i < size) {
                  new_arr(i) = inner_delta(i) * sigmoid_prime(z(i))
                  i += 1
              }
              (id, Vectors.dense(new_arr).asInstanceOf[DenseVector])
          })

          // printRDD(delta, "Initial delta 2")

          /*
           * Here, delta is a vector with the same length as the last neuron
           * layer.
           */
          nabla_b(nlayers - 1) = delta
          nabla_w(nlayers - 1) = get_nabla_w(delta, activations(nlayers - 2))
          l = 2
          while (l < nlayers) {
              /*
               * delta is a vector with the same length as the number of neurons
               * in layer currLayer + 1 (starting at output layer).
               *
               * zs(currLayer) is a vector with the same length as the number of
               * neurons in layer currLayer.
               *
               * broadcastedWeights(currLayer + 1) has as many rows as the
               * currLayer + 1 layer of neurons and as many columns as the
               * currLayer of neurons. This means it has as many rows as delta
               * has elements. Transposing the weights and multiplying by delta
               * produces a new vector of the same length as currLayer.
               */
              val currLayer = nlayers - l // -l in the python code
              val nextLayer = currLayer + 1
              val prevLayer = currLayer - 1
              val layerSize = layerDimensionalities(currLayer)
              val prevLayerSize = layerDimensionalities(prevLayer)
              val nextLayerSize = layerDimensionalities(nextLayer)

              delta = delta.cache
              delta = if (useSwat) CLWrapper.cl[Tuple2[Int, DenseVector]](delta)
                    else delta

              // TODO wrap?
              delta = delta.map(pair => {
                val id = pair._1
                val d : DenseVector = pair._2
                val prevArr : Array[Double] = new Array[Double](layerSize)

                var i = 0
                while (i < layerSize) {
                  // For each element in delta and each column in weights
                  var acc : Double = 0.0
                  var j = 0
                  while (j < nextLayerSize) {
                    // transposed
                    acc +=
                        (broadcastedWeights.value(nextLayer - 1)(i * nextLayerSize + j) * d(j))
                    j += 1
                  }
                  prevArr(i) = acc
                  i += 1
                }

                (id, Vectors.dense(prevArr).asInstanceOf[DenseVector])
              }).join(zs(currLayer - 1)).map(joined => {
                val id = joined._1
                val d : DenseVector = joined._2._1
                val z : DenseVector = joined._2._2
                var prevArr : Array[Double] = new Array[Double](layerSize)

                var i : Int = 0
                while (i < layerSize) {
                  prevArr(i) = d(i) * sigmoid_prime(z(i))
                  i += 1
                }

                (id, Vectors.dense(prevArr).asInstanceOf[DenseVector])
              })
              nabla_b(nlayers - l) = delta
              nabla_w(nlayers - l) = get_nabla_w(delta, activations(prevLayer))
              l += 1
          }

          /*
           * Add all of the elements in nabla_b[:nlayers - 1] to biases and all of
           * the elements in nabla_w[:nlayers - 1] to weights.
           */
          for (l <- 0 until nlayers - 1) {
            val collected_delta_b : DenseVector = reduce_sum(nabla_b(l + 1))
            assert(collected_delta_b.size == biases(l).size)
            val newBiases : Array[Double] = new Array[Double](biases(l).size)
            for (i <- 0 until collected_delta_b.size) {
              newBiases(i) = biases(l)(i) -
                  ((learning_rate / n_training_datapoints) *
                  collected_delta_b(i))
            }
            biases(l) = Vectors.dense(newBiases).asInstanceOf[DenseVector]

            val collected_delta_weights : DenseVector = reduce_sum(nabla_w(l + 1))
            assert(collected_delta_weights.size == weights(l).size)
            val newWeights : Array[Double] = new Array[Double](weights(l).size)
            for (i <- 0 until collected_delta_weights.size) {
              newWeights(i) = weights(l)(i) -
                  ((learning_rate / n_training_datapoints) *
                  collected_delta_weights(i))
            }
            weights(l) = Vectors.dense(newWeights).asInstanceOf[DenseVector]
          }

          var testing_activations = testing_data

          for (l <- 1 until nlayers) {
            val prevLayerSize = layerDimensionalities(l - 1)
            val layerSize = layerDimensionalities(l)
            testing_activations = if (useSwat)
                CLWrapper.cl[Tuple2[Int, DenseVector]](testing_activations) else
                testing_activations
            testing_activations = feedForwardOneLayer(l, testing_activations,
                    layerSize, prevLayerSize, broadcastedWeights,
                    broadcastedBiases)
          }
          val total = testing_y.count
          val ncorrect = testing_activations.join(testing_y).map(joined => {
              val id : Int = joined._1
              val x : DenseVector = joined._2._1
              val y : DenseVector = joined._2._2
              assert(x.size == y.size)
              var desired_neuron = -1
              for (i <- 0 until y.size) {
                if (y(i) != 0.0) {
                    assert(desired_neuron == -1)
                    desired_neuron = i
                }
              }
              assert(desired_neuron != -1)

              var max_neuron = -1
              var max_neuron_val = -1.0
              for (i <- 0 until x.size) {
                if (max_neuron == -1 || x(i) > max_neuron_val) {
                  max_neuron = i
                  max_neuron_val = x(i)
                }
              }
              max_neuron == desired_neuron
          }).filter(correct => correct).count

          val iterEndTime = System.currentTimeMillis

          System.err.println("iteration " + iter + ", " + ncorrect + " / " +
                  total + " correct : " + (iterEndTime - iterStartTime) + " ms")
          iter += 1
        }

        val endTime = System.currentTimeMillis
        System.err.println("Overall time = " + (endTime - startTime) + " ms")

        return (weights, biases)
    }

    def convert_file(input : String, output : String, sc : SparkContext) {
      sc.textFile(input).map(line => {
            val tokens : Array[String] = line.split(" ")
            val id = tokens(0).toInt
            val arr : Array[Double] = new Array[Double](tokens.length - 1)
            for (t <- 1 until tokens.length) {
              arr(t - 1) = tokens(t).toDouble
            }
            (id, Vectors.dense(arr).asInstanceOf[DenseVector])
          }).saveAsObjectFile(output)
    }

    def convert(args : Array[String]) {
      if (args.length != 8) {
        System.err.println("usage: SparkNN convert training-input " +
                "training-converted training-correct-input " +
                "training-correct-converted testing-input testing-converted " +
                "testing-correct-input testing-correct-converted")
        System.exit(1)
      }
      val sc = get_spark_context("Spark NN Convert");

      val trainingInput = args(0)
      val trainingOutput = args(1)
      val correctInput = args(2)
      val correctOutput = args(3)

      convert_file(trainingInput, trainingOutput, sc)
      convert_file(correctInput, correctOutput, sc)

      val testingInput = args(4)
      val testingOutput = args(5)
      val testingCorrectInput = args(6)
      val testingCorrectOutput = args(7)

      convert_file(testingInput, testingOutput, sc)
      convert_file(testingCorrectInput, testingCorrectOutput, sc)
    }
}