package com.kosherdialer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** הגדרות האפליקציה (נשמרות ב-SharedPreferences). */
object Prefs {
    private var sp: SharedPreferences? = null
    var theme by mutableIntStateOf(0) // 0 אוטומטי, 1 בהיר, 2 כהה
    var vibrate by mutableStateOf(true)
    var dtmfTones by mutableStateOf(true)
    var callWaiting by mutableStateOf(true)
    var sortDesc by mutableStateOf(false)
    var voicemail by mutableStateOf("")
    var ringtone by mutableStateOf<String?>(null)
    val blocked = mutableStateListOf<String>()

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("p", 0)
        sp = p
        theme = p.getInt("theme", 0)
        vibrate = p.getBoolean("vibrate", true)
        dtmfTones = p.getBoolean("dtmf", true)
        callWaiting = p.getBoolean("cw", true)
        sortDesc = p.getBoolean("sortDesc", false)
        voicemail = p.getString("vm", "") ?: ""
        ringtone = p.getString("ring", null)
        blocked.addAll((p.getString("blocked", "") ?: "").split(",").filter { it.isNotBlank() })
    }

    fun save() {
        sp?.edit()
            ?.putInt("theme", theme)?.putBoolean("vibrate", vibrate)?.putBoolean("dtmf", dtmfTones)
            ?.putBoolean("cw", callWaiting)?.putBoolean("sortDesc", sortDesc)?.putString("vm", voicemail)
            ?.putString("ring", ringtone)?.putString("blocked", blocked.joinToString(","))
            ?.apply()
    }

    fun isBlocked(number: String): Boolean {
        val k = norm(number)
        return k.isNotEmpty() && blocked.any { norm(it) == k }
    }
}

object DtmfTone {
    private var tg: ToneGenerator? = null
    fun play(c: Char) {
        if (!Prefs.dtmfTones) return
        val t = when (c) {
            in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (c - '0')
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        try {
            if (tg == null) tg = ToneGenerator(AudioManager.STREAM_DTMF, 80)
            tg?.startTone(t, 120)
        } catch (_: Exception) {}
    }
}

/** הקלטת שיחה דרך המיקרופון של המכשיר (ההקלטה כוללת רק את מה שהמיקרופון קולט). */
object Recorder {
    var recording by mutableStateOf(false)
    private var mr: MediaRecorder? = null

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "calls").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.extension == "m4a" }?.sortedByDescending { it.name } ?: emptyList()

    fun start(ctx: Context, label: String): Boolean {
        if (recording) return true
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir(ctx), "${stamp}_${label.filter { it.isDigit() }}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            mr = r; recording = true; true
        } catch (e: Exception) { mr = null; recording = false; false }
    }

    fun stop() {
        try { mr?.stop() } catch (_: Exception) {}
        try { mr?.release() } catch (_: Exception) {}
        mr = null; recording = false
    }
}
