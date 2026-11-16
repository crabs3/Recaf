package software.coley.recaf.android.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/** Colors for code, picked to read on both the light and the dark surface colors. */
data class CodeColors(val keyword: Color, val string: Color, val comment: Color, val number: Color, val annotation: Color)

val LightCodeColors = CodeColors(Color(0xFF7B3FB5), Color(0xFF2E7D32), Color(0xFF7A8088), Color(0xFFB5541C), Color(0xFF8A6D00))
val DarkCodeColors = CodeColors(Color(0xFFC792EA), Color(0xFFA5D6A7), Color(0xFF8B929C), Color(0xFFF2AE7C), Color(0xFFE6C36A))

val Mono = FontFamily.Monospace

@Composable
fun codeColors(): CodeColors = if (isSystemInDarkTheme()) DarkCodeColors else LightCodeColors

/** Material theme following the system's colors and dark mode. */
@Composable
fun RecafTheme(content: @Composable () -> Unit) {
	val context = LocalContext.current
	val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
	MaterialTheme(colorScheme = colors, content = content)
}
