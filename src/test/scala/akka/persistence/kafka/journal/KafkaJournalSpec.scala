package akka.persistence.kafka.journal

import com.typesafe.config.ConfigFactory
import akka.persistence.journal.{JournalPerfSpec, JournalSpec}
import akka.persistence.kafka.server._
import akka.persistence.CapabilityFlag

class KafkaJournalSpec extends JournalPerfSpec (
  config = ConfigFactory.parseString(
    """
      |akka.persistence.journal.plugin = "kafka-journal"
      |akka.persistence.snapshot-store.plugin = "kafka-snapshot-store"
      |akka.test.single-expect-default = 10s
      |kafka-journal.event.producer.request.required.acks = 1
    """.stripMargin)) with KafkaTest {

  override def eventsCount: Int = 10 * 10

  /** Number of measurement iterations each test will be run. */
  override def measurementIterations: Int = 10

  val systemConfig = system.settings.config
  ConfigurationOverride.configApp = config.withFallback(systemConfig)

  //override def supportsAtomicPersistAllOfSeveralEvents: Boolean = false
  
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()

  override protected def supportsSerialization: CapabilityFlag = CapabilityFlag.off()

}
