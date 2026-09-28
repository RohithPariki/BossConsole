package ai.rever.boss.window

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.theme.LocalWindowGlass
import ai.rever.boss.theme.sidebarGlassEnabled
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** True only after AppKit installed and measured the toolbar; callers keep their fallback until then. */
@Composable
internal actual fun NativeSidebarTitleBar(
    title: String,
    actions: List<NativeTitleBarAction>,
): Boolean {
    val window = (LocalAwtWindow.current as? ComposeWindow)?.takeIf { SystemUtils.isMacOS } ?: return false
    val currentActions by rememberUpdatedState(actions)
    var controller by remember(window) { mutableStateOf<MacSidebarToolbar?>(null) }
    var headerHeight by remember(window) { mutableStateOf<Double?>(null) }
    LaunchedEffect(window) {
        while (!window.isShowing || window.windowHandle == 0L) delay(16)
        controller =
            MacSidebarToolbar(window.windowHandle, { headerHeight = it }) { id ->
                currentActions
                    .flatMap { listOf(it) + it.menu.orEmpty() + it.contextMenu }
                    .find { it.id == id }
                    ?.onClick
                    ?.invoke()
            }
    }
    val currentController = controller
    NativeBrowserFieldHosting(currentController, actions, headerHeight != null)
    NativeAddressSuggestions(currentController, actions)
    DisposableEffect(currentController) { onDispose { currentController?.close() } }
    val background = BossTheme.colors.raised.copy(alpha = LocalWindowGlass.current.chromeOpacity)
    val icons =
        actions
            .mapNotNull { action ->
                action.textInput?.favicon?.let { action.id to rememberNativeFavicon(it) }
                    ?: action.icon?.let { action.id to rememberNativeToolbarIcon(it) }
            }.toMap()
    SideEffect { currentController?.update(title, actions, background.luminance() < 0.5f, background.toArgb(), icons) }
    // The sidebar paints its continuous glass surface upward through this inset. Painting
    // another translucent fill here doubles the tint only over its title-bar portion.
    val insetBackground = if (sidebarGlassEnabled) Color.Transparent else background
    headerHeight?.let { Spacer(Modifier.fillMaxWidth().height(it.toFloat().dp).background(insetBackground)) }
    return headerHeight != null
}

/** Release the plugin fallback only after the native field exists, and restore it on disposal. */
@Composable
private fun NativeBrowserFieldHosting(
    controller: MacSidebarToolbar?,
    actions: List<NativeTitleBarAction>,
    ready: Boolean,
) {
    val input = actions.firstOrNull { it.textInput != null }?.textInput
    DisposableEffect(controller, input?.identity, ready) {
        if (ready && controller != null) input?.onHosted { controller.focusAddress() }
        onDispose { input?.onHosted(null) }
    }
}
