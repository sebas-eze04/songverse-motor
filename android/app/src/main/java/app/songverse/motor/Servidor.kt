package app.songverse.motor

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// ============================================================
// El servidor del motor: HTTP mínimo en 127.0.0.1:47321, el mismo protocolo que
// el motor de Windows (POST con JSON; responde JSON, `{ error }` si algo falla).
//
// Solo atiende a 127.0.0.1/localhost (contra el «DNS rebinding») y solo a las
// apps con WebView (Tauri) de este mismo dispositivo, por su cabecera Origin:
// una página web no puede tener ese origen.
// ============================================================
class Servidor {
    private val allowedOrigins = setOf("http://tauri.localhost", "https://tauri.localhost")
    private val maxBody = 24 * 1024 * 1024
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var socket: ServerSocket? = null

    /** false si el puerto ya está ocupado (otro motor en marcha). */
    fun start(): Boolean {
        if (socket != null) return true
        val server = try {
            ServerSocket(Motor.PORT, 50, InetAddress.getByName("127.0.0.1"))
        } catch (error: Exception) {
            return false
        }
        socket = server
        thread(name = "songverse-http", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (error: Exception) {
                    break
                }
                pool.execute {
                    try {
                        client.soTimeout = 30_000
                        handle(client)
                    } catch (_: Exception) {
                        /* una conexión que se corta no tumba el servidor */
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readLine(input: InputStream, limit: Int = 8192): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < limit) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    private fun read(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val cut = line.indexOf(':')
            if (cut > 0) headers[line.substring(0, cut).trim().lowercase()] = line.substring(cut + 1).trim()
            if (headers.size > 100) return null
        }
        val length = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, maxBody)
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers, if (read == length) body else body.copyOf(read))
    }

    private fun respond(output: OutputStream, origin: String?, code: Int, contentType: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            410 -> "Gone"
            else -> "Error"
        }
        val head = StringBuilder("HTTP/1.1 $code $reason\r\n")
        head.append("Content-Type: $contentType\r\n")
        head.append("Content-Length: ${body.size}\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n")
        if (origin != null) {
            head.append("Access-Control-Allow-Origin: $origin\r\n")
            head.append("Vary: Origin\r\n")
            head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            head.append("Access-Control-Allow-Headers: Content-Type\r\n")
            head.append("Access-Control-Allow-Private-Network: true\r\n")
            head.append("Access-Control-Max-Age: 600\r\n")
        }
        for ((key, value) in extra) head.append("$key: $value\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun json(output: OutputStream, origin: String?, code: Int, value: Any) =
        respond(output, origin, code, "application/json; charset=utf-8", value.toString().toByteArray(Charsets.UTF_8))

    private fun handle(client: Socket) {
        val input = client.getInputStream().buffered()
        val output = client.getOutputStream().buffered()
        val request = read(input) ?: return
        val host = request.headers["host"].orEmpty()
        if (host != "127.0.0.1:${Motor.PORT}" && host != "localhost:${Motor.PORT}") {
            return json(output, null, 403, JSONObject().put("error", "host"))
        }
        val origin = request.headers["origin"]?.takeIf { it in allowedOrigins }
            ?: return json(output, null, 403, JSONObject().put("error", "origen no permitido"))
        if (request.method == "OPTIONS") return respond(output, origin, 204, "application/json; charset=utf-8", ByteArray(0))

        Motor.lastActivity = System.currentTimeMillis()
        val body = runCatching { JSONObject(request.body.toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }

        // El archivo terminado va tal cual, no en JSON.
        if (request.path == "/file") {
            val file: File = Motor.finishedFile(body.optLong("id"))
                ?: return json(output, origin, 404, JSONObject().put("error", "Ese trabajo no tiene un archivo terminado."))
            if (!file.exists()) return json(output, origin, 410, JSONObject().put("error", "El archivo ya no está en su carpeta."))
            val mime = if (file.extension.lowercase() == "mp3") "audio/mpeg" else "application/octet-stream"
            return respond(output, origin, 200, mime, file.readBytes(), mapOf("X-Content-Type-Options" to "nosniff"))
        }

        val result: Any = try {
            when (request.path) {
                "/status" -> Motor.status()
                "/search" -> Motor.search(body.optString("q"), body.optInt("limit", 10))
                "/info" -> Motor.info(body.optString("url"))
                "/fetch" -> Motor.fetch(body)
                "/song" -> Motor.song(body)
                "/jobs" -> Motor.jobs(body.optJSONArray("ids"))
                "/jobs/clear" -> Motor.clearJobs()
                "/pause" -> Motor.pause(body.optLong("id"))
                "/resume" -> Motor.resume(body.optLong("id"))
                "/cancel" -> Motor.cancel(body.optLong("id"))
                "/forget" -> Motor.forget(body.optLong("id"))
                "/update" -> JSONObject().put("engine", Motor.updateEngine())
                "/repair" -> JSONObject().put("engine", Motor.repairYtdlp())
                // Lo que en Windows abre carpetas o guarda un índice: aquí no hay nada que abrir.
                "/manifest", "/open" -> JSONObject().put("ok", true)
                "/choose" -> JSONObject().put("path", JSONObject.NULL)
                "/video" -> throw MotorError("Los vídeos no están disponibles en Android.")
                else -> throw MotorError("Ruta desconocida")
            }
        } catch (error: MotorError) {
            return json(output, origin, 400, JSONObject().put("error", error.message ?: "Error"))
        } catch (error: Throwable) {
            return json(output, origin, 400, JSONObject().put("error", (error.message ?: error.toString()).take(300)))
        }
        json(output, origin, 200, result)
    }
}
