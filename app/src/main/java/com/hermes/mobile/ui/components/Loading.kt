package com.hermes.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.HermesMotion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Waiting, made legible — and made *alive*.
 *
 * A phone driving a desktop agent is a distributed system, and CAP is not
 * negotiable: when the link is partitioned or the desktop is mid-inference, the
 * client cannot have a fresh answer AND an instant one. That latency is
 * structural, so the honest move is to make the wait readable rather than
 * pretend it is not happening.
 *
 * A stalled spinner communicates "possibly broken". These communicate
 * "working, and here is roughly where": motion that is unmistakably alive,
 * plus a label that names the real phase. Every animation below is:
 *
 *  - **Cheap.** Pure Canvas draw on values Compose is already animating. No
 *    layout pass per frame, no bitmap, no recomposition of the transcript.
 *  - **Non-blocking.** Purely decorative; it never gates the UI thread and it
 *    never blocks a result arriving early.
 *  - **Honest.** No animation implies a completion percentage it cannot know —
 *    a fake progress bar that sticks at 90% is worse than no bar at all.
 *
 * The palette work matters as much as the motion: a turn that runs for two
 * minutes is two minutes of this mark on screen, so it is drawn in the warm
 * signal colours (clay and honey), never in a cold default blue.
 */

/** Shared timing so every waiting state in the app feels like one system. */
private const val PULSE_MS = 1500
private const val ORBIT_MS = 2600
private const val SHIMMER_MS = 1400

/**
 * The signature waiter: three dots that breathe along a sine offset.
 *
 * Used inline wherever a short reply is expected. The phase offset per dot is
 * what stops it reading as a mechanical loop.
 */
@Composable
fun BreathingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dot: androidx.compose.ui.unit.Dp = 7.dp,
) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = LinearEasing),
        ),
        label = "phase",
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val s = (sin(phase - i * 0.7f) + 1f) / 2f          // 0..1
            Canvas(Modifier.size(dot)) {
                drawCircle(
                    color = color.copy(alpha = 0.35f + 0.65f * s),
                    radius = size.minDimension / 2f * (0.62f + 0.38f * s),
                )
            }
        }
    }
}

/**
 * The Hermes mark while it works: a breathing clay core with two satellites in
 * independent orbits and a slow halo.
 *
 * This is the app's "it is thinking" glyph — used in the live-turn bar, the
 * pairing screen and the artifact compiler. Three motions at three different
 * periods (breath 2.2 s, outer orbit 2.6 s, inner counter-orbit 3.8 s) never
 * visibly repeat, so a long wait never looks like a closed loop restarting.
 */
@Composable
fun ThinkingOrb(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    accent: Color = MaterialTheme.colorScheme.primary,
    secondary: Color = MaterialTheme.colorScheme.tertiary,
) {
    val t = rememberInfiniteTransition(label = "orb")
    val breath by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val outer by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(ORBIT_MS, easing = LinearEasing)),
        label = "outer",
    )
    val inner by t.animateFloat(
        0f, -360f,
        infiniteRepeatable(tween((ORBIT_MS * 1.45f).toInt(), easing = LinearEasing)),
        label = "inner",
    )
    Canvas(modifier.size(size)) {
        val d = this.size.minDimension
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val core = d * (0.20f + 0.035f * breath)

        // Halo: a soft warm bloom that expands as the core contracts.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    accent.copy(alpha = 0.22f * (0.4f + 0.6f * breath)),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = d * 0.5f,
            ),
            radius = d * 0.5f,
            center = Offset(cx, cy),
        )

        // Faint track so the satellites have something to orbit.
        drawCircle(
            color = accent.copy(alpha = 0.10f),
            radius = d * 0.40f,
            center = Offset(cx, cy),
            style = Stroke(width = d * 0.03f),
        )

        // Outer satellite: the worker.
        val rOuter = d * 0.40f
        val aOuter = Math.toRadians(outer.toDouble())
        drawCircle(
            color = accent.copy(alpha = 0.9f),
            radius = d * 0.075f,
            center = Offset(
                cx + (rOuter * cos(aOuter)).toFloat(),
                cy + (rOuter * sin(aOuter)).toFloat(),
            ),
        )
        // Its comet tail — motion that reads even in a still frame's periphery.
        drawArc(
            brush = Brush.sweepGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.Transparent),
            ),
            startAngle = outer - 70f, sweepAngle = 70f, useCenter = false,
            topLeft = Offset(cx - rOuter, cy - rOuter),
            size = Size(rOuter * 2, rOuter * 2),
            style = Stroke(width = d * 0.05f),
        )

        // Inner satellite: the reader/verifier, counter-rotating so the two
        // passes cross rather than chase.
        val rInner = d * 0.22f
        val aInner = Math.toRadians(inner.toDouble())
        drawCircle(
            color = secondary.copy(alpha = 0.75f),
            radius = d * 0.055f,
            center = Offset(
                cx + (rInner * cos(aInner)).toFloat(),
                cy + (rInner * sin(aInner)).toFloat(),
            ),
        )

        // Core, breathing at the centre.
        drawCircle(color = accent.copy(alpha = 0.95f), radius = core, center = Offset(cx, cy))
    }
}

/**
 * A slow orbital sweep for longer waits (inference, a large transfer).
 *
 * Two counter-rotating arcs on a faint track. Counter-rotation is deliberate:
 * a single spinning arc at low speed is ambiguous with a frozen frame, whereas
 * two arcs crossing each other is unmistakably live even at a glance.
 */
@Composable
fun OrbitSpinner(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val t = rememberInfiniteTransition(label = "orbit")
    val a by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(ORBIT_MS, easing = LinearEasing)),
        label = "outer",
    )
    val b by t.animateFloat(
        0f, -360f,
        infiniteRepeatable(tween((ORBIT_MS * 1.45f).toInt(), easing = LinearEasing)),
        label = "inner",
    )
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.075f
        drawCircle(
            color = color.copy(alpha = 0.13f),
            radius = this.size.minDimension / 2f - stroke,
            style = Stroke(width = stroke),
        )
        rotate(a) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.9f), Color.Transparent),
                ),
                startAngle = 0f, sweepAngle = 110f, useCenter = false,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = Offset(stroke, stroke),
                size = Size(this.size.width - stroke * 2, this.size.height - stroke * 2),
            )
        }
        rotate(b) {
            val inset = stroke * 3.4f
            drawArc(
                color = color.copy(alpha = 0.45f),
                startAngle = 40f, sweepAngle = 70f, useCenter = false,
                style = Stroke(width = stroke * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
            )
        }
    }
}

/**
 * A radar sweep for "looking for your PC" — the one wait where the app is
 * genuinely *searching* a space, so the search gets a spatial metaphor instead
 * of a spinner. The sweep is honest: it re-fires with every fresh discovery
 * pass, and hits are drawn as they land.
 */
@Composable
fun RadarSweep(
    modifier: Modifier = Modifier,
    hits: List<Pair<Float, Float>> = emptyList(),
    size: androidx.compose.ui.unit.Dp = 96.dp,
    /** When false the rings sit still — a frozen radar means "not searching". */
    active: Boolean = true,
) {
    val t = rememberInfiniteTransition(label = "radar")
    val angle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "sweep",
    )
    val accent = MaterialTheme.colorScheme.primary
    val blip = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.size(size)) {
        val d = this.size.minDimension
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = d / 2f * 0.86f

        // Range rings.
        listOf(0.35f, 0.62f, 0.9f).forEach { f ->
            drawCircle(
                color = accent.copy(alpha = if (active) 0.14f else 0.08f),
                radius = r * f, center = c,
                style = Stroke(width = d * 0.012f),
            )
        }
        // Cross hairs.
        drawLine(accent.copy(alpha = 0.10f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), d * 0.012f)
        drawLine(accent.copy(alpha = 0.10f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), d * 0.012f)

        if (active) {
            // The sweep: a soft wedge trailing the beam.
            rotate(angle) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                        center = c,
                    ),
                    startAngle = 0f, sweepAngle = 60f, useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                )
                drawLine(
                    color = accent.copy(alpha = 0.85f),
                    start = c, end = Offset(c.x + r, c.y), strokeWidth = d * 0.02f,
                )
            }
        }
        // Hits: blips the user watches land.
        hits.forEach { (hx, hy) ->
            drawCircle(
                color = blip,
                radius = d * 0.035f,
                center = Offset(c.x + hx * r, c.y + hy * r),
            )
        }
    }
}

/**
 * Skeleton shimmer for content whose SHAPE is known but whose bytes are not
 * (a transcript being restored, a file listing, an artifact compiling).
 *
 * Showing the coming layout instead of a spinner removes the layout jump when
 * data lands, which is the part users actually perceive as slowness.
 */
@Composable
fun ShimmerLine(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 12.dp,
    widthFraction: Float = 1f,
) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        -1f, 2f,
        infiniteRepeatable(tween(SHIMMER_MS, easing = FastOutSlowInEasing)),
        label = "sweep",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    // The sweep is warm — a highlight that matches the accent reads as the
    // page breathing; a white sweep reads as a loading gif from 2014.
    val hi = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Canvas(
        modifier
            .fillMaxWidth(widthFraction)
            .height(height),
    ) {
        drawRoundRect(
            color = base,
            cornerRadius = CornerRadius(size.height / 2f),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, hi, Color.Transparent),
                startX = size.width * (x - 0.35f),
                endX = size.width * (x + 0.35f),
            ),
            cornerRadius = CornerRadius(size.height / 2f),
        )
    }
}

/** A paragraph-shaped skeleton — ragged widths so it reads as prose, not bars. */
@Composable
fun ShimmerParagraph(lines: Int = 3, modifier: Modifier = Modifier) {
    val widths = remember(lines) { listOf(1f, 0.92f, 0.66f, 0.85f, 0.55f) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i -> ShimmerLine(widthFraction = widths[i % widths.size]) }
    }
}

/**
 * A phase label that *changes*, cross-faded.
 *
 * This is the single most effective latency tool in the app: a static label
 * stops being read after four seconds, while a label that moves through real
 * phases ("Reading files" → "Running a command" → "Writing a reply") keeps
 * telling the user the work is progressing. It never invents a percentage;
 * it cycles only over phases that genuinely occur, and lands on the last one
 * until the turn completes.
 */
@Composable
fun PhaseTicker(
    phases: List<String>,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    /** How long each phase is shown before the ticker advances. */
    holdMs: Long = 2600,
) {
    val safe = remember(phases) { phases.ifEmpty { listOf("Working") } }
    var index by remember(safe) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(safe) {
        while (index < safe.lastIndex) {
            delay(holdMs)
            index += 1
        }
    }
    AnimatedContent(
        targetState = safe[index],
        transitionSpec = {
            (fadeIn(tween(260)) + slideInVertically { it / 2 }) togetherWith
                (fadeOut(tween(160)) + slideOutVertically { -it / 2 })
        },
        label = "phase-ticker",
        modifier = modifier,
    ) { text ->
        Text(text, style = style, color = color, maxLines = 2)
    }
}

/**
 * The full waiting state: motion plus a phase label.
 *
 * [phase] should name what is actually happening ("compiling", "thinking",
 * "uploading"). A label that changes as the work progresses is what separates
 * "slow but fine" from "hung" in the user's head — and it is honest, because
 * it reports a real state transition rather than an invented percentage.
 */
@Composable
fun WaitingState(
    phase: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    compact: Boolean = false,
) {
    if (compact) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            BreathingDots()
            Spacer(Modifier.width(10.dp))
            Text(
                phase,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ThinkingOrb()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                phase,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * An indeterminate bar for waits that have no measurable position.
 *
 * A track with a travelling highlight — never a filling percentage. Placed
 * under a phase label it turns a bare line of text into something the eye can
 * follow, at the cost of one gradient draw.
 */
@Composable
fun IndeterminateAccentBar(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 3.dp,
) {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(
        -0.6f, 1.6f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing)),
        label = "travel",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = r)
        val segW = size.width * 0.38f
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, accent, Color.Transparent),
                startX = size.width * x - segW / 2f,
                endX = size.width * x + segW / 2f,
            ),
            size = Size(size.width, size.height),
            cornerRadius = r,
        )
    }
}

/**
 * Determinate progress for transfers, where a real fraction exists.
 *
 * Kept visually distinct from the indeterminate states above: a filling track
 * means "this number is measured". Never use it for inference. The fill is
 * animated so a jump from 12% to 60% lands as motion rather than a teleport.
 */
@Composable
fun GradientProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 6.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(clamped) { anim.animateTo(clamped, HermesMotion.Soft) }

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = r)
        if (anim.value > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(c2, c1)),
                size = Size(size.width * anim.value, size.height),
                cornerRadius = r,
            )
        }
    }
}

/**
 * Connection heartbeat for the app bar: a dot whose pulse rate encodes health.
 *
 * Steady slow pulse = connected. Fast anxious pulse = reconnecting. Static =
 * offline. Encoding state in *motion* rather than colour alone keeps it
 * readable for colour-blind users and at a glance in peripheral vision.
 */
@Composable
fun StatusPulse(
    connected: Boolean,
    reconnecting: Boolean = false,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val color = when {
        reconnecting -> MaterialTheme.colorScheme.tertiary
        connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    if (!connected && !reconnecting) {
        Canvas(modifier.size(size)) { drawCircle(color) }
        return
    }
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(if (reconnecting) 620 else 2000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "beat",
    )
    Canvas(modifier.size(size * 2f)) {
        val r = this.size.minDimension / 4f
        drawCircle(color = color.copy(alpha = 0.22f * (1f - p)), radius = r * (1f + p * 1.5f))
        drawCircle(color = color.copy(alpha = 0.55f + 0.45f * abs(p)), radius = r)
    }
}

/**
 * The "a turn is running" bar under the transcript: orb, live phase, and an
 * indeterminate accent line, in one compact warm surface.
 *
 * Deliberately not a card: it sits directly above the composer for the whole
 * duration of a turn, so it is a strip. [phases] is the sequence the client
 * can honestly report from its own activity stream.
 */
@Composable
fun RunningTurnStrip(
    phases: List<String>,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkingOrb(size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                PhaseTicker(
                    phases = phases,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BreathingDots(dot = 5.dp)
        }
        IndeterminateAccentBar()
    }
}
