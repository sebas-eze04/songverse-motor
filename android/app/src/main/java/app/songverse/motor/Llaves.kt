package app.songverse.motor

import android.content.Context
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ============================================================
// Las llaves de las apps que usan el motor (1.2.2).
//
// En Android cualquier app puede abrir un puerto en 127.0.0.1, también el del
// motor si lo encuentra libre, y contestar en su lugar. Para que la app que
// integra el motor sepa que habla con ÉL y no con otra, le entrega una llave al
// despertarlo: 32 bytes al azar, en el Intent que arranca el servicio. Ese
// Intent va dirigido al paquete del motor, cuya firma la app ya comprobó, así
// que nadie más lo lee. Desde entonces:
//   · la app firma cada petición con la llave (HMAC-SHA-256);
//   · el motor firma cada respuesta, cuerpo incluido.
// Quien ocupe el puerto no tiene la llave y no puede firmar nada.
//
// Cada llave es, además, un cliente distinto: sus trabajos no los ve ni los
// toca otro. Las peticiones sin llave (apps anteriores a esto) siguen
// funcionando, sin firma y con sus trabajos aparte.
//
// 1.2.3: la app puede, además, CIFRAR lo que pide (AES-256-GCM con una llave
// que sale de la compartida). Si otra app ocupa el puerto justo cuando el
// motor se cae, lo que le llegue no lo puede leer. Y cada arranque del motor
// es una sesión distinta (Servidor.kt): una petición capturada no sirve después.
// ============================================================
object Llaves {
    /** Extra del Intent de arranque con la llave (64 caracteres hexadecimales). */
    const val EXTRA = "io.ytmp3.motor.KEY"

    private const val PREFS = "llaves"
    private const val FIELD = "lista"
    /** Tope de llaves recordadas: se olvida la que lleva más tiempo sin entregarse. */
    private const val MAX = 8
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** id → llave, de la entregada hace más tiempo a la más reciente. */
    private val keys = LinkedHashMap<String, ByteArray>()
    private var loaded = false

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Cómo nombra la app su llave en cada petición, sin enseñarla. */
    private fun id(key: ByteArray): String = hex(sha256(key)).take(16)

    fun hmac(key: ByteArray, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Abre el cuerpo cifrado de una petición: 12 bytes de vector y, detrás, AES-256-GCM con su
     * sello. La llave de cifrado es HMAC(llave, "enc"); `bound` (número de petición y ruta) va
     * atado al sello. `null` si no lo cifró quien tiene la llave o si lo tocaron por el camino.
     */
    fun open(key: ByteArray, data: ByteArray, bound: String): ByteArray? {
        if (data.size < 12 + 16) return null
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(mac.doFinal("enc".toByteArray(Charsets.UTF_8)), "AES"), GCMParameterSpec(128, data, 0, 12))
            cipher.updateAAD(bound.toByteArray(Charsets.UTF_8))
            cipher.doFinal(data, 12, data.size - 12)
        } catch (error: Exception) {
            null
        }
    }

    /** Compara sin delatar, por el tiempo que tarda, cuánto se parecen. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FIELD, "").orEmpty()
        for (text in saved.split(',')) {
            if (HEX64.matches(text)) unhex(text).let { keys[id(it)] = it }
        }
    }

    /** Apunta la llave que trae un Intent de arranque (si trae una y está bien formada). */
    @Synchronized
    fun add(context: Context, value: String?) {
        val text = value?.trim()?.lowercase() ?: return
        if (!HEX64.matches(text)) return
        load(context)
        val key = unhex(text)
        val kid = id(key)
        keys.remove(kid)
        keys[kid] = key
        while (keys.size > MAX) keys.remove(keys.keys.first())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(FIELD, keys.values.joinToString(",") { hex(it) }).apply()
    }

    @Synchronized
    fun find(id: String): ByteArray? = keys[id]
}
