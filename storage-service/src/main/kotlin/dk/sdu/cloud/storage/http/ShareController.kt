/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.http

import dk.sdu.cloud.auth.api.protect
import dk.sdu.cloud.auth.api.validatedPrincipal
import dk.sdu.cloud.service.cloudClient
import dk.sdu.cloud.service.implement
import dk.sdu.cloud.service.logEntry
import dk.sdu.cloud.storage.api.FindByShareId
import dk.sdu.cloud.storage.api.ShareDescriptions
import dk.sdu.cloud.storage.api.ShareState
import dk.sdu.cloud.storage.services.ShareService
import dk.sdu.cloud.storage.services.tryWithShareService
import io.ktor.application.ApplicationCall
import io.ktor.routing.Route
import io.ktor.routing.route
import org.slf4j.LoggerFactory

class ShareController(private val shareService: ShareService) {
    fun configure(routing: Route) = with(routing) {
        route("shares") {
            protect()

            implement(ShareDescriptions.list) {
                logEntry(log, it)
                tryWithShareService {
                    ok(shareService.list(call.user, it.pagination))
                }
            }

            implement(ShareDescriptions.accept) {
                logEntry(log, it)

                tryWithShareService {
                    ok(shareService.updateState(call.user, it.id, ShareState.ACCEPTED))
                }
            }

            implement(ShareDescriptions.revoke) {
                logEntry(log, it)

                tryWithShareService {
                    ok(shareService.deleteShare(call.user, it.id))
                }
            }

            implement(ShareDescriptions.reject) {
                logEntry(log, it)

                tryWithShareService {
                    ok(shareService.deleteShare(call.user, it.id))
                }
            }

            implement(ShareDescriptions.update) {
                logEntry(log, it)

                tryWithShareService {
                    ok(shareService.update(call.user, it.id, it.rights))
                }
            }

            implement(ShareDescriptions.create) {
                logEntry(log, it)

                tryWithShareService {
                    ok(FindByShareId(shareService.create(call.user, it, call.cloudClient)))
                }
            }
        }
    }

    val ApplicationCall.user: String get() = request.validatedPrincipal.subject

    companion object {
        private val log = LoggerFactory.getLogger(ShareController::class.java)
    }
}
