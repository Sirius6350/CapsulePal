package com.capsulepal.app.notifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue

/**
 * 手绘充电电池图标，用作实况通知卡片右上角的大图标。
 * 按当前电量填充，不依赖任何位图资源。
 */
object ChargingIconFactory {

    private const val ACCENT = "#E8735A"

    fun render(context: Context, levelPct: Int): Bitmap {
        val size = dp(context, 48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val stroke = size * 0.055f
        val radius = size * 0.14f
        val body = RectF(size * 0.10f, size * 0.26f, size * 0.80f, size * 0.74f)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT)
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT)
            style = Paint.Style.FILL
        }

        canvas.drawRoundRect(body, radius, radius, outline)
        // 正极触点
        canvas.drawRoundRect(
            RectF(size * 0.83f, size * 0.42f, size * 0.91f, size * 0.58f),
            size * 0.02f,
            size * 0.02f,
            fill
        )

        val inset = stroke * 1.8f
        val usableWidth = body.width() - inset * 2f
        val level = levelPct.coerceIn(0, 100) / 100f
        if (level > 0f) {
            val fillRect = RectF(
                body.left + inset,
                body.top + inset,
                body.left + inset + usableWidth * level,
                body.bottom - inset
            )
            canvas.drawRoundRect(fillRect, radius * 0.5f, radius * 0.5f, fill)
        }

        return bitmap
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
