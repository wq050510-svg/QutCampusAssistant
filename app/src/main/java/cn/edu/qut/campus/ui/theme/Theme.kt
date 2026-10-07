package cn.edu.qut.campus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// AMOLED 纯黑配色：深色下背景/表面使用 #000000，与 ProfileScreen 的「纯黑省电」承诺一致。
// 这里用显式色值（而非 Color.kt 里的 DarkBackground/DarkSurface）以免主题色被其它模块改回 #121212。
// ---------------------------------------------------------------------------
private val AmoledBlack = Color(0xFF000000)
private val AmoledSurfaceVariant = Color(0xFF1A1A1A)

/** 深色主色：浅蓝在纯黑上对比度足够，避免深灰字配黑底 */
private val AmoledPrimary = Color(0xFF8ECAE6)
private val AmoledPrimaryContainer = Color(0xFF00405F)
private val AmoledOnPrimaryContainer = Color(0xFFCBE6FF)
private val AmoledSecondaryContainer = Color(0xFF23313A)
private val AmoledOnSecondaryContainer = Color(0xFFD3E5F0)
private val AmoledOutline = Color(0xFF4A4A4A)
private val AmoledOutlineVariant = Color(0xFF2A2A2A)
private val AmoledError = Color(0xFFFFB4AB)
private val AmoledOnError = Color(0xFF690005)
private val AmoledErrorContainer = Color(0xFF93000A)

private val DarkColorScheme = darkColorScheme(
    primary = AmoledPrimary,
    onPrimary = Color(0xFF00344C),
    primaryContainer = AmoledPrimaryContainer,
    onPrimaryContainer = AmoledOnPrimaryContainer,
    secondary = AmoledPrimary,
    secondaryContainer = AmoledSecondaryContainer,
    onSecondaryContainer = AmoledOnSecondaryContainer,
    // AMOLED：背景与表面均为纯黑，层级靠 surfaceVariant / outline 区分
    background = AmoledBlack,
    onBackground = Color(0xFFE6E6E6),
    surface = AmoledBlack,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = Color(0xFFC3C7CB),
    outline = AmoledOutline,
    outlineVariant = AmoledOutlineVariant,
    error = AmoledError,
    onError = AmoledOnError,
    errorContainer = AmoledErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = QutBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = QutBlueContainer,
    onPrimaryContainer = Color(0xFF001E30),
    background = LightBackground,
    onBackground = Color(0xFF191C1E),
    surface = LightSurface,
    onSurface = Color(0xFF191C1E),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF41474D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE)
)

@Composable
fun QutCampusAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12+ 且允许动态取色时优先 Material You；
        // 低版本 Build.VERSION.SDK_INT 判断先短路，dynamic*ColorScheme 不会被调用，因此不会崩溃。
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                // 保留 Material You 的品牌色（主色/强调色跟随壁纸），
                // 但强制把背景与各层表面压成 AMOLED 纯黑：
                // 动态取色默认会给出深紫/深灰底，与「AMOLED 纯黑省电」的承诺不符（实测截图确认过）。
                dynamicDarkColorScheme(context).copy(
                    background = AmoledBlack,
                    onBackground = Color(0xFFE6E6E6),
                    surface = AmoledBlack,
                    onSurface = Color(0xFFEDEDED),
                    surfaceVariant = AmoledSurfaceVariant,
                    onSurfaceVariant = Color(0xFFC3C7CB),
                    surfaceDim = AmoledBlack,
                    surfaceBright = Color(0xFF2A2A2A),
                    surfaceContainerLowest = AmoledBlack,
                    surfaceContainerLow = Color(0xFF0D0D0D),
                    surfaceContainer = Color(0xFF141414),
                    surfaceContainerHigh = Color(0xFF1C1C1C),
                    surfaceContainerHighest = Color(0xFF242424),
                    outline = AmoledOutline,
                    outlineVariant = AmoledOutlineVariant
                )
            } else {
                dynamicLightColorScheme(context)
            }
        }
        // 关闭动态取色时使用固定品牌配色（深色即 AMOLED 纯黑）
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
