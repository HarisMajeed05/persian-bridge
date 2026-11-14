package app.persianbridge

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * My look. The two accents carry meaning and are never decoration:
 * teal means the text stays on my phone, amber means it goes to Google.
 * Everything else is a quiet green black so those two colours are the only thing that shouts.
 */

@Immutable
data class Pal(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val teal: Color,
    val onTeal: Color,
    val amber: Color,
    val onAmber: Color,
    val danger: Color,
)

private val DarkPal = Pal(
    dark = true,
    bg = Color(0xFF0D1412), surface = Color(0xFF141E1B), raised = Color(0xFF1B2824), line = Color(0xFF2A3A35),
    text = Color(0xFFE7F0ED), muted = Color(0xFF93A8A1),
    teal = Color(0xFF3CC9B6), onTeal = Color(0xFF03211C),
    amber = Color(0xFFE8B04B), onAmber = Color(0xFF2A1C00),
    danger = Color(0xFFF0746A),
)

private val LightPal = Pal(
    dark = false,
    bg = Color(0xFFEDF2F0), surface = Color(0xFFFFFFFF), raised = Color(0xFFE4ECE9), line = Color(0xFFCAD6D1),
    text = Color(0xFF0E1A17), muted = Color(0xFF51655F),
    teal = Color(0xFF0B7A6C), onTeal = Color(0xFFFFFFFF),
    amber = Color(0xFF8A5A00), onAmber = Color(0xFFFFFFFF),
    danger = Color(0xFFB3261E),
)

private val LocalPal = staticCompositionLocalOf { DarkPal }

object Pb {
    val c: Pal
        @Composable @ReadOnlyComposable get() = LocalPal.current
}

object Fonts {
    // Instrument Sans for the interface, Vazirmatn for anything Persian.
    val ui = FontFamily(
        Font(R.font.instrument_sans_regular, FontWeight.Normal),
        Font(R.font.instrument_sans_medium, FontWeight.Medium),
        Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
    )
    val fa = FontFamily(
        Font(R.font.vazirmatn_regular, FontWeight.Normal),
        Font(R.font.vazirmatn_medium, FontWeight.Medium),
        Font(R.font.vazirmatn_bold, FontWeight.Bold),
    )
}

object T {
    val title = TextStyle(fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp)
    val head = TextStyle(fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Fonts.ui, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyMed = TextStyle(fontFamily = Fonts.ui, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)
    val small = TextStyle(fontFamily = Fonts.ui, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val micro = TextStyle(fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
    val fa = TextStyle(fontFamily = Fonts.fa, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 31.sp, textDirection = TextDirection.Rtl)
    val faSmall = TextStyle(fontFamily = Fonts.fa, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp, textDirection = TextDirection.Rtl)
}

@Composable
fun PbTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    var pal = if (dark) DarkPal else LightPal
    if (dynamic) {
        val cs = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        pal = pal.copy(teal = cs.primary, onTeal = cs.onPrimary)
    }
    val scheme = if (dark) {
        darkColorScheme(primary = pal.teal, onPrimary = pal.onTeal, background = pal.bg, surface = pal.surface, onSurface = pal.text, error = pal.danger)
    } else {
        lightColorScheme(primary = pal.teal, onPrimary = pal.onTeal, background = pal.bg, surface = pal.surface, onSurface = pal.text, error = pal.danger)
    }
    CompositionLocalProvider(
        LocalPal provides pal,
        LocalTextSelectionColors provides TextSelectionColors(pal.teal, pal.teal.copy(alpha = 0.3f)),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ---------- haptics ----------

class Haptic(private val v: View) {
    fun tap() { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm() { v.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    fun reject() { v.performHapticFeedback(HapticFeedbackConstants.REJECT) }
    fun toggle(on: Boolean) {
        val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else HapticFeedbackConstants.CLOCK_TICK
        v.performHapticFeedback(c)
    }
}

@Composable
fun rememberHaptic(): Haptic {
    val v = LocalView.current
    return remember(v) { Haptic(v) }
}

// ---------- text ----------

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = T.body,
    color: Color = Pb.c.text,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified,
) {
    Text(
        text = text, modifier = modifier, color = color, style = style, maxLines = maxLines, textAlign = align,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Persian text. Always right to left, always Vazirmatn, so it stays correct next to Latin text. */
@Composable
fun Fa(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    small: Boolean = false,
    color: Color = Pb.c.text,
) {
    Text(text = text, modifier = modifier, color = color, style = if (small) T.faSmall else T.fa, textAlign = TextAlign.Start)
}

// ---------- surfaces and press feel ----------

@Composable
fun Modifier.panel(radius: Dp = 18.dp, fill: Color = Pb.c.surface, stroke: Color = Pb.c.line): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(fill).border(1.dp, stroke, shape)
}

@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (down && enabled) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press")
    val hap = rememberHaptic()
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button) {
            hap.tap()
            onClick()
        }
}

// ---------- icons, drawn so I ship no icon library ----------

enum class Ic { Copy, Pen, Close, Gear, Lock, Out, Shield, Warn, Check, Plus, Trash, Eye, Chev, Down, Info }

@Composable
fun Glyph(ic: Ic, tint: Color, modifier: Modifier = Modifier, dp: Dp = 20.dp) {
    Canvas(modifier.size(dp)) {
        val k = this.size.width / 24f
        val st = Stroke(width = 1.9f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        fun line(a: Float, b: Float, c: Float, d: Float) =
            drawLine(tint, p(a, b), p(c, d), strokeWidth = st.width, cap = StrokeCap.Round)
        fun Path.m(x: Float, y: Float) = moveTo(x * k, y * k)
        fun Path.l(x: Float, y: Float) = lineTo(x * k, y * k)
        fun Path.cu(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) =
            cubicTo(a * k, b * k, c * k, d * k, e * k, f * k)
        fun draw(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = st)

        when (ic) {
            Ic.Copy -> {
                drawRoundRect(tint, p(9f, 9f), Size(11f * k, 11f * k), CornerRadius(2.5f * k), style = st)
                draw { m(15f, 9f); l(15f, 5f); l(5f, 5f); l(5f, 15f); l(9f, 15f) }
            }
            Ic.Pen -> {
                draw { m(4f, 20f); l(4.8f, 15.2f); l(15.5f, 4.5f); l(19.5f, 8.5f); l(8.8f, 19.2f); close() }
                line(13f, 7f, 17f, 11f)
            }
            Ic.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Ic.Gear -> {
                drawCircle(tint, 3.2f * k, p(12f, 12f), style = st)
                drawCircle(tint, 6.8f * k, p(12f, 12f), style = st)
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4f
                    line(12f + cos(a) * 6.8f, 12f + sin(a) * 6.8f, 12f + cos(a) * 9.6f, 12f + sin(a) * 9.6f)
                }
            }
            Ic.Lock -> {
                drawRoundRect(tint, p(5f, 11f), Size(14f * k, 9f * k), CornerRadius(2.2f * k), style = st)
                draw {
                    m(8f, 11f); l(8f, 8f)
                    arcTo(Rect(p(8f, 4f), p(16f, 12f)), 180f, 180f, false)
                    l(16f, 11f)
                }
            }
            Ic.Out -> {
                draw { m(5f, 14f); l(5f, 19f); l(19f, 19f); l(19f, 14f) }
                line(12f, 15f, 12f, 4.5f)
                draw { m(8f, 8f); l(12f, 4f); l(16f, 8f) }
            }
            Ic.Shield -> draw {
                m(12f, 3f); l(19f, 6f); l(19f, 12f)
                cu(19f, 16.5f, 16f, 19.5f, 12f, 21f)
                cu(8f, 19.5f, 5f, 16.5f, 5f, 12f)
                l(5f, 6f); close()
            }
            Ic.Warn -> {
                draw { m(12f, 4f); l(21f, 19.5f); l(3f, 19.5f); close() }
                line(12f, 10f, 12f, 14f)
                drawCircle(tint, 1.1f * k, p(12f, 16.8f))
            }
            Ic.Check -> draw { m(5f, 12.5f); l(10f, 17.5f); l(19f, 7f) }
            Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ic.Trash -> {
                line(5f, 7f, 19f, 7f)
                draw { m(9f, 7f); l(9f, 5f); l(15f, 5f); l(15f, 7f) }
                draw { m(7f, 7f); l(8f, 19f); l(16f, 19f); l(17f, 7f) }
            }
            Ic.Eye -> {
                draw { m(3f, 12f); cu(6f, 6.5f, 18f, 6.5f, 21f, 12f); cu(18f, 17.5f, 6f, 17.5f, 3f, 12f); close() }
                drawCircle(tint, 2.6f * k, p(12f, 12f), style = st)
            }
            Ic.Chev -> draw { m(9f, 6f); l(15f, 12f); l(9f, 18f) }
            Ic.Down -> draw { m(6f, 9f); l(12f, 15f); l(18f, 9f) }
            Ic.Info -> {
                drawCircle(tint, 9f * k, p(12f, 12f), style = st)
                line(12f, 11f, 12f, 16f)
                drawCircle(tint, 1.1f * k, p(12f, 8f))
            }
        }
    }
}

// ---------- loaders ----------

@Composable
fun Spinner(color: Color, dp: Dp = 18.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(dp)) {
        val sw = this.size.width * 0.13f
        drawArc(
            color, a.value, 110f, false, Offset(sw / 2, sw / 2),
            Size(this.size.width - sw, this.size.width - sw), style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

/** For waits longer than about a second. Shorter waits get an inline spinner or nothing. */
@Composable
fun Skeleton(lines: Int = 3, modifier: Modifier = Modifier) {
    val c = Pb.c
    val t = rememberInfiniteTransition(label = "sk")
    val a = t.animateFloat(
        0.35f, 0.85f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == lines - 1 && lines > 1) 0.55f else 1f)
                    .height(14.dp)
                    .graphicsLayer { alpha = a.value }
                    .clip(RoundedCornerShape(7.dp))
                    .background(c.line)
            )
        }
    }
}

// ---------- controls ----------

enum class BtnKind { Primary, Tonal, Ghost, Danger }

@Composable
fun PbButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    icon: Ic? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = Pb.c
    val bg: Color
    val fg: Color
    val bd: Color
    when (kind) {
        BtnKind.Primary -> { bg = c.teal; fg = c.onTeal; bd = Color.Transparent }
        BtnKind.Tonal -> { bg = c.raised; fg = c.text; bd = c.line }
        BtnKind.Ghost -> { bg = Color.Transparent; fg = c.teal; bd = Color.Transparent }
        BtnKind.Danger -> { bg = Color.Transparent; fg = c.danger; bd = c.danger.copy(alpha = 0.5f) }
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .pressable(enabled && !busy, onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, bd, shape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            Spinner(fg, 18.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Glyph(icon, fg, dp = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Txt(label, style = T.bodyMed, color = fg, maxLines = 1)
    }
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Pb.c.teal,
    icon: Ic? = null,
) {
    val c = Pb.c
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent, label = "chipbg")
    val bd by animateColorAsState(if (selected) accent else c.line, label = "chipbd")
    val fg = if (selected) accent else c.muted
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .pressable(true, onClick)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, bd, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, fg, dp = 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Txt(label, style = T.small, color = fg, maxLines = 1)
    }
}

/** A horizontally scrolling row of choices. I use it where a segmented control would not fit. */
@Composable
fun <K> ChoiceRow(options: List<Pair<K, String>>, selected: K, onSelect: (K) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (k, label) -> Chip(label, k == selected, { onSelect(k) }) }
    }
}

@Composable
fun ModeBadge(mode: Mode, modifier: Modifier = Modifier) {
    val c = Pb.c
    val smart = mode == Mode.SMART
    val col = if (smart) c.amber else c.teal
    Row(
        modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (smart) Ic.Out else Ic.Lock, col, dp = 14.dp)
        Spacer(Modifier.width(6.dp))
        Txt(if (smart) "Smart" else "Private", style = T.micro, color = col, maxLines = 1)
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = true,
    secret: Boolean = false,
    rtl: Boolean = false,
    minLines: Int = 1,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
) {
    val c = Pb.c
    var focused by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        when { error != null -> c.danger; focused -> c.teal; else -> c.line }, label = "fieldborder",
    )
    val shape = RoundedCornerShape(12.dp)
    val style = (if (rtl) T.fa else T.body).copy(color = c.text)
    Column(modifier) {
        Txt(label, style = T.small, color = c.muted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.raised).border(1.dp, border, shape)
                .padding(start = 14.dp, end = if (secret) 4.dp else 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (value.isEmpty() && hint.isNotEmpty()) {
                    Txt(hint, style = style, color = c.muted.copy(alpha = 0.7f))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    textStyle = style,
                    singleLine = singleLine,
                    minLines = minLines,
                    cursorBrush = SolidColor(c.teal),
                    visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (secret) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = !secret,
                        keyboardType = if (secret) KeyboardType.Password else keyboard,
                        imeAction = imeAction,
                    ),
                )
            }
            if (secret) {
                Box(
                    Modifier
                        .size(44.dp)
                        .semantics { contentDescription = if (shown) "Hide key" else "Show key" }
                        .pressable(true) { shown = !shown },
                    contentAlignment = Alignment.Center,
                ) { Glyph(Ic.Eye, if (shown) c.teal else c.muted, dp = 20.dp) }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Txt(error, style = T.small, color = c.danger)
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Pb.c.teal,
    enabled: Boolean = true,
) {
    val c = Pb.c
    val hap = rememberHaptic()
    val src = remember { MutableInteractionSource() }
    val x = animateDpAsState(if (checked) 20.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 500f), label = "thumb")
    val track by animateColorAsState(if (checked) accent else c.line, label = "track")
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(
                value = checked, interactionSource = src, indication = null, enabled = enabled, role = Role.Switch,
                onValueChange = { hap.toggle(it); onChange(it) },
            )
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, style = T.bodyMed)
            if (desc != null) Txt(desc, style = T.small, color = c.muted)
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
            Box(
                Modifier
                    .padding(3.dp)
                    .offset { IntOffset(x.value.roundToPx(), 0) }
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (checked) c.onTeal else c.muted)
            )
        }
    }
}

// ---------- feedback ----------

enum class NoticeKind { Info, Warn, Error, Ok }

@Composable
fun Notice(
    kind: NoticeKind,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Pb.c
    val col = when (kind) {
        NoticeKind.Info, NoticeKind.Ok -> c.teal
        NoticeKind.Warn -> c.amber
        NoticeKind.Error -> c.danger
    }
    val ic = when (kind) {
        NoticeKind.Info -> Ic.Info
        NoticeKind.Ok -> Ic.Check
        NoticeKind.Warn, NoticeKind.Error -> Ic.Warn
    }
    Row(
        modifier.fillMaxWidth().panel(14.dp, col.copy(alpha = 0.09f), col.copy(alpha = 0.35f)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph(ic, col, Modifier.padding(top = 1.dp), 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Txt(title, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Txt(text, style = T.small, color = c.text.copy(alpha = 0.88f))
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                PbButton(actionLabel, onAction, kind = BtnKind.Ghost, modifier = Modifier.offset(x = (-16).dp))
            }
        }
    }
}

/** A live status line for onboarding. state null means I am still checking. */
@Composable
fun StatusRow(
    title: String,
    detail: String,
    state: Boolean?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Pb.c
    val dot by animateColorAsState(if (state == true) c.teal else Color.Transparent, label = "dot")
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(dot)
                .border(1.5.dp, if (state == true) c.teal else c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                true -> Glyph(Ic.Check, c.onTeal, dp = 16.dp)
                null -> Spinner(c.muted, 14.dp)
                false -> Unit
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, style = T.bodyMed)
            Txt(detail, style = T.small, color = c.muted)
        }
        if (actionLabel != null && onAction != null && state != true) {
            Spacer(Modifier.width(10.dp))
            PbButton(actionLabel, onAction, kind = BtnKind.Tonal)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Pb.c
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_bridge), null, Modifier.size(72.dp).alpha(0.55f))
        Spacer(Modifier.height(12.dp))
        Txt(title, style = T.head, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Txt(body, style = T.body, color = c.muted, align = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PbButton(actionLabel, onAction, kind = BtnKind.Tonal)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text, modifier.padding(top = 20.dp, bottom = 8.dp), style = T.small.copy(fontWeight = FontWeight.SemiBold), color = Pb.c.muted)
}

@Composable
fun HLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Pb.c.line))
}
