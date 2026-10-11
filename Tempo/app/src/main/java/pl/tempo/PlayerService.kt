package pl.tempo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns

object Cfg {
    fun def(mode: String, key: String): Float = when (key) {
        "normal" -> if (mode == "car") 50f else 5f
        "slow" -> if (mode == "car") 20f else 2f
        "slowRate" -> 0.7f
        else -> 1.6f
    }
    fun get(p: SharedPreferences, mode: String, key: String): Float =
        p.getFloat("${mode}_$key", def(mode, key))
}

class PlayerService : Service(), LocationListener {
    companion object {
        @Volatile var running = false
        @Volatile var onUpdate: ((Float, Float, String) -> Unit)? = null
    }

    private var mp: MediaPlayer? = null
    private var queue = listOf<Uri>()
    private var idx = 0
    private var smooth = 0f
    private var trackName = ""
    private lateinit var prefs: SharedPreferences

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        prefs = getSharedPreferences("tempo", Context.MODE_PRIVATE)
        when (i?.action) {
            "stop" -> { stopSelf(); return START_NOT_STICKY }
            "reload" -> { loadQueue(); playIdx(0) }
            else -> {
                startFg()
                if (!running) {
                    running = true
                    loadQueue()
                    playIdx(0)
                    startGps()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("tempo", "Tempo", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, "tempo")
            .setContentTitle("Tempo")
            .setContentText("Muzyka dopasowana do prędkości")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else startForeground(1, n)
    }

    private fun startGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (e: SecurityException) { stopSelf() }
    }

    private fun loadQueue() {
        queue = (prefs.getString("playlist", "") ?: "").split("\n")
            .filter { it.isNotBlank() }.map { Uri.parse(it) }
    }

    private fun nameOf(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val c = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c >= 0) return it.getString(c)
            }
        }
        return u.lastPathSegment ?: ""
    }

    private fun playIdx(i: Int, tries: Int = 0) {
        mp?.release(); mp = null
        if (queue.isEmpty() || tries > queue.size) return
        idx = i % queue.size
        val u = queue[idx]
        try {
            val p = MediaPlayer()
            p.setDataSource(this, u)
            p.setOnCompletionListener { playIdx(idx + 1) }
            p.prepare()
            p.start()
            mp = p
            trackName = nameOf(u)
            applyRate()
        } catch (e: Exception) {
            playIdx(idx + 1, tries + 1)
        }
    }

    private fun rateFor(v: Float): Float {
        val mode = prefs.getString("mode", "walk") ?: "walk"
        val n = Cfg.get(prefs, mode, "normal")
        val s = Cfg.get(prefs, mode, "slow")
        val sr = Cfg.get(prefs, mode, "slowRate")
        val mr = Cfg.get(prefs, mode, "maxRate")
        val span = maxOf(1f, n - s)
        return when {
            v <= s -> sr
            v < n -> sr + (1f - sr) * (v - s) / span
            else -> minOf(mr, 1f + (mr - 1f) * (v - n) / span)
        }
    }

    private fun applyRate() {
        val r = rateFor(smooth)
        val p = mp
        try {
            if (p != null && p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(r)
        } catch (e: Exception) { }
        onUpdate?.invoke(smooth, r, trackName)
    }

    override fun onLocationChanged(loc: Location) {
        val v = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        smooth = smooth * 0.6f + v * 0.4f
        if (smooth < 0.8f && v < 0.5f) smooth = 0f
        applyRate()
    }

    @Deprecated("old API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        running = false
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (e: Exception) { }
        mp?.release(); mp = null
        super.onDestroy()
    }
}
