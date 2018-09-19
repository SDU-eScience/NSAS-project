/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.services

import dk.sdu.cloud.CommonErrorMessage
import dk.sdu.cloud.client.AuthenticatedCloud
import dk.sdu.cloud.notification.api.CreateNotification
import dk.sdu.cloud.notification.api.Notification
import dk.sdu.cloud.notification.api.NotificationDescriptions
import dk.sdu.cloud.service.NormalizedPaginationRequest
import dk.sdu.cloud.service.Page
import dk.sdu.cloud.service.RESTHandler
import dk.sdu.cloud.service.stackTraceToString
import dk.sdu.cloud.storage.api.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.experimental.launch
import org.slf4j.LoggerFactory

sealed class ShareException(override val message: String) : RuntimeException(message) {
    class NotFound : ShareException("Not found")
    class NotAllowed : ShareException("Not allowed")
    class DuplicateException : ShareException("Already exists")
    class PermissionException : ShareException("Not allowed")
    class BadRequest(why: String) : ShareException("Bad request: $why")
}

private val log = LoggerFactory.getLogger(ShareService::class.java)

suspend fun RESTHandler<*, *, CommonErrorMessage>.handleShareException(ex: Exception) {
    when (ex) {
        is ShareException -> {
            @Suppress("UNUSED_VARIABLE")
            val ignored = when (ex) {
                is ShareException.NotFound -> {
                    error(CommonErrorMessage(ex.message), HttpStatusCode.NotFound)
                }

                is ShareException.NotAllowed -> {
                    error(CommonErrorMessage(ex.message), HttpStatusCode.Forbidden)
                }

                is ShareException.DuplicateException -> {
                    error(CommonErrorMessage(ex.message), HttpStatusCode.Conflict)
                }
                is ShareException.PermissionException -> {
                    error(CommonErrorMessage(ex.message), HttpStatusCode.Forbidden)
                }
                is ShareException.BadRequest -> {
                    error(CommonErrorMessage(ex.message), HttpStatusCode.BadRequest)
                }
            }
        }

        is IllegalArgumentException -> {
            log.debug("Bad request:")
            log.debug(ex.stackTraceToString())
            error(CommonErrorMessage("Bad request"), HttpStatusCode.BadRequest)
        }

        else -> {
            log.warn("Unknown exception caught in share service!")
            log.warn(ex.stackTraceToString())
            error(CommonErrorMessage("Internal Server Error"), HttpStatusCode.InternalServerError)
        }
    }
}

suspend inline fun RESTHandler<*, *, CommonErrorMessage>.tryWithShareService(body: () -> Unit) {
    try {
        body()
    } catch (ex: Exception) {
        handleShareException(ex)
    }
}

class ShareService(
    private val source: ShareDAO,
    private val fs: FileSystemService
) {
    suspend fun list(
        user: String,
        paging: NormalizedPaginationRequest = NormalizedPaginationRequest(null, null)
    ): Page<SharesByPath> {
        return list(fs.openContext(user), paging)
    }

    suspend fun list(
        ctx: FSUserContext,
        paging: NormalizedPaginationRequest = NormalizedPaginationRequest(null, null)
    ): Page<SharesByPath> {
        return source.list(ctx.user, paging)
    }

    suspend fun retrieveShareForPath(
        user: String,
        path: String
    ): SharesByPath {
        return retrieveShareForPath(fs.openContext(user), path)
    }

    suspend fun retrieveShareForPath(
        ctx: FSUserContext,
        path: String
    ): SharesByPath {
        val stat = fs.stat(ctx, path) ?: throw ShareException.NotFound()
        if (stat.ownerName != ctx.user) {
            throw ShareException.NotAllowed()
        }

        return source.findSharesForPath(ctx.user, path)
    }

    suspend fun create(
        user: String,
        share: CreateShareRequest,
        cloud: AuthenticatedCloud
    ): ShareId {
        return create(fs.openContext(user), share, cloud)
    }

    suspend fun create(
        ctx: FSUserContext,
        share: CreateShareRequest,
        cloud: AuthenticatedCloud
    ): ShareId {
        // Check if user is allowed to share this file
        val stat = fs.stat(ctx, share.path) ?: throw ShareException.NotFound()
        if (stat.ownerName != ctx.user) {
            throw ShareException.NotAllowed()
        }

        // TODO Need to verify sharedWith exists!
        val rewritten = Share(
            owner = ctx.user,
            createdAt = System.currentTimeMillis(),
            modifiedAt = System.currentTimeMillis(),
            state = ShareState.REQUEST_SENT,
            path = share.path,
            sharedWith = share.sharedWith,
            rights = share.rights
        )

        val result = source.create(ctx.user, rewritten)

        launch {
            NotificationDescriptions.create.call(
                CreateNotification(
                    user = share.sharedWith,
                    notification = Notification(
                        type = "SHARE_REQUEST",
                        message = "${ctx.user} has shared a file with you",

                        meta = mapOf(
                            "shareId" to result,
                            "path" to share.path,
                            "rights" to share.rights
                        )
                    )
                ),
                cloud
            )
        }

        return result
    }

    suspend fun update(
        user: String,
        shareId: ShareId,
        newRights: Set<AccessRight>
    ) {
        return update(fs.openContext(user), shareId, newRights)
    }

    suspend fun update(
        ctx: FSUserContext,
        shareId: ShareId,
        newRights: Set<AccessRight>
    ) {
        val existingShare = source.find(ctx.user, shareId) ?: throw ShareException.NotFound()
        if (existingShare.owner != ctx.user) throw ShareException.NotAllowed()

        val newShare = existingShare.copy(
            modifiedAt = System.currentTimeMillis(),
            rights = newRights
        )

        if (existingShare.state == ShareState.ACCEPTED) {
            fs.grantRights(fs.openContext(existingShare.owner), existingShare.sharedWith, existingShare.path, newRights)
        }

        source.update(ctx.user, shareId, newShare)
    }

    suspend fun updateState(
        user: String,
        shareId: ShareId,
        newState: ShareState
    ) {
        updateState(fs.openContext(user), shareId, newState)
    }

    suspend fun updateState(
        ctx: FSUserContext,
        shareId: ShareId,
        newState: ShareState
    ) {
        log.debug("Updating state ${ctx.user} $shareId $newState")
        val existingShare = source.find(ctx.user, shareId) ?: throw ShareException.NotFound()
        log.debug("Existing share: $existingShare")

        when (ctx.user) {
            existingShare.sharedWith -> when (newState) {
                ShareState.ACCEPTED -> {
                    // This is okay
                }

                else -> throw ShareException.NotAllowed()
            }

            existingShare.owner -> throw ShareException.NotAllowed()

            else -> {
                log.warn("ShareDAO returned a result but user is not owner or being sharedWith! $existingShare ${ctx.user}")
                throw IllegalStateException()
            }
        }

        if (newState == ShareState.ACCEPTED) {
            fs.grantRights(
                fs.openContext(existingShare.owner),
                existingShare.sharedWith,
                existingShare.path,
                existingShare.rights
            )
            fs.createSoftSymbolicLink(
                fs.openContext(existingShare.sharedWith),
                fs.findFreeNameForNewFile(
                    ctx,
                    fs.joinPath(fs.homeDirectory(ctx), existingShare.path.substringAfterLast('/'))
                ),
                existingShare.path
            )
        }

        log.debug("Updating state")
        source.updateState(ctx.user, shareId, newState)
    }

    suspend fun deleteShare(
        user: String,
        shareId: ShareId
    ) {
        val existingShare = source.find(user, shareId) ?: throw ShareException.NotFound()
        fs.revokeRights(fs.openContext(existingShare.owner), existingShare.sharedWith, existingShare.path)
        source.deleteShare(user, shareId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ShareService::class.java)
    }
}
