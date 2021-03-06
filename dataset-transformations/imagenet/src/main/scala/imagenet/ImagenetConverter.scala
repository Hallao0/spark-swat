import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.rdd.cl._
import Array._
import scala.math._
import org.apache.spark.rdd._
import java.net._
import scala.io.Source

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.DenseVector

object ImagenetConverter {
    def main(args : Array[String]) {
        if (args.length != 2) {
            println("usage: ImagenetConverter input-dir output-dir")
            return;
        }

        val inputDir = args(0)
        val outputDir = args(1)
        val sc = get_spark_context("Imagenet Converter");

        val input : RDD[String] = sc.textFile(inputDir)
        val converted : RDD[DenseVector] = input.map(str => {
            val tokens : Array[String] = str.split(" ")
            val arr : Array[Double] = new Array[Double](tokens.length)
            var i : Int = 0
            while (i < tokens.length) {
                arr(i) = tokens(i).toDouble
                i += 1
            }
            Vectors.dense(arr).asInstanceOf[DenseVector]
        })

        converted.saveAsObjectFile(outputDir)
        sc.stop
    }

    def get_spark_context(appName : String) : SparkContext = {
        val conf = new SparkConf()
        conf.setAppName(appName)

        val localhost = InetAddress.getLocalHost
        // val localIpAddress = localhost.getHostAddress
        conf.setMaster("spark://" + localhost.getHostName + ":7077") // 7077 is the default port

        return new SparkContext(conf)
    }
}
