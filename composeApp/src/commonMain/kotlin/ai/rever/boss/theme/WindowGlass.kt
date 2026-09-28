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

internal const val DEFAULT_GLASS_TINT = 0.5f
internal const val DEFAULT_GLASS_OPACITY = 0.5f

/** Per-window capability: saved opacity is never applied before a real native backdrop exists. */
internal data class WindowGlass(
    val installed: Boolean = false,
    val coverage: String = "off",
    val tint: Float = DEFAULT_GLASS_TINT,
    val opacity: Float = DEFAULT_GLASS_OPACITY,
) {
    val chromeOpacity: Float
        get() =
            when {
                !installed -> 1f
                coverage == "window" -> contentOpacity
                coverage == "sidebar" -> safeTint
                else -> 1f
            }

    // BossTerm paints the background opacity and tint as one combined fill.
    val contentOpacity: Float
        get() = if (installed && coverage == "window") 1f - (1f - safeOpacity) * (1f - safeTint) else 1f
    private val safeOpacity: Float get() = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else DEFAULT_GLASS_OPACITY
    private val safeTint: Float get() = if (tint.isFinite()) tint.coerceIn(0f, 1f) else DEFAULT_GLASS_TINT
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
            ink = if (glass.installed && glass.coverage == "window") colors.ink.copy(alpha = 0f) else colors.ink,
            panel =
                if (glass.installed &&
                    glass.coverage == "window"
                ) {
                    Color.Transparent
                } else {
                    colors.panel.copy(alpha = glass.chromeOpacity)
                },
        )
    MaterialTheme(colors = MaterialTheme.colors.copy(background = colors.ink.copy(alpha = glass.contentOpacity))) {
        CompositionLocalProvider(LocalBossColors provides surfaces) {
            val background =
                if (glass.installed && glass.coverage == "window") {
                    colors.ink.copy(alpha = glass.contentOpacity)
                } else if (glass.installed) {
                    Color.Transparent
                } else {
                    colors.ink
                }
            Box(Modifier.fillMaxSize().background(background)) { content() }
        }
    }
}
