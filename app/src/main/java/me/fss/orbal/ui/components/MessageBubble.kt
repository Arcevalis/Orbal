package me.fss.orbal.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.fss.orbal.data.local.entities.MessageAttachment
import java.io.File
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Box

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    attachments: List<MessageAttachment> = emptyList(),
    reasoningContent: String? = null,
    onImageClick: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    onStopSpeaking: (() -> Unit)? = null,
    isSpeaking: Boolean = false,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = screenWidth * 0.85f

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { onLongPress?.invoke() },
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Thinking block — OGAM-style reasoning above answer (only for assistant)
                if (!isUser && !reasoningContent.isNullOrBlank()) {
                    ThinkingBlock(reasoning = reasoningContent, isStreaming = false)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // Image attachments — show grid before text
                if (attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Simple responsive grid: 1 image full width, 2+ in 2 columns
                        val columns = if (attachments.size == 1) 1 else 2
                        // For 1 column, height ~200dp; for 2 columns, ~120dp each
                        attachments.chunked(columns).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                row.forEach { att ->
                                    val file = File(att.localPath)
                                    AsyncImage(
                                        model = file,
                                        contentDescription = "Image ${att.width}x${att.height}",
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(if (attachments.size == 1) 200.dp else 120.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { onImageClick?.invoke(att.localPath) },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                // Fill remaining if uneven row
                                if (row.size < columns) {
                                    repeat(columns - row.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    if (content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (content.isNotBlank()) {
                    DisableSelection {
                        if (isUser) {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                            )
                        } else {
                            MarkdownText(
                                content = content,
                                textColor = Color.White,
                            )
                        }
                    }
                } else if (attachments.isNotEmpty()) {
                    // Image-only message fallback — already shown images
                } else {
                    // Empty content case (should not happen) — show placeholder
                    Text(
                        text = if (isUser) "(image)" else content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                }

                // Action row for assistant messages — small icons like speaker (regen + TTS)
                if (!isUser && (onSpeak != null || onRegenerate != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (onRegenerate != null) {
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Regenerate",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (onSpeak != null) {
                            IconButton(
                                onClick = { if (isSpeaking) onStopSpeaking?.invoke() else onSpeak() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (isSpeaking) "Stop" else "Read aloud",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
