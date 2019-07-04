package kafka

import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Committer, Consumer, Producer}
import akka.kafka.{CommitterSettings, ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Source}
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class KafkaAkkaStreamTest extends FunSuite with BeforeAndAfterAll with Matchers {
  import KafkaCommon._

  val config = ConfigFactory.load("test.conf")
  val producerConfig = config.getConfig("akka.kafka.producer")
  val consumerConfig = config.getConfig("akka.kafka.consumer")
  val committerConfig = config.getConfig("akka.kafka.committer")

  implicit val system = ActorSystem.create("kafka-akka-stream", config)
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit val logger = system.log

  val topic = "kv"

  override protected def afterAll(): Unit = {
    Await.result(system.terminate, 3 seconds)
    ()
  }

  test("producer -> consumer") {
    assertTopic(topic) shouldBe true

    produceMessages(3)
    val postProduceMessageCount = countMessages(topic)

    consumeMessages()
    val postConsumeMessageCount = countMessages(topic)

    postProduceMessageCount should be >= 3
    postConsumeMessageCount shouldEqual 0
  }

  def produceMessages(count: Int): Unit = {
    val producerSettings = ProducerSettings[String, String](producerConfig, new StringSerializer, new StringSerializer)
      .withBootstrapServers(producerConfig.getString("bootstrap.servers"))

    val done = Source(1 to count)
      .map(_.toString)
      .map { string =>
        val record = new ProducerRecord[String, String] (topic, string, string)
        logger.info(s"*** Producer -> topic: $topic key: ${record.key} value: ${record.value}")
        record
      }
      .runWith(Producer.plainSink(producerSettings))
    Await.result(done, 3 seconds)
    ()
  }

  def consumeMessages(): Unit = {
    val consumerSettings = ConsumerSettings[String, String](consumerConfig, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(consumerConfig.getString("bootstrap.servers"))
      .withGroupId(consumerConfig.getString("group.id"))
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, consumerConfig.getString("auto.offset.reset"))

    val committerSettings = CommitterSettings(committerConfig)

    val done = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .map { message =>
        val record = message.record
        logger.info(s"*** Consumer -> topic: ${record.topic} partition: ${record.partition} offset: ${record.offset} key: ${record.key} value: ${record.value}")
        message.committableOffset
      }
      .toMat(Committer.sink(committerSettings))(Keep.right)
      // .mapMaterializedValue(DrainingControl.apply) // Somehow prevents message consume and commit ( with Keep.both above )
      .run
    Try(Await.result(done, 3 seconds)) // Future[Done] never completes, so times out. But messages are consumed and committed.
    ()
  }
}