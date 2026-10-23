package com.kosherdialer

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** שירות רקע: שומר את החיבור למכשיר הכשר, מצלצל בשיחה נכנסת, מתחבר מחדש אוטומטית ומנסה לנתב את הקול. */
class HfpService : Service() {
    private var ringtone: Ringtone? = null
    private var audioOn = false
    private var wanted = true
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("status", "חיבור", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("calls", "שיחות", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        Hfp.listener = { onCallState(it) }
        Hfp.onLost = { handler.postDelayed({ reconnect() }, 8000) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val n = NotificationCompat.Builder(this, "status")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("טלפון")
            .setContentText("מחובר כאוזניות למכשיר הכשר")
            .setContentIntent(openApp())
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, type)
        wanted = true
        reconnect()
        return START_STICKY
    }

    private fun reconnect() {
        if (!wanted || Hfp.link != Hfp.Link.OFF) return
        val addr = getSharedPreferences("p", 0).getString("dev", null) ?: return
        val ad = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try { Hfp.connect(ad.getRemoteDevice(addr)) } catch (_: Exception) {}
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE
    )

    @Suppress("DEPRECATION")
    private fun vibrate(on: Boolean) {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (on && Prefs.vibrate) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0)) else v.cancel()
    }

    private fun onCallState(st: Hfp.Call) {
        val nm = getSystemService(NotificationManager::class.java)
        if (st == Hfp.Call.INCOMING) {
            val uri = Prefs.ringtone?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            ringtone?.play()
            vibrate(true)
            val n = NotificationCompat.Builder(this, "calls")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("שיחה נכנסת")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(openApp(), true)
                .setContentIntent(openApp())
                .setOngoing(true).build()
            try { nm.notify(2, n) } catch (_: Exception) {}
        } else {
            ringtone?.stop(); ringtone = null
            vibrate(false)
            nm.cancel(2)
        }
        audio(st == Hfp.Call.DIALING || st == Hfp.Call.ALERTING || st == Hfp.Call.ACTIVE)
    }

    /** ניסיון לנתב את הקול דרך חיבור ה-SCO של הבלוטות'. עובד רק אם המערכת במכשיר מזהה אותו. */
    private fun audio(on: Boolean) {
        if (on == audioOn) return
        audioOn = on
        val am = getSystemService(AudioManager::class.java)
        try {
            if (on) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val d = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    if (d != null) am.setCommunicationDevice(d)
                    Hfp.audioRouted = d != null
                } else {
                    @Suppress("DEPRECATION") am.startBluetoothSco()
                    @Suppress("DEPRECATION") run { am.isBluetoothScoOn = true }
                }
            } else {
                am.isMicrophoneMute = false
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else {
                    @Suppress("DEPRECATION") am.stopBluetoothSco()
                    @Suppress("DEPRECATION") run { am.isBluetoothScoOn = false }
                }
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        Hfp.listener = null
        Hfp.onLost = null
        Hfp.disconnect()
        ringtone?.stop()
        vibrate(false)
        super.onDestroy()
    }
}
