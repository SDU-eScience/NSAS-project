/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.auth

import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.onelogin.saml2.settings.SettingsBuilder
import com.onelogin.saml2.util.Util
import dk.sdu.cloud.auth.api.*
import dk.sdu.cloud.auth.services.OTTBlackListTable
import dk.sdu.cloud.auth.services.PersonUtils
import dk.sdu.cloud.auth.services.Principals
import dk.sdu.cloud.auth.services.RefreshTokens
import dk.sdu.cloud.auth.services.saml.KtorUtils
import dk.sdu.cloud.auth.services.saml.validateOrThrow
import dk.sdu.cloud.service.*
import io.ktor.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.experimental.runBlocking
import org.apache.kafka.clients.producer.KafkaProducer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.*

private val log = LoggerFactory.getLogger("dk.sdu.cloud.auth.ServerMainKt")

private fun loadKeysAndInsertIntoProps(properties: Properties): Pair<RSAPublicKey, RSAPrivateKey> {
    val certs =
        File("certs").takeIf { it.exists() && it.isDirectory } ?: throw IllegalStateException("Missing 'certs' folder")
    val x509Cert = File(certs, "cert.pem").takeIf { it.exists() && it.isFile }
            ?: throw IllegalStateException("Missing x509 cert. Expected at: ${certs.absolutePath} with name cert.pem")
    val privateKey = File(certs, "key.pem").takeIf { it.exists() && it.isFile }
            ?: throw IllegalStateException("Missing x509 cert. Expected at: ${certs.absolutePath} with name key.pem")

    val x509Text = x509Cert.readText()
    val privText = privateKey.readText()
    properties["onelogin.saml2.sp.x509cert"] = x509Text
    properties["onelogin.saml2.sp.privatekey"] = privText

    val loadedX509Cert = Util.loadCert(x509Text)
    val loadedPrivKey = Util.loadPrivateKey(privText)

    return Pair(
        loadedX509Cert.publicKey as RSAPublicKey,
        loadedPrivKey as RSAPrivateKey
    )
}

internal typealias HttpServerProvider = (Application.() -> Unit) -> ApplicationEngine

data class DatabaseConfiguration(
    val url: String,
    val driver: String,
    val username: String,
    val password: String
) {
    override fun toString(): String {
        return "DatabaseConfiguration(url='$url', driver='$driver', username='$username')"
    }
}

data class AuthConfiguration(
    val enablePasswords: Boolean = true,
    val enableWayf: Boolean = false,
    val database: DatabaseConfiguration,
    val production: Boolean = true,
    private val connection: RawConnectionConfig
) {
    @get:JsonIgnore
    val connConfig: ConnectionConfig
        get() = connection.processed

    internal fun configure() {
        connection.configure(AuthServiceDescription, 42300)
    }
}

fun main(args: Array<String>) {
    val configuration = run {
        log.info("Reading configuration...")
        val configMapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        val configFilePath = args.getOrNull(0) ?: "/etc/${AuthServiceDescription.name}/config.json"
        val configFile = File(configFilePath)
        log.debug("Using path: $configFilePath. This has resolved to: ${configFile.absolutePath}")
        if (!configFile.exists()) {
            throw IllegalStateException(
                "Unable to find configuration file. Attempted to locate it at: " +
                        configFile.absolutePath
            )
        }

        configMapper.readValue<AuthConfiguration>(configFile).also {
            it.configure()
            log.info("Retrieved the following configuration:")
            log.info(it.toString())
        }
    }

    val kafka = run {
        log.info("Connecting to Kafka")
        val streamsConfig = KafkaUtil.retrieveKafkaStreamsConfiguration(configuration.connConfig)
        val producer = run {
            val kafkaProducerConfig = KafkaUtil.retrieveKafkaProducerConfiguration(configuration.connConfig)
            KafkaProducer<String, String>(kafkaProducerConfig)
        }

        log.info("Connected to Kafka")
        KafkaServices(streamsConfig, producer)
    }

    log.info("Connecting to database...")
    Database.connect(
        url = configuration.database.url,
        driver = configuration.database.driver,

        user = configuration.database.username,
        password = configuration.database.password
    )
    log.info("Connected to database!")

    KtorUtils.runningInProduction = configuration.production

    when (args.getOrNull(1)) {
        null, "run-server" -> {
            log.info("Connecting to Service Registry")
            val serviceRegistry = ServiceRegistry(AuthServiceDescription.instance(configuration.connConfig))
            log.info("Connected to Service Registry")

            val serverProvider: HttpServerProvider = { block ->
                embeddedServer(Netty, port = configuration.connConfig.service.port, module = block)
            }

            val samlProperties = Properties().apply {
                load(AuthServer::class.java.classLoader.getResourceAsStream("saml.properties"))
            }

            val (_, priv) = loadKeysAndInsertIntoProps(samlProperties)
            val authSettings = SettingsBuilder().fromProperties(samlProperties).build().validateOrThrow()

            // TODO FIXME!!!
            val cloud = RefreshingJWTAuthenticatedCloud(defaultServiceClient(args, serviceRegistry), "TODO")

            AuthServer(
                jwtAlg = Algorithm.RSA256(null, priv),
                config = configuration,
                authSettings = authSettings,
                serviceRegistry = serviceRegistry,
                kafka = kafka,
                ktor = serverProvider,
                cloud = cloud
            ).start()
        }

        "generate-db" -> {
            log.info("Generating database (intended for development only)")
            log.info("Creating tables...")
            transaction {
                create(Principals, RefreshTokens, OTTBlackListTable)
            }
            log.info("OK")
        }

        "create-user" -> {
            val userEvents = kafka.producer.forStream(AuthStreams.UserUpdateStream)

            val console = System.console()
            val firstNames = console.readLine("First names: ")
            val lastName = console.readLine("Last name: ")

            val role = console.readLine("Role (String): ").let { Role.valueOf(it) }

            val email = console.readLine("Email: ")
            val password = String(console.readPassword("Password: "))

            val person = PersonUtils.createUserByPassword(firstNames, lastName, email, role, password)

            log.info("Creating user: ")
            log.info(person.toString())

            runBlocking {
                userEvents.emit(UserEvent.Created(person.id, person))
            }

            log.info("OK")
        }

        "create-api-token" -> {
            val userEvents = kafka.producer.forStream(AuthStreams.UserUpdateStream)
            val tokenEvents = kafka.producer.forStream(AuthStreams.RefreshTokenStream)

            val scanner = Scanner(System.`in`)
            print("Service name: ")
            val serviceName = scanner.nextLine()

            val token = UUID.randomUUID().toString()
            val principal = ServicePrincipal("_$serviceName", Role.SERVICE)

            runBlocking {
                userEvents.emit(UserEvent.Created(principal.id, principal))
                val event = RefreshTokenEvent.Created(token, principal.id)
                tokenEvents.emit(event)
            }

            log.info("Created a service user: ${principal.id}")
            log.info("Active refresh token: $token")
        }
    }
}
