package org.apache.spark

import java.io.{EOFException, File, FileInputStream, PrintWriter}

import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler._
import org.apache.spark.scheduler.SparkListenerTaskStart
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import com.google.common.io.Files

class EventReplayer(context: SparkContext, eventLogPath: String) {
  def this(context: SparkContext) =
    this(context, System.getProperty("spark.eventLogging.eventLogPath"))

  require(
    eventLogPath != null,
    "Please specify the event log path, " +
      "either by setting the \"spark.eventLogging.eventLogPath\", " +
      "or by constructing EventReplayer with a legal event log path")

  private[this] val stream =
    new EventLogInputStream(new FileInputStream(new File(eventLogPath)), context)

  private[this] val _events = new ArrayBuffer[SparkListenerEvents]

  val rdds =
    new mutable.HashMap[Int, RDD[_]] with mutable.SynchronizedMap[Int, RDD[_]]

  val tasks =
    new mutable.HashMap[(Int, Int), Task[_]] with mutable.SynchronizedMap[(Int, Int), Task[_]]

  val taskEndReasons =
    new mutable.HashMap[Task[_], TaskEndReason] with mutable.SynchronizedMap[Task[_], TaskEndReason]

  context.eventLogger.foreach(_.registerEventReplayer(this))
  loadEvents()

  def events = _events.readOnly

  def tasksForRDD(rdd: RDD[_]) =
    for {
      task <- tasks.values
      taskRDD <- task match {
        case t: ResultTask[_, _] => Some(t.rdd)
        case t: ShuffleMapTask => Some(t.rdd)
        case _ => None
      }
      if taskRDD.id == rdd.id
    } yield task

  def taskWithId(stageId: Int, partitionId: Int): Option[Task[_]] =
    tasks.get((stageId, partitionId))

  private[this] def loadEvents() {
    try {
      while (true) {
        val event = stream.readObject().asInstanceOf[SparkListenerEvents]
        appendEvent(event)
      }
    }
    catch {
      case _: EOFException => stream.close()
    }
  }

  private[this] def collectJobRDDs(job: ActiveJob) {
    def collectJobStages(stage: Stage, visited: Set[Stage]): Set[Stage] =
      if (!visited.contains(stage)) {
        (for {
          parent <- stage.parents.toSet[Stage]
          ancestor <- collectJobStages(parent, visited + stage)
        } yield ancestor) + stage
      }
      else {
        Set.empty
      }

    def collectStageRDDs(rdd: RDD[_], visited: Set[RDD[_]]): Set[RDD[_]] =
      if (!visited.contains(rdd)) {
        (for {
          dep <- rdd.dependencies.toSet[Dependency[_]]
          ancestor <- dep match {
            case _: NarrowDependency[_] => collectStageRDDs(dep.rdd, visited + rdd)
            case _ => Set.empty[RDD[_]]
          }

        } yield ancestor) + rdd
      }
      else {
        Set.empty
      }

    val jobRDDs = for {
      stage <- collectJobStages(job.finalStage, Set.empty)
      rdd <- collectStageRDDs(stage.rdd, Set.empty)
    } yield rdd

    for (rdd <- jobRDDs) {
      context.updateRddId(rdd.id)
      rdds(rdd.id) = rdd
    }
  }

  private[spark] def appendEvent(event: SparkListenerEvents) {
    _events += event

    event match {
      case SparkListenerTaskStart(task, _) =>
        tasks((task.stageId, task.partitionId)) = task

      case SparkListenerTaskEnd(task, reason, _, _) =>
        taskEndReasons(task) = reason

      case SparkListenerJobStart(job, _) =>
        // Records all RDDs within the job
        collectJobRDDs(job)

      case _ =>
    }
  }

  private[this] def rddType(rdd: RDD[_]) = rdd.getClass.getSimpleName

  /**
   * Visualizes the RDD DAG with GraphViz
   *
   * @param format Output file format, can be "pdf", "png", "svg", etc., default to "pdf".  Please
   *               refer to GraphViz documentation for all supported file formats.
   * @param path Output file path.  If `null` then a random path would be used.
   * @return The absolution file path of the output file
   */
  def visualizeRDDs(format: String = "png", path: String = null) = {
    val extension = format
    val basename =
      if (path == null)
        File.createTempFile("spark-rdds-", "").getAbsolutePath
      else
        Files.getNameWithoutExtension(new File(path).getAbsolutePath)

    val outFilePath = basename + "." + extension
    val dotFile = new File(basename + ".dot")
    val dotFilePath = dotFile.getAbsolutePath
    val dot = new PrintWriter(dotFile)

    dot.println("digraph {")
    dot.println("  node[shape=rectangle]")

    for ((_, rdd) <- rdds) {
      dot.println("  %d [label=\"#%d: %s\\n%s\"]"
        .format(rdd.id, rdd.id, rdd.getClass.getSimpleName, rdd.origin))
      for (dep <- rdd.dependencies) {
        dot.println("  %d -> %d;".format(rdd.id, dep.rdd.id))
      }
    }

    dot.println("}")
    dot.close()

    Runtime.getRuntime.exec("dot -Grankdir=BT -T%s %s -o %s"
      .format(format, dotFilePath, outFilePath)).waitFor()

    outFilePath
  }

  def printRDDs() {
    for ((id, rdd) <- rdds) {
      println("#%d: %s %s".format(id, rddType(rdd), rdd.origin))
    }
  }

  def assertForall[T: ClassManifest](rdd: RDD[_])(f: T => Boolean): RDD[T] = {
    val typedRDD = rdd.asInstanceOf[RDD[T]]
    typedRDD.postCompute(RDDAssertions.assertForall[T](rdd, f))
    typedRDD
  }

  def assertExists[T: ClassManifest](rdd: RDD[_])(f: T => Boolean): RDD[T] = {
    val typedRDD = rdd.asInstanceOf[RDD[T]]
    typedRDD.postCompute(RDDAssertions.assertExists[T](rdd, f))
    typedRDD
  }
}

private[spark] object RDDAssertions {
  def assertForall[T: ClassManifest](rdd: RDD[_], f: T => Boolean): RDD[T]#PostCompute =
    (partition: Partition, context: TaskContext, iterator: Iterator[T]) => {
      iterator.map { element =>
        if (!f(element))
          throw new AssertionError(
            """
              |RDD forall-assertion error:
              |  element: %s
              |  RDD type: %s
              |  RDD ID: %d
              |  partition: %d
            """.stripMargin.format(element, rdd.getClass.getSimpleName, rdd.id, partition.index))
        else
          element
      }
    }

  def assertExists[T: ClassManifest](rdd: RDD[_], f: T => Boolean): RDD[T]#PostCompute = {
    class ExistsIterator(underlying: Iterator[T], partition: Partition) extends Iterator[T] {
      var target: Option[T] = None

      def hasNext = underlying.hasNext

      def next() =
        if (hasNext) {
          val value = underlying.next()
          if (f(value)) {
            updateTarget(value)
          }
          if (!hasNext) {
            checkAssertion()
          }
          value
        }
        else {
          underlying.next()
        }

      def updateTarget(value: T) {
        if (target.isEmpty) {
          target = Some(value)
        }
      }

      def checkAssertion() {
        if (target.isEmpty) {
          throw new AssertionError(
            """
              |RDD exists-assertion error:
              |  RDD type: %s
              |  RDD ID: %d
              |  partition: %d
            """.stripMargin.format(rdd.getClass.getSimpleName, rdd.id, partition.index))
        }
      }
    }

    (partition: Partition, context: TaskContext, iterator: Iterator[T]) =>
      new ExistsIterator(iterator, partition)
  }
}
