package com.maenalyn.couples

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.firestore.DocumentSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun OurInstantGalleryScreen(
    code: String,
    uid: String,
    pad: PaddingValues,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val loadMutex = remember(code, uid) { Mutex() }
    val background = Color(0xFF09090B)
    val foreground = Color.White
    val muted = Color(0xFF9A9AA2)

    var members by remember(code, uid) { mutableStateOf<LivePhotoGalleryMembers?>(null) }
    var memberError by remember(code, uid) { mutableStateOf<String?>(null) }
    var selectedOwnerUid by remember(code, uid) { mutableStateOf(uid) }
    var entries by remember(code, uid) { mutableStateOf<List<LivePhotoGalleryEntry>>(emptyList()) }
    var nextCursor by remember(code, uid) { mutableStateOf<DocumentSnapshot?>(null) }
    var hasMore by remember(code, uid) { mutableStateOf(false) }
    var loading by remember(code, uid) { mutableStateOf(false) }
    var galleryError by remember(code, uid) { mutableStateOf<String?>(null) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    val partnerLabel = when (members?.partnerGender) {
        "female" -> "She"
        "male" -> "He"
        else -> "Partner"
    }

    LaunchedEffect(code, uid) {
        runCatching { LivePhotoWidgetRepo.loadGalleryMembers(code, uid) }
            .onSuccess {
                members = it
                memberError = null
            }
            .onFailure {
                memberError = it.message ?: "Couldn't load your shared gallery."
            }
    }

    suspend fun loadNextPage(reset: Boolean) = loadMutex.withLock {
        loading = true
        galleryError = null
        try {
            var cursor = if (reset) null else nextCursor
            val found = mutableListOf<LivePhotoGalleryEntry>()
            var scannedPages = 0
            do {
                val page = LivePhotoWidgetRepo.loadGalleryPage(
                    code = code,
                    ownerUid = selectedOwnerUid,
                    pageSize = 30,
                    after = cursor
                )
                cursor = page.nextCursor
                found += page.entries
                scannedPages++
            } while (found.isEmpty() && cursor != null && scannedPages < 10)

            entries = if (reset) found else entries + found
            nextCursor = cursor
            hasMore = cursor != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            galleryError = error.message ?: "Couldn't load these photos. Check your connection."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(code, selectedOwnerUid, members?.partnerUid) {
        if (members != null) {
            entries = emptyList()
            nextCursor = null
            hasMore = false
            loadNextPage(reset = true)
        }
    }

    val groupedEntries = remember(entries) {
        entries.groupBy { entry ->
            Instant.ofEpochMilli(entry.createdAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }.toSortedMap(compareByDescending<LocalDate> { it })
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier.fillMaxSize().background(background).padding(pad)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to camera", tint = foreground)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("INSTANT GALLERY", color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("${entries.size} photos", color = muted, fontSize = 11.sp)
            }
        }

        if (members != null) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF17171B)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GalleryOwnerTab(
                    label = "I",
                    selected = selectedOwnerUid == uid,
                    modifier = Modifier.weight(1f),
                    onClick = { if (selectedOwnerUid != uid) selectedOwnerUid = uid }
                )
                GalleryOwnerTab(
                    label = partnerLabel,
                    selected = selectedOwnerUid == members!!.partnerUid,
                    modifier = Modifier.weight(1f),
                    onClick = { if (selectedOwnerUid != members!!.partnerUid) selectedOwnerUid = members!!.partnerUid }
                )
            }
        }

        when {
            memberError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(memberError.orEmpty(), color = foreground, textAlign = TextAlign.Center)
            }
            members == null || (loading && entries.isEmpty()) -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = foreground) }
            entries.isEmpty() && !loading -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (selectedOwnerUid == uid) "No captures yet" else "No captures from $partnerLabel yet", color = foreground, fontWeight = FontWeight.SemiBold)
                    Text("New Our Instant photos will appear here.", color = muted, fontSize = 12.sp)
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    groupedEntries.forEach { (date, dayEntries) ->
                        item(key = "date-$date", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())),
                                color = foreground,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 8.dp)
                            )
                        }
                        items(dayEntries, key = { it.id }) { entry ->
                            GalleryGridPhoto(
                                code = code,
                                entry = entry,
                                onClick = { viewerIndex = entries.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 } }
                            )
                        }
                    }
                }
            }
        }

        if (galleryError != null) {
            Text(galleryError.orEmpty(), color = Color(0xFFFF9A9A), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (hasMore) {
            TextButton(
                onClick = { scope.launch { loadNextPage(reset = false) } },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = foreground, strokeWidth = 2.dp)
                else Text("Load older photos", color = foreground)
            }
        }
    }

    val index = viewerIndex
    if (index != null && index in entries.indices) {
        OurInstantPhotoViewer(
            code = code,
            entries = entries,
            initialIndex = index,
            ownerLabel = if (selectedOwnerUid == uid) "I" else partnerLabel,
            onDismiss = { viewerIndex = null }
        )
    }

}

@Composable
private fun GalleryOwnerTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        color = if (selected) Color.White else Color.Transparent,
        shape = RoundedCornerShape(14.dp)
    ) {
        Box(Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) Color(0xFF111114) else Color(0xFFE5E5E7), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GalleryGridPhoto(code: String, entry: LivePhotoGalleryEntry, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, code, entry.id) {
        val bytes = runCatching {
            LivePhotoWidgetRepo.loadGalleryImageBytes(code, entry.id, preferThumbnail = true)
        }.getOrNull()
        value = if (bytes == null) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF202024)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Our Instant photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OurInstantPhotoViewer(
    code: String,
    entries: List<LivePhotoGalleryEntry>,
    initialIndex: Int,
    ownerLabel: String,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(entries.indices)) {
        entries.size
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val entry = entries[page]
                val bitmap by produceState<Bitmap?>(null, code, entry.id) {
                    val bytes = runCatching {
                        LivePhotoWidgetRepo.loadGalleryImageBytes(code, entry.id, preferThumbnail = false)
                    }.getOrNull()
                    value = if (bytes == null) null else withContext(Dispatchers.IO) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                DisposableEffect(bitmap) {
                    onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
                }
                Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 76.dp), contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(bitmap!!.asImageBitmap(), contentDescription = "Full-screen Our Instant photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close photo viewer", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(ownerLabel, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        activeEntry?.let {
                            DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())
                                .format(Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()))
                        }.orEmpty(),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                "${pagerState.currentPage + 1} / ${entries.size}",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
            )
        }
    }
}
