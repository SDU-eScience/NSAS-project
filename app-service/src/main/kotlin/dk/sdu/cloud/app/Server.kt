/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.app

import dk.sdu.cloud.app.api.AppServiceDescription
import dk.sdu.cloud.app.api.HPCStreams
import dk.sdu.cloud.app.http.AppController
import dk.sdu.cloud.app.http.JobController
import dk.sdu.cloud.app.http.ToolController
import dk.sdu.cloud.app.services.*
import dk.sdu.cloud.app.services.ssh.SSHConnectionPool
import dk.sdu.cloud.auth.api.JWTProtection
import dk.sdu.cloud.auth.api.RefreshingJWTAuthenticatedCloud
import dk.sdu.cloud.auth.api.protect
import dk.sdu.cloud.service.*
import io.ktor.application.install
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class Server(
    private val kafka: KafkaServices,
    private val serviceRegistry: ServiceRegistry,
    private val cloud: RefreshingJWTAuthenticatedCloud,
    private val config: HPCConfig,
    private val ktor: HttpServerProvider
) {
    private var initialized = false

    private lateinit var httpServer: ApplicationEngine
    private lateinit var kStreams: KafkaStreams
    private lateinit var scheduledExecutor: ScheduledExecutorService
    private lateinit var slurmPollAgent: SlurmPollAgent

    fun start() {
        if (initialized) throw IllegalStateException("Already started!")

        val instance = AppServiceDescription.instance(config.connConfig)

        log.info("Init Core Services")
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

        log.info("Init Application Services")
        val sshPool = SSHConnectionPool(config.ssh)
        val sbatchGenerator = SBatchGenerator()
        slurmPollAgent = SlurmPollAgent(sshPool, scheduledExecutor, 0L, 15L, TimeUnit.SECONDS)

        val jobDao = JobsDAO()
        val jobExecutionService = JobExecutionService(
            cloud,
            kafka.producer.forStream(HPCStreams.appEvents),
            sbatchGenerator,
            jobDao,
            slurmPollAgent,
            sshPool,
            config.ssh.user
        )
        val jobService = JobService(jobDao, sshPool, jobExecutionService)

        kStreams = run {
            log.info("Constructing Kafka Streams Topology")
            val kBuilder = StreamsBuilder()

            log.info("Configuring stream processors...")
            kBuilder.stream(HPCStreams.appEvents).foreach { _, event -> jobExecutionService.handleAppEvent(event) }
            log.info("Stream processors configured!")

            kafka.build(kBuilder.build()).also {
                log.info("Kafka Streams Topology successfully built!")
            }
        }

        kStreams.setUncaughtExceptionHandler { _, exception ->
            log.error("Caught fatal exception in Kafka! Stacktrace follows:")
            log.error(exception.stackTraceToString())
            stop()
        }

        httpServer = ktor {
            log.info("Configuring HTTP server")
            installDefaultFeatures(cloud, kafka, instance)
            install(JWTProtection)

            routing {
                route("api/hpc") {
                    protect()

                    AppController(ApplicationDAO).configure(this)
                    JobController(jobService).configure(this)
                    ToolController(ToolDAO).configure(this)
                }
            }
            log.info("HTTP server successfully configured!")
        }

        log.info("Starting Application Services")
        slurmPollAgent.start()
        jobExecutionService.initialize()

        log.info("Starting HTTP server...")
        // TODO We don't actually wait for the server to be ready before we register health endpoint!
        httpServer.start(wait = false)
        log.info("HTTP server started!")

        log.info("Starting Kafka Streams...")
        kStreams.start()
        log.info("Kafka Streams started!")

        initialized = true
        serviceRegistry.register(listOf("/api/hpc"))
        log.info("Server is ready!")
        log.info(instance.toString())
    }

    fun stop() {
        httpServer.stop(gracePeriod = 0, timeout = 30, timeUnit = TimeUnit.SECONDS)
        kStreams.close(30, TimeUnit.SECONDS)

        slurmPollAgent.stop()
        scheduledExecutor.shutdown()
    }

    companion object {
        private val log = LoggerFactory.getLogger(Server::class.java)
    }
}
