package com.myra.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val MyraBackground = Color(0xFF0A111D)
private val MyraSurface = Color(0xFF121D2B)
private val MyraBorder = Color(0xFF233246)
private val MyraMint = Color(0xFF62E6D4)
private val MyraText = Color(0xFFF1F7FA)
private val MyraMuted = Color(0xFFA0AFBF)

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val microphoneGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                results[Manifest.permission.POST_NOTIFICATIONS] == true ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

            if (microphoneGranted && notificationsGranted) {
                authenticateThenStart()
            } else {
                AssistantRuntime.status.value =
                    "Grant microphone and notification permission to start hands-free listening."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 17, 29)
        window.navigationBarColor = android.graphics.Color.rgb(10, 17, 29)
        setContent {
            MyraScreen(
                geminiConfigured = ApiKeyVault.hasGeminiKey(this),
                listeningFlow = AssistantRuntime.listening,
                statusFlow = AssistantRuntime.status,
                lastEventFlow = AssistantRuntime.lastEvent,
                onStart = ::requestPermissionsAndStart,
                onStop = ::stopAssistant,
                onSaveKeys = ::saveKeys,
                onClearOpenAiKey = {
                    ApiKeyVault.clearOpenAiKey(this)
                    "Optional OpenAI fallback key removed from this phone."
                },
                onOpenSecuritySettings = {
                    runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                        .onFailure { showMessage("Open Android Settings and set a screen lock.") }
                },
            )
        }
        handleActionIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ForegroundState.activityVisible = true
    }

    override fun onStop() {
        ForegroundState.activityVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleActionIntent(intent)
    }

    private fun saveKeys(gemini: String, openAi: String): String {
        return try {
            if (gemini.isNotBlank()) ApiKeyVault.saveGeminiKey(this, gemini)
            if (openAi.isNotBlank()) ApiKeyVault.saveOpenAiKey(this, openAi)
            if (gemini.isBlank() && openAi.isBlank()) {
                "No changes saved. Enter a Gemini key; OpenAI remains optional."
            } else if (!ApiKeyVault.hasGeminiKey(this)) {
                "OpenAI was saved, but a Gemini key is still required before MYRA can start."
            } else {
                "Provider keys encrypted and saved on this phone."
            }
        } catch (_: Exception) {
            "Android secure key storage failed. No key was saved."
        }
    }

    private fun requestPermissionsAndStart() {
        if (!ApiKeyVault.hasGeminiKey(this)) {
            AssistantRuntime.status.value = "Add a Gemini key before starting."
            showMessage("Add your Gemini API key in MYRA settings first.")
            return
        }

        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isEmpty()) authenticateThenStart() else permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun authenticateThenStart() {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val manager = BiometricManager.from(this)
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            AssistantRuntime.status.value = "Set up a secure phone screen lock first."
            showMessage("Set up a fingerprint, face unlock, or secure screen lock in Android settings.")
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val serviceIntent = Intent(this@MainActivity, AssistantService::class.java)
                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    AssistantRuntime.status.value = "Starting hands-free session"
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    AssistantRuntime.status.value = "Session not started"
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Start MYRA hands-free session")
            .setSubtitle("Confirm with your phone's biometric or screen lock")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun stopAssistant() {
        stopService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
        AssistantRuntime.listening.value = false
        AssistantRuntime.status.value = "Assistant stopped"
    }

    private fun handleActionIntent(intent: Intent?) {
        if (intent?.action != AssistantService.ACTION_EXECUTE_ACTION) return
        val kind = intent.getStringExtra(AndroidActions.EXTRA_ACTION_KIND).orEmpty()
        val value = intent.getStringExtra(AndroidActions.EXTRA_ACTION_VALUE).orEmpty()
        val result = AndroidActions.executeApprovedAction(this, kind, value)
        AssistantRuntime.lastEvent.value = result
        showMessage(result)
        intent.action = null
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun MyraScreen(
    geminiConfigured: Boolean,
    listeningFlow: kotlinx.coroutines.flow.StateFlow<Boolean>,
    statusFlow: kotlinx.coroutines.flow.StateFlow<String>,
    lastEventFlow: kotlinx.coroutines.flow.StateFlow<String>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSaveKeys: (String, String) -> String,
    onClearOpenAiKey: () -> String,
    onOpenSecuritySettings: () -> Unit,
) {
    val listening by listeningFlow.collectAsState()
    val status by statusFlow.collectAsState()
    val lastEvent by lastEventFlow.collectAsState()
    var geminiInput by remember { mutableStateOf("") }
    var openAiInput by remember { mutableStateOf("") }
    var keyStatus by remember {
        mutableStateOf(if (geminiConfigured) "Gemini key is saved on this phone." else "Gemini key required; OpenAI is optional.")
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MyraMint,
            background = MyraBackground,
            surface = MyraSurface,
            onBackground = MyraText,
            onSurface = MyraText,
        ),
    ) {
        Surface(color = MyraBackground) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MYRA", color = MyraText, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("YOUR PERSONAL ASSISTANT", color = MyraMuted, fontSize = 10.sp, letterSpacing = 2.sp)
                    }
                    Surface(color = if (listening) Color(0xFF173D3B) else MyraSurface, shape = CircleShape) {
                        Text(
                            if (listening) "● LISTENING" else "○ OFF",
                            color = if (listening) MyraMint else MyraMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MyraSurface),
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MyraBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            color = Color(0xFF173D3B),
                            shape = CircleShape,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("M", color = MyraMint, fontSize = 36.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(horizontal = 27.dp, vertical = 18.dp))
                        }
                        Text(status, color = MyraText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (listening) "Speak naturally. Replies are spoken aloud." else "Authenticate and start a hands-free session.",
                            color = MyraMuted,
                            fontSize = 13.sp,
                        )
                        Button(
                            onClick = if (listening) onStop else onStart,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (listening) Color(0xFF943E50) else MyraMint,
                                contentColor = if (listening) MyraText else MyraBackground,
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) {
                            Text(
                                if (listening) "Stop listening" else "Start hands-free",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                InfoCard(title = "RECENT", body = lastEvent)
                Spacer(Modifier.height(12.dp))
                InfoCard(
                    title = "PHONE SECURITY",
                    body = "Your phone's biometric or screen lock confirms the start of a session. Android does not provide secure voice-only identity verification; anyone near the phone may be heard while the session is active.",
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenSecuritySettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C2A3B), contentColor = MyraText),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Android security settings")
                }

                Spacer(Modifier.height(22.dp))
                Text("AI PROVIDERS", color = MyraMint, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Gemini is required. OpenAI is an optional fallback for quota or service outages. Provider use is billed to your provider accounts.",
                    color = MyraMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                SecretInput(
                    value = geminiInput,
                    onValueChange = { geminiInput = it },
                    label = "Gemini API key (required)",
                )
                Spacer(Modifier.height(9.dp))
                SecretInput(
                    value = openAiInput,
                    onValueChange = { openAiInput = it },
                    label = "OpenAI API key (optional fallback)",
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        keyStatus = onSaveKeys(geminiInput, openAiInput)
                        geminiInput = ""
                        openAiInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C2A3B), contentColor = MyraText),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Encrypt and save on this phone")
                }
                Button(
                    onClick = { keyStatus = onClearOpenAiKey() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C2A3B), contentColor = MyraText),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove optional OpenAI fallback key")
                }
                Text(keyStatus, color = MyraMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Keys are entered after installation and encrypted with Android Keystore. They are not included in the APK. Speech recognition and voice quality depend on the Android services and en-IN voices installed on your phone.",
                    color = MyraMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun SecretInput(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = MyraMuted, fontSize = 12.sp) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MyraMint,
            unfocusedBorderColor = MyraBorder,
            focusedTextColor = MyraText,
            unfocusedTextColor = MyraText,
            cursorColor = MyraMint,
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MyraSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MyraBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = MyraMint, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(body, color = MyraMuted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
