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
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

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
    var outboxMessages by remember(coupleCode, currentUid) {
        mutableStateOf<List<UsChatOutboxEntry>>(emptyList())
    }
    var readStates by remember(coupleCode, currentUid) {
        mutableStateOf<List<UsChatReadState>>(emptyList())
    }
    val partnerReadState = readStates.firstOrNull { it.readerUid != currentUid }
    val timelineMessages = remember(messages, outboxMessages) {
        buildChatTimeline(messages, outboxMessages)
    }

    LaunchedEffect(coupleCode, currentUid, refreshTrigger) {
        loading = messages.isEmpty()
        error = null
        try {
            val localState = withContext(Dispatchers.IO) {
                val cachedRoomId = UsChatOutboxStore.findCachedRoomId(
                    context,
                    coupleCode,
                    currentUid
                )
                val savedOutbox = UsChatOutboxStore.listForCouple(
                    context,
                    coupleCode,
                    currentUid
                )
                cachedRoomId to savedOutbox
            }
            roomId = localState.first
            outboxMessages = localState.second
            // Re-enqueue older local messages before the user can add newer ones.
            localState.second
                .filter { it.status == UsChatOutboxStatus.PENDING }
                .forEach { UsChatOutboxScheduler.enqueue(context, it) }

            val room = try {
                UsChatRepository.findRoom(coupleCode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val cachedRoomId = localState.first
                if (cachedRoomId == null || context.hasValidatedInternet()) throw failure

                // The room was resolved during an earlier online session.
                // Keep the local conversation usable while WorkManager waits
                // for connectivity to return.
                realtimeLabel = "Offline · messages will send when you reconnect"
                error = null
                return@LaunchedEffect
            }

            withContext(Dispatchers.IO) {
                UsChatOutboxStore.cacheRoomMapping(
                    context,
                    coupleCode,
                    currentUid,
                    room.id
                )
            }
            roomId = room.id

            val page = UsChatRepository.loadLatestMessages(room.id)
            messages = mergeChatTimeline(
                current = if (hasInitialPage) messages else emptyList(),
                incoming = page.messages
            )
            if (!hasInitialPage) hasOlderMessages = page.hasMore
            val confirmedIds = page.messages.mapNotNull { it.clientMessageId }.toSet()
            outboxMessages = withContext(Dispatchers.IO) {
                UsChatOutboxStore.deleteConfirmed(context, room.id, confirmedIds)
                UsChatOutboxStore.listForRoom(context, room.id, currentUid)
            }
            hasInitialPage = true
            readStates = UsChatRepository.loadReadStates(room.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!context.hasValidatedInternet() && roomId != null) {
                realtimeLabel = "Offline · messages will send when connected"
                error = null
            } else {
                error = failure.message ?: "Couldn't connect to Us Chat."
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(roomId, currentUid) {
        val activeRoomId = roomId ?: return@LaunchedEffect
        while (isActive) {
            outboxMessages = withContext(Dispatchers.IO) {
                UsChatOutboxStore.listForRoom(context, activeRoomId, currentUid)
            }
            delay(2_000L)
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
        val readInsertFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "chat_room_reads"
            filter("room_id", io.github.jan.supabase.postgrest.query.filter.FilterOperator.EQ, activeRoomId)
        }
        val readUpdateFlow = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "chat_room_reads"
            filter("room_id", io.github.jan.supabase.postgrest.query.filter.FilterOperator.EQ, activeRoomId)
        }

        var realtimeConnected = false

        suspend fun syncLatest() {
            val page = UsChatRepository.loadLatestMessages(activeRoomId)
            messages = mergeChatTimeline(messages, page.messages)
            if (!hasInitialPage) {
                hasOlderMessages = page.hasMore
                hasInitialPage = true
            }
            val confirmedIds = page.messages.mapNotNull { it.clientMessageId }.toSet()
            outboxMessages = withContext(Dispatchers.IO) {
                UsChatOutboxStore.deleteConfirmed(context, activeRoomId, confirmedIds)
                UsChatOutboxStore.listForRoom(context, activeRoomId, currentUid)
            }
            readStates = UsChatRepository.loadReadStates(activeRoomId)
        }

        val messageCollector = launch {
            changeFlow.collect {
                try {
                    syncLatest()
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!context.hasValidatedInternet()) {
                        realtimeLabel = "Offline · messages will send when connected"
                        error = null
                    } else {
                        error = failure.message ?: "A new message arrived, but the chat couldn't refresh."
                    }
                }
            }
        }

        val readInsertCollector = launch {
            readInsertFlow.collect {
                try {
                    readStates = UsChatRepository.loadReadStates(activeRoomId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Realtime is a signal; periodic sync will recover read state.
                }
            }
        }
        val readUpdateCollector = launch {
            readUpdateFlow.collect {
                try {
                    readStates = UsChatRepository.loadReadStates(activeRoomId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Realtime is a signal; periodic sync will recover read state.
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
                        if (!context.hasValidatedInternet()) {
                            realtimeLabel = "Offline · messages will send when connected"
                            error = null
                        } else {
                            error = failure.message ?: "Chat reconnected, but messages couldn't refresh."
                        }
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
                    if (!context.hasValidatedInternet()) {
                        realtimeLabel = "Offline · messages will send when connected"
                        error = null
                    } else {
                        error = failure.message ?: "Couldn't refresh messages. Check your connection."
                        realtimeLabel = "Sync issue · retrying"
                    }
                }
            }
        } finally {
            messageCollector.cancel()
            readInsertCollector.cancel()
            readUpdateCollector.cancel()
            connectionCollector.cancel()
            runCatching { supabase.realtime.removeChannel(channel) }
        }
    }

    LaunchedEffect(timelineMessages.lastOrNull()?.id) {
        if (timelineMessages.isNotEmpty()) {
            listState.animateScrollToItem(timelineMessages.lastIndex)
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
                val confirmedIds = olderPage.messages.mapNotNull { it.clientMessageId }.toSet()
                outboxMessages = withContext(Dispatchers.IO) {
                    UsChatOutboxStore.deleteConfirmed(context, activeRoomId, confirmedIds)
                    UsChatOutboxStore.listForRoom(context, activeRoomId, currentUid)
                }
                val anchorIndex = previousTopId?.let { id ->
                    updated.indexOfFirst { it.id == id }
                } ?: -1
                if (anchorIndex >= 0) listState.scrollToItem(anchorIndex, previousOffset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = if (context.hasValidatedInternet()) {
                    failure.message ?: "Couldn't load older messages."
                } else {
                    "Connect to the internet to load older messages."
                }
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

        val clientMessageId = UUID.randomUUID().toString()
        scope.launch {
            sending = true
            error = null
            var savedLocally = false
            try {
                val queued = withContext(Dispatchers.IO) {
                    UsChatOutboxStore.insert(
                        context = context,
                        roomId = activeRoomId,
                        coupleCode = coupleCode,
                        senderUid = currentUid,
                        body = body,
                        clientMessageId = clientMessageId
                    )
                }
                draft = ""
                savedLocally = true
                outboxMessages = withContext(Dispatchers.IO) {
                    UsChatOutboxStore.listForRoom(context, activeRoomId, currentUid)
                }
                UsChatOutboxScheduler.enqueue(context, queued)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: if (savedLocally) {
                    "Message saved on this device, but delivery could not be scheduled. Tap Retry."
                } else {
                    "Message could not be saved locally. Your draft is still here."
                }
            } finally {
                sending = false
            }
        }
    }

    fun retryOutboxMessage(clientMessageId: String) {
        scope.launch {
            try {
                val queued = withContext(Dispatchers.IO) {
                    UsChatOutboxStore.retry(context, clientMessageId)
                        ?: throw IllegalStateException("This failed message is no longer available to retry.")
                }
                outboxMessages = withContext(Dispatchers.IO) {
                    UsChatOutboxStore.listForRoom(context, queued.roomId, currentUid)
                }
                UsChatOutboxScheduler.enqueue(context, queued)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Couldn't retry this message."
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
                    TextButton(onClick = { refreshTrigger++ }) {
                        Text("Retry", color = colors.onErrorContainer)
                    }
                }
            }
        }

        when {
            loading && timelineMessages.isEmpty() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Opening your conversation…", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            timelineMessages.isEmpty() -> {
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
                    itemsIndexed(timelineMessages, key = { _, message -> message.id }) { _, message ->
                        ChatMessageBubble(
                            message = message,
                            mine = message.senderUid == currentUid,
                            onRetry = ::retryOutboxMessage
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
                    onValueChange = {
                        draft = it.take(4000)
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


private fun Context.hasValidatedInternet(): Boolean {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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

private data class ChatTimelineItem(
    val id: String,
    val senderUid: String,
    val body: String,
    val createdAt: String,
    val clientMessageId: String?,
    val deliveryState: String?
)

private fun buildChatTimeline(
    serverMessages: List<UsChatMessage>,
    outboxMessages: List<UsChatOutboxEntry>
): List<ChatTimelineItem> {
    val confirmedClientIds = serverMessages.mapNotNull { it.clientMessageId }.toSet()
    val result = ArrayList<ChatTimelineItem>(serverMessages.size + outboxMessages.size)

    serverMessages.forEach { message ->
        result += ChatTimelineItem(
            id = message.id,
            senderUid = message.senderUid,
            body = message.body,
            createdAt = message.createdAt,
            clientMessageId = message.clientMessageId,
            deliveryState = null
        )
    }

    outboxMessages
        .filterNot { it.clientMessageId in confirmedClientIds }
        .forEach { entry ->
            result += ChatTimelineItem(
                id = entry.serverMessageId ?: "local:${entry.clientMessageId}",
                senderUid = entry.senderUid,
                body = entry.body,
                createdAt = entry.serverCreatedAt
                    ?: Instant.ofEpochMilli(entry.localCreatedAtMs).toString(),
                clientMessageId = entry.clientMessageId,
                deliveryState = entry.status
            )
        }

    return result.sortedWith { left, right ->
        val timeComparison = runCatching {
            Instant.parse(left.createdAt).compareTo(Instant.parse(right.createdAt))
        }.getOrElse { left.createdAt.compareTo(right.createdAt) }
        if (timeComparison != 0) timeComparison else left.id.compareTo(right.id)
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatTimelineItem,
    mine: Boolean,
    onRetry: (String) -> Unit
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
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    time,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
                message.deliveryState?.let { state ->
                    Text(
                        " · " + when (state) {
                            UsChatOutboxStatus.PENDING -> "Waiting for connection"
                            UsChatOutboxStatus.SENDING -> "Sending…"
                            UsChatOutboxStatus.SENT -> "Sent"
                            UsChatOutboxStatus.FAILED -> "Not sent"
                            else -> state
                        },
                        color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 3.dp)
                    )
                    if (state == UsChatOutboxStatus.FAILED && message.clientMessageId != null) {
                        TextButton(
                            onClick = { onRetry(message.clientMessageId) },
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("Retry", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
