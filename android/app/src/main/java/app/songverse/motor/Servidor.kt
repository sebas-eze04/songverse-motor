package app.songverse.motor

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// ============================================================
// El servidor del motor: HTTP mínimo en 127.0.0.1:47321, el mismo protocolo que
// el motor de Windows (POST con JSON; responde JSON, `{ error }` si algo falla).
//
// Solo atiende a 127.0.0.1/localhost (contra el «DNS rebinding») y solo a las
// apps con WebView (Tauri) de este mismo dispositivo, por su cabecera Origin:
// una página web no puede tener ese origen.
//
// Desde 1.2.2, con llaves (Llaves.kt): la app que entregó una llave firma
// cada petición y el motor firma cada respuesta, para que la app sepa que quien
// contesta en el puerto es el motor y no otra app que lo ocupó.
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

    /** Una petición firmada: con qué llave llegó y lo que hace falta para firmar su respuesta. */
    private class Canal(val id: String, val key: ByteArray, val nonce: String, val path: String)

    /** La petición trae llave, pero no vale: `llave` (el motor no la conoce) o `firma`. */
    private class Rechazo(val why: String) : Exception(why)

    private val keyId = Regex("^[0-9a-f]{16}$")
    private val nonceShape = Regex("^[0-9a-f]{32}$")

    /** Números de petición ya vistos: una petición firmada no sirve dos veces. */
    private val seen = HashMap<String, Long>()

    private fun firstTime(nonce: String): Boolean = synchronized(seen) {
        val now = System.currentTimeMillis()
        if (seen.size > 4096) seen.entries.removeIf { now - it.value > 2 * MAX_SKEW_MS }
        seen.put(nonce, now) == null
    }

    /**
     * Quién pide: `null` si la petición no trae llave (una app anterior a las llaves: se atiende
     * sin firma). Si la trae, tiene que ser una que el motor conozca y la firma tiene que cuadrar
     * con la ruta, el cuerpo y la hora; si no, `Rechazo`.
     */
    private fun canalOf(request: Request): Canal? {
        val id = request.headers["x-motor-key"] ?: return null
        val key = (if (keyId.matches(id)) Llaves.find(id) else null) ?: throw Rechazo("llave")
        val nonce = request.headers["x-motor-nonce"].orEmpty()
        val time = request.headers["x-motor-time"].orEmpty()
        val at = time.toLongOrNull()
        if (!nonceShape.matches(nonce) || at == null || kotlin.math.abs(System.currentTimeMillis() - at) > MAX_SKEW_MS) throw Rechazo("firma")
        val expected = Llaves.hmac(key, "req\n$nonce\n$time\n${request.path}\n${Llaves.hex(Llaves.sha256(request.body))}")
        if (!Llaves.same(expected, request.headers["x-motor-auth"].orEmpty().lowercase())) throw Rechazo("firma")
        if (!firstTime(nonce)) throw Rechazo("firma")
        return Canal(id, key, nonce, request.path)
    }

    /** La firma de una respuesta: ata el cuerpo a la petición que la pidió. */
    private fun proof(canal: Canal, code: Int, bodyHash: ByteArray): String =
        Llaves.hmac(canal.key, "res\n${canal.nonce}\n${canal.path}\n$code\n${Llaves.hex(bodyHash)}")

    private fun head(origin: String?, code: Int, contentType: String, length: Long, extra: Map<String, String>): ByteArray {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            410 -> "Gone"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = StringBuilder("HTTP/1.1 $code $reason\r\n")
        head.append("Content-Type: $contentType\r\n")
        head.append("Content-Length: $length\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n")
        if (origin != null) {
            head.append("Access-Control-Allow-Origin: $origin\r\n")
            head.append("Vary: Origin\r\n")
            head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            head.append("Access-Control-Allow-Headers: Content-Type, X-Motor-Key, X-Motor-Nonce, X-Motor-Time, X-Motor-Auth\r\n")
            head.append("Access-Control-Expose-Headers: X-Motor-Proof\r\n")
            head.append("Access-Control-Allow-Private-Network: true\r\n")
            head.append("Access-Control-Max-Age: 600\r\n")
        }
        for ((key, value) in extra) head.append("$key: $value\r\n")
        head.append("\r\n")
        return head.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun respond(output: OutputStream, origin: String?, code: Int, contentType: String, body: ByteArray, canal: Canal? = null) {
        val extra = if (canal != null) mapOf("X-Motor-Proof" to proof(canal, code, Llaves.sha256(body))) else emptyMap()
        output.write(head(origin, code, contentType, body.size.toLong(), extra))
        output.write(body)
        output.flush()
    }

    /** Un archivo, sin cargarlo entero en memoria: primero su huella (para la firma), luego su contenido. */
    private fun respondFile(output: OutputStream, origin: String?, contentType: String, file: File, canal: Canal?) {
        val extra = HashMap<String, String>()
        extra["X-Content-Type-Options"] = "nosniff"
        val length = file.length()
        if (canal != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            extra["X-Motor-Proof"] = proof(canal, 200, digest.digest())
        }
        output.write(head(origin, 200, contentType, length, extra))
        file.inputStream().use { it.copyTo(output, 64 * 1024) }
        output.flush()
    }

    private fun json(output: OutputStream, origin: String?, code: Int, value: Any, canal: Canal? = null) =
        respond(output, origin, code, "application/json; charset=utf-8", value.toString().toByteArray(Charsets.UTF_8), canal)

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
        val canal = try {
            canalOf(request)
        } catch (error: Rechazo) {
            // Sin firma: quien pide no demostró tener una llave, así que tampoco se le demuestra nada.
            return json(output, origin, 401, JSONObject().put("error", error.why))
        }
        // De quién son los trabajos: de la llave que los pidió ("" = las apps sin llave, juntas).
        val owner = canal?.id.orEmpty()
        val body = runCatching { JSONObject(request.body.toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }

        // El archivo terminado va tal cual, no en JSON.
        if (request.path == "/file") {
            val file: File = Motor.finishedFile(body.optLong("id"), owner)
                ?: return json(output, origin, 404, JSONObject().put("error", "Ese trabajo no tiene un archivo terminado."), canal)
            if (!file.exists()) return json(output, origin, 410, JSONObject().put("error", "El archivo ya no está en su carpeta."), canal)
            if (file.length() > MAX_FILE) return json(output, origin, 413, JSONObject().put("error", "El archivo es demasiado grande."), canal)
            val mime = if (file.extension.lowercase() == "mp3") "audio/mpeg" else "application/octet-stream"
            return respondFile(output, origin, mime, file, canal)
        }

        val result: Any = try {
            when (request.path) {
                "/status" -> Motor.status()
                "/search" -> Motor.search(body.optString("q"), body.optInt("limit", 10))
                "/info" -> Motor.info(body.optString("url"))
                "/fetch" -> Motor.fetch(body)
                "/song" -> Motor.song(body, owner)
                "/jobs" -> Motor.jobs(body.optJSONArray("ids"), owner)
                "/jobs/clear" -> Motor.clearJobs(owner)
                "/pause" -> Motor.pause(body.optLong("id"), owner)
                "/resume" -> Motor.resume(body.optLong("id"), owner)
                "/cancel" -> Motor.cancel(body.optLong("id"), owner)
                "/forget" -> Motor.forget(body.optLong("id"), owner)
                "/update" -> JSONObject().put("engine", Motor.updateEngine())
                "/repair" -> JSONObject().put("engine", Motor.repairYtdlp())
                // Lo que en Windows abre carpetas o guarda un índice: aquí no hay nada que abrir.
                "/manifest", "/open" -> JSONObject().put("ok", true)
                "/choose" -> JSONObject().put("path", JSONObject.NULL)
                "/video" -> throw MotorError("Los vídeos no están disponibles en Android.")
                else -> throw MotorError("Ruta desconocida")
            }
        } catch (error: MotorError) {
            return json(output, origin, 400, JSONObject().put("error", error.message ?: "Error"), canal)
        } catch (error: Throwable) {
            return json(output, origin, 400, JSONObject().put("error", (error.message ?: error.toString()).take(300)), canal)
        }
        json(output, origin, 200, result, canal)
    }

    private companion object {
        /** Una petición firmada vale si su hora no se aparta de la del motor más que esto (es el mismo reloj). */
        const val MAX_SKEW_MS = 120_000L
        /** Un archivo mayor no es una canción. */
        const val MAX_FILE = 512L * 1024 * 1024
    }
}
