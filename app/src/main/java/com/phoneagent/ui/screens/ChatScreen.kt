package com.phoneagent.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.ai.ChatMessage
import com.phoneagent.engine.AgentEngine
import com.phoneagent.memory.Conversation
import com.phoneagent.ui.components.MarkdownText
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val engine = remember { AgentEngine.getInstance(context) }
    val messages by engine.conversationHistory.collectAsState()
    val isProcessing by engine.isProcessing.collectAsState()
    val streamingText by engine.streamingText.collectAsState()
    val conversations by engine.conversationManager.conversations.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showDrawer by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var selectedMessageIndex by remember { mutableStateOf(-1) }
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val base64 = uriToBase64(context, it)
            if (base64 != null) {
                pendingImages = pendingImages + base64
            }
        }
    }

    // Auto-scroll
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            try {
                listState.animateScrollToItem(messages.size - 1 + if (streamingText.isNotEmpty()) 1 else 0)
            } catch (_: Exception) { }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 1.dp
        ) {
            TopAppBar(
                title = {
                    val currentConvId = engine.conversationManager.currentConversationId
                    val currentTitle = conversations.find { it.id == currentConvId }?.title ?: "PhoneAgent"
                    Column {
                        Text(
                            currentTitle,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isProcessing) {
                            Text(
                                "正在思考...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showDrawer = !showDrawer }) {
                        Icon(
                            if (showDrawer) Icons.Default.MenuOpen else Icons.Default.Menu,
                            contentDescription = "对话列表"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { engine.newConversation() } }) {
                        Icon(Icons.Outlined.NoteAdd, contentDescription = "新对话")
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("分享对话") },
                                onClick = { engine.shareConversation(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("重新生成") },
                                onClick = {
                                    showMoreMenu = false
                                    scope.launch { try { engine.regenerateLastResponse() } catch (_: Exception) {} }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("清空显示") },
                                onClick = { engine.clearHistory(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Outlined.ClearAll, contentDescription = null) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        Row(modifier = Modifier.weight(1f)) {
            // Conversation drawer
            if (showDrawer) {
                ConversationDrawer(
                    conversations = conversations,
                    currentId = engine.conversationManager.currentConversationId,
                    onSelect = { conv ->
                        scope.launch { engine.switchConversation(conv.id); showDrawer = false }
                    },
                    onDelete = { conv -> scope.launch { engine.deleteConversation(conv.id) } },
                    onNewChat = { scope.launch { engine.newConversation(); showDrawer = false } },
                    modifier = Modifier.width(260.dp)
                )
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(messages) { index, message ->
                    MessageBubble(
                        message = message,
                        isSelected = selectedMessageIndex == index,
                        onLongClick = { selectedMessageIndex = if (selectedMessageIndex == index) -1 else index },
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            selectedMessageIndex = -1
                        },
                        onDelete = {
                            engine.deleteMessageAt(index)
                            selectedMessageIndex = -1
                        },
                        onSpeak = {
                            engine.voiceManager.speak(message.content)
                            selectedMessageIndex = -1
                        },
                        onRegenerate = if (message.role == "assistant" && index == messages.lastIndex) {
                            {
                                selectedMessageIndex = -1
                                scope.launch { try { engine.regenerateLastResponse() } catch (_: Exception) {} }
                            }
                        } else null
                    )
                }

                // Streaming indicator
                if (streamingText.isNotEmpty()) {
                    item {
                        StreamingBubble(streamingText)
                    }
                }

                // Processing indicator (before streaming starts)
                if (isProcessing && streamingText.isEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "●",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "思考中...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pending image preview
        AnimatedVisibility(visible = pendingImages.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${pendingImages.size} 张图片已选择",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { pendingImages = emptyList() },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("清除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Input bar
        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Image picker button
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = "选择图片",
                        tint = if (pendingImages.isNotEmpty()) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Text input
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...", color = MaterialTheme.colorScheme.outline) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Send / Stop button
                if (isProcessing) {
                    IconButton(
                        onClick = { engine.cancelChat() },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "停止")
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val text = inputText.trim()
                                val images = pendingImages.ifEmpty { null }
                                inputText = ""
                                pendingImages = emptyList()
                                scope.launch {
                                    try {
                                        engine.chatStream(text, images)
                                    } catch (e: Exception) {
                                        android.util.Log.e("ChatScreen", "Chat error", e)
                                        kotlinx.coroutines.Dispatchers.Main.let {
                                            Toast.makeText(context, "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                          else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

// ========================================================================
//  Conversation drawer
// ========================================================================

@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    currentId: Long,
    onSelect: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            // Header
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "对话记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(
                        onClick = onNewChat,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新建", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(conversations) { conv ->
                    val selected = conv.id == currentId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(conv) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (selected) Icons.Default.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conv.title,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    "${conv.messageCount}条 · ${dateFormat.format(Date(conv.updatedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(onClick = { onDelete(conv) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========================================================================
//  Message bubble with markdown + actions
// ========================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onSpeak: () -> Unit,
    onRegenerate: (() -> Unit)?,
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"

    val bgColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    val alignment = if (isUser) Arrangement.End else Arrangement.Start

    Column(modifier = Modifier.animateContentSize()) {
        // Role label
        if (!isUser) {
            Row(
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isTool) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isTool) Icons.Outlined.Build else Icons.Outlined.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isTool) MaterialTheme.colorScheme.onTertiaryContainer
                                   else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (isTool) "工具执行" else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = alignment
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = bgColor,
                tonalElevation = if (isUser) 0.dp else 1.dp,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = { },
                        onLongClick = onLongClick
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Render content
                    if (!isUser && !isTool) {
                        MarkdownText(
                            text = message.content,
                            baseColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Generated image
                    if (message.imageUrl != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Image, contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    message.imageUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Tool calls
                    if (!message.toolCalls.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        message.toolCalls.forEach { tc ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    "→ ${tc.name}(${tc.arguments.entries.joinToString { "${it.key}=${it.value}" }})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Action bar when selected
        AnimatedVisibility(visible = isSelected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 2.dp
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制",
                                modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = "朗读",
                                modifier = Modifier.size(18.dp))
                        }
                        if (onRegenerate != null) {
                            IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "重新生成",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ========================================================================
//  Streaming bubble
// ========================================================================

@Composable
private fun StreamingBubble(text: String) {
    Column {
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                tonalElevation = 1.dp,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    MarkdownText(
                        text = text,
                        baseColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "● ● ●",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// ========================================================================
//  Utilities
// ========================================================================

private fun uriToBase64(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        // Decode with inJustDecodeBounds to check size first
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val peekStream = context.contentResolver.openInputStream(uri) ?: run { inputStream.close(); return null }
        BitmapFactory.decodeStream(peekStream, null, options)
        peekStream.close()

        // Calculate subsample to keep image under ~2048px on longest side
        val maxDim = maxOf(options.outWidth, options.outHeight)
        val sampleSize = if (maxDim > 2048) (maxDim / 2048).coerceAtLeast(1) else 1
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        val bitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        inputStream.close()
        if (bitmap == null) return null

        val stream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
        bitmap.recycle()
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    } catch (_: OutOfMemoryError) { null }
    catch (_: Exception) { null }
}
