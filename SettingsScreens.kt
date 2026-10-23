@file:OptIn(ExperimentalMaterial3Api::class)

package com.kosherdialer

import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SubTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
    )
}

@Composable
private fun Section(t: String) {
    Text(t, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Item(title: String, sub: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (sub != null) ({ Text(sub) }) else null,
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    )
}

@Composable
fun SettingsScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    var themeDlg by remember { mutableStateOf(false) }
    var vmDlg by remember { mutableStateOf(false) }
    var vmText by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<String?>(null) }
    val ringName = remember(Prefs.ringtone) {
        try {
            RingtoneManager.getRingtone(ctx, Prefs.ringtone?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)).getTitle(ctx)
        } catch (e: Exception) { "ברירת מחדל" }
    }
    val ringPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (data != null) {
            val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            Prefs.ringtone = uri?.toString(); Prefs.save()
        }
    }
    Scaffold(topBar = { SubTopBar("הגדרות", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("אפשרויות תצוגה")
            Item("ערכת נושא", listOf("לפי המערכת", "בהיר", "כהה")[Prefs.theme]) { themeDlg = true }
            Item("מיון אנשי קשר הפוך (ת-א)", trailing = {
                Switch(Prefs.sortDesc, { Prefs.sortDesc = it; Prefs.save() })
            })

            Section("צלילים ורטט")
            Item("רינגטון של שיחה נכנסת", ringName) {
                ringPicker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Prefs.ringtone?.let { Uri.parse(it) })
                )
            }
            Item("רטט בשיחה נכנסת", trailing = { Switch(Prefs.vibrate, { Prefs.vibrate = it; Prefs.save() }) })
            Item("צלילי לוח מקשים", trailing = { Switch(Prefs.dtmfTones, { Prefs.dtmfTones = it; Prefs.save() }) })

            Section("שיחות")
            Item("שיחה ממתינה", "מציג שיחה נוספת שנכנסת באמצע שיחה", trailing = {
                Switch(Prefs.callWaiting, { Prefs.callWaiting = it; Prefs.save(); Hfp.setCallWaiting(it) })
            })
            Item("תא קולי", Prefs.voicemail.ifBlank { "לא הוגדר (לחיצה ארוכה על 1 בלוח המקשים תחייג אליו)" }) {
                vmText = Prefs.voicemail; vmDlg = true
            }
            Item("מספרים חסומים", "שיחות מהמספרים האלה ינותקו אוטומטית") { go("blocked") }
            Item("הקלטות שיחות", "האזנה ומחיקה") { go("recordings") }

            Section("מערכת")
            Item("אל תגביל את האפליקציה בסוללה", "מומלץ, כדי שהחיבור יישאר פעיל כשהמסך כבוי") {
                try {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")))
                } catch (e: Exception) {
                    try { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            }
            Item("בדיקת תמיכה בדיבורית במכשיר", "בודק אם המערכת מאפשרת לאפליקציה להתחבר כאוזניות") {
                probe = "בודק..."
                Hfp.probeClientProfile(ctx) { probe = it }
            }
            Item("אודות") { go("about") }
            Text(
                "הגדרות מערכת כמו נגישות, מכשירי שמיעה, TTY ותגובות מהירות ב-SMS אינן זמינות באפליקציה הזאת.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    probe?.let { msg ->
        AlertDialog(
            onDismissRequest = { probe = null },
            confirmButton = { TextButton(onClick = { probe = null }) { Text("סגור") } },
            title = { Text("תוצאת הבדיקה") },
            text = { Text(msg) }
        )
    }
    if (themeDlg) AlertDialog(
        onDismissRequest = { themeDlg = false },
        confirmButton = { TextButton(onClick = { themeDlg = false }) { Text("סגור") } },
        title = { Text("ערכת נושא") },
        text = {
            Column {
                listOf("לפי המערכת", "בהיר", "כהה").forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { Prefs.theme = i; Prefs.save(); themeDlg = false },
                        verticalAlignment = Alignment.CenterVertically
                    ) { RadioButton(Prefs.theme == i, { Prefs.theme = i; Prefs.save(); themeDlg = false }); Text(t) }
                }
            }
        }
    )
    if (vmDlg) AlertDialog(
        onDismissRequest = { vmDlg = false },
        title = { Text("מספר תא קולי") },
        text = {
            OutlinedTextField(vmText, { vmText = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        },
        confirmButton = { TextButton(onClick = { Prefs.voicemail = vmText.trim(); Prefs.save(); vmDlg = false }) { Text("שמור") } },
        dismissButton = { TextButton(onClick = { vmDlg = false }) { Text("ביטול") } }
    )
}

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    var add by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Scaffold(
        topBar = { SubTopBar("מספרים חסומים", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { text = ""; add = true }) { Icon(Icons.Default.Add, "הוסף מספר") }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (Prefs.blocked.isEmpty()) Empty("אין מספרים חסומים")
            else LazyColumn {
                items(Prefs.blocked.toList()) { n ->
                    ListItem(
                        headlineContent = { Text(n) },
                        trailingContent = {
                            IconButton(onClick = { Prefs.blocked.remove(n); Prefs.save() }) { Icon(Icons.Default.Delete, "מחק") }
                        }
                    )
                }
            }
        }
    }
    if (add) AlertDialog(
        onDismissRequest = { add = false },
        title = { Text("חסימת מספר") },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("מספר טלפון") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        },
        confirmButton = {
            TextButton(onClick = {
                val n = text.filter { it.isDigit() || it == '+' }
                if (n.isNotEmpty() && n !in Prefs.blocked) { Prefs.blocked.add(n); Prefs.save() }
                add = false
            }) { Text("חסום") }
        },
        dismissButton = { TextButton(onClick = { add = false }) { Text("ביטול") } }
    )
}

@Composable
fun RecordingsScreen(vm: PhoneVm, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(Recorder.list(ctx)) }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { try { player[0]?.release() } catch (_: Exception) {} } }

    fun stopPlay() {
        try { player[0]?.release() } catch (_: Exception) {}
        player[0] = null; playing = null
    }
    fun toggle(f: File) {
        if (playing == f.path) { stopPlay(); return }
        stopPlay()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(f.path); mp.prepare()
            mp.setOnCompletionListener { stopPlay() }
            mp.start(); player[0] = mp; playing = f.path
        } catch (_: Exception) { stopPlay() }
    }

    Scaffold(topBar = { SubTopBar("הקלטות שיחות", onBack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (files.isEmpty()) Empty("אין הקלטות")
            else LazyColumn {
                items(files) { f ->
                    val parts = f.nameWithoutExtension.split("_")
                    val date = try {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(parts[0] + "_" + parts[1])!!)
                    } catch (e: Exception) { f.name }
                    val num = parts.getOrNull(2) ?: ""
                    val who = if (num.isEmpty()) "מספר חסוי" else (vm.nameFor(num) ?: num)
                    ListItem(
                        leadingContent = {
                            IconButton(onClick = { toggle(f) }) {
                                Icon(if (playing == f.path) Icons.Default.Pause else Icons.Default.PlayArrow, "נגן")
                            }
                        },
                        headlineContent = { Text(who) },
                        supportingContent = { Text(date) },
                        trailingContent = {
                            IconButton(onClick = {
                                if (playing == f.path) stopPlay()
                                f.delete(); files = Recorder.list(ctx)
                            }) { Icon(Icons.Default.Delete, "מחק") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { SubTopBar("אודות", onBack) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Phone, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("טלפון", fontSize = 28.sp)
            Text("גרסה 1.0", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Text("נבנה ע״י YOSSI WERNER", fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("YOSSI0556786311@GMIL.COM", fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("UNXPR DRXV", fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}
