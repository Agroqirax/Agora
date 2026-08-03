package com.newoether.agora.ui.common

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import com.newoether.agora.service.AppForegroundTracker

@Stable
interface AgoraHaptics {
    fun tap()
    fun selection()
    fun longPress()
    fun confirm()
    fun reject()
    fun destructive()
    fun startAnsweringTexture()
    fun stopAnsweringTexture()

    @Deprecated("Use tap()")
    fun action() = tap()

    @Deprecated("Use confirm()")
    fun success() = confirm()

    @Deprecated("Use destructive()")
    fun generationStopped() = destructive()
}

object NoOpAgoraHaptics : AgoraHaptics {
    override fun tap() = Unit
    override fun selection() = Unit
    override fun longPress() = Unit
    override fun confirm() = Unit
    override fun reject() = Unit
    override fun destructive() = Unit
    override fun startAnsweringTexture() = Unit
    override fun stopAnsweringTexture() = Unit
}

val LocalAgoraHaptics = compositionLocalOf<AgoraHaptics> { NoOpAgoraHaptics }

@Composable
fun rememberAgoraHaptics(enabled: Boolean): AgoraHaptics {
    val view = LocalView.current
    val enabledState = rememberUpdatedState(enabled)
    val haptics = remember(view) {
        PlatformAgoraHaptics(view) { enabledState.value }
    }
    DisposableEffect(haptics) {
        val listener: (Boolean) -> Unit = { inForeground ->
            if (!inForeground) haptics.stopAnsweringTexture()
        }
        AppForegroundTracker.addListener(listener)
        onDispose {
            AppForegroundTracker.removeListener(listener)
            haptics.stopAnsweringTexture()
        }
    }
    return haptics
}

private class PlatformAgoraHaptics(
    private val view: View,
    private val enabled: () -> Boolean
) : AgoraHaptics {
    private val vibrator: Vibrator? = view.context.applicationContext.findVibrator()
    private var answeringTextureRequested = false
    private var answeringTextureActive = false
    private val resumeAnsweringTexture = Runnable {
        textureResumeScheduled = false
        if (answeringTextureRequested) startAnsweringTextureNow()
    }
    private var textureResumeScheduled = false

    override fun tap() = performDiscrete(HapticFeedbackConstants.VIRTUAL_KEY)

    override fun selection() = performDiscrete(HapticFeedbackConstants.CLOCK_TICK)

    override fun longPress() = performDiscrete(HapticFeedbackConstants.LONG_PRESS)

    override fun confirm() = performDiscrete(confirmFeedback())

    override fun reject() = performDiscrete(rejectFeedback())

    override fun destructive() {
        stopAnsweringTexture()
        performDiscrete(HapticFeedbackConstants.CONTEXT_CLICK, resumeTexture = false)
    }

    override fun startAnsweringTexture() {
        answeringTextureRequested = true
        if (!textureResumeScheduled) startAnsweringTextureNow()
    }

    private fun startAnsweringTextureNow() {
        if (!answeringTextureRequested || !isAllowed() || answeringTextureActive) return
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        answeringTextureActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
            // Keep the continuous answering texture, but at a low duty cycle. Starting each cycle
            // with silence prevents a resume from producing an immediate burst after a discrete
            // tap, while the unequal pair avoids a mechanical metronome feel.
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(80L, 3L, 24L, 2L, 91L),
                    intArrayOf(0, 4, 0, 3, 0),
                    0
                )
            )
        } else {
            // Timing-only devices render every pulse at full strength. Sparse 1-2ms pulses keep
            // the feature continuous without the near-solid full-power buzz of the old 43ms loop.
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(80L, 2L, 40L, 1L, 77L), 0)
        }
    }

    override fun stopAnsweringTexture() {
        answeringTextureRequested = false
        view.removeCallbacks(resumeAnsweringTexture)
        textureResumeScheduled = false
        if (!answeringTextureActive) return
        answeringTextureActive = false
        vibrator?.cancel()
    }

    private fun performDiscrete(type: Int, resumeTexture: Boolean = true) {
        if (!isAllowed()) return
        view.removeCallbacks(resumeAnsweringTexture)
        textureResumeScheduled = false
        if (answeringTextureActive) {
            answeringTextureActive = false
            vibrator?.cancel()
        }
        view.performHapticFeedback(type)
        if (resumeTexture && answeringTextureRequested) {
            textureResumeScheduled = true
            view.postDelayed(resumeAnsweringTexture, TEXTURE_RESUME_DELAY_MS)
        }
    }

    private fun isAllowed(): Boolean = enabled() && AppForegroundTracker.isInForeground

    private fun confirmFeedback(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }

    private fun rejectFeedback(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }

    private companion object {
        const val TEXTURE_RESUME_DELAY_MS = 160L
    }
}

private fun Context.findVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
