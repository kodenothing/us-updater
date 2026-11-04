package com.maenalyn.couples

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun OurInstantCameraScreen(code: String, uid: String, pad: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dark = Color(0xFF09090B)
    val white = Color.White

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }
    var permissionFeedback by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var current by remember(code) { mutableStateOf(LivePhotoWidgetState()) }
    var working by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionPermanentlyDenied = !granted &&
            (context as? Activity)?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } == true
        permissionFeedback = if (granted) null else
            if (permissionPermanentlyDenied) {
                "Camera access is blocked. Use the button below to open Us app permissions."
            } else {
                "Allow camera access to take and share an instant."
            }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(code) {
        LivePhotoWidgetRepo.observeCurrent(code).collect { current = it }
    }

    var sharedPhoto by remember(code) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(code, current.activePhotoId) {
        sharedPhoto = null
        if (current.activePhotoId.isNotBlank()) {
            sharedPhoto = runCatching {
                LivePhotoWidgetRepo.loadThumbnail(context, code, current.activePhotoId)
            }.getOrNull()
        }
    }

    fun requestCameraAccess() {
        if (permissionPermanentlyDenied) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { context.startActivity(intent) }
                .onFailure { permissionFeedback = "Couldn't open app permissions." }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun capturePhoto() {
        if (working) return
        if (!hasCameraPermission) {
            requestCameraAccess()
            return
        }
        val capture = imageCapture
        if (capture == null) {
            feedback = cameraError ?: "Camera is still starting. Please try again."
            return
        }
        if (uid.isBlank()) {
            feedback = "Your account isn't ready. Please try again."
            return
        }

        val photoFile = File(context.cacheDir, "our-instant-${UUID.randomUUID()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        working = true
        feedback = null
        try {
            capture.targetRotation = context.display?.rotation ?: capture.targetRotation
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val photoUri = runCatching {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                photoFile
                            )
                        }.getOrElse { error ->
                            photoFile.delete()
                            working = false
                            feedback = error.message ?: "Couldn't prepare the captured photo."
                            return
                        }

                        scope.launch {
                            try {
                                LivePhotoWidgetUploadScheduler.enqueueFromUri(
                                    context, code, uid, photoUri
                                )
                                feedback = "Captured! Sharing with your person…"
                            } catch (error: Exception) {
                                photoFile.delete()
                                feedback = error.message ?: "Couldn't prepare this photo. Please try again."
                            } finally {
                                working = false
                            }
                        }
                    }

                    override fun onError(error: ImageCaptureException) {
                        photoFile.delete()
                        working = false
                        feedback = error.message ?: "Couldn't capture the photo. Please try again."
                    }
                }
            )
        } catch (error: Exception) {
            photoFile.delete()
            working = false
            feedback = error.message ?: "Couldn't start the camera capture."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(pad).background(dark).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "OUR INSTANT",
                    color = white,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    "A little moment, shared with your person.",
                    color = white.copy(alpha = 0.68f),
                    fontSize = 11.sp
                )
            }
            if (current.activePhotoId.isNotBlank()) {
                IconButton(onClick = { confirmRemove = true }, enabled = !working) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove shared photo", tint = white)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF17171B)),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                OurInstantCameraPreview(
                    onImageCaptureReady = { imageCapture = it },
                    onCameraError = { cameraError = it }
                )
                if (cameraError != null && imageCapture == null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (sharedPhoto != null) {
                            androidx.compose.foundation.Image(
                                bitmap = sharedPhoto!!.asImageBitmap(),
                                contentDescription = "Current shared photo",
                                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                        Text(cameraError.orEmpty(), color = white, textAlign = TextAlign.Center)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (sharedPhoto != null) {
                        androidx.compose.foundation.Image(
                            bitmap = sharedPhoto!!.asImageBitmap(),
                            contentDescription = "Current shared photo",
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = white.copy(alpha = 0.8f), modifier = Modifier.size(44.dp))
                    }
                    Text(
                        permissionFeedback ?: "Camera access is needed to take your next shared instant.",
                        color = white.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                    if (permissionPermanentlyDenied) {
                        TextButton(onClick = { requestCameraAccess() }) {
                            Text("Open app permissions", color = white)
                        }
                    }
                }
            }

            if (working) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = white)
                        Text("Sharing your instant…", color = white, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(104.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .border(4.dp, white, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(white)
                    .clickable(enabled = !working) { capturePhoto() },
                contentAlignment = Alignment.Center
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = if (hasCameraPermission) "Take a photo" else "Allow camera access",
                        tint = Color.Black,
                        modifier = Modifier.size(31.dp)
                    )
                }
            }
        }

        feedback?.let {
            Text(
                it,
                color = white.copy(alpha = 0.82f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the shared Our Instant photo?") },
            text = {
                Text("This permanently removes the active Our Instant photo and its Firestore image documents. Both home-screen widgets will return to the empty state.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        scope.launch {
                            working = true
                            try {
                                LivePhotoWidgetRepo.removeCurrentPhoto(context, code, uid)
                                feedback = "Our Instant photo removed."
                            } catch (error: Exception) {
                                feedback = error.message ?: "Couldn't remove the shared photo."
                            } finally {
                                working = false
                            }
                        }
                    }
                ) { Text("Remove photo") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun OurInstantCameraPreview(
    onImageCaptureReady: (ImageCapture?) -> Unit,
    onCameraError: (String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val providerFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    val cameraProvider by produceState<ProcessCameraProvider?>(initialValue = null, providerFuture) {
        providerFuture.addListener(
            {
                try {
                    value = providerFuture.get()
                } catch (error: Exception) {
                    onCameraError(error.message ?: "Couldn't initialize the camera.")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(cameraProvider, lifecycleOwner, previewView) {
        val provider = cameraProvider
        if (provider == null) {
            onImageCaptureReady(null)
            onDispose { }
        } else {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                onCameraError(null)
                onImageCaptureReady(capture)
            } catch (error: Exception) {
                onImageCaptureReady(null)
                onCameraError(error.message ?: "Couldn't start the camera on this device.")
            }
            onDispose {
                runCatching { provider.unbind(preview, capture) }
                onImageCaptureReady(null)
            }
        }
    }
}
