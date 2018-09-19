/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.metadata

import com.fasterxml.jackson.annotation.JsonIgnore
import com.google.common.net.HostAndPort
import com.orbitz.consul.Consul
import dk.sdu.cloud.auth.api.RefreshingJWTAuthenticatedCloud
import dk.sdu.cloud.metadata.api.MetadataServiceDescription
import dk.sdu.cloud.service.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

data class ElasticConfiguration(
    val hostname: String,
    val port: Int = 9200,
    val scheme: String = "http"
)

data class Configuration(
    private val connection: RawConnectionConfig,
    val database: DatabaseConfiguration,
    val refreshToken: String,
    val elastic: ElasticConfiguration

) : ServerConfiguration {
    @get:JsonIgnore
    override val connConfig: ConnectionConfig
        get() = connection.processed

    override fun configure() {
        connection.configure(MetadataServiceDescription, 43100)
    }

    override fun toString(): String {
        return "Configuration(connection=$connection)"
    }
}

private val log = LoggerFactory.getLogger("dk.sdu.cloud.metadata.MainKt")

fun main(args: Array<String>) {
    log.info("Starting storage service")

    val configuration = readConfigurationBasedOnArgs<Configuration>(args, MetadataServiceDescription, log = log)
    val kafka = KafkaUtil.createKafkaServices(configuration, log = log)

    log.info("Connecting to Service Registry")
    val serviceRegistry = ServiceRegistry(
        MetadataServiceDescription.instance(configuration.connConfig),
        Consul.builder()
            .withHostAndPort(HostAndPort.fromHost("localhost").withDefaultPort(8500))
            .build()
    )
    log.info("Connected to Service Registry")

    val cloud = RefreshingJWTAuthenticatedCloud(
        defaultServiceClient(args, serviceRegistry),
        configuration.refreshToken
    )

    val engine = Netty
    val serverProvider: HttpServerProvider = { block ->
        embeddedServer(engine, port = configuration.connConfig.service.port, module = block)
    }

    log.info("Using engine: ${engine.javaClass.simpleName}")

    log.info("Connected to database")
    Database.connect(
        url = configuration.database.url,
        driver = configuration.database.driver,

        user = configuration.database.username,
        password = configuration.database.password
    )
    log.info("Connected!")

    Server(configuration, kafka, serverProvider, serviceRegistry, cloud, args).start()
}
