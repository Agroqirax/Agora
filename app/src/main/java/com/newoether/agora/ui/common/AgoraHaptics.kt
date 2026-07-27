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
    fun action()
    fun selection()
    fun longPress()
    fun success()
    fun reject()
    fun generationStopped()
    fun startAnsweringTexture()
    fun stopAnsweringTexture()
}

object NoOpAgoraHaptics : AgoraHaptics {
    override fun action() = Unit
    override fun selection() = Unit
    override fun longPress() = Unit
    override fun success() = Unit
    override fun reject() = Unit
    override fun generationStopped() = Unit
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
    private var answeringTextureActive = false

    override fun action() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    override fun selection() = perform(HapticFeedbackConstants.CLOCK_TICK)

    override fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    override fun success() = perform(confirmFeedback())

    override fun reject() = perform(rejectFeedback())

    override fun generationStopped() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    override fun startAnsweringTexture() {
        if (!isAllowed() || answeringTextureActive) return
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        answeringTextureActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
            // Grain, not a beat. Three short pulses of UNEQUAL strength and spacing per ~43ms
            // cycle read as a texture; the previous single 16ms pulse every 48ms read as a
            // metronome tick. Amplitudes are deliberately near the low end of perceptible
            // (5-8 of 255) so it stays at the edge of awareness.
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(5L, 9L, 4L, 11L, 6L, 8L),
                    intArrayOf(8, 0, 5, 0, 7, 0),
                    0
                )
            )
        } else {
            // Without amplitude control the amplitude array is ignored and every pulse fires at
            // full strength, so lightness has to come from duration alone — 3ms pulses are about
            // as soft as a timing-only pattern gets.
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0L, 3L, 12L, 3L, 14L, 3L, 10L), 0)
        }
    }

    override fun stopAnsweringTexture() {
        if (!answeringTextureActive) return
        answeringTextureActive = false
        vibrator?.cancel()
    }

    private fun perform(type: Int) {
        if (isAllowed()) {
            view.performHapticFeedback(type)
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
}

private fun Context.findVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
