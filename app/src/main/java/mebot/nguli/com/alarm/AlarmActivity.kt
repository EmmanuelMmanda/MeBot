package mebot.nguli.com.alarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import mebot.nguli.com.R
import mebot.nguli.com.core.AlarmDismissMode
import mebot.nguli.com.core.MathChallenge
import mebot.nguli.com.core.Settings as AppSettings
import mebot.nguli.com.core.effectiveDismissMode
import mebot.nguli.com.ui.MeBotTheme
import mebot.nguli.com.ui.MeBotPalette
import mebot.nguli.com.ui.MeBotTokens
import mebot.nguli.com.ui.WaveCard
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** A compact night screen. Playback and challenge validation live outside the activity. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        allowOnLockScreen()
        val controller = AlarmController(this)
        setContent {
            val localized = remember { alarmLocale(controller.language()) }
            fun words(id: Int, vararg args: Any) = localized.getString(id, *args)
            // Available before unlock without reading private profile/check-in storage.
            MeBotTheme(AppSettings(theme = "dark")) {
                var session by remember { mutableStateOf(controller.session()) }
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                var message by remember { mutableStateOf<String?>(null) }
                var cameraDenied by remember { mutableStateOf(false) }
                var scanSessionId by rememberSaveable { mutableStateOf<String?>(null) }
                var confirmStop by rememberSaveable(session?.id) { mutableStateOf(false) }
                val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
                    val current = controller.session()?.takeIf { it.id == scanSessionId }
                    scanSessionId = null
                    if (current != null && result.contents != null) {
                        message = when (controller.submitCheckpoint(current.id, result.contents)) {
                            CheckpointResult.WRONG_CODE -> words(R.string.alarm_wrong_code)
                            CheckpointResult.NOT_READY -> words(R.string.alarm_not_ready)
                            else -> null
                        }
                    } else if (result.contents == null) message = words(R.string.alarm_scan_cancelled)
                    session = controller.session()
                }
                fun scan() {
                    val current = controller.session() ?: return
                    if (!current.audible || current.plan.effectiveDismissMode != AlarmDismissMode.QR) return
                    controller.beginWalk(current.id)
                    session = controller.session()
                    scanSessionId = current.id
                    message = null
                    scanner.launch(ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setCaptureActivity(AlarmScanActivity::class.java)
                        setPrompt(words(R.string.alarm_scan_prompt))
                        setBeepEnabled(false)
                        setBarcodeImageEnabled(false)
                        setOrientationLocked(false)
                    })
                }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    cameraDenied = !granted
                    if (granted) scan() else message = words(R.string.alarm_camera_permission)
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        session = controller.session()
                        now = System.currentTimeMillis()
                        delay(500)
                    }
                }
                val audible = session?.audible == true
                DisposableEffect(audible) {
                    if (audible) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                            .padding(horizontal = MeBotTokens.ScreenPadding, vertical = MeBotTokens.SpaceLg),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Alarm, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(words(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (session?.isTest == true) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                                Text(words(R.string.mebot_alarm_test), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val current = session
                        if (current == null) {
                            Spacer(Modifier.height(64.dp))
                            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                            }
                            Text(words(R.string.alarm_ended), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { returnToApp() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                                Text(words(R.string.alarm_return_to_app))
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                            }
                        } else {
                            val waiting = current.phase == AlarmPhase.SNOOZED || current.phase == AlarmPhase.WAITING_FOLLOWUP
                            val remaining = current.deadlineAtMillis?.let { (it - now).coerceAtLeast(0) }
                            val time = if (waiting && remaining != null) formatRemaining(remaining)
                                else Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(time, fontSize = 48.sp, lineHeight = 54.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp)
                                Text(current.plan.label.ifBlank { words(R.string.alarm_your_title) }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                            val math = current.mathChallenge
                            if (current.audible && current.plan.effectiveDismissMode == AlarmDismissMode.MATH && math?.current != null) {
                                MathChallengePanel(
                                    sessionId = current.id,
                                    challenge = math,
                                    words = ::words,
                                    submit = { questionId, answer ->
                                        val result = controller.submitMathAnswer(current.id, questionId, answer)
                                        session = controller.session()
                                        result
                                    },
                                )
                            } else {
                                val title = when (current.phase) {
                                    AlarmPhase.WAITING_FOLLOWUP -> words(R.string.mebot_alarm_followup_wait)
                                    AlarmPhase.SNOOZED -> words(R.string.mebot_alarm_snoozed)
                                    AlarmPhase.FOLLOWUP_RINGING -> words(R.string.mebot_alarm_scan_again)
                                    AlarmPhase.WALKING -> words(R.string.alarm_go_checkpoint)
                                    AlarmPhase.RINGING -> if (current.plan.effectiveDismissMode == AlarmDismissMode.QR) words(R.string.alarm_scan_action) else words(R.string.mebot_alarm_time_to_wake)
                                }
                                AlarmHero {
                                    Icon(if (current.plan.effectiveDismissMode == AlarmDismissMode.QR) Icons.Outlined.QrCodeScanner else Icons.Outlined.WbSunny, null, Modifier.size(40.dp))
                                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                    if (current.plan.effectiveDismissMode == AlarmDismissMode.QR) {
                                        Text(current.plan.checkpointLabel.ifBlank { words(R.string.alarm_checkpoint_generic) }, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (current.phase == AlarmPhase.WALKING) {
                                        Text(words(R.string.mebot_alarm_quiet_remaining, formatRemaining(remaining ?: 0)), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (current.audible && current.plan.effectiveDismissMode == AlarmDismissMode.QR) {
                                    Button(onClick = {
                                        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) message = words(R.string.alarm_no_camera)
                                        else if (ContextCompat.checkSelfPermission(this@AlarmActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
                                        else permission.launch(Manifest.permission.CAMERA)
                                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                                        Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(words(R.string.alarm_scan_action))
                                    }
                                } else if (current.audible && current.plan.effectiveDismissMode == AlarmDismissMode.STANDARD) {
                                    Button(onClick = { controller.dismiss(current.id); session = controller.session() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                                        Text(words(R.string.alarm_dismiss_full_action))
                                    }
                                }
                            }
                            if (current.canSnooze) {
                                OutlinedButton(onClick = {
                                    if (!controller.snooze(current.id)) message = words(R.string.alarm_snooze_failed)
                                    session = controller.session()
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
                                    Icon(Icons.Outlined.Snooze, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(words(R.string.mebot_alarm_snooze_short, current.plan.snoozeMinutes))
                                }
                            }
                            if (waiting) OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
                                Text(words(R.string.alarm_close_for_now))
                            }
                            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                            if (cameraDenied) TextButton(onClick = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                            }) { Text(words(R.string.alarm_camera_settings)) }
                            TextButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(words(R.string.alarm_emergency_action), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (confirmStop && session != null) {
                        val stopSessionId = requireNotNull(session).id
                        AlertDialog(
                            onDismissRequest = { confirmStop = false },
                            title = { Text(words(R.string.mebot_alarm_stop_title)) },
                            text = { Text(words(R.string.mebot_alarm_stop_detail)) },
                            confirmButton = { TextButton(onClick = {
                                controller.emergencyStop(stopSessionId)
                                session = controller.session()
                                confirmStop = false
                            }) { Text(words(R.string.mebot_alarm_stop_confirm)) } },
                            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(words(R.string.mebot_alarm_keep_ringing)) } },
                        )
                    }
                }
            }
        }
    }

    private fun allowOnLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun returnToApp() {
        startActivity(Intent().setClassName(this, "mebot.nguli.com.MainActivity").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

@Composable
private fun MathChallengePanel(
    sessionId: String,
    challenge: MathChallenge,
    words: (Int, Array<out Any>) -> String,
    submit: (String, Int) -> MathAnswerResult,
) {
    val question = challenge.current ?: return
    var answer by rememberSaveable(sessionId, question.id) { mutableStateOf("") }
    var incorrect by rememberSaveable(sessionId, question.id) { mutableStateOf(false) }
    fun label(id: Int, vararg args: Any): String = words(id, args)
    AlarmHero {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label(R.string.mebot_alarm_math), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Surface(color = Color.White.copy(alpha = .18f), contentColor = Color.White, shape = CircleShape) {
                Text(label(R.string.mebot_alarm_question_count, challenge.answered + 1, challenge.questions.size), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(challenge.questions.size) { index ->
                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = if (index < challenge.answered) 1f else .28f)))
            }
        }
        Text(question.expression, fontSize = if (question.expression.length > 12) 28.sp else 38.sp, lineHeight = 44.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("math-question").semantics { liveRegion = LiveRegionMode.Polite })
        Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = .12f)).border(1.dp, Color.White.copy(alpha = .38f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Text(answer.ifEmpty { "?" }, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (answer.isEmpty()) .65f else 1f),
                modifier = Modifier.testTag("math-answer").semantics { liveRegion = LiveRegionMode.Polite; contentDescription = label(R.string.mebot_alarm_answer, answer.ifEmpty { label(R.string.mebot_alarm_empty) }) })
        }
        Text(if (incorrect) label(R.string.mebot_alarm_try_again) else label(R.string.mebot_alarm_solve_to_stop),
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .94f),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("delete", "0", "submit")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    val submitKey = key == "submit"
                    Surface(
                        onClick = {
                            when (key) {
                                "delete" -> { answer = answer.dropLast(1); incorrect = false }
                                "submit" -> answer.toIntOrNull()?.let { value ->
                                    incorrect = submit(question.id, value) == MathAnswerResult.INCORRECT
                                    answer = ""
                                }
                                else -> if (answer.length < 4) { answer = if (answer == "0") key else answer + key; incorrect = false }
                            }
                        },
                        enabled = !submitKey || answer.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 58.dp).testTag("math-key-$key"),
                        color = if (submitKey) MaterialTheme.colorScheme.primary.copy(alpha = if (answer.isEmpty()) .45f else 1f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (submitKey) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                            when (key) {
                                "delete" -> Icon(Icons.AutoMirrored.Outlined.Backspace, label(R.string.mebot_alarm_delete_digit), Modifier.size(24.dp))
                                "submit" -> Icon(Icons.AutoMirrored.Outlined.ArrowForward, label(R.string.mebot_alarm_submit), Modifier.size(25.dp))
                                else -> Text(key, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmHero(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    WaveCard(containerColor = MeBotPalette.Brand, contentColor = MeBotPalette.OnBrand) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** Capture is only started by the visible alarm screen. No images are retained. */
class AlarmScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        super.onCreate(savedInstanceState)
    }
}

internal fun formatRemaining(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
