package com.honeystream.flix

import android.media.MediaPlayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nuvio.tv.R

/*
 * HoneyFlix startup intro: the same intro as HoneyLive (shared HoneyStream design), with the
 * play button in the honeycomb and the "HoneyFlix" wordmark. Ported from HoneyLive's
 * ui/Intro.kt + ui/Marks.kt; keep the two in step.
 */
// Timeline in ms (same as the approved preview). The jingle starts so its "BING" lands at BING.
private const val TOTAL = 4400f
private const val BING = 2400f
private const val JINGLE_AT = 1780L          // BING minus the jingle's 620 ms lead-in
private const val ARRIVE_FIRST = 1750f
private const val ARRIVE_LAST = 2350f

private fun seg(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
private fun easeOut(x: Float) = 1f - (1f - x).pow(3)
private fun easeInOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

/**
 * Startup intro (~4.4 s): little line-art bees (or the season's creature) wander around the
 * screen, each flies into one cell of the honeycomb as that cell draws itself in, the centre
 * symbol lands on the jingle's "BING", then the wordmark and honey drip settle. Any button skips.
 */
@Composable
fun HoneyIntro(onDone: () -> Unit) {
    val playSound = true
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val clock = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val focus = remember { FocusRequester() }
    var finished by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val theme = remember { HoneySeason.today() }
    val seasonal = theme.id != "honey"
    val markId = if (seasonal) theme.id else "flix"
    val cells = remember { HoneyMarks.cellCenters.map { it to HoneyMarks.cell(it) } }
    val centre = remember(markId) { HoneyMarks.centreSymbol(markId) }
    val creature = remember(markId) { if (seasonal) HoneyMarks.symbol(markId) else null }

    fun finish() {
        if (finished) return
        finished = true
        scope.launch { fade.animateTo(0f, tween(300)); onDone() }
    }

    DisposableEffect(playSound) {
        val res = when (theme.id) {
            "halloween" -> R.raw.jingle_spooky
            "holidays" -> R.raw.jingle_holidays
            "frost" -> R.raw.jingle_frost
            "spring" -> R.raw.jingle_spring
            "summer" -> R.raw.jingle_summer
            "autumn" -> R.raw.jingle_autumn
            "thanksgiving" -> R.raw.jingle_thanksgiving   // ends with a gobble
            else -> R.raw.jingle
        }
        var mp: MediaPlayer? = null
        val job = if (playSound) scope.launch {
            mp = runCatching { MediaPlayer.create(ctx, res)?.apply { setVolume(0.6f, 0.6f) } }.getOrNull()
            delay(JINGLE_AT)
            if (!finished) runCatching { mp?.start() }
        } else null
        onDispose { job?.cancel(); runCatching { mp?.release() } }
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        clock.animateTo(TOTAL, tween(TOTAL.toInt(), easing = LinearEasing))
        finish()
    }

    val t = clock.value
    val accent = Color(theme.accent)
    val bg = Color(theme.bg)
    val text = Color(theme.text)
    val markDp = 230.dp

    Box(
        Modifier.fillMaxSize().background(bg).alpha(fade.value)
            .focusRequester(focus).focusable()
            .onKeyEvent { finish(); true },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val markPx = with(density) { markDp.toPx() }
            val lift = with(density) { 60.dp.toPx() }
            val unit = markPx / 100f
            val origin = Offset(size.width / 2 - markPx / 2, size.height / 2 - markPx / 2 - lift)
            val fillGlow = easeOut(seg(t, BING + 250, BING + 800))

            // ---- Comb: each cell draws in as its flyer arrives ----
            cells.forEachIndexed { i, (_, path) ->
                val d = easeInOut(seg(t, arrival(i) - 60, arrival(i) + 380))
                if (d > 0f) translate(origin.x, origin.y) { scale(unit, unit, pivot = Offset.Zero) { honeyMarkUnit(path, accent, d, fillGlow * 0.6f) } }
            }
            // ---- Centre symbol on the BING ----
            val sd = easeInOut(seg(t, BING - 40, BING + 520))
            if (sd > 0f) translate(origin.x, origin.y) { scale(unit, unit, pivot = Offset.Zero) { honeyMarkUnit(centre, accent, sd, 0f) } }

            // ---- Flyers ----
            cells.forEachIndexed { i, (c, _) ->
                val arrive = arrival(i)
                if (t > arrive + 120) return@forEachIndexed
                val target = origin + c * unit
                val go = easeInOut(seg(t, arrive - 650, arrive))
                fun pos(tt: Float): Offset {
                    val w = wander(tt, i, size.width, size.height, lift)
                    return w * (1 - go) + target * go
                }
                val p = pos(t); val ahead = pos(t + 30f)
                val ang = Math.toDegrees(atan2((ahead.y - p.y).toDouble(), (ahead.x - p.x).toDouble())).toFloat()
                val facingLeft = kotlin.math.abs(ang) > 90
                val tilt = (if (facingLeft) (if (ang > 0) 180 - ang else -180 - ang) else ang).coerceIn(-25f, 25f) * 0.5f
                val bob = 3f * density.density * sin(t / 90f + i)
                val alpha = 1f - seg(t, arrive - 40, arrive + 120)
                val s = 1.9f * density.density * (1f - 0.55f * go)
                val flap = 0.5f + 0.5f * sin(t / 28f + i)
                withTransform({
                    translate(p.x, p.y + bob)
                    scale(if (facingLeft) -s else s, s, pivot = Offset.Zero)
                    rotate(tilt, pivot = Offset.Zero)
                }) {
                    if (creature != null) drawCreature(creature, accent, alpha) else drawBee(accent, bg, flap, alpha)
                }
            }
        }

        // ---- Wordmark + drip ----
        val word = easeOut(seg(t, BING + 300, BING + 900))
        val drip = easeOut(seg(t, BING + 700, BING + 1450))
        Column(Modifier.align(Alignment.Center).offset(y = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.alpha(word)) {
                val spacing = ((1f - word) * 0.35f).sp      // letters tighten as they settle
                androidx.compose.foundation.text.BasicText("Honey", style = androidx.compose.ui.text.TextStyle(color = text, fontSize = 54.sp, fontWeight = FontWeight.Black, letterSpacing = spacing))
                androidx.compose.foundation.text.BasicText("Flix", style = androidx.compose.ui.text.TextStyle(color = accent, fontSize = 54.sp, fontWeight = FontWeight.Black, letterSpacing = spacing))
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(320.dp).height(28.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxWidth(drip).height(5.dp).background(accent, RoundedCornerShape(3.dp)))
                if (drip > 0.6f) {
                    val d = (drip - 0.6f) / 0.4f
                    Box(Modifier.offset(y = 3.dp).size((7 * d + 2).dp, (14 * d + 2).dp).background(accent, RoundedCornerShape(50)))
                }
            }
        }
    }
}

/** Cells fill in a scattered order between ARRIVE_FIRST and ARRIVE_LAST. */
private fun arrival(i: Int) = ARRIVE_FIRST + (ARRIVE_LAST - ARRIVE_FIRST) * ((i * 3) % 7) / 6f

/** A lazy, looping flight path that enters from the side of the screen. */
private fun wander(tt: Float, i: Int, w: Float, h: Float, lift: Float): Offset {
    val s = tt / 1000f; val ph = i * 0.9f
    val x = w / 2 + w * 0.4f * sin(0.9f * s + ph) * cos(0.37f * s + ph * 0.5f)
    val y = h / 2 - lift + h * 0.32f * sin(1.3f * s + ph * 1.7f)
    val enter = easeOut(seg(tt, 0f, 700f)); val side = if (i % 2 == 1) -1 else 1
    return Offset(x * enter + (w / 2 + side * w * 0.7f) * (1 - enter), y)
}

/** A little line-art bee facing right, about 28×20 units, wings flapping with [flap]. */
private fun DrawScope.drawBee(c: Color, bg: Color, flap: Float, alpha: Float) {
    val wingH = 6f + 4f * flap
    val line = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    rotate(-25f, pivot = Offset(-2f, -9f)) {
        drawOval(c.copy(alpha = 0.18f * alpha), Offset(-7f, -9f - wingH), Size(10f, wingH * 2))
        drawOval(c.copy(alpha = alpha), Offset(-7f, -9f - wingH), Size(10f, wingH * 2), style = line)
    }
    rotate(20f, pivot = Offset(4f, -9f)) {
        drawOval(c.copy(alpha = 0.18f * alpha), Offset(-0.5f, -9f - wingH * 0.85f), Size(9f, wingH * 1.7f))
        drawOval(c.copy(alpha = alpha), Offset(-0.5f, -9f - wingH * 0.85f), Size(9f, wingH * 1.7f), style = line)
    }
    drawOval(bg.copy(alpha = alpha), Offset(-10f, -6.5f), Size(20f, 13f))
    drawOval(c.copy(alpha = alpha), Offset(-10f, -6.5f), Size(20f, 13f), style = Stroke(2f))
    drawLine(c.copy(alpha = alpha), Offset(-3f, -6f), Offset(-3f, 6f), 2f, StrokeCap.Round)     // stripes
    drawLine(c.copy(alpha = alpha), Offset(2f, -6.3f), Offset(2f, 6.3f), 2f, StrokeCap.Round)
    drawLine(c.copy(alpha = alpha), Offset(-10f, 0f), Offset(-14f, 0f), 2f, StrokeCap.Round)    // stinger
    drawCircle(bg.copy(alpha = alpha), 3.6f, Offset(11.5f, -1f))
    drawCircle(c.copy(alpha = alpha), 3.6f, Offset(11.5f, -1f), style = Stroke(2f))
}

/** The season's symbol (turkey, pumpkin, …) shrunk to bee size and centred on the flight point. */
private fun DrawScope.drawCreature(path: Path, c: Color, alpha: Float) {
    scale(0.42f, 0.42f, pivot = Offset.Zero) {
        translate(-50f, -60f) {
            drawPath(path, c.copy(alpha = alpha), style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

internal object HoneyMarks {
    /**
     * The HoneyStream family mark: a honeycomb cluster whose centre cell holds the app's symbol.
     * HoneyLive = a little TV with antennae; seasonal themes put their object (pumpkin, leaf…) in the centre.
     */
    private val cache = HashMap<String, Path>()
    fun path(themeId: String): Path = cache.getOrPut(themeId) { build(themeId) }
    private fun build(themeId: String): Path {
        val symbol = when (themeId) {
            "halloween" -> pumpkin()
            "autumn" -> leaf()
            "thanksgiving" -> turkey()
            "holidays" -> tree()
            "frost" -> snowflake()
            "spring" -> blossom()
            "summer" -> sun()
            "flix" -> play(); else -> tv()
        }
        return honeycomb().apply { addPath(shrinkToCentre(symbol)) }
    }

    /** Six outer cells plus the centre cell, in the 100×100 box. */
    private fun honeycomb() = Path().apply {
        val cell = 17.3f; val dist = 33.3f
        fun hex(cx: Float, cy: Float) {
            for (i in 0..5) {
                val a = PI / 180 * (60 * i - 90)
                val x = cx + cell * cos(a).toFloat(); val y = cy + cell * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        hex(50f, 50f)
        for (k in 0 until 6) {
            val a = PI / 3 * k
            hex(50 + dist * cos(a).toFloat(), 50 + dist * sin(a).toFloat())
        }
    }

    /** Centres of the seven honeycomb cells (centre first), in the mark's 0–100 box. */
    val cellCenters: List<Offset> = listOf(Offset(50f, 50f)) +
        (0 until 6).map { k -> Offset(50 + 33.3f * cos(PI / 3 * k).toFloat(), 50 + 33.3f * sin(PI / 3 * k).toFloat()) }

    /** One honeycomb cell outline, for drawing the comb cell by cell. */
    fun cell(c: Offset): Path = Path().apply {
        for (i in 0..5) {
            val a = PI / 180 * (60 * i - 90)
            val x = c.x + 17.3f * cos(a).toFloat(); val y = c.y + 17.3f * sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** The theme's symbol already shrunk into the centre cell (TV, turkey, pumpkin, …). */
    fun centreSymbol(themeId: String): Path = shrinkToCentre(symbol(themeId))

    /** The theme's symbol at full size: seasonal intros fly little copies of it instead of bees. */
    fun symbol(themeId: String): Path = when (themeId) {
        "halloween" -> pumpkin(); "autumn" -> leaf(); "thanksgiving" -> turkey(); "holidays" -> tree()
        "frost" -> snowflake(); "spring" -> blossom(); "summer" -> sun(); "flix" -> play(); else -> tv()
    }

    /** Scales a full-size (100×100) symbol down into the centre cell. */
    private fun shrinkToCentre(p: Path): Path {
        val m = android.graphics.Matrix().apply { postScale(0.24f, 0.24f, 50f, 50f) }
        val a = android.graphics.Path(p.asAndroidPath()); a.transform(m)
        return a.asComposePath()
    }

    /** Retro TV with rabbit-ear antennae (HoneyLive). */
    /** Play button (HoneyFlix). */
    private fun play() = Path().apply { moveTo(34f, 22f); lineTo(34f, 78f); lineTo(80f, 50f); close() }

    private fun tv() = Path().apply {
        moveTo(50f, 34f); lineTo(30f, 10f); moveTo(50f, 34f); lineTo(68f, 8f)                 // antennae
        addOval(Rect(26f, 6f, 34f, 14f)); addOval(Rect(64f, 4f, 72f, 12f))                    // antenna tips
        addRoundRect(androidx.compose.ui.geometry.RoundRect(10f, 34f, 90f, 86f, 10f, 10f))    // cabinet
        addRoundRect(androidx.compose.ui.geometry.RoundRect(18f, 42f, 66f, 78f, 6f, 6f))      // screen
        addOval(Rect(72f, 46f, 82f, 56f)); addOval(Rect(72f, 62f, 82f, 72f))                  // knobs
        moveTo(26f, 86f); lineTo(22f, 96f); moveTo(74f, 86f); lineTo(78f, 96f)               // feet
    }

    private fun hexagon() = Path().apply {
        for (i in 0..5) {
            val a = PI / 180 * (60 * i - 90)
            val x = 50 + 44 * cos(a).toFloat(); val y = 50 + 44 * sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
        // a small bee-wing pair inside: two loops
        addOval(Rect(34f, 34f, 50f, 50f)); addOval(Rect(50f, 34f, 66f, 50f))
        addOval(Rect(40f, 46f, 60f, 70f))
    }

    private fun pumpkin() = Path().apply {
        addOval(Rect(10f, 26f, 90f, 90f))
        addOval(Rect(30f, 26f, 70f, 90f))
        moveTo(50f, 26f); cubicTo(49f, 18f, 52f, 12f, 58f, 8f)       // stem
        moveTo(34f, 52f); lineTo(42f, 44f); lineTo(46f, 54f)            // eyes
        moveTo(54f, 54f); lineTo(58f, 44f); lineTo(66f, 52f)
        moveTo(34f, 68f); lineTo(42f, 74f); lineTo(50f, 68f); lineTo(58f, 74f); lineTo(66f, 68f) // grin
    }

    private fun turkey() = Path().apply {
        // Tail fan: seven feathers spread behind the body.
        val cx = 50f; val cy = 58f
        for (i in 0 until 7) {
            val a = Math.toRadians(-165.0 + i * 25)
            fun p(r: Float, off: Double = 0.0) = Offset(cx + r * cos(a + off).toFloat(), cy + r * sin(a + off).toFloat())
            val b1 = p(20f, -0.16); val b2 = p(20f, 0.16); val tip = p(46f)
            val c1 = p(44f, -0.22); val c2 = p(44f, 0.22)
            moveTo(b1.x, b1.y); quadraticTo(c1.x, c1.y, tip.x, tip.y); quadraticTo(c2.x, c2.y, b2.x, b2.y)
        }
        addOval(Rect(32f, 48f, 68f, 84f))                                   // body
        addOval(Rect(42f, 32f, 58f, 48f))                                   // head
        moveTo(57f, 40f); lineTo(64f, 42f); lineTo(57f, 44f)                // beak
        moveTo(55f, 45f); quadraticTo(57f, 53f, 53f, 55f)                   // wattle
        moveTo(46f, 83f); lineTo(44f, 94f); moveTo(40f, 97f); lineTo(44f, 94f); lineTo(47f, 97f)   // legs
        moveTo(54f, 83f); lineTo(56f, 94f); moveTo(53f, 97f); lineTo(56f, 94f); lineTo(60f, 97f)
    }

    private fun leaf() = Path().apply {
        moveTo(50f, 6f)
        cubicTo(82f, 24f, 90f, 60f, 50f, 88f)
        cubicTo(10f, 60f, 18f, 24f, 50f, 6f)
        moveTo(50f, 14f); lineTo(50f, 96f)                              // midrib + stem
        moveTo(50f, 40f); lineTo(32f, 28f); moveTo(50f, 40f); lineTo(68f, 28f)
        moveTo(50f, 58f); lineTo(28f, 46f); moveTo(50f, 58f); lineTo(72f, 46f)
    }

    private fun tree() = Path().apply {
        moveTo(50f, 8f); lineTo(70f, 34f); lineTo(60f, 34f); lineTo(80f, 60f); lineTo(67f, 60f)
        lineTo(88f, 84f); lineTo(12f, 84f); lineTo(33f, 60f); lineTo(20f, 60f); lineTo(40f, 34f); lineTo(30f, 34f); close()
        moveTo(46f, 84f); lineTo(46f, 96f); lineTo(54f, 96f); lineTo(54f, 84f)
    }

    private fun snowflake() = Path().apply {
        for (k in 0 until 3) {
            val a = PI / 3 * k
            fun p(r: Float, off: Double = 0.0) = Offset(50 + r * cos(a + off).toFloat(), 50 + r * sin(a + off).toFloat())
            val e1 = p(44f); val e2 = p(-44f)
            moveTo(e1.x, e1.y); lineTo(e2.x, e2.y)
            for (sgn in listOf(1f, -1f)) {
                val b = p(28f * sgn)
                val l = Offset(b.x + 10 * cos(a + sgn * 2.4).toFloat() * sgn, b.y + 10 * sin(a + sgn * 2.4).toFloat() * sgn)
                val r = Offset(b.x + 10 * cos(a - sgn * 2.4).toFloat() * sgn, b.y + 10 * sin(a - sgn * 2.4).toFloat() * sgn)
                moveTo(l.x, l.y); lineTo(b.x, b.y); lineTo(r.x, r.y)
            }
        }
    }

    private fun blossom() = Path().apply {
        for (i in 0 until 5) {
            val a = PI * 2 / 5 * i - PI / 2
            val cx = 50 + 24 * cos(a).toFloat(); val cy = 50 + 24 * sin(a).toFloat()
            addOval(Rect(cx - 18, cy - 18, cx + 18, cy + 18))
        }
        addOval(Rect(42f, 42f, 58f, 58f))
    }

    private fun sun() = Path().apply {
        addOval(Rect(28f, 28f, 72f, 72f))
        for (i in 0 until 8) {
            val a = PI / 4 * i
            moveTo(50 + 30 * cos(a).toFloat(), 50 + 30 * sin(a).toFloat())
            lineTo(50 + 46 * cos(a).toFloat(), 50 + 46 * sin(a).toFloat())
        }
    }

    /** Points spread along the mark's outline (all contours): where intro particles land. */
    fun targets(themeId: String, count: Int): List<Offset> {
        val src = path(themeId).asAndroidPath()
        val lengths = mutableListOf<Float>()
        android.graphics.PathMeasure(src, false).let { pm -> do { lengths += pm.length } while (pm.nextContour()) }
        val total = lengths.sum().coerceAtLeast(1f)
        val out = mutableListOf<Offset>()
        val pos = FloatArray(2)
        val pm = android.graphics.PathMeasure(src, false)
        var ci = 0
        do {
            val share = ((lengths[ci] / total) * count).toInt().coerceAtLeast(1)
            for (k in 0 until share) { pm.getPosTan(pm.length * k / share, pos, null); out += Offset(pos[0], pos[1]) }
            ci++
        } while (pm.nextContour() && ci < lengths.size)
        return out.take(count).ifEmpty { listOf(Offset(50f, 50f)) }
    }
}

/**
 * Draws a theme's mark. [draw] 0–1 traces the outline in; [fill] 0–1 fades in a soft glow fill.
 */
/** Draws the mark in its own 0–100 units (caller sets up the scale). */
internal fun DrawScope.honeyMarkUnit(path: Path, color: Color, draw: Float, fill: Float) {
    val shown = if (draw >= 1f) path else trimmed(path, draw)
    if (fill > 0f) {
        drawPath(path, color.copy(alpha = 0.18f * fill))                                                              // soft fill
        drawPath(path, color.copy(alpha = 0.35f * fill), style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) // glow
    }
    drawPath(shown, color, style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The first [fraction] of every contour, so all strokes draw in together. */
private fun trimmed(path: Path, fraction: Float): Path {
    val out = android.graphics.Path()
    val pm = android.graphics.PathMeasure(path.asAndroidPath(), false)
    do {
        pm.getSegment(0f, pm.length * fraction.coerceIn(0f, 1f), out, true)
    } while (pm.nextContour())
    return out.asComposePath()
}

/** The season's look for the intro (same palettes and dates as HoneyLive's "Auto by season"). */
internal data class HoneySeason(val id: String, val bg: Long, val accent: Long, val text: Long) {
    companion object {
        private val all = listOf(
            HoneySeason("honey", 0xFF120D06, 0xFFF5A623, 0xFFFFF6E5),
            HoneySeason("halloween", 0xFF0E0812, 0xFFFF7A1A, 0xFFFFF1E6),
            HoneySeason("autumn", 0xFF140C07, 0xFFE07B39, 0xFFFFF3E8),
            HoneySeason("thanksgiving", 0xFF140B06, 0xFFE8743B, 0xFFFFF3E8),
            HoneySeason("holidays", 0xFF07120D, 0xFFE5484D, 0xFFF4FFF8),
            HoneySeason("frost", 0xFF070E16, 0xFF7FD3FF, 0xFFF0F8FF),
            HoneySeason("spring", 0xFF0B130C, 0xFF8CD867, 0xFFF4FFF0),
            HoneySeason("summer", 0xFF0F0D04, 0xFFFFD23F, 0xFFFFFBEA),
        )
        fun today(): HoneySeason {
            val cal = java.util.Calendar.getInstance()
            val m = cal.get(java.util.Calendar.MONTH) + 1; val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val c = java.util.Calendar.getInstance().apply { clear(); set(cal.get(java.util.Calendar.YEAR), java.util.Calendar.NOVEMBER, 1) }
            val thanksgiving = 1 + (java.util.Calendar.THURSDAY - c.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7 + 21
            val id = when {
                m == 10 && d >= 15 -> "halloween"
                m == 9 || m == 10 -> "autumn"
                m == 11 && d <= thanksgiving + 1 -> "thanksgiving"
                m == 11 || m == 12 -> "holidays"
                m <= 2 -> "frost"
                m <= 5 -> "spring"
                else -> "summer"
            }
            return all.first { it.id == id }
        }
    }
}
