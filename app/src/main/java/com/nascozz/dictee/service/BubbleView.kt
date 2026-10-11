package com.nascozz.dictee.service

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.nascozz.dictee.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * La petite bulle flottante. Ronde au repos, elle s'étire en pilule avec une onde sonore
 * pendant l'enregistrement, puis affiche un spinner, une coche ou une erreur.
 * Toute la partie visuelle est dessinée à la main pour rester fluide et légère.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : View(context) {

    interface Listener {
        fun onBubbleTap()
        fun onBubbleLongPress()
    }

    enum class State { IDLE, RECORDING, TRANSCRIBING, SUCCESS, ERROR }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density
    private val diameter = 42f * dp
    val margin = (9 * dp).roundToInt()
    val collapsedSize = (diameter + margin * 2).roundToInt()
    val expandedWidth = (178 * dp).roundToInt()

    var state = State.IDLE
        private set

    private var expand = 0f
    private var expandToken = 0
    private var expandAnim: ValueAnimator? = null
    private var colorAnim: ValueAnimator? = null
    private var topColor = IDLE_TOP
    private var bottomColor = IDLE_BOTTOM
    private var checkProgress = 0f
    private val levels = FloatArray(26)
    private var smoothLevel = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(7 * dp, 0f, 2.5f * dp, 0x55000000)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private val rect = RectF()
    private val checkPath = Path()
    private val checkSegment = Path()

    private val longPress = Runnable {
        longPressFired = true
        animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        listener?.onBubbleLongPress()
    }
    private var longPressFired = false

    init {
        contentDescription = "Dictée : touche pour dicter"
        isClickable = true
        isHapticFeedbackEnabled = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pivotX = w - collapsedSize / 2f
        pivotY = h / 2f
    }

    // --- API publique -------------------------------------------------------------------------

    fun setState(s: State) {
        if (s == state) return
        state = s
        val (t, b) = when (s) {
            State.IDLE, State.TRANSCRIBING -> IDLE_TOP to IDLE_BOTTOM
            State.RECORDING -> REC_TOP to REC_BOTTOM
            State.SUCCESS -> OK_TOP to OK_BOTTOM
            State.ERROR -> ERR_TOP to ERR_BOTTOM
        }
        animateColors(t, b)
        if (s == State.RECORDING) {
            levels.fill(0f)
            smoothLevel = 0f
        }
        if (s == State.SUCCESS) {
            checkProgress = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 340
                startDelay = 60
                interpolator = DecelerateInterpolator()
                addUpdateListener { checkProgress = it.animatedValue as Float; invalidate() }
            }.start()
        }
        invalidate()
    }

    fun addLevel(raw: Float) {
        smoothLevel = smoothLevel * 0.45f + raw * 0.55f
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = smoothLevel
        invalidate()
    }

    /** Étire (true) ou replie (false) la pilule. [onEnd] n'est appelé que si l'animation n'a pas été remplacée. */
    fun expand(open: Boolean, onEnd: (() -> Unit)? = null) {
        val token = ++expandToken
        expandAnim?.cancel()
        expandAnim = ValueAnimator.ofFloat(expand, if (open) 1f else 0f).apply {
            duration = if (open) 340 else 260
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { expand = it.animatedValue as Float; invalidate() }
            doOnEnd { if (token == expandToken) onEnd?.invoke() }
            start()
        }
    }

    val isExpanded get() = expand > 0f

    fun popIn() {
        animate().cancel()
        scaleX = 0.3f
        scaleY = 0.3f
        alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(OvershootInterpolator(2.4f))
            .setDuration(340)
            .start()
    }

    fun popOut(onEnd: () -> Unit) {
        animate().cancel()
        animate().scaleX(0.4f).scaleY(0.4f).alpha(0f)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(150)
            .withEndAction(onEnd)
            .start()
    }

    fun shake() {
        val d = 5 * dp
        ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -d, d, -d * 0.7f, d * 0.7f, -d * 0.3f, 0f)
            .setDuration(400)
            .start()
    }

    // --- Dessin --------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val r = diameter / 2f
        val cy = height / 2f
        val right = width - margin.toFloat()
        val pillW = diameter + (expandedWidth - 2 * margin - diameter) * expand
        val left = right - pillW
        val cx = right - r

        if (state == State.RECORDING) {
            val grow = (2.5f + smoothLevel * 6f) * dp
            haloPaint.color = withAlpha(REC_TOP, 60)
            rect.set(left - grow, cy - r - grow, right + grow, cy + r + grow)
            canvas.drawRoundRect(rect, r + grow, r + grow, haloPaint)
        }

        rect.set(left, cy - r, right, cy + r)
        bgPaint.shader = LinearGradient(left, cy - r, right, cy + r, topColor, bottomColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        when (state) {
            State.IDLE -> drawMic(canvas, cx, cy)
            State.RECORDING -> {
                drawBars(canvas, left + r * 0.8f, cx - r * 0.9f, cy, expand)
                val s = 6.5f * dp
                rect.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(rect, 3 * dp, 3 * dp, whiteFill)
            }
            State.TRANSCRIBING -> {
                val t = SystemClock.uptimeMillis()
                val start = (t % 900) / 900f * 360f
                val sweep = 70f + 180f * abs(sin(t / 520.0)).toFloat()
                val sr = 9.5f * dp
                rect.set(cx - sr, cy - sr, cx + sr, cy + sr)
                canvas.drawArc(rect, start, sweep, false, whiteStroke)
            }
            State.SUCCESS -> drawCheck(canvas, cx, cy)
            State.ERROR -> {
                canvas.drawLine(cx, cy - 8 * dp, cx, cy + 2.5f * dp, whiteStroke)
                canvas.drawCircle(cx, cy + 7.5f * dp, 1.7f * dp, whiteFill)
            }
        }

        if (state == State.RECORDING || state == State.TRANSCRIBING) postInvalidateOnAnimation()
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float) {
        val half = (11 * dp).roundToInt()
        mic.setBounds(cx.roundToInt() - half, cy.roundToInt() - half, cx.roundToInt() + half, cy.roundToInt() + half)
        mic.draw(canvas)
    }

    private fun drawBars(canvas: Canvas, start: Float, end: Float, cy: Float, alpha: Float) {
        val span = end - start
        if (span < 10 * dp || alpha <= 0.01f) return
        val n = levels.size
        val step = span / n
        barPaint.strokeWidth = min(2.8f * dp, step * 0.62f)
        barPaint.alpha = (255 * min(1f, alpha * 1.4f)).toInt()
        val maxH = diameter * 0.6f
        for (i in 0 until n) {
            val x = start + step * (i + 0.5f)
            // Les barres les plus anciennes (à gauche) s'estompent légèrement.
            val fade = 0.55f + 0.45f * (i / (n - 1f))
            val h = max(3 * dp, levels[i] * maxH * fade)
            canvas.drawLine(x, cy - h / 2, x, cy + h / 2, barPaint)
        }
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float) {
        checkPath.reset()
        checkPath.moveTo(cx - 7.5f * dp, cy + 0.5f * dp)
        checkPath.lineTo(cx - 2.5f * dp, cy + 5.5f * dp)
        checkPath.lineTo(cx + 8f * dp, cy - 5.5f * dp)
        val pm = PathMeasure(checkPath, false)
        checkSegment.reset()
        pm.getSegment(0f, pm.length * checkProgress, checkSegment, true)
        canvas.drawPath(checkSegment, whiteStroke)
    }

    private fun animateColors(t: Int, b: Int) {
        colorAnim?.cancel()
        val fromT = topColor
        val fromB = bottomColor
        val ev = ArgbEvaluator()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            addUpdateListener {
                val f = it.animatedFraction
                topColor = ev.evaluate(f, fromT, t) as Int
                bottomColor = ev.evaluate(f, fromB, b) as Int
                invalidate()
            }
            start()
        }
    }

    // --- Toucher -------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFired = false
                animate().scaleX(0.88f).scaleY(0.88f).alpha(1f).setInterpolator(DecelerateInterpolator()).setDuration(90).start()
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(260).start()
                if (!longPressFired) performClick()
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

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private companion object {
        val IDLE_TOP = 0xFF6D5DFC.toInt()
        val IDLE_BOTTOM = 0xFFB15CFF.toInt()
        val REC_TOP = 0xFFFF4D6D.toInt()
        val REC_BOTTOM = 0xFFFF8A5B.toInt()
        val OK_TOP = 0xFF22C55E.toInt()
        val OK_BOTTOM = 0xFF10B981.toInt()
        val ERR_TOP = 0xFFF43F5E.toInt()
        val ERR_BOTTOM = 0xFFE11D48.toInt()
    }
}
