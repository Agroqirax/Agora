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
    val mermaidEnabled: Boolean = false,
    val vegaLiteEnabled: Boolean = false,
    val geoJsonEnabled: Boolean = false,
    val geoJsonTileUrl: String = "",
    val geoJsonThemeEnabled: Boolean = false,
    val geoJsonRouteProvider: String = "osm",
)

@Composable
fun rememberChatWidgetSettings(settings: SettingsRepository): ChatWidgetSettings {
    val htmlEnabled by settings.htmlChatWidgetsEnabled.collectAsState()
    val htmlNetworkEnabled by settings.htmlChatWidgetsNetworkEnabled.collectAsState()
    val htmlThemeEnabled by settings.htmlChatWidgetsThemeEnabled.collectAsState()
    val htmlJsEnabled by settings.htmlChatWidgetsJsEnabled.collectAsState()
    val mermaidEnabled by settings.mermaidChatWidgetsEnabled.collectAsState()
    val vegaLiteEnabled by settings.vegaLiteChatWidgetsEnabled.collectAsState()
    val geoJsonEnabled by settings.geoJsonChatWidgetsEnabled.collectAsState()
    val geoJsonTileUrl by settings.geoJsonTileUrl.collectAsState()
    val geoJsonThemeEnabled by settings.geoJsonChatWidgetsThemeEnabled.collectAsState()
    val geoJsonRouteProvider by settings.geoJsonRouteProvider.collectAsState()
    return ChatWidgetSettings(
        htmlEnabled, htmlNetworkEnabled, htmlThemeEnabled, htmlJsEnabled,
        mermaidEnabled, vegaLiteEnabled, geoJsonEnabled,
        geoJsonTileUrl, geoJsonThemeEnabled, geoJsonRouteProvider,
    )
}
