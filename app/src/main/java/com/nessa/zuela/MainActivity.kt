package com.nessa.zuela

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nessa.zuela.brain.Personality
import com.nessa.zuela.util.NotificationAccess
import com.nessa.zuela.wake.WakeWordService

class MainActivity : ComponentActivity() {

    private val vm: ZuelaViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = ZuelaColors) {
                ZuelaApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // App is on screen: the in-app mic owns the microphone.
        WakeWordService.stop(this)
    }

    override fun onPause() {
        super.onPause()
        // App is leaving the screen: hand the mic to the background listener.
        if (vm.wakeWordEnabled) {
            try {
                WakeWordService.start(this)
            } catch (_: Exception) {
                // Foreground service start can be restricted; fail quietly.
            }
        }
    }
}

private val ZuelaColors = lightColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE4FF),
    onPrimaryContainer = Color(0xFF2A1065),
    secondaryContainer = Color(0xFFF2ECF9),
    surface = Color(0xFFFDFBFF),
    background = Color(0xFFFDFBFF),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZuelaApp(vm: ZuelaViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Keep the newest message visible.
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Zuela", fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                )
            )
        },
        bottomBar = {
            BottomControls(vm)
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.messages) { message ->
                ChatBubble(message)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(vm, onDismiss = { showSettings = false })
    }
}

@Composable
private fun ChatBubble(message: ZuelaViewModel.ChatMessage) {
    val isUser = message.fromUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(
                start = if (isUser) 48.dp else 0.dp,
                end = if (isUser) 0.dp else 48.dp,
            ),
        ) {
            Text(
                text = message.text,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun BottomControls(vm: ZuelaViewModel) {
    Surface(shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when (vm.status) {
                    ZuelaViewModel.Status.IDLE -> "Tap the mic to talk"
                    ZuelaViewModel.Status.LISTENING -> "Listening…"
                    ZuelaViewModel.Status.THINKING -> "Thinking…"
                    ZuelaViewModel.Status.SPEAKING -> "Speaking — tap the mic to interrupt"
                },
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
            )

            // Voice visualizer: the mic gently pulses while listening.
            val listening = vm.status == ZuelaViewModel.Status.LISTENING
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 1f,
                targetValue = if (listening) 1.18f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(650),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulseScale",
            )

            FloatingActionButton(
                onClick = { vm.onMicTap() },
                containerColor = if (listening)
                    Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(72.dp)
                    .scale(if (listening) pulse else 1f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = "Microphone",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = vm.wakeWordEnabled,
                    onCheckedChange = { vm.toggleWakeWord(it) },
                )
                Column {
                    Text("\"Hey Zuela\" in the background", fontSize = 14.sp)
                    Text(
                        "Listens when you leave the app (uses battery)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(vm: ZuelaViewModel, onDismiss: () -> Unit) {
    var keyInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ---- Personality ----
                SectionLabel("Personality")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Personality.entries.forEach { p ->
                        FilterChip(
                            selected = vm.personality == p,
                            onClick = { vm.selectPersonality(p) },
                            label = { Text(p.label) },
                        )
                    }
                }

                // ---- Wake word ----
                SectionLabel("Wake word")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WAKE_WORDS.forEach { (token, label) ->
                        FilterChip(
                            selected = vm.wakeWord == token,
                            onClick = { vm.selectWakeWord(token) },
                            label = { Text(label) },
                        )
                    }
                }

                // ---- Conversation mode ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = vm.conversationMode,
                        onCheckedChange = { vm.updateConversationMode(it) },
                    )
                    Column {
                        Text("Conversation mode", fontSize = 14.sp)
                        Text(
                            "Keep listening after each answer — no tapping",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }

                HorizontalDivider()

                // ---- Memory ----
                SectionLabel("Memory")
                Text(
                    "Zuela remembers ${vm.memoryCount} thing" +
                        (if (vm.memoryCount == 1) "" else "s") +
                        " about you. Say \"remember that…\" any time.",
                    fontSize = 13.sp,
                )
                if (vm.memoryCount > 0) {
                    Button(onClick = { vm.clearMemory() }) { Text("Clear memory") }
                }

                HorizontalDivider()

                // ---- Messages & media ----
                SectionLabel("Messages & media")
                Text(
                    "To read messages aloud, reply to WhatsApp/SMS/Instagram, and control " +
                        "music (pause, skip, next), Zuela needs Notification access.",
                    fontSize = 13.sp,
                )
                Button(onClick = { NotificationAccess.openSettings(context) }) {
                    Text("Grant notification access")
                }

                HorizontalDivider()

                // ---- API key ----
                SectionLabel("AI (Groq — free)")
                Text(
                    if (vm.apiKeySet)
                        "A Groq API key is saved. Paste a new one to replace it."
                    else
                        "Open questions use Groq (free, worldwide). Get a key at " +
                            "console.groq.com/keys. Time, alarms, calls, memory and opening " +
                            "apps all work without it.",
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Groq API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The key is stored only in this app's private storage on your phone.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (keyInput.isNotBlank()) vm.saveApiKey(keyInput)
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
    )
}

private val WAKE_WORDS = listOf(
    "zuela" to "Hey Zuela",
    "nova" to "Hey Nova",
    "nessa" to "Hey Nessa",
    "assistant" to "Hey Assistant",
)
