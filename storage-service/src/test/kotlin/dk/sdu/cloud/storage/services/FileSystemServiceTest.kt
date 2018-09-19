/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.services

import dk.sdu.cloud.storage.api.AccessRight
import dk.sdu.cloud.storage.api.FileType
import dk.sdu.cloud.storage.api.SensitivityLevel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FileSystemServiceTest {
    private val service = cephFSWithRelaxedMocks(".")

    @Test
    fun testOutputParsing() {
        val where = File("/f/shared")

        val output = """
           D,1,509,root,root,4096,1523862649,1523862649,1523862650,1,3,user1,14,user2,2,user3,6,,CONFIDENTIAL,.
           D,0,493,root,root,4096,1523862224,1523862224,1523862237,2,0,,CONFIDENTIAL,..
           F,0,420,root,root,0,1523862649,1523862649,1523862649,3,0,,CONFIDENTIAL,qwe
        """.trimIndent()

        val result = service.parseDirListingOutput(where, output, includeImplicit = true).second
        assertEquals(3, result.size)

        run {
            val firstFile = result[0]
            assertEquals(FileType.DIRECTORY, firstFile.type)
            assertEquals("root", firstFile.ownerName)
            assertEquals(4096, firstFile.size)
            assertEquals(1523862649000, firstFile.createdAt)
            assertEquals(1523862649000, firstFile.modifiedAt)
            assertTrue(firstFile.link)
            val firstAcl = firstFile.acl
            assertEquals(3, firstAcl.size)

            assertEquals("user1", firstAcl[0].entity)
            assertEquals(false, firstAcl[0].isGroup)
            assertTrue(AccessRight.READ in firstAcl[0].rights)
            assertTrue(AccessRight.WRITE in firstAcl[0].rights)
            assertTrue(AccessRight.EXECUTE in firstAcl[0].rights)

            assertEquals("user2", firstAcl[1].entity)
            assertEquals(false, firstAcl[1].isGroup)
            assertTrue(AccessRight.READ in firstAcl[1].rights)

            assertEquals("user3", firstAcl[2].entity)
            assertEquals(false, firstAcl[2].isGroup)
            assertTrue(AccessRight.READ in firstAcl[2].rights)
            assertTrue(AccessRight.WRITE in firstAcl[2].rights)

            assertEquals(SensitivityLevel.CONFIDENTIAL, firstFile.sensitivityLevel)
            assertEquals("/f/shared", firstFile.path)
        }

        run {
            val file = result[1]
            assertEquals(FileType.DIRECTORY, file.type)
            assertEquals("root", file.ownerName)
            assertFalse(file.link)
            assertEquals(4096, file.size)
            assertEquals(1523862224000, file.createdAt)
            assertEquals(1523862224000, file.modifiedAt)
            val acl = file.acl
            assertEquals(0, acl.size)

            assertEquals(SensitivityLevel.CONFIDENTIAL, file.sensitivityLevel)
            assertEquals("/f", file.path)
        }

        run {
            val file = result[2]
            assertEquals(FileType.FILE, file.type)
            assertFalse(file.link)
            assertEquals("root", file.ownerName)
            assertEquals(0, file.size)
            assertEquals(1523862649000, file.createdAt)
            assertEquals(1523862649000, file.modifiedAt)
            val acl = file.acl
            assertEquals(0, acl.size)

            assertEquals(SensitivityLevel.CONFIDENTIAL, file.sensitivityLevel)
            assertEquals("/f/shared/qwe", file.path)
        }
    }

    @Test
    fun testFavorites() {
        val output = """
            1
            D
            Favorites/Jobs_00000000
            /mnt/cephfs/home/jonas@hinchely.dk/Jobs
            1099511627815
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,8,1523962672,1523962672,1523962688,1099511627810,0,,CONFIDENTIAL,.
            D,0,493,root,root,4,1523883079,1523883079,1523973678,1099511627777,0,,CONFIDENTIAL,..
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,1,1523880643,1523880643,1523968202,1099511627816,0,,CONFIDENTIAL,A Link to Uploads
            D,0,493,c_jonas_hinchely_dk,c_jonas_hinchely_dk,25,1523880056,1523880056,1523966653,1099511627819,0,,CONFIDENTIAL,Dan's Master Thesis
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,1,1524036190,1524036190,1524036193,1099511627814,0,,CONFIDENTIAL,Favorites
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,4,1523882904,1523882904,1524034628,1099511627815,0,,CONFIDENTIAL,Jobs
            D,0,493,c_jonas_hinchely_dk,c_jonas_hinchely_dk,4,1523880038,1496255424,1524033923,1099511627824,0,,CONFIDENTIAL,Link to dir
            F,0,420,c_jonas_hinchely_dk,c_jonas_hinchely_dk,104,1523880038,1496255424,1496255424,1099511627827,0,,CONFIDENTIAL,Link to file.tex
            D,0,493,c_jonas_hinchely_dk,c_jonas_hinchely_dk,0,1523887389,1523887389,1523887392,1099511627930,0,,CONFIDENTIAL,Test
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,1,1523880643,1523880643,1523968202,1099511627816,0,,CONFIDENTIAL,Uploads
        """.trimIndent()
        val parsed = service.parseDirListingOutput(File("/home/jonas@hinchely.dk"), output, false, true)
        assertEquals(1, parsed.first.size)
        assertEquals(1099511627815L, parsed.first.first().inode)
        assertTrue(parsed.second.find { it.path.endsWith("Jobs") }!!.favorited)
    }

    @Test (expected = FileSystemException.CriticalException::class)
    fun testFavoritesWrongFormating() {
        val output = """
            1
            D
            Favorites/Jobs_00000000
            /mnt/cephfs/home/jonas@hinchely.dk/Jobs
            1099511627815
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,8,1523962672,1523962672,1523962688,1099511627810,0,,CONFIDENTIAL,.
            L,0,493,root,root,4,1523883079,1523883079,1523973678,1099511627777,0,,CONFIDENTIAL,..
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,1,1523880643,1523880643,1523968202,1099511627816,0,,CONFIDENTIAL,A Link to Uploads
            D,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,1,1523880643,1523880643,1523968202,1099511627816,0,CONFIDENTIAL,Uploads
        """.trimIndent()
        service.parseDirListingOutput(File("/home/jonas@hinchely.dk"), output, false, true)

    }


    @Test (expected = IllegalStateException::class)
    fun testIllegalTypeNOTDFL() {
        val output = """
            1
            D
            Favorites/Jobs_00000000
            /mnt/cephfs/home/jonas@hinchely.dk/Jobs
            1099511627815
            K,0,448,c_jonas_hinchely_dk,c_jonas_hinchely_dk,8,1523962672,1523962672,1523962688,1099511627810,0,CONFIDENTIAL,.
            D,0,493,root,root,4,1523883079,1523883079,1523973678,1099511627777,0,CONFIDENTIAL,..
        """.trimIndent()
        service.parseDirListingOutput(File("/home/jonas@hinchely.dk"), output, false, true)
    }

}
