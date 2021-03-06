/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.services

import dk.sdu.cloud.storage.api.BulkUploadOverwritePolicy
import io.mockk.mockk
import junit.framework.Assert.*
import org.junit.Test
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarHeader
import org.kamranzafar.jtar.TarOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class BulkUploadTest {
    fun File.mkdir(name: String, closure: File.() -> Unit) {
        val f = File(this, name)
        f.mkdir()
        f.closure()
    }

    fun File.touch(name: String, contents: String) {
        File(this, name).writeText(contents)
    }

    fun createFileSystem(closure: File.() -> Unit): File {
        val fsRoot = Files.createTempDirectory("share-service-test").toFile()
        fsRoot.closure()
        return fsRoot
    }

    fun TarOutputStream.putDirectory(name: String) {
        putNextEntry(
            TarEntry(
                TarHeader.createHeader(
                    name, 0, 0, true, 511 // 0777
                )
            )
        )
    }

    fun TarOutputStream.putFile(name: String, contents: String) {
        val payload = contents.toByteArray()
        putNextEntry(
            TarEntry(
                TarHeader.createHeader(
                    name, payload.size.toLong(), 0, false, 511
                )
            )
        )

        write(payload)
    }

    fun createTarFile(target: OutputStream, closure: TarOutputStream.() -> Unit) {
        TarOutputStream(GZIPOutputStream(target)).use {
            it.closure()
        }
    }

    @Test
    fun testSimpleUpload() {
        val fsRoot = createFileSystem {
            mkdir("home") {
                mkdir("user") {
                }
            }
        }

        val tarFile = Files.createTempFile("foo", ".tar.gz").toFile()
        createTarFile(tarFile.outputStream()) {
            putDirectory("test")
            putFile("test/file", "hello!")
        }

        val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

        val upload = UploadService(fs, mockk(relaxed = true))
        upload.bulkUpload("user", "/home/user/", "tgz", BulkUploadOverwritePolicy.OVERWRITE, tarFile.inputStream())

        val homeDir = File(fsRoot, "/home/user")
        assertTrue(homeDir.exists())

        val testDir = File(homeDir, "test")
        assertTrue(testDir.exists())
        assertTrue(testDir.isDirectory)

        val testFile = File(testDir, "file")
        assertTrue(testFile.exists())
        assertFalse(testFile.isDirectory)
        assertEquals("hello!", testFile.readText())
    }

    @Test
    fun testRename() {
        val originalContents = "original"
        val fsRoot = createFileSystem {
            mkdir("home") {
                mkdir("user") {
                    mkdir("test") {
                        touch("file", originalContents)
                    }
                }
            }
        }

        val tarFile = Files.createTempFile("foo", ".tar.gz").toFile()
        createTarFile(tarFile.outputStream()) {
            putDirectory("test")
            putFile("test/file", "hello!")
        }

        val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

        val upload = UploadService(fs, mockk(relaxed = true))
        val result =
            upload.bulkUpload("user", "/home/user/", "tgz", BulkUploadOverwritePolicy.RENAME, tarFile.inputStream())

        val homeDir = File(fsRoot, "/home/user")
        assertTrue(homeDir.exists())

        val testDir = File(homeDir, "test")
        assertTrue(testDir.exists())
        assertTrue(testDir.isDirectory)

        val origTestFile = File(testDir, "file")
        assertTrue(origTestFile.exists())
        assertFalse(origTestFile.isDirectory)
        assertEquals(originalContents, origTestFile.readText())

        val testFile = File(testDir, "file(1)")
        assertTrue(testFile.exists())
        assertFalse(testFile.isDirectory)
        assertEquals("hello!", testFile.readText())

        assertEquals(0, result.size)
    }

    @Test
    fun testOverwrite() {
        val originalContents = "original"
        val fsRoot = createFileSystem {
            mkdir("home") {
                mkdir("user") {
                    mkdir("test") {
                        touch("file", originalContents)
                    }
                }
            }
        }

        val tarFile = Files.createTempFile("foo", ".tar.gz").toFile()
        createTarFile(tarFile.outputStream()) {
            putDirectory("test")
            putFile("test/file", "hello!")
        }

        val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

        val upload = UploadService(fs, mockk(relaxed = true))
        val result =
            upload.bulkUpload("user", "/home/user/", "tgz", BulkUploadOverwritePolicy.OVERWRITE, tarFile.inputStream())

        val homeDir = File(fsRoot, "/home/user")
        assertTrue(homeDir.exists())

        val testDir = File(homeDir, "test")
        assertTrue(testDir.exists())
        assertTrue(testDir.isDirectory)

        val origTestFile = File(testDir, "file")
        assertTrue(origTestFile.exists())
        assertFalse(origTestFile.isDirectory)
        assertEquals("hello!", origTestFile.readText())

        assertEquals(0, result.size)
    }

    @Test
    fun testReject() {
        val originalContents = "original"
        val fsRoot = createFileSystem {
            mkdir("home") {
                mkdir("user") {
                    mkdir("test") {
                        touch("file", originalContents)
                    }
                }
            }
        }

        val tarFile = Files.createTempFile("foo", ".tar.gz").toFile()
        createTarFile(tarFile.outputStream()) {
            putDirectory("test")
            putFile("test/file", "hello!")
        }

        val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

        val upload = UploadService(fs, mockk(relaxed = true))
        val result =
            upload.bulkUpload("user", "/home/user/", "tgz", BulkUploadOverwritePolicy.REJECT, tarFile.inputStream())

        val homeDir = File(fsRoot, "/home/user")
        assertTrue(homeDir.exists())

        val testDir = File(homeDir, "test")
        assertTrue(testDir.exists())
        assertTrue(testDir.isDirectory)

        val origTestFile = File(testDir, "file")
        assertTrue(origTestFile.exists())
        assertFalse(origTestFile.isDirectory)
        assertEquals(originalContents, origTestFile.readText())

        assertEquals(1, result.size)
        assertEquals(listOf("/home/user/test/file"), result)
    }

    @Test
    fun testFromFileToDir() {
        val originalContents = "original"
        val fsRoot = createFileSystem {
            mkdir("home") {
                mkdir("user") {
                    mkdir("test") {
                        touch("file", originalContents)
                    }
                }
            }
        }

        val tarFile = Files.createTempFile("foo", ".tar.gz").toFile()
        createTarFile(tarFile.outputStream()) {
            putDirectory("test")
            putDirectory("test/file")
            putFile("test/file/foo", "contents")
        }

        val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

        val upload = UploadService(fs, mockk(relaxed = true))
        val result =
            upload.bulkUpload("user", "/home/user/", "tgz", BulkUploadOverwritePolicy.OVERWRITE, tarFile.inputStream())

        val homeDir = File(fsRoot, "/home/user")
        assertTrue(homeDir.exists())

        val testDir = File(homeDir, "test")
        assertTrue(testDir.exists())
        assertTrue(testDir.isDirectory)

        val origTestFile = File(testDir, "file")
        assertTrue(origTestFile.exists())
        assertFalse(origTestFile.isDirectory)
        assertEquals(originalContents, origTestFile.readText())

        assertEquals(1, result.size)
        assertEquals(listOf("/home/user/test/file/foo"), result)
    }

    @Test
    fun testFromDirToFile() {
        val fsRoot = createFileSystem {
            mkdir("home") {
                mkdir("user") {
                    mkdir("test") {
                        mkdir("file") {}
                    }
                }
            }
        }

        val tarFile = Files.createTempFile("foo", ".tar.gz").toFile()
        createTarFile(tarFile.outputStream()) {
            putDirectory("test")
            putFile("test/file", "contents")
        }
        val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

        val upload = UploadService(fs, mockk(relaxed = true))
        val result =
            upload.bulkUpload("user", "/home/user/", "tgz", BulkUploadOverwritePolicy.OVERWRITE, tarFile.inputStream())

        val homeDir = File(fsRoot, "/home/user")
        assertTrue(homeDir.exists())

        val testDir = File(homeDir, "test")
        assertTrue(testDir.exists())
        assertTrue(testDir.isDirectory)

        val origTestFile = File(testDir, "file")
        assertTrue(origTestFile.exists())
        assertTrue(origTestFile.isDirectory)

        assertEquals(1, result.size)
        assertEquals(listOf("/home/user/test/file"), result)
    }

    @Test
    fun testShellInjection() {
        val fsRoot = createFileSystem {
            mkdir("home") {
                mkdir("user") {
                    mkdir("test") {
                        mkdir("file") {}
                    }
                }
            }
        }

        val tarFile = Files.createTempFile("foo", ".tar.gz").toFile()
        createTarFile(tarFile.outputStream()) {
            putDirectory("test")
            putFile("test/\$PWD", "contents")
        }
        val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

        val upload = UploadService(fs, mockk(relaxed = true))
        val result =
            upload.bulkUpload("user", "/home/user/", "tgz", BulkUploadOverwritePolicy.OVERWRITE, tarFile.inputStream())

        val homeDir = File(fsRoot, "/home/user")
        assertTrue(homeDir.exists())

        val testDir = File(homeDir, "test")
        assertTrue(testDir.exists())
        assertTrue(testDir.isDirectory)

        val origTestFile = File(testDir, "\$PWD")
        assertTrue(origTestFile.exists())
        assertFalse(origTestFile.isDirectory)

        assertEquals(0, result.size)
    }
}
