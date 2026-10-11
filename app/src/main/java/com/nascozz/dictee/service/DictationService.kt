package com.nascozz.dictee.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.nascozz.dictee.Graph
import com.nascozz.dictee.audio.Recorder
import com.nascozz.dictee.stt.SttException
import com.nascozz.dictee.stt.SttRequest
import com.nascozz.dictee.stt.Transcriber
import com.nascozz.dictee.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service d'accessibilité : repère le champ de texte actif, y accroche la bulle,
 * enregistre la voix, envoie l'audio au fournisseur choisi et insère le texte.
 */
class DictationService : AccessibilityService(), BubbleView.Listener {

    private enum class Mode { IDLE, RECORDING, TRANSCRIBING, FEEDBACK }

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var wm: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var params: WindowManager.LayoutParams
    private val recorder by lazy { Recorder(this) }

    private var attached = false
    private var hiding = false
    private var anchorX = 0
    private var anchorY = 0
    private var target: AccessibilityNodeInfo? = null
    private var mode = Mode.IDLE
    private var transcribeJob: Job? = null
    private var refreshPending = false

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = BubbleView(this).also { it.listener = this }
        params = WindowManager.LayoutParams(
            bubble.collapsedSize,
            bubble.collapsedSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        scope.launch {
            Graph.settings.state.collect { if (!it.bubbleEnabled && mode == Mode.IDLE) hide() }
        }
    }

    // --- Suivi du champ de texte ----------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                main.removeCallbacks(refresh)
                refreshPending = true
                main.postDelayed(refresh, 40)
            }
            else -> if (!refreshPending) {
                // Contenu modifié, défilement… : on limite à ~6 rafraîchissements par seconde.
                refreshPending = true
                main.postDelayed(refresh, 160)
            }
        }
    }

    private val refresh = Runnable {
        refreshPending = false
        refreshBubble()
    }

    private fun refreshBubble() {
        if (mode != Mode.IDLE) return
        if (!Graph.settings.current.bubbleEnabled) return hide()
        val node = findFocusedEditable()
        if (node == null || !keyboardVisible()) return hide()
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return hide()
        target = node
        show(r)
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val n = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        if (!n.isEditable || n.isPassword || !n.isVisibleToUser) return null
        return n
    }

    private fun keyboardVisible(): Boolean {
        val ws = runCatching { windows }.getOrNull()
        if (ws.isNullOrEmpty()) return true
        return ws.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    // --- Fenêtre de la bulle -------------------------------------------------------------------

    private fun show(field: Rect) {
        val (x, y) = anchorFor(field)
        anchorX = x
        anchorY = y
        params.width = bubble.collapsedSize
        params.x = x
        params.y = y
        if (!attached) {
            runCatching { wm.addView(bubble, params) }.onSuccess {
                attached = true
                hiding = false
                bubble.popIn()
            }
        } else {
            if (hiding) {
                hiding = false
                bubble.popIn()
            }
            runCatching { wm.updateViewLayout(bubble, params) }
        }
    }

    private fun hide() {
        if (!attached || hiding) return
        hiding = true
        bubble.popOut {
            if (hiding) {
                runCatching { wm.removeViewImmediate(bubble) }
                attached = false
                hiding = false
            }
        }
    }

    /** Place la bulle au-dessus du coin droit du champ (ou dedans pour les grandes zones de texte). */
    private fun anchorFor(field: Rect): Pair<Int, Int> {
        val size = bubble.collapsedSize
        val m = bubble.margin
        val (sw, sh) = screenSize()
        val gap = dp(6)
        var x = field.right - dp(4) + m - size
        val y = when {
            field.height() > dp(180) -> field.top + dp(8) - m
            field.top - size + m + gap >= dp(32) -> field.top + gap + m - size
            else -> field.bottom - gap - m
        }
        x = x.coerceIn(0, sw - size)
        return x to y.coerceIn(0, sh - size)
    }

    private fun setExpanded(open: Boolean) {
        if (!attached) return
        if (open) {
            params.width = bubble.expandedWidth
            params.x = (anchorX + bubble.collapsedSize - bubble.expandedWidth).coerceAtLeast(0)
            runCatching { wm.updateViewLayout(bubble, params) }
            bubble.expand(true)
        } else if (bubble.isExpanded) {
            bubble.expand(false) {
                params.width = bubble.collapsedSize
                params.x = anchorX
                if (attached) runCatching { wm.updateViewLayout(bubble, params) }
            }
        }
    }

    // --- Dictée ---------------------------------------------------------------------------------

    override fun onBubbleTap() {
        when (mode) {
            Mode.IDLE, Mode.FEEDBACK -> startRecording()
            Mode.RECORDING -> stopRecording()
            Mode.TRANSCRIBING -> Unit
        }
    }

    override fun onBubbleLongPress() {
        when (mode) {
            Mode.RECORDING -> {
                main.removeCallbacks(levelTick)
                main.removeCallbacks(autoStop)
                recorder.cancel()
                vibrate(VibrationEffect.EFFECT_TICK)
                toast("Dictée annulée")
                resetToIdle()
            }
            Mode.TRANSCRIBING -> {
                transcribeJob?.cancel()
                toast("Dictée annulée")
                resetToIdle()
            }
            else -> openApp()
        }
    }

    private fun startRecording() {
        main.removeCallbacks(resetRunnable)
        val s = Graph.settings.current
        if (!s.hasKey(s.provider)) {
            fail("Ajoute ta clé ${s.provider.label} dans Dictée")
            openApp()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Autorise le micro dans Dictée")
            openApp()
            return
        }
        findFocusedEditable()?.let { target = it }
        if (!recorder.start()) {
            fail("Micro indisponible")
            return
        }
        mode = Mode.RECORDING
        busy = true
        vibrate(VibrationEffect.EFFECT_CLICK)
        bubble.setState(BubbleView.State.RECORDING)
        setExpanded(true)
        main.post(levelTick)
        main.postDelayed(autoStop, MAX_RECORDING_MS)
    }

    private val levelTick = object : Runnable {
        override fun run() {
            if (mode != Mode.RECORDING) return
            bubble.addLevel(recorder.level())
            main.postDelayed(this, 55)
        }
    }

    private val autoStop = Runnable { if (mode == Mode.RECORDING) stopRecording() }

    private fun stopRecording() {
        main.removeCallbacks(levelTick)
        main.removeCallbacks(autoStop)
        val result = recorder.stop()
        setExpanded(false)
        if (result == null) {
            fail("Trop court : touche, parle, puis touche à nouveau")
            return
        }
        val (file, duration) = result
        mode = Mode.TRANSCRIBING
        bubble.setState(BubbleView.State.TRANSCRIBING)
        vibrate(VibrationEffect.EFFECT_TICK)

        val s = Graph.settings.current
        val provider = s.provider
        val request = SttRequest(
            provider = provider,
            apiKey = Graph.settings.apiKey(provider).orEmpty(),
            model = s.modelFor(provider),
            language = s.language.takeIf { it != "auto" },
            prompt = s.prompt,
            customBaseUrl = s.customBaseUrl,
        )
        transcribeJob = scope.launch {
            try {
                val text = Transcriber.transcribe(file, request)
                if (text.isBlank()) {
                    fail("Je n'ai rien entendu")
                } else {
                    val inserted = insertText(text)
                    Graph.history.add(text, provider.label, request.model, duration)
                    succeed(if (inserted) null else "Texte copié : colle-le où tu veux")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SttException) {
                fail(e.message ?: "Erreur de transcription")
            } catch (e: Exception) {
                fail("Erreur : ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    private fun insertText(raw: String): Boolean {
        val text = raw.trim()
        val node = target?.takeIf { runCatching { it.refresh() }.getOrDefault(false) } ?: findFocusedEditable()
        if (node == null) return copyToClipboard(text)

        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val existing = if (showingHint) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > existing.length) {
            start = existing.length
            end = existing.length
        }
        if (start > end) start = end.also { end = start }
        end = end.coerceAtMost(existing.length)

        var insert = text
        if (Graph.settings.current.autoSpace) {
            val before = existing.getOrNull(start - 1)
            if (before != null && !before.isWhitespace() && before !in "([{\"'«/") insert = " $insert"
            val after = existing.getOrNull(end)
            if (after != null && !after.isWhitespace() && after !in ".,;:!?)]}»") insert = "$insert "
        }

        val newText = existing.substring(0, start) + insert + existing.substring(end)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val cursor = start + insert.length
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            return true
        }
        // Certaines applis refusent SET_TEXT : on passe par le presse-papiers + coller.
        clipboard().setPrimaryClip(ClipData.newPlainText("Dictée", insert))
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return false
    }

    private fun copyToClipboard(text: String): Boolean {
        clipboard().setPrimaryClip(ClipData.newPlainText("Dictée", text))
        return false
    }

    // --- Retours visuels ------------------------------------------------------------------------

    private fun succeed(note: String?) {
        mode = Mode.FEEDBACK
        bubble.setState(BubbleView.State.SUCCESS)
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
        note?.let(::toast)
        main.postDelayed(resetRunnable, 900)
    }

    private fun fail(message: String) {
        if (mode == Mode.RECORDING) recorder.cancel()
        mode = Mode.FEEDBACK
        busy = true
        setExpanded(false)
        bubble.setState(BubbleView.State.ERROR)
        bubble.shake()
        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
        toast(message)
        main.removeCallbacks(resetRunnable)
        main.postDelayed(resetRunnable, 1800)
    }

    private val resetRunnable = Runnable { resetToIdle() }

    private fun resetToIdle() {
        main.removeCallbacks(resetRunnable)
        setExpanded(false)
        mode = Mode.IDLE
        busy = false
        bubble.setState(BubbleView.State.IDLE)
        refreshBubble()
    }

    private fun vibrate(effect: Int) {
        if (!Graph.settings.current.haptics) return
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun clipboard() = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

    private fun openApp() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recorder.cancel()
        scope.cancel()
        if (attached) runCatching { wm.removeViewImmediate(bubble) }
        attached = false
        busy = false
        super.onDestroy()
    }

    companion object {
        private const val MAX_RECORDING_MS = 10 * 60 * 1000L

        /** Vrai pendant une dictée : la mise à jour automatique attend qu'elle soit finie. */
        @Volatile
        var busy = false
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, DictationService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
