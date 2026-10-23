package com.kosherdialer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * מימוש צד ה"דיבורית" (HFP Hands-Free) ישירות מעל RFCOMM.
 * האפליקציה מתחברת למכשיר הכשר כמו אוזניות: חיוג, מענה, ניתוק, המתנה, DTMF, והצגת שיחה נכנסת.
 */
@SuppressLint("MissingPermission")
object Hfp {
    enum class Link { OFF, CONNECTING, ON }
    enum class Call { IDLE, INCOMING, DIALING, ALERTING, ACTIVE }

    var link by mutableStateOf(Link.OFF)
    var call by mutableStateOf(Call.IDLE)
    var held by mutableIntStateOf(0)          // 0 אין, 1 יש שיחה פעילה ושיחה בהמתנה, 2 רק שיחה בהמתנה
    var number by mutableStateOf("")
    var speaker by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var activeSince by mutableLongStateOf(0L)
    var audioRouted by mutableStateOf<Boolean?>(null)
    var message by mutableStateOf<String?>(null)
    var listener: ((Call) -> Unit)? = null
    var onLost: (() -> Unit)? = null

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private val results = LinkedBlockingQueue<String>()
    private var idxCall = -1
    private var idxSetup = -1
    private var idxHeld = -1
    private var callVal = 0
    private var setupVal = 0
    private var heldVal = 0

    fun connect(device: BluetoothDevice) {
        if (link != Link.OFF) return
        link = Link.CONNECTING
        message = null
        thread {
            try {
                val s = device.createRfcommSocketToServiceRecord(UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb"))
                s.connect()
                socket = s; out = s.outputStream
                startReader(s.inputStream)
                if (!cmd("AT+BRSF=36")) error("BRSF")
                cmd("AT+CIND=?")
                cmd("AT+CIND?")
                if (!cmd("AT+CMER=3,0,0,1")) error("CMER")
                cmd("AT+CLIP=1")
                cmd("AT+CCWA=${if (Prefs.callWaiting) 1 else 0}")
                link = Link.ON
            } catch (e: Exception) {
                disconnect("לא הצלחתי להתחבר למכשיר הכשר כאוזניות (${e.message}). ודא שהוא מצומד, ושאין לו חיבור פעיל לדיבורית אחרת.")
            }
        }
    }

    @Synchronized
    fun disconnect(msg: String? = null) {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
        link = Link.OFF
        callVal = 0; setupVal = 0; heldVal = 0
        update()
        if (msg != null) { message = msg; onLost?.invoke() }
    }

    fun dial(n: String) {
        val clean = n.filter { it.isDigit() || it in "+*#" }
        if (clean.isEmpty()) return
        number = clean
        async("ATD$clean;", "החיוג נכשל")
    }

    fun answer() = async("ATA", "המענה נכשל")
    fun hangup() = async("AT+CHUP", "הניתוק נכשל")
    fun hold() = async("AT+CHLD=2", "ההמתנה לא נתמכת במכשיר הכשר")
    fun merge() = async("AT+CHLD=3", "המיזוג לא נתמך במכשיר הכשר")
    fun sendDtmf(c: Char) = async("AT+VTS=$c", "")
    fun setCallWaiting(on: Boolean) { if (link == Link.ON) async("AT+CCWA=${if (on) 1 else 0}", "") }

    fun addCall(n: String) {
        val clean = n.filter { it.isDigit() || it in "+*#" }
        if (clean.isEmpty()) return
        thread {
            try {
                cmd("AT+CHLD=2")
                number = clean
                if (!cmd("ATD$clean;")) message = "החיוג נכשל"
            } catch (e: Exception) { message = "החיוג נכשל" }
        }
    }

    fun applySpeaker(ctx: Context, on: Boolean) {
        speaker = on
        @Suppress("DEPRECATION")
        ctx.getSystemService(AudioManager::class.java).isSpeakerphoneOn = on
    }

    fun setMute(ctx: Context, on: Boolean) {
        muted = on
        ctx.getSystemService(AudioManager::class.java).isMicrophoneMute = on
    }


    /** בדיקה: האם המערכת במכשיר מפעילה את תפקיד הדיבורית (HFP Client, פרופיל 16)? */
    fun probeClientProfile(ctx: Context, done: (String) -> Unit) {
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null) { done("אין בלוטות' במכשיר"); return }
        var answered = false
        try {
            val ok = ad.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    answered = true
                    done("התפקיד זמין במערכת. ההרשאות לשימוש בו עדיין שמורות למערכת, אבל זו תוצאה חשובה - שלח אותה למפתח.")
                    try { ad.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, 16)
            if (!ok) done("לא זמין: המערכת במכשיר לא מפעילה תפקיד דיבורית. אי אפשר לעקוף את זה מאפליקציה.")
            else Handler(Looper.getMainLooper()).postDelayed({
                if (!answered) done("לא זמין: אין תגובה מהמערכת.")
            }, 3000)
        } catch (e: Exception) {
            done("לא זמין: ${e.message}")
        }
    }

    private fun async(c: String, err: String) {
        thread {
            try { if (!cmd(c) && err.isNotEmpty()) message = err }
            catch (e: Exception) { if (err.isNotEmpty()) message = err }
        }
    }

    @Synchronized
    private fun cmd(c: String): Boolean {
        val o = out ?: return false
        results.clear()
        o.write((c + "\r").toByteArray()); o.flush()
        return results.poll(5, TimeUnit.SECONDS) == "OK"
    }

    private fun startReader(inp: InputStream) = thread {
        val sb = StringBuilder()
        try {
            while (true) {
                val b = inp.read()
                if (b < 0) break
                val ch = b.toChar()
                if (ch == '\r' || ch == '\n') {
                    if (sb.isNotEmpty()) { handle(sb.toString().trim()); sb.setLength(0) }
                } else sb.append(ch)
            }
        } catch (_: Exception) {}
        if (socket != null) disconnect("החיבור למכשיר הכשר נותק")
    }

    private fun handle(l: String) {
        when {
            l == "OK" -> results.offer("OK")
            l == "ERROR" || l.startsWith("+CME ERROR") -> results.offer("ERROR")
            l.startsWith("+CIND:") -> {
                if (l.contains("(")) {
                    val names = Regex("\\(\"([^\"]+)\"").findAll(l).map { it.groupValues[1] }.toList()
                    idxCall = names.indexOf("call") + 1
                    idxSetup = names.indexOf("callsetup") + 1
                    idxHeld = names.indexOf("callheld") + 1
                } else {
                    val v = l.substringAfter(":").split(",").map { it.trim().toIntOrNull() ?: 0 }
                    if (idxCall > 0 && v.size >= idxCall) callVal = v[idxCall - 1]
                    if (idxSetup > 0 && v.size >= idxSetup) setupVal = v[idxSetup - 1]
                    if (idxHeld > 0 && v.size >= idxHeld) heldVal = v[idxHeld - 1]
                    update()
                }
            }
            l.startsWith("+CIEV:") -> {
                val p = l.substringAfter(":").split(",").map { it.trim().toIntOrNull() ?: 0 }
                if (p.size >= 2) {
                    if (p[0] == idxCall) callVal = p[1]
                    if (p[0] == idxSetup) setupVal = p[1]
                    if (p[0] == idxHeld) heldVal = p[1]
                    update()
                }
            }
            l.startsWith("+CLIP:") -> {
                number = l.substringAfter("\"", "").substringBefore("\"")
                if (setupVal == 1 && Prefs.isBlocked(number)) hangup()
            }
        }
    }

    private fun update() {
        held = heldVal
        val st = when {
            setupVal == 1 -> Call.INCOMING
            setupVal == 2 -> Call.DIALING
            setupVal == 3 -> Call.ALERTING
            callVal == 1 -> Call.ACTIVE
            else -> Call.IDLE
        }
        if (st == call) return
        if (st == Call.ACTIVE) activeSince = SystemClock.elapsedRealtime()
        call = st
        if (st == Call.IDLE) {
            number = ""; speaker = false; muted = false; audioRouted = null
            Recorder.stop()
        }
        listener?.invoke(st)
    }
}
