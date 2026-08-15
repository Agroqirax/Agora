package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.ui.chat.PdfPageSelectDialog
import com.newoether.agora.ui.chat.VideoSliceDialog
import com.newoether.agora.ui.common.OpenAiServiceTierControlPanel
import com.newoether.agora.ui.common.ThinkingControlPanel
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBottomBarOverlayHost(
    showThinkingSheet: Boolean,
    onDismissThinkingSheet: () -> Unit,
    thinkingEnabled: Boolean,
    thinkingLevel: String,
    thinkingBudgetEnabled: Boolean,
    thinkingBudgetTokens: Int,
    onThinkingToggle: (Boolean) -> Unit,
    onThinkingLevelChange: (String) -> Unit,
    onThinkingBudgetEnabledChange: (Boolean) -> Unit,
    onThinkingBudgetTokensChange: (Int) -> Unit,
    selectedModel: String,
    customProviders: List<CustomProviderConfig>,
    showOpenAiServiceTierSheet: Boolean,
    openAiServiceTierAvailable: Boolean,
    onDismissOpenAiServiceTierSheet: () -> Unit,
    openAiServiceTierEnabled: Boolean,
    openAiServiceTier: String,
    onOpenAiServiceTierToggle: (Boolean) -> Unit,
    onOpenAiServiceTierChange: (String) -> Unit,
    internalCameraPath: String?,
    onInternalCameraPathChange: (String?) -> Unit,
    composer: ChatComposerState,
    pdfViewerSelection: Set<Int>,
    onTogglePdfSelection: ((Int) -> Unit)?,
    onPdfPreviewSelect: ((List<String>, Int) -> Unit)?,
    onInitPdfSelection: ((Set<Int>) -> Unit)?,
) {
    if (showThinkingSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismissThinkingSheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            DialogWindowEdgeToEdge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                ThinkingControlPanel(
                    enabled = thinkingEnabled,
                    level = thinkingLevel,
                    budgetEnabled = thinkingBudgetEnabled,
                    budgetTokens = thinkingBudgetTokens,
                    onEnabledChange = onThinkingToggle,
                    onLevelChange = onThinkingLevelChange,
                    onBudgetEnabledChange = onThinkingBudgetEnabledChange,
                    onBudgetTokensChange = onThinkingBudgetTokensChange,
                    providerName = providerDisplayName(
                        com.newoether.agora.model.ModelId.parse(selectedModel).providerName,
                        customProviders,
                    ),
                    animateSections = true
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showOpenAiServiceTierSheet && openAiServiceTierAvailable) {
        ModalBottomSheet(
            onDismissRequest = onDismissOpenAiServiceTierSheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DialogWindowEdgeToEdge()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                OpenAiServiceTierControlPanel(
                    enabled = openAiServiceTierEnabled,
                    tier = openAiServiceTier,
                    onEnabledChange = onOpenAiServiceTierToggle,
                    onTierChange = onOpenAiServiceTierChange,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    internalCameraPath?.let { privatePath ->
        InternalCameraCaptureDialog(
            targetPath = privatePath,
            onCaptured = {
                onInternalCameraPathChange(null)
                composer.completeCameraCapture(privatePath, captured = true)
            },
            onCancelled = {
                onInternalCameraPathChange(null)
                composer.completeCameraCapture(privatePath, captured = false)
            },
            onFailure = {
                onInternalCameraPathChange(null)
                composer.completeCameraCapture(privatePath, captured = false)
                composer.reportCameraPreparationFailure()
            },
        )
    }

    // Attachment rejection / camera failure dialog
    if (composer.rejectedMessage != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { composer.rejectedMessage = null },
            title = { Text(stringResource(composer.rejectedTitleRes), fontWeight = FontWeight.Bold) },
            text = { Text(composer.rejectedMessage!!) },
            confirmButton = {
                TextButton(onClick = { composer.rejectedMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // PDF page selection dialog
    if (composer.showPdfPageDialog && composer.pendingPdfUri != null) {
        PdfPageSelectDialog(
            totalPages = composer.pendingPdfPages,
            thumbnailPaths = composer.pendingPdfRenderedPaths,
            isLoading = composer.pendingPdfIsRendering,
            renderProgress = composer.pendingPdfRenderProgress,
            selectedPages = pdfViewerSelection,
            onTogglePage = { onTogglePdfSelection?.invoke(it) },
            onSelectAll = { select -> onTogglePdfSelection?.let { toggle ->
                (0 until composer.pendingPdfPages.coerceAtLeast(1)).forEach { i ->
                    if ((i in pdfViewerSelection) != select) toggle(i)
                }
            }},
            onPreviewPage = { index ->
                composer.showPdfPageDialog = false
                composer.pdfDialogHiddenForPreview = true
                onPdfPreviewSelect?.invoke(composer.pendingPdfRenderedPaths, index)
            },
            onConfirm = { selection ->
                composer.confirmPendingPdfSelection(selection.selectedPages)
            },
            onDismiss = {
                composer.dismissPendingPdf()
            }
        )
    }

    // Video slice dialog
    if (composer.showVideoSliceDialog && composer.pendingVideoUri != null) {
        VideoSliceDialog(
            videoUri = composer.pendingVideoUri!!,
            durationMs = composer.pendingVideoDurationMs,
            onConfirm = { result ->
                composer.showVideoSliceDialog = false
                composer.addSlicedVideo(result.uri, result.frameCount, result.intervalMs)
                // Process next video in queue
                composer.processNextVideo()
            },
            onDismiss = {
                composer.showVideoSliceDialog = false
                // Process next video in queue
                composer.processNextVideo()
            }
        )
    }
}
