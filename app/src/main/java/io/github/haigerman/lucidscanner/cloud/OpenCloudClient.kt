package io.github.haigerman.lucidscanner.cloud

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

class OpenCloudException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Legt Dateien per WebDAV in OpenCloud ab. Anmeldung mit Benutzername und App-Token
 * (in OpenCloud unter Einstellungen → App-Tokens erzeugt).
 *
 * Als Adresse genügt die Adresse der OpenCloud (`cloud.example.com`); der persönliche Bereich
 * wird dann automatisch gefunden. Wer in einen anderen Space ablegen will, trägt dessen
 * WebDAV-Adresse ein (`https://cloud.example.com/dav/spaces/<Space-ID>`).
 */
class OpenCloudClient(
    serverUrl: String,
    private val user: String,
    token: String,
    private val http: OkHttpClient = defaultClient,
) {
    private val base: HttpUrl = parseServerUrl(serverUrl)
        ?: throw OpenCloudException("Die OpenCloud-Adresse ist ungültig.")
    private val auth = Credentials.basic(user, token, Charsets.UTF_8)
    private var davRoot: HttpUrl? = null

    /** Prüft Adresse und Anmeldung. Liefert eine kurze Erfolgsmeldung. */
    fun testConnection(): String {
        val root = resolveDavRoot()
        execute(Request.Builder().url(root).method("PROPFIND", null).header("Depth", "0")).use { r ->
            when (r.code) {
                207, 200 -> return "Verbindung steht."
                else -> throw failure(r.code, "Verbindung")
            }
        }
    }

    /** Namen der Unterordner von [folder], alphabetisch. */
    fun listFolders(folder: String): List<String> {
        val segments = splitFolder(folder)
        val url = child(resolveDavRoot(), segments)
        val body = PROPFIND_FOLDERS.toRequestBody("application/xml; charset=utf-8".toMediaType())
        execute(Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1")).use { r ->
            if (r.code == 404 && segments.isNotEmpty()) throw OpenCloudException("Diesen Ordner gibt es in OpenCloud nicht.")
            if (r.code != 207) throw failure(r.code, "Ordner lesen")
            return parseFolders(r.body?.string().orEmpty())
        }
    }

    /** Legt [folder] an (samt fehlender Ordner darüber). Gibt es ihn schon, passiert nichts. */
    fun createFolder(folder: String) = createFolders(resolveDavRoot(), splitFolder(folder))

    /**
     * Lädt [file] als [fileName] in [folder] hoch; fehlende Ordner werden angelegt.
     * Liefert den Pfad der Datei in OpenCloud.
     */
    fun upload(folder: String, fileName: String, file: File, onProgress: (Float) -> Unit = {}): String {
        val root = resolveDavRoot()
        val segments = splitFolder(folder)
        val target = child(root, segments + fileName)
        var code = put(target, file, onProgress)
        if (code == 404 || code == 409) {
            // Zielordner fehlt noch
            createFolders(root, segments)
            code = put(target, file, onProgress)
        }
        when (code) {
            200, 201, 204 -> Unit
            412 -> throw OpenCloudException("In OpenCloud gibt es schon eine Datei mit diesem Namen.")
            else -> throw failure(code, "Hochladen")
        }
        return "/" + (segments + fileName).joinToString("/")
    }

    // ------------------------------------------------------------------

    private fun put(target: HttpUrl, file: File, onProgress: (Float) -> Unit): Int {
        val body = object : RequestBody() {
            override fun contentType() = "application/pdf".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                val total = file.length().coerceAtLeast(1)
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        sent += n
                        onProgress(sent.toFloat() / total)
                    }
                }
            }
        }
        // If-None-Match: vorhandene Dateien nie überschreiben
        return execute(Request.Builder().url(target).put(body).header("If-None-Match", "*")).use { it.code }
    }

    private fun createFolders(root: HttpUrl, segments: List<String>) {
        for (depth in 1..segments.size) {
            val code = execute(Request.Builder().url(child(root, segments.take(depth))).method("MKCOL", null)).use { it.code }
            // 405: gibt es schon
            if (code != 201 && code != 405) throw failure(code, "Ordner anlegen")
        }
    }

    /** WebDAV-Wurzel des persönlichen Bereichs (oder die direkt eingetragene WebDAV-Adresse). */
    private fun resolveDavRoot(): HttpUrl {
        davRoot?.let { return it }
        val path = base.encodedPath
        val root = if (path.contains("/dav/") || path.contains("/webdav")) {
            base
        } else {
            discoverPersonalSpace() ?: base.newBuilder()
                .addPathSegments("remote.php/dav/files").addPathSegment(user).build()
        }
        davRoot = root
        return root
    }

    private fun discoverPersonalSpace(): HttpUrl? {
        val url = base.newBuilder().addPathSegments("graph/v1.0/me/drives").build()
        execute(Request.Builder().url(url).get()).use { r ->
            if (r.code == 401 || r.code == 403) throw failure(r.code, "Anmeldung")
            if (r.code != 200) return null
            return try {
                val drives = JSONObject(r.body?.string().orEmpty()).optJSONArray("value") ?: return null
                for (i in 0 until drives.length()) {
                    val drive = drives.optJSONObject(i) ?: continue
                    if (drive.optString("driveType") != "personal") continue
                    val dav = drive.optJSONObject("root")?.optString("webDavUrl").orEmpty()
                    return dav.trimEnd('/').toHttpUrlOrNull() ?: continue
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun execute(builder: Request.Builder): okhttp3.Response {
        try {
            return http.newCall(builder.header("Authorization", auth).build()).execute()
        } catch (e: UnknownHostException) {
            throw OpenCloudException("OpenCloud nicht gefunden – Adresse und Internetverbindung prüfen.", e)
        } catch (e: SSLException) {
            throw OpenCloudException("Keine sichere Verbindung zur OpenCloud möglich (${e.message}).", e)
        } catch (e: IOException) {
            throw OpenCloudException("OpenCloud nicht erreichbar (${e.message ?: e.javaClass.simpleName}).", e)
        }
    }

    private fun failure(code: Int, action: String): OpenCloudException = OpenCloudException(
        when (code) {
            401 -> "Anmeldung abgelehnt – Benutzername und App-Token prüfen."
            403 -> "Kein Schreibrecht für diesen Ort in OpenCloud."
            404 -> "Unter dieser Adresse wurde keine OpenCloud gefunden."
            507 -> "Der Speicherplatz in OpenCloud ist voll."
            else -> "$action fehlgeschlagen (HTTP $code)."
        }
    )

    private fun child(root: HttpUrl, segments: List<String>): HttpUrl {
        val b = root.newBuilder()
        for (s in segments) b.addPathSegment(s)
        return b.build()
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        private const val PROPFIND_FOLDERS =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

        /**
         * Liest aus einer WebDAV-Antwort (PROPFIND, Depth 1) die Namen der Unterordner.
         * Der abgefragte Ordner selbst steht mit in der Antwort – er hat den kürzesten Pfad.
         */
        fun parseFolders(xml: String): List<String> {
            val doc = try {
                DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(InputSource(StringReader(xml)))
            } catch (e: Exception) {
                throw OpenCloudException("Die Antwort der OpenCloud ließ sich nicht lesen.", e)
            }
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val folders = ArrayList<List<String>>()
            for (i in 0 until responses.length) {
                val response = responses.item(i) as? Element ?: continue
                if (response.getElementsByTagNameNS("DAV:", "collection").length == 0) continue
                val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
                val path = if (href.contains("://")) href.substringAfter("://").substringAfter('/', "") else href
                folders += path.substringBefore('?').split('/').filter { it.isNotEmpty() }.map(::percentDecode)
            }
            val depth = folders.minOfOrNull { it.size } ?: return emptyList()
            // nach deutscher Schreibweise sortieren: „Ärzte“ gehört zu A, nicht hinter Z
            val collator = java.text.Collator.getInstance(java.util.Locale.GERMAN)
            return folders.filter { it.size == depth + 1 }.map { it.last() }.sortedWith(collator::compare)
        }

        private fun percentDecode(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    out.write(hex)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        /** Einheitliche Schreibweise eines Ordnerpfads: `Dokumente/Scans`, leer für die oberste Ebene. */
        fun normalizeFolder(folder: String): String = splitFolder(folder).joinToString("/")

        /** Ergänzt `https://`, wenn kein Schema angegeben ist, und entfernt Schrägstriche am Ende. */
        fun parseServerUrl(input: String): HttpUrl? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "https://$s"
            return s.toHttpUrlOrNull()
        }

        /** Zerlegt einen Ordnerpfad wie `/Dokumente/Scans/` in seine Teile. */
        fun splitFolder(folder: String): List<String> =
            folder.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }

        /** Macht aus einem frei eingegebenen Namen einen gültigen PDF-Dateinamen. */
        fun pdfFileName(name: String, fallback: String): String {
            var s = name.trim().replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "-").trim('.', ' ')
            if (s.lowercase().endsWith(".pdf")) s = s.dropLast(4).trim('.', ' ')
            if (s.isEmpty()) s = fallback
            return "$s.pdf"
        }
    }
}
