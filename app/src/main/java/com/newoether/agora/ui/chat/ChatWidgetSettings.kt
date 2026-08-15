package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.newoether.agora.data.repository.SettingsRepository

/** Bundles the chat-widget-rendering settings so they thread through MessageList/MessageItem/
 *  MessageBubbleAssets as one parameter instead of ten. */
@Immutable
data class ChatWidgetSettings(
    val htmlEnabled: Boolean = false,
    val htmlNetworkEnabled: Boolean = false,
    val htmlThemeEnabled: Boolean = false,
    val htmlJsEnabled: Boolean = false,
    val svgEnabled: Boolean = false,
    val svgNetworkEnabled: Boolean = false,
    val svgJsEnabled: Boolean = false,
    val svgThemeEnabled: Boolean = true,
    val mermaidEnabled: Boolean = false,
    val vegaLiteEnabled: Boolean = false,
    val vegaLiteNetworkEnabled: Boolean = false,
    val chartJsEnabled: Boolean = false,
    val chartJsNetworkEnabled: Boolean = false,
    val geoJsonEnabled: Boolean = false,
    val geoJsonTileUrl: String = "",
    val geoJsonThemeEnabled: Boolean = false,
)

@Composable
fun rememberChatWidgetSettings(settings: SettingsRepository): ChatWidgetSettings {
    val htmlEnabled by settings.htmlChatWidgetsEnabled.collectAsState()
    val htmlNetworkEnabled by settings.htmlChatWidgetsNetworkEnabled.collectAsState()
    val htmlThemeEnabled by settings.htmlChatWidgetsThemeEnabled.collectAsState()
    val htmlJsEnabled by settings.htmlChatWidgetsJsEnabled.collectAsState()
    val svgEnabled by settings.svgChatWidgetsEnabled.collectAsState()
    val svgNetworkEnabled by settings.svgChatWidgetsNetworkEnabled.collectAsState()
    val svgJsEnabled by settings.svgChatWidgetsJsEnabled.collectAsState()
    val svgThemeEnabled by settings.svgChatWidgetsThemeEnabled.collectAsState()
    val mermaidEnabled by settings.mermaidChatWidgetsEnabled.collectAsState()
    val vegaLiteEnabled by settings.vegaLiteChatWidgetsEnabled.collectAsState()
    val vegaLiteNetworkEnabled by settings.vegaLiteChatWidgetsNetworkEnabled.collectAsState()
    val chartJsEnabled by settings.chartJsChatWidgetsEnabled.collectAsState()
    val chartJsNetworkEnabled by settings.chartJsChatWidgetsNetworkEnabled.collectAsState()
    val geoJsonEnabled by settings.geoJsonChatWidgetsEnabled.collectAsState()
    val geoJsonTileUrl by settings.geoJsonTileUrl.collectAsState()
    val geoJsonThemeEnabled by settings.geoJsonChatWidgetsThemeEnabled.collectAsState()
    return ChatWidgetSettings(
        htmlEnabled, htmlNetworkEnabled, htmlThemeEnabled, htmlJsEnabled,
        svgEnabled, svgNetworkEnabled, svgJsEnabled, svgThemeEnabled,
        mermaidEnabled, vegaLiteEnabled, vegaLiteNetworkEnabled, chartJsEnabled, chartJsNetworkEnabled,
        geoJsonEnabled, geoJsonTileUrl, geoJsonThemeEnabled,
    )
}
