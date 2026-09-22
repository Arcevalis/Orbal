package me.fss.orbal.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import me.fss.orbal.ai.ModelManager
import me.fss.orbal.data.local.entities.Conversation
import me.fss.orbal.data.local.entities.Message
import me.fss.orbal.data.repository.MessageWithAttachments
import me.fss.orbal.ui.components.InputBar
import me.fss.orbal.ui.components.LoadingIndicator
import me.fss.orbal.ui.components.MessageBubble
import me.fss.orbal.ui.components.StreamingMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val SensitiveDataKey = SemanticsPropertyKey<Boolean>("SensitiveData")
private var SemanticsPropertyReceiver.sensitiveData by SensitiveDataKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sensitiveDataModifier = if (
        uiState.sensitiveDataAccessibilityEnabled &&
        Build.VERSION.SDK_INT >= 36 // Android 16+
    ) {
        Modifier.semantics { sensitiveData = true }
    } else {
        Modifier
    }
    var inputText by rememberSaveable { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var showImageSourceSheet by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    var actionMessage by remember { mutableStateOf<MessageWithAttachments?>(null) }
    var editMessage by remember { mutableStateOf<Message?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    // Throttle timestamp for streaming follow
    var lastAutoScrollMs by remember { mutableStateOf(0L) }

    // Launchers
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)) { uris ->
        if (uris.isNotEmpty()) {
            val remaining = 4 - pendingImages.size
            val toAdd = uris.take(remaining)
            pendingImages = pendingImages + toAdd
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraUri != null) {
            if (pendingImages.size < 4) {
                pendingImages = pendingImages + cameraUri!!
            }
            cameraUri = null
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                val cacheImagesDir = File(context.cacheDir, "images").apply { mkdirs() }
                val file = File(cacheImagesDir, "camera_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Camera error: ${e.message}") }
            }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Camera permission denied") }
        }
    }

    fun launchCamera() {
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            try {
                val cacheImagesDir = File(context.cacheDir, "images").apply { mkdirs() }
                val file = File(cacheImagesDir, "camera_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Camera error: ${e.message}") }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun launchGallery() {
        val remaining = 4 - pendingImages.size
        if (remaining <= 0) {
            scope.launch { snackbarHostState.showSnackbar("Max 4 images") }
            return
        }
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    // --- Scroll: follow only when you're literally at the bottom (true bottom, not just top of last item) ---
    val isAtBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            if (total == 0) true
            else {
                val last = layout.visibleItemsInfo.lastOrNull()
                if (last == null) {
                    // Layout not yet computed — assume at bottom for initial scroll
                    true
                } else {
                    // Need the *last item* to be fully visible (bottom edge inside viewport)
                    // Tall streaming message: top visible != bottom visible
                    val isLastItem = last.index == total - 1 || layout.visibleItemsInfo.any { it.index == total - 1 }
                    if (!isLastItem) false
                    else {
                        val lastItem = layout.visibleItemsInfo.find { it.index == total - 1 } ?: last
                        // viewportEndOffset is the bottom edge in pixels
                        val viewportEnd = layout.viewportEndOffset
                        val itemBottom = lastItem.offset + lastItem.size
                        // Allow 8px tolerance for rounding/dividers
                        itemBottom <= viewportEnd + 8
                    }
                }
            }
        }
    }
    // autoScroll follows isAtBottom directly — no separate dragged state to drift
    val autoScrollEnabled by remember { derivedStateOf { isAtBottom } }

    // Helper: snap to true bottom (instant, for streaming/inserts)
    suspend fun snapToTrueBottom() {
        val totalItems = uiState.messages.size + (if (uiState.isGenerating) 1 else 0)
        if (totalItems <= 0) return
        try { listState.scrollToItem(totalItems - 1) } catch (_: Exception) { return }
        // Nudge so bottom of tall last item is visible, not just its top
        try {
            // Let layout settle one frame
            // (no delay needed for instant snap, but check offset)
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.find { it.index == totalItems - 1 }
            if (lastItem != null) {
                val viewportEnd = info.viewportEndOffset
                val itemBottom = lastItem.offset + lastItem.size
                val overhang = itemBottom - viewportEnd
                if (overhang > 0) {
                    listState.scrollToItem(totalItems - 1, scrollOffset = overhang)
                    // Fallback: if scrollOffset clamped, nudge by scrollBy
                    val info2 = listState.layoutInfo
                    val last2 = info2.visibleItemsInfo.find { it.index == totalItems - 1 }
                    if (last2 != null) {
                        val overhang2 = (last2.offset + last2.size) - info2.viewportEndOffset
                        if (overhang2 > 2) {
                            try { listState.scroll { scrollBy(overhang2.toFloat()) } } catch (_: Exception) {}
                        }
                    }
                }
            } else {
                // Last item not laid out yet — large nudge will be clamped to real bottom
                try { listState.scroll { scrollBy(10000f) } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
    // Helper: animate to true bottom (for FAB)
    suspend fun animateToTrueBottom() {
        val totalItems = uiState.messages.size + (if (uiState.isGenerating) 1 else 0)
        if (totalItems <= 0) return
        try { listState.animateScrollToItem(totalItems - 1) } catch (_: Exception) { return }
        try {
            delay(60)
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.find { it.index == totalItems - 1 }
            if (lastItem != null) {
                val overhang = (lastItem.offset + lastItem.size) - info.viewportEndOffset
                if (overhang > 2) {
                    listState.scroll { scrollBy(overhang.toFloat() + 4f) }
                }
            } else {
                listState.scroll { scrollBy(8000f) }
            }
        } catch (_: Exception) {}
    }

    // New messages (sent/received) — instant snap to true bottom if we were already at bottom
    LaunchedEffect(uiState.messages.size) {
        if (autoScrollEnabled) {
            snapToTrueBottom()
        }
    }
    // Streaming tokens — throttled instant follow (50ms) so drag-up never snaps back
    LaunchedEffect(uiState.partialResponse) {
        if (!autoScrollEnabled) return@LaunchedEffect
        if (uiState.partialResponse.isEmpty()) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastAutoScrollMs < 50) return@LaunchedEffect
        lastAutoScrollMs = now
        snapToTrueBottom()
    }
    // Also re-pin when generating state flips (e.g. isGenerating true adds streaming item)
    LaunchedEffect(uiState.isGenerating) {
        if (autoScrollEnabled && uiState.isGenerating) {
            snapToTrueBottom()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(uiState.showConversationDrawer) {
        if (uiState.showConversationDrawer) drawerState.open() else drawerState.close()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = uiState.conversations,
                currentId = uiState.currentConversation?.id,
                onSelect = { viewModel.switchConversation(it) },
                onDelete = { viewModel.deleteConversation(it.id) },
                onRename = { conv, newTitle -> viewModel.renameConversation(conv.id, newTitle) },
                onNew = { viewModel.newConversation() },
            )
        },
        gesturesEnabled = true,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.currentConversation?.title ?: "Orbal",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White,
                            )
                            if (uiState.isRemoteEnabled) {
                                val toolsSuffix = if (uiState.toolsEnabled) " · tools" else ""
                                val rawId = uiState.remoteModelId
                                when {
                                    rawId.isBlank() -> Text(
                                        text = "Local ● no model",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    uiState.isGenerating -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Local ● ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                        )
                                        Text(
                                            text = rawId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Text(
                                            text = " — generating…$toolsSuffix",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                            maxLines = 1,
                                        )
                                    }
                                    else -> {
                                        val tpsSuffix = uiState.tokensPerSecond?.let { String.format(" • %.1f tok/s", it) } ?: ""
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Local ● ",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White,
                                            )
                                            Text(
                                                text = rawId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (tpsSuffix.isNotEmpty()) {
                                                Text(
                                                    text = tpsSuffix,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                )
                                            }
                                            if (toolsSuffix.isNotEmpty()) {
                                                Text(
                                                    text = toolsSuffix,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                when (uiState.modelState) {
                                    is ModelManager.ModelState.Loading -> {
                                        Text(
                                            text = "Loading model…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                        )
                                    }
                                    is ModelManager.ModelState.Ready -> {
                                        uiState.tokensPerSecond?.let { tps ->
                                            Text(
                                                text = String.format("%.1f tok/s", tps),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White,
                                            )
                                        }
                                    }
                                    is ModelManager.ModelState.Error -> {
                                        Text(
                                            text = "Model error",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                            Icon(
                                if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = "Search",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { viewModel.newConversation() }) {
                            Icon(Icons.Filled.Add, contentDescription = "New Chat", tint = Color.White)
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .then(sensitiveDataModifier)
            ) {
                if (uiState.modelState is ModelManager.ModelState.Ready && uiState.contextMax > 0) {
                    val fraction = (uiState.contextUsed.toFloat() / uiState.contextMax.toFloat())
                        .coerceIn(0f, 1f)
                    val barColor = when {
                        fraction > 0.85f -> MaterialTheme.colorScheme.error
                        fraction > 0.65f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Context",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${uiState.contextUsed} / ${uiState.contextMax}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }

                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        placeholder = { Text("Search messages…") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                    )
                }

                val displayMessages = if (searchQuery.isBlank()) uiState.messages
                    else uiState.messages.filter { it.message.content.contains(searchQuery, ignoreCase = true) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    if (uiState.messages.isEmpty() && !uiState.isGenerating) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Orbal",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = when {
                                            uiState.isRemoteEnabled && uiState.remoteModelId.isBlank() ->
                                                "Remote mode — no model.\nPick one in Settings → Remote Model."
                                            uiState.isRemoteEnabled -> {
                                                val sId = if (uiState.remoteModelId.length > 24) uiState.remoteModelId.take(24) + "…" else uiState.remoteModelId
                                                "Local — $sId${if (uiState.toolsEnabled) " · tools" else ""}\nSend a message to start chatting."
                                            }
                                            uiState.modelState is ModelManager.ModelState.Loading -> "Loading model…"
                                            uiState.modelState is ModelManager.ModelState.NotLoaded -> "No model loaded.\nImport a GGUF in Settings."
                                            uiState.modelState is ModelManager.ModelState.Error -> (uiState.modelState as ModelManager.ModelState.Error).message
                                            uiState.modelState is ModelManager.ModelState.Ready -> "Send a message or add an image \uD83D\uDCF7"
                                            else -> "Send a message to start chatting"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    if (!uiState.isRemoteEnabled && uiState.modelState is ModelManager.ModelState.Loading) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }

                    items(displayMessages, key = { it.message.id }) { mwa ->
                        val message = mwa.message
                        MessageBubble(
                            content = message.content,
                            isUser = message.role == "user",
                            attachments = mwa.attachments,
                            reasoningContent = message.reasoningContent,
                            onImageClick = { path -> viewerPath = path },
                            onLongPress = {
                                if (!uiState.isGenerating) {
                                    actionMessage = mwa
                                }
                            },
                            onSpeak = if (message.role == "assistant") {
                                { viewModel.speakMessage(message.id, message.content) }
                            } else null,
                            onStopSpeaking = { viewModel.stopSpeaking() },
                            isSpeaking = uiState.speakingMessageId == message.id,
                            onRegenerate = if (message.role == "assistant" && !uiState.isGenerating) {
                                { viewModel.regenerateFromMessage(message.id) }
                            } else null,
                        )
                    }

                    if (uiState.isGenerating) {
                        item {
                            if (uiState.partialResponse.isNotEmpty() || uiState.partialReasoning.isNotEmpty()) {
                                StreamingMessage(
                                    partialResponse = uiState.partialResponse,
                                    partialReasoning = uiState.partialReasoning
                                )
                            } else {
                                LoadingIndicator()
                            }
                        }
                    }


                    }
                    // Scroll-to-bottom FAB — shows whenever we're not at the true bottom
                    val showJump by remember { derivedStateOf { !isAtBottom && (uiState.messages.isNotEmpty() || uiState.isGenerating) } }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showJump,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        androidx.compose.material3.SmallFloatingActionButton(
                            onClick = {
                                scope.launch { animateToTrueBottom() }
                            },
                            modifier = Modifier
                                .padding(end = 16.dp, bottom = 16.dp)
                                .windowInsetsPadding(WindowInsets.navigationBars),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom"
                            )
                        }
                    }
                }

                HorizontalDivider()
                // Model ready check (independent of compaction/generation — typing allowed even while compacting per user clarification)
                val isModelReady = when {
                    uiState.isRemoteEnabled && uiState.remoteModelId.isNotBlank() -> true
                    uiState.isRemoteEnabled -> false
                    else -> uiState.modelState is ModelManager.ModelState.Ready
                }
                // During compaction: allow typing but not sending/queuing. During generation: allow queuing.
                if (uiState.isCompacting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Compacting conversation — you can keep typing, sending paused until done…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                if (uiState.queuedMessageCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            text = "${uiState.queuedMessageCount} message${if (uiState.queuedMessageCount > 1) "s" else ""} queued — will send after current response",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            // Clear queue? For now just inform
                            scope.launch { snackbarHostState.showSnackbar("${uiState.queuedMessageCount} queued") }
                        }) { Text("View", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                InputBar(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        if (uiState.isCompacting) {
                            scope.launch { snackbarHostState.showSnackbar("Compacting — please wait before sending") }
                            return@InputBar
                        }
                        viewModel.sendMessageWithImages(inputText, pendingImages)
                        inputText = ""
                        pendingImages = emptyList()
                    },
                    onStop = { viewModel.stopGeneration() },
                    isGenerating = uiState.isGenerating,
                    isCompacting = uiState.isCompacting,
                    queuedCount = uiState.queuedMessageCount,
                    enabled = isModelReady,
                    pendingImages = pendingImages,
                    onAddImage = { showImageSourceSheet = true },
                    onRemoveImage = { uri -> pendingImages = pendingImages.filterNot { it == uri } },
                )
            }
        }
    }

    if (showImageSourceSheet) {
        AlertDialog(
            onDismissRequest = { showImageSourceSheet = false },
            title = { Text("Add image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { showImageSourceSheet = false; launchGallery() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Choose from gallery")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = { showImageSourceSheet = false; launchCamera() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Take photo")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (pendingImages.isNotEmpty()) {
                        Text(
                            text = "${pendingImages.size}/4 images selected. Tap X on preview to remove.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Vision needs Local Model enabled. Up to 4 images (1024px, JPEG).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showImageSourceSheet = false }) { Text("Close") } },
            dismissButton = {}
        )
    }

    // Full-screen image viewer
    if (viewerPath != null) {
        Dialog(
            onDismissRequest = { viewerPath = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = File(viewerPath!!),
                    contentDescription = "Full image",
                    modifier = Modifier.fillMaxWidth()
                )
                IconButton(
                    onClick = { viewerPath = null },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    if (actionMessage != null) {
        val mwa = actionMessage!!
        val msg = mwa.message
        val isUser = msg.role == "user"
        AlertDialog(
            onDismissRequest = { actionMessage = null },
            title = { Text(if (isUser) "Message Actions" else "Response Actions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(msg.content))
                            scope.launch { snackbarHostState.showSnackbar("Copied") }
                            actionMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Copy")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (isUser) {
                        TextButton(
                            onClick = {
                                editMessage = msg
                                editDraft = msg.content
                                actionMessage = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Edit & Resend")
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.regenerateFromMessage(msg.id)
                            actionMessage = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(if (isUser) "Resend" else "Regenerate")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionMessage = null }) { Text("Close") }
            },
            dismissButton = {}
        )
    }

    if (editMessage != null) {
        val isUserEdit = editMessage!!.role == "user"
        AlertDialog(
            onDismissRequest = { editMessage = null },
            title = { Text(if (isUserEdit) "Edit & Resend" else "Edit Message") },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter message...") },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editDraft.trim()
                        if (trimmed.isNotEmpty() && trimmed != editMessage!!.content) {
                            viewModel.editMessage(editMessage!!.id, trimmed)
                        }
                        editMessage = null
                    },
                    enabled = editDraft.trim().isNotEmpty()
                ) {
                    Text(if (isUserEdit) "SAVE & RESEND" else "SAVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { editMessage = null }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun ConversationDrawer(
    conversations: List<Conversation>,
    currentId: String?,
    onSelect: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onRename: (Conversation, String) -> Unit,
    onNew: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        drawerTonalElevation = 0.dp,
    ) {
        // Header inherits AMOLED black via surface + Quicksand via OrbalTheme typography
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = "Conversations",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

        NavigationDrawerItem(
            label = { Text("New Chat", style = MaterialTheme.typography.labelLarge, color = Color.White) },
            selected = false,
            onClick = onNew,
            icon = { Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedIconColor = Color.White,
                unselectedTextColor = Color.White,
                unselectedBadgeColor = Color.Transparent,
            ),
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        )

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(conversations) { conversation ->
                val isSelected = conversation.id == currentId
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = conversation.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    selected = isSelected,
                    onClick = { onSelect(conversation) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        selectedBadgeColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedContainerColor = Color.Transparent,
                        unselectedIconColor = Color.White,
                        unselectedTextColor = Color.White,
                        unselectedBadgeColor = Color.Transparent,
                    ),
                    badge = {
                        Row {
                            IconButton(onClick = {
                                renameTarget = conversation
                                renameText = conversation.title
                            }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "Rename",
                                    tint = Color.White,
                                )
                            }
                            IconButton(onClick = { onDelete(conversation) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Chat name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget?.let { onRename(it, renameText) }
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank(),
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
