package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogClientCollectionsTest {
    private fun api(server: MockWebServer) = CatalogClient(server.url("/").toString()).collectionsApi("token")

    @Test
    fun `lists, creates, renames, deletes and sets membership with the documented routes and bodies`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"collections":[{"id":"c1","name":"Kids","sort_order":0,"item_count":2}]}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"c2","name":"Favourites","item_count":0}"""))
            server.enqueue(MockResponse().setBody("""{"id":"c2","name":"Best","sort_order":0,"item_count":0}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("""{"collection_id":"c1","item_id":"i1","present":false}"""))
            val client = api(server)
            runBlocking {
                assertEquals(listOf(RemoteCollection("c1", "Kids")), client.list())
                assertEquals(RemoteCollection("c2", "Favourites"), client.create("Favourites").value)
                assertEquals(RemoteCollection("c2", "Best"), client.rename("c2", "Best").value)
                assertEquals(CallOutcome.Ok, client.delete("c2").outcome)
                assertEquals(CallOutcome.Ok, client.setMember("c1", "i1", false).outcome)
            }
            val requests = (1..5).map { server.takeRequest() }
            assertEquals(listOf("GET /api/catalog/collections", "POST /api/catalog/collections", "PATCH /api/catalog/collections/c2",
                "DELETE /api/catalog/collections/c2", "PUT /api/catalog/items/i1/collections/c1"), requests.map { "${it.method} ${it.path}" })
            assertEquals("Bearer token", requests[0].getHeader("Authorization"))
            assertEquals("Favourites", Json.parseToJsonElement(requests[1].body.readUtf8()).jsonObject["name"]?.jsonPrimitive?.content)
            assertEquals("Best", Json.parseToJsonElement(requests[2].body.readUtf8()).jsonObject["name"]?.jsonPrimitive?.content)
            assertEquals("false", Json.parseToJsonElement(requests[4].body.readUtf8()).jsonObject["present"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `answers map to outcomes, and an older server without the rename route keeps the rename pending`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(409))   // create: name taken
            server.enqueue(MockResponse().setResponseCode(404))   // rename: gone
            server.enqueue(MockResponse().setResponseCode(405))   // rename: no such route
            server.enqueue(MockResponse().setResponseCode(404))   // delete: already gone
            server.enqueue(MockResponse().setResponseCode(500))   // member: server trouble
            val client = api(server)
            runBlocking {
                assertEquals(CallOutcome.Conflict, client.create("Kids").outcome)
                assertEquals(CallOutcome.NotFound, client.rename("c1", "Family").outcome)
                assertEquals(CallOutcome.Failed, client.rename("c1", "Family").outcome)
                assertEquals(CallOutcome.NotFound, client.delete("c1").outcome)
                assertEquals(CallOutcome.Failed, client.setMember("c1", "i1", true).outcome)
            }
        }
    }

    @Test
    fun `an unreachable server fails every call and lists nothing`() {
        val server = MockWebServer().apply { start() }
        val client = api(server)
        server.shutdown()
        runBlocking {
            assertNull(client.list())
            assertEquals(CallOutcome.Failed, client.create("Kids").outcome)
            assertEquals(CallOutcome.Failed, client.delete("c1").outcome)
        }
    }

    @Test
    fun `the catalog reads each title's collections`() {
        val item = Json { ignoreUnknownKeys = true }.decodeFromString<RemoteCatalogItem>(
            """{"id":"i1","title":"Dune","collections":[{"id":"c1","name":"Kids"}],"sources":[]}""",
        )
        assertEquals(listOf(RemoteCollection("c1", "Kids")), item.collections)
    }
}
