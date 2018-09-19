/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.services

import dk.sdu.cloud.storage.api.BulkUploadOverwritePolicy
import dk.sdu.cloud.storage.api.FileType
import dk.sdu.cloud.storage.util.CappedInputStream
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarInputStream
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

class UploadService(
    private val fs: FileSystemService,
    private val checksumService: ChecksumService
) {
    fun upload(user: String, path: String, writer: OutputStream.() -> Unit) {
        upload(fs.openContext(user), path, writer)
    }

    fun upload(ctx: FSUserContext, path: String, writer: OutputStream.() -> Unit) {
        if (path.contains("\n")) throw FileSystemException.BadRequest("Bad filename")

        fs.write(ctx, path, writer)
        checksumService.computeAndAttachChecksum(ctx, path)
    }

    fun bulkUpload(
        user: String,
        path: String,
        format: String,
        policy: BulkUploadOverwritePolicy,
        stream: InputStream
    ): List<String> {
        return when (format) {
            "tgz" -> bulkUploadTarGz(fs.openContext(user), path, policy, stream)
            else -> throw FileSystemException.BadRequest("Unsupported format '$format'")
        }
    }

    fun bulkUpload(
        ctx: FSUserContext,
        path: String,
        format: String,
        policy: BulkUploadOverwritePolicy,
        stream: InputStream
    ): List<String> {
        return when (format) {
            "tgz" -> bulkUploadTarGz(ctx, path, policy, stream)
            else -> throw FileSystemException.BadRequest("Unsupported format '$format'")
        }
    }

    private fun bulkUploadTarGz(
        ctx: FSUserContext,
        path: String,
        policy: BulkUploadOverwritePolicy,
        stream: InputStream
    ): List<String> {
        val rejectedFiles = ArrayList<String>()
        val rejectedDirectories = ArrayList<String>()

        TarInputStream(GZIPInputStream(stream)).use {
            val createdDirectories = HashSet<String>()
            var entry: TarEntry? = it.nextEntry
            while (entry != null) {
                val initialTargetPath = fs.joinPath(path, entry.name)
                val cappedStream = CappedInputStream(it, entry.size)
                if (entry.name.contains("PaxHeader/")) {
                    // This is some meta data stuff in the tarball. We don't want this
                    log.debug("Skipping entry: ${entry.name}")
                    cappedStream.skipRemaining()
                } else if (rejectedDirectories.any { entry?.name?.startsWith(it) == true }) {
                    log.debug("Skipping entry: ${entry.name}")
                    rejectedFiles += initialTargetPath
                    cappedStream.skipRemaining()
                } else {
                    log.debug("Downloading ${entry.name} isDir=${entry.isDirectory} (${entry.size} bytes)")

                    val existing = fs.stat(ctx, initialTargetPath)

                    val targetPath: String? = if (existing != null) {
                        val existingIsDirectory = existing.type == FileType.DIRECTORY
                        if (entry.isDirectory != existingIsDirectory) {
                            log.debug("Type of existing and new does not match. Rejecting regardless of policy")
                            rejectedDirectories += entry.name
                            null
                        } else {
                            if (entry.isDirectory) {
                                log.debug("Directory already exists. Skipping")
                                null
                            } else {
                                when (policy) {
                                    BulkUploadOverwritePolicy.OVERWRITE -> {
                                        log.debug("Overwriting file")
                                        initialTargetPath
                                    }

                                    BulkUploadOverwritePolicy.RENAME -> {
                                        log.debug("Renaming file")
                                        fs.findFreeNameForNewFile(
                                            ctx,
                                            initialTargetPath
                                        )
                                    }

                                    BulkUploadOverwritePolicy.REJECT -> {
                                        log.debug("Rejecting file")
                                        null
                                    }
                                }
                            }
                        }
                    } else {
                        log.debug("File does not exist")
                        initialTargetPath
                    }

                    if (targetPath != null) {
                        log.debug("Accepting file $initialTargetPath ($targetPath)")

                        try {
                            if (entry.isDirectory) {
                                createdDirectories += targetPath
                                fs.mkdir(ctx, targetPath)
                            } else {
                                val parentDir = File(targetPath).parentFile.path
                                if (parentDir !in createdDirectories) {
                                    createdDirectories += parentDir
                                    fs.mkdir(ctx, parentDir)
                                }

                                upload(ctx, targetPath) { cappedStream.copyTo(this) }
                            }
                        } catch (ex: FileSystemException.PermissionException) {
                            rejectedFiles += initialTargetPath
                        }
                    } else {
                        if (!entry.isDirectory) {
                            log.debug("Skipping file $initialTargetPath")
                            cappedStream.skipRemaining()
                            rejectedFiles += initialTargetPath
                        }
                    }
                }

                entry = it.nextEntry
            }
        }
        return rejectedFiles
    }

    companion object {
        private val log = LoggerFactory.getLogger(UploadService::class.java)
    }
}
