package io.github.haigerman.lucidscanner.cloud

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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
    fun testConnection(folder: String): String {
        val root = resolveDavRoot()
        execute(Request.Builder().url(root).method("PROPFIND", null).header("Depth", "0")).use { r ->
            when (r.code) {
                207, 200 -> Unit
                else -> throw failure(r.code, "Verbindung")
            }
        }
        val segments = splitFolder(folder)
        if (segments.isEmpty()) return "Verbindung steht."
        execute(Request.Builder().url(child(root, segments)).method("PROPFIND", null).header("Depth", "0")).use { r ->
            return when (r.code) {
                207, 200 -> "Verbindung steht, Ordner gefunden."
                404 -> "Verbindung steht. Der Ordner wird beim ersten Scan angelegt."
                else -> throw failure(r.code, "Ordner prüfen")
            }
        }
    }

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
