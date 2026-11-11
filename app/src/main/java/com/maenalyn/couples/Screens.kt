package com.maenalyn.couples

import android.graphics.Bitmap
import android.net.Uri
import android.graphics.Color as AColor
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.firebase.auth.FirebaseAuth
import com.maenalyn.couples.chat.UsChatOutboxStore
import com.maenalyn.couples.chat.UsChatRepository
import com.maenalyn.couples.chat.UsChatScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ---------- Shared bits ----------

@Composable
fun PulsingHeart(
    size: Dp,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    flat: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val scale = if (animated) pulse else 1f
    val gradient = LocalUsGradient.current
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .then(
                if (flat) Modifier.background(Color.White.copy(alpha = 0.25f))
                else Modifier.background(gradient)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Favorite, null, Modifier.size(size * 0.5f), tint = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnniversaryPicker(initialEpochDay: Long, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialEpochDay * 86_400_000L)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                state.selectedDateMillis?.let { onSet(Math.floorDiv(it, 86_400_000L)) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton({ onDismiss() }) { Text("Cancel") } }
    ) { DatePicker(state) }
}

// ---------- Pairing ----------

@Composable
fun PairingScreen(vm: MainVm) {
    val busy by vm.busy.collectAsState()
    val appLockEnabled by vm.appLockEnabled.collectAsState()
    val msg by vm.message.collectAsState()
    var showEnter by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { vm.join(it) }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.background)))) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PulsingHeart(96.dp)
            Spacer(Modifier.height(20.dp))
            Text("Us", style = MaterialTheme.typography.displayMedium, color = cs.onBackground)
            Text(
                "Just the two of you.\nOne makes a code, the other scans it.",
                textAlign = TextAlign.Center,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )
            Button(
                onClick = { vm.generate() }, enabled = !busy,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("Generate a code") }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setCaptureActivity(PortraitCaptureActivity::class.java)
                            .setPrompt("Scan your partner's QR code")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                enabled = !busy, shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("Scan a QR code") }
            TextButton(onClick = vm::recoverCurrentPairing, enabled = !busy) {
                Text("Recover pairing on this phone")
            }
            TextButton(onClick = { showEnter = true }, enabled = !busy) { Text("Enter a code instead") }
            TextButton(onClick = { showRestore = true }, enabled = !busy) {
                Text("Restore an existing account")
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
            msg?.let {
                Text(AppMessageText.forDisplay(it), color = if (it == "Pairing code copied.") cs.primary else cs.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }

    if (showEnter) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEnter = false },
            title = { Text("Enter code") },
            text = {
                OutlinedTextField(
                    text, { text = it.uppercase() }, singleLine = true,
                    shape = RoundedCornerShape(20.dp), placeholder = { Text("8- or 12-character code") }
                )
            },
            confirmButton = { TextButton({ showEnter = false; vm.join(text) }, enabled = !busy) { Text("Pair") } },
            dismissButton = { TextButton({ showEnter = false }, enabled = !busy) { Text("Cancel") } }
        )
    }

    if (showRestore) {
        EmailPasswordDialog(
            title = "Restore your account",
            description = "Sign in with the email and password you linked on your previous phone.",
            actionLabel = "Restore",
            busy = busy,
            onDismiss = { showRestore = false },
            onSubmit = { email, password ->
                showRestore = false
                vm.restoreAccount(email, password)
            }
        )
    }
}

@Composable
private fun EmailPasswordDialog(
    title: String,
    description: String,
    actionLabel: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (description.isNotBlank()) {
                    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(email.trim(), password) },
                enabled = !busy && email.isNotBlank() && password.isNotBlank()
            ) { Text(actionLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}

private fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val dark = AColor.rgb(59, 42, 102)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (m[x, y]) dark else AColor.WHITE)
    }
    return bmp
}

@Composable
fun InviteCard(code: String, onCodeCopied: () -> Unit) {
    val bmp = remember(code) { qrBitmap(code).asImageBitmap() }
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = Color.White,
            tonalElevation = 1.dp,
            shadowElevation = 3.dp
        ) {
            Image(
                bitmap = bmp,
                contentDescription = "Pairing QR code",
                modifier = Modifier.padding(16.dp).size(220.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surface)
                .padding(start = 20.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = code,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = cs.onSurface
            )
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Us pairing code", code)
                    )
                    onCodeCopied()
                }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy pairing code", tint = cs.primary)
            }
        }
    }
}

private enum class PairingRouteState { LOADING, ERROR, WAITING, PAIRED }

@Composable
fun PairingFlowScreen(vm: MainVm, code: String) {
    val couple by vm.couple.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val coupleLoadError by vm.coupleLoadError.collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }
    var showRestoreAccount by remember { mutableStateOf(false) }
    val route = when {
        couple == null && coupleLoadError != null -> PairingRouteState.ERROR
        couple == null -> PairingRouteState.LOADING
        couple!!.memberCount < 2 -> PairingRouteState.WAITING
        else -> PairingRouteState.PAIRED
    }
    val requestCancel = { if (!busy && route == PairingRouteState.WAITING) confirmCancel = true }

    LaunchedEffect(route) {
        if (route == PairingRouteState.PAIRED) confirmCancel = false
    }

    BackHandler(enabled = route == PairingRouteState.WAITING) {
        if (!busy) confirmCancel = true
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                fadeIn(tween(700, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(650, easing = FastOutSlowInEasing))
            },
            label = "pairing to shared space transition"
        ) { destination ->
            when (destination) {
                PairingRouteState.LOADING -> PairingLoadingScreen()
                PairingRouteState.ERROR -> PairingRecoveryScreen(
                    message = coupleLoadError.orEmpty(),
                    onRetry = vm::retryCoupleCheck,
                    onRestoreAccount = { showRestoreAccount = true }
                )
                PairingRouteState.WAITING -> PairingInviteScreen(code, message) {
                    vm.message.value = "Pairing code copied."
                }
                PairingRouteState.PAIRED -> HomeScreen(vm)
            }
        }

        if (route == PairingRouteState.WAITING) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .systemBarsPadding()
                    .padding(start = 16.dp, top = 8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 3.dp
            ) {
                IconButton(onClick = requestCancel, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to pairing")
                }
            }
        }
    }

    if (showRestoreAccount) {
        EmailPasswordDialog(
            title = "Restore your account",
            description = "Sign in with the email and password you previously linked to this shared space.",
            actionLabel = "Restore",
            busy = busy,
            onDismiss = { showRestoreAccount = false },
            onSubmit = { email, password ->
                showRestoreAccount = false
                vm.restoreAccount(email, password)
            }
        )
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmCancel = false },
            title = { Text("Cancel this invite?") },
            text = { Text("The code will stop working. You can generate a new one anytime.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        vm.cancelPendingInvite()
                    },
                    enabled = !busy
                ) { Text("Cancel invite") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }, enabled = !busy) {
                    Text("Keep waiting")
                }
            }
        )
    }
}

@Composable
private fun PairingRecoveryScreen(
    message: String,
    onRetry: () -> Unit,
    onRestoreAccount: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.background))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PulsingHeart(76.dp, animated = false)
            Text(
                "Couldn't verify your shared space",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                message,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "This check has not deleted your saved pairing code or local data.",
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Retry verification")
            }
            TextButton(onClick = onRestoreAccount) {
                Text("Restore an existing account")
            }
        }
    }
}

@Composable
private fun PairingLoadingScreen() {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.background))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PulsingHeart(76.dp)
            Spacer(Modifier.height(20.dp))
            Text("Opening your shared space", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PairingInviteScreen(code: String, message: String?, onCodeCopied: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.background)))
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Pair your phones",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))
            InviteCard(code, onCodeCopied)
            Spacer(Modifier.height(22.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = cs.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Waiting for partner…",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            message?.let {
                Text(
                    AppMessageText.forDisplay(it),
                    color = cs.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

// ---------- Home ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainVm) {
    val couple by vm.couple.collectAsState()
    val all by vm.items.collectAsState()
    val events by vm.events.collectAsState()
    val notes by vm.notes.collectAsState()
    val memories by vm.memories.collectAsState()
    val sharedLocations by vm.sharedLocations.collectAsState()
    val sharingEnabled by vm.shareLocationEnabled.collectAsState()
    val keepSharingInBackground by vm.keepLocationSharingInBackground.collectAsState()
    val shareDurationMinutes by vm.shareDurationMinutes.collectAsState()
    val shareExpiresAtMillis by vm.shareLocationExpiresAtMillis.collectAsState()
    val savedPlaces by vm.savedPlaces.collectAsState()
    val placeAlertsEnabled by vm.placeAlertsEnabled.collectAsState()
    val currentUid = Repo.currentUid()
    val partnerName = couple?.memberNames?.entries?.firstOrNull { it.key != currentUid }?.value.orEmpty().ifBlank { "Partner" }
    val myNickname by vm.myNickname.collectAsState()
    val dailyUs by vm.dailyUs.collectAsState()
    val busy by vm.busy.collectAsState()
    val appLockEnabled by vm.appLockEnabled.collectAsState()
    val pendingWrites by vm.pendingWrites.collectAsState()
    val msg by vm.message.collectAsState()
    val title by vm.appTitle.collectAsState()
    val showMilestones by vm.showMilestones.collectAsState()
    val showBreakdown by vm.showBreakdown.collectAsState()
    val animateHeart by vm.animateHeart.collectAsState()
    val heartSizeDp by vm.heartSizeDp.collectAsState()
    val heartAnimationDurationMs by vm.heartAnimationDurationMs.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    BackHandler(enabled = tab == 8 || tab == 9) { tab = 0 }
    var confirm by remember { mutableStateOf(false) }
    var showDailyUs by remember { mutableStateOf(false) }
    var todayKey by remember { mutableStateOf(LocalDate.now().toString()) }
    var dailyDateKey by remember { mutableStateOf(todayKey) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var locationPermissionGranted by remember { mutableStateOf(hasLocationPermission()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission()
        locationPermissionGranted = granted
        if (granted) vm.setLocationSharingEnabled(true)
        else vm.message.value = "Location permission was not granted. You can enable it in Android Settings."
    }
    var pendingBackgroundSharingEnable by remember { mutableStateOf(false) }
    var pendingPlaceAlertsEnable by remember { mutableStateOf(false) }
    var pendingPlaceAlertsSettingsReturn by remember { mutableStateOf(false) }
    var showPlaceAlertsBackgroundDialog by remember { mutableStateOf(false) }
    fun hasBackgroundLocationPermission(): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    var backgroundLocationPermissionGranted by remember {
        mutableStateOf(PlaceGeofenceManager.hasRequiredPermissions(context))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val shouldEnableSharing = pendingBackgroundSharingEnable
        val shouldEnablePlaceAlerts = pendingPlaceAlertsEnable
        pendingBackgroundSharingEnable = false
        pendingPlaceAlertsEnable = false
        if (granted && shouldEnableSharing) vm.setKeepLocationSharingInBackground(true)
        else if (shouldEnableSharing) vm.message.value = "Allow notifications to keep live sharing visible while the screen is off."
        if (granted && shouldEnablePlaceAlerts) vm.setPlaceAlertsEnabled(true)
        else if (shouldEnablePlaceAlerts) vm.message.value = "Allow notifications before enabling arrival and departure alerts."
    }
    fun requestPlaceAlertNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingPlaceAlertsEnable = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingPlaceAlertsEnable = false
            vm.setPlaceAlertsEnabled(true)
        }
    }
    val placeBackgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        backgroundLocationPermissionGranted = PlaceGeofenceManager.hasRequiredPermissions(context)
        if (granted && backgroundLocationPermissionGranted) requestPlaceAlertNotifications()
        else {
            pendingPlaceAlertsEnable = false
            vm.message.value = "Allow all-time location access to enable saved-place arrival alerts."
        }
    }
    val placeFineLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        backgroundLocationPermissionGranted = PlaceGeofenceManager.hasRequiredPermissions(context)
        if (!granted) {
            pendingPlaceAlertsEnable = false
            vm.message.value = "Precise location is required for saved-place alerts."
        } else if (Build.VERSION.SDK_INT >= 30 && !hasBackgroundLocationPermission()) {
            showPlaceAlertsBackgroundDialog = true
        } else if (Build.VERSION.SDK_INT == 29 && !hasBackgroundLocationPermission()) {
            placeBackgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestPlaceAlertNotifications()
        }
    }
    fun beginPlaceAlertsEnable() {
        pendingPlaceAlertsEnable = true
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            placeFineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else if (Build.VERSION.SDK_INT >= 30 && !hasBackgroundLocationPermission()) {
            showPlaceAlertsBackgroundDialog = true
        } else if (Build.VERSION.SDK_INT == 29 && !hasBackgroundLocationPermission()) {
            placeBackgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestPlaceAlertNotifications()
        }
    }
    val locationPublisher = remember(context) { ForegroundLocationPublisher(context) }

    DisposableEffect(lifecycleOwner, sharingEnabled, keepSharingInBackground, locationPermissionGranted) {
        fun startSharing() {
            if (sharingEnabled && !keepSharingInBackground && locationPermissionGranted && hasLocationPermission()) {
                locationPublisher.start { location -> vm.publishMyLocation(location) }
            }
        }
        fun stopSharing() {
            locationPublisher.stop()
            if (!vm.keepLocationSharingInBackground.value) vm.removeMySharedLocation()
        }
        val locationObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startSharing()
                Lifecycle.Event.ON_PAUSE -> stopSharing()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(locationObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startSharing()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(locationObserver)
            stopSharing()
        }
    }

    DisposableEffect(lifecycleOwner, pendingPlaceAlertsSettingsReturn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationPermissionGranted = hasLocationPermission()
                backgroundLocationPermissionGranted = PlaceGeofenceManager.hasRequiredPermissions(context)
                vm.refreshLocationSharingPreferences()
                vm.refreshPlaceAlertRegistration()
                if (pendingPlaceAlertsSettingsReturn) {
                    pendingPlaceAlertsSettingsReturn = false
                    if (backgroundLocationPermissionGranted) {
                        requestPlaceAlertNotifications()
                    } else {
                        pendingPlaceAlertsEnable = false
                        vm.message.value = "Allow all-time location access in app settings to enable place alerts."
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val previousToday = todayKey
                val refreshedToday = LocalDate.now().toString()
                todayKey = refreshedToday
                if (dailyDateKey == previousToday) dailyDateKey = refreshedToday
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(dailyDateKey) { vm.watchDailyUs(dailyDateKey) }
    val snack = remember { SnackbarHostState() }
    val cs = MaterialTheme.colorScheme
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(msg) {
        msg?.let {
            snack.showSnackbar(AppMessageText.forDisplay(it))
            if (vm.message.value == it) vm.message.value = null
        }
    }

    val tabs = listOf(
        Triple("Calendar", Icons.Filled.CalendarMonth, 1),
        Triple("Notes", Icons.Filled.FavoriteBorder, 2),
        Triple("Memories", Icons.Filled.Image, 3),
        Triple("Lists", Icons.Filled.CheckCircle, 4)
    )

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen && !imeVisible,
        scrimColor = cs.scrim.copy(alpha = 0.34f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(260.dp),
                drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
                drawerContainerColor = cs.surface,
                drawerTonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 28.dp, end = 8.dp, bottom = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(15.dp),
                            color = cs.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "Us",
                                    color = cs.onPrimaryContainer,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Us",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Your shared space",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = cs.outlineVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        "YOUR SPACE",
                        modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        letterSpacing = 1.2.sp
                    )

                    NavigationDrawerItem(
                        label = { Text("Our Instant") },
                        selected = tab == 8,
                        onClick = {
                            tab = 8
                            drawerScope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Filled.Widgets, contentDescription = null) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("Our Map") },
                        selected = tab == 7,
                        onClick = {
                            tab = 7
                            drawerScope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = tab == 6,
                        onClick = {
                            tab = 6
                            drawerScope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(vertical = 3.dp)
                    )

                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = cs.outlineVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        "A little space for two.",
                        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 24.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    ) {
        Scaffold(
        containerColor = cs.background,
        snackbarHost = {
            SnackbarHost(hostState = snack) { data ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = cs.inverseSurface,
                    contentColor = cs.inverseOnSurface,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = cs.inverseOnSurface.copy(alpha = 0.14f),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Us", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.inverseOnSurface)
                            }
                        }
                        Text(
                            data.visuals.message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3
                        )
                        IconButton(onClick = { data.dismiss() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss message", tint = cs.inverseOnSurface)
                        }
                    }
                }
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "Open navigation menu",
                            tint = cs.onSurface
                        )
                    }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title.ifBlank { "Us" }, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        val subtitle = when {
                            pendingWrites > 0 -> "Changes waiting to sync"
                            tab == 1 -> "Our plans"
                            tab == 2 -> "Little words, kept close"
                            tab == 3 -> "Our moments"
                            tab == 4 -> "Things we're doing and dreaming"
                            tab == 7 -> "See where we are"
                            tab == 8 -> "Our Instant"
                            tab == 9 -> "A little space for your words"
                            else -> ""
                        }
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { tab = 9 }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Us Chat",
                            tint = if (tab == 9) cs.primary else cs.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
    ) { pad ->
        val layoutDirection = LocalLayoutDirection.current
        val screenPad = PaddingValues(
            start = pad.calculateStartPadding(layoutDirection),
            top = pad.calculateTopPadding(),
            end = pad.calculateEndPadding(layoutDirection),
            bottom = pad.calculateBottomPadding() + if (imeVisible) 0.dp else 112.dp
        )

        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                targetState = tab,
            transitionSpec = {
                if (initialState == 7 || targetState == 7) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { -it / 18 })
                }
            },
            label = "main tab transition"
        ) { destination ->
            when (destination) {
                0 -> TogetherTab(
                    couple = couple,
                    events = events,
                    noteCount = notes.size,
                    memoryCount = memories.size,
                    showMilestones = showMilestones,
                    showBreakdown = showBreakdown,
                    animateHeart = animateHeart,
                    heartSizeDp = heartSizeDp,
                    heartAnimationDurationMs = heartAnimationDurationMs,
                    onSetDate = vm::setAnniversary,
                    onOpenCalendar = { tab = 1 },
                    onOpenNotes = { tab = 2 },
                    onOpenMemories = { tab = 3 },
                    onOpenLists = { tab = 4 },
                    dailyUs = dailyUs,
                    onOpenDailyUs = { dailyDateKey = todayKey; showDailyUs = true },
                    pad = screenPad
                )
                1 -> CalendarTab(vm, events, screenPad)
                2 -> NotesTab(vm, notes, screenPad)
                3 -> MemoriesTab(vm, memories, screenPad)
                4 -> ListsHubTab(all, vm, screenPad)
                7 -> OurMapTab(
                    couple = couple,
                    currentUid = currentUid,
                    sharedLocations = sharedLocations,
                    sharingEnabled = sharingEnabled,
                    permissionGranted = locationPermissionGranted,
                    keepSharingInBackground = keepSharingInBackground,
                    shareDurationMinutes = shareDurationMinutes,
                    shareExpiresAtMillis = shareExpiresAtMillis,
                    onShareDurationChange = vm::setLocationSharingDuration,
                    savedPlaces = savedPlaces,
                    placeAlertsEnabled = placeAlertsEnabled,
                    backgroundLocationPermissionGranted = backgroundLocationPermissionGranted,
                    onTogglePlaceAlerts = { enabled ->
                        if (enabled) beginPlaceAlertsEnable() else {
                            pendingPlaceAlertsEnable = false
                            vm.setPlaceAlertsEnabled(false)
                        }
                    },
                    onSavePlace = vm::savePlace,
                    onDeletePlace = vm::deletePlace,
                    onToggleSharing = { enabled ->
                        if (!enabled) {
                            vm.setLocationSharingEnabled(false)
                        } else if (hasLocationPermission()) {
                            vm.setLocationSharingEnabled(true)
                        } else {
                            locationPermissionLauncher.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ))
                        }
                    },
                    onToggleBackgroundSharing = { enabled ->
                        if (!enabled) {
                            vm.setKeepLocationSharingInBackground(false)
                        } else if (!sharingEnabled) {
                            vm.message.value = "Turn on live location sharing first."
                        } else if (!hasLocationPermission()) {
                            vm.message.value = "Allow location access before enabling screen-off sharing."
                        } else if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            pendingBackgroundSharingEnable = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setKeepLocationSharingInBackground(true)
                        }
                    },
                    modifier = Modifier.padding(screenPad)
                )
                8 -> OurInstantTab(
                    code = vm.code.value.orEmpty(),
                    uid = currentUid.orEmpty(),
                    pad = screenPad
                )
                9 -> UsChatScreen(
                    coupleCode = vm.code.value.orEmpty(),
                    currentUid = currentUid.orEmpty(),
                    partnerName = partnerName,
                    myNickname = myNickname,
                    modifier = Modifier.padding(screenPad)
                )
                else -> SettingsTab(vm, couple, { confirm = true }, screenPad)
            }
            }
            if (!imeVisible) {
                UsBottomNavigationBar(
                    tabs = tabs,
                    selectedTab = tab,
                    onTabSelected = { tab = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
        }
    }

    if (showPlaceAlertsBackgroundDialog) {
        AlertDialog(
            onDismissRequest = {
                showPlaceAlertsBackgroundDialog = false
                pendingPlaceAlertsEnable = false
            },
            title = { Text("Allow location all the time") },
            text = {
                Text("Us uses this permission only to detect when you arrive at or leave a saved place, even when the app is closed. Live location sharing remains a separate setting, and you can disable place alerts at any time.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPlaceAlertsBackgroundDialog = false
                    pendingPlaceAlertsSettingsReturn = true
                    val settingsIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(settingsIntent) }
                        .onFailure {
                            pendingPlaceAlertsSettingsReturn = false
                            pendingPlaceAlertsEnable = false
                            vm.message.value = "Couldn't open app settings. Enable Allow all the time under Us app permissions."
                        }
                }) { Text("Open app settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPlaceAlertsBackgroundDialog = false
                    pendingPlaceAlertsEnable = false
                }) { Text("Not now") }
            }
        )
    }

    if (showDailyUs) {
        ModalBottomSheet(
            onDismissRequest = { showDailyUs = false; dailyDateKey = todayKey },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            DailyUsSheetContent(
                state = dailyUs,
                saving = pendingWrites > 0,
                onSaveAnswer = vm::saveDailyAnswer,
                onSaveCheckin = vm::saveDailyCheckin
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = false },
            title = { Text("Request permanent deletion?") },
            text = {
                Text(
                    "Both partners must approve before the shared space and its memories, notes, plans, and lists can be permanently deleted. " +
                        "Your approval is saved until your partner responds."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.unpair(); confirm = false }, enabled = !busy) {
                    Text("Request deletion", color = cs.error)
                }
            },
            dismissButton = { TextButton({ confirm = false }, enabled = !busy) { Text("Cancel") } }
        )
    }
}

private object HeartNavigationShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.94f)
            cubicTo(w * 0.43f, h * 0.86f, w * 0.055f, h * 0.62f, w * 0.055f, h * 0.32f)
            cubicTo(w * 0.055f, h * 0.12f, w * 0.23f, h * 0.015f, w * 0.39f, h * 0.085f)
            cubicTo(w * 0.455f, h * 0.115f, w * 0.49f, h * 0.18f, w * 0.5f, h * 0.245f)
            cubicTo(w * 0.51f, h * 0.18f, w * 0.545f, h * 0.115f, w * 0.61f, h * 0.085f)
            cubicTo(w * 0.77f, h * 0.015f, w * 0.945f, h * 0.12f, w * 0.945f, h * 0.32f)
            cubicTo(w * 0.945f, h * 0.62f, w * 0.57f, h * 0.86f, w * 0.5f, h * 0.94f)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun UsBottomNavigationBar(
    tabs: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, Int>>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val leftTabs = tabs.take(2)
    val rightTabs = tabs.drop(2)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .height(90.dp)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(26.dp),
            color = cs.surfaceContainerLow,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f).fillMaxHeight()) {
                    leftTabs.forEach { (label, icon, destination) ->
                        UsNavigationIcon(
                            label = label,
                            icon = icon,
                            selected = selectedTab == destination,
                            modifier = Modifier.weight(1f),
                            onClick = { onTabSelected(destination) }
                        )
                    }
                }
                Spacer(Modifier.width(68.dp))
                Row(Modifier.weight(1f).fillMaxHeight()) {
                    rightTabs.forEach { (label, icon, destination) ->
                        UsNavigationIcon(
                            label = label,
                            icon = icon,
                            selected = selectedTab == destination,
                            modifier = Modifier.weight(1f),
                            onClick = { onTabSelected(destination) }
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 78.dp, height = 90.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(width = 72.dp, height = 70.dp)
                    .shadow(8.dp, HeartNavigationShape)
                    .clip(HeartNavigationShape)
                    .background(cs.primary)
                    .clickable { onTabSelected(0) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Home",
                    modifier = Modifier.size(27.dp).offset(y = (-2).dp),
                    tint = cs.onPrimary
                )
            }
            if (selectedTab == 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                )
            }
        }
    }
}

@Composable
private fun UsNavigationIcon(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(23.dp),
            tint = if (selected) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.58f)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (selected) cs.primary else Color.Transparent)
        )
    }
}

// ---------- Together tab ----------

@Composable
fun TogetherTab(
    couple: CoupleState.Active?,
    events: List<CalendarEvent>,
    noteCount: Int,
    memoryCount: Int,
    showMilestones: Boolean,
    showBreakdown: Boolean,
    animateHeart: Boolean,
    heartSizeDp: Int,
    heartAnimationDurationMs: Int,
    onSetDate: (Long) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenLists: () -> Unit,
    dailyUs: DailyUsState,
    onOpenDailyUs: () -> Unit,
    pad: PaddingValues
) {
    var picking by remember { mutableStateOf(false) }
    val ann = couple?.anniversaryEpochDay ?: DEFAULT_ANNIVERSARY

    Column(
        Modifier.padding(pad).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Counter(
            LocalDate.ofEpochDay(ann), showMilestones, showBreakdown, animateHeart,
            heartSizeDp, heartAnimationDurationMs
        ) { picking = true }
        UpcomingPlanCard(events, onOpenCalendar)
        DailyUsPreviewCard(dailyUs, onOpenDailyUs)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardShortcut("Calendar", "Plan your next date", Icons.Filled.CalendarMonth, onOpenCalendar, Modifier.weight(1f))
            DashboardShortcut("Love notes", "$noteCount saved", Icons.Filled.Favorite, onOpenNotes, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardShortcut("Memories", "$memoryCount photos", Icons.Filled.Image, onOpenMemories, Modifier.weight(1f))
            DashboardShortcut("Our lists", "Things to do and dream", Icons.Filled.CheckCircle, onOpenLists, Modifier.weight(1f))
        }
    }

    if (picking) {
        AnniversaryPicker(ann, { picking = false }, onSetDate)
    }
}

@Composable
private fun UpcomingPlanCard(events: List<CalendarEvent>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now().toEpochDay()
    val nextEvent = events.filter { it.dateEpochDay >= today }
        .minWithOrNull(compareBy<CalendarEvent> { it.dateEpochDay }.thenBy { if (it.allDay) "" else it.startTime })
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).animateContentSize()
            .background(cs.secondaryContainer.copy(alpha = 0.82f))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(cs.surface.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.CalendarMonth, null, tint = cs.secondary, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("UP NEXT", color = cs.onSecondaryContainer.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(nextEvent?.title ?: "Make a plan together", color = cs.onSecondaryContainer, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                nextEvent?.let {
                    val day = LocalDate.ofEpochDay(it.dateEpochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                    if (it.allDay) day else "$day · ${it.startTime}"
                } ?: "Your shared calendar is ready",
                color = cs.onSecondaryContainer.copy(alpha = 0.78f), fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DailyUsPreviewCard(state: DailyUsState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).animateContentSize()
            .background(cs.secondaryContainer.copy(alpha = 0.64f))
            .clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DAILY US", color = cs.onSecondaryContainer.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.weight(1f))
            Text("Open prompt  ›", color = cs.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(state.question.ifBlank { "A little question for the two of you" }, color = cs.onSecondaryContainer, fontWeight = FontWeight.SemiBold, maxLines = 2)
        val status = when {
            state.bothAnswered && state.bothCheckedIn -> "You both answered and checked in today 💗"
            state.bothAnswered -> "You both answered today's prompt"
            state.hasAnswered -> "Your answer is saved · waiting for your person"
            else -> "Take a moment to answer and check in"
        }
        Text(status, color = cs.onSecondaryContainer.copy(alpha = 0.76f), fontSize = 11.sp)
    }
}

@Composable
private fun DashboardShortcut(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).animateContentSize().background(cs.surfaceVariant.copy(alpha = 0.58f))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(21.dp))
        Text(title, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Counter(
    start: LocalDate,
    showMilestones: Boolean,
    showBreakdown: Boolean,
    animateHeart: Boolean,
    heartSizeDp: Int,
    heartAnimationDurationMs: Int,
    onEdit: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val gradient = LocalUsGradient.current
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(start, today)
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(days) { target = days.coerceAtLeast(0).toInt() }
    val shown by animateIntAsState(target, tween(1200, easing = FastOutSlowInEasing), label = "count")
    val p = Period.between(start, today)

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(gradient).padding(28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PulsingHeart(44.dp, animated = animateHeart, flat = true)
            Spacer(Modifier.height(12.dp))
            if (days < 0) {
                Text(
                    "Your story starts in ${-days} days",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center
                )
            } else {
                Text(
                    "TOGETHER FOR", color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold
                )
                Text("$shown", fontSize = 76.sp, lineHeight = 80.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text(if (days == 1L) "day" else "days", color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (showBreakdown) {
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.22f)) {
                        Text(
                            "${p.years}y  ${p.months}m  ${p.days}d",
                            color = Color.White, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            TextButton(onClick = onEdit, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Text("Change date")
            }
        }
        if (animateHeart && days >= 0) {
            HeartSplashOverlay(
                startEpochDay = start.toEpochDay(),
                days = days,
                heartSizeDp = heartSizeDp,
                durationMs = heartAnimationDurationMs,
                modifier = Modifier.matchParentSize()
            )
        }
    }

    if (days >= 0 && showMilestones) {
        val nextHundred = (days / 100 + 1) * 100
        var next = start.withYear(today.year)
        if (next.isBefore(today)) next = next.plusYears(1)
        var years = next.year - start.year
        if (years < 1) { next = next.plusYears(1); years = 1 }

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Milestone(
                "💞", "Day $nextHundred", start.plusDays(nextHundred), today,
                cs.primaryContainer, cs.onPrimaryContainer, Modifier.weight(1f).fillMaxHeight()
            )
            Milestone(
                "🎉", "$years-year anniversary", next, today,
                cs.secondaryContainer, cs.onSecondaryContainer, Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun HeartSplashOverlay(
    startEpochDay: Long,
    days: Long,
    heartSizeDp: Int,
    durationMs: Int,
    modifier: Modifier = Modifier
) {
    data class Particle(
        val direction: Int,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val sizeFactor: Float,
        val delayFactor: Float,
        val tint: Color
    )

    val particles = listOf(
        Particle(-1, -48f, 74f, -58f, 1.00f, 0.00f, Color(0xFFFFD7E6)),
        Particle(1, -30f, 72f, -42f, 0.88f, 0.06f, Color(0xFFFFAFCB)),
        Particle(-1, 24f, 88f, 44f, 0.72f, 0.13f, Color(0xFFFFB9D0)),
        Particle(1, 46f, 86f, 54f, 0.80f, 0.17f, Color(0xFFFFE3EC)),
        Particle(-1, 2f, 52f, 82f, 0.60f, 0.23f, Color(0xFFFF8FB8)),
        Particle(1, -4f, 52f, -86f, 0.64f, 0.27f, Color(0xFFFFD1E0))
    )

    val safeDuration = durationMs.coerceIn(600, 2600)
    val approachDuration = (safeDuration * 0.58f).roundToInt().coerceAtLeast(300)
    val scatterDuration = (safeDuration * 0.36f).roundToInt().coerceAtLeast(180)
    val rampEasing = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.2f, 1f)

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val travel = widthPx / 2f + with(density) { 30.dp.toPx() }

        particles.forEachIndexed { index, particle ->
            val x = remember(index, travel) { Animatable(particle.direction * travel) }
            val y = remember(index) { Animatable(with(density) { particle.startY.dp.toPx() }) }
            val alpha = remember(index) { Animatable(0f) }
            val scale = remember(index) { Animatable(0.35f) }
            val burstX = with(density) { particle.endX.dp.toPx() }
            val burstY = with(density) { particle.endY.dp.toPx() }
            val particleDelay = (safeDuration * 0.20f * particle.delayFactor).roundToInt().toLong()

            LaunchedEffect(startEpochDay, days, travel, heartSizeDp, safeDuration) {
                x.snapTo(particle.direction * travel)
                y.snapTo(with(density) { particle.startY.dp.toPx() })
                alpha.snapTo(0f)
                scale.snapTo(0.35f)
                delay(particleDelay)

                coroutineScope {
                    launch {
                        x.animateTo(
                            particle.direction * 8f,
                            tween(approachDuration, easing = rampEasing)
                        )
                    }
                    launch { y.animateTo(0f, tween(approachDuration, easing = rampEasing)) }
                    launch { alpha.animateTo(0.96f, tween((approachDuration * 0.38f).roundToInt(), easing = rampEasing)) }
                    launch { scale.animateTo(1.06f, tween(approachDuration, easing = rampEasing)) }
                }

                delay((safeDuration * 0.03f).roundToInt().toLong())
                coroutineScope {
                    launch { x.animateTo(particle.direction * burstX, tween(scatterDuration, easing = FastOutSlowInEasing)) }
                    launch { y.animateTo(burstY, tween(scatterDuration, easing = FastOutSlowInEasing)) }
                    launch { alpha.animateTo(0f, tween(scatterDuration, easing = FastOutSlowInEasing)) }
                    launch { scale.animateTo(0.28f, tween(scatterDuration, easing = FastOutSlowInEasing)) }
                }
            }

            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = particle.tint,
                modifier = Modifier
                    .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
                    .graphicsLayer {
                        this.alpha = alpha.value
                        scaleX = scale.value
                        scaleY = scale.value
                        rotationZ = if (particle.direction < 0) -12f else 12f
                    }
                    .size((heartSizeDp * particle.sizeFactor).dp)
            )
        }
    }
}

@Composable
private fun Milestone(
    emoji: String, title: String, date: LocalDate, today: LocalDate,
    container: Color, content: Color, modifier: Modifier
) {
    val left = ChronoUnit.DAYS.between(today, date)
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(container).padding(16.dp)) {
        Text(emoji, fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold, color = content)
        Text(
            date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")),
            color = content.copy(alpha = 0.7f), fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(if (left == 0L) "Today! 🎉" else "in $left days", color = content, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- To-do / Bucket list ----------

@Composable
fun ListTab(
    type: String, title: String, hint: String, emptyEmoji: String, emptyText: String,
    all: List<Item>, vm: MainVm, pad: PaddingValues
) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val list = all.filter { it.type == type }
    val done = list.count { it.done }

    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            if (list.isEmpty()) "Nothing yet" else "$done of ${list.size} done",
            color = cs.onSurfaceVariant
        )
        if (list.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { done / list.size.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(8.dp).clip(CircleShape),
                color = cs.secondary, trackColor = cs.secondaryContainer
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f),
                placeholder = { Text(hint) }, singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.primary, unfocusedBorderColor = cs.outline
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { vm.addItem(type, text); text = "" },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(52.dp)
            ) { Icon(Icons.Filled.Add, "Add") }
        }

        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(emptyEmoji, fontSize = 44.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(emptyText, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.id }) { item ->
                    val itemSurface by androidx.compose.animation.animateColorAsState(
                        targetValue = cs.surfaceVariant.copy(alpha = if (item.done) 0.4f else 0.85f),
                        animationSpec = tween(220),
                        label = "list item completion"
                    )
                    val itemTextAlpha by animateFloatAsState(
                        targetValue = if (item.done) 0.58f else 1f,
                        animationSpec = tween(220),
                        label = "list item text"
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).animateContentSize()
                            .background(itemSurface),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            item.done, { vm.toggle(item) },
                            colors = CheckboxDefaults.colors(checkedColor = cs.secondary, uncheckedColor = cs.outline)
                        )
                        Text(
                            item.text, Modifier.weight(1f).padding(vertical = 12.dp).graphicsLayer { alpha = itemTextAlpha },
                            color = if (item.done) cs.onSurface.copy(alpha = 0.45f) else cs.onSurface,
                            textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                        )
                        IconButton({ vm.remove(item) }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

// ---------- Settings ----------

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.6f)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = cs.primary)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsTab(
    vm: MainVm,
    couple: CoupleState.Active?,
    onUnpair: () -> Unit,
    pad: PaddingValues
) {
    val cs = MaterialTheme.colorScheme
    val themeMode by vm.themeMode.collectAsState()
    val palette by vm.palette.collectAsState()
    val showMilestones by vm.showMilestones.collectAsState()
    val showBreakdown by vm.showBreakdown.collectAsState()
    val animateHeart by vm.animateHeart.collectAsState()
    val savedNickname by vm.myNickname.collectAsState()
    val savedGender by vm.myGender.collectAsState()
    val partnerNotificationsEnabled by vm.partnerNotificationsEnabled.collectAsState()
    val notifyLists by vm.notifyLists.collectAsState()
    val notifyEvents by vm.notifyEvents.collectAsState()
    val notifyNotes by vm.notifyNotes.collectAsState()
    val notifyMemories by vm.notifyMemories.collectAsState()
    val notifyDaily by vm.notifyDaily.collectAsState()
    val quietHoursEnabled by vm.quietHoursEnabled.collectAsState()
    val quietHoursStart by vm.quietHoursStart.collectAsState()
    val quietHoursEnd by vm.quietHoursEnd.collectAsState()
    val privatePreviews by vm.privateNotificationPreviews.collectAsState()
    val accountEmail by vm.accountEmail.collectAsState()
    val busy by vm.busy.collectAsState()
    val appLockEnabled by vm.appLockEnabled.collectAsState()
    val coupleCode by vm.code.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasDndAccess by remember { mutableStateOf(UsNotifications.hasDndAccess(context)) }
    var bypassDndEnabled by remember {
        mutableStateOf(context.getSharedPreferences("us", android.content.Context.MODE_PRIVATE)
            .getBoolean("bypassDndNotifications", false))
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasDndAccess = UsNotifications.hasDndAccess(context)
                UsNotifications.createChannels(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var picking by remember { mutableStateOf(false) }
    var showRecoverySetup by remember { mutableStateOf(false) }
    var sharedName by remember(couple?.coupleName) { mutableStateOf(couple?.coupleName ?: "Us 💜") }
    var nickname by remember(savedNickname) { mutableStateOf(savedNickname) }
    var gender by remember(savedGender) { mutableStateOf(savedGender) }
    var backupAction by remember { mutableStateOf("export") }
    var backupPassword by remember { mutableStateOf("") }
    var backupSource by remember { mutableStateOf<Uri?>(null) }
    var showBackupPassword by remember { mutableStateOf(false) }
    val backupPreview by vm.backupPreview.collectAsState()
    var quietStartEdit by remember(quietHoursStart) { mutableStateOf(quietHoursStart) }
    var quietEndEdit by remember(quietHoursEnd) { mutableStateOf(quietHoursEnd) }
    var updateBusy by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDialogState by remember { mutableStateOf("checking") }
    var updateMessage by remember { mutableStateOf("") }
    var updateRelease by remember { mutableStateOf<GithubReleaseInfo?>(null) }
    var downloadedUpdate by remember { mutableStateOf<java.io.File?>(null) }
    val updateScope = rememberCoroutineScope()
    var showChatResetConfirm by remember { mutableStateOf(false) }
    var chatResetBusy by remember { mutableStateOf(false) }
    val installedVersion = remember(context) { GithubAppUpdater.installedVersionName(context) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportBackup(uri, backupPassword)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupSource = uri
            backupAction = "restore"
            backupPassword = ""
            showBackupPassword = true
        }
    }
    val ann = couple?.anniversaryEpochDay ?: DEFAULT_ANNIVERSARY
    val paletteIdx = palette.coerceIn(0, UsPalettes.lastIndex)

    Column(
        Modifier.padding(pad).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        SettingsCard("Appearance") {
            Text("Theme", color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("System", "Light", "Dark").forEachIndexed { i, label ->
                    FilterChip(
                        selected = themeMode == i,
                        onClick = { vm.setThemeMode(i) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Accent colors", color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsPalettes.forEachIndexed { i, p ->
                    val selected = paletteIdx == i
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(p.gradStart, p.gradEnd)))
                            .then(if (selected) Modifier.border(3.dp, cs.onSurface, CircleShape) else Modifier)
                            .clickable { vm.setPalette(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Filled.Check, "Selected", tint = Color.White)
                    }
                }
            }
        }

        SettingsCard("My profile") {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.take(24) },
                label = { Text("Nickname") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.renameMyNickname(nickname) },
                enabled = !busy && nickname.trim().isNotBlank() && nickname.trim() != savedNickname,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save nickname") }

            HorizontalDivider(color = cs.outlineVariant)
            Text("Gender", color = cs.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "male" to "Male",
                    "female" to "Female",
                    "unspecified" to "Not set"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = gender == value,
                        onClick = { gender = value },
                        modifier = Modifier.weight(1f),
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
            Button(
                onClick = { vm.setMyGender(gender) },
                enabled = !busy && gender != savedGender,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save gender") }
        }

        SettingsCard("Our shared space") {
            OutlinedTextField(
                value = sharedName,
                onValueChange = { sharedName = it.take(32) },
                label = { Text("Shared space name") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.renameCouple(sharedName) },
                enabled = !busy && sharedName.trim().isNotBlank() && sharedName.trim() != (couple?.coupleName ?: "Us 💜"),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save name") }
        }

        SettingsCard("Us Chat") {
            Text(
                "Clear the current message history for both of you and start with an empty conversation. Your partner's chat will switch automatically when they reconnect.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Messages waiting to send offline will not be carried into the new conversation.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { showChatResetConfirm = true },
                enabled = !chatResetBusy && !coupleCode.isNullOrBlank() && couple?.memberCount == 2,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                border = BorderStroke(1.dp, cs.error.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (chatResetBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Clear conversation & start new")
            }
        }

        SettingsCard("Our story") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Together since", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    Text(
                        LocalDate.ofEpochDay(ann).format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalButton(onClick = { picking = true }) { Text("Change") }
            }
        }

        SettingsCard("Home screen") {
            SwitchRow("Show milestone cards", showMilestones) { vm.setShowMilestones(it) }
            SwitchRow("Show years / months / days", showBreakdown) { vm.setShowBreakdown(it) }
            SwitchRow("Heart splash animation", animateHeart) { vm.setAnimateHeart(it) }
        }

        SettingsCard("Privacy") {
            SwitchRow("Lock with biometrics or screen lock", appLockEnabled) { enabled ->
                if (!enabled) vm.setAppLock(false) else {
                    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    else androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
                    val canUse = androidx.biometric.BiometricManager.from(context).canAuthenticate(authenticators) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                    if (canUse) vm.setAppLock(true)
                    else vm.message.value = "Set up a fingerprint, face unlock, or device screen lock first."
                }
            }
        }

        SettingsCard("Notifications") {
            SwitchRow("Partner activity notifications", partnerNotificationsEnabled) {
                vm.setPartnerNotifications(it)
            }
            SwitchRow("List updates", notifyLists) { vm.setNotificationCategory("lists", it) }
            SwitchRow("Calendar updates", notifyEvents) { vm.setNotificationCategory("events", it) }
            SwitchRow("Love notes", notifyNotes) { vm.setNotificationCategory("notes", it) }
            SwitchRow("Photo memories", notifyMemories) { vm.setNotificationCategory("memories", it) }
            SwitchRow("Daily Us prompts and check-ins", notifyDaily) { vm.setNotificationCategory("daily", it) }
            SwitchRow("Private previews", privatePreviews) { vm.setPrivateNotificationPreviews(it) }
            SwitchRow("Allow important alerts during Do Not Disturb", bypassDndEnabled) { enabled ->
                bypassDndEnabled = enabled
                context.getSharedPreferences("us", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("bypassDndNotifications", enabled).apply()
                hasDndAccess = UsNotifications.hasDndAccess(context)
                UsNotifications.createChannels(context)
                if (enabled && !hasDndAccess) {
                    val opened = UsNotifications.openDndAccessSettings(context)
                    if (!opened) vm.message.value = "Could not open Do Not Disturb access settings. You can enable it in Android Settings."
                }
            }
            Text(
                when {
                    !bypassDndEnabled -> "Off. Us notifications follow your phone's Do Not Disturb settings."
                    hasDndAccess -> "Ready. Partner alerts and calendar reminders can interrupt Do Not Disturb when your phone's DND policy allows priority channels. Your partner-alert switches and quiet hours still apply."
                    else -> "One more step: allow Us under Android's Do Not Disturb access settings."
                },
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (bypassDndEnabled && !hasDndAccess) {
                OutlinedButton(
                    onClick = {
                        val opened = UsNotifications.openDndAccessSettings(context)
                        if (!opened) vm.message.value = "Could not open Do Not Disturb access settings. You can enable it in Android Settings."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant Do Not Disturb access") }
            }
            if (bypassDndEnabled && hasDndAccess) {
                OutlinedButton(
                    onClick = {
                        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                            vm.message.value = "Enable Us notifications in Android Settings first."
                        } else {
                            UsNotifications.show(
                                context,
                                "Us test alert",
                                "Important notifications are using the Do Not Disturb channel.",
                                System.currentTimeMillis().toInt(),
                                partner = true
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send test alert") }
            }
            SwitchRow("Quiet hours", quietHoursEnabled) {
                vm.setQuietHours(it, quietStartEdit, quietEndEdit)
            }
            if (quietHoursEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        quietStartEdit,
                        { quietStartEdit = it.take(5) },
                        label = { Text("From") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        quietEndEdit,
                        { quietEndEdit = it.take(5) },
                        label = { Text("Until") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedButton(onClick = { vm.setQuietHours(true, quietStartEdit, quietEndEdit) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save quiet hours")
                }
            }
            OutlinedButton(onClick = {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                runCatching { context.startActivity(intent) }
                    .onFailure { vm.message.value = "Could not open Android notification settings. Open them from your phone Settings." }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Notification settings")
            }
        }

        SettingsCard("App updates") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Version $installedVersion",
                    modifier = Modifier.weight(1f),
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        showUpdateDialog = true
                        updateDialogState = "checking"
                        updateBusy = true
                        updateMessage = "Checking GitHub for the latest version…"
                        updateRelease = null
                        downloadedUpdate = null
                        updateScope.launch {
                            try {
                                val result = GithubAppUpdater.checkLatestRelease(context)
                                updateRelease = result.release
                                updateDialogState = if (result.updateAvailable) "available" else "up_to_date"
                                updateMessage = if (result.updateAvailable) {
                                    "Version ${result.release.tagName} is available."
                                } else {
                                    "You're using the latest version."
                                }
                            } catch (error: Exception) {
                                updateDialogState = "error"
                                updateMessage = AppMessageText.forDisplay(error, "Couldn't check for updates. Please try again.")
                            } finally {
                                updateBusy = false
                            }
                        }
                    },
                    enabled = !updateBusy
                ) {
                    Text("Update")
                }
            }
        }

        SettingsCard("Backup & restore") {
            OutlinedButton(
                onClick = {
                    backupAction = "export"
                    backupPassword = ""
                    showBackupPassword = true
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export encrypted backup") }
            OutlinedButton(
                onClick = { openBackup.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore from backup") }
        }

        SettingsCard("Account recovery") {
            if (accountEmail.isNullOrBlank()) {
                OutlinedButton(
                    onClick = { showRecoverySetup = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(50)
                ) { Text("Set up account recovery") }
            } else {
                Text(accountEmail!!, fontWeight = FontWeight.SemiBold)
            }
        }

        SettingsCard("Pairing") {
            val members = couple?.memberCount ?: 1
            Text(if (members >= 2) "Paired, 2 of 2" else "Waiting for your partner (1 of 2)")
            val approvals = couple?.deleteApprovals?.count { it.value } ?: 0
            if (approvals > 0) {
                Text("Deletion approval: $approvals of $members", color = cs.error, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onUnpair,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                border = BorderStroke(1.dp, cs.error),
                shape = RoundedCornerShape(50)
            ) { Text("Request permanent deletion") }
        }

        Text(
            "Made only for my Most Beautiful Girl (Mae)",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
        )
    }


    if (showChatResetConfirm) {
        AlertDialog(
            onDismissRequest = { if (!chatResetBusy) showChatResetConfirm = false },
            title = { Text("Start a new Us Chat conversation?") },
            text = {
                Text(
                    "This clears the message contents for both of you and starts a fresh conversation. Your partner will switch to it automatically when connected. Messages still waiting in either phone's offline queue will be discarded. This cannot be undone from the app."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !chatResetBusy,
                    onClick = {
                        val activeCoupleCode = coupleCode
                        val activeUid = FirebaseAuth.getInstance().currentUser?.uid
                        if (activeCoupleCode.isNullOrBlank() || activeUid.isNullOrBlank()) {
                            vm.message.value = "Your paired account couldn't be verified. Reopen Us Chat and try again."
                            showChatResetConfirm = false
                            return@TextButton
                        }

                        chatResetBusy = true
                        updateScope.launch {
                            try {
                                val room = UsChatRepository.findRoom(activeCoupleCode)
                                UsChatRepository.resetConversation(room.id, activeUid)
                                val updatedRoom = UsChatRepository.findRoom(activeCoupleCode)
                                withContext(Dispatchers.IO) {
                                    UsChatOutboxStore.deleteForOtherConversations(
                                        context,
                                        activeCoupleCode,
                                        activeUid,
                                        updatedRoom.id,
                                        updatedRoom.activeConversationId
                                    )
                                    UsChatOutboxStore.cacheRoomMapping(
                                        context,
                                        activeCoupleCode,
                                        activeUid,
                                        updatedRoom.id,
                                        updatedRoom.activeConversationId
                                    )
                                }
                                vm.message.value = "A new Us Chat conversation has started for both of you."
                                showChatResetConfirm = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                vm.message.value = AppMessageText.forDisplay(
                                    failure,
                                    "Couldn't start a new conversation. Check your connection and try again."
                                )
                            } finally {
                                chatResetBusy = false
                            }
                        }
                    }
                ) {
                    if (chatResetBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Clear & start new")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !chatResetBusy,
                    onClick = { showChatResetConfirm = false }
                ) { Text("Cancel") }
            }
        )
    }

    if (showUpdateDialog) {
        val release = updateRelease
        AlertDialog(
            onDismissRequest = { if (!updateBusy) showUpdateDialog = false },
            title = {
                Text(
                    when (updateDialogState) {
                        "checking" -> "Checking for updates"
                        "available" -> "New version available"
                        "downloading" -> "Preparing update"
                        "permission" -> "Installation permission needed"
                        "up_to_date" -> "You're up to date"
                        else -> "Update check failed"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (updateBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(updateMessage.ifBlank { "Please wait…" })
                        }
                    } else {
                        when (updateDialogState) {
                            "available" -> {
                                Text("A new version of Us is available.")
                                Text(
                                    "${release?.tagName ?: "New version"}  ·  Installed: $installedVersion",
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!release?.notes.isNullOrBlank()) {
                                    Text(
                                        release!!.notes,
                                        color = cs.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            "up_to_date" -> Text("You're using the latest version ($installedVersion). No update is available right now.")
                            "permission" -> Text(updateMessage)
                            else -> Text(updateMessage)
                        }
                    }
                }
            },
            confirmButton = {
                when (updateDialogState) {
                    "available" -> TextButton(
                        enabled = !updateBusy && release != null,
                        onClick = {
                            val selectedRelease = release ?: return@TextButton
                            updateBusy = true
                            updateDialogState = "downloading"
                            updateMessage = "Downloading and verifying ${selectedRelease.tagName}…"
                            updateScope.launch {
                                try {
                                    val apk = GithubAppUpdater.downloadAndValidate(context, selectedRelease)
                                    downloadedUpdate = apk
                                    val installerOpened = GithubAppUpdater.launchInstaller(context, apk)
                                    if (installerOpened) {
                                        showUpdateDialog = false
                                    } else {
                                        updateDialogState = "permission"
                                        updateMessage = "The APK is downloaded and verified. Allow Us to install unknown apps in Android settings, then return and tap Install."
                                    }
                                } catch (error: Exception) {
                                    updateDialogState = "error"
                                    updateMessage = AppMessageText.forDisplay(error, "Couldn't download the update. Please try again.")
                                } finally {
                                    updateBusy = false
                                }
                            }
                        }
                    ) { Text("Download & Install") }
                    "permission" -> TextButton(
                        enabled = !updateBusy && downloadedUpdate?.exists() == true,
                        onClick = {
                            runCatching {
                                val installerOpened = GithubAppUpdater.launchInstaller(context, downloadedUpdate!!)
                                if (installerOpened) showUpdateDialog = false
                                else updateMessage = "Allow Us to install unknown apps, then tap Install again."
                            }.onFailure {
                                updateDialogState = "error"
                                updateMessage = AppMessageText.forDisplay(it, "Couldn't open Android's installer.")
                            }
                        }
                    ) { Text("Install") }
                    "checking", "downloading" -> Unit
                    else -> TextButton(onClick = { showUpdateDialog = false }) { Text("OK") }
                }
            },
            dismissButton = {
                if (!updateBusy && updateDialogState == "available") {
                    TextButton(onClick = { showUpdateDialog = false }) { Text("Later") }
                } else if (!updateBusy && updateDialogState == "permission") {
                    TextButton(onClick = { showUpdateDialog = false }) { Text("Close") }
                }
            }
        )
    }

    if (showBackupPassword) {
        AlertDialog(
            onDismissRequest = { showBackupPassword = false },
            title = { Text(if (backupAction == "export") "Encrypt backup" else "Unlock backup") },
            text = {
                OutlinedTextField(
                    value = backupPassword,
                    onValueChange = { backupPassword = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = backupPassword.length >= 10 && !busy,
                    onClick = {
                        showBackupPassword = false
                        if (backupAction == "export") {
                            createBackup.launch("Us-backup-${LocalDate.now()}.usbackup")
                        } else {
                            backupSource?.let { vm.inspectBackup(it, backupPassword) }
                        }
                    }
                ) { Text(if (backupAction == "export") "Choose file" else "Restore") }
            },
            dismissButton = { TextButton(onClick = { showBackupPassword = false }) { Text("Cancel") } }
        )
    }

    backupPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { vm.clearBackupPreview(); backupPassword = "" },
            title = { Text("Review backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This backup belongs to your current shared space and both partner accounts.")
                    Text("To-do and bucket-list records: ${preview.listItems}")
                    Text("Calendar events: ${preview.events}")
                    Text("Love notes: ${preview.notes}")
                    Text("Memories: ${preview.memories} (${preview.photoFiles} photo files)")
                    Text("Activity records: ${preview.activityEntries}")
                    Text("Saved places: ${preview.savedPlaces}")
                    Text("Daily Us days: ${preview.dailyDays} (${preview.dailyAnswers} of your answers, ${preview.dailyCheckins} check-ins)")
                    if (preview.exportedAt > 0L) {
                        Text("Created ${java.text.SimpleDateFormat("MMM d, yyyy · h:mm a", java.util.Locale.getDefault()).format(java.util.Date(preview.exportedAt))}", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text("Existing records will be skipped. This does not merge duplicate IDs.", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearBackupPreview()
                    backupSource?.let { vm.restoreBackup(it, backupPassword) }
                    backupPassword = ""
                }, enabled = !busy) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearBackupPreview(); backupPassword = "" }) { Text("Cancel") }
            }
        )
    }

    if (picking) {
        AnniversaryPicker(ann, { picking = false }) { vm.setAnniversary(it) }
    }

    if (showRecoverySetup) {
        EmailPasswordDialog(
            title = "Set up account recovery",
            description = "",
            actionLabel = "Secure account",
            busy = busy,
            onDismiss = { showRecoverySetup = false },
            onSubmit = { email, password ->
                showRecoverySetup = false
                vm.linkRecoveryEmail(email, password)
            }
        )
    }
}
