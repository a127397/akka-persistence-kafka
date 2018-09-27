Kafka Plugins for Akka Persistence
==================================
This is a fork of the [Krasserm project](https://github.com/krasserm/akka-persistence-kafka). It has been deployed in production and works well on our environments.

Replicated [Akka Persistence](https://doc.akka.io/docs/akka/current/persistence.html) journal and snapshot store backed by [Apache Kafka](http://kafka.apache.org/).  
Derived from [Martin Krasser implementation](https://github.com/krasserm/akka-persistence-kafka).

Dependency
----------

To include the Kafka plugins into your `sbt` project, add the following lines to your `build.sbt` file:

    "worldline bintray" at "https://dl.bintray.com/worldline-messaging-org/maven"

    libraryDependencies += "com.github.krasserm" %% "akka-persistence-kafka" % “0.7.0”

This version of `akka-persistence-kafka` depends on Kafka 1.1.0, Akka 2.5.13.

Usage hints
-----------

Kafka does not permanently store log entries but rather deletes them after a configurable _retention time_ which defaults to 7 days in Kafka 1.1.0. Therefore, applications need to take snapshots of their persistent actors at intervals that are smaller than the configured retention time (for example, every 3 days). This ensures that persistent actors can always be recovered successfully. 

Alternatively, the retention time can be set to a maximum value so that Kafka will never delete old entries. In this case, all events written by a single persistent actor must fit on a single node. This is a limitation of the current implementation which may be removed in later versions. However, this limitation is likely not relevant when running Kafka with default (or comparable) retention times and taking snapshots.

The latest snapshot of a persistent actor is never deleted if [log compaction](http://kafka.apache.org/documentation.html#compaction) is enabled. See also section [Configuration hints](#configuration-hints) for details how to properly configure Kafka for being used with the storage plugins.  

Journal plugin
--------------

### Activation 

To activate the journal plugin, add the following line to `application.conf`:

    akka.persistence.journal.plugin = "kafka-journal"

This will run the journal plugin with default settings and connect to a Kafka instance running on `localhost:9092`. The Kafka connect string can be customized with the `kafka-journal.producer.bootstrap.servers`, `kafka-journal.event.producer.bootstrap.servers` and `kafka-journal.consumer.bootstrap.servers` configuration keys (see also section [Kafka cluster](#kafka-cluster)). Recommended Kafka broker configurations are given in section [Configuration hints](#configuration-hints).

### Use cases 

- Akka Persistence [journal plugin](http://doc.akka.io/docs/akka/2.3.9/scala/persistence.html#journal-plugin-api) (obvious).
- Event publishing to [user-defined topics](#user-defined-topics).
- Event consumption from user-defined topics by [external consumers](#external-consumers).  

### Journal topics

For each persistent actor, the plugin creates a Kafka topic where the topic name equals the actor's `persistenceId` (only if it contains alphanumeric, `.`, `-` or `_` characters, otherwise, all other characters are replaced by `_`). Events published to these topics are serialized `akka.persistence.PersistentRepr` objects (see [journal plugin API](http://doc.akka.io/docs/akka/2.3.9/scala/persistence.html#journal-plugin-api)). Serialization of `PersistentRepr` objects can be [customized](http://doc.akka.io/docs/akka/2.3.11/scala/persistence.html#custom-serialization). Journal topics are mainly intended for internal use (for recovery of persistent actors) but can also be [consumed externally](#external-consumers). 

### User-defined topics

The journal plugin can also publish events to user-defined topics. By default, all events generated by all persistent actors are published to a single `events` topic. This topic is intended for [external consumption](#external-consumers) only. Events published to user-defined topics are serialized `Event` objects

```scala
package akka.persistence.kafka

/**
 * Event published to user-defined topics.
 *
 * @param persistenceId Id of the persistent actor that generates event `data`.
 * @param sequenceNr Sequence number of the event.
 * @param data Event data generated by a persistent actor.
 */
case class Event(persistenceId: String, sequenceNr: Long, data: Any)
```

where `data` is the actual event written by a persistent actor (by calling `persist` or `persistAsync`), `sequenceNr` is the event's sequence number and `persistenceId` the id of the persistent actor. `Event` objects are serialized with a [protobuf](https://github.com/google/protobuf) serializer and event `data` serialization can be customized with a [user-defined serializer](http://doc.akka.io/docs/akka/2.3.11/scala/persistence.html#custom-serialization) in the same way as for [journal topics](#journal-topics). Custom serializer configurations always apply to both, journal topics and user-defined topics.

For publishing events to user-defined topics the journal plugin uses an `EventTopicMapper`: 

```scala
package akka.persistence.kafka

/**
 * Defines a mapping of events to user-defined topics.
 */
trait EventTopicMapper {
  /**
   * Maps an event to zero or more topics.
   *
   * @param event event to be mapped.
   * @return a sequence of topic names.
   */
  def topicsFor(event: Event): immutable.Seq[String]
}
```

The default mapper is `DefaultEventTopicMapper` which maps all events to the `events` topic. It is configured in the [reference configuration](#reference-configuration) as follows: 

    kafka-journal.event.producer.topic.mapper.class = "akka.persistence.kafka.DefaultEventTopicMapper"

To customize the mapping of events to user-defined topics, applications can implement and configure a custom `EventTopicMapper`. For example, in order to publish

- events from persistent actor `a` to topics `topic-a-1` and `topic-a-2` and
- events from persistent actor `b` to topic `topic-b` 

and to turn of publishing of events from all other actors, one would implement the following `ExampleEventTopicMapper`

```scala
package akka.persistence.kafka.example

class ExampleEventTopicMapper extends EventTopicMapper {
  def topicsFor(event: Event): Seq[String] = event.persistenceId match {
    case "a" => List("topic-a-1", "topic-a-2")
    case "b" => List("topic-b")
    case _   => Nil
  }
```

and configure it in `application.conf`:

    kafka-journal.event.producer.topic.mapper.class = "akka.persistence.kafka.example.ExampleEventTopicMapper"

To turn off publishing events to user-defined topics, the `EmptyEventTopicMapper` should be configured.

    kafka-journal.event.producer.topic.mapper.class = "akka.persistence.kafka.EmptyEventTopicMapper"

### External consumers

The following example shows how to consume `Event`s from a user-defined topic with name `topic-a-2` (see [previous](#user-defined-topics) example) using Kafka's [high-level consumer API](http://kafka.apache.org/documentation.html#highlevelconsumerapi):
  
```scala
import java.util.Properties

import akka.persistence.kafka.{EventDecoder, Event}

import kafka.consumer.{Consumer, ConsumerConfig}
import kafka.serializer.StringDecoder

val props = new Properties()
props.put("group.id", "consumer-1")
props.put("zookeeper.connect", "localhost:2181")
// ...

val system = ActorSystem("consumer")

val consConn = Consumer.create(new ConsumerConfig(props))
val streams = consConn.createMessageStreams(Map("topic-a-2" -> 1),
  keyDecoder = new StringDecoder, valueDecoder = new EventDecoder(system))

streams("topic-a-2")(0).foreach { mm =>
  val event: Event = mm.message
  println(s"consumed ${event}")
}
```  

Applications may also consume serialized `PersistentRepr` objects from journal topics and deserialize them with Akka's serialization extension: 

```scala
import java.util.Properties

import akka.actor._
import akka.persistence.PersistentRepr
import akka.serialization.SerializationExtension

import com.typesafe.config.ConfigFactory

import kafka.consumer.{Consumer, ConsumerConfig}
import kafka.serializer.{DefaultDecoder, StringDecoder}

val props = new Properties()
props.put("group.id", "consumer-2")
props.put("zookeeper.connect", "localhost:2181")
// ...

val system = ActorSystem("example")
val extension = SerializationExtension(system)

val consConn = Consumer.create(new ConsumerConfig(props))
val streams = consConn.createMessageStreams(Map("a" -> 1),
  keyDecoder = new StringDecoder, valueDecoder = new DefaultDecoder)

streams("a")(0).foreach { mm =>
  val persistent: PersistentRepr = extension.deserialize(mm.message, classOf[PersistentRepr]).get
  println(s"consumed ${persistent}")
}
```

There are many other libraries that can be used to consume (event) streams from Kafka topics, such as [Spark Streaming](http://spark.apache.org/docs/latest/streaming-programming-guide.html), to mention only one example.    

### Implementation notes

- During initialization, the journal plugin fetches cluster metadata from Zookeeper which may take up to a few seconds.
- The journal plugin always writes `PersistentRepr` entries to partition 0 of journal topics. This ensures that all events written by a single persistent actor are stored in correct order. Later versions of the plugin may switch to a higher partition after having written a configurable number of events to the current partition. 
- The journal plugin distributes `Event` entries to all available partitions of user-defined topics. The partition key is the event's `persistenceId` so that a partial ordering of events is preserved when consuming events from user-defined topics. In other words, events written by a single persistent actor are always consumed in correct order but the relative ordering of events from different persistent actors is not defined.  

### Current limitations

- The journal plugin does not support features that have been deprecated in Akka 2.3.4 (channels and single event deletions).
- Range deletions are not persistent (which may not be relevant for applications that configure Kafka with reasonably small retention times).

### Example source code

The complete source code of all examples from previous sections is in [Example.scala](https://github.com/krasserm/akka-persistence-kafka/blob/master/src/test/scala/akka/persistence/kafka/example/Example.scala), the corresponding configuration in [example.conf](https://github.com/krasserm/akka-persistence-kafka/blob/master/src/test/resources/example.conf).

Snapshot store plugin
---------------------

### Activation 

To activate the snapshot store plugin, add the following line to `application.conf`:

    akka.persistence.snapshot-store.plugin = "kafka-snapshot-store"

This will run the snapshot store plugin with default settings and connect to a Kafka instance running on `localhost:9092`. The Kafka connect string can be customized with the `kafka-journal.event.producer.bootstrap.servers` and `kafka-journal.consumer.bootstrap.servers` configuration keys (see also section [Kafka cluster](#kafka-cluster)). Recommended Kafka broker configurations are given in section [Configuration hints](#configuration-hints).

### Snapshot topics

For each persistent actor, the plugin creates a Kafka topic where the topic name equals the actor's `persistenceId`, prefixed by the value of the `kafka-snapshot-store.prefix` configuration key which defaults to `snapshot-`. 
For example, if an actor's `persistenceId` is `example`, its snapshots are published to topic `snapshot-example`. For persistent views, the `viewId` is taken instead of the `persistenceId`.

### Implementation notes

- During initialization, the journal plugin fetches cluster metadata from Zookeeper which may take up to a few seconds.
- The journal plugin always writes snapshots to partition 0 of snapshot topics.  

### Current limitations

- Deletions are not persistent (which may not be relevant for applications that configure Kafka with reasonably small retention times).

### Special feature

To enable dataless mode using the oldest Kafka offset given by the application to the persistence plugin
    
    kafka-snapshot-store.snapshot-dataless = true

Kafka
-----

### Kafka cluster

To connect to an existing Kafka cluster, an application must set a value for the `kafka-journal.zookeeper.connect` key in its `application.conf`:  

    kafka-journal.zookeeper.connect = "<host1>:<port1>,<host2>:<port2>,..."

If you want to run a Kafka cluster on a single node, you may find [this article](http://www.michael-noll.com/blog/2013/03/13/running-a-multi-broker-apache-kafka-cluster-on-a-single-node/) useful.

### Test server

To use the test server, the following additional dependencies must be added to `build.sbt`:

    libraryDependencies ++= Seq(
      "com.github.krasserm" %% "akka-persistence-kafka" % "0.4" % "test" classifier "tests",
      "org.apache.curator" % "curator-test" % "2.7.1" % "test"
    )

This makes the `TestServer` class available which can be used to start a single Kafka and Zookeeper instance:

```scala
import akka.persistence.kafka.server.TestServer

// start a local Kafka and Zookeeper instance
val server = new TestServer() 

// use the local instance
// ...

// and stop it
server.stop()
```

The `TestServer` configuration can be customized with the `test-server.*` configuration keys (see [reference configuration](#reference-configuration) for details).

### Configuration hints

The following [broker configurations](http://kafka.apache.org/documentation.html#brokerconfigs) are recommended for being used with the storage plugins:

- `num.partitions` should be set to `1` by default because the plugins only write to partition 0 of [journal topics](#journal-topics) and [snapshot topics](#snapshot-topics). If a higher number of partitions is needed for [user-defined topics](#user-defined-topics) (e.g. for scalability or throughput reasons) then this should be configured manually with the `kafka-topics` command line tool. 
- `default.replication.factor` should be set to at least `2` for high-availability of topics created by the plugins.
- `message.max.bytes` and `replica.fetch.max.bytes` should be set to a value that is larger than the largest snapshot size. The default value is `1024 * 1024` which may be large enough for journal entries but likely to small for snapshots. When changing these settings make sure to also set `kafka-snapshot-store.consumer.fetch.max.bytes` and `kafka-journal.consumer.fetch.max.bytes` to this value.     
- `log.cleanup.policy` must be set to `"compact"` otherwise the most recent snapshot may be deleted if the retention time is exceeded and complete state recovery of persistent actors is not possible any more.  

See also section [Usage hints](#usage-hints).

Reference configuration
-----------------------

    akka {
      actor {
        serializers {
          kafka-event = "akka.persistence.kafka.journal.KafkaEventSerializer"
          kafka-snapshot = "akka.persistence.kafka.snapshot.KafkaSnapshotSerializer"
        }
    
        serialization-bindings {
          "akka.persistence.kafka.Event" = kafka-event
          "akka.persistence.kafka.snapshot.KafkaSnapshot" = kafka-snapshot
        }
      }
    }
    
    kafka-journal {
    
      # FQCN of the Kafka journal plugin
      class = "akka.persistence.kafka.journal.KafkaJournal"
    
      # Dispatcher for the plugin actor
      plugin-dispatcher = "kafka-journal.default-dispatcher"
    
      # Number of concurrent writers (should be <= number of available threads in
      # dispatcher).
      write-concurrency = 8
    
      # Time in milliseconds to wait for a writer to complete a batch
      writer-timeout-ms = 5000
    
      # The partition to use when publishing to and consuming from journal topics.
      partition = 0
    
      # Default dispatcher for plugin actor.
      default-dispatcher {
        type = Dispatcher
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = 2
          parallelism-max = 8
        }
      }
    
      consumer {
        # -------------------------------------------------------------------
        # Simple consumer configuration (used for message replay and reading
        # metadata).
        #
        # See http://kafka.apache.org/documentation.html#consumerconfigs
        # See http://kafka.apache.org/documentation.html#simpleconsumerapi
        # -------------------------------------------------------------------
    
        poll-timeout = 3000
      }
    
      producer {
        # -------------------------------------------------------------------
        # PersistentRepr producer (to journal topics) configuration.
        #
        # See http://kafka.apache.org/documentation.html#producerconfigs
        #
        # -------------------------------------------------------------------
    
        acks = -1
    
        # Increase if hundreds of topics are created during initialization.
        retries = 5
    
        # Increase if hundreds of topics are created during initialization.
        retry.backoff.ms = 100
    
        # Add further Kafka producer settings here, if needed.
        # ...
      }
    
      event.producer {
        # -------------------------------------------------------------------
        # Event producer (to user-defined topics) configuration.
        #
        # See http://kafka.apache.org/documentation.html#producerconfigs
        # -------------------------------------------------------------------
    
        acks = -1
    
        topic.mapper.class = "akka.persistence.kafka.DefaultEventTopicMapper"
    
    
        # Add further Kafka producer settings here, if needed.
        # ...
      }
    
    }
    
    kafka-snapshot-store {
    
      # FQCN of the Kafka snapshot store plugin
      class = "akka.persistence.kafka.snapshot.KafkaSnapshotStore"
    
      # Dispatcher for the plugin actor.
      plugin-dispatcher = "kafka-snapshot-store.default-dispatcher"
    
      # The partition to use when publishing to and consuming from snapshot topics.
      partition = 0
    
      # Topic name prefix (which prepended to persistenceId)
      prefix = "snapshot-"
    
      # If set to true snapshots with sequence numbers higher than the sequence number
      # of the latest entry in their corresponding journal topic are ignored. This is
      # necessary to recover from certain Kafka failure scenarios. Should only be set
      # to false for isolated snapshot store tests.
      ignore-orphan = true
    
      # Default dispatcher for plugin actor.
      default-dispatcher {
        type = Dispatcher
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = 2
          parallelism-max = 8
        }
      }
    
      consumer {
        # -------------------------------------------------------------------
        # New consumer configuration (used for loading snapshots and
        # reading metadata).
        #
        # See http://kafka.apache.org/documentation.html#consumerconfigs
        # See http://kafka.apache.org/documentation.html#consumerapi
        # -------------------------------------------------------------------
    
        poll-timeout = 3000
      }
    
      producer {
        # -------------------------------------------------------------------
        # Snapshot producer configuration.
        #
        # See http://kafka.apache.org/documentation.html#producerconfigs
        #
        # -------------------------------------------------------------------
    
        acks = -1
    
        # Increase if hundreds of topics are created during initialization.
        retries = 5
    
        # Increase if hundreds of topics are created during initialization.
        retry.backoff.ms = 500
    
        # Add further Kafka producer settings here, if needed.
        # ...
      }
    
    }
