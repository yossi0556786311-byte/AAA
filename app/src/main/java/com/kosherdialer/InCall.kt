package com.kosherdialer

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun CallAction(icon: ImageVector, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(96.dp).alpha(if (enabled) 1f else 0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(if (on) cs.primary else cs.surfaceVariant)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (on) cs.onPrimary else cs.onSurfaceVariant) }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun InCallScreen(vm: PhoneVm) {
    val ctx = LocalContext.current
    val name = vm.nameFor(Hfp.number)
    var keypad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var addDialog by remember { mutableStateOf(false) }
    var addNumber by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Hfp.call) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val recPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { if (!Recorder.start(ctx, Hfp.number)) Hfp.message = "ההקלטה נכשלה" }
        else Hfp.message = "אין הרשאת מיקרופון. אפשר לאשר בהגדרות האפליקציה."
    }
    val active = Hfp.call == Hfp.Call.ACTIVE
    val sec = ((now - Hfp.activeSince) / 1000).coerceAtLeast(0)
    val status = when (Hfp.call) {
        Hfp.Call.INCOMING -> "שיחה נכנסת"
        Hfp.Call.DIALING -> "מחייג..."
        Hfp.Call.ALERTING -> "מצלצל..."
        else -> if (Hfp.held == 2) "בהמתנה" else "%d:%02d".format(sec / 60, sec % 60)
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Person, null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(12.dp))
        Text(name ?: Hfp.number.ifEmpty { "מספר חסוי" }, fontSize = 28.sp)
        if (name != null) Text(Hfp.number, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(status, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Recorder.recording) Text("● מקליט", color = Color(0xFFD93025), fontSize = 14.sp)
        if (Hfp.call != Hfp.Call.INCOMING && Hfp.audioRouted == false)
            Text("הקול לא מנותב לאנדרואיד - ייתכן שישמע במכשיר הכשר", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        Hfp.message?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { Hfp.message = null }.padding(top = 4.dp))
        }
        Spacer(Modifier.weight(1f))

        if (Hfp.call != Hfp.Call.INCOMING) {
            if (keypad) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(typed.takeLast(20), fontSize = 28.sp, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    listOf("123", "456", "789", "*0#").forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { ch ->
                                Box(
                                    Modifier.size(68.dp).clip(CircleShape).clickable {
                                        typed += ch; DtmfTone.play(ch); Hfp.sendDtmf(ch)
                                    },
                                    contentAlignment = Alignment.Center
                                ) { Text(ch.toString(), fontSize = 28.sp) }
                            }
                        }
                    }
                }
                TextButton(onClick = { keypad = false }) { Text("הסתר לוח מקשים") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallAction(Icons.Default.MicOff, "השתק", Hfp.muted) { Hfp.setMute(ctx, !Hfp.muted) }
                    CallAction(Icons.Default.Dialpad, "לוח מקשים", enabled = active) { keypad = true }
                    CallAction(Icons.Default.VolumeUp, "רמקול", Hfp.speaker) { Hfp.applySpeaker(ctx, !Hfp.speaker) }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallAction(Icons.Default.Pause, "המתנה", Hfp.held == 2, enabled = active) { Hfp.hold() }
                    if (Hfp.held == 1) CallAction(Icons.Default.CallMerge, "מזג", enabled = active) { Hfp.merge() }
                    else CallAction(Icons.Default.PersonAdd, "הוסף שיחה", enabled = active) { addNumber = ""; addDialog = true }
                    CallAction(
                        if (Recorder.recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        if (Recorder.recording) "עצור" else "הקלט", Recorder.recording
                    ) {
                        if (Recorder.recording) Recorder.stop()
                        else if (!Recorder.start(ctx, Hfp.number)) recPerm.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (Hfp.call == Hfp.Call.INCOMING) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Green).clickable { Hfp.answer() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Call, "ענה", tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFD93025)).clickable { Hfp.hangup() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.CallEnd, "נתק", tint = Color.White, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (addDialog) AlertDialog(
        onDismissRequest = { addDialog = false },
        title = { Text("הוסף שיחה") },
        text = {
            OutlinedTextField(addNumber, { addNumber = it }, singleLine = true, label = { Text("מספר טלפון") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone))
        },
        confirmButton = { TextButton(onClick = { addDialog = false; Hfp.addCall(addNumber) }) { Text("חייג") } },
        dismissButton = { TextButton(onClick = { addDialog = false }) { Text("ביטול") } }
    )
}
