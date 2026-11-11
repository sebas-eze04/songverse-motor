package app.songverse.motor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

// ============================================================
// El motor como servicio en primer plano: mientras hay una app pidiéndole
// cosas (o descargas en marcha), Android no lo duerme ni lo cierra. Pasado un
// rato sin trabajo ni peticiones, se apaga solo: la app lo vuelve a despertar
// cuando lo necesita.
// ============================================================
class MotorService : Service() {
    private val servidor = Servidor()
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastBusy = -1

    private val tick = object : Runnable {
        override fun run() {
            val busy = Motor.busy()
            if (busy != lastBusy) {
                lastBusy = busy
                notify(busy)
            }
            // Con descargas en marcha, el procesador no se duerme aunque se apague la pantalla.
            if (busy > 0 && wakeLock?.isHeld != true) wakeLock?.acquire(60 * 60 * 1000L)
            if (busy == 0 && wakeLock?.isHeld == true) wakeLock?.release()
            if (busy == 0 && System.currentTimeMillis() - Motor.lastActivity > IDLE_MS) {
                stopSelf()
                return
            }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Motor", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "songverse:descargas")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android exige mostrar el aviso en los primeros segundos.
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, build(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, build(0))
        Motor.lastActivity = System.currentTimeMillis()
        // La llave de la app que lo despierta (Llaves.kt), y las que ya tenía apuntadas.
        Llaves.load(applicationContext)
        Llaves.add(applicationContext, intent?.getStringExtra(Llaves.EXTRA))
        Motor.start(applicationContext)
        // Un solo motor a la vez: el puerto hace de cerrojo.
        servidor.start()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        servidor.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun build(busy: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = if (busy > 0) getString(R.string.descargando, busy) else getString(R.string.activo)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.icono_aviso)
            .setContentTitle("SongVerse")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun notify(busy: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, build(busy))
    }

    companion object {
        private const val CHANNEL = "motor"
        private const val NOTIFICATION = 1
        /** Sin trabajo ni peticiones durante este rato, el motor se apaga. */
        private const val IDLE_MS = 15 * 60 * 1000L

        fun start(context: Context, key: String? = null) {
            val intent = Intent(context, MotorService::class.java)
            if (key != null) intent.putExtra(Llaves.EXTRA, key)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
