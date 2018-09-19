/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.services

import dk.sdu.cloud.storage.api.StorageEvent
import dk.sdu.cloud.storage.api.StorageEventProducer
import dk.sdu.cloud.storage.services.cephfs.CopyService
import io.mockk.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CopyTest {

    @Test
    fun testSimpleCopy() {
        val emitter: StorageEventProducer = mockk()
        coEvery { emitter.emit(any()) } just Runs

        val fsRoot = createDummyFS()
        val fs = cephFSWithRelaxedMocks(
            fsRoot.absolutePath,
            copyService = CopyService(true),
            eventProducer = emitter
        )

        fs.copy(fs.openContext("user1"), "/home/user1/folder", "/home/user1/folder_copy/")

        val copiedFolder = File(fsRoot, "home/user1/folder_copy")
        assertTrue(copiedFolder.exists())
        assertEquals("File A", File(copiedFolder, "a").readText())
        assertEquals("File B", File(copiedFolder, "b").readText())
        assertEquals("File C", File(copiedFolder, "c").readText())

        val existingFolder = File(fsRoot, "home/user1/folder")
        assertEquals(File(existingFolder, "a").readText(), File(copiedFolder, "a").readText())
        assertEquals(File(existingFolder, "b").readText(), File(copiedFolder, "b").readText())
        assertEquals(File(existingFolder, "c").readText(), File(copiedFolder, "c").readText())

        // The function returns immediately. We want to wait for those events to have been emitted.
        // This is not a fool proof way of doing it. But we have no way of waiting for tasks
        Thread.sleep(100)

        coVerify {
            emitter.emit(match { it is StorageEvent.CreatedOrModified && it.path == "/home/user1/folder_copy/a" })
            emitter.emit(match { it is StorageEvent.CreatedOrModified && it.path == "/home/user1/folder_copy/b" })
            emitter.emit(match { it is StorageEvent.CreatedOrModified && it.path == "/home/user1/folder_copy/c" })
            emitter.emit(match { it is StorageEvent.CreatedOrModified && it.path == "/home/user1/folder_copy" })
        }
    }


}
