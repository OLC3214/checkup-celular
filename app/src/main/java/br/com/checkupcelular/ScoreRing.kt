package br.com.checkupcelular

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Anel circular com a nota de saúde do celular (0–100). score = -1 mostra "—". */
class ScoreRing(context: Context) : View(context) {

    var score = -1
        set(v) { field = v; invalidate() }
    var ringColor = 0xFF2563EB.toInt()
        set(v) { field = v; invalidate() }
    var trackColor = 0x22000000
    var textColor = 0xFF14171C.toInt()
    var mutedColor = 0xFF6B7280.toInt()
    var caption = "saúde"

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val stroke = 10 * d
        val pad = stroke / 2 + 2 * d
        val left = (width - size) / 2
        val top = (height - size) / 2
        box.set(left + pad, top + pad, left + size - pad, top + size - pad)

        track.strokeWidth = stroke; track.color = trackColor
        arc.strokeWidth = stroke; arc.color = ringColor
        c.drawArc(box, 135f, 270f, false, track)
        if (score >= 0) c.drawArc(box, 135f, 270f * score / 100f, false, arc)

        big.textSize = size * 0.30f; big.color = textColor
        small.textSize = 13 * d; small.color = mutedColor
        val cy = height / 2f
        c.drawText(if (score >= 0) "$score" else "—", width / 2f, cy + big.textSize * 0.32f, big)
        c.drawText(caption.uppercase(), width / 2f, cy + big.textSize * 0.32f + 22 * d, small)
    }
}
