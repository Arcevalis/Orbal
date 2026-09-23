package me.fss.orbal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Collapsible thinking block.
 * Shows reasoning (from <think>, <|channel>thought, Qwen analysis, or structured reasoning_content)
 * separated from the visible answer. The setting "Strip Thinking Tags" controls whether this
 * is rendered at all; when disabled, the engine preserves reasoning and this block shows it.
 */
@Composable
fun ThinkingBlock(
    reasoning: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (reasoning.isBlank()) return
    // Expanded while streaming so live reasoning is visible, collapsed when done.
    // remember(isStreaming) resets when streaming phase flips.
    var expanded by remember(isStreaming) { mutableStateOf(isStreaming) }

    val headerText = if (isStreaming) "Thinking..." else "Thought process"
    val preview = if (!expanded) {
        reasoning.lineSequence().firstOrNull()?.take(80)?.let {
            if (reasoning.length > 80) "$it..." else it
        } ?: reasoning.take(60)
    } else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
            .padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headerText,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
                if (!expanded && preview != null) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                text = if (expanded) "▼" else "▶",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
