package me.fss.orbal.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.fss.orbal.ai.SystemPrompts
import me.fss.orbal.ui.theme.AppFont
import me.fss.orbal.ui.theme.CatppuccinAccent
import me.fss.orbal.ui.theme.CatppuccinFlavor
import me.fss.orbal.ui.theme.DraculaAccent
import me.fss.orbal.ui.theme.PtyxisPalette
import me.fss.orbal.ui.theme.ThemeMode
import me.fss.orbal.ui.theme.accentColors
import me.fss.orbal.ui.theme.catppuccinAccentColor
import me.fss.orbal.ui.theme.catppuccinFlavorFromKey
import me.fss.orbal.ui.theme.draculaAccentColor
import me.fss.orbal.utils.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit = {},
    onNavigateToHelp: () -> Unit = {},
    onNavigateToKnowledgeBase: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importModel(it) } }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportChats(it) } }

    val importChatsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importChats(it) } }

    var showClearDialog by remember { mutableStateOf(false) }
    var clearConfirmText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // === THEME ===
            SectionHeader("Appearance")
            ThemeMode.entries.forEach { mode ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = uiState.themeMode == mode.name,
                            onClick = { viewModel.setTheme(mode) },
                            role = Role.RadioButton,
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.themeMode == mode.name)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = uiState.themeMode == mode.name, onClick = null)
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }

            // === ACCENT COLOUR ===
            SectionHeader("Accent Colour")
            when (uiState.themeMode) {
                ThemeMode.CATPPUCCIN.name -> {
                    val selectedFlavor = catppuccinFlavorFromKey(uiState.catppuccinFlavor)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CatppuccinFlavor.entries.forEach { flavor ->
                            Button(
                                onClick = { viewModel.setCatppuccinFlavor(flavor.key) },
                                colors = if (selectedFlavor == flavor) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                                modifier = Modifier.weight(1f),
                            ) { Text(flavor.displayName) }
                        }
                    }
                    val catScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(catScrollState)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CatppuccinAccent.entries.forEach { accent ->
                            val isSelected = uiState.catppuccinAccent == accent.key
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(catppuccinAccentColor(selectedFlavor, accent))
                                    .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { viewModel.setCatppuccinAccent(accent.key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    MonochromeAccentsToggle(uiState.monochromeAccents) { viewModel.setMonochromeAccents(it) }
                }
                ThemeMode.DRACULA.name -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        DraculaAccent.entries.forEach { accent ->
                            val isSelected = uiState.draculaAccent == accent.key
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(draculaAccentColor(dark = true, accent = accent))
                                    .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { viewModel.setDraculaAccent(accent.key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    MonochromeAccentsToggle(uiState.monochromeAccents) { viewModel.setMonochromeAccents(it) }
                }
                ThemeMode.PTYXIS.name -> {
                    val ptyxisScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(ptyxisScrollState)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PtyxisPalette.entries.forEach { palette ->
                            val isSelected = uiState.ptyxisPalette == palette.key
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(palette.primaryC))
                                    .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { viewModel.setPtyxisPalette(palette.key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    Text(
                        PtyxisPalette.entries.find { it.key == uiState.ptyxisPalette }?.displayName ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        accentColors.forEach { accent ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(accent.seed)
                                    .then(if (uiState.accentColor == accent.key) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { viewModel.setAccentColor(accent.key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiState.accentColor == accent.key) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // === FONT ===
            SectionHeader("Font")
            val fontScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(fontScrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppFont.entries.forEach { font ->
                    Button(
                        onClick = { viewModel.setAppFont(font.key) },
                        colors = if (uiState.appFont == font.key) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                    ) { Text(font.displayName) }
                }
            }
            var fontScaleValue by remember { mutableFloatStateOf(uiState.fontScale) }
            ParamSlider(
                label = "Text Size: ${(fontScaleValue * 100).toInt()}%",
                description = "Scales all app text. 100% = default.",
                value = fontScaleValue,
                onValueChange = { fontScaleValue = it },
                onValueChangeFinished = { viewModel.setFontScale(fontScaleValue) },
                valueRange = 0.85f..1.3f,
                steps = 8,
            )

            HorizontalDivider()

            // === MODEL ===
            SectionHeader("Model")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (uiState.activeModel != null) {
                        Text("Active: ${uiState.activeModel!!.name}", style = MaterialTheme.typography.titleSmall)
                        Text("Size: ${FileUtils.formatFileSize(uiState.activeModel!!.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("No model selected", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            uiState.models.forEach { model ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (model.id == uiState.activeModel?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${FileUtils.formatFileSize(model.sizeBytes)} | ctx: ${model.contextSize}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            if (model.id != uiState.activeModel?.id) {
                                TextButton(onClick = { viewModel.selectModel(model.id) }) { Text("Use") }
                            }
                            if (!model.isBundled) {
                                IconButton(onClick = { viewModel.deleteModel(model.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick = { importModelLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Import GGUF Model")
            }

            HorizontalDivider()

            // === REMOTE MODEL (Local — OpenAI-compatible) ===
            SectionHeader("Remote Model")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Connects to LM Studio / Ollama / vLLM at http://host:1234/v1. Bypasses on-device GGUF when enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Emulator: 10.0.2.2:1234  •  Phone: 192.168.x.x:1234 — enable server + CORS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use Remote Model")
                    Text(
                        if (uiState.remoteEnabled) "Using local server" else "Using on-device GGUF",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = uiState.remoteEnabled, onCheckedChange = { viewModel.setRemoteEnabled(it) })
            }
            // Always show config so user can set it before toggling
            OutlinedTextField(
                value = uiState.remoteBaseUrl,
                onValueChange = { viewModel.setRemoteBaseUrl(it) },
                label = { Text("Base URL") },
                placeholder = { Text("http://10.0.2.2:1234/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.remoteApiKey,
                onValueChange = { viewModel.setRemoteApiKey(it) },
                label = { Text("API Key (optional)") },
                placeholder = { Text("leave empty for local servers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.fetchRemoteModels() },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isFetchingRemoteModels && uiState.remoteBaseUrl.isNotBlank(),
                ) {
                    if (uiState.isFetchingRemoteModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Refresh Models")
                    }
                }
                Button(
                    onClick = { viewModel.testRemoteConnection() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    enabled = uiState.remoteBaseUrl.isNotBlank(),
                ) { Text("Test") }
            }
            // Model picker
            if (uiState.remoteModels.isNotEmpty()) {
                var remoteModelExpanded by remember { mutableStateOf(false) }
                val currentRemoteLabel = uiState.remoteModelId.ifEmpty { "Select model" }
                ExposedDropdownMenuBox(expanded = remoteModelExpanded, onExpandedChange = { remoteModelExpanded = it }) {
                    OutlinedTextField(
                        value = currentRemoteLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Remote Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = remoteModelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = remoteModelExpanded, onDismissRequest = { remoteModelExpanded = false }) {
                        uiState.remoteModels.forEach { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = { viewModel.setRemoteModelId(modelId); remoteModelExpanded = false },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = uiState.remoteModelId,
                    onValueChange = { viewModel.setRemoteModelId(it) },
                    label = { Text("Remote Model ID") },
                    placeholder = { Text("e.g. google_gemma-4-e2b-it") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Tap Refresh Models to auto-discover, or type the model ID manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.remoteStatus != null) {
                SelectionContainer {
                    Text(
                        uiState.remoteStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.remoteStatus!!.startsWith("✗") || uiState.remoteStatus!!.startsWith("Failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (uiState.remoteEnabled && uiState.remoteModelId.isBlank()) {
                Text(
                    "⚠ Remote is ON but no model selected — refresh models or enter ID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // === TOOLS (Standard) ===
            SectionHeader("Tools")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tools run locally; only web search uses the network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Tools")
                    Text(if (uiState.toolsEnabled) "Model can use tools" else "Tools disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.toolsEnabled, onCheckedChange = { viewModel.setToolsEnabled(it) })
            }
            if (uiState.toolsEnabled) {
                // Per-tool toggles — name only, keeps list tight
                me.fss.orbal.ai.tools.ToolRegistry.AVAILABLE_TOOLS.forEach { tool ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tool.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = uiState.enabledTools.contains(tool.id), onCheckedChange = { viewModel.toggleTool(tool.id, it) })
                    }
                }
                var maxToolValue by remember { mutableFloatStateOf(uiState.maxToolCalls.toFloat()) }
                ParamSlider(
                    label = "Max Tool Steps: ${maxToolValue.toInt()}",
                    description = "Tool loop limit per reply.",
                    value = maxToolValue,
                    onValueChange = { maxToolValue = it },
                    onValueChangeFinished = { viewModel.setMaxToolCalls(maxToolValue.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                )
            }

            HorizontalDivider()

            // === KNOWLEDGE BASE (Standard) ===
            SectionHeader("Knowledge Base")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Searches your docs (top 5) for RAG.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onNavigateToKnowledgeBase, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors()) {
                Text("Open Knowledge Base")
            }

            HorizontalDivider()

            // === PERFORMANCE ===
            SectionHeader("Performance")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("GPU Acceleration (Vulkan)")
                    Text(
                        if (uiState.gpuDeviceName.isNotEmpty()) "GPU: ${uiState.gpuDeviceName}"
                        else "No compatible GPU detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = uiState.useGpu, onCheckedChange = { viewModel.setUseGpu(it) })
            }
            if (uiState.useGpu) {
                var gpuLayersValue by remember { mutableFloatStateOf(uiState.gpuLayers.toFloat()) }
                ParamSlider(
                    label = "GPU Layers: ${gpuLayersValue.toInt()}" + if (gpuLayersValue.toInt() >= 99) " (all)" else "",
                    description = "99 = all. Lower if loading fails.",
                    value = gpuLayersValue,
                    onValueChange = { gpuLayersValue = it },
                    onValueChangeFinished = { viewModel.setGpuLayers(gpuLayersValue.toInt()) },
                    valueRange = 1f..99f,
                    steps = 97,
                )
            }
            val maxThreads = remember { Runtime.getRuntime().availableProcessors() }
            var threadsValue by remember { mutableFloatStateOf(uiState.numThreads.toFloat()) }
            ParamSlider(
                label = "CPU Threads: ${threadsValue.toInt()}",
                description = "More = faster; too many throttles.",
                value = threadsValue,
                onValueChange = { threadsValue = it },
                onValueChangeFinished = { viewModel.setNumThreads(threadsValue.toInt()) },
                valueRange = 1f..maxThreads.toFloat(),
                steps = (maxThreads - 2).coerceAtLeast(0),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Memory-Map Model")
                    Text("On-demand pages — lower RAM.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.useMmap, onCheckedChange = { viewModel.setUseMmap(it) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lock Model in RAM")
                    Text("Prevents swap; may fail silently.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.useMlock, onCheckedChange = { viewModel.setUseMlock(it) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Quantized KV Cache (experimental)")
                    Text("Halves long-chat memory. Off if load fails.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.kvCacheQ8, onCheckedChange = { viewModel.setKvCacheQ8(it) })
            }
            Text(
                "Applies next load.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Which ggml backend plugin actually won the runtime scoring on this
            // device. Seven CPU variants ship in the APK and only one is chosen;
            // without this there is no way to tell a phone running the fast
            // dotprod/i8mm kernels from one that fell back to the armv8.0
            // baseline, which is several times slower on quantized models.
            // Selectable so it can be pasted into a bug report.
            if (uiState.backendInfo.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Active backend", style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(
                        uiState.backendInfo.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Include this when reporting slow performance. DOTPROD and MATMUL_INT8 " +
                        "mean the fast quantized-matmul kernels are in use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // === SAMPLING PARAMETERS ===
            SectionHeader("Sampling Parameters")

            // Temperature
            var tempValue by remember { mutableFloatStateOf(uiState.temperature) }
            ParamSlider(
                label = "Temperature: ${String.format("%.2f", tempValue)}",
                description = "Lower = focused, higher = creative.",
                value = tempValue,
                onValueChange = { tempValue = it },
                onValueChangeFinished = { viewModel.setTemperature(tempValue) },
                valueRange = 0.1f..2.0f,
                steps = 38,
            )

            // Top-P (nucleus sampling)
            var topPValue by remember { mutableFloatStateOf(uiState.topP) }
            ParamSlider(
                label = "Top-P: ${String.format("%.2f", topPValue)}",
                description = "Nucleus threshold. Lower = focused.",
                value = topPValue,
                onValueChange = { topPValue = it },
                onValueChangeFinished = { viewModel.setTopP(topPValue) },
                valueRange = 0.1f..1.0f,
                steps = 18,
            )

            // Top-K
            var topKValue by remember { mutableIntStateOf(uiState.topK) }
            ParamSlider(
                label = "Top-K: $topKValue",
                description = "Limit to K most likely tokens. 0 = off.",
                value = topKValue.toFloat(),
                onValueChange = { topKValue = it.toInt() },
                onValueChangeFinished = { viewModel.setTopK(topKValue) },
                valueRange = 0f..100f,
                steps = 20,
            )

            // Min-P
            var minPValue by remember { mutableFloatStateOf(uiState.minP) }
            ParamSlider(
                label = "Min-P: ${String.format("%.2f", minPValue)}",
                description = "Adaptive alternative to Top-K.",
                value = minPValue,
                onValueChange = { minPValue = it },
                onValueChangeFinished = { viewModel.setMinP(minPValue) },
                valueRange = 0.0f..0.5f,
                steps = 10,
            )

            // Repeat Penalty
            var repeatValue by remember { mutableFloatStateOf(uiState.repeatPenalty) }
            ParamSlider(
                label = "Repeat Penalty: ${String.format("%.2f", repeatValue)}",
                description = "1.0 = off. Higher = less repetition.",
                value = repeatValue,
                onValueChange = { repeatValue = it },
                onValueChangeFinished = { viewModel.setRepeatPenalty(repeatValue) },
                valueRange = 1.0f..2.0f,
                steps = 20,
            )

            // Max tokens — uncapped for large outputs
            Text("Max Tokens: ${uiState.maxTokens}", style = MaterialTheme.typography.bodyMedium)
            Text("Max tokens per response.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(256, 512, 1024, 2048).forEach { value ->
                    Button(
                        onClick = { viewModel.setMaxTokens(value) },
                        colors = if (uiState.maxTokens == value) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f),
                    ) { Text("$value") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4096, 8192, 16384).forEach { value ->
                    Button(
                        onClick = { viewModel.setMaxTokens(value) },
                        colors = if (uiState.maxTokens == value) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f),
                    ) { Text("$value") }
                }
            }

            // Context size — server overrides local when connected
            var ctxValue by remember { mutableFloatStateOf(uiState.contextSize.toFloat()) }
            ParamSlider(
                label = "Context Size: ${ctxValue.toInt()}${if (uiState.serverCtx > 0) " · server ${uiState.serverCtx}" else ""}",
                description = if (uiState.remoteEnabled && uiState.serverCtx > 0) "Local fallback. Server value used." else "History the model can see.",
                value = ctxValue,
                onValueChange = { ctxValue = it },
                onValueChangeFinished = { viewModel.setContextSize(ctxValue.toInt()) },
                valueRange = 512f..32768f,
                steps = 63,
            )
            if (uiState.remoteEnabled) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (uiState.serverCtx > 0) "Effective: ${uiState.effectiveCtx} tokens" else "Effective: ${uiState.contextSize} tokens — tap Refresh",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { viewModel.refreshServerCtx() }, modifier = Modifier.fillMaxWidth(), enabled = uiState.remoteBaseUrl.isNotBlank() && uiState.remoteModelId.isNotBlank()) {
                            Text("Refresh")
                        }
                    }
                }
            }

            HorizontalDivider()

            // === COMPACTION (Standard) — opt-in, dynamic to effectiveCtx ===
            SectionHeader("Compaction")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Summarizes old messages when context exceeds threshold. Keeps recent ${uiState.compactionKeepRecent} verbatim.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Auto-Compaction")
                    Text(if (uiState.compactionEnabled) "On — at ~${(uiState.compactionThreshold*100).toInt()}% of ${uiState.effectiveCtx}" else "Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.compactionEnabled, onCheckedChange = { viewModel.setCompactionEnabled(it) })
            }
            if (uiState.compactionEnabled) {
                var thresh by remember { mutableFloatStateOf(uiState.compactionThreshold) }
                ParamSlider(
                    label = "Threshold: ${(thresh*100).toInt()}% (${(uiState.effectiveCtx * thresh).toInt()} tokens)",
                    description = "Compact when input exceeds this %.",
                    value = thresh,
                    onValueChange = { thresh = it },
                    onValueChangeFinished = { viewModel.setCompactionThreshold(thresh) },
                    valueRange = 0.6f..0.95f,
                    steps = 7,
                )
                var keep by remember { mutableIntStateOf(uiState.compactionKeepRecent) }
                ParamSlider(
                    label = "Keep Recent: $keep",
                    description = "Recent messages kept verbatim.",
                    value = keep.toFloat(),
                    onValueChange = { keep = it.toInt() },
                    onValueChangeFinished = { viewModel.setCompactionKeepRecent(keep) },
                    valueRange = 4f..20f,
                    steps = 16,
                )
            }

            HorizontalDivider()

            // === THINKING TOGGLE ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Strip Thinking Tags")
                    Text("Hide <think> blocks from model output", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.disableThinking, onCheckedChange = { viewModel.setDisableThinking(it) })
            }

            HorizontalDivider()

            // === SYSTEM PROMPT ===
            SectionHeader("System Prompt")
            var promptExpanded by remember { mutableStateOf(false) }
            var customPrompt by rememberSaveable { mutableStateOf(uiState.customSystemPrompt) }
            ExposedDropdownMenuBox(expanded = promptExpanded, onExpandedChange = { promptExpanded = it }) {
                OutlinedTextField(
                    value = SystemPrompts.getLabel(uiState.systemPromptKey),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = promptExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = promptExpanded, onDismissRequest = { promptExpanded = false }) {
                    SystemPrompts.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { viewModel.setSystemPrompt(option.key); promptExpanded = false }
                        )
                    }
                }
            }
            if (uiState.systemPromptKey == "translator") {
                var fromExpanded by remember { mutableStateOf(false) }
                var toExpanded by remember { mutableStateOf(false) }
                val fromLabel = SystemPrompts.languages.find { it.code == uiState.translatorFrom }?.label ?: "English"
                val toLabel = SystemPrompts.languages.find { it.code == uiState.translatorTo }?.label ?: "Spanish"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExposedDropdownMenuBox(
                        expanded = fromExpanded,
                        onExpandedChange = { fromExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = fromLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("From") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fromExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = fromExpanded, onDismissRequest = { fromExpanded = false }) {
                            SystemPrompts.languages.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.label) },
                                    onClick = { viewModel.setTranslatorFrom(lang.code); fromExpanded = false },
                                )
                            }
                        }
                    }
                    ExposedDropdownMenuBox(
                        expanded = toExpanded,
                        onExpandedChange = { toExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = toLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("To") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = toExpanded, onDismissRequest = { toExpanded = false }) {
                            SystemPrompts.languages.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.label) },
                                    onClick = { viewModel.setTranslatorTo(lang.code); toExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
            if (uiState.systemPromptKey == "custom") {
                OutlinedTextField(value = customPrompt, onValueChange = { customPrompt = it; viewModel.setCustomSystemPrompt(it) }, label = { Text("Custom System Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LaTeX Math Hints")
                    Text("Instruct the model to use \$...\$ notation for math", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.mathLatexHints, onCheckedChange = { viewModel.setMathLatexHints(it) })
            }

            HorizontalDivider()

            // === SECURITY ===
            SectionHeader("Security")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Biometric Lock")
                Switch(
                    checked = uiState.biometricLock,
                    onCheckedChange = { viewModel.setBiometricLock(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-Lock on Background")
                Switch(
                    checked = uiState.biometricLock && uiState.autoLockOnBackground,
                    enabled = uiState.biometricLock,
                    onCheckedChange = { viewModel.setAutoLockOnBackground(it) }
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Screenshot Protection")
                Switch(
                    checked = uiState.screenshotProtectionEnabled,
                    onCheckedChange = { viewModel.setScreenshotProtectionEnabled(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tapjacking Protection")
                Switch(
                    checked = uiState.tapjackingProtectionEnabled,
                    onCheckedChange = { viewModel.setTapjackingProtectionEnabled(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Accessibility Data Sensitivity")
                Switch(
                    checked = uiState.sensitiveDataAccessibilityEnabled,
                    onCheckedChange = { viewModel.setSensitiveDataAccessibilityEnabled(it) }
                )
            }
            Text(
                "Applies on Android 16+ to mark chat content as sensitive for accessibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Secure Storage Backend")
                Text(uiState.secureStorageBackend)
            }

            // === DATA ===
            SectionHeader("Data Management")
            Button(onClick = { exportLauncher.launch("orbal_export.json") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Export Chats as JSON")
            }
            Button(onClick = { importChatsLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Import Chats from JSON")
            }
            Button(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Clear All Chats")
            }

            HorizontalDivider()

            // === ABOUT & HELP ===
            Button(onClick = onNavigateToHelp, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors()) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Help & Model Guide")
            }
            Button(onClick = onNavigateToAbout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors()) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("About Orbal")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Model import loading dialog
    if (uiState.isImportingModel) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing Model") },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Text("Copying and validating model file\u2026\nThis may take a moment for large models.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
        )
    }

    // Clear all dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false; clearConfirmText = "" },
            title = { Text("Clear All Chats") },
            text = {
                Column {
                    Text("This will permanently delete all conversations and messages. This cannot be undone.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Type DELETE to confirm:")
                    OutlinedTextField(value = clearConfirmText, onValueChange = { clearConfirmText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAllChats(); showClearDialog = false; clearConfirmText = "" }, enabled = clearConfirmText == "DELETE") {
                    Text("Confirm", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false; clearConfirmText = "" }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun MonochromeAccentsToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Monochrome Accents")
            Text("Use a single accent colour for primary, secondary, and tertiary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ParamSlider(
    label: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
