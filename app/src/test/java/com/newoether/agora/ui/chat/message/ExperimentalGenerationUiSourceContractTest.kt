package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalGenerationUiSourceContractTest {
    @Test
    fun `terminal states use retry text tokens without active animation or shell`() {
        val root = locateMainSourceRoot()
        val assistant = source(root, "message/AssistantMessageContent.kt")
        val terminalBar = source(root, "message/GenerationErrorBar.kt")
        val retry = source(root, "message/RetryActivityIndicator.kt")
        val tail = source(root, "StreamingTailIndicator.kt")

        assertFalse(assistant.contains("AssistantStatusRow("))
        assertFalse(assistant.contains("AssistantStatusKind"))
        assertTrue(assistant.contains("AssistantInlineActivity("))
        assertTrue(assistant.contains("RetryActivityIndicator("))
        assertTrue(assistant.contains("StoppedGenerationBar("))
        assertTrue(assistant.contains("GenerationErrorBar(errorContent.errorText)"))
        assertFalse(assistant.contains("if (mode == AssistantInlineActivityMode.NONE) return"))
        assertTrue(assistant.contains("var retainedMode by remember"))
        assertTrue(assistant.contains("visible = mode != AssistantInlineActivityMode.NONE"))
        assertTrue(assistant.contains("exit = fadeOut(tween(320, easing = FastOutSlowInEasing))"))

        assertTrue(terminalBar.contains("internal fun GenerationTerminalText("))
        assertTrue(terminalBar.contains("style = ChatType.body"))
        assertTrue(terminalBar.contains("onSurfaceVariant.copy(alpha = 0.55f)"))
        assertTrue(terminalBar.contains("NoAutoScrollSelectionContainer"))
        assertTrue(terminalBar.contains("normalizePersistedGenerationErrorText("))
        assertFalse(terminalBar.contains("Surface("))
        assertFalse(terminalBar.contains("Icon("))
        assertFalse(terminalBar.contains("RoundedCornerShape"))
        assertFalse(terminalBar.contains("GenerationActivityDot("))
        assertFalse(terminalBar.contains("surfaceVariant.copy"))
        assertFalse(terminalBar.contains("colorScheme.error"))

        assertTrue(retry.contains("RETRY_REVEAL_MS_PER_GRAPHEME = 27"))
        assertTrue(retry.contains("RETRY_REVEAL_MIN_MS = 225"))
        assertTrue(retry.contains("RETRY_REVEAL_MAX_MS = 600"))
        assertTrue(retry.contains("easing = LinearOutSlowInEasing"))
        assertTrue(retry.contains("val revealProgress = remember {"))
        assertTrue(retry.contains("entranceStarted"))
        assertFalse(retry.contains("remember(label) {\n        Animatable"))
        assertTrue(retry.contains("retryGraphemeBoundaries("))
        assertTrue(retry.contains("retryRevealDurationMillis("))
        assertTrue(retry.contains("retryGraphemeAlpha("))
        assertTrue(retry.contains("retryCaretPosition("))
        assertTrue(retry.contains("getHorizontalPosition("))
        assertTrue(retry.contains("GenerationActivityDot("))

        assertTrue(tail.contains("internal fun GenerationActivityDot("))
        assertTrue(tail.contains("rememberInfiniteTransition("))
        assertTrue(tail.contains(".size(11.dp)"))
    }

    @Test
    fun `Thinking card uses compact chrome one trailing rotating arrow and synchronized motion`() {
        val timeline = source(locateMainSourceRoot(), "message/MessageItemTimeline.kt")

        assertTrue(timeline.contains("CompactSegmentIcon.LOADING"))
        assertTrue(timeline.contains("compactSegmentHasActiveContent("))
        assertTrue(timeline.contains("targetState = collapsedIcon"))
        assertTrue(timeline.contains("CircularProgressIndicator("))
        assertTrue(timeline.contains("BoxWithConstraints("))
        assertTrue(timeline.contains("rememberTextMeasurer("))
        assertTrue(timeline.contains("label = \"compactSegmentWidth\""))
        assertTrue(timeline.contains("Modifier.width(cardWidth)"))
        assertTrue(timeline.contains("durationMillis = 400"))
        assertTrue(timeline.contains("easing = LinearOutSlowInEasing"))
        assertTrue(timeline.contains("THINKING_COLLAPSED_WIDTH_ALLOWANCE_DP = 12"))
        assertTrue(timeline.contains("val contentLayoutWidth ="))
        assertTrue(timeline.contains("wrapContentSize(Alignment.TopStart, unbounded = true)"))
        assertTrue(timeline.contains("requiredWidth(contentLayoutWidth)"))
        assertFalse(timeline.contains("animateContentSize("))
        assertTrue(timeline.contains("RoundedCornerShape(18.dp)"))
        assertTrue(timeline.contains("padding(start = 12.dp, top = 10.dp, bottom = 10.dp)"))
        assertTrue(timeline.contains("align(Alignment.TopEnd)"))
        assertTrue(timeline.contains("padding(top = 10.dp, end = 8.dp)"))
        assertTrue(timeline.contains("Modifier.size(18.dp)"))
        assertTrue(timeline.contains("fontSize = 13.sp"))
        assertTrue(timeline.contains("lineHeight = 22.sp"))
        assertTrue(timeline.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue(timeline.contains("Modifier.weight(1f)"))
        assertTrue(timeline.contains("strokeWidth = 4.dp"))
        assertEquals(1, timeline.windowed("Icons.Default.KeyboardArrowDown".length)
            .count { it == "Icons.Default.KeyboardArrowDown" })
        assertFalse(timeline.contains("Icons.Default.KeyboardArrowRight"))
        assertFalse(timeline.contains("Icons.Default.KeyboardArrowUp"))
        assertTrue(timeline.contains("rotationZ = disclosureRotation"))
        assertTrue(timeline.contains("thinking_for_seconds_ellipsis"))
    }

    @Test
    fun `Sources summary opens without haptics`() {
        val assistant = source(locateMainSourceRoot(), "message/AssistantMessageContent.kt")
        val summaryClick = assistant
            .substringAfter("CitationSourcesSummaryCapsule(")
            .substringBefore("modifier = Modifier")

        assertTrue(summaryClick.contains("showCitationSources = true"))
        assertFalse(summaryClick.contains("haptics."))
    }

    @Test
    fun `answer and thought Markdown use the same one point one line height multiplier`() {
        val assets = source(locateMainSourceRoot(), "message/MessageBubbleAssets.kt")

        assertTrue(assets.contains("MARKDOWN_LINE_HEIGHT_MULTIPLIER = 1.1f"))
        assertTrue(assets.contains("scaledMarkdownTextStyle("))
        assertTrue(assets.contains("val markdownBodyStyle = scaledMarkdownTextStyle(ChatType.body)"))
        assertTrue(assets.contains("val thoughtMarkdownBodyStyle = scaledMarkdownTextStyle(ChatType.thoughtBody)"))
        assertTrue(assets.contains("h1 = scaledMarkdownTextStyle(ChatType.mdH1)"))
        assertTrue(assets.contains("h6 = scaledMarkdownTextStyle(ChatType.mdH6)"))
        assertTrue(assets.contains("code = scaledMarkdownTextStyle(ChatType.code)"))
        assertTrue(assets.contains("h1 = scaledMarkdownTextStyle(ChatType.thH1)"))
        assertTrue(assets.contains("h6 = scaledMarkdownTextStyle(ChatType.thH6)"))
        assertTrue(assets.contains("code = scaledMarkdownTextStyle(ChatType.thoughtCode)"))
        assertTrue(assets.contains("plainTextStyle = markdownBodyStyle"))
        assertTrue(assets.contains("plainTextStyle = thoughtMarkdownBodyStyle"))
    }

    @Test
    fun `user actions move from the bottom row into the bubble long press menu`() {
        val user = source(locateMainSourceRoot(), "message/UserMessageBubble.kt")

        assertTrue(user.contains(".combinedClickable("))
        assertTrue(user.contains("onLongClick ="))
        assertFalse(user.contains("NoAutoScrollSelectionContainer"))
        assertTrue(user.contains("R.string.copy"))
        assertTrue(user.contains("R.string.edit"))
        assertTrue(user.contains("R.string.select_text"))
        assertTrue(user.contains("R.string.info"))
        assertTrue(user.contains("R.string.delete"))

        val branch = user.substringAfter("if (showBranchSelector")
        assertTrue(branch.contains("onSwitchBranch(-1)"))
        assertTrue(branch.contains("onSwitchBranch(1)"))
    }

    @Test
    fun `Select Text reuses the sheet shell with twelve dp raw-content top inset`() {
        val root = locateMainSourceRoot()
        val item = source(root, "message/MessageItem.kt")
        val detail = source(root, "message/SegmentDetailSheet.kt")

        assertTrue(item.contains("showUserTextSelection"))
        assertTrue(item.contains("titleOverride = stringResource(R.string.select_text)"))
        assertTrue(item.contains("directSelectableTextContent = displayMessage.text"))
        assertTrue(detail.contains("directSelectableTextContent: String? = null"))
        assertTrue(detail.contains("NoAutoScrollSelectionContainer("))
        assertTrue(detail.contains("SearchHighlightedPlainText("))
        assertTrue(detail.contains("padding(top = 12.dp, bottom = 32.dp)"))
        assertTrue(detail.contains("SmoothBottomSheet("))
        assertTrue(detail.contains("rememberSmoothBottomSheetState("))
        assertFalse(detail.contains("Dialog("))
        assertFalse(detail.contains("detectVerticalDragGestures("))
        assertFalse(detail.contains("snapshotFlow"))
    }

    private fun source(root: File, relative: String): String =
        File(root, "com/newoether/agora/ui/chat/$relative").readText()

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
