package com.capsulepal.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capsulepal.app.partner.PartnerAppearance
import com.capsulepal.app.partner.PartnerMood
import com.capsulepal.app.partner.PartnerOutfit
import com.capsulepal.app.ui.theme.LocalCapsulePalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * App 内的伙伴：一台横置的胶囊小机器。
 *
 * 左右两端是红色端帽，中间是深色玻璃屏，8×8 像素点阵眼长在屏幕里。
 * 外观（扫描线、光晕等）由用户勾选；装扮（睡帽、节日帽等）按状态与日期自动出现。
 */
@Composable
fun PartnerFace(
    mood: PartnerMood,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    animated: Boolean = true,
    appearance: Set<PartnerAppearance> = PartnerAppearance.defaults,
    outfit: PartnerOutfit? = null,
    charging: Boolean = false,
    progress: Float = 0f
) {
    val compact = size.value < 90f

    // 表情切换先闭眼再睁眼，避免直接从一种眼型跳到另一种。
    var shownMood by remember { mutableStateOf(mood) }
    var eyesOpen by remember { mutableStateOf(true) }
    var curious by remember { mutableStateOf(false) }
    val lookRight = remember { Random.nextBoolean() }
    LaunchedEffect(mood, animated) {
        if (mood != shownMood) {
            eyesOpen = false
            delay(90L)
            shownMood = mood
        }
        eyesOpen = true
        if (!animated) return@LaunchedEffect
        // 回到待机时先好奇地看向一侧，再把视线收回来。
        if (mood == PartnerMood.IDLE) {
            curious = true
            delay(3200L)
            curious = false
        }
        if (mood == PartnerMood.HAPPY || mood == PartnerMood.SLEEPY) return@LaunchedEffect
        while (true) {
            delay(2600L + Random.nextLong(2600L))
            eyesOpen = false
            delay(110L)
            eyesOpen = true
        }
    }
    val eyeOpen by animateFloatAsState(
        targetValue = if (eyesOpen) 1f else 0f,
        animationSpec = tween(90),
        label = "partnerEyes"
    )

    val transition = rememberInfiniteTransition(label = "partnerBreath")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "partnerBreathValue"
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "partnerScanLine"
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "partnerDrift"
    )
    // 每 7 秒一次约 1.3 秒的高光扫过玻璃，其余时间静止。
    val glossSweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 7000
                0f at 0
                0f at 5000
                1f at 6300
                1f at 7000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "partnerGlossSweep"
    )

    val shell = LocalCapsulePalette.current

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val scale = if (animated) breath else 1f
                scaleX = 1f + (scale - 1f) * 0.4f
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height

            val bodyW = w * 0.92f
            val bodyH = (bodyW / 2.2f).coerceAtMost(h * 0.5f)
            val bodyLeft = (w - bodyW) / 2f
            val bodyTop = h * 0.5f - bodyH / 2f
            val radius = bodyH / 2f

            val screenLeft = bodyLeft + bodyW * 0.18f
            val screenW = bodyW * 0.64f
            val screenH = bodyH * 0.66f
            val screenTop = bodyTop + (bodyH - screenH) / 2f
            val screenRadius = screenH * 0.36f

            // 有装扮时铺一层主题背景：渐变光晕 + 场景粒子（无装扮保持干净）。
            if (outfit != null) {
                drawOutfitBackdrop(outfit, w, h, if (animated) drift else 0f, compact)
            }

            drawOval(
                color = Color.Black.copy(alpha = 0.09f),
                topLeft = Offset(bodyLeft + bodyW * 0.10f, bodyTop + bodyH * 0.90f),
                size = Size(bodyW * 0.80f, bodyH * 0.22f)
            )

            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(shell.shellStart, shell.shellMid, shell.shellMid, shell.shellEnd),
                    startX = bodyLeft,
                    endX = bodyLeft + bodyW
                ),
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(radius, radius)
            )
            // 深色模式只给外壳加一圈很淡的轮廓光，伙伴本体配色与浅色一致。
            if (shell.shellRim.alpha > 0f) {
                drawRoundRect(
                    color = shell.shellRim,
                    topLeft = Offset(bodyLeft, bodyTop),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = max(1f, w * 0.005f))
                )
            }
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF34302B), Color(0xFF171412)),
                    start = Offset(screenLeft, screenTop),
                    end = Offset(screenLeft + screenW, screenTop + screenH)
                ),
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(screenRadius, screenRadius)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(screenRadius, screenRadius),
                style = Stroke(width = max(1f, w * 0.006f))
            )

            // 屏幕圆角路径：扫描线、屏幕反光、高光扫过都必须严格裁剪在黑色屏幕内。
            val screenClip = Path().apply {
                addRoundRect(
                    RoundRect(
                        screenLeft,
                        screenTop,
                        screenLeft + screenW,
                        screenTop + screenH,
                        CornerRadius(screenRadius, screenRadius)
                    )
                )
            }

            if (!compact && PartnerAppearance.SCREEN_REFLECTION in appearance) {
                // 屏幕反光：一圈很淡的薄荷色描边，贴着玻璃内侧，随呼吸缓慢明暗变化。
                val pulse = ((breath - 1f) / 0.02f).coerceIn(0f, 1f)
                val strokeW = max(1.2f, w * 0.008f).coerceAtMost(screenH * 0.18f)
                val inset = strokeW / 2f
                clipPath(screenClip) {
                    drawRoundRect(
                        color = DecoMint.copy(alpha = 0.10f + 0.14f * pulse),
                        topLeft = Offset(screenLeft + inset, screenTop + inset),
                        size = Size(screenW - strokeW, screenH - strokeW),
                        cornerRadius = CornerRadius(
                            (screenRadius - inset).coerceAtLeast(0f),
                            (screenRadius - inset).coerceAtLeast(0f)
                        ),
                        style = Stroke(width = strokeW)
                    )
                }
            }

            if (!compact && animated && PartnerAppearance.SCREEN_REFLECTION in appearance) {
                // 斜向高光带缓慢扫过玻璃：起止位置都完全在屏幕外，
                // 扫到右边缘时不会残留半条光带，避免看起来“卡一下”。
                // 与薄荷色内描边同属「屏幕反光」，关掉开关时一起消失。
                val bandW = screenW * 0.26f
                val skew = screenH * 0.5f
                val startLeft = screenLeft - bandW - skew
                val endLeft = screenLeft + screenW + skew
                val bandLeft = startLeft + glossSweep * (endLeft - startLeft)
                val band = Path().apply {
                    moveTo(bandLeft, screenTop)
                    lineTo(bandLeft + bandW, screenTop)
                    lineTo(bandLeft + bandW - skew, screenTop + screenH)
                    lineTo(bandLeft - skew, screenTop + screenH)
                    close()
                }
                clipPath(screenClip) {
                    drawPath(
                        path = band,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.07f),
                                Color.Transparent
                            ),
                            start = Offset(bandLeft, screenTop),
                            end = Offset(bandLeft + bandW, screenTop + screenH)
                        )
                    )
                }
            }

            if (PartnerAppearance.SCAN_LINE in appearance && animated) {
                // 扫描线只在屏幕内上下扫，行高完全落在圆角内，不会越过黑色屏幕。
                val lineH = max(1f, h * 0.004f)
                val scanY = screenTop + (screenH - lineH) * scan
                clipPath(screenClip) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.10f),
                        topLeft = Offset(screenLeft, scanY),
                        size = Size(screenW, lineH)
                    )
                }
            }

            if (mood == PartnerMood.ALERT) {
                drawRoundRect(
                    color = DecoCoral.copy(alpha = 0.28f + (breath - 1f) * 8f),
                    topLeft = Offset(screenLeft, screenTop),
                    size = Size(screenW, screenH),
                    cornerRadius = CornerRadius(screenRadius, screenRadius),
                    style = Stroke(width = max(1.5f, w * 0.008f))
                )
            }

            val eyeSize = (screenH * 0.64f).coerceAtMost(screenW * 0.21f)
            val pixel = eyeSize / 8f
            val eyeGap = screenW * 0.34f
            // 充电时视线微微上抬，像在期待电量涨满。
            val eyeY = screenTop + screenH * 0.48f -
                if (shownMood == PartnerMood.CHARGING) pixel * 1.1f else 0f
            val eyeColor = Color(0xFFF7F1E6)
            // 好奇时两只眼睛一起看向一侧，持续几秒后收回。
            val gazeX = if (curious) (if (lookRight) 1f else -1f) * pixel * 1.6f else 0f
            val leftEyeX = screenLeft + screenW / 2f - eyeGap / 2f + gazeX
            val rightEyeX = screenLeft + screenW / 2f + eyeGap / 2f + gazeX
            val pattern = when {
                shownMood == PartnerMood.CHARGING -> OPEN_EYE
                shownMood == PartnerMood.FOCUS -> FOCUS_EYE
                shownMood == PartnerMood.HAPPY -> HAPPY_EYE
                shownMood == PartnerMood.SLEEPY -> SLEEPY_EYE
                shownMood == PartnerMood.ALERT -> ALERT_EYE
                curious -> OPEN_EYE
                else -> if (eyeOpen > 0.5f || !animated) OPEN_EYE else BLINK_EYE
            }

            drawPixelPattern(pattern, leftEyeX, eyeY, pixel, eyeColor)
            drawPixelPattern(pattern, rightEyeX, eyeY, pixel, eyeColor)

            // 腮红：贴着眼睛外下角，用像素块保持屏幕的点阵感。
            val blushOn = shownMood == PartnerMood.HAPPY ||
                shownMood == PartnerMood.ALERT ||
                shownMood == PartnerMood.CHARGING ||
                curious
            if (blushOn) {
                val blush = Color(0xFFE58C7C).copy(alpha = 0.75f)
                drawPixelPattern(BLUSH, leftEyeX - pixel * 4.0f, eyeY + pixel * 5.0f, pixel * 0.78f, blush)
                drawPixelPattern(BLUSH, rightEyeX + pixel * 4.0f, eyeY + pixel * 5.0f, pixel * 0.78f, blush)
            }

            // 屏幕里只放像素表情：完成装扮时在眼睛下方补一张像素嘴。
            if (outfit == PartnerOutfit.CONFETTI) {
                drawPixelPattern(
                    pattern = SMILE_MOUTH,
                    centerX = screenLeft + screenW / 2f,
                    centerY = eyeY + eyeSize * 0.94f,
                    pixel = pixel * 0.92f,
                    color = DecoMint
                )
            }

            when (outfit) {
                PartnerOutfit.FOCUS_GOGGLES -> {
                    drawGogglesFrame(
                        screenLeft = screenLeft,
                        screenTop = screenTop,
                        screenW = screenW,
                        screenH = screenH,
                        screenRadius = screenRadius,
                        bodyLeft = bodyLeft,
                        bodyW = bodyW,
                        bodyH = bodyH
                    )
                }
                PartnerOutfit.NATIONAL_DAY -> {
                    drawNationalStar(bodyLeft, bodyTop, bodyW, bodyH)
                }
                null, PartnerOutfit.CONFETTI, PartnerOutfit.CHARGE_BOLT -> Unit
                else -> {
                    drawHeadwear(outfit, bodyLeft + bodyW / 2f, bodyTop, bodyW, bodyH)
                }
            }

            if (charging || outfit == PartnerOutfit.CHARGE_BOLT) {
                val badgeRadius = bodyH * 0.24f
                drawChargeBadge(
                    cx = bodyLeft + bodyW * 0.11f,
                    cy = bodyTop + bodyH * 0.5f,
                    r = badgeRadius
                )
            }

            if (!compact && PartnerAppearance.PROGRESS_BAND in appearance) {
                // 进度灯带放在机身下沿，不占用屏幕，保持屏幕只有像素表情。
                val bandW = bodyW * 0.46f
                val bandH = max(1.5f, bodyH * 0.055f)
                val bandLeft = bodyLeft + (bodyW - bandW) / 2f
                val bandTop = bodyTop + bodyH * 0.865f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.20f),
                    topLeft = Offset(bandLeft, bandTop),
                    size = Size(bandW, bandH),
                    cornerRadius = CornerRadius(bandH / 2f, bandH / 2f)
                )
                val clamped = progress.coerceIn(0f, 1f)
                if (clamped > 0f) {
                    drawRoundRect(
                        color = DecoMint,
                        topLeft = Offset(bandLeft, bandTop),
                        size = Size(bandW * clamped, bandH),
                        cornerRadius = CornerRadius(bandH / 2f, bandH / 2f)
                    )
                }
            }
        }

        if (mood == PartnerMood.SLEEPY && !compact) {
            Column(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .offset(x = (-size * 0.06f), y = size * 0.02f)
            ) {
                Text(
                    text = "z",
                    fontSize = (size.value * 0.14f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "z",
                    fontSize = (size.value * 0.10f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.offset(x = size * 0.10f)
                )
            }
        }
    }
}

/** 头部装扮：都挂在机身顶部，尺寸随机身缩放。 */
private fun DrawScope.drawHeadwear(
    outfit: PartnerOutfit?,
    cx: Float,
    top: Float,
    bodyW: Float,
    bodyH: Float
) {
    if (outfit == null) return
    val cream = Color(0xFFF7F1E6)
    when (outfit) {
        PartnerOutfit.NIGHT_CAP -> {
            // 软垂睡帽：帽身向右塌下来，帽尖挂一颗奶白绒球。
            val capW = bodyW * 0.34f
            val capH = bodyH * 0.52f
            val crown = Path().apply {
                moveTo(cx - capW * 0.50f, top + bodyH * 0.01f)
                lineTo(cx - capW * 0.32f, top - capH * 0.56f)
                quadraticBezierTo(cx - capW * 0.20f, top - capH * 0.98f, cx + capW * 0.04f, top - capH * 0.92f)
                quadraticBezierTo(cx + capW * 0.32f, top - capH * 0.84f, cx + capW * 0.44f, top - capH * 0.46f)
                quadraticBezierTo(cx + capW * 0.52f, top - capH * 0.22f, cx + capW * 0.42f, top - capH * 0.06f)
                lineTo(cx + capW * 0.32f, top + bodyH * 0.01f)
                close()
            }
            drawPath(crown, Color(0xFF5C6A96))
            drawPath(
                path = crown,
                color = Color.White.copy(alpha = 0.10f),
                style = Stroke(width = max(1f, bodyH * 0.012f))
            )
            drawLine(
                color = Color(0xFF4A567E).copy(alpha = 0.75f),
                start = Offset(cx - capW * 0.04f, top - capH * 0.88f),
                end = Offset(cx + capW * 0.34f, top - capH * 0.22f),
                strokeWidth = max(1f, bodyH * 0.012f),
                cap = StrokeCap.Round
            )
            drawRoundRect(
                color = cream,
                topLeft = Offset(cx - capW * 0.54f, top - bodyH * 0.075f),
                size = Size(capW * 1.08f, bodyH * 0.13f),
                cornerRadius = CornerRadius(bodyH * 0.065f, bodyH * 0.065f)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.35f),
                topLeft = Offset(cx - capW * 0.48f, top - bodyH * 0.050f),
                size = Size(capW * 0.94f, bodyH * 0.028f),
                cornerRadius = CornerRadius(bodyH * 0.014f, bodyH * 0.014f)
            )
            drawCircle(
                color = cream,
                radius = bodyH * 0.105f,
                center = Offset(cx + capW * 0.46f, top + bodyH * 0.02f)
            )
            drawCircle(
                color = DecoButter.copy(alpha = 0.55f),
                radius = bodyH * 0.042f,
                center = Offset(cx + capW * 0.43f, top - bodyH * 0.015f)
            )
        }
        PartnerOutfit.FOCUS_GOGGLES -> Unit
        PartnerOutfit.CONFETTI -> Unit
        PartnerOutfit.CHARGE_BOLT -> Unit
        PartnerOutfit.NEW_YEAR -> {
            val capW = bodyW * 0.24f
            val capH = bodyH * 0.46f
            val cone = Path().apply {
                moveTo(cx - capW * 0.5f, top)
                lineTo(cx, top - capH)
                lineTo(cx + capW * 0.5f, top)
                close()
            }
            drawPath(cone, DecoButter)
            drawLine(
                color = DecoCoral.copy(alpha = 0.85f),
                start = Offset(cx - capW * 0.26f, top - capH * 0.34f),
                end = Offset(cx + capW * 0.26f, top - capH * 0.34f),
                strokeWidth = max(1f, bodyH * 0.03f)
            )
            drawCircle(color = DecoMint, radius = bodyH * 0.065f, center = Offset(cx, top - capH))
        }
        PartnerOutfit.SPRING_FESTIVAL -> {
            val capW = bodyW * 0.30f
            val domeH = bodyH * 0.17f
            drawArc(
                color = Color(0xFFC9483A),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(cx - capW * 0.5f, top - domeH),
                size = Size(capW, domeH * 2f)
            )
            drawRoundRect(
                color = DecoButter,
                topLeft = Offset(cx - capW * 0.56f, top - bodyH * 0.07f),
                size = Size(capW * 1.12f, bodyH * 0.10f),
                cornerRadius = CornerRadius(bodyH * 0.05f, bodyH * 0.05f)
            )
            val plaque = bodyH * 0.15f
            drawRoundRect(
                color = DecoButter,
                topLeft = Offset(cx - plaque / 2f, top - domeH * 0.92f),
                size = Size(plaque, plaque),
                cornerRadius = CornerRadius(plaque * 0.18f, plaque * 0.18f)
            )
            drawRect(
                color = Color(0xFFC9483A),
                topLeft = Offset(cx - plaque * 0.20f, top - domeH * 0.80f),
                size = Size(plaque * 0.40f, plaque * 0.10f)
            )
            drawRect(
                color = Color(0xFFC9483A),
                topLeft = Offset(cx - plaque * 0.20f, top - domeH * 0.62f),
                size = Size(plaque * 0.40f, plaque * 0.10f)
            )
        }
        PartnerOutfit.QINGMING -> {
            // 纸鸢：更大的菱形风筝，双色纸面 + 骨架 + 短飘带，牵在右肩上。
            val kiteX = cx + bodyW * 0.22f
            val kiteY = top - bodyH * 0.34f
            val s = bodyH * 0.20f
            val kite = Path().apply {
                moveTo(kiteX, kiteY - s)
                lineTo(kiteX + s * 0.78f, kiteY - s * 0.10f)
                lineTo(kiteX, kiteY + s * 0.92f)
                lineTo(kiteX - s * 0.78f, kiteY - s * 0.10f)
                close()
            }
            drawPath(kite, Color(0xFFAFD2BE))
            val rightHalf = Path().apply {
                moveTo(kiteX, kiteY - s)
                lineTo(kiteX + s * 0.78f, kiteY - s * 0.10f)
                lineTo(kiteX, kiteY + s * 0.92f)
                close()
            }
            drawPath(rightHalf, Color(0xFF93BFA9))
            drawLine(
                color = DecoInk.copy(alpha = 0.30f),
                start = Offset(kiteX, kiteY - s),
                end = Offset(kiteX, kiteY + s * 0.92f),
                strokeWidth = max(1f, s * 0.05f)
            )
            drawLine(
                color = DecoInk.copy(alpha = 0.30f),
                start = Offset(kiteX - s * 0.78f, kiteY - s * 0.10f),
                end = Offset(kiteX + s * 0.78f, kiteY - s * 0.10f),
                strokeWidth = max(1f, s * 0.05f)
            )
            val tail = Path().apply {
                moveTo(kiteX, kiteY + s * 0.92f)
                quadraticBezierTo(kiteX + s * 0.42f, kiteY + s * 1.30f, kiteX + s * 0.16f, kiteY + s * 1.62f)
            }
            drawPath(
                path = tail,
                color = DecoInk.copy(alpha = 0.28f),
                style = Stroke(width = max(1f, s * 0.045f), cap = StrokeCap.Round)
            )
            drawCircle(color = DecoButter, radius = s * 0.10f, center = Offset(kiteX + s * 0.28f, kiteY + s * 1.42f))
            drawCircle(color = DecoCoral, radius = s * 0.07f, center = Offset(kiteX + s * 0.12f, kiteY + s * 1.60f))
            drawLine(
                color = DecoInk.copy(alpha = 0.30f),
                start = Offset(kiteX, kiteY + s * 0.92f),
                end = Offset(cx + bodyW * 0.42f, top + bodyH * 0.02f),
                strokeWidth = max(0.8f, bodyH * 0.010f)
            )
        }
        PartnerOutfit.LABOR_DAY -> {
            // 安全帽：圆顶 + 中央帽脊 + 一圈宽帽檐 + 侧面散热孔。
            val capW = bodyW * 0.34f
            val domeH = bodyH * 0.32f
            val dome = Path().apply {
                moveTo(cx - capW * 0.50f, top - bodyH * 0.01f)
                cubicTo(
                    cx - capW * 0.44f, top - domeH * 1.06f,
                    cx + capW * 0.44f, top - domeH * 1.06f,
                    cx + capW * 0.50f, top - bodyH * 0.01f
                )
                close()
            }
            drawPath(dome, DecoButter)
            drawRoundRect(
                color = Color(0xFFE8B85C),
                topLeft = Offset(cx - capW * 0.045f, top - domeH * 0.98f),
                size = Size(capW * 0.09f, domeH * 0.94f),
                cornerRadius = CornerRadius(capW * 0.045f, capW * 0.045f)
            )
            listOf(-1f, 1f).forEach { side ->
                drawLine(
                    color = DecoInk.copy(alpha = 0.18f),
                    start = Offset(cx + side * capW * 0.34f, top - domeH * 0.34f),
                    end = Offset(cx + side * capW * 0.24f, top - domeH * 0.34f),
                    strokeWidth = max(1f, bodyH * 0.022f),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = DecoInk.copy(alpha = 0.18f),
                    start = Offset(cx + side * capW * 0.35f, top - domeH * 0.18f),
                    end = Offset(cx + side * capW * 0.25f, top - domeH * 0.18f),
                    strokeWidth = max(1f, bodyH * 0.022f),
                    cap = StrokeCap.Round
                )
            }
            drawOval(
                color = Color(0xFFD1A047),
                topLeft = Offset(cx - capW * 0.66f, top - bodyH * 0.045f),
                size = Size(capW * 1.32f, bodyH * 0.17f)
            )
            drawOval(
                color = Color(0xFFE3B75B),
                topLeft = Offset(cx - capW * 0.58f, top - bodyH * 0.030f),
                size = Size(capW * 1.16f, bodyH * 0.10f)
            )
        }
        PartnerOutfit.DRAGON_BOAT -> {
            val capW = bodyW * 0.26f
            val capH = bodyH * 0.34f
            val leaf = Path().apply {
                moveTo(cx - capW * 0.5f, top)
                quadraticBezierTo(cx - capW * 0.10f, top - capH * 1.20f, cx, top - capH)
                quadraticBezierTo(cx + capW * 0.10f, top - capH * 1.20f, cx + capW * 0.5f, top)
                close()
            }
            drawPath(leaf, Color(0xFF6E9F6E))
            drawLine(
                color = Color(0xFF4F7A50),
                start = Offset(cx, top - capH * 0.92f),
                end = Offset(cx, top),
                strokeWidth = max(0.8f, bodyH * 0.018f)
            )
            drawLine(
                color = DecoButter.copy(alpha = 0.85f),
                start = Offset(cx - capW * 0.34f, top - capH * 0.22f),
                end = Offset(cx + capW * 0.34f, top - capH * 0.22f),
                strokeWidth = max(0.8f, bodyH * 0.02f)
            )
        }
        PartnerOutfit.MID_AUTUMN -> {
            val earW = bodyW * 0.10f
            val earH = bodyH * 0.56f
            drawEar(cx - bodyW * 0.07f, top, earW, earH, -10f, cream)
            drawEar(cx + bodyW * 0.07f, top, earW, earH, 10f, cream)
        }
        PartnerOutfit.NATIONAL_DAY -> Unit
        PartnerOutfit.CHRISTMAS -> {
            // 圣诞帽：帽身向右垂下来，宽绒边 + 大绒球 + 一小枝冬青。
            val capW = bodyW * 0.34f
            val capH = bodyH * 0.48f
            val hat = Path().apply {
                moveTo(cx - capW * 0.50f, top + bodyH * 0.01f)
                lineTo(cx - capW * 0.34f, top - capH * 0.52f)
                quadraticBezierTo(cx - capW * 0.16f, top - capH * 0.94f, cx + capW * 0.08f, top - capH * 0.80f)
                quadraticBezierTo(cx + capW * 0.44f, top - capH * 0.62f, cx + capW * 0.54f, top - capH * 0.18f)
                quadraticBezierTo(cx + capW * 0.58f, top - capH * 0.02f, cx + capW * 0.46f, top + bodyH * 0.01f)
                close()
            }
            // 帽身用渐变分出受光面，再用深红描边把帽子和暗背景分开。
            drawPath(
                path = hat,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFE0675A), Color(0xFFC04236), Color(0xFF9E3128)),
                    start = Offset(cx - capW * 0.55f, top - capH * 0.55f),
                    end = Offset(cx + capW * 0.55f, top - capH * 0.05f)
                )
            )
            drawPath(
                path = hat,
                color = Color(0xFF7E241C).copy(alpha = 0.55f),
                style = Stroke(width = max(1f, bodyH * 0.014f))
            )
            // 一条高光 + 一条褶皱，避免帽身看起来是一整块平面。
            val hatSheen = Path().apply {
                moveTo(cx - capW * 0.36f, top - capH * 0.10f)
                quadraticBezierTo(cx - capW * 0.20f, top - capH * 0.60f, cx + capW * 0.02f, top - capH * 0.72f)
            }
            drawPath(
                path = hatSheen,
                color = Color.White.copy(alpha = 0.24f),
                style = Stroke(width = max(1f, bodyH * 0.022f), cap = StrokeCap.Round)
            )
            val hatFold = Path().apply {
                moveTo(cx - capW * 0.06f, top - capH * 0.18f)
                quadraticBezierTo(cx + capW * 0.14f, top - capH * 0.34f, cx + capW * 0.40f, top - capH * 0.20f)
            }
            drawPath(
                path = hatFold,
                color = Color(0xFF7E241C).copy(alpha = 0.40f),
                style = Stroke(width = max(1f, bodyH * 0.016f), cap = StrokeCap.Round)
            )
            // 绒球：先垫一层阴影，再加高光，和帽尖拉开层次。
            val pomX = cx + capW * 0.56f
            val pomY = top - capH * 0.04f
            val pomR = bodyH * 0.10f
            drawCircle(
                color = Color(0xFFBFB3A0).copy(alpha = 0.55f),
                radius = pomR,
                center = Offset(pomX + pomR * 0.10f, pomY + pomR * 0.16f)
            )
            drawCircle(color = cream, radius = pomR, center = Offset(pomX, pomY))
            drawCircle(
                color = Color.White.copy(alpha = 0.55f),
                radius = pomR * 0.42f,
                center = Offset(pomX - pomR * 0.26f, pomY - pomR * 0.28f)
            )
            val brimTop = top - bodyH * 0.090f
            // 帽檐在机身和绒边上都投一层影子，边界更清楚。
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.16f),
                topLeft = Offset(cx - capW * 0.56f, brimTop + bodyH * 0.055f),
                size = Size(capW * 1.12f, bodyH * 0.135f),
                cornerRadius = CornerRadius(bodyH * 0.068f, bodyH * 0.068f)
            )
            drawRoundRect(
                color = cream,
                topLeft = Offset(cx - capW * 0.58f, brimTop),
                size = Size(capW * 1.16f, bodyH * 0.15f),
                cornerRadius = CornerRadius(bodyH * 0.075f, bodyH * 0.075f)
            )
            // 帽檐下沿压一条浅阴影，把绒边和下面的绒球分开。
            drawRoundRect(
                color = Color(0xFFDCD1BE),
                topLeft = Offset(cx - capW * 0.58f, brimTop + bodyH * 0.100f),
                size = Size(capW * 1.16f, bodyH * 0.05f),
                cornerRadius = CornerRadius(bodyH * 0.025f, bodyH * 0.025f)
            )
            for (index in 0..4) {
                drawCircle(
                    color = cream,
                    radius = bodyH * 0.042f,
                    center = Offset(cx - capW * 0.48f + index * capW * 0.24f, brimTop + bodyH * 0.115f)
                )
            }
            val hollyX = cx + capW * 0.34f
            val hollyY = brimTop - bodyH * 0.012f
            drawOval(
                color = Color(0xFF5E7C4A),
                topLeft = Offset(hollyX - bodyH * 0.075f, hollyY - bodyH * 0.028f),
                size = Size(bodyH * 0.10f, bodyH * 0.042f)
            )
            drawOval(
                color = Color(0xFF6E8F58),
                topLeft = Offset(hollyX - bodyH * 0.010f, hollyY - bodyH * 0.040f),
                size = Size(bodyH * 0.10f, bodyH * 0.042f)
            )
            drawCircle(
                color = Color(0xFFC0392B),
                radius = bodyH * 0.022f,
                center = Offset(hollyX + bodyH * 0.030f, hollyY + bodyH * 0.004f)
            )
        }
        PartnerOutfit.HALLOWEEN -> {
            val capW = bodyW * 0.30f
            val domeH = bodyH * 0.20f
            drawArc(
                color = Color(0xFFE8933F),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(cx - capW * 0.5f, top - domeH),
                size = Size(capW, domeH * 2f)
            )
            drawRoundRect(
                color = Color(0xFF5E7C4A),
                topLeft = Offset(cx - bodyW * 0.015f, top - domeH * 1.16f),
                size = Size(bodyW * 0.03f, domeH * 0.30f),
                cornerRadius = CornerRadius(bodyW * 0.015f, bodyW * 0.015f)
            )
            val eye = domeH * 0.22f
            val eyePath = Path().apply {
                moveTo(cx - capW * 0.22f, top - domeH * 0.55f)
                lineTo(cx - capW * 0.10f, top - domeH * 0.55f)
                lineTo(cx - capW * 0.16f, top - domeH * 0.34f)
                close()
            }
            val eyePath2 = Path().apply {
                moveTo(cx + capW * 0.10f, top - domeH * 0.55f)
                lineTo(cx + capW * 0.22f, top - domeH * 0.55f)
                lineTo(cx + capW * 0.16f, top - domeH * 0.34f)
                close()
            }
            drawPath(eyePath, Color(0xFF2A2724))
            drawPath(eyePath2, Color(0xFF2A2724))
            drawLine(
                color = Color(0xFF2A2724),
                start = Offset(cx - eye, top - domeH * 0.22f),
                end = Offset(cx + eye, top - domeH * 0.22f),
                strokeWidth = max(1f, bodyH * 0.025f),
                cap = StrokeCap.Round
            )
        }
        else -> Unit
    }
}

private fun DrawScope.drawEar(cx: Float, top: Float, earW: Float, earH: Float, degrees: Float, color: Color) {
    rotate(degrees, pivot = Offset(cx, top)) {
        drawOval(
            color = color,
            topLeft = Offset(cx - earW / 2f, top - earH),
            size = Size(earW, earH)
        )
        drawOval(
            color = Color(0xFFF2B8B0),
            topLeft = Offset(cx - earW * 0.24f, top - earH * 0.86f),
            size = Size(earW * 0.48f, earH * 0.62f)
        )
    }
}

/**
 * 专注护目镜：白色镜框围住屏幕四边，两侧镜架贴着机身伸到红色端帽。
 */
private fun DrawScope.drawGogglesFrame(
    screenLeft: Float,
    screenTop: Float,
    screenW: Float,
    screenH: Float,
    screenRadius: Float,
    bodyLeft: Float,
    bodyW: Float,
    bodyH: Float
) {
    val frame = Color(0xFFF7F1E6)
    val frameW = max(2f, bodyH * 0.078f)
    val armH = max(1.5f, bodyH * 0.16f)
    val armY = screenTop + screenH / 2f - armH / 2f
    val armRadius = CornerRadius(armH / 2f, armH / 2f)

    // 镜架：从镜框两侧贴着机身延伸到端帽，末端收圆。
    val leftArmStart = bodyLeft + bodyW * 0.004f
    val rightArmEnd = bodyLeft + bodyW * 0.996f
    drawRoundRect(
        color = frame,
        topLeft = Offset(leftArmStart, armY),
        size = Size(screenLeft - leftArmStart + frameW * 0.5f, armH),
        cornerRadius = armRadius
    )
    drawRoundRect(
        color = frame,
        topLeft = Offset(screenLeft + screenW - frameW * 0.5f, armY),
        size = Size(rightArmEnd - (screenLeft + screenW) + frameW * 0.5f, armH),
        cornerRadius = armRadius
    )
    // 镜腿末端的一小段深色，像贴在端帽上的收尾。
    val tipW = armH * 0.55f
    val tipColor = Color(0xFFE6D9C6)
    drawRoundRect(
        color = tipColor,
        topLeft = Offset(leftArmStart, armY + armH * 0.10f),
        size = Size(tipW, armH * 0.80f),
        cornerRadius = CornerRadius(armH * 0.30f, armH * 0.30f)
    )
    drawRoundRect(
        color = tipColor,
        topLeft = Offset(rightArmEnd - tipW, armY + armH * 0.10f),
        size = Size(tipW, armH * 0.80f),
        cornerRadius = CornerRadius(armH * 0.30f, armH * 0.30f)
    )

    // 铰链：镜架与镜框衔接处的方形小节点。
    val hinge = frameW * 1.05f
    listOf(screenLeft - frameW * 0.55f, screenLeft + screenW - frameW * 0.50f).forEach { hx ->
        drawRoundRect(
            color = frame,
            topLeft = Offset(hx, armY - hinge * 0.14f),
            size = Size(hinge, armH + hinge * 0.28f),
            cornerRadius = CornerRadius(hinge * 0.30f, hinge * 0.30f)
        )
    }

    // 镜框：沿屏幕外缘一圈，外侧留一点深色阴影让白框立起来。
    drawRoundRect(
        color = DecoInk.copy(alpha = 0.20f),
        topLeft = Offset(screenLeft - frameW * 0.72f, screenTop - frameW * 0.66f),
        size = Size(screenW + frameW * 1.44f, screenH + frameW * 1.32f),
        cornerRadius = CornerRadius(screenRadius + frameW * 0.72f, screenRadius + frameW * 0.72f),
        style = Stroke(width = frameW * 0.34f)
    )
    drawRoundRect(
        color = frame,
        topLeft = Offset(screenLeft - frameW * 0.5f, screenTop - frameW * 0.5f),
        size = Size(screenW + frameW, screenH + frameW),
        cornerRadius = CornerRadius(screenRadius + frameW * 0.5f, screenRadius + frameW * 0.5f),
        style = Stroke(width = frameW)
    )

    // 镜框内缘：一道细暗线，区分玻璃和边框。
    drawRoundRect(
        color = DecoInk.copy(alpha = 0.22f),
        topLeft = Offset(screenLeft, screenTop),
        size = Size(screenW, screenH),
        cornerRadius = CornerRadius(screenRadius, screenRadius),
        style = Stroke(width = max(1f, frameW * 0.16f))
    )

    // 镜框左上角的一小段高光。
    drawRoundRect(
        color = Color.White.copy(alpha = 0.55f),
        topLeft = Offset(screenLeft + screenW * 0.10f, screenTop - frameW * 0.88f),
        size = Size(screenW * 0.26f, frameW * 0.22f),
        cornerRadius = CornerRadius(frameW * 0.11f, frameW * 0.11f)
    )
}

/** 国庆装扮：五角星贴在右侧红色端帽上。 */
private fun DrawScope.drawNationalStar(
    bodyLeft: Float,
    bodyTop: Float,
    bodyW: Float,
    bodyH: Float
) {
    val sx = bodyLeft + bodyW * 0.895f
    val sy = bodyTop + bodyH * 0.32f
    drawPath(starPath(sx, sy, bodyH * 0.098f, bodyH * 0.040f), DecoButter)
    drawPath(
        path = starPath(sx, sy, bodyH * 0.098f, bodyH * 0.040f),
        color = Color(0xFFD9A84E).copy(alpha = 0.55f),
        style = Stroke(width = max(1f, bodyH * 0.012f))
    )
}

/** 装扮背景：主题渐变 + 场景粒子，画在机身后面。 */
private fun DrawScope.drawOutfitBackdrop(
    outfit: PartnerOutfit,
    w: Float,
    h: Float,
    t: Float,
    compact: Boolean
) {
    val center = Offset(w / 2f, h / 2f)
    val r = w * 0.52f
    val colors = when (outfit) {
        PartnerOutfit.NIGHT_CAP -> listOf(Color(0xFF2C3560), Color(0xFF171C3A), Color.Transparent)
        PartnerOutfit.FOCUS_GOGGLES -> listOf(Color(0xFF25484F), Color(0xFF132A30), Color.Transparent)
        PartnerOutfit.CONFETTI -> listOf(Color(0xFFFFF1DD), Color(0xFFF8DCC6), Color.Transparent)
        PartnerOutfit.CHARGE_BOLT -> listOf(Color(0xFF1B4436), Color(0xFF0C221B), Color.Transparent)
        PartnerOutfit.NEW_YEAR -> listOf(Color(0xFFFFF2D9), Color(0xFFFFDFC2), Color.Transparent)
        PartnerOutfit.SPRING_FESTIVAL -> listOf(Color(0xFFD55345), Color(0xFF932F26), Color.Transparent)
        PartnerOutfit.QINGMING -> listOf(Color(0xFFE7F5EC), Color(0xFFC6E1D2), Color.Transparent)
        PartnerOutfit.LABOR_DAY -> listOf(Color(0xFF254650), Color(0xFF152C33), Color.Transparent)
        PartnerOutfit.DRAGON_BOAT -> listOf(Color(0xFFE3F4E7), Color(0xFFC0E2CC), Color.Transparent)
        PartnerOutfit.MID_AUTUMN -> listOf(Color(0xFF28305F), Color(0xFF101534), Color.Transparent)
        PartnerOutfit.NATIONAL_DAY -> listOf(Color(0xFFFFF0D8), Color(0xFFFFD9AE), Color.Transparent)
        PartnerOutfit.CHRISTMAS -> listOf(Color(0xFF1E3B4C), Color(0xFF0D1D28), Color.Transparent)
        PartnerOutfit.HALLOWEEN -> listOf(Color(0xFF382A50), Color(0xFF161024), Color.Transparent)
    }
    drawCircle(
        brush = Brush.radialGradient(colors = colors, center = center, radius = r),
        radius = r,
        center = center
    )
    if (compact) return

    val clip = Path().apply {
        addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r))
    }
    clipPath(clip) {
        when (outfit) {
            PartnerOutfit.NIGHT_CAP, PartnerOutfit.MID_AUTUMN -> {
                drawTwinkleStars(w, h, t, Color(0xFFF7F1E6))
                val moonX = if (outfit == PartnerOutfit.MID_AUTUMN) w * 0.76f else w * 0.22f
                val moonY = if (outfit == PartnerOutfit.MID_AUTUMN) h * 0.20f else h * 0.18f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            DecoButter.copy(alpha = if (outfit == PartnerOutfit.MID_AUTUMN) 0.44f else 0.22f),
                            Color.Transparent
                        ),
                        center = Offset(moonX, moonY),
                        radius = w * 0.24f
                    ),
                    radius = w * 0.24f,
                    center = Offset(moonX, moonY)
                )
                drawCircle(
                    color = DecoButter.copy(alpha = if (outfit == PartnerOutfit.MID_AUTUMN) 0.85f else 0.55f),
                    radius = w * 0.055f,
                    center = Offset(moonX, moonY)
                )
            }
            PartnerOutfit.FOCUS_GOGGLES, PartnerOutfit.LABOR_DAY -> {
                drawGridLines(w, h, DecoMint.copy(alpha = 0.10f))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(DecoButter.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.42f),
                        radius = w * 0.34f
                    ),
                    radius = w * 0.34f,
                    center = Offset(w * 0.5f, h * 0.42f)
                )
            }
            PartnerOutfit.CONFETTI, PartnerOutfit.NEW_YEAR -> {
                drawFloatingConfetti(
                    w, h, t,
                    listOf(DecoCoral, DecoMint, DecoButter, DecoLavender, Color(0xFF7FB8A6))
                )
            }
            PartnerOutfit.CHARGE_BOLT -> {
                repeat(3) { index ->
                    drawCircle(
                        color = DecoMint.copy(alpha = 0.16f - index * 0.04f),
                        radius = w * (0.18f + index * 0.10f),
                        center = center,
                        style = Stroke(width = max(1f, w * 0.008f))
                    )
                }
                repeat(6) { index ->
                    val angle = (index * 60f + t * 360f) * PI.toFloat() / 180f
                    drawCircle(
                        color = DecoButter.copy(alpha = 0.55f),
                        radius = w * 0.010f,
                        center = Offset(
                            center.x + (w * 0.28f * cos(angle)),
                            center.y + (w * 0.22f * sin(angle))
                        )
                    )
                }
            }
            PartnerOutfit.SPRING_FESTIVAL -> {
                repeat(14) { index ->
                    val x = hash01(index * 5 + 1) * w
                    val y = hash01(index * 9 + 4) * h
                    val radius = w * (0.010f + 0.020f * hash01(index * 3 + 2))
                    drawCircle(
                        color = DecoButter.copy(alpha = 0.12f + 0.16f * hash01(index * 7 + 6)),
                        radius = radius,
                        center = Offset(x, y)
                    )
                }
            }
            PartnerOutfit.QINGMING -> {
                repeat(16) { index ->
                    val x = hash01(index * 11 + 2) * w
                    val y = ((hash01(index * 4 + 7) + t * 0.8f) % 1f) * h
                    drawLine(
                        color = Color(0xFF6E9F8C).copy(alpha = 0.30f),
                        start = Offset(x, y),
                        end = Offset(x - w * 0.02f, y + h * 0.055f),
                        strokeWidth = max(1f, w * 0.006f),
                        cap = StrokeCap.Round
                    )
                }
                repeat(4) { index ->
                    val x = w * (0.12f + 0.22f * index)
                    val y = h * (0.20f + 0.14f * hash01(index * 5 + 3))
                    drawOval(
                        color = Color(0xFF7FAE8F).copy(alpha = 0.55f),
                        topLeft = Offset(x, y),
                        size = Size(w * 0.075f, w * 0.034f)
                    )
                }
            }
            PartnerOutfit.DRAGON_BOAT -> {
                repeat(3) { index ->
                    drawArc(
                        color = Color(0xFF6E9F8C).copy(alpha = 0.28f),
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(w * (0.10f + index * 0.12f), h * (0.70f + index * 0.06f)),
                        size = Size(w * 0.34f, h * 0.18f),
                        style = Stroke(width = max(1f, w * 0.008f))
                    )
                }
                repeat(3) { index ->
                    val left = index != 1
                    drawOval(
                        color = Color(0xFF5E8F68).copy(alpha = 0.45f),
                        topLeft = Offset(if (left) w * 0.10f else w * 0.72f, h * (0.24f + index * 0.16f)),
                        size = Size(w * 0.10f, w * 0.040f)
                    )
                }
            }
            PartnerOutfit.NATIONAL_DAY -> {
                drawPixelText(
                    text = "1949.10.01",
                    centerX = w / 2f,
                    centerY = h * 0.18f,
                    cell = w * 0.0155f,
                    color = DecoCoralDeep.copy(alpha = 0.26f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(DecoButter.copy(alpha = 0.34f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.42f),
                        radius = w * 0.36f
                    ),
                    radius = w * 0.36f,
                    center = Offset(w * 0.5f, h * 0.42f)
                )
            }
            PartnerOutfit.CHRISTMAS -> {
                repeat(18) { index ->
                    val x = hash01(index * 7 + 3) * w
                    val y = ((hash01(index * 3 + 8) + t * 0.55f) % 1f) * h
                    drawCircle(
                        color = Color.White.copy(alpha = 0.60f + 0.30f * hash01(index * 5 + 1)),
                        radius = w * (0.006f + 0.008f * hash01(index * 9 + 2)),
                        center = Offset(x, y)
                    )
                }
                repeat(6) { index ->
                    drawCircle(
                        color = DecoButter.copy(alpha = 0.10f),
                        radius = w * (0.05f + 0.04f * hash01(index * 4 + 6)),
                        center = Offset(hash01(index * 13 + 5) * w, h * (0.10f + 0.18f * hash01(index * 2 + 9)))
                    )
                }
            }
            PartnerOutfit.HALLOWEEN -> {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFE8933F).copy(alpha = 0.34f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.34f),
                        radius = w * 0.40f
                    ),
                    radius = w * 0.40f,
                    center = Offset(w * 0.5f, h * 0.34f)
                )
                repeat(3) { index ->
                    drawBat(
                        cx = w * (0.20f + 0.26f * index),
                        cy = h * (0.16f + 0.10f * hash01(index * 6 + 4)),
                        wing = w * (0.035f + 0.014f * hash01(index * 8 + 1))
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawTwinkleStars(w: Float, h: Float, t: Float, color: Color) {
    repeat(16) { index ->
        val x = hash01(index * 7 + 1) * w
        val y = hash01(index * 13 + 5) * h * 0.72f
        val twinkle = 0.30f + 0.55f * (0.5f + 0.5f * sin((t * 2f + index * 0.31f) * 2f * PI.toFloat()))
        drawCircle(
            color = color.copy(alpha = twinkle * 0.85f),
            radius = w * (0.006f + 0.007f * hash01(index * 3 + 2)),
            center = Offset(x, y)
        )
    }
}

private fun DrawScope.drawGridLines(w: Float, h: Float, color: Color) {
    val step = w / 10f
    var x = 0f
    while (x <= w) {
        drawLine(color, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y <= h) {
        drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawFloatingConfetti(
    w: Float,
    h: Float,
    t: Float,
    palette: List<Color>
) {
    repeat(16) { index ->
        val x = hash01(index * 3 + 1) * w
        val y = ((hash01(index * 7 + 4) + t * 0.22f) % 1f) * h
        val pieceW = w * (0.020f + 0.014f * hash01(index * 5 + 2))
        drawRoundRect(
            color = palette[index % palette.size].copy(alpha = 0.85f),
            topLeft = Offset(x, y),
            size = Size(pieceW, pieceW * 0.52f),
            cornerRadius = CornerRadius(pieceW * 0.22f, pieceW * 0.22f)
        )
    }
}

private fun DrawScope.drawBat(cx: Float, cy: Float, wing: Float) {
    val bat = Path().apply {
        moveTo(cx, cy + wing * 0.24f)
        lineTo(cx - wing * 0.28f, cy - wing * 0.10f)
        lineTo(cx - wing * 0.52f, cy - wing * 0.30f)
        lineTo(cx - wing * 0.46f, cy + wing * 0.22f)
        lineTo(cx - wing * 0.16f, cy + wing * 0.36f)
        lineTo(cx, cy + wing * 0.62f)
        lineTo(cx + wing * 0.16f, cy + wing * 0.36f)
        lineTo(cx + wing * 0.46f, cy + wing * 0.22f)
        lineTo(cx + wing * 0.52f, cy - wing * 0.30f)
        lineTo(cx + wing * 0.28f, cy - wing * 0.10f)
        close()
    }
    drawPath(bat, Color(0xFF1B1428).copy(alpha = 0.75f))
}

/** 稳定的伪随机：同一个 index 每帧位置一致，避免粒子抖动。 */
private fun hash01(seed: Int): Float {
    var x = seed * 374761393 + 668265263
    x = (x xor (x shr 13)) * 1274126177
    return ((x xor (x shr 16)) and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
}

/** 3×5 像素字模，仅用于国庆背景的 1949.10.01。 */
// 装扮与屏幕表情使用固定的装饰色，不随版本变色；版本只影响外壳。
private val DecoCoral = Color(0xFFE8735A)
private val DecoCoralDeep = Color(0xFFC9553E)
private val DecoMint = Color(0xFF7FB6A4)
private val DecoButter = Color(0xFFF2C879)
private val DecoInk = Color(0xFF2B2724)
private val DecoLavender = Color(0xFFA99BC9)

private val PIXEL_FONT = mapOf(
    '0' to listOf("###", "#.#", "#.#", "#.#", "###"),
    '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
    '2' to listOf("###", "..#", "###", "#..", "###"),
    '3' to listOf("###", "..#", "###", "..#", "###"),
    '4' to listOf("#.#", "#.#", "###", "..#", "..#"),
    '5' to listOf("###", "#..", "###", "..#", "###"),
    '6' to listOf("###", "#..", "###", "#.#", "###"),
    '7' to listOf("###", "..#", "..#", "..#", "..#"),
    '8' to listOf("###", "#.#", "###", "#.#", "###"),
    '9' to listOf("###", "#.#", "###", "..#", "###"),
    '.' to listOf("...", "...", "...", "...", ".#.")
)

private fun DrawScope.drawPixelText(
    text: String,
    centerX: Float,
    centerY: Float,
    cell: Float,
    color: Color
) {
    val cols = text.length * 4 - 1
    val left = centerX - cols * cell / 2f
    val top = centerY - 2.5f * cell
    text.forEachIndexed { index, char ->
        val glyph = PIXEL_FONT[char] ?: return@forEachIndexed
        val glyphLeft = left + index * 4 * cell
        glyph.forEachIndexed { row, line ->
            line.forEachIndexed { col, cellChar ->
                if (cellChar == '#') {
                    drawRect(
                        color = color,
                        topLeft = Offset(glyphLeft + col * cell, top + row * cell),
                        size = Size(cell * 0.92f, cell * 0.92f)
                    )
                }
            }
        }
    }
}

/** 充电时左侧端帽上的闪电徽章。 */
private fun DrawScope.drawChargeBadge(cx: Float, cy: Float, r: Float) {
    drawCircle(color = DecoButter, radius = r, center = Offset(cx, cy))
    drawCircle(
        color = Color.White.copy(alpha = 0.35f),
        radius = r * 0.82f,
        center = Offset(cx - r * 0.18f, cy - r * 0.18f)
    )
    val bolt = Path().apply {
        moveTo(cx + r * 0.16f, cy - r * 0.66f)
        lineTo(cx - r * 0.36f, cy + r * 0.08f)
        lineTo(cx - r * 0.02f, cy + r * 0.08f)
        lineTo(cx - r * 0.16f, cy + r * 0.66f)
        lineTo(cx + r * 0.36f, cy - r * 0.10f)
        lineTo(cx + r * 0.02f, cy - r * 0.10f)
        close()
    }
    drawPath(bolt, DecoCoralDeep)
}

/** 8×8 像素点阵眼：每只眼 8 行 × 8 列，'#' 为发光像素。 */
private val OPEN_EYE = listOf(
    "..####..",
    ".######.",
    "########",
    "########",
    "########",
    "########",
    ".######.",
    "..####.."
)
private val BLINK_EYE = listOf(
    "........",
    "........",
    "........",
    "########",
    "########",
    "........",
    "........",
    "........"
)
private val HAPPY_EYE = listOf(
    "........",
    "...##...",
    "..####..",
    ".##..##.",
    "##....##",
    "........",
    "........",
    "........"
)
private val ALERT_EYE = listOf(
    ".######.",
    "########",
    "########",
    "########",
    "########",
    "########",
    "########",
    ".######."
)
private val SLEEPY_EYE = listOf(
    "........",
    "........",
    "........",
    ".######.",
    ".######.",
    "........",
    "........",
    "........"
)

/** 专注：边缘收窄、目光稳定，配合护目镜。 */
private val FOCUS_EYE = listOf(
    "........",
    ".######.",
    "########",
    "########",
    "########",
    "########",
    ".######.",
    "........"
)

/** 腮红：两行像素块，只在开心、提醒、充电和好奇时出现。 */
private val BLUSH = listOf(
    "#.#",
    "###"
)

/** 完成装扮专用：眼睛下方的一张像素嘴，右端上挑像个小勾。 */
private val SMILE_MOUTH = listOf(
    "........##",
    "##.....#..",
    ".##...#...",
    "..#####..."
)

private fun DrawScope.drawPixelPattern(
    pattern: List<String>,
    centerX: Float,
    centerY: Float,
    pixel: Float,
    color: Color
) {
    val top = centerY - pattern.size * pixel / 2f
    val left = centerX - pattern.first().length * pixel / 2f
    pattern.forEachIndexed { row, line ->
        line.forEachIndexed { col, cell ->
            if (cell == '#') {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left + col * pixel, top + row * pixel),
                    size = Size(pixel * 0.86f, pixel * 0.86f),
                    cornerRadius = CornerRadius(pixel * 0.18f, pixel * 0.18f)
                )
            }
        }
    }
}

private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path {
    val path = Path()
    for (index in 0 until 10) {
        val angle = (-90.0 + index * 36.0) * PI / 180.0
        val radius = if (index % 2 == 0) outer else inner
        val x = cx + (radius * cos(angle)).toFloat()
        val y = cy + (radius * sin(angle)).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
