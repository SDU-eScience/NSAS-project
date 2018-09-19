/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.auth.http

import com.onelogin.saml2.settings.Saml2Settings
import dk.sdu.cloud.auth.services.TokenService
import dk.sdu.cloud.auth.services.saml.Auth
import dk.sdu.cloud.auth.services.saml.KtorUtils
import dk.sdu.cloud.auth.util.urlDecoded
import dk.sdu.cloud.auth.util.urlEncoded
import dk.sdu.cloud.service.logEntry
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.ContentTransformationException
import io.ktor.request.receiveParameters
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.util.toMap
import org.slf4j.LoggerFactory

private const val SAML_RELAY_STATE_PREFIX = "/auth/saml/login?service="

class SAMLController(
        private val authSettings: Saml2Settings,
        private val tokenService: TokenService
) {
    fun configure(routing: Routing): Unit = with(routing) {
        route("auth") {
            route("saml") {
                get("metadata") {
                    logEntry(log)
                    call.respondText(authSettings.spMetadata, ContentType.Application.Xml)
                }

                get("login") {
                    val service = call.parameters["service"] ?: return@get run {
                        logEntry(log, mapOf("message" to "missing service"))
                        call.respondRedirect("/auth/login")
                    }

                    logEntry(log, mapOf("service" to service))

                    val relayState = KtorUtils.getSelfURLhost(call) +
                            "$SAML_RELAY_STATE_PREFIX${service.urlEncoded}"

                    log.debug("Using relayState=$relayState")

                    val auth = Auth(authSettings, call)
                    val samlRequestTarget = auth.login(
                            setNameIdPolicy = true,
                            returnTo = relayState,
                            stay = true
                    )

                    log.debug("Redirecting to $samlRequestTarget")
                    call.respondRedirect(samlRequestTarget, permanent = false)
                }

                post("acs") {
                    val params = try {
                        call.receiveParameters()
                    } catch (ex: ContentTransformationException) {
                        logEntry(log, mapOf("message" to "missing parameters"))
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }

                    logEntry(log, mapOf("params" to params.toMap()))

                    val service = params["RelayState"]?.let {
                        val index = it.indexOf(SAML_RELAY_STATE_PREFIX)
                        if (index == -1) return@let null

                        it.substring(index + SAML_RELAY_STATE_PREFIX.length).urlDecoded
                    } ?: return@post run {
                        log.info("Missing or invalid relayState")
                        call.respondRedirect("/auth/login")
                    }

                    val auth = Auth(authSettings, call, params)
                    auth.processResponse()

                    val user = tokenService.processSAMLAuthentication(auth)
                    if (user == null) {
                        log.debug("User not successfully authenticated")
                        call.respond(HttpStatusCode.Unauthorized)
                    } else {
                        log.debug("User successfully authenticated!")
                        val token = tokenService.createAndRegisterTokenFor(user)
                        log.debug("Using token: $token")
                        call.respondRedirect("/auth/login-redirect?" +
                                "service=${service.urlEncoded}" +
                                "&accessToken=${token.accessToken.urlEncoded}" +
                                "&refreshToken=${token.refreshToken.urlEncoded}"
                        )
                    }
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SAMLController::class.java)
    }
}
