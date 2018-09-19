/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.service

import com.github.zafarkhaja.semver.Version
import com.orbitz.consul.Consul
import com.orbitz.consul.model.kv.ImmutableOperation
import com.orbitz.consul.model.kv.Operation
import com.orbitz.consul.model.kv.Verb
import dk.sdu.cloud.client.ServiceDescription
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.get
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.isSuccess
import kotlinx.coroutines.experimental.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

fun ServiceDescription.definition(): ServiceDefinition = ServiceDefinition(name, Version.valueOf(version))
fun ServiceDescription.instance(config: ConnectionConfig): ServiceInstance =
    ServiceInstance(definition(), config.service.hostname, config.service.port)

data class ServiceDefinition(val name: String, val version: Version)
data class ServiceInstance(val definition: ServiceDefinition, val hostname: String, val port: Int)

class ServiceRegistry(
    private val instance: ServiceInstance,
    consul: Consul = Consul.builder().build(),
    private val serviceCheckExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) {
    private val agent = consul.agentClient()
    private val health = consul.healthClient()
    private val kvStore = consul.keyValueClient()

    private var isRegistered = false

    private fun kvOperation(verb: Verb, key: String? = null, value: String? = null): Operation =
        ImmutableOperation.builder().apply {
            verb(verb.toValue())
            if (key != null) key(key)
            if (value != null) value(value)
        }.build()

    fun register(
        exposedHttpEndpoints: List<String>,
        httpHealthEndpoint: String? = HEALTH_URI,
        serviceHealthCheck: (() -> Boolean)? = null
    ) {
        if (isRegistered) throw IllegalStateException("Already registered at Consul!")

        // Must be unique, but should be reused (old entries will stick around in Consul but in a failed state
        // when TTL expires)
        val serviceId = "${instance.hostname}:${instance.port}-${instance.definition.name}"

        // K/V store is not replicated by default across DCs.
        //
        // But I think there will be other problems when we have multiple DCs and we might also want to keep a lot of
        // this local for each DC regardless. The Consul Replicate project might also solve this. (DC = Data Center)
        val apiKeyValueOperations = ArrayList<Operation>().apply {
            val apiKeyPrefix = "service/${instance.definition.name}/api"

            add(kvOperation(Verb.DELETE_TREE, apiKeyPrefix))

            exposedHttpEndpoints.forEachIndexed { index, endpoint ->
                add(kvOperation(Verb.SET, "$apiKeyPrefix/_$index", endpoint))
            }
        }.toList()

        var txAttempt = 0
        while (true) {
            if (txAttempt >= 5) throw IllegalStateException("Unable to write API into Consul K/V Store!")
            val response = kvStore.performTransaction(*apiKeyValueOperations.toTypedArray())
            if (response.response.errors().isEmpty()) break
            Thread.sleep(500)
            txAttempt++
        }

        agent.register(
            instance.port,
            TTL_IN_SECONDS,
            instance.definition.name,
            serviceId,
            // Tags
            "api",
            VERSION_PREFIX + instance.definition.version.toStringNoMetadata()
        )

        serviceCheckExecutor.scheduleAtFixedRate({
            healthLog.debug("Scheduling health check...")
            val result = try {
                serviceHealthCheck?.invoke() ?: true
            } catch (ex: Exception) {
                healthLog.warn("Caught exception while running service check!")
                healthLog.warn(ex.stackTraceToString())
                false
            }
            if (serviceHealthCheck != null) healthLog.debug("Custom check returned: $result")

            val serviceIsOkay: Boolean = run {
                if (!result) {
                    healthLog.warn("Service check returned false. Setting state to critical!")
                    return@run false
                }

                if (httpHealthEndpoint != null) {
                    // TODO FIXME HTTP IS HARDCODED
                    val status = try {
                        runBlocking {
                            httpHealthClient.get<HttpResponse>(
                                "http://${instance.hostname}:${instance.port}$HEALTH_URI"
                            )
                        }
                    } catch (ex: Exception) {
                        healthLog.warn("Caught exception while sending request to health endpoint!")
                        healthLog.warn(ex.stackTraceToString())
                        return@run false
                    }

                    if (!status.status.isSuccess()) {
                        healthLog.warn("Health endpoint did not return a status code in range 200..299")
                        healthLog.warn(status.status.toString())
                        healthLog.warn(runBlocking { status.readText() })

                        agent.fail(serviceId)
                        return@run false
                    }
                }

                return@run true
            }

            if (serviceIsOkay) {
                agent.pass(serviceId)
            } else {
                agent.fail(serviceId)
            }
        }, CHECK_PERIOD_IN_SECONDS, CHECK_PERIOD_IN_SECONDS, TimeUnit.SECONDS)

        isRegistered = true
    }

    fun listServices(name: String): List<ServiceInstance> =
        health.getHealthyServiceInstances(name).response.mapNotNull {
            val version = it.service.tags.find { it.startsWith(VERSION_PREFIX) }?.let {
                try {
                    Version.valueOf(it.substringAfter(VERSION_PREFIX))
                } catch (ex: Exception) {
                    null
                }
            } ?: return@mapNotNull null

            ServiceInstance(ServiceDefinition(it.service.service, version), it.node.address, it.service.port)
        }

    fun listServices(name: String, versionExpression: String): List<ServiceInstance> {
        if (versionExpression.contains("-SNAPSHOT")) {
            throw IllegalArgumentException("Version metadata not allowed in version expression")
        }

        return listServices(name).filter { it.definition.version.satisfies(versionExpression) }
    }

    companion object {
        const val VERSION_PREFIX = "v."
        const val TTL_IN_SECONDS = 10L
        const val CHECK_PERIOD_IN_SECONDS = 5L

        private val log = LoggerFactory.getLogger(ServiceRegistry::class.java)
        private fun Version.toStringNoMetadata() = "$majorVersion.$minorVersion.$patchVersion"

        private val httpHealthClient = HttpClient(Apache)
    }
}
