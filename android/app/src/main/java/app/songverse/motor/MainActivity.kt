package app.songverse.motor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

// ============================================================
// SongVerse no tiene pantalla que usar: es un motor para otras apps. Al abrirlo
// deja el servicio en marcha y lo dice. Cuando lo despierta la app que lo
// integra (acción DESPERTAR), ni siquiera se muestra: arranca el servicio y se va.
// ============================================================
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MotorService.start(this)
        if (intent?.action == ACTION_WAKE) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            text = "SongVerse"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        val body = TextView(this).apply {
            text = getString(R.string.pantalla, BuildConfig.VERSION_NAME)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                addView(title)
                addView(body)
            }
        )
    }

    companion object {
        const val ACTION_WAKE = "io.ytmp3.motor.WAKE"
    }
}
