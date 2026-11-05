package com.maenalyn.couples.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun UsChatScreen(
    coupleCode: String,
    currentUid: String,
    partnerName: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current.applicationContext
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var roomId by remember(coupleCode, currentUid) { mutableStateOf<String?>(null) }
    var messages by remember(coupleCode, currentUid) { mutableStateOf<List<UsChatMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var loading by remember(coupleCode, currentUid) { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }
    var hasOlderMessages by remember(coupleCode, currentUid) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember(coupleCode, currentUid) { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var partnerReadCursor by remember(coupleCode, currentUid) { mutableStateOf<UsChatRoomRead?>(null) }
    var realtimeLabel by remember(coupleCode, currentUid) { mutableStateOf("Connecting…") }
    var hasInitialPage by remember(coupleCode, currentUid) { mutableStateOf(false) }

    LaunchedEffect(coupleCode, currentUid, refreshTrigger) {
        loading = messages.isEmpty()
        error = null
        try {
            val room = UsChatRepository.findRoom(coupleCode)
            roomId = room.id

            val page = UsChatRepository.loadLatestMessages(room.id)
            val queued = UsChatOutboxStore.forRoom(context, room.id)
            val confirmedClientIds = page.messages.mapNotNull { it.clientMessageId }.toSet()
            queued.filter { it.clientMessageId in confirmedClientIds }.forEach {
                UsChatOutboxStore.delete(context, it.clientMessageId)
            }
            val remainingQueued = queued.filterNot { it.clientMessageId in confirmedClientIds }
            messages = mergeChatTimeline(
                current = if (hasInitialPage) messages else emptyList(),
                latestServerPage = page.messages,
                queuedMessages = remainingQueued
            )
            if (!hasInitialPage) hasOlderMessages = page.hasMore
            hasInitialPage = true
            partnerReadCursor = UsChatRepository.loadPartnerReadCursor(room.id, currentUid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Couldn't connect to Us Chat."
        } finally {
            loading = false
        }
    }

    // 4.2: one room-scoped channel and collectors. The SDK owns WebSocket
    // reconnection; channel status changes trigger an immediate authoritative
    // refresh. Polling recovers missed events and covers subscription failures.
    LaunchedEffect(roomId, currentUid) {
        val activeRoomId = roomId ?: return@LaunchedEffect
        val supabase = SupabaseChatClientProvider.get()
        val channel = supabase.realtime.channel("us-chat-$activeRoomId")

        val messageChanges = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "chat_messages"
            filter("room_id", FilterOperator.EQ, activeRoomId)
        }
        val readChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "chat_room_reads"
            filter("room_id", FilterOperator.EQ, activeRoomId)
        }

        var realtimeConnected = false

        suspend fun syncLatest() {
            val page = UsChatRepository.loadLatestMessages(activeRoomId)
            val queued = UsChatOutboxStore.forRoom(context, activeRoomId)
            val confirmedClientIds = page.messages.mapNotNull { it.clientMessageId }.toSet()
            queued.filter { it.clientMessageId in confirmedClientIds }.forEach {
                UsChatOutboxStore.delete(context, it.clientMessageId)
            }
            val remainingQueued = queued.filterNot { it.clientMessageId in confirmedClientIds }
            messages = mergeChatTimeline(messages, page.messages, remainingQueued)
            partnerReadCursor = UsChatRepository.loadPartnerReadCursor(activeRoomId, currentUid)
        }

        val messageCollector = launch {
            messageChanges.collect {
                try {
                    syncLatest()
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "A new message arrived, but the chat couldn't refresh."
                }
            }
        }

        val readCollector = launch {
            readChanges.collect {
                try {
                    partnerReadCursor = UsChatRepository.loadPartnerReadCursor(activeRoomId, currentUid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The next receipt event or safety poll will refresh the cursor.
                }
            }
        }

        val statusCollector = launch {
            var wasConnected = false
            channel.status.collect { status ->
                realtimeConnected = status == RealtimeChannel.Status.SUBSCRIBED
                realtimeLabel = if (realtimeConnected) "Live updates" else "Reconnecting…"

                if (realtimeConnected && !wasConnected) {
                    try {
                        syncLatest()
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Chat reconnected, but messages couldn't refresh."
                    }
                }
                wasConnected = realtimeConnected
            }
        }

        try {
            try {
                channel.subscribe(blockUntilSubscribed = true)
                realtimeConnected = true
                realtimeLabel = "Live updates"
                syncLatest()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                realtimeConnected = false
                realtimeLabel = "Checking connection…"
            }

            while (isActive) {
                delay(if (realtimeConnected) 30_000L else 5_000L)
                try {
                    syncLatest()
                    if (!realtimeConnected) {
                        realtimeLabel = "Checking connection…"
                    }
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Couldn't refresh messages. Check your connection."
                    realtimeLabel = "Offline · retrying"
                }
            }
        } finally {
            messageCollector.cancel()
            readCollector.cancel()
            statusCollector.cancel()
            runCatching { supabase.realtime.removeChannel(channel) }
        }
    }

    val latestServerMessage = messages.lastOrNull {
        !it.isPending && it.sendError == null
    }

    // 4.3: opening the conversation or receiving a newer server message moves
    // this member's cursor forward. The partner's cursor is received separately.
    LaunchedEffect(roomId, currentUid, latestServerMessage?.id, refreshTrigger) {
        val activeRoomId = roomId ?: return@LaunchedEffect
        val latest = latestServerMessage ?: return@LaunchedEffect
        try {
            UsChatRepository.markRead(activeRoomId, currentUid, latest.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A later refresh or new message will retry this read-cursor write.
        }
    }

    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun loadOlderMessages() {
        val activeRoomId = roomId ?: return
        val cursor = messages.firstOrNull { !it.isPending && it.sendError == null } ?: return
        if (loadingOlder || !hasOlderMessages) return

        scope.launch {
            loadingOlder = true
            try {
                val olderPage = UsChatRepository.loadMessagesBefore(activeRoomId, cursor)
                val queued = UsChatOutboxStore.forRoom(context, activeRoomId)
                val confirmedClientIds = olderPage.messages.mapNotNull { it.clientMessageId }.toSet()
                queued.filter { it.clientMessageId in confirmedClientIds }.forEach {
                    UsChatOutboxStore.delete(context, it.clientMessageId)
                }
                val remainingQueued = queued.filterNot { it.clientMessageId in confirmedClientIds }
                messages = mergeChatTimeline(messages, olderPage.messages, remainingQueued)
                hasOlderMessages = olderPage.hasMore
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Couldn't load older messages."
            } finally {
                loadingOlder = false
            }
        }
    }

    fun retryQueuedMessage(message: UsChatMessage) {
        val clientMessageId = message.clientMessageId ?: return
        scope.launch {
            try {
                UsChatOutboxStore.markPending(context, clientMessageId)
                val queued = UsChatOutboxStore.forRoom(context, message.roomId)
                messages = mergeChatTimeline(messages, emptyList(), queued)
                UsChatOutboxScheduler.enqueue(context, clientMessageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Couldn't retry this message."
            }
        }
    }

    fun sendDraft() {
        val body = draft.trim()
        val activeRoomId = roomId
        if (body.isEmpty() || sending || activeRoomId == null) return
        if (body.length > 4000) {
            error = "Messages must be 4,000 characters or fewer."
            return
        }

        scope.launch {
            sending = true
            error = null
            try {
                val queued = UsChatOutboxMessage(
                    clientMessageId = UUID.randomUUID().toString(),
                    roomId = activeRoomId,
                    senderUid = currentUid,
                    body = body,
                    createdAt = Instant.now().toString(),
                    status = UsChatOutboxMessage.STATUS_PENDING,
                    lastError = null
                )
                UsChatOutboxStore.enqueue(context, queued)
                val pending = UsChatOutboxStore.forRoom(context, activeRoomId)
                messages = mergeChatTimeline(messages, emptyList(), pending)
                draft = ""
                UsChatOutboxScheduler.enqueue(context, queued.clientMessageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Message couldn't be queued. Please try again."
            } finally {
                sending = false
            }
        }
    }

    val partnerReadMessageId = partnerReadCursor?.lastReadMessageId
    val partnerReadIndex = messages.indexOfFirst { it.id == partnerReadMessageId }
    val lastSeenMineIndex = if (partnerReadIndex >= 0) {
        messages.indices.lastOrNull { index ->
            index <= partnerReadIndex &&
                messages[index].senderUid == currentUid &&
                !messages[index].isPending &&
                messages[index].sendError == null
        }
    } else {
        null
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ChatBubbleOutline, null, tint = colors.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Just us, right here", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, modifier = Modifier.size(11.dp), tint = colors.onSurfaceVariant)
                    Text(
                        "Private chat · only you two",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                Text(
                    realtimeLabel,
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 15.dp, top = 1.dp)
                )
            }
            IconButton(onClick = { refreshTrigger++ }, enabled = !loading && !loadingOlder) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh messages", tint = colors.onSurfaceVariant)
            }
        }

        if (error != null) {
            Surface(
                color = colors.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        error.orEmpty(),
                        color = colors.onErrorContainer,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { refreshTrigger++ }) {
                        Text("Retry", color = colors.onErrorContainer)
                    }
                }
            }
        }

        when {
            loading && messages.isEmpty() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Opening your conversation…", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            messages.isEmpty() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(horizontal = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colors.primaryContainer,
                            modifier = Modifier.size(76.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.ChatBubbleOutline,
                                    null,
                                    modifier = Modifier.size(34.dp),
                                    tint = colors.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("A little space for your words", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Send the first message to $partnerName. Your conversation starts here.",
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            else -> {
                if (hasOlderMessages || loadingOlder) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (loadingOlder) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("Loading older messages…", fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                        } else {
                            TextButton(onClick = { loadOlderMessages() }) {
                                Text("Load older messages", fontSize = 12.sp)
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val index = messages.indexOf(message)
                        val mine = message.senderUid == currentUid
                        ChatMessageBubble(
                            message = message,
                            mine = mine,
                            seen = mine && index == lastSeenMineIndex,
                            onRetry = { retryQueuedMessage(message) }
                        )
                    }
                }
            }
        }

        Surface(
            color = colors.surface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(4000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Write something sweet…", fontSize = 14.sp) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = {
                        if (draft.length > 3500) {
                            Text("${draft.length}/4000", fontSize = 10.sp)
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { sendDraft() },
                    enabled = draft.isNotBlank() && !sending && roomId != null,
                    modifier = Modifier.size(48.dp)
                ) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                    }
                }
            }
        }
    }
}

private fun mergeChatTimeline(
    current: List<UsChatMessage>,
    latestServerPage: List<UsChatMessage>,
    queuedMessages: List<UsChatOutboxMessage>
): List<UsChatMessage> {
    val serverMessages = (current.filter { !it.isPending && it.sendError == null } + latestServerPage)
        .distinctBy { it.id }
    val confirmedClientIds = serverMessages.mapNotNull { it.clientMessageId }.toSet()
    val localMessages = queuedMessages
        .filterNot { it.clientMessageId in confirmedClientIds }
        .map { it.asPendingMessage() }

    return (serverMessages + localMessages)
        .distinctBy { it.clientMessageId ?: it.id }
        .sortedWith { left, right ->
            val timeComparison = runCatching {
                Instant.parse(left.createdAt).compareTo(Instant.parse(right.createdAt))
            }.getOrElse {
                left.createdAt.compareTo(right.createdAt)
            }
            if (timeComparison != 0) timeComparison else left.id.compareTo(right.id)
        }
}

@Composable
private fun ChatMessageBubble(
    message: UsChatMessage,
    mine: Boolean,
    seen: Boolean,
    onRetry: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val bubbleColor = if (mine) colors.primary else colors.surfaceVariant
    val textColor = if (mine) colors.onPrimary else colors.onSurfaceVariant
    val time = remember(message.createdAt) {
        runCatching {
            DateTimeFormatter.ofPattern("h:mm a")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(message.createdAt))
        }.getOrDefault("")
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.fillMaxWidth(0.86f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (mine) 20.dp else 5.dp,
                    bottomEnd = if (mine) 5.dp else 20.dp
                )
            ) {
                Text(
                    message.body,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    time,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
                when {
                    message.sendError != null -> {
                        TextButton(onClick = onRetry) {
                            Text("Not sent · Retry", fontSize = 10.sp, color = colors.error)
                        }
                    }
                    message.isAwaitingSync -> {
                        Text(
                            " · Sent · syncing…",
                            color = colors.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    message.isPending -> {
                        Text(
                            " · Sending…",
                            color = colors.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    seen -> {
                        Text(
                            " · Seen",
                            color = colors.primary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
