/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
package dk.sdu.cloud.storage.http.fileControllerTests

import dk.sdu.cloud.auth.api.JWTProtection
import dk.sdu.cloud.auth.api.Role
import dk.sdu.cloud.service.ServiceInstance
import dk.sdu.cloud.service.definition
import dk.sdu.cloud.service.installDefaultFeatures
import dk.sdu.cloud.storage.http.FilesController
import dk.sdu.cloud.storage.services.cephFSWithRelaxedMocks
import dk.sdu.cloud.storage.services.createDummyFS
import dk.sdu.cloud.storage.util.withAuthMock
import io.ktor.application.install
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals


class SyncFilesTest {

    //Sync File Testing
    //TODO()
    @Test
    fun syncFileTest() {
        withAuthMock {
            withTestApplication(
                moduleFunction = {
                    val instance = ServiceInstance(
                        dk.sdu.cloud.storage.api.StorageServiceDescription.definition(),
                        "localhost",
                        42000
                    )
                    installDefaultFeatures(mockk(relaxed = true), mockk(relaxed = true), instance, requireJobId = false)
                    install(JWTProtection)
                    val fsRoot = createDummyFS()
                    val fs = cephFSWithRelaxedMocks(fsRoot.absolutePath)

                    routing {
                        route("api") {
                            FilesController(fs).configure(this)
                        }
                    }
                },

                test = {
                    val response = handleRequest(HttpMethod.Post, "/api/files/sync") {
                        setUser("user1", Role.USER)
                        setBody(
                            """
                            {
                            "path" : "/home/user1/folder/"
                            }
                            """.trimIndent()
                        )
                    }.response

                    assertEquals(HttpStatusCode.OK, response.status())
                }
            )
        }
    }
}
