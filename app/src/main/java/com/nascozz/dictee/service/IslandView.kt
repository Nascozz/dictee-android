package com.nascozz.dictee.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.nascozz.dictee.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pilule noire façon « Dynamic Island » qui sort du trou de la caméra frontale.
 * Le contenu est réparti de part et d'autre de la caméra : icône/état à gauche, onde/libellé à droite.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context) : View(context) {

    interface Listener {
        fun onBubbleTap()
        fun onBubbleLongPress()
        /** Tap sur la moitié droite quand une mise à jour est proposée. */
        fun onUpdateTap()
    }

    /** LIVE = enregistrement avec sous-titres en direct : l'île s'agrandit vers le bas. */
    /** UPDATE = repos + mise à jour disponible : micro à gauche, « ↓ Mise à jour » à droite. */
    enum class State { HIDDEN, IDLE, RECORDING, LIVE, TRANSCRIBING, SUCCESS, ERROR, UPDATE }

    var listener: Listener? = null

    /** Appelé quand la fenêtre doit changer de taille (centrée sur la caméra, haut fixe). */
    var onSizeNeeded: ((Int, Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val margin = (4 * dp).roundToInt()

    /** Diamètre du trou de la caméra (0 s'il n'y en a pas : la pilule naît alors de rien). */
    private var hole = 0f
    private var pillH = 34 * dp
    val windowHeight get() = (pillH + 2 * margin).roundToInt()
    private val captionLines = 3
    private val captionPad = 16 * dp
    private val captionTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val captionLineH = captionTextPaint.fontSpacing * 1.12f
    private val captionH get() = captionLines * captionLineH + captionPad * 1.4f
    private var caption = ""
    private var captionLayout: StaticLayout? = null

    var state = State.HIDDEN
        private set
    private var label: String? = null

    private var curW = 0f
    private var curH = 0f
    private var contentAlpha = 0f
    private var morphAnim: ValueAnimator? = null
    private var alphaAnim: ValueAnimator? = null
    private var morphToken = 0

    private var checkProgress = 0f
    private var recordStart = 0L
    private val levels = FloatArray(20)
    private var smoothLevel = 0f

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate().apply { setTint(Color.WHITE) }
    private val rect = RectF()
    private val checkPath = Path()
    private val checkSeg = Path()

    private var longPressFired = false
    private val longPress = Runnable {
        longPressFired = true
        animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        listener?.onBubbleLongPress()
    }

    init {
        contentDescription = "Dictée : touche pour dicter"
        isClickable = true
    }

    fun setHole(diameter: Float) {
        hole = diameter
        pillH = (diameter + 10 * dp).coerceIn(32 * dp, 40 * dp)
        if (state == State.HIDDEN) {
            curW = hole
            curH = hole
        }
    }

    /** Largeur de fenêtre minimale pour la taille de pilule actuelle. */
    fun windowWidthFor(pillWidth: Float) = (ceil(pillWidth) + 2 * margin).toInt().let { it + (it and 1) }
    private fun windowHeightFor(h: Float) = (ceil(max(h, pillH)) + 2 * margin).toInt()

    /** Texte en direct affiché sous la pilule (état LIVE). */
    fun setCaption(text: String) {
        caption = text
        captionLayout = null
        invalidate()
    }

    private fun liveWidth(): Float = min(hole + 290 * dp, resources.displayMetrics.widthPixels - 20 * dp)

    private fun targetWidth(s: State): Float = when (s) {
        State.HIDDEN -> hole
        State.IDLE -> hole + 88 * dp
        State.RECORDING -> hole + 188 * dp
        State.LIVE -> liveWidth()
        State.TRANSCRIBING, State.SUCCESS, State.ERROR -> hole + 128 * dp
        State.UPDATE -> hole + 224 * dp
    }

    /**
     * Change d'état en faisant « respirer » la pilule vers sa nouvelle taille.
     * [onSettled] est appelé à la fin de l'animation si aucun autre changement n'est arrivé entre-temps.
     */
    fun setState(s: State, text: String? = null, onSettled: (() -> Unit)? = null) {
        val previous = state
        state = s
        label = text
        if (s == State.RECORDING) caption = ""
        if (s == State.RECORDING && previous != State.LIVE) {
            levels.fill(0f)
            smoothLevel = 0f
            recordStart = SystemClock.uptimeMillis()
        }
        if (s == State.SUCCESS) {
            checkProgress = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                startDelay = 140
                interpolator = DecelerateInterpolator()
                addUpdateListener { checkProgress = it.animatedValue as Float; invalidate() }
            }.start()
        }

        val toW = targetWidth(s)
        val toH = when (s) {
            State.HIDDEN -> hole
            State.LIVE -> pillH + captionH
            else -> pillH
        }
        val growing = toW >= curW || toH > curH + 1

        // Le contenu s'efface puis réapparaît une fois la pilule à sa nouvelle taille
        // (sauf entre enregistrement et sous-titres, où le chrono reste affiché).
        val recordingPair = previous in RECORDING_STATES && s in RECORDING_STATES
        animateContent(
            if (s == State.HIDDEN) 0f else 1f,
            from = if (previous != s && !recordingPair) 0f else contentAlpha,
            delay = if (growing) 120 else 60,
        )

        val token = ++morphToken
        morphAnim?.cancel()
        if (growing) onSizeNeeded?.invoke(windowWidthFor(max(toW, curW) * 1.08f), windowHeightFor(max(toH, curH) * 1.04f))
        val fromW = curW
        val fromH = curH
        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (growing) 460 else 320
            interpolator = if (growing) OvershootInterpolator(1.6f) else PathInterpolator(0.3f, 0f, 0.1f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                curW = max(0f, fromW + (toW - fromW) * f)
                curH = max(0f, fromH + (toH - fromH) * min(1f, f))
                invalidate()
            }
            doOnEnd {
                if (token != morphToken) return@doOnEnd
                onSizeNeeded?.invoke(windowWidthFor(max(toW, hole)), windowHeightFor(toH))
                onSettled?.invoke()
            }
            start()
        }
        invalidate()
    }

    fun addLevel(raw: Float) {
        smoothLevel = smoothLevel * 0.45f + raw * 0.55f
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = smoothLevel
        invalidate()
    }

    fun shake() {
        val d = 6 * dp
        ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -d, d, -d * 0.6f, d * 0.6f, -d * 0.25f, 0f)
            .setDuration(420)
            .start()
    }

    private fun animateContent(to: Float, from: Float, delay: Long) {
        alphaAnim?.cancel()
        contentAlpha = from
        alphaAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            startDelay = delay
            addUpdateListener { contentAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // --- Dessin --------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pivotX = w / 2f
        pivotY = margin + pillH / 2f
    }

    override fun onDraw(canvas: Canvas) {
        if (curW <= 0.5f || curH <= 0.5f) return
        val cx = width / 2f
        // Le haut de l'île reste collé à la caméra ; elle ne grandit que vers le bas.
        val cy = margin + pillH / 2f
        val top = margin + max(0f, (pillH - curH) / 2f)
        rect.set(cx - curW / 2, top, cx + curW / 2, top + curH)
        val radius = min(curH / 2, pillH / 2)
        canvas.drawRoundRect(rect, radius, radius, pillPaint)
        if (curH > pillH + 4 * dp) drawCaption(canvas, rect)

        val a = contentAlpha
        if (a > 0.01f && curW > hole + 24 * dp) {
            // Zones de part et d'autre de la caméra.
            val leftStart = rect.left + curH * 0.42f
            val leftEnd = cx - hole / 2 - 6 * dp
            val rightStart = cx + hole / 2 + 6 * dp
            val rightEnd = rect.right - curH * 0.42f
            val lc = (leftStart + leftEnd) / 2
            val rc = (rightStart + rightEnd) / 2

            when (state) {
                State.IDLE -> {
                    drawMic(canvas, lc, cy, a)
                    drawMiniWave(canvas, rc, cy, a)
                }
                State.RECORDING, State.LIVE -> {
                    drawTimer(canvas, lc, cy, a)
                    drawBars(canvas, rightStart, rightEnd, cy, a)
                }
                State.TRANSCRIBING -> {
                    drawSpinner(canvas, lc, cy, a)
                    drawDots(canvas, rc, cy, a)
                }
                State.SUCCESS -> {
                    drawCheck(canvas, lc, cy, a)
                    drawLabel(canvas, rc, cy, label ?: "Inséré", GREEN, a)
                }
                State.ERROR -> {
                    drawBang(canvas, lc, cy, a)
                    drawLabel(canvas, rc, cy, label ?: "Erreur", RED_SOFT, a)
                }
                State.UPDATE -> {
                    drawMic(canvas, lc, cy, a)
                    // Flèche + libellé, centrés ensemble dans la moitié droite.
                    val text = label ?: "Mise à jour"
                    textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, resources.displayMetrics)
                    val group = 12 * dp + 7 * dp + textPaint.measureText(text)
                    val start = rc - group / 2
                    drawDownArrow(canvas, start + 6 * dp, cy, a)
                    drawLabel(canvas, start + 19 * dp + textPaint.measureText(text) / 2, cy, text, Color.WHITE, a)
                    textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
                }
                State.HIDDEN -> Unit
            }
        }
        // Chrono, onde et spinner : 30 images/s suffisent (au lieu de 60 à 120 selon l'écran).
        if (state in RECORDING_STATES || state == State.TRANSCRIBING) postInvalidateDelayed(FRAME_MS)
    }

    /** Les 3 dernières lignes du texte en direct, avec un curseur rouge qui clignote. */
    private fun drawCaption(canvas: Canvas, r: RectF) {
        val visible = ((r.height() - pillH) / captionH).coerceIn(0f, 1f)
        if (visible <= 0.05f || caption.isEmpty()) return
        val width = (liveWidth() - 2 * captionPad).toInt()
        val layout = captionLayout ?: StaticLayout.Builder
            .obtain(caption, 0, caption.length, captionTextPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.12f)
            .build()
            .also { captionLayout = it }
        val left = r.centerX() - width / 2f
        val areaTop = r.top + pillH + 2 * dp
        val areaBottom = r.bottom - captionPad * 0.7f
        val first = max(0, layout.lineCount - captionLines)
        canvas.save()
        canvas.clipRect(left - 4 * dp, areaTop, left + width + 4 * dp, areaBottom)
        canvas.translate(left, areaTop - layout.getLineTop(first))
        captionTextPaint.alpha = (255 * visible * contentAlpha).toInt()
        layout.draw(canvas)
        if (state == State.LIVE && (SystemClock.uptimeMillis() / 530) % 2 == 0L) {
            val last = layout.lineCount - 1
            val x = layout.getLineRight(last) + 3 * dp
            fill.color = RED
            fill.alpha = (255 * visible).toInt()
            canvas.drawRoundRect(
                x, layout.getLineTop(last) + 3 * dp, x + 2.2f * dp, layout.getLineBottom(last) - 3 * dp,
                1.1f * dp, 1.1f * dp, fill,
            )
        }
        canvas.restore()
    }

    private fun drawMic(canvas: Canvas, x: Float, y: Float, a: Float) {
        val half = (9.5f * dp).roundToInt()
        mic.alpha = (255 * a).toInt()
        mic.setBounds(x.roundToInt() - half, y.roundToInt() - half, x.roundToInt() + half, y.roundToInt() + half)
        mic.draw(canvas)
    }

    private fun drawMiniWave(canvas: Canvas, x: Float, y: Float, a: Float) {
        val hs = floatArrayOf(6f, 12f, 8f, 14f, 7f)
        barPaint.color = RED
        barPaint.alpha = (255 * a * 0.85f).toInt()
        barPaint.strokeWidth = 2.4f * dp
        val step = 4.5f * dp
        val start = x - step * (hs.size - 1) / 2
        hs.forEachIndexed { i, h ->
            val hh = h * dp / 2
            canvas.drawLine(start + i * step, y - hh, start + i * step, y + hh, barPaint)
        }
    }

    private fun drawTimer(canvas: Canvas, x: Float, y: Float, a: Float) {
        val elapsed = (SystemClock.uptimeMillis() - recordStart) / 1000
        val text = "%d:%02d".format(elapsed / 60, elapsed % 60)
        val tw = textPaint.measureText(text)
        val dot = 4f * dp
        val gap = 7f * dp
        val start = x - (dot * 2 + gap + tw) / 2
        val pulse = 0.55f + 0.45f * abs(sin(SystemClock.uptimeMillis() / 380.0)).toFloat()
        fill.color = RED
        fill.alpha = (255 * a * pulse).toInt()
        canvas.drawCircle(start + dot, y, dot, fill)
        textPaint.alpha = (255 * a).toInt()
        canvas.drawText(text, start + dot * 2 + gap, y - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
    }

    private fun drawBars(canvas: Canvas, start: Float, end: Float, y: Float, a: Float) {
        val span = end - start
        if (span < 12 * dp) return
        val n = levels.size
        val step = span / n
        barPaint.color = RED
        barPaint.strokeWidth = min(2.6f * dp, step * 0.6f)
        val maxH = curH * 0.56f
        for (i in 0 until n) {
            val fade = 0.45f + 0.55f * (i / (n - 1f))
            barPaint.alpha = (255 * a * fade).toInt()
            val h = max(2.6f * dp, levels[i] * maxH)
            val bx = start + step * (i + 0.5f)
            canvas.drawLine(bx, y - h / 2, bx, y + h / 2, barPaint)
        }
    }

    private fun drawSpinner(canvas: Canvas, x: Float, y: Float, a: Float) {
        val t = SystemClock.uptimeMillis()
        val r = 7.5f * dp
        stroke.color = Color.WHITE
        stroke.alpha = (255 * a).toInt()
        rect.set(x - r, y - r, x + r, y + r)
        canvas.drawArc(rect, (t % 900) / 900f * 360f, 70f + 170f * abs(sin(t / 520.0)).toFloat(), false, stroke)
    }

    private fun drawDots(canvas: Canvas, x: Float, y: Float, a: Float) {
        val t = SystemClock.uptimeMillis()
        val step = 9 * dp
        for (i in 0..2) {
            val phase = sin((t / 180.0) - i * 0.9).toFloat()
            fill.color = RED
            fill.alpha = (255 * a * (0.35f + 0.65f * max(0f, phase))).toInt()
            canvas.drawCircle(x + (i - 1) * step, y - max(0f, phase) * 2.5f * dp, 2.6f * dp, fill)
        }
    }

    private fun drawCheck(canvas: Canvas, x: Float, y: Float, a: Float) {
        checkPath.reset()
        checkPath.moveTo(x - 6.5f * dp, y + 0.5f * dp)
        checkPath.lineTo(x - 2f * dp, y + 5f * dp)
        checkPath.lineTo(x + 7f * dp, y - 5f * dp)
        val pm = PathMeasure(checkPath, false)
        checkSeg.reset()
        pm.getSegment(0f, pm.length * checkProgress, checkSeg, true)
        stroke.color = GREEN
        stroke.alpha = (255 * a).toInt()
        canvas.drawPath(checkSeg, stroke)
    }

    private fun drawDownArrow(canvas: Canvas, x: Float, y: Float, a: Float) {
        stroke.color = RED
        stroke.alpha = (255 * a).toInt()
        canvas.drawLine(x, y - 7 * dp, x, y + 5 * dp, stroke)
        canvas.drawLine(x - 5 * dp, y, x, y + 5 * dp, stroke)
        canvas.drawLine(x + 5 * dp, y, x, y + 5 * dp, stroke)
    }

    private fun drawBang(canvas: Canvas, x: Float, y: Float, a: Float) {
        stroke.color = RED_SOFT
        stroke.alpha = (255 * a).toInt()
        canvas.drawLine(x, y - 7 * dp, x, y + 2 * dp, stroke)
        fill.color = RED_SOFT
        fill.alpha = (255 * a).toInt()
        canvas.drawCircle(x, y + 6.5f * dp, 1.6f * dp, fill)
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, text: String, color: Int, a: Float) {
        textPaint.color = color
        textPaint.alpha = (255 * a).toInt()
        val tw = textPaint.measureText(text)
        canvas.drawText(text, x - tw / 2, y - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        textPaint.color = Color.WHITE
    }

    // --- Toucher -------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFired = false
                animate().scaleX(0.94f).scaleY(0.94f).setInterpolator(DecelerateInterpolator()).setDuration(90).start()
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(280).start()
                if (!longPressFired) {
                    if (state == State.UPDATE && e.x > width / 2f) listener?.onUpdateTap() else performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onBubbleTap()
        return true
    }

    private companion object {
        const val FRAME_MS = 33L
        val RECORDING_STATES = setOf(State.RECORDING, State.LIVE)
        val RED = 0xFFFF4D6D.toInt()
        val RED_SOFT = 0xFFFF7A8E.toInt()
        val GREEN = 0xFF4ADE80.toInt()
    }
}
