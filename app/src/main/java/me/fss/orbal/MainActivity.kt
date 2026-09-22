package me.fss.orbal

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import me.fss.orbal.BuildConfig
import me.fss.orbal.data.repository.SettingsRepository
import me.fss.orbal.ui.navigation.NavGraph
import me.fss.orbal.ui.navigation.Routes
import me.fss.orbal.ui.theme.OrbalTheme
import me.fss.orbal.ui.theme.ThemeMode
import me.fss.orbal.utils.BiometricHelper
import me.fss.orbal.utils.SignatureVerifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var isAuthenticated by mutableStateOf(false)
    private var authRequired by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!BuildConfig.DEBUG && !SignatureVerifier(this).isSignedByTrustedCert()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Unverified App")
                .setMessage("This app has been modified or re-signed and cannot run to protect your data.")
                .setCancelable(false)
                .setPositiveButton("Exit") { _, _ -> finish() }
                .show()
            return
        }

        authRequired = settingsRepository.biometricLock
        applyWindowSecurityToggles()

        if (authRequired) {
            authenticateWithBiometric()
        } else {
            isAuthenticated = true
        }

        val themeModeState = mutableStateOf(
            try { ThemeMode.valueOf(settingsRepository.themeMode) }
            catch (_: Exception) { ThemeMode.AMOLED }
        )
        val accentColorState = mutableStateOf(settingsRepository.accentColor)
        val catppuccinAccentState = mutableStateOf(settingsRepository.catppuccinAccent)
        val draculaAccentState = mutableStateOf(settingsRepository.draculaAccent)
        val catppuccinFlavorState = mutableStateOf(settingsRepository.catppuccinFlavor)
        val ptyxisPaletteState = mutableStateOf(settingsRepository.ptyxisPalette)
        val monochromeAccentsState = mutableStateOf(settingsRepository.monochromeAccents)
        val appFontState = mutableStateOf(settingsRepository.appFont)
        val fontScaleState = mutableStateOf(settingsRepository.fontScale)

        setContent {
            val themeMode by themeModeState

            // Poll for theme changes when returning from Settings
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.flow.flow {
                    while (true) {
                        emit(settingsRepository.themeMode)
                        kotlinx.coroutines.delay(500)
                    }
                }.collect { _ ->
                    try {
                        val mode = ThemeMode.valueOf(settingsRepository.themeMode)
                        if (mode != themeModeState.value) {
                            themeModeState.value = mode
                        }
                        val newAccent = settingsRepository.accentColor
                        if (newAccent != accentColorState.value) {
                            accentColorState.value = newAccent
                        }
                        val newCatppuccin = settingsRepository.catppuccinAccent
                        if (newCatppuccin != catppuccinAccentState.value) {
                            catppuccinAccentState.value = newCatppuccin
                        }
                        val newDracula = settingsRepository.draculaAccent
                        if (newDracula != draculaAccentState.value) {
                            draculaAccentState.value = newDracula
                        }
                        val newCatppuccinFlavor = settingsRepository.catppuccinFlavor
                        if (newCatppuccinFlavor != catppuccinFlavorState.value) {
                            catppuccinFlavorState.value = newCatppuccinFlavor
                        }
                        val newPtyxisPalette = settingsRepository.ptyxisPalette
                        if (newPtyxisPalette != ptyxisPaletteState.value) {
                            ptyxisPaletteState.value = newPtyxisPalette
                        }
                        val newMonochromeAccents = settingsRepository.monochromeAccents
                        if (newMonochromeAccents != monochromeAccentsState.value) {
                            monochromeAccentsState.value = newMonochromeAccents
                        }
                        val newAppFont = settingsRepository.appFont
                        if (newAppFont != appFontState.value) {
                            appFontState.value = newAppFont
                        }
                        val newFontScale = settingsRepository.fontScale
                        if (newFontScale != fontScaleState.value) {
                            fontScaleState.value = newFontScale
                        }

                        // Keep security flags in sync with settings toggles.
                        applyWindowSecurityToggles()

                        authRequired = settingsRepository.biometricLock
                        if (!authRequired) {
                            isAuthenticated = true
                        }
                    } catch (_: Exception) {}
                }
            }


            OrbalTheme(
                themeMode = themeMode,
                accentColorKey = accentColorState.value,
                catppuccinFlavorKey = catppuccinFlavorState.value,
                catppuccinAccentKey = catppuccinAccentState.value,
                draculaAccentKey = draculaAccentState.value,
                ptyxisPaletteKey = ptyxisPaletteState.value,
                monochromeAccents = monochromeAccentsState.value,
                appFontKey = appFontState.value,
                fontScale = fontScaleState.value,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (isAuthenticated) {
                        Box(modifier = Modifier.safeDrawingPadding()) {
                            val navController = rememberNavController()
                            val startDest = if (settingsRepository.onboardingComplete) {
                                Routes.Chat.route
                            } else {
                                Routes.Onboarding.route
                            }
                            NavGraph(
                                navController = navController,
                                startDestination = startDest,
                            )
                        }
                    } else if (authRequired) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Authentication required",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        authRequired = settingsRepository.biometricLock
        applyWindowSecurityToggles()

        if (authRequired && !isAuthenticated) {
            authenticateWithBiometric()
        } else if (!authRequired) {
            isAuthenticated = true
        }
    }

    override fun onPause() {
        super.onPause()
        val shouldAutoLock =
            settingsRepository.biometricLock && settingsRepository.autoLockOnBackgroundEnabled
        if (shouldAutoLock) {
            isAuthenticated = false
        }
    }

    private fun authenticateWithBiometric() {
        val helper = BiometricHelper(this)

        when (helper.canAuthenticate()) {
            BiometricHelper.BiometricStatus.AVAILABLE -> {
                helper.authenticate(
                    onSuccess = { isAuthenticated = true },
                    onFailure = { _, _ -> },
                    onError = { code, _ ->
                        if (code != 10 && code != 13) {
                            isAuthenticated = true
                        }
                    }
                )
            }
            BiometricHelper.BiometricStatus.NOT_ENROLLED -> {
                isAuthenticated = true
            }
            else -> {
                isAuthenticated = true
            }
        }
    }

    private fun applyWindowSecurityToggles() {
        if (settingsRepository.screenshotProtectionEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
