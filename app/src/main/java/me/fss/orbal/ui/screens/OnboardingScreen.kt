package me.fss.orbal.ui.screens

import android.app.Application
import androidx.compose.ui.platform.LocalContext
import me.fss.orbal.di.LocalAppContainer
import me.fss.orbal.di.AppContainer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = AppContainer.Factory(LocalAppContainer.current, LocalContext.current.applicationContext as Application)),
) {
    val copyProgress by viewModel.copyProgress.collectAsStateWithLifecycle()
    val copyDone by viewModel.copyDone.collectAsStateWithLifecycle()
    val copyError by viewModel.copyError.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(targetState = step, label = "onboarding_step") { currentStep ->
            when (currentStep) {
                0 -> WelcomeStep()
                1 -> ModelSetupStep(
                    progress = copyProgress,
                    isDone = copyDone,
                    error = copyError,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Step indicators
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp),
        ) {
            repeat(2) { index ->
                Card(
                    modifier = Modifier.size(width = 32.dp, height = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (index <= step)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {}
            }
        }

        Button(
            onClick = {
                when (step) {
                    0 -> {
                        step = 1
                        viewModel.startModelCopy()
                    }
                    1 -> {
                        if (copyDone || copyError != null) {
                            viewModel.completeOnboarding("default")
                            onComplete()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = when (step) {
                1 -> copyDone || copyError != null
                else -> true
            }
        ) {
            Text(
                text = when (step) {
                    1 -> "Get Started"
                    else -> "Next"
                }
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Welcome to Orbal",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Your private AI assistant. All processing happens entirely on your device.\n\nNo internet connection. No tracking. No cloud. Your conversations stay yours.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelSetupStep(
    progress: Float,
    isDone: Boolean,
    error: String?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Memory,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Setting Up Your Model",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        if (error != null) {
            Text(
                text = "No Bundled Model",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "That\u2019s fine! You can import any GGUF model from Settings after setup.\n\nDownload models from HuggingFace and use the Import button.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (isDone) {
            Text(
                text = "Model ready!",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = "Preparing the AI model for first use. This only happens once.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
