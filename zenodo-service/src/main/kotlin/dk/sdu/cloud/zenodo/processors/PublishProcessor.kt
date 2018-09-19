/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.zenodo.processors

import com.auth0.jwt.interfaces.DecodedJWT
import dk.sdu.cloud.auth.api.AuthDescriptions
import dk.sdu.cloud.auth.api.RequestOneTimeToken
import dk.sdu.cloud.client.AuthenticatedCloud
import dk.sdu.cloud.client.CloudContext
import dk.sdu.cloud.client.JWTAuthenticatedCloud
import dk.sdu.cloud.client.RESTResponse
import dk.sdu.cloud.service.TokenValidation
import dk.sdu.cloud.service.stackTraceToString
import dk.sdu.cloud.service.stream
import dk.sdu.cloud.service.withCausedBy
import dk.sdu.cloud.storage.api.DOWNLOAD_FILE_SCOPE
import dk.sdu.cloud.storage.api.DownloadByURI
import dk.sdu.cloud.storage.api.FileDescriptions
import dk.sdu.cloud.zenodo.api.ZenodoCommandStreams
import dk.sdu.cloud.zenodo.api.ZenodoPublicationStatus
import dk.sdu.cloud.zenodo.api.ZenodoPublishCommand
import dk.sdu.cloud.zenodo.services.PublicationService
import dk.sdu.cloud.zenodo.services.ZenodoRPCException
import dk.sdu.cloud.zenodo.services.ZenodoRPCService
import kotlinx.coroutines.experimental.runBlocking
import org.apache.kafka.streams.StreamsBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

class PublishProcessor(
    private val zenodoService: ZenodoRPCService,
    private val publicationService: PublicationService,
    private val cloudContext: CloudContext
) {
    fun init(kBuilder: StreamsBuilder) {
        kBuilder.stream(ZenodoCommandStreams.publishCommands).foreach { _, command ->
            runBlocking {
                log.info("Handling publishing command $command")
                // TODO When we change the design of commands are handled we still likely need two streams.
                // The first in which we need to authenticate the request, and the second in which we handle it.
                // This will, however, require more support for services acting on behalf of pre-authenticated users.
                // Because we don't have support for this yet, we will simply handle the request immediately.

                log.debug("Validating principal...")
                val validatedPrincipal =
                    TokenValidation.validateOrNull(command.jwt)
                            ?: return@runBlocking run {
                                log.info("Token for command is not valid or is missing the correct scope!")
                                log.debug("Bad command was: $command")
                            }

                val cloud = JWTAuthenticatedCloud(cloudContext, validatedPrincipal.token).withCausedBy(command.uuid)

                log.debug("Updating status of command: ${command.publicationId} to UPLOADING")
                publicationService.updateStatusOf(command.publicationId, ZenodoPublicationStatus.UPLOADING)
                val files = transferCloudFilesToTemporaryStorage(command, cloud)

                try {
                    transferTemporaryFilesToZenodo(command, validatedPrincipal, files)

                    log.debug("Updating status of command: ${command.publicationId} to COMPLETE")
                    publicationService.updateStatusOf(command.publicationId, ZenodoPublicationStatus.COMPLETE)
                } catch (ex: Exception) {
                    log.debug("Updating status of command: ${command.publicationId} to FAILURE")
                    publicationService.updateStatusOf(command.publicationId, ZenodoPublicationStatus.FAILURE)

                    when (ex) {
                        is PublishingException -> {
                            log.debug("Caught known exception while transferring files to Zenodo")
                            log.debug(ex.stackTraceToString())
                            // TODO We can use ex.message for output to user
                        }

                        else -> {
                            log.warn("Caught unexpected exception while transferring files to Zenodo")
                            log.warn(ex.stackTraceToString())
                            throw ex
                        }
                    }
                } finally {
                    files.forEach { it.delete() }
                }
            }
        }
    }

    private suspend fun transferCloudFilesToTemporaryStorage(
        command: ZenodoPublishCommand,
        cloud: AuthenticatedCloud
    ): List<File> {
        val buffer = ByteArray(4096)
        return command.request.filePaths.map {
            val tokenResponse = AuthDescriptions.requestOneTimeTokenWithAudience.call(
                RequestOneTimeToken("$DOWNLOAD_FILE_SCOPE,irods"), cloud
            )

            if (tokenResponse !is RESTResponse.Ok) {
                log.warn("Unable to request a new one time token for download!")
                log.warn("${tokenResponse.status}: ${tokenResponse.rawResponseBody}")
                throw PublishingException.CloudAuthenticationError()
            }

            val token = tokenResponse.result

            // This will generate misleading audit trail, unless we get a caused-by header added to it
            val fileDownload = FileDescriptions.download.call(DownloadByURI(it, token.accessToken), cloud)
            val tempFile = Files.createTempFile("zenodo-upload", "").toFile()
            try {
                tempFile.outputStream().use { out ->
                    fileDownload.response.responseBodyAsStream.use { inp ->
                        while (true) {
                            val read = inp.read(buffer)
                            if (read != -1) out.write(buffer, 0, read)
                            else break
                        }
                    }
                }
            } catch (ex: Exception) {
                log.warn("Caught exception while download files temporarily!")
                log.warn(ex.stackTraceToString())
                throw PublishingException.CloudIOError()
            }

            tempFile
        }
    }

    private suspend fun transferTemporaryFilesToZenodo(
        command: ZenodoPublishCommand,
        validatedPrincipal: DecodedJWT,
        files: List<File>
    ) {
        log.debug("Creating deposition at Zenodo!")
        val deposition = try {
            zenodoService.createDeposition(validatedPrincipal)
        } catch (ex: ZenodoRPCException) {
            log.warn("Unable to create a response at Zenodo!")
            log.warn(ex.stackTraceToString())
            throw PublishingException.ZenodoCommunicationFailure()
        }
        log.debug("Response was: $deposition")

        val depositionId = deposition.id
        publicationService.attachZenodoId(command.publicationId, depositionId)
        files.forEachIndexed { idx, file ->
            val path = command.request.filePaths[idx]
            val name = path.substringAfterLast("/")
            log.debug("Uploading file: $name ${file.length()}")
            try {
                zenodoService.createUpload(
                    validatedPrincipal,
                    depositionId,
                    name,
                    file
                )

                publicationService.markUploadAsCompleteInPublication(command.publicationId, path)
            } catch (ex: ZenodoRPCException) {
                log.warn("Upload failed for file: $name")
                log.warn(ex.stackTraceToString())
                throw PublishingException.ZenodoCommunicationFailure()
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PublishProcessor::class.java)
    }
}

private sealed class PublishingException : RuntimeException() {
    abstract override val message: String

    class CloudAuthenticationError : PublishingException() {
        override val message = "Could not correctly authenticate with SDU Cloud"
    }

    class CloudIOError : PublishingException() {
        override val message = "An error occurred while transferring files from SDU Cloud"
    }

    class ZenodoCommunicationFailure : PublishingException() {
        override val message = "An error occurred while communicating with Zenodo"
    }
}
