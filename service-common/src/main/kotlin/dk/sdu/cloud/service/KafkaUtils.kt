/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.service

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.*
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.state.KeyValueStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.math.min
import kotlin.reflect.KClass

fun <K, V> StreamsBuilder.stream(description: StreamDescription<K, V>): KStream<K, V> =
    stream(description.name, Consumed.with(description.keySerde, description.valueSerde))

fun <K, V> StreamsBuilder.table(description: StreamDescription<K, V>): KTable<K, V> =
    table(description.name, Consumed.with(description.keySerde, description.valueSerde))

fun <K, V, A> StreamsBuilder.aggregate(
    description: StreamDescription<K, V>,
    tableDescription: TableDescription<K, A>,
    initializer: () -> A? = { null },
    aggregate: (K, V, A?) -> A
): KTable<K, A> {
    val materializedAs = Materialized.`as`<K, A, KeyValueStore<Bytes, ByteArray>>(tableDescription.name)
        .withKeySerde(tableDescription.keySerde)
        .withValueSerde(tableDescription.valueSerde)

    return stream(description).groupByKey(Serialized.with(description.keySerde, description.valueSerde)).aggregate(
        initializer,
        aggregate,
        materializedAs
    )
}

fun <K, V, A> KGroupedStream<K, V>.aggregate(
    target: TableDescription<K, A>,
    initializer: () -> A? = { null },
    aggregate: (K, V, A?) -> A
) {
    val materializedAs = Materialized.`as`<K, A, KeyValueStore<Bytes, ByteArray>>(target.name)
        .withKeySerde(target.keySerde)
        .withValueSerde(target.valueSerde)
    aggregate(initializer, aggregate, materializedAs)
}

fun <K, V : Any, R : V> KStream<K, V>.filterIsInstance(klass: KClass<R>) =
    filter { _, value -> klass.isInstance(value) }.mapValues {
        @Suppress("UNCHECKED_CAST")
        it as R
    }

fun <K, V> KStream<K, V>.toTable(): KTable<K, V> = groupByKey().reduce { _, newValue -> newValue }
fun <K, V> KStream<K, V>.toTable(keySerde: Serde<K>, valSerde: Serde<V>): KTable<K, V> =
    groupByKey(Serialized.with(keySerde, valSerde)).reduce { _, newValue -> newValue }

fun <K, V> KStream<K, V>.through(description: StreamDescription<K, V>): KStream<K, V> =
    through(description.name, Produced.with(description.keySerde, description.valueSerde))

fun <K, V> KStream<K, V>.to(description: StreamDescription<K, V>) {
    to(description.name, Produced.with(description.keySerde, description.valueSerde))
}

class KafkaServices(
    private val streamsConfig: Properties,
    val producer: KafkaProducer<String, String>
) {
    public fun build(block: Topology): KafkaStreams {
        return KafkaStreams(block, streamsConfig)
    }
}

@Suppress("RedundantVisibilityModifier", "MemberVisibilityCanBePrivate")
object KafkaUtil {
    public fun retrieveKafkaStreamsConfiguration(config: ConnectionConfig): Properties {
        return retrieveKafkaStreamsConfiguration(config.kafka, config.service)
    }

    public fun retrieveKafkaStreamsConfiguration(
        kafkaConnectionConfig: KafkaConnectionConfig,
        serviceConfig: ServiceConnectionConfig
    ): Properties = Properties().apply {
        this[StreamsConfig.APPLICATION_ID_CONFIG] = serviceConfig.description.name
        this[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConnectionConfig.servers.joinToString(",") { it.toString() }
        this[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "latest" // TODO This should probably be changed back
        this[StreamsConfig.APPLICATION_SERVER_CONFIG] = "${serviceConfig.hostname}:${serviceConfig.port}"

        // The defaults do not use java.io.tmpdir
        this[StreamsConfig.STATE_DIR_CONFIG] = File(System.getProperty("java.io.tmpdir"), "kafka-streams").absolutePath
    }

    public fun retrieveKafkaProducerConfiguration(config: ConnectionConfig): Properties {
        return retrieveKafkaProducerConfiguration(config.kafka)
    }

    public fun retrieveKafkaProducerConfiguration(kafkaServers: KafkaConnectionConfig): Properties = Properties().apply {
        this[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaServers.servers.joinToString(",") { it.toString() }
        this[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.qualifiedName!!
        this[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.qualifiedName!!
    }

    /**
     * Creates kafka streams based on the defaults as defined in [KafkaUtil]
     *
     * It is possible to change the configuration used by passing either [streamsConfigBody] or [producerConfigBody]
     */
    public inline fun createKafkaServices(
        configuration: ServerConfiguration,
        streamsConfigBody: (Properties) -> Unit = {},
        producerConfigBody: (Properties) -> Unit = {},
        log: Logger = LoggerFactory.getLogger(KafkaUtil::class.java)
    ): KafkaServices {
        log.info("Connecting to Kafka")
        val streamsConfig = retrieveKafkaStreamsConfiguration(configuration.connConfig).also(streamsConfigBody)
        val producerConfig = retrieveKafkaProducerConfiguration(configuration.connConfig).also(producerConfigBody)
        val producer = KafkaProducer<String, String>(producerConfig)
        log.info("Connected to Kafka")
        return KafkaServices(streamsConfig, producer)
    }
}
