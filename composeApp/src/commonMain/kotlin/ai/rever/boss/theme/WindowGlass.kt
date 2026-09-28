package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.LocalBossColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Per-window capability: saved opacity is never applied before a real native backdrop exists. */
internal data class WindowGlass(
    val installed: Boolean = false,
    val coverage: String = "off",
    val tint: Float = 0.24f,
) {
    val chromeOpacity: Float get() = if (installed && coverage in setOf("sidebar", "window")) safeTint else 1f
    val contentOpacity: Float get() = if (installed && coverage == "window") safeTint else 1f
    private val safeTint: Float get() = if (tint.isFinite()) tint.coerceIn(0f, 1f) else 0.24f
}

internal val LocalWindowGlass = staticCompositionLocalOf { WindowGlass() }

internal fun isGlassTheme(id: String): Boolean = id == "liquid-glass-dark" || id == "liquid-glass-light"

/** Scope transparency to app surfaces; menus, dialogs and separate windows keep opaque tokens. */
@Composable
internal fun GlassAppSurfaces(content: @Composable () -> Unit) {
    val glass = LocalWindowGlass.current
    val colors = BossTheme.colors
    val surfaces =
        colors.copy(
            ink = colors.ink.copy(alpha = glass.contentOpacity),
            panel = colors.panel.copy(alpha = glass.chromeOpacity),
        )
    MaterialTheme(colors = MaterialTheme.colors.copy(background = surfaces.ink)) {
        CompositionLocalProvider(LocalBossColors provides surfaces) {
            Box(Modifier.fillMaxSize().background(if (glass.installed) Color.Transparent else colors.ink)) { content() }
        }
    }
}
