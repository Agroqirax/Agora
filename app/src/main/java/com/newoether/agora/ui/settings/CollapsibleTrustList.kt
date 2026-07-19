package com.newoether.agora.ui.settings

/**
 * Splits a trust list (currently: allowed notification-interact apps) into what to render
 * inline vs. what sits behind a "Show N more" toggle.
 *
 * Rendered the same way every time: each entry gets its own
 * [com.newoether.agora.ui.settings.SettingsItem] row with a revoke action, appended after
 * a toggle switch inside a `SettingsGroup`. Left unbounded, trusting a dozen
 * notification-sending apps pushes every other setting below it off screen — this
 * collapses anything past [collapseThreshold] until the user asks to see the rest.
 * Notifications collapses fully by default ([collapseThreshold] = 0): unlike shell/MCP
 * servers (which get their own row with an enable toggle either way), a trusted app only
 * shows up here at all once the user has granted it, so even one entry is worth folding
 * away rather than always showing.
 *
 * @return the entries to render inline, and how many more are hidden (0 once expanded,
 *   or if there were never more than [collapseThreshold] to begin with).
 */
fun <T> collapsibleTrustList(
    items: List<T>,
    expanded: Boolean,
    collapseThreshold: Int = 3,
): Pair<List<T>, Int> {
    if (items.size <= collapseThreshold || expanded) return items to 0
    return items.take(collapseThreshold) to (items.size - collapseThreshold)
}
