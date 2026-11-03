package com.maenalyn.couples

import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private val dateLong = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val dateShort = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
fun CalendarTab(vm: MainVm, events: List<CalendarEvent>, pad: PaddingValues) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    val cs = MaterialTheme.colorScheme
    val days = remember(month) {
        val firstOffset = month.atDay(1).dayOfWeek.value % 7
        val count = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7
        (0 until count).map { index ->
            val day = index - firstOffset + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }
    }
    val selectedEvents = events.filter { it.dateEpochDay == selectedDate.toEpochDay() }
        .sortedWith(compareBy<CalendarEvent> { it.allDay.not() }.thenBy { it.startTime })

    Column(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Our calendar", style = MaterialTheme.typography.titleLarge)
                Text("Plans, little dates, and days to look forward to.", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            FilledIconButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, "Add event")
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.48f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.Filled.ChevronLeft, "Previous month")
                }
                AnimatedContent(
                    targetState = month,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInVertically(tween(200)) { it / 5 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 5 })
                    },
                    modifier = Modifier.weight(1f),
                    label = "calendar month"
                ) { displayedMonth ->
                    Text(
                        displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                TextButton(onClick = { month = YearMonth.now(); selectedDate = LocalDate.now() }) { Text("Today") }
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.Filled.ChevronRight, "Next month")
                }
            }
            Row {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                        color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                days.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        week.forEach { date ->
                            if (date == null) {
                                Box(Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                val selected = date == selectedDate
                                val chipColor by androidx.compose.animation.animateColorAsState(
                                    targetValue = if (selected) cs.primary else Color.Transparent,
                                    animationSpec = tween(180),
                                    label = "selected calendar day"
                                )
                                val hasEvent = events.any { it.dateEpochDay == date.toEpochDay() }
                                Column(
                                    Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                                        .background(chipColor)
                                        .clickable { selectedDate = date }
                                        .padding(top = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        date.dayOfMonth.toString(),
                                        color = if (selected) cs.onPrimary else if (date.month == month.month) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontWeight = if (selected || date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(
                                        when {
                                            hasEvent && selected -> cs.onPrimary
                                            hasEvent -> cs.secondary
                                            else -> androidx.compose.ui.graphics.Color.Transparent
                                        }
                                    ))
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(cs.secondary))
                Text("Shared plans", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selectedDate.format(dateLong), style = MaterialTheme.typography.titleMedium)
                Text(if (selectedEvents.isEmpty()) "A little room for something lovely" else "${selectedEvents.size} plan${if (selectedEvents.size == 1) "" else "s"}",
                    color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            TextButton(onClick = { editing = null; showEditor = true }) { Text("Add plan") }
        }
        if (selectedEvents.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.35f)).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Event, null, tint = cs.primary, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(8.dp))
                Text("Nothing planned for this day", fontWeight = FontWeight.SemiBold)
                Text("Add a date, a reminder, or something to look forward to.",
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 12.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(selectedEvents, key = { it.id }) { event ->
                    EventRow(
                        event = event,
                        onEdit = { editing = event; showEditor = true },
                        onDelete = { vm.deleteEvent(event.id) }
                    )
                }
            }
        }
    }

    if (showEditor) {
        CalendarEventEditor(
            initialDate = selectedDate,
            event = editing,
            onDismiss = { showEditor = false },
            onSave = { draft ->
                if (editing == null) vm.addEvent(draft) else vm.updateEvent(editing!!.id, draft)
                selectedDate = LocalDate.ofEpochDay(draft.dateEpochDay)
                month = YearMonth.from(selectedDate)
                showEditor = false
            }
        )
    }
}

@Composable
private fun EventRow(event: CalendarEvent, onEdit: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).animateContentSize()
            .background(cs.surfaceVariant.copy(alpha = 0.5f)).clickable(onClick = onEdit).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(48.dp).clip(CircleShape).background(cs.primary))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(event.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (event.allDay) "All day" else listOf(event.startTime, event.endTime).filter { it.isNotBlank() }.joinToString(" – "),
                color = cs.onSurfaceVariant, fontSize = 12.sp
            )
            if (event.description.isNotBlank()) Text(event.description, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Edit, "Edit event", tint = cs.onSurfaceVariant) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEventEditor(
    initialDate: LocalDate,
    event: CalendarEvent?,
    onDismiss: () -> Unit,
    onSave: (CalendarEventDraft) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var title by remember(event?.id) { mutableStateOf(event?.title ?: "") }
    var description by remember(event?.id) { mutableStateOf(event?.description ?: "") }
    var date by remember(event?.id) { mutableStateOf(event?.dateEpochDay?.let { LocalDate.ofEpochDay(it) } ?: initialDate) }
    var allDay by remember(event?.id) { mutableStateOf(event?.allDay ?: true) }
    var startTime by remember(event?.id) { mutableStateOf(event?.startTime ?: "18:00") }
    var endTime by remember(event?.id) { mutableStateOf(event?.endTime ?: "") }
    var reminder by remember(event?.id) { mutableIntStateOf(event?.reminderMinutes ?: 0) }
    var showDatePicker by remember { mutableStateOf(false) }
    var expandedReminder by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "Add a plan" else "Edit plan") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(500) }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(date.format(dateShort))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All day", Modifier.weight(1f))
                    Switch(allDay, { allDay = it })
                }
                if (!allDay) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(startTime, { startTime = it.take(5) }, label = { Text("Start · HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(endTime, { endTime = it.take(5) }, label = { Text("End · HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Text("Use 24-hour time, for example 18:30.", color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
                ExposedDropdownMenuBox(expanded = expandedReminder, onExpandedChange = { expandedReminder = it }) {
                    OutlinedTextField(
                        value = when (reminder) { 0 -> "No reminder"; 10 -> "10 minutes before"; 60 -> "1 hour before"; 1440 -> "1 day before"; else -> "No reminder" },
                        onValueChange = {}, readOnly = true, label = { Text("Reminder") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedReminder) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedReminder, onDismissRequest = { expandedReminder = false }) {
                        listOf(0 to "No reminder", 10 to "10 minutes before", 60 to "1 hour before", 1440 to "1 day before").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { reminder = value; expandedReminder = false })
                        }
                    }
                }
                validation?.let { Text(it, color = cs.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalizedStart = if (allDay) "" else startTime.trim()
                val normalizedEnd = if (allDay) "" else endTime.trim()
                if (title.isBlank()) validation = "Give your plan a title."
                else if (!allDay && runCatching { LocalTime.parse(normalizedStart) }.isFailure) validation = "Enter a valid start time, such as 18:30."
                else if (!allDay && normalizedEnd.isNotBlank() && runCatching { LocalTime.parse(normalizedEnd) }.isFailure) validation = "Enter a valid end time, such as 20:00."
                else if (!allDay && normalizedEnd.isNotBlank() && LocalTime.parse(normalizedEnd) <= LocalTime.parse(normalizedStart)) validation = "End time must be after the start time."
                else onSave(CalendarEventDraft(title, description, date.toEpochDay(), allDay, normalizedStart, normalizedEnd, reminder))
            }) { Text("Save plan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showDatePicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { date = LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L)) }; showDatePicker = false }) { Text("Select") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = picker) }
    }
}

@Composable
fun NotesTab(vm: MainVm, notes: List<LoveNote>, pad: PaddingValues) {
    var showEditor by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Love notes", style = MaterialTheme.typography.titleLarge)
                Text("Little words worth keeping.", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            FilledIconButton(onClick = { showEditor = true }) { Icon(Icons.Filled.Add, "Write a note") }
        }
        if (notes.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.FavoriteBorder, null, tint = cs.primary, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your little love-note corner", style = MaterialTheme.typography.titleMedium)
                Text("Leave a message, a thank-you, or a note for later.", color = cs.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { showEditor = true }) { Text("Write your first note") }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onPin = { vm.pinNote(note, !note.pinned) },
                        onDelete = { vm.deleteNote(note) }
                    )
                }
            }
        }
    }
    if (showEditor) {
        var title by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("Write a little note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it.take(80) }, label = { Text("A title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it.take(2000) }, label = { Text("Your note") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.addNote(title, text); showEditor = false }, enabled = text.isNotBlank()) {
                Icon(Icons.Filled.Send, null); Spacer(Modifier.width(5.dp)); Text("Save note")
            } },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NoteCard(note: LoveNote, onPin: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).animateContentSize()
            .background(if (note.pinned) cs.secondaryContainer.copy(alpha = 0.75f) else cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FavoriteBorder, null, tint = cs.secondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (note.title.isBlank()) "A note for us" else note.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onPin) { Icon(if (note.pinned) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (note.pinned) "Unpin" else "Pin", tint = cs.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete note", tint = cs.onSurfaceVariant) }
        }
        Text(note.text, style = MaterialTheme.typography.bodyLarge)
        Text(
            java.text.SimpleDateFormat("MMM d · h:mm a", java.util.Locale.getDefault()).format(java.util.Date(note.createdAt)),
            color = cs.onSurfaceVariant, fontSize = 11.sp
        )
    }
}

private data class MemoryAlbumEntry(
    val id: String,
    val title: String,
    val photos: List<Memory>
)

@Composable
private fun MemoryAlbumCover(
    memory: Memory,
    vm: MainVm,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localFull = File(File(context.filesDir, "memories"), memory.fileName)
    val localThumb = File(File(context.filesDir, "memory_thumbnails"), "${memory.id}.jpg")
    var thumbnail by remember(memory.id) {
        mutableStateOf(localThumb.takeIf { it.exists() } ?: localFull.takeIf { it.exists() })
    }
    var loading by remember(memory.id) { mutableStateOf(false) }

    LaunchedEffect(memory.id, memory.status) {
        if (thumbnail == null && !loading && memory.status == "ready" && memory.storageMode == "firestore_blob") {
            loading = true
            thumbnail = vm.loadMemoryThumbnail(memory)
            loading = false
        }
    }

    val imageModel: Any? = thumbnail?.takeIf { it.exists() }
        ?: memory.photoUrl.takeIf { it.isNotBlank() }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = memory.caption.ifBlank { "Album cover" },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(38.dp))
            }
        }
    }
}

@Composable
private fun MemoryAlbumCard(
    album: MemoryAlbumEntry,
    cover: Memory,
    vm: MainVm,
    onOpen: () -> Unit,
    onChangeCover: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(22.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.48f))
            .clickable(onClick = onOpen)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.88f)) {
            MemoryAlbumCover(cover, vm, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(74.dp)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.66f))))
            )
            Text(
                "${album.photos.size} ${if (album.photos.size == 1) "photo" else "photos"}",
                Modifier.align(Alignment.BottomStart).padding(12.dp),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            IconButton(
                onClick = onChangeCover,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape).size(36.dp)
            ) {
                Icon(Icons.Filled.Edit, "Change album cover", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            album.title,
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MemoryPhotoTile(
    memory: Memory,
    vm: MainVm,
    onClick: () -> Unit
) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) {
            MemoryAlbumCover(memory, vm, Modifier.fillMaxSize())
            if (memory.favorite) {
                Icon(
                    Icons.Filled.Star,
                    "Favorite",
                    tint = Color(0xFFFFD166),
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(18.dp)
                )
            }
        }
        Text(
            memory.caption.ifBlank { "A moment together" },
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MemoriesTab(vm: MainVm, memories: List<Memory>, pad: PaddingValues) {
    val context = LocalContext.current
    val albumCovers by vm.memoryAlbumCovers.collectAsState()
    var captionUri by remember { mutableStateOf<Uri?>(null) }
    var search by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Memory?>(null) }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var albumVisible by remember { mutableStateOf(false) }
    var coverChooserAlbumId by remember { mutableStateOf<String?>(null) }
    var viewerAlbumId by remember { mutableStateOf<String?>(null) }
    var photoIndex by remember { mutableIntStateOf(-1) }
    var photoViewerVisible by remember { mutableStateOf(false) }
    var photoForSave by remember { mutableStateOf<Memory?>(null) }
    val hasMore by vm.hasMoreMemories.collectAsState()
    val loadingMore by vm.loadingMoreMemories.collectAsState()
    val gridState = rememberLazyGridState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> captionUri = uri }
    val savePhoto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) photoForSave?.let { vm.saveMemoryTo(uri, it) }
        photoForSave = null
    }
    val cs = MaterialTheme.colorScheme

    val filtered = remember(memories, search, favoritesOnly) {
        memories.filter { memory ->
            (!favoritesOnly || memory.favorite) &&
                (search.isBlank() || memory.caption.contains(search.trim(), ignoreCase = true))
        }
    }
    val allAlbums = remember(memories) {
        memories.groupBy { memory ->
            java.time.YearMonth.from(
                java.time.Instant.ofEpochMilli(memory.createdAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            )
        }.toSortedMap(compareByDescending { it }).map { (month, photos) ->
            MemoryAlbumEntry(
                id = month.toString(),
                title = month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                photos = photos.sortedByDescending { it.createdAt }
            )
        }
    }
    val visibleAlbums = remember(filtered) {
        filtered.groupBy { memory ->
            java.time.YearMonth.from(
                java.time.Instant.ofEpochMilli(memory.createdAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            )
        }.toSortedMap(compareByDescending { it }).map { (month, photos) ->
            MemoryAlbumEntry(
                id = month.toString(),
                title = month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                photos = photos.sortedByDescending { it.createdAt }
            )
        }
    }
    val activeAlbum = allAlbums.firstOrNull { it.id == selectedAlbumId }
    val coverAlbum = allAlbums.firstOrNull { it.id == coverChooserAlbumId }
    val viewerAlbum = allAlbums.firstOrNull { it.id == viewerAlbumId }
    val viewerPhotos = viewerAlbum?.photos.orEmpty()
    val currentPhoto = viewerPhotos.getOrNull(photoIndex)

    val backgroundBlur by animateDpAsState(
        targetValue = if (albumVisible || coverChooserAlbumId != null || photoViewerVisible) 12.dp else 0.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "memory background blur"
    )

    LaunchedEffect(albumVisible, selectedAlbumId) {
        if (!albumVisible && selectedAlbumId != null) {
            delay(280)
            selectedAlbumId = null
        }
    }
    LaunchedEffect(photoViewerVisible, photoIndex) {
        if (!photoViewerVisible && photoIndex >= 0) {
            delay(260)
            photoIndex = -1
            viewerAlbumId = null
        }
    }

    Column(Modifier.fillMaxSize().blur(backgroundBlur).padding(pad).padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Our memories", style = MaterialTheme.typography.titleLarge)
                val knownBytes = memories.sumOf { it.sizeBytes }
                val mb = knownBytes / (1024f * 1024f)
                Text("${"%.1f".format(mb)} MB · ${memories.size} photos loaded", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
            FilledIconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.CameraAlt, "Add a photo")
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it.take(80) },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search captions") },
            shape = RoundedCornerShape(18.dp)
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !favoritesOnly, onClick = { favoritesOnly = false }, label = { Text("All albums") })
            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = true }, label = { Text("Favorite photos") }, leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) })
        }

        if (visibleAlbums.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Image, null, tint = cs.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (memories.isEmpty()) "Your story in snapshots" else "No memories found", style = MaterialTheme.typography.titleMedium)
                if (memories.isEmpty()) {
                    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Add your first memory") }
                } else if (hasMore) {
                    OutlinedButton(onClick = { vm.loadMoreMemories() }, enabled = !loadingMore) {
                        Text(if (loadingMore) "Loading older memories…" else "Search older memories")
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(visibleAlbums, key = { it.id }) { album ->
                    val fullAlbum = allAlbums.firstOrNull { it.id == album.id } ?: album
                    val selectedCoverId = albumCovers[album.id]
                    val cover = fullAlbum.photos.firstOrNull { it.id == selectedCoverId } ?: fullAlbum.photos.first()
                    MemoryAlbumCard(
                        album = album,
                        cover = cover,
                        vm = vm,
                        onOpen = { selectedAlbumId = album.id; albumVisible = true },
                        onChangeCover = { coverChooserAlbumId = album.id }
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (hasMore) {
                        OutlinedButton(
                            onClick = { vm.loadMoreMemories() },
                            enabled = !loadingMore,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            if (loadingMore) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Load older memories")
                        }
                    }
                }
            }
        }
    }

    if (selectedAlbumId != null && activeAlbum != null) {
        Dialog(
            onDismissRequest = { albumVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogView = LocalView.current
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                dialogWindow?.setDimAmount(0f)
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            AnimatedVisibility(
                visible = albumVisible,
                enter = fadeIn(tween(240, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.90f, animationSpec = tween(320, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(220, easing = FastOutSlowInEasing)) + scaleOut(targetScale = 0.94f, animationSpec = tween(240, easing = FastOutSlowInEasing))
            ) {
                Column(
                    Modifier.fillMaxWidth().fillMaxHeight(0.90f).padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(28.dp)).background(cs.surface),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { albumVisible = false }) {
                            Icon(Icons.Filled.ChevronLeft, "Close album")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(activeAlbum.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("${activeAlbum.photos.size} photos", color = cs.onSurfaceVariant, fontSize = 12.sp)
                        }
                        IconButton(onClick = { coverChooserAlbumId = activeAlbum.id }) {
                            Icon(Icons.Filled.Edit, "Change album cover")
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        gridItems(activeAlbum.photos, key = { it.id }) { memory ->
                            MemoryPhotoTile(memory, vm) {
                                if (memory.status == "ready" || memory.photoUrl.isNotBlank()) {
                                    viewerAlbumId = activeAlbum.id
                                    photoIndex = activeAlbum.photos.indexOfFirst { it.id == memory.id }
                                    photoViewerVisible = true
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            if (hasMore) {
                                OutlinedButton(
                                    onClick = { vm.loadMoreMemories() },
                                    enabled = !loadingMore,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                ) {
                                    if (loadingMore) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text("Load older photos")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (coverChooserAlbumId != null && coverAlbum != null) {
        Dialog(
            onDismissRequest = { coverChooserAlbumId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(26.dp)).background(cs.surface).padding(16.dp)
            ) {
                Text("Choose album cover", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Select any photo in ${coverAlbum.title}.", color = cs.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    gridItems(coverAlbum.photos, key = { it.id }) { memory ->
                        MemoryPhotoTile(memory, vm) {
                            vm.setMemoryAlbumCover(coverAlbum.id, memory)
                            coverChooserAlbumId = null
                        }
                    }
                }
                TextButton(onClick = { coverChooserAlbumId = null }, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }

    if (photoIndex >= 0 && currentPhoto != null && viewerAlbum != null) {
        Dialog(
            onDismissRequest = { photoViewerVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogView = LocalView.current
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                dialogWindow?.setDimAmount(0f)
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            AnimatedVisibility(
                visible = photoViewerVisible,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)),
                exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.97f, animationSpec = tween(180))
            ) {
                val memory = currentPhoto
                val localImage = File(File(context.filesDir, "memories"), memory.fileName)
                var fullImage by remember(memory.id) { mutableStateOf(localImage.takeIf { it.exists() }) }
                var fullLoading by remember(memory.id) { mutableStateOf(false) }
                LaunchedEffect(memory.id) {
                    if (fullImage == null && memory.storageMode == "firestore_blob" && memory.status == "ready") {
                        fullLoading = true
                        fullImage = vm.loadMemoryImage(memory)
                        fullLoading = false
                    }
                }
                Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.97f))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { photoViewerVisible = false }) {
                            Icon(Icons.Filled.Close, "Close photo", tint = Color.White)
                        }
                        Text(
                            "${photoIndex + 1} / ${viewerPhotos.size}",
                            Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                        IconButton(onClick = { vm.toggleMemoryFavorite(memory) }) {
                            Icon(Icons.Filled.Star, "Toggle favorite", tint = if (memory.favorite) Color(0xFFFFD166) else Color.White)
                        }
                        IconButton(onClick = { photoForSave = memory; savePhoto.launch(memory.fileName) }) {
                            Icon(Icons.Filled.Download, "Save photo", tint = Color.White)
                        }
                        IconButton(onClick = { photoViewerVisible = false; deleteTarget = memory }) {
                            Icon(Icons.Filled.Delete, "Delete memory", tint = Color.White)
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        val imageModel: Any? = fullImage?.takeIf { it.exists() }
                            ?: memory.photoUrl.takeIf { it.isNotBlank() }
                        if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = memory.caption,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else if (fullLoading) CircularProgressIndicator(color = Color.White)
                        else Icon(Icons.Filled.Image, null, tint = Color.White.copy(alpha = 0.65f), modifier = Modifier.size(54.dp))
                        if (photoIndex > 0) {
                            IconButton(
                                onClick = { photoIndex = (photoIndex - 1).coerceAtLeast(0) },
                                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).background(Color.Black.copy(alpha = 0.42f), CircleShape)
                            ) { Icon(Icons.Filled.ChevronLeft, "Previous photo", tint = Color.White) }
                        }
                        if (photoIndex < viewerPhotos.lastIndex) {
                            IconButton(
                                onClick = { photoIndex = (photoIndex + 1).coerceAtMost(viewerPhotos.lastIndex) },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.42f), CircleShape)
                            ) { Icon(Icons.Filled.ChevronRight, "Next photo", tint = Color.White) }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(memory.caption.ifBlank { "A moment together" }, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(memory.createdAt)),
                            color = Color.White.copy(alpha = 0.70f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this memory?") },
            text = { Text("This removes the photo for both of you.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteMemory(memory); deleteTarget = null }) { Text("Delete", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    captionUri?.let { uri ->
        var caption by remember(uri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { captionUri = null },
            title = { Text("Add a caption") },
            text = { OutlinedTextField(caption, { caption = it.take(280) }, label = { Text("What do you want to remember?") }, modifier = Modifier.fillMaxWidth(), maxLines = 3) },
            confirmButton = { TextButton(onClick = { vm.addMemory(uri, caption); captionUri = null }) { Text("Save memory") } },
            dismissButton = { TextButton(onClick = { captionUri = null }) { Text("Cancel") } }
        )
    }
}


@Composable
fun ListsHubTab(all: List<Item>, vm: MainVm, pad: PaddingValues) {
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected == 0, { selected = 0 }, label = { Text("To-do") }, leadingIcon = { Icon(Icons.Filled.Event, null, Modifier.size(16.dp)) })
            FilterChip(selected == 1, { selected = 1 }, label = { Text("Bucket list") }, leadingIcon = { Icon(Icons.Filled.FavoriteBorder, null, Modifier.size(16.dp)) })
        }
        if (selected == 0) ListTab("todo", "To-do", "Add a to-do…", "✨", "Nothing to do yet. Add your first one together.", all, vm, PaddingValues(0.dp))
        else ListTab("bucket", "Bucket list", "Add a dream…", "🌙", "Dream big. Add your first bucket list item.", all, vm, PaddingValues(0.dp))
    }
}

@Composable
fun DailyUsSheetContent(
    state: DailyUsState,
    saving: Boolean,
    onSaveAnswer: (String) -> Unit,
    onSaveCheckin: (String, String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var answer by remember(state.dateKey) { mutableStateOf(state.myAnswer) }
    var mood by remember(state.dateKey) { mutableStateOf(state.myMood ?: "Loved") }
    var note by remember(state.dateKey) { mutableStateOf(state.myCheckinNote) }

    LaunchedEffect(state.myAnswer) {
        if (state.myAnswer.isNotBlank()) answer = state.myAnswer
    }
    LaunchedEffect(state.myMood, state.myCheckinNote) {
        if (state.myMood != null) mood = state.myMood
        note = state.myCheckinNote
    }

    Column(
        Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DAILY US", color = cs.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Text("A little question for the two of you", style = MaterialTheme.typography.titleLarge)
            Text(
                runCatching { LocalDate.parse(state.dateKey).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) }.getOrDefault("Today"),
                color = cs.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(cs.primaryContainer.copy(alpha = 0.72f)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("TODAY'S PROMPT", color = cs.onPrimaryContainer.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(state.question.ifBlank { "What made you feel loved today?" }, style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it.take(1000) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                label = { Text("Your answer") },
                placeholder = { Text("Write something from your heart…") }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${answer.length}/1000", color = cs.onPrimaryContainer.copy(alpha = 0.65f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Button(onClick = { onSaveAnswer(answer) }, enabled = answer.isNotBlank() && !saving) {
                    Text(if (state.hasAnswered) "Update answer" else "Save answer")
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.55f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Your person's answer", style = MaterialTheme.typography.titleMedium)
            when {
                !state.hasAnswered -> Text("Your answer stays private until you submit yours. Then you can read each other's responses.", color = cs.onSurfaceVariant, fontSize = 13.sp)
                state.partnerAnswer.isNullOrBlank() -> Text("Your answer is saved. Your person's response will appear here when they answer too.", color = cs.onSurfaceVariant, fontSize = 13.sp)
                else -> Text(state.partnerAnswer.orEmpty(), color = cs.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
            if (state.bothAnswered) {
                Text("You both answered today's question 💗", color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Daily check-in", style = MaterialTheme.typography.titleMedium)
            Text("How are you feeling today?", color = cs.onSurfaceVariant, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                DailyUsRepo.moods.forEach { option ->
                    FilterChip(
                        selected = mood == option,
                        onClick = { mood = option },
                        label = { Text(option) }
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(240) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Anything your person should know? (optional)") },
                supportingText = { Text("${note.length}/240") }
            )
            Button(
                onClick = { onSaveCheckin(mood, note) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.myMood == null) "Save check-in" else "Update check-in") }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(cs.secondaryContainer.copy(alpha = 0.55f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("Your person's check-in", style = MaterialTheme.typography.titleMedium)
            if (state.partnerMood == null) {
                Text("Their check-in will show here when they share it.", color = cs.onSurfaceVariant, fontSize = 13.sp)
            } else {
                Text(state.partnerMood.orEmpty(), color = cs.onSecondaryContainer, fontWeight = FontWeight.Bold)
                if (state.partnerCheckinNote.isNotBlank()) {
                    Text(state.partnerCheckinNote, color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.bothCheckedIn) {
                Text("You both checked in today 💞", color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ActivityCenterTab(
    entries: List<ActivityEntry>,
    memberNames: Map<String, String>,
    currentUid: String?,
    pad: PaddingValues,
    onOpenTarget: (String, String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Lists", "Plans", "Notes", "Memories", "Daily Us")
    fun category(type: String): String = when (type) {
        "todo_created", "bucket_created", "todo_completed" -> "Lists"
        "event_created", "event_updated" -> "Plans"
        "note_created" -> "Notes"
        "memory_created" -> "Memories"
        "daily_answered", "daily_checkin" -> "Daily Us"
        else -> "Other"
    }
    val visible = remember(entries, filter) {
        entries.filter { filter == "All" || category(it.type) == filter }
    }

    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("A little history of us", style = MaterialTheme.typography.titleLarge)
            Text("Shared moments and updates from both of you.", color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            filters.forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
            }
        }
        if (visible.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.45f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("💗", fontSize = 34.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (entries.isEmpty()) "Your story will show up here" else "No updates in this category yet", fontWeight = FontWeight.SemiBold)
                Text("When either of you adds something, it will appear here.", color = cs.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(visible, key = { it.id }) { entry ->
                    val who = memberNames[entry.actorUid]?.takeIf { it.isNotBlank() }
                        ?: if (entry.actorUid == currentUid) "You" else "Your person"
                    val icon = when (category(entry.type)) {
                        "Lists" -> "✓"
                        "Plans" -> "♡"
                        "Notes" -> "✉"
                        "Memories" -> "▧"
                        "Daily Us" -> "♥"
                        else -> "•"
                    }
                    val timestamp = remember(entry.createdAt) {
                        runCatching {
                            val at = java.time.Instant.ofEpochMilli(entry.createdAt).atZone(java.time.ZoneId.systemDefault())
                            val today = LocalDate.now()
                            val day = at.toLocalDate()
                            val dateLabel = when (day) {
                                today -> "Today"
                                today.minusDays(1) -> "Yesterday"
                                else -> day.format(DateTimeFormatter.ofPattern("MMM d"))
                            }
                            "$dateLabel · ${at.format(DateTimeFormatter.ofPattern("h:mm a"))}"
                        }.getOrDefault("")
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(cs.surfaceVariant.copy(alpha = 0.45f))
                            .clickable { if (entry.targetCollection.isNotBlank()) onOpenTarget(entry.targetCollection, entry.targetId) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) { Text(icon, color = cs.onPrimaryContainer, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("$who · ${entry.title}", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            if (entry.summary.isNotBlank()) Text(entry.summary, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(timestamp, color = cs.onSurfaceVariant.copy(alpha = 0.8f), fontSize = 10.sp)
                        }
                        Text("›", color = cs.primary, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}
