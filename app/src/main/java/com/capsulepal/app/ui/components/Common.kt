package com.capsulepal.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    enabled: Boolean = true,
    // 默认也用圆角，避免点击时出现一个大方块灰色水波纹。
    shape: Shape = RoundedCornerShape(18.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = if (pressed) 0.9f else 0.32f,
            stiffness = if (pressed) 900f else 420f
        ),
        label = "bounceScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        // 先按绘制形状裁剪，点击反馈不会溢出圆角/椭圆之外。
        .clip(shape)
        .then(
            if (onLongClick != null) {
                Modifier.combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
            } else {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick
                )
            }
        )
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = color
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    @androidx.annotation.DrawableRes iconRes: Int? = null
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color,
        contentColor = contentColor
    ) {
        if (iconRes == null) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    strokeWidth: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    center: @Composable BoxScope.() -> Unit = {}
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 650),
        label = "ringProgress"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        center()
    }
}

@Composable
fun AnimatedCheck(
    checked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 380),
        label = "checkDraw"
    )
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w * 0.14f).coerceAtLeast(1.5f)
        if (progress > 0f) {
            val first = Path().apply {
                moveTo(w * 0.18f, h * 0.53f)
                lineTo(w * 0.42f, h * 0.76f)
            }
            val second = Path().apply {
                moveTo(w * 0.42f, h * 0.76f)
                lineTo(w * 0.84f, h * 0.26f)
            }
            val firstFraction = (progress / 0.42f).coerceIn(0f, 1f)
            val secondFraction = ((progress - 0.42f) / 0.58f).coerceIn(0f, 1f)
            if (firstFraction > 0f) {
                drawPath(
                    path = first,
                    color = color,
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                        miter = 1f
                    ),
                    alpha = 1f
                )
            }
            if (secondFraction > 0f) {
                drawLine(
                    color = color,
                    start = Offset(w * 0.42f, h * 0.76f),
                    end = Offset(
                        w * 0.42f + (w * 0.42f) * secondFraction,
                        h * 0.76f - (h * 0.50f) * secondFraction
                    ),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            if (firstFraction < 1f) {
                val end = Offset(
                    w * 0.18f + (w * 0.24f) * firstFraction,
                    h * 0.53f + (h * 0.23f) * firstFraction
                )
                drawLine(
                    color = color.copy(alpha = 0f),
                    start = Offset(w * 0.18f, h * 0.53f),
                    end = end,
                    strokeWidth = stroke
                )
            }
        }
    }
}

@Composable
fun ElasticSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val offset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "switchOffset"
    )
    val trackColor = if (checked) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (checked) Color.White else MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 48.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(CircleShape)
                .background(trackColor)
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val stretch = 1f + 0.35f * sin(offset * PI.toFloat()).coerceAtLeast(0f)
            Box(
                modifier = Modifier
                    .offset(x = (20.dp * offset))
                    .size(width = 26.dp * stretch, height = 26.dp)
                    .clip(CircleShape)
                    .background(thumbColor)
            )
        }
    }
}

@Composable
fun NumberTicker(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = MaterialTheme.colorScheme.onBackground,
    suffix: String = ""
) {
    val animatedValue by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 500),
        label = "numberTicker"
    )
    AnimatedContent(
        targetState = animatedValue,
        transitionSpec = {
            (slideInVertically { height -> height / 2 } + fadeIn(tween(180)))
                .togetherWith(slideOutVertically { height -> -height / 2 } + fadeOut(tween(140)))
        },
        label = "numberTickerContent",
        modifier = modifier
    ) { number ->
        Text(
            text = "$number$suffix",
            style = style.copy(fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1
        )
    }
}

@Composable
fun MiniSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxValue: Float = 120f
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = stepX * index
            val y = size.height - (value.coerceIn(0f, maxValue) / maxValue) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
        val lastX = size.width
        val lastY = size.height - (values.last().coerceIn(0f, maxValue) / maxValue) * size.height
        drawCircle(color = color, radius = 3.5.dp.toPx(), center = Offset(lastX, lastY))
    }
}

@Composable
fun InlineStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun Space(width: Dp = 0.dp, height: Dp = 0.dp) {
    Spacer(Modifier.width(width).height(height))
}
