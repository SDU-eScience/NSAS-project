/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.services

import dk.sdu.cloud.service.stackTraceToString
import dk.sdu.cloud.storage.api.FileType
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarHeader
import org.kamranzafar.jtar.TarOutputStream
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.util.*
import java.util.zip.GZIPOutputStream

class BulkDownloadService(private val fs: FileSystemService) {
    fun downloadFiles(user: String, prefixPath: String, listOfFiles: List<String>, target: OutputStream) {
        TarOutputStream(GZIPOutputStream(target)).use { tarStream ->
            for (path in listOfFiles) {
                try {
                    // Calculate correct path, check if file exists and filter out bad files
                    val absPath = "${prefixPath.removeSuffix("/")}/${path.removePrefix("/")}"
                    val ctx = fs.openContext(user)
                    val stat = fs.stat(ctx, absPath) ?: continue

                    // Write tar header
                    log.debug("Writing tar header: ($path, $stat)")
                    tarStream.putNextEntry(
                        TarEntry(
                            TarHeader.createHeader(
                                path,
                                stat.size,
                                stat.modifiedAt,
                                stat.type == FileType.DIRECTORY,
                                511 // TODO! (0777)
                            )
                        )
                    )

                    // Write file contents
                    fs.read(ctx, absPath).use { ins -> ins.copyTo(tarStream) }
                } catch (ex: FileSystemException) {
                    when (ex) {
                        is FileSystemException.NotFound, is FileSystemException.PermissionException -> {
                            log.debug("Skipping file, caused by exception:")
                            log.debug(ex.stackTraceToString())
                        }

                        else -> throw ex
                    }
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BulkDownloadService::class.java)
    }
}
