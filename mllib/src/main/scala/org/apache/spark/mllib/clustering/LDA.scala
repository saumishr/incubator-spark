package org.apache.spark.mllib.clustering

import breeze.linalg._

import org.apache.spark.{SparkContext, Logging}
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD

class LDAModel (
    val docCounts: DenseVector[Int],
    val topicCounts: DenseVector[Int],
    val docTopicCounts: DenseMatrix[Int],
    val topicTermCounts: DenseMatrix[Int])
  extends Serializable {
  ???
}

class LDA private (
    var numTopics: Int,
    var docTopicSmoothing: Double,
    var termTopicSmoothing: Double,
    var numIteration: Int)
  extends Serializable with Logging
{
  def run(input: RDD[SparseVector[Double]], initialParameters: LDAModel): LDAModel = {
    ???
  }
}

object LDA {

  def train() = ???
  def main(args: Array[String]) {
    if (args.length != ???) {
      println("Usage: LDA <master> <input_dir> <k> <max_iterations> <mini-split>")
      System.exit(1)
    }

    val (master, inputDir, k, iters, minSplit) = (args(0), args(1), args(2).toInt, args(3).toInt, args(4).toInt)
    val sc = new SparkContext(master, "LDA")
    val data = MLUtils.loadCorpus(sc, inputDir, k, minSplit)
    val model = LDA.train()
  }
}


// vim: set ts=4 sw=4 et:
