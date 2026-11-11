package app.songverse.motor

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

// ============================================================
// SongVerse para Android — el motor local (1.2.1).
//
// El mismo motor que en Windows, con el mismo protocolo: una app no puede bajar
// audio de YouTube por sí misma; este lo hace por ella, en el dispositivo del
// usuario. Escucha SOLO en 127.0.0.1 y solo atiende a las apps con WebView
// (Tauri) de este mismo dispositivo (cabecera Origin): ninguna web puede usarlo.
//
// Cada canción baja con yt-dlp en la mejor calidad, se convierte a MP3 de
// 320 kbps con ffmpeg y recibe sus etiquetas ID3: título, artista, álbum,
// número de pista, portada y letra (sincronizada, si la hay; además en un .lrc
// al lado). La app que lo pidió recoge el archivo terminado con /file.
//
// Se mantiene solo: yt-dlp se pone al día al arrancar y cada 6 horas; si una
// descarga falla con olor a «YouTube cambió», se actualiza y se reintenta; un
// corte de red se reintenta a los pocos segundos.
// ============================================================

class MotorError(message: String) : Exception(message)

class Job(val id: Long, val kind: String, val title: String, val dest: String, val owner: String) {
    /** queued · running · paused · done · failed · cancelled */
    @Volatile var status = "queued"
    @Volatile var progress = 0.0
    @Volatile var downloaded = 0L
    @Volatile var total: Long? = null
    @Volatile var speed = 0.0
    @Volatile var eta: Long? = null
    @Volatile var file: String? = null
    @Volatile var rel: String? = null
    @Volatile var size: Long? = null
    @Volatile var error: String? = null

    fun json(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("title", title)
        put("status", status)
        put("progress", progress)
        put("downloaded", downloaded)
        put("total", total ?: JSONObject.NULL)
        put("speed", speed)
        put("eta", eta ?: JSONObject.NULL)
        put("dest", dest)
        put("file", file ?: JSONObject.NULL)
        put("rel", rel ?: JSONObject.NULL)
        put("size", size ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }
}

object Motor {
    const val PORT = 47321
    private const val WORKERS = 2

    /** Servicios a los que la app puede pedir algo a través del motor (letras y portadas). Lista cerrada. */
    private val FETCH_HOSTS = listOf(
        "api.lyrics.ovh", "c.y.qq.com", "y.qq.com", "lrclib.net", "music.youtube.com", "music.163.com", "lyrics.kugou.com",
        "open.spotify.com", "scdn.co", "spotifycdn.com", "api.deezer.com", "dzcdn.net", "itunes.apple.com", "mzstatic.com", "ytimg.com",
    )

    private lateinit var context: Context
    private val started = AtomicBoolean(false)
    private val jobs = ArrayList<Job>()
    private val queue = LinkedBlockingDeque<Long>()

    /** Lo pedido en cada trabajo, para reanudarlo tras una pausa. */
    private val tasks = ConcurrentHashMap<Long, JSONObject>()

    /** El proceso de yt-dlp que corre para cada trabajo (para pausar o cancelar). */
    private val running = ConcurrentHashMap<Long, String>()
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val updating = AtomicBoolean(false)

    /**
     * yt-dlp es un archivo que se reemplaza al ponerse al día: nadie lo ejecuta mientras se
     * reemplaza (lectura: cada ejecución; escritura: la puesta al día o la reparación).
     */
    private val ytdlpFile = ReentrantReadWriteLock()

    @Volatile var engineVersion = ""
        private set
    @Volatile var ready = false
        private set
    @Volatile var initError: String? = null
        private set
    @Volatile var lastActivity = System.currentTimeMillis()

    val musicDir: File
        get() = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "musica")).also { it.mkdirs() }

    fun busy(): Int = synchronized(jobs) { jobs.count { it.status == "queued" || it.status == "running" } }

    // ------------------------------------------------------------
    // Arranque
    // ------------------------------------------------------------

    fun start(appContext: Context) {
        if (started.getAndSet(true)) return
        context = appContext.applicationContext
        cleanStaleTemp()
        thread(name = "songverse-inicio", isDaemon = true) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                ready = true
                engineVersion = readVersion()
                // Al arrancar, yt-dlp se pone al día solo (YouTube cambia cada pocas semanas y lo rompe).
                runCatching { updateEngine() }
            } catch (error: Throwable) {
                initError = error.message ?: error.toString()
            }
        }
        repeat(WORKERS) { n -> thread(name = "songverse-trabajo-$n", isDaemon = true) { worker() } }
        thread(name = "songverse-al-dia", isDaemon = true) { updateLoop() }
    }

    private fun awaitReady() {
        val until = System.currentTimeMillis() + 90_000
        while (!ready && initError == null && System.currentTimeMillis() < until) Thread.sleep(200)
        initError?.let { throw MotorError("El motor no pudo iniciarse: $it") }
        if (!ready) throw MotorError("El motor aún se está preparando. Inténtalo en un momento.")
    }

    private fun readVersion(): String = runCatching {
        val request = YoutubeDLRequest(emptyList<String>())
        request.addOption("--version")
        ytdlpFile.read { YoutubeDL.getInstance().execute(request, null, null).out.trim() }
    }.getOrElse { "" }

    /** Pone al día yt-dlp (espera a que termine lo que lo esté usando). */
    fun updateEngine(): String {
        awaitReady()
        if (updating.getAndSet(true)) throw MotorError("Ya se está actualizando.")
        try {
            ytdlpFile.write { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
        } catch (error: Throwable) {
            throw MotorError(error.message ?: "No se pudo actualizar yt-dlp.")
        } finally {
            updating.set(false)
        }
        val version = readVersion()
        // Una descarga cortada deja un yt-dlp que no arranca: se rehace con el que trae el motor.
        if (version.isEmpty()) return repairYtdlp()
        engineVersion = version
        return version
    }

    /** Rehace la copia de yt-dlp con la que trae el motor (y la pone al día si puede). */
    fun repairYtdlp(): String {
        awaitReady()
        ytdlpFile.write {
            val dir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
            File(dir, YoutubeDL.ytdlpBin).delete()
            runCatching { YoutubeDL.getInstance().init_ytdlp(context, dir) }
        }
        var version = readVersion()
        if (version.isEmpty()) throw MotorError("yt-dlp no arranca en este dispositivo.")
        engineVersion = version
        if (!updating.getAndSet(true)) {
            try {
                runCatching { ytdlpFile.write { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) } }
            } finally {
                updating.set(false)
            }
            version = readVersion().ifEmpty { version }
            engineVersion = version
        }
        return version
    }

    /** yt-dlp no arranca: su copia se rompió (una puesta al día cortada). */
    private fun looksBroken(error: String): Boolean {
        val low = error.lowercase()
        return listOf("zipimporterror", "bad local file header", "badzipfile", "not a zip file", "can't find '__main__'").any { low.contains(it) }
    }

    /** Cada 6 horas, nunca a mitad de una descarga. */
    private fun updateLoop() {
        while (true) {
            Thread.sleep(6 * 3600_000L)
            while (busy() > 0) Thread.sleep(60_000)
            runCatching { updateEngine() }
        }
    }

    // ------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------

    private fun tempDirFor(id: Long) = File(context.cacheDir, "songverse-motor-$id")

    /** Al arrancar no corre ningún trabajo: los parciales de una ejecución anterior ya no los continuará nadie. */
    private fun cleanStaleTemp() {
        context.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("songverse-motor-") }?.forEach { it.deleteRecursively() }
    }

    /** Nombre de archivo seguro (los mismos caracteres que en Windows: el archivo puede acabar allí). */
    fun safeName(text: String, max: Int): String {
        val cleaned = text.map { c -> if (c in "/\\:*?\"<>|" || c.isISOControl()) ' ' else c }.joinToString("")
        var name = cleaned.trim().split(Regex("\\s+")).joinToString(" ").trimEnd('.')
        name = name.take(max).trim()
        return name.ifEmpty { "Sin título" }
    }

    private fun uniquePath(dir: File, stem: String, ext: String): File {
        var file = File(dir, "$stem.$ext")
        var n = 2
        while (file.exists()) {
            file = File(dir, "$stem ($n).$ext")
            n++
        }
        return file
    }

    /** Solo enlaces https de YouTube: un texto que empiece por «-» yt-dlp lo leería como opción. */
    fun checkedYoutube(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) throw MotorError("Solo enlaces de YouTube (https).")
        val host = trimmed.removePrefix("https://").split('/', '?', '#').first().lowercase()
        val ok = listOf("youtube.com", "youtu.be", "music.youtube.com", "youtube-nocookie.com").any { host == it || host.endsWith(".$it") }
        if (!ok) throw MotorError("Solo enlaces de YouTube.")
        return trimmed
    }

    private fun hostAllowed(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").split('/', '?', '#', ':').first().lowercase()
        return FETCH_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun decodeDataUrl(dataUrl: String?): ByteArray? {
        val payload = dataUrl?.substringAfter(";base64,", "")?.trim().orEmpty()
        if (payload.isEmpty()) return null
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }

    /** Un corte de red pasajero: se reintenta una vez, unos segundos después. */
    private fun looksTransient(error: String): Boolean {
        val low = error.lowercase()
        return listOf(
            "timed out", "timeout", "connection reset", "connection aborted", "remote end closed", "temporary failure", "getaddrinfo",
            "network is unreachable", "incomplete read", "http error 500", "http error 502", "http error 503", "http error 504", "unable to resolve host",
        ).any { low.contains(it) }
    }

    /** «YouTube cambió»: se pone al día yt-dlp y se reintenta. */
    private fun looksStale(error: String): Boolean {
        val low = error.lowercase()
        return listOf("403", "forbidden", "sign in to confirm", "nsig", "signature", "unable to extract", "please report this issue", "requested format is not available")
            .any { low.contains(it) }
    }

    // ------------------------------------------------------------
    // yt-dlp
    // ------------------------------------------------------------

    private val PROGRESS = Regex("""\[download]\s+([\d.]+)%\s+of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)(?:\s+at\s+([\d.]+)\s*([KMGT]?i?B)/s)?(?:\s+ETA\s+([\d:]+))?""")

    private fun bytes(number: String, unit: String): Double {
        val factor = when (unit.uppercase().removeSuffix("B").removeSuffix("I")) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "T" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (number.toDoubleOrNull() ?: 0.0) * factor
    }

    private fun seconds(clock: String?): Long? {
        val parts = clock?.split(':')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (parts.isEmpty()) return null
        return parts.fold(0L) { total, part -> total * 60 + part }
    }

    private fun job(id: Long): Job? = synchronized(jobs) { jobs.find { it.id == id } }

    /** El trabajo, solo si es de quien pregunta (`owner`: su llave; "" = las apps sin llave). */
    private fun job(id: Long, owner: String): Job? = job(id)?.takeIf { it.owner == owner }

    private fun status(id: Long): String = job(id)?.status ?: ""

    /**
     * Ejecuta yt-dlp para el trabajo `id` informando el avance (0–1) y los bytes, la velocidad y el
     * tiempo que falta al propio trabajo. Devuelve lo que imprimió. `expected` es el total que anunció la ficha.
     */
    private fun runYtdlp(id: Long, url: String, options: List<Pair<String, String?>>, expected: Long?, progress: (Double) -> Unit): String {
        awaitReady()
        val request = YoutubeDLRequest(url)
        for ((name, value) in options) if (value == null) request.addOption(name) else request.addOption(name, value)
        val processId = "sv-$id-${System.nanoTime()}"
        running[id] = processId
        ytdlpFile.readLock().lock()
        try {
            val response = YoutubeDL.getInstance().execute(request, processId) { _, _, line ->
                lastActivity = System.currentTimeMillis()
                val match = PROGRESS.find(line)
                if (match != null) {
                    val percent = (match.groupValues[1].toDoubleOrNull() ?: 0.0) / 100.0
                    val announced = bytes(match.groupValues[2], match.groupValues[3])
                    val downloaded = (announced * percent).toLong()
                    val total = expected?.takeIf { it >= downloaded } ?: announced.toLong().takeIf { it > 0 }
                    val speed = if (match.groupValues[4].isNotEmpty()) bytes(match.groupValues[4], match.groupValues[5]) else 0.0
                    val fraction = total?.let { (downloaded.toDouble() / it).coerceIn(0.0, 1.0) } ?: percent.coerceIn(0.0, 1.0)
                    job(id)?.let { j ->
                        j.downloaded = downloaded
                        j.total = total
                        j.speed = speed
                        j.eta = if (total != null && speed > 1.0) ((total - downloaded).coerceAtLeast(0) / speed).toLong() else seconds(match.groupValues[6].ifEmpty { null })
                    }
                    progress(fraction)
                } else if (line.trimStart().startsWith("[ExtractAudio]")) {
                    // Ya bajó todo y convierte (yt-dlp no da avance de eso).
                    progress(1.0)
                }
            }
            return response.out
        } catch (error: YoutubeDL.CanceledException) {
            throw MotorError(if (status(id) == "paused") "En pausa." else "Cancelada.")
        } catch (error: YoutubeDLException) {
            if (status(id) == "paused") throw MotorError("En pausa.")
            if (status(id) == "cancelled") throw MotorError("Cancelada.")
            val text = (error.message ?: "").lines().filter { it.contains("ERROR") }.joinToString("\n") { it.trim() }
            throw MotorError(text.ifEmpty { error.message?.trim()?.takeLast(300) ?: "La descarga falló." })
        } finally {
            ytdlpFile.readLock().unlock()
            running.remove(id)
            job(id)?.speed = 0.0
        }
    }

    /** Reintenta UNA vez: tras poner al día yt-dlp si el fallo huele a «YouTube cambió» y, tras unos segundos, si la red se cortó. */
    private fun runWithRepair(id: Long, url: String, options: List<Pair<String, String?>>, expected: Long?, progress: (Double) -> Unit): String {
        try {
            return runYtdlp(id, url, options, expected, progress)
        } catch (error: MotorError) {
            val message = error.message ?: ""
            if (status(id) != "running") throw error
            if (looksBroken(message)) {
                repairYtdlp()
                return runYtdlp(id, url, options, expected, progress)
            }
            if (looksStale(message)) {
                runCatching { updateEngine() }
                return runYtdlp(id, url, options, expected, progress)
            }
            if (looksTransient(message)) {
                Thread.sleep(5000)
                if (status(id) != "running") throw error
                return runYtdlp(id, url, options, expected, progress)
            }
            throw error
        }
    }

    private fun killJob(id: Long) {
        running.remove(id)?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
    }

    // ------------------------------------------------------------
    // Trabajos
    // ------------------------------------------------------------

    private fun enqueue(kind: String, title: String, dest: String, request: JSONObject, owner: String): Long {
        val id = nextId.getAndIncrement()
        synchronized(jobs) { jobs.add(Job(id, kind, title, dest, owner)) }
        tasks[id] = request
        queue.addLast(id)
        return id
    }

    /** Pausa: se mata el yt-dlp; el archivo parcial queda y yt-dlp lo continúa al reanudar. */
    fun pause(id: Long, owner: String): JSONObject {
        val job = job(id, owner)
        if (job == null || (job.status != "running" && job.status != "queued")) throw MotorError("Ese trabajo no se puede pausar.")
        job.status = "paused"
        job.speed = 0.0
        job.eta = null
        queue.remove(id)
        killJob(id)
        return JSONObject().put("ok", true)
    }

    fun resume(id: Long, owner: String): JSONObject {
        val job = job(id, owner)
        if (job == null || job.status != "paused") throw MotorError("Ese trabajo no está en pausa.")
        if (!tasks.containsKey(id)) throw MotorError("Trabajo desconocido.")
        job.status = "queued"
        job.error = null
        queue.addLast(id)
        return JSONObject().put("ok", true)
    }

    fun cancel(id: Long, owner: String): JSONObject {
        // El trabajo de otro cliente ni se toca ni se dice que existe.
        val mine = job(id, owner) ?: return JSONObject().put("ok", true)
        mine.let { job ->
            if (job.status != "done") {
                job.status = "cancelled"
                job.speed = 0.0
                job.eta = null
            }
        }
        queue.remove(id)
        killJob(id)
        tasks.remove(id)
        tempDirFor(id).deleteRecursively()
        return JSONObject().put("ok", true)
    }

    private fun worker() {
        while (true) {
            val id = queue.takeFirst()
            val job = job(id) ?: continue
            // Pausado o cancelado mientras esperaba su turno: no se toca.
            if (job.status != "queued") continue
            val request = tasks[id] ?: continue
            job.status = "running"
            lastActivity = System.currentTimeMillis()
            try {
                val (file, rel) = downloadSong(id, request)
                tasks.remove(id)
                // Lo que ocupa en disco: el archivo y su .lrc, si se escribió.
                val lrc = File(file.parentFile, file.nameWithoutExtension + ".lrc")
                job.size = file.length() + (if (lrc.exists()) lrc.length() else 0)
                job.file = file.absolutePath
                job.rel = rel
                job.progress = 1.0
                job.speed = 0.0
                job.eta = 0
                job.total?.let { job.downloaded = it }
                job.status = "done"
            } catch (error: Throwable) {
                // Una pausa o cancelación no es un fallo.
                if (job.status == "running") {
                    job.error = (error.message ?: error.toString()).take(300)
                    job.status = "failed"
                }
            }
            lastActivity = System.currentTimeMillis()
        }
    }

    private fun downloadSong(id: Long, req: JSONObject): Pair<File, String?> {
        val url = checkedYoutube(req.optString("url"))
        val title = req.optString("title").trim()
        val artist = req.optString("artist").trim()
        val base = musicDir
        val folder = req.optString("folder").takeIf { it.isNotBlank() }?.let { safeName(it, 80) }
        val dir = if (folder != null) File(base, folder) else base
        if (!dir.exists() && !dir.mkdirs()) throw MotorError("No se pudo crear la carpeta.")

        // Carpeta temporal fija por trabajo: al reanudar, yt-dlp sigue el .part.
        val temp = tempDirFor(id)
        temp.mkdirs()
        // Una pausa durante la conversión deja un MP3 a medias: yt-dlp lo daría por bueno al reanudar.
        File(temp, "audio.mp3").delete()
        val kbps = req.optInt("bitrate", 320).takeIf { it in listOf(128, 192, 256, 320) } ?: 320
        val options = mutableListOf<Pair<String, String?>>(
            "-f" to "bestaudio/best",
            "-x" to null,
            "--audio-format" to "mp3",
            "--audio-quality" to "${kbps}K",
            "--continue" to null,
            "--no-playlist" to null,
            "--no-warnings" to null,
            "--newline" to null,
            "--progress" to null,
            "-o" to File(temp, "audio.%(ext)s").absolutePath,
        )
        val coverBytes = decodeDataUrl(req.optString("cover").takeIf { it.isNotBlank() })
        if (coverBytes == null) {
            options += "--write-thumbnail" to null
            options += "--convert-thumbnails" to "jpg"
        }
        val expected = req.optLong("expected", 0).takeIf { it > 0 }
        val mp3 = File(temp, "audio.mp3")
        try {
            runWithRepair(id, url, options, expected) { p -> job(id)?.progress = p * 0.9 }
        } catch (error: MotorError) {
            if (status(id) != "running" || !mp3.exists()) {
                // Pausa o cancelación: el parcial se queda (yt-dlp lo continúa); el fallo real, no.
                if (status(id) == "running") temp.deleteRecursively()
                throw error
            }
        }
        if (!mp3.exists() || mp3.length() == 0L) {
            temp.deleteRecursively()
            throw MotorError("La descarga terminó sin archivo.")
        }
        job(id)?.progress = 0.95

        // Etiquetas: lo que dijo la app (la búsqueda, el servicio de música) manda.
        val cover = coverBytes ?: File(temp, "audio.jpg").takeIf { it.exists() }?.readBytes()
        val lyrics = req.optString("lyrics").trim().takeIf { it.isNotEmpty() }
        Id3.write(
            mp3,
            Id3.Tags(
                title = title,
                artist = artist,
                album = req.optString("album").trim(),
                track = req.optInt("track", 0).takeIf { it > 0 },
                year = req.optInt("year", 0).takeIf { it > 1900 },
                albumArtist = req.optString("album_artist").trim().ifEmpty { artist },
                comment = "SongVerse",
                cover = cover,
                lyrics = lyrics,
            ),
        )

        val target = uniquePath(dir, safeName("$artist - $title", 150), "mp3")
        if (!mp3.renameTo(target)) {
            mp3.copyTo(target, overwrite = true)
            mp3.delete()
        }
        // La letra con tiempos también al lado, para cualquier reproductor.
        if (lyrics != null && lyrics.lines().any { it.trimStart().startsWith("[") && it.contains(":") }) {
            runCatching { File(target.parentFile, target.nameWithoutExtension + ".lrc").writeText(lyrics) }
        }
        temp.deleteRecursively()
        val rel = runCatching { target.relativeTo(base).path.replace('\\', '/') }.getOrNull()
        return target to rel
    }

    // ------------------------------------------------------------
    // Peticiones de la app
    // ------------------------------------------------------------

    fun status(): JSONObject = JSONObject().apply {
        put("app", "songverse-motor")
        put("platform", "android")
        put("version", BuildConfig.VERSION_NAME)
        put("engine", engineVersion)
        put("ready", ready)
        put("music_dir", musicDir.absolutePath)
        put("video_dir", musicDir.absolutePath)
        put("downloads_dir", musicDir.absolutePath)
        put("busy", busy())
        initError?.let { put("error", it) }
    }

    private fun dump(target: String, vararg flags: String): JSONObject {
        awaitReady()
        val request = YoutubeDLRequest(target)
        request.addOption("-J")
        request.addOption("--no-warnings")
        for (flag in flags) request.addOption(flag)
        val out = try {
            ytdlpFile.read { YoutubeDL.getInstance().execute(request, null, null).out }
        } catch (error: YoutubeDLException) {
            throw MotorError((error.message ?: "").trim().takeLast(300).ifEmpty { "No se pudo leer." })
        }
        return runCatching { JSONObject(out) }.getOrElse { throw MotorError("No se pudo leer la respuesta de yt-dlp.") }
    }

    /** Una consulta (búsqueda o ficha), con un reintento si yt-dlp está roto o «YouTube cambió». */
    private fun dumpWithRepair(target: String, vararg flags: String): JSONObject {
        try {
            return dump(target, *flags)
        } catch (error: MotorError) {
            val message = error.message ?: ""
            when {
                looksBroken(message) -> repairYtdlp()
                looksStale(message) -> runCatching { updateEngine() }
                else -> throw error
            }
            return dump(target, *flags)
        }
    }

    fun search(query: String, limit: Int): JSONArray {
        val q = query.trim()
        if (q.isEmpty()) return JSONArray()
        val data = dumpWithRepair("ytsearch${limit.coerceIn(1, 20)}:$q", "--flat-playlist")
        val entries = data.optJSONArray("entries") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            out.put(JSONObject().apply {
                put("id", id)
                put("url", "https://www.youtube.com/watch?v=$id")
                put("title", entry.opt("title") ?: JSONObject.NULL)
                put("channel", entry.optString("channel").ifEmpty { entry.optString("uploader") }.ifEmpty { null } ?: JSONObject.NULL)
                put("duration", entry.opt("duration") ?: JSONObject.NULL)
                put("thumbnail", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
            })
        }
        return out
    }

    /** Ficha de un vídeo concreto: título, canal, duración, datos de la canción y peso del mejor audio. */
    fun info(rawUrl: String): JSONObject {
        val url = checkedYoutube(rawUrl)
        val data = dumpWithRepair(url, "--no-playlist")
        val formats = data.optJSONArray("formats") ?: JSONArray()
        var audioSize = 0L
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val audioOnly = f.optString("acodec", "none") != "none" && f.optString("vcodec", "none") == "none"
            if (!audioOnly) continue
            val size = f.optLong("filesize", 0).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0)
            if (size > audioSize) audioSize = size
        }
        val id = data.optString("id")
        val thumbs = data.optJSONArray("thumbnails") ?: JSONArray()
        var best: String? = null
        var bestArea = -1L
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val thumbUrl = t.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val area = t.optLong("width", 0) * t.optLong("height", 0)
            if (area > bestArea) {
                bestArea = area
                best = thumbUrl
            }
        }
        val year: Any = data.optInt("release_year", 0).takeIf { it > 0 }
            ?: data.optString("upload_date").take(4).toIntOrNull()
            ?: JSONObject.NULL
        return JSONObject().apply {
            put("id", id)
            put("title", data.opt("title") ?: JSONObject.NULL)
            put("channel", data.optString("channel").ifEmpty { data.optString("uploader") }.ifEmpty { null } ?: JSONObject.NULL)
            put("duration", data.opt("duration") ?: JSONObject.NULL)
            put("thumbnail", data.opt("thumbnail") ?: JSONObject.NULL)
            put("thumbnail_max", best ?: "https://i.ytimg.com/vi/$id/maxresdefault.jpg")
            put("heights", JSONArray())
            put("sizes", JSONObject())
            put("audio_size", audioSize)
            put("artist", data.opt("artist") ?: JSONObject.NULL)
            put("track", data.opt("track") ?: JSONObject.NULL)
            put("album", data.opt("album") ?: JSONObject.NULL)
            put("year", year)
        }
    }

    fun fetch(body: JSONObject): JSONObject {
        val url = body.optString("url")
        if (!hostAllowed(url)) throw MotorError("Servicio no permitido.")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = if (body.optString("method", "GET").uppercase() == "POST") "POST" else "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            body.optJSONObject("headers")?.let { headers ->
                for (key in headers.keys()) headers.optString(key).takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty(key, it) }
            }
            if (body.has("body") && !body.isNull("body")) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.optString("body").toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status >= 400) {
                val text = runCatching { connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
                return JSONObject().put("status", status).put("text", text)
            }
            val max = 12 * 1024 * 1024
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() < max) {
                    val read = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            return if (body.optBoolean("binary", false)) {
                JSONObject().put("status", status).put("mime", connection.contentType ?: "").put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } else {
                JSONObject().put("status", status).put("text", bytes.toString(Charsets.UTF_8))
            }
        } catch (error: java.io.IOException) {
            throw MotorError(error.message ?: "Sin conexión.")
        } finally {
            connection.disconnect()
        }
    }

    fun song(body: JSONObject, owner: String): JSONObject {
        checkedYoutube(body.optString("url"))
        if (body.optString("title").isBlank()) throw MotorError("Falta el título.")
        val title = "${body.optString("artist")} — ${body.optString("title")}"
        return JSONObject().put("job", enqueue("song", title, "music", body, owner))
    }

    fun jobs(ids: JSONArray?, owner: String): JSONArray {
        val wanted = ids?.let { list -> (0 until list.length()).map { list.optLong(it) }.toSet() }
        val out = JSONArray()
        synchronized(jobs) { jobs.filter { it.owner == owner && (wanted == null || it.id in wanted) }.forEach { out.put(it.json()) } }
        return out
    }

    fun clearJobs(owner: String): JSONObject {
        synchronized(jobs) { jobs.removeAll { it.owner == owner && it.status != "queued" && it.status != "running" && it.status != "paused" } }
        return JSONObject().put("ok", true)
    }

    /** El archivo terminado de un trabajo. Solo los que hizo este motor (por su id): nunca una ruta que mande la app. */
    fun finishedFile(id: Long, owner: String): File? = job(id, owner)?.takeIf { it.status == "done" }?.file?.let { File(it) }

    /**
     * La app ya recogió el archivo y lo guardó en su biblioteca: la copia del motor sobra (en un
     * teléfono el espacio cuenta). Solo en Android.
     */
    fun forget(id: Long, owner: String): JSONObject {
        val file = finishedFile(id, owner)
        if (file != null) {
            file.delete()
            File(file.parentFile, file.nameWithoutExtension + ".lrc").delete()
            // La subcarpeta de la playlist, si quedó vacía.
            file.parentFile?.takeIf { it != musicDir && it.list()?.isEmpty() == true }?.delete()
        }
        return JSONObject().put("ok", true)
    }
}
