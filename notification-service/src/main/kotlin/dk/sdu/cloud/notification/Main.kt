/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.notification

import com.fasterxml.jackson.annotation.JsonIgnore
import com.google.common.net.HostAndPort
import com.orbitz.consul.Consul
import dk.sdu.cloud.auth.api.RefreshingJWTAuthenticatedCloud
import dk.sdu.cloud.notification.api.NotificationServiceDescription
import dk.sdu.cloud.service.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

data class Configuration(
    private val connection: RawConnectionConfig,
    val appDatabaseUser: String,
    val appDatabasePassword: String,
    val appDatabaseUrl: String, // TODO This should be fixed
    val refreshToken: String,
    val consulHostname: String = "localhost"
) : ServerConfiguration {
    @get:JsonIgnore
    override val connConfig: ConnectionConfig
        get() = connection.processed

    override fun configure() {
        connection.configure(NotificationServiceDescription, 42110)
    }

    override fun toString(): String {
        return "Configuration(connection=$connection, appDatabaseUrl='$appDatabaseUrl', consulHostname='$consulHostname')"
    }
}

private val log = LoggerFactory.getLogger("dk.sdu.cloud.notification.MainKt")

fun main(args: Array<String>) {
    log.info("Starting notification service")

    val configuration = readConfigurationBasedOnArgs<Configuration>(args, NotificationServiceDescription, log = log)
    val kafka = KafkaUtil.createKafkaServices(configuration, log = log)

    log.info("Connecting to Service Registry")
    val serviceRegistry = ServiceRegistry(
        NotificationServiceDescription.instance(configuration.connConfig),
        Consul.builder()
            .withHostAndPort(HostAndPort.fromHost(configuration.consulHostname).withDefaultPort(8500))
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

    Server(configuration, kafka, serverProvider, serviceRegistry, cloud, args).start()
}
