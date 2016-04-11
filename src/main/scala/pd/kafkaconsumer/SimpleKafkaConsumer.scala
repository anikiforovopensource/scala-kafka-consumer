package pd.kafkaconsumer

import java.util.Properties
import org.apache.kafka.clients.consumer.{ ConsumerRebalanceListener, ConsumerRecords, KafkaConsumer }
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.{ Deserializer, StringDeserializer }
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ Future, promise }
import scala.util.Random
import scala.util.control.NonFatal

/**
 * SimpleKafkaConsumer aims to abstract away low level Kafka polling and error handling details.
 *
 * All you have to do is extend this class and provide your own implementation of
 * `processRecords(...)` method:
 * {{{
 * class MyConsumer extends SimpleKafkaConsumer(
 *   myTopic, properties)
 * {
 *   override protected def processRecords(records: ConsumerRecords[String, String]): Unit = {
 *     for (record <- records) { println(record) }
 *   }
 * }
 * }}}
 *
 * After instantiation, call the `start()` to begin polling the configured Kafka broker.
 * Remember to call the `shutdown()` method in order to stop polling and release all the
 * assigned partitions.
 *
 * {{{
 *   val consumer = new MyConsumer
 *   consumer.start()
 *   ...
 *   consumer.shutdown()
 * }}}
 *
 * Any unhandled exceptions will cause the underlying Kafka consumer to be re-started after
 * the specified `simple-consumer.restart-on-exception-delay` interval plus a random offset.
 *
 * @param topic  The Kafka topic to connect to
 * @param kafkaConsumerProps Standard Kafka client Properties. It should contain "bootstrap.server"
 *                           and "consumer.group" as a minimum.
 * @param keyDeserializer Optional key deserializer, by default String-based
 * @param valueDeserializer Optional value deserializer, by default String-based
 * @param pollTimeout Optional timeout for the Kafka client poll() call
 * @param restartOnExceptionDelay Optional sleep time before reconnecting on an exception
 * @tparam K
 * @tparam V
 */
abstract class SimpleKafkaConsumer[K, V](
    val topic: String,
    val kafkaConsumerProps: Properties,
    val keyDeserializer: Deserializer[K] = new StringDeserializer,
    val valueDeserializer: Deserializer[V] = new StringDeserializer,
    val pollTimeout: Duration = SimpleKafkaConsumer.pollTimeout,
    val restartOnExceptionDelay: Duration = SimpleKafkaConsumer.restartOnExceptionDelay
) {
  protected val log = LoggerFactory.getLogger(this.getClass)

  private val lock = new Object
  private val shutdownPromise = promise[Unit]
  @volatile private var shutdownRequested = false
  @volatile private var currentKafkaConsumer: Option[KafkaConsumer[K, V]] = None
  @volatile private var isPollingThreadRunning = false

  /**
   * SimpleKafkaConsumer is considered to be `terminated` after `shutdown()` has been called
   * and the polling thread has stopped.
   *
   * @return true when the polling thread has stopped after a call to `shutdown()`
   */
  final def hasTerminated = shutdownRequested && !isPollingThreadRunning

  /**
   * Starts the polling thread. Once started, the consumer must be `shutdown()` to terminate.
   *
   * Any unhandled exceptions will cause the underlying Kafka consumer to be re-started after
   * the specified `restart-on-exception-delay` interval plus a random offset.
   */
  final def start(): Unit = lock.synchronized {
    if (isPollingThreadRunning) throw new IllegalStateException("Already running.")
    if (shutdownPromise.isCompleted) throw new IllegalStateException("Was shutdown.")

    log.info("Starting polling thread...")
    isPollingThreadRunning = true

    new Thread() {
      override def run(): Unit = {
        try {
          backoffOnUnhandledExceptionLoop()
        } finally {
          log.info("Shutting down polling thread.")
          lock.synchronized {
            isPollingThreadRunning = false
            shutdownPromise.success(Unit)
          }
        }
      }
    }.start()
  }

  /**
   * Will cause the polling thread to shutdown.
   *
   * @return a future that will becomes complete when shutdown is finished
   */
  def shutdown(): Future[Unit] = lock.synchronized {
    shutdownRequested = true
    currentKafkaConsumer.foreach(_.wakeup())
    lock.notifyAll()
    shutdownPromise.future
  }

  /**
   * This is the only method you need to implement in order to start consuming messages using
   * SimpleKafkaConsumer. The consumer offset is committed after every successful invocation
   * of this method. If this method throws and exception, all of the records in the failed
   * batch will be eventually retried.
   *
   * Any unhandled exceptions will cause the underlying Kafka consumer to be re-started after
   * the specified `restart-on-exception-delay` interval plus a random offset.
   *
   * To prevent auto-restart, you may be tempted to explicitly call `shutdown()` from inside your
   * exception handler. However, doing this will shutdown the SimpleKafkaConsumer permanently,
   * leaving your application in a degraded state.
   *
   * @param records result of polling Kafka, may be empty
   */
  protected def processRecords(records: ConsumerRecords[K, V]): Unit

  /**
   * Allows to inject custom ConsumerRebalanceListener. The listener code runs on the polling
   * thread, as described in
   * [[http://kafka.apache.org/090/javadoc/org/apache/kafka/clients/consumer/ConsumerRebalanceListener.html Kafka documentation]]
   *
   * @return an instance of ConsumerRebalanceListener
   */
  protected def makeRebalanceListener(): ConsumerRebalanceListener = {
    new LoggingRebalanceListener(topic, log) {
      override protected def logNewAssignment(partitions: Set[TopicPartition]): Unit = {
        val partitioningInfo = getTopicPartitioningInfo(partitions, maxLength = Some(16))
        setCurrentThreadDescription(partitioningInfo)
      }
    }
  }

  private def backoffOnUnhandledExceptionLoop(): Unit = {
    while (!shutdownRequested) {
      try {
        initializeConsumerAndEnterPollLoop()
      } catch {
        case NonFatal(e) =>
          logExceptionAndDelayRestart(e)

        case fatal: Throwable =>
          log.error("Fatal error in polling thread.", fatal)
          throw fatal
      }
    }
  }

  private def initializeConsumerAndEnterPollLoop(): Unit = {
    try {
      setCurrentThreadDescription("initializing")
      currentKafkaConsumer = Some(makeKafkaConsumer())
      initializeKafkaConsumer(currentKafkaConsumer.get)
      pollLoop(currentKafkaConsumer.get)
    } catch {
      case e: WakeupException if shutdownRequested =>
      // This is expected, suppress the exception and exit the loop.
    } finally {
      log.info("Stopping Kafka consumer.")
      currentKafkaConsumer.foreach(_.close())
      currentKafkaConsumer = None
      setCurrentThreadDescription("stopped")
    }
  }

  private def pollLoop(kafkaConsumer: KafkaConsumer[K, V]): Unit = {
    while (!shutdownRequested) {
      pollKafkaConsumer(kafkaConsumer)
    }
  }

  private def pollKafkaConsumer(kafkaConsumer: KafkaConsumer[K, V]): Unit = {
    val records = kafkaConsumer.poll(pollTimeout.toMillis)
    processRecords(records)
    if (!records.isEmpty) kafkaConsumer.commitSync()
  }

  private def logExceptionAndDelayRestart(exception: Throwable): Unit = {
    val randomDelay = Random.nextInt(restartOnExceptionDelay.toSeconds.toInt).seconds
    val restartDelayWithRandomOffset = restartOnExceptionDelay + randomDelay
    log.error("Unhandled exception, restarting kafka consumer " +
      s"in $restartDelayWithRandomOffset.", exception)
    setCurrentThreadDescription("awaiting restart")
    sleepWithInterrupt(restartDelayWithRandomOffset)
  }

  private def sleepWithInterrupt(sleepDuration: Duration): Unit = {
    try {
      lock.synchronized {
        if (!shutdownRequested) lock.wait(sleepDuration.toMillis)
      }
    } catch {
      case e: InterruptedException =>
      // This is expected, suppress the exception and return.
    }
  }

  private def makeKafkaConsumer(): KafkaConsumer[K, V] = {
    val props = overwriteConsumerProperties(kafkaConsumerProps)
    new KafkaConsumer[K, V](props, keyDeserializer, valueDeserializer)
  }

  private def initializeKafkaConsumer(kafkaConsumer: KafkaConsumer[K, V]): Unit = {
    kafkaConsumer.subscribe(Seq(topic), makeRebalanceListener())
  }

  private def overwriteConsumerProperties(consumerProps: Properties): Properties = {
    val props = consumerProps.clone().asInstanceOf[Properties]
    props.put("enable.auto.commit", "false")
    props
  }

  private def setCurrentThreadDescription(status: String): Unit = {
    Thread.currentThread.setName(s"TopicConsumer: ${topic} [$status]")
  }
}

object SimpleKafkaConsumer {
  /** Default poll timeout */
  val pollTimeout: Duration = 1 second
  /** Default restart delay */
  val restartOnExceptionDelay: Duration = 5 seconds

  /** Helper to create basic properties */
  def makeProps(
    bootstrapServer: String,
    consumerGroup: String,
    maxPartitionFetchBytes: Int
  ): Properties = {
    val props = new Properties()
    props.put("group.id", consumerGroup)
    props.put("bootstrap.servers", bootstrapServer)
    // See the comments in Chef's pd-kafka default attributes. You can tune this
    // down, but for a single consumer process it's actually not too important - if you
    // consume a single topic, you'll save this times partitions in memory which is usually
    // a big "meh".
    props.put("max.partition.fetch.bytes", maxPartitionFetchBytes.toString)
    props
  }
}
