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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
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
 * Service d'accessibilité : quand un champ de texte est actif (clavier ouvert), une pilule noire
 * sort de la caméra frontale. On y enregistre la voix, on l'envoie au fournisseur choisi
 * et on insère le texte au curseur.
 */
class DictationService : AccessibilityService(), IslandView.Listener {

    private enum class Mode { IDLE, RECORDING, TRANSCRIBING, FEEDBACK }

    /** Position et taille du trou de la caméra, en pixels écran. */
    private data class Hole(val cx: Int, val cy: Int, val diameter: Int)

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var wm: WindowManager
    private lateinit var island: IslandView
    private lateinit var params: WindowManager.LayoutParams
    private val recorder by lazy { Recorder(this) }

    private var attached = false
    private var hiding = false
    private var hole = Hole(0, 0, 0)
    private var target: AccessibilityNodeInfo? = null
    private var mode = Mode.IDLE
    private var transcribeJob: Job? = null
    private var refreshPending = false

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        island = IslandView(this).also {
            it.listener = this
            it.onWidthNeeded = ::resizeWindow
        }
        params = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
        refreshIsland()
    }

    private fun refreshIsland() {
        if (mode != Mode.IDLE) return
        if (!Graph.settings.current.bubbleEnabled) return hide()
        val node = findFocusedEditable()
        if (node == null || !keyboardVisible()) return hide()
        target = node
        show()
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val node = when {
            focus == null -> null
            focus.isEditable -> focus
            // Compose & co : le focus signalé est la vue hôte, le vrai champ est un de ses descendants.
            else -> target?.takeIf { runCatching { it.refresh() && it.isFocused }.getOrDefault(false) }
                ?: searchFocusedEditable(focus)
        }
        return node?.takeUnless { it.isPassword }
    }

    /** Parcours en largeur, borné pour rester rapide même sur des écrans très chargés. */
    private fun searchFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var budget = 600
        while (queue.isNotEmpty() && budget-- > 0) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isFocused) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun keyboardVisible(): Boolean {
        val ws = runCatching { windows }.getOrNull()
        if (ws.isNullOrEmpty()) return true
        return ws.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    // --- Fenêtre de l'île -----------------------------------------------------------------------

    private fun show() {
        if (attached && !hiding) return
        if (hiding) {
            hiding = false
            island.setState(IslandView.State.IDLE)
            return
        }
        hole = findHole()
        island.setHole(hole.diameter.toFloat())
        params.height = island.windowHeight
        params.y = hole.cy - params.height / 2
        resizeWindow(island.windowWidthFor(hole.diameter.toFloat()), update = false)
        runCatching { wm.addView(island, params) }.onSuccess {
            attached = true
            island.setState(IslandView.State.IDLE)
        }
    }

    private fun hide() {
        if (!attached || hiding) return
        hiding = true
        island.setState(IslandView.State.HIDDEN) {
            if (hiding) {
                runCatching { wm.removeViewImmediate(island) }
                attached = false
                hiding = false
            }
        }
    }

    /** La fenêtre s'adapte à la taille de la pilule tout en restant centrée sur la caméra. */
    private fun resizeWindow(width: Int, update: Boolean = true) {
        params.width = width
        params.x = hole.cx - width / 2
        if (update && attached) runCatching { wm.updateViewLayout(island, params) }
    }

    private fun findHole(): Hole {
        val cutout = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets.displayCutout
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.cutout
        }
        val r = cutout?.boundingRectTop
        if (r != null && !r.isEmpty) {
            return if (r.width() > r.height() * 1.6f) {
                // Encoche large : on se centre dessus.
                Hole(r.centerX(), r.centerY(), r.height())
            } else {
                // Poinçon (Pixel, Galaxy…) : le rectangle peut descendre du haut de l'écran jusqu'au trou.
                Hole(r.centerX(), r.bottom - r.width() / 2, r.width())
            }
        }
        // Pas de caméra dans l'écran : l'île naît au milieu de la barre d'état.
        return Hole(screenWidth() / 2, statusBarHeight() / 2, 0)
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
                resetToIdle()
            }
            Mode.TRANSCRIBING -> {
                transcribeJob?.cancel()
                resetToIdle()
            }
            else -> openApp()
        }
    }

    private fun startRecording() {
        main.removeCallbacks(resetRunnable)
        val s = Graph.settings.current
        if (!s.hasKey(s.provider)) {
            fail("Ajoute ta clé ${s.provider.label} dans Dictée", "Clé API")
            openApp()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Autorise le micro dans Dictée", "Micro")
            openApp()
            return
        }
        findFocusedEditable()?.let { target = it }
        if (!recorder.start()) {
            fail("Micro indisponible", "Micro")
            return
        }
        mode = Mode.RECORDING
        busy = true
        vibrate(VibrationEffect.EFFECT_CLICK)
        island.setState(IslandView.State.RECORDING)
        main.post(levelTick)
        main.postDelayed(autoStop, MAX_RECORDING_MS)
    }

    private val levelTick = object : Runnable {
        override fun run() {
            if (mode != Mode.RECORDING) return
            island.addLevel(recorder.level())
            main.postDelayed(this, 55)
        }
    }

    private val autoStop = Runnable { if (mode == Mode.RECORDING) stopRecording() }

    private fun stopRecording() {
        main.removeCallbacks(levelTick)
        main.removeCallbacks(autoStop)
        val result = recorder.stop()
        if (result == null) {
            fail("Trop court : touche, parle, puis touche à nouveau", "Trop court")
            return
        }
        val (file, duration) = result
        mode = Mode.TRANSCRIBING
        island.setState(IslandView.State.TRANSCRIBING)
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
                    fail("Je n'ai rien entendu", "Rien entendu")
                } else {
                    val inserted = insertText(text)
                    Graph.history.add(text, provider.label, request.model, duration)
                    if (inserted) succeed("Inséré") else {
                        toast("Texte copié : colle-le où tu veux")
                        succeed("Copié")
                    }
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

    /**
     * Insère le texte au curseur. Renvoie false si on a dû se contenter du presse-papiers.
     *
     * Beaucoup d'applis (Messages, WhatsApp, champs Compose…) renvoient le texte grisé d'aide
     * (« Message ») comme si c'était le contenu du champ. On ne réécrit donc tout le champ que
     * quand on est sûr de son contenu ; dans le doute, on colle au curseur, ce qui est toujours juste.
     */
    private fun insertText(raw: String): Boolean {
        val text = raw.trim()
        val node = target?.takeIf { runCatching { it.refresh() }.getOrDefault(false) } ?: findFocusedEditable()
            ?: return copyToClipboard(text)

        val rawText = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        val showingHint = node.isShowingHintText || (hint != null && rawText == hint)
        val existing = if (showingHint) "" else rawText
        var start = node.textSelectionStart
        var end = node.textSelectionEnd

        // Contenu incertain : texte présent mais curseur absent ou tout au début (souvent un placeholder).
        val uncertain = !showingHint && existing.isNotEmpty() && (start <= 0 && end <= 0)
        if (uncertain) return paste(node, text)

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
        return paste(node, insert)
    }

    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        clipboard().setPrimaryClip(ClipData.newPlainText("Dictée", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboard(text: String): Boolean {
        clipboard().setPrimaryClip(ClipData.newPlainText("Dictée", text))
        return false
    }

    // --- Retours visuels ------------------------------------------------------------------------

    private fun succeed(label: String) {
        mode = Mode.FEEDBACK
        island.setState(IslandView.State.SUCCESS, label)
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
        main.postDelayed(resetRunnable, 1100)
    }

    private fun fail(message: String, label: String = "Erreur") {
        if (mode == Mode.RECORDING) recorder.cancel()
        mode = Mode.FEEDBACK
        busy = true
        if (attached) {
            island.setState(IslandView.State.ERROR, label)
            island.shake()
        }
        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
        toast(message)
        main.removeCallbacks(resetRunnable)
        main.postDelayed(resetRunnable, 2000)
    }

    private val resetRunnable = Runnable { resetToIdle() }

    private fun resetToIdle() {
        main.removeCallbacks(resetRunnable)
        mode = Mode.IDLE
        busy = false
        if (attached && !hiding) island.setState(IslandView.State.IDLE)
        refreshIsland()
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

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels

    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val top = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            if (top > 0) return top
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recorder.cancel()
        scope.cancel()
        if (attached) runCatching { wm.removeViewImmediate(island) }
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
