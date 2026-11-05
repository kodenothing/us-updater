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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    var roomId by remember(coupleCode) { mutableStateOf<String?>(null) }
    var messages by remember(coupleCode) { mutableStateOf<List<UsChatMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var loading by remember(coupleCode) { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember(coupleCode) { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(coupleCode, currentUid, refreshTrigger) {
        loading = messages.isEmpty()
        error = null
        try {
            val room = UsChatRepository.findRoom(coupleCode)
            roomId = room.id
            while (isActive) {
                try {
                    messages = UsChatRepository.loadMessages(room.id)
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Couldn't refresh messages. Check your connection."
                }
                loading = false
                delay(5_000)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loading = false
            error = failure.message ?: "Couldn't connect to Us Chat."
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
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
                UsChatRepository.sendMessage(activeRoomId, currentUid, body)
                draft = ""
                messages = UsChatRepository.loadMessages(activeRoomId)
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Message couldn't be sent. Please try again."
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
            }
            IconButton(onClick = { refreshTrigger++ }, enabled = !loading) {
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
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Opening your conversation…", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            messages.isEmpty() -> {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val mine = message.senderUid == currentUid
                        ChatMessageBubble(message = message, mine = mine)
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
