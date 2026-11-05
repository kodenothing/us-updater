package com.maenalyn.couples

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {
    private val vm: MainVm by viewModels()
    private var lockStateSetter: ((Boolean) -> Unit)? = null
    private var splashTrigger by mutableIntStateOf(0)

    companion object {
        const val EXTRA_SHOW_SPLASH = "com.maenalyn.couples.EXTRA_SHOW_SPLASH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideNavigationBar()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            val splashRequest = splashTrigger
            var splashVisible by remember { mutableStateOf(true) }
            androidx.compose.runtime.LaunchedEffect(splashRequest) {
                splashVisible = true
                delay(1750)
                splashVisible = false
            }
            val mode by vm.themeMode.collectAsState()
            val paletteIdx by vm.palette.collectAsState()
            val appLockEnabled by vm.appLockEnabled.collectAsState()
            val currentCode by vm.code.collectAsState()
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            androidx.compose.runtime.LaunchedEffect(currentCode) {
                val prefs = getSharedPreferences("us", MODE_PRIVATE)
                if (currentCode != null && Build.VERSION.SDK_INT >= 33 &&
                    !prefs.getBoolean("notificationPrompted", false)) {
                    prefs.edit().putBoolean("notificationPrompted", true).apply()
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) { 1 -> false; 2 -> true; else -> systemDark }
            var unlocked by remember { mutableStateOf(!appLockEnabled) }
            var showDndWelcome by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(splashVisible, unlocked) {
                if (!splashVisible && unlocked) {
                    val prefs = getSharedPreferences("us", MODE_PRIVATE)
                    val alreadyPrompted = prefs.getBoolean("dndFirstLaunchPrompted", false)
                    if (!alreadyPrompted &&
                        !UsNotifications.hasDndAccess(this@MainActivity) &&
                        UsNotifications.isDndActive(this@MainActivity)
                    ) {
                        prefs.edit().putBoolean("dndFirstLaunchPrompted", true).apply()
                        showDndWelcome = true
                    }
                }
            }
            androidx.compose.runtime.LaunchedEffect(appLockEnabled) {
                if (!appLockEnabled) unlocked = true
            }
            SideEffect { lockStateSetter = { shouldLock -> unlocked = !shouldLock } }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
                onDispose { }
            }
            UsTheme(dark, paletteIdx) {
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (appLockEnabled && !unlocked) {
                            AppLockScreen(onUnlock = { authenticate { unlocked = true } })
                        } else {
                            val c = currentCode
                            if (c == null) PairingScreen(vm) else PairingFlowScreen(vm, c)
                        }
                    }
                    AnimatedVisibility(
                        visible = splashVisible,
                        enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.985f, animationSpec = tween(420)),
                        exit = fadeOut(tween(420)) + scaleOut(targetScale = 1.015f, animationSpec = tween(420))
                    ) {
                        UsSplashScreen()
                    }
                    if (showDndWelcome) {
                        AlertDialog(
                            onDismissRequest = { showDndWelcome = false },
                            title = { Text("Do Not Disturb is on") },
                            text = {
                                Text(
                                    "Your phone is currently filtering notifications. You can let selected Us partner alerts and calendar reminders bypass Priority-mode Do Not Disturb when your phone's active DND rules allow them. Android will open a system settings page, where you must allow Do Not Disturb access for Us."
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDndWelcome = false
                                        getSharedPreferences("us", MODE_PRIVATE)
                                            .edit().putBoolean("bypassDndNotifications", true).apply()
                                        UsNotifications.createChannels(this@MainActivity)
                                        if (!UsNotifications.openDndAccessSettings(this@MainActivity)) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Could not open Do Not Disturb access settings. You can enable it later in Us Settings.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                ) { Text("Open DND settings") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDndWelcome = false }) { Text("Not now") }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
        UsNotifications.createChannels(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_SPLASH, false)) {
            splashTrigger += 1
        }
    }

    override fun onStop() {
        super.onStop()
        if (getSharedPreferences("us", MODE_PRIVATE).getBoolean("appLock", false)) {
            lockStateSetter?.invoke(true)
        }
    }

    private fun authenticate(onSuccess: () -> Unit) {
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        val manager = BiometricManager.from(this)
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            val setupText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                "Set up a fingerprint, face unlock, or device screen lock first."
            else
                "Enroll a fingerprint or face biometric first."
            Toast.makeText(this, setupText, Toast.LENGTH_LONG).show()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Us")
            .setSubtitle("Verify to open your shared space")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

@androidx.compose.runtime.Composable
private fun AppLockScreen(onUnlock: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.background))).padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(cs.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Lock, null, tint = cs.onPrimary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("Your space is private", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Unlock to open your little world together.", color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUnlock, shape = RoundedCornerShape(50)) {
                Icon(Icons.Filled.Favorite, null)
                Spacer(Modifier.size(8.dp))
                Text("Unlock Us")
            }
        }
    }
}
