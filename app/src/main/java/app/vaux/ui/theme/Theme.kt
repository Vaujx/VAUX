package app.vaux.ui.theme

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/** Manga inks and paper. The four main colours can be changed by the user in Settings. */
object Ink {
    var Black by mutableStateOf(Color(0xFF0B0B0C))   // background
    var Paper by mutableStateOf(Color(0xFFF5F3EF))   // text / accents
    var Tone80 by mutableStateOf(Color(0xFF2A2A2C))  // cards
    var Tone50 by mutableStateOf(Color(0xFF8C8C8F))  // muted text
    val Tone20: Color get() = lerp(Paper, Black, 0.15f)
    val defaults = listOf("0B0B0C", "F5F3EF", "2A2A2C", "8C8C8F")

    fun parse(s: String): Color? {
        val h = s.trim().removePrefix("#")
        if (h.length != 6 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return Color(("FF$h").toLong(16).toInt())
    }
    fun hex(c: Color) = "%06X".format(c.toArgb() and 0xFFFFFF)
    fun set(bg: Color, text: Color, card: Color, muted: Color) { Black = bg; Paper = text; Tone80 = card; Tone50 = muted }
    fun save(p: SharedPreferences) = p.edit().putString("c_bg", hex(Black)).putString("c_tx", hex(Paper))
        .putString("c_cd", hex(Tone80)).putString("c_mu", hex(Tone50)).apply()
    fun load(p: SharedPreferences) {
        p.getString("c_bg", null)?.let { parse(it) }?.let { Black = it }
        p.getString("c_tx", null)?.let { parse(it) }?.let { Paper = it }
        p.getString("c_cd", null)?.let { parse(it) }?.let { Tone80 = it }
        p.getString("c_mu", null)?.let { parse(it) }?.let { Tone50 = it }
    }
}

val VauxShapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(36.dp))

@Composable
fun VauxTheme(dark: Boolean = true, content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Ink.Paper, onPrimary = Ink.Black, background = Ink.Black, onBackground = Ink.Paper,
        surface = Ink.Tone80, onSurface = Ink.Paper, onSurfaceVariant = Ink.Tone50, secondary = Ink.Tone50, outline = Ink.Tone50,
        surfaceContainerHigh = Ink.Tone80, surfaceContainerHighest = Ink.Tone80, secondaryContainer = Ink.Tone80, onSecondaryContainer = Ink.Paper),
    shapes = VauxShapes, content = content)

/** Manga screentone: dots shrink toward the top, like the shaded hills in the reference panel. */
@Composable
fun Halftone(modifier: Modifier, color: Color = Ink.Tone80) = Canvas(modifier) {
    val s = 12.dp.toPx(); var y = 0f; var row = 0
    while (y < size.height) {
        val k = y / size.height; var x = if (row % 2 == 0) 0f else s / 2
        while (x < size.width) { drawCircle(color, s * 0.55f * k * k, Offset(x, y)); x += s }
        y += s * 0.87f; row++
    }
}
