package com.maenalyn.couples.chat

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class FailedChatSend(
    val body: String,
    val clientMessageId: String
)

@Composable
internal fun UsChatScreen(
    coupleCode: String,
    currentUid: String,
    partnerName: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
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
    var realtimeLabel by remember(coupleCode, currentUid) { mutableStateOf("Connecting…") }
    var hasInitialPage by remember(coupleCode, currentUid) { mutableStateOf(false) }
    var failedSend by remember { mutableStateOf<FailedChatSend?>(null) }

    LaunchedEffect(coupleCode, currentUid, refreshTrigger) {
        loading = messages.isEmpty()
        error = null
        try {
            val room = UsChatRepository.findRoom(coupleCode)
            roomId = room.id

            val page = UsChatRepository.loadLatestMessages(room.id)
            messages = mergeChatTimeline(
                current = if (hasInitialPage) messages else emptyList(),
                incoming = page.messages
            )
            if (!hasInitialPage) hasOlderMessages = page.hasMore
            hasInitialPage = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Couldn't connect to Us Chat."
        } finally {
            loading = false
        }
    }

    // Stage 4.2: observe both the WebSocket and this room's channel. The
    // Supabase Kotlin 3.1.4 client reconnects its socket and rejoins channels.
    // We refresh from PostgREST after both layers report a successful rejoin.
    LaunchedEffect(roomId) {
        val activeRoomId = roomId ?: return@LaunchedEffect
        val supabase = SupabaseChatClientProvider.get()
        val channel = supabase.realtime.channel("us-chat-$activeRoomId")
        val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "chat_messages"
            filter("room_id", io.github.jan.supabase.postgrest.query.filter.FilterOperator.EQ, activeRoomId)
        }

        var realtimeConnected = false

        suspend fun syncLatest() {
            val page = UsChatRepository.loadLatestMessages(activeRoomId)
            messages = mergeChatTimeline(messages, page.messages)
        }

        val messageCollector = launch {
            changeFlow.collect {
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

        val connectionCollector = launch {
            combine(supabase.realtime.status, channel.status) { socketStatus, channelStatus ->
                socketStatus == Realtime.Status.CONNECTED &&
                    channelStatus == RealtimeChannel.Status.SUBSCRIBED
            }.collect { connected ->
                val justRecovered = connected && !realtimeConnected
                realtimeConnected = connected
                realtimeLabel = when {
                    connected -> "Live updates"
                    supabase.realtime.status.value == Realtime.Status.DISCONNECTED -> "Reconnecting…"
                    else -> "Connecting…"
                }
                if (justRecovered) {
                    try {
                        syncLatest()
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Chat reconnected, but messages couldn't refresh."
                    }
                }
            }
        }

        try {
            try {
                // Keep this non-blocking. A failed first handshake must not
                // prevent the fallback sync loop from running.
                channel.subscribe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                realtimeLabel = "Reconnecting…"
            }

            while (isActive) {
                delay(if (realtimeConnected) 30_000L else 5_000L)
                try {
                    syncLatest()
                    if (realtimeConnected) error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Couldn't refresh messages. Check your connection."
                    realtimeLabel = "Offline · retrying"
                }
            }
        } finally {
            messageCollector.cancel()
            connectionCollector.cancel()
            runCatching { supabase.realtime.removeChannel(channel) }
        }
    }

    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun loadOlderMessages() {
        val activeRoomId = roomId ?: return
        val cursor = messages.firstOrNull() ?: return
        if (loadingOlder || !hasOlderMessages) return

        scope.launch {
            loadingOlder = true
            try {
                val previousTopId = messages.getOrNull(listState.firstVisibleItemIndex)?.id
                val previousOffset = listState.firstVisibleItemScrollOffset
                val olderPage = UsChatRepository.loadMessagesBefore(activeRoomId, cursor)
                val updated = mergeChatTimeline(messages, olderPage.messages)
                messages = updated
                hasOlderMessages = olderPage.hasMore
                val anchorIndex = previousTopId?.let { id ->
                    updated.indexOfFirst { it.id == id }
                } ?: -1
                if (anchorIndex >= 0) listState.scrollToItem(anchorIndex, previousOffset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Couldn't load older messages."
            } finally {
                loadingOlder = false
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

        // Keep one UUID for retries of the same draft so a timed-out request
        // that actually committed cannot create a second database row.
        val clientMessageId = failedSend
            ?.takeIf { it.body == body }
            ?.clientMessageId
            ?: UUID.randomUUID().toString()

        scope.launch {
            sending = true
            error = null
            try {
                val saved = UsChatRepository.sendMessage(
                    roomId = activeRoomId,
                    senderUid = currentUid,
                    body = body,
                    clientMessageId = clientMessageId
                )
                messages = mergeChatTimeline(messages, listOf(saved))
                draft = ""
                failedSend = null
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failedSend = FailedChatSend(body, clientMessageId)
                error = failure.message ?: "Message couldn't be sent. Your draft is still here; retry it."
            } finally {
                sending = false
            }
        }
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
                    TextButton(onClick = {
                        if (failedSend != null) sendDraft() else refreshTrigger++
                    }) {
                        Text(if (failedSend != null) "Retry send" else "Retry", color = colors.onErrorContainer)
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
                    itemsIndexed(messages, key = { _, message -> message.id }) { _, message ->
                        ChatMessageBubble(message = message, mine = message.senderUid == currentUid)
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
                    onValueChange = {
                        draft = it.take(4000)
                        if (failedSend?.body != it.trim()) failedSend = null
                    },
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
    incoming: List<UsChatMessage>
): List<UsChatMessage> {
    val byId = LinkedHashMap<String, UsChatMessage>()
    (current + incoming).forEach { message -> byId[message.id] = message }

    return byId.values
        .distinctBy { it.clientMessageId ?: it.id }
        .sortedWith { left, right ->
            val timeComparison = runCatching {
                Instant.parse(left.createdAt).compareTo(Instant.parse(right.createdAt))
            }.getOrElse { left.createdAt.compareTo(right.createdAt) }
            if (timeComparison != 0) timeComparison else left.id.compareTo(right.id)
        }
}

@Composable
private fun ChatMessageBubble(message: UsChatMessage, mine: Boolean) {
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
            Text(
                time,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
}
