package app.songverse.motor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

// ============================================================
// Etiquetas ID3v2.4 de un MP3: título, artista, álbum, número de pista, año,
// artista del álbum, comentario, portada (APIC) y letra (USLT), en UTF-8. Lo
// mismo que escribe el motor de Windows (crate `id3`, versión 2.4).
//
// ffmpeg deja su propia etiqueta al convertir: se quita y se pone esta.
// ============================================================
object Id3 {
    class Tags(
        val title: String,
        val artist: String,
        val album: String = "",
        val track: Int? = null,
        val year: Int? = null,
        val albumArtist: String = "",
        val comment: String = "",
        val cover: ByteArray? = null,
        val lyrics: String? = null,
    )

    private fun syncsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 10)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(syncsafe(payload.size))
        out.write(byteArrayOf(0, 0))
        out.write(payload)
        return out.toByteArray()
    }

    /** Marco de texto: codificación 3 (UTF-8) y el texto. */
    private fun text(id: String, value: String) = frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))

    /** COMM y USLT: codificación, idioma, descripción vacía y el texto. */
    private fun longText(id: String, value: String) =
        frame(id, byteArrayOf(3) + "und".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value.toByteArray(Charsets.UTF_8))

    private fun picture(data: ByteArray): ByteArray {
        val png = data.size > 4 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()
        val mime = if (png) "image/png" else "image/jpeg"
        // Codificación, tipo MIME, 0, tipo de imagen 3 (portada), descripción vacía, 0 y la imagen.
        return frame("APIC", byteArrayOf(3) + mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + data)
    }

    fun build(tags: Tags): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(text("TIT2", tags.title))
        frames.write(text("TPE1", tags.artist))
        if (tags.album.isNotBlank()) frames.write(text("TALB", tags.album))
        tags.track?.let { frames.write(text("TRCK", it.toString())) }
        tags.year?.takeIf { it > 1900 }?.let { frames.write(text("TDRC", it.toString())) }
        if (tags.albumArtist.isNotBlank()) frames.write(text("TPE2", tags.albumArtist))
        if (tags.comment.isNotBlank()) frames.write(longText("COMM", tags.comment))
        tags.cover?.takeIf { it.isNotEmpty() }?.let { frames.write(picture(it)) }
        tags.lyrics?.takeIf { it.isNotBlank() }?.let { frames.write(longText("USLT", it)) }
        val body = frames.toByteArray()
        val out = ByteArrayOutputStream(body.size + 10)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(4, 0, 0))
        out.write(syncsafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    /** Cuánto ocupa la etiqueta ID3v2 con que empieza el archivo (0 si no la hay). */
    private fun existingTagSize(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(10)
            if (raf.read(head) < 10) return 0
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
            val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            return 10L + size + footer
        }
    }

    /** Reescribe el MP3 con estas etiquetas (por un archivo temporal: nunca queda a medias). */
    fun write(mp3: File, tags: Tags) {
        val skip = existingTagSize(mp3)
        val temp = File(mp3.parentFile, mp3.name + ".etiquetas")
        temp.outputStream().buffered().use { out ->
            out.write(build(tags))
            mp3.inputStream().use { input ->
                var left = skip
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped <= 0) break
                    left -= skipped
                }
                input.copyTo(out)
            }
        }
        if (!mp3.delete() || !temp.renameTo(mp3)) {
            temp.copyTo(mp3, overwrite = true)
            temp.delete()
        }
    }
}
