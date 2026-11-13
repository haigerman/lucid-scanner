package io.github.haigerman.lucidscanner

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import io.github.haigerman.lucidscanner.cloud.OpenCloudClient
import io.github.haigerman.lucidscanner.cloud.OpenCloudException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** Kleiner WebDAV-Server, der sich wie OpenCloud verhält (Graph-Abfrage, MKCOL, PUT, PROPFIND). */
class OpenCloudClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val log = ArrayList<String>()
    private val folders = HashSet<String>()
    private val files = HashMap<String, ByteArray>()
    private var graph = true
    private val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("anna:geheim-token".toByteArray())

    private val url get() = "http://127.0.0.1:${server.port}"

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val body = request.body.readByteArray()
                synchronized(log) { log += "${request.method} $path" }
                fun reply(code: Int, text: String = "") = MockResponse().setResponseCode(code).setBody(text)
                if (request.getHeader("Authorization") != expectedAuth) return reply(401)
                val space = "/dav/spaces/abc\$123"
                val legacy = "/remote.php/dav/files/anna"
                return when {
                    path == "/graph/v1.0/me/drives" ->
                        if (graph) reply(200, """{"value":[{"driveType":"project","root":{"webDavUrl":"$url/dav/spaces/other"}},
                            {"driveType":"personal","root":{"webDavUrl":"$url$space"}}]}""") else reply(404)
                    path.startsWith(space) || path.startsWith(legacy) -> {
                        val rel = path.removePrefix(space).removePrefix(legacy)
                        val parent = rel.substringBeforeLast('/')
                        when (request.method) {
                            "PROPFIND" -> reply(if (rel.isEmpty() || rel in folders) 207 else 404, "<d:multistatus xmlns:d=\"DAV:\"/>")
                            "MKCOL" -> when {
                                rel in folders -> reply(405)
                                parent.isNotEmpty() && parent !in folders -> reply(409)
                                else -> { folders += rel; reply(201) }
                            }
                            "PUT" -> when {
                                parent.isNotEmpty() && parent !in folders -> reply(409)
                                rel in files && request.getHeader("If-None-Match") == "*" -> reply(412)
                                else -> { files[rel] = body; reply(201) }
                            }
                            else -> reply(400)
                        }
                    }
                    else -> reply(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun pdf(size: Int = 200_000) = tmp.newFile("scan.pdf").also { f -> f.writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    @Test fun uploadsIntoPersonalSpaceAndCreatesFolders() {
        val file = pdf()
        var progress = 0f
        val remote = OpenCloudClient(url, "anna", "geheim-token")
            .upload("/Dokumente/Scans & Belege/", "Scan 2026-11-13 10.15.pdf", file) { progress = it }
        assertEquals("/Dokumente/Scans & Belege/Scan 2026-11-13 10.15.pdf", remote)
        assertEquals(1f, progress, 0.001f)
        assertEquals(setOf("/Dokumente", "/Dokumente/Scans%20&%20Belege"), folders)
        assertArrayEquals(file.readBytes(), files["/Dokumente/Scans%20&%20Belege/Scan%202026-11-13%2010.15.pdf"])
        assertEquals("GET /graph/v1.0/me/drives", log.first())
        assertTrue(log.all { it.startsWith("GET /graph") || it.contains("/dav/spaces/abc\$123/") })
    }

    @Test fun secondUploadNeedsOneRequest() {
        val client = OpenCloudClient(url, "anna", "geheim-token")
        val file = pdf(1000)
        client.upload("Scans", "a.pdf", file)
        log.clear()
        client.upload("Scans", "b.pdf", file)
        assertEquals(listOf("PUT /dav/spaces/abc\$123/Scans/b.pdf"), log)
    }

    @Test fun neverOverwrites() {
        val client = OpenCloudClient(url, "anna", "geheim-token")
        val file = pdf(1000)
        client.upload("Scans", "a.pdf", file)
        try {
            client.upload("Scans", "a.pdf", file)
            fail()
        } catch (e: OpenCloudException) {
            assertTrue(e.message!!.contains("schon eine Datei"))
        }
    }

    @Test fun fallsBackToClassicWebDavPath() {
        graph = false
        OpenCloudClient(url, "anna", "geheim-token").upload("Scans", "a.pdf", pdf(1000))
        assertTrue(log.contains("PUT /remote.php/dav/files/anna/Scans/a.pdf"))
    }

    @Test fun acceptsWebDavAddressOfASpace() {
        OpenCloudClient("$url/dav/spaces/abc\$123/", "anna", "geheim-token").upload("", "a.pdf", pdf(1000))
        assertEquals(listOf("PUT /dav/spaces/abc\$123/a.pdf"), log)
    }

    @Test fun wrongTokenIsReported() {
        try {
            OpenCloudClient(url, "anna", "falsch").upload("Scans", "a.pdf", pdf(1000))
            fail()
        } catch (e: OpenCloudException) {
            assertTrue(e.message!!.contains("App-Token"))
        }
    }

    @Test fun unreachableServerIsReported() {
        val port = server.port
        server.shutdown()
        try {
            OpenCloudClient("http://127.0.0.1:$port", "anna", "geheim-token").testConnection("Scans")
            fail()
        } catch (e: OpenCloudException) {
            assertTrue(e.message!!.contains("nicht erreichbar"))
        }
    }

    @Test fun connectionTest() {
        val client = OpenCloudClient(url, "anna", "geheim-token")
        assertTrue(client.testConnection("Scans").contains("angelegt"))
        client.upload("Scans", "a.pdf", pdf(1000))
        assertTrue(client.testConnection("Scans").contains("gefunden"))
    }

    @Test fun addressAndNameHelpers() {
        assertEquals("https://cloud.example.com/", OpenCloudClient.parseServerUrl(" cloud.example.com/ ").toString())
        assertEquals("http://192.168.1.5:9200/", OpenCloudClient.parseServerUrl("http://192.168.1.5:9200").toString())
        assertEquals(null, OpenCloudClient.parseServerUrl("  "))
        assertEquals(listOf("a", "b c"), OpenCloudClient.splitFolder("/a//b c/../"))
        assertEquals("Rechnung-2026.pdf", OpenCloudClient.pdfFileName(" Rechnung/2026.PDF ", "Scan"))
        assertEquals("Scan.pdf", OpenCloudClient.pdfFileName(" . ", "Scan"))
    }
}
