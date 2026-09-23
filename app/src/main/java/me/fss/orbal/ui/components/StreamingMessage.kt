package me.fss.orbal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

@Composable
fun StreamingMessage(
    partialResponse: String,
    partialReasoning: String = "",
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = screenWidth * 0.85f

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = maxWidth)
        ) {
            DisableSelection {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (partialReasoning.isNotBlank()) {
                        ThinkingBlock(reasoning = partialReasoning, isStreaming = true)
                    }
                    if (partialResponse.isNotBlank()) {
                        Text(
                            text = "$partialResponse▍",
                            style = MaterialTheme.typography.bodyLarge,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    } else if (partialReasoning.isBlank()) {
                        // Neither reasoning nor answer yet — show thinking indicator
                        Text(
                            text = "Thinking...▍",
                            style = MaterialTheme.typography.bodyLarge,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}
