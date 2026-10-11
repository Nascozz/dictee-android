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
import com.nascozz.dictee.audio.PcmStreamer
import com.nascozz.dictee.audio.Recorder
import com.nascozz.dictee.audio.WavFile
import com.nascozz.dictee.stt.MistralRealtime
import com.nascozz.dictee.stt.TextFormatter
import com.nascozz.dictee.stt.SttException
import com.nascozz.dictee.stt.SttRequest
import com.nascozz.dictee.stt.Transcriber
import com.nascozz.dictee.ui.MainActivity
import com.nascozz.dictee.update.Relauncher
import com.nascozz.dictee.update.Updater
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
    private val updatePrefs by lazy { getSharedPreferences("update", MODE_PRIVATE) }

    // Temps réel (Mistral Voxtral Realtime)
    private var live: MistralRealtime? = null
    private var streamer: PcmStreamer? = null
    private val liveText = StringBuilder()
    private var liveField: LiveField? = null
    private var liveDurationMs = 0L
    private var fieldWritePending = false
    /** Au moins une écriture directe a réussi : le texte est déjà dans le champ, ne jamais recoller. */
    private var liveWritten = false
    /** Dernier contenu écrit dans le champ, pour ne pas le réécrire pour rien. */
    private var lastFieldWrite: String? = null
    /** Longueur du texte brut déjà écrit dans le champ. */
    private var writtenRawLen = 0
    /**
     * Après une retouche au clavier, le champ (préfixe compris) contient ta version : seul le texte brut
     * reçu après [liveRawBase] reste à écrire, à la suite.
     */
    private var liveRawBase = 0
    /** Moment de notre dernière écriture directe (les champs Compose l'affichent avec un léger retard). */
    private var lastWriteAt = 0L
    private var lastDeltaAt = 0L

    // Copie locale de l'audio temps réel, pour pouvoir réessayer si la transcription échoue.
    private val pcmLock = Any()
    private var pcmFile: java.io.File? = null
    private var pcmOut: java.io.OutputStream? = null

    /** Champ qu'on peut réécrire en direct : on garde ce qu'il y avait avant et après le curseur. */
    private class LiveField(
        val node: AccessibilityNodeInfo,
        val original: String,
        val prefix: String,
        val suffix: String,
        /** Un espace a été collé pour rendre le champ lisible : à retirer si la dictée n'aboutit pas. */
        val primed: Boolean = false,
    )

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        island = IslandView(this).also {
            it.listener = this
            it.onSizeNeeded = { w, h -> resizeWindow(w, h) }
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
        // Une mise à jour arrive pendant que l'île est au repos : on la propose tout de suite.
        scope.launch {
            Updater.state.collect {
                if (mode == Mode.IDLE && attached && !hiding && island.state != idleState()) island.setState(idleState())
            }
        }
        // Juste après une mise à jour lancée depuis l'écran : on rouvre Dictée.
        Relauncher.relaunch(this)
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
            island.setState(idleState())
            return
        }
        Updater.checkIfStale(30 * 60 * 1000L)
        hole = findHole()
        island.setHole(hole.diameter.toFloat())
        params.height = island.windowHeight
        params.y = hole.cy - params.height / 2
        resizeWindow(island.windowWidthFor(hole.diameter.toFloat()), params.height, update = false)
        runCatching { wm.addView(island, params) }.onSuccess {
            attached = true
            island.setState(idleState())
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

    /** La fenêtre suit la taille de l'île : centrée sur la caméra, le haut ne bouge pas. */
    private fun resizeWindow(width: Int, height: Int, update: Boolean = true) {
        params.width = width
        params.height = height
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
                cancelLive(restoreField = true)
                vibrate(VibrationEffect.EFFECT_TICK)
                resetToIdle()
            }
            Mode.TRANSCRIBING -> {
                transcribeJob?.cancel()
                cancelLive(restoreField = true)
                resetToIdle()
            }
            Mode.IDLE, Mode.FEEDBACK -> if (island.state == IslandView.State.UPDATE) {
                // Appui long sur la proposition : on ne la montre plus pour cette version.
                Updater.available?.let { updatePrefs.edit().putInt(DISMISSED_VERSION, it.versionCode).apply() }
                vibrate(VibrationEffect.EFFECT_TICK)
                island.setState(IslandView.State.IDLE)
            } else {
                openApp()
            }
        }
    }

    /** État de repos de l'île : avec la proposition de mise à jour s'il y en a une (non masquée). */
    private fun idleState(): IslandView.State {
        val r = Updater.available ?: return IslandView.State.IDLE
        val dismissed = updatePrefs.getInt(DISMISSED_VERSION, 0)
        return if (r.versionCode > dismissed) IslandView.State.UPDATE else IslandView.State.IDLE
    }

    /** Tap sur « ↓ Mise à jour » : installation sans rouvrir Dictée (tu restes dans ton appli). */
    override fun onUpdateTap() {
        if (mode != Mode.IDLE && mode != Mode.FEEDBACK) return
        val r = Updater.available ?: return
        main.removeCallbacks(resetRunnable)
        mode = Mode.FEEDBACK
        island.setState(IslandView.State.TRANSCRIBING)
        vibrate(VibrationEffect.EFFECT_CLICK)
        Updater.install(this, r, reopen = false)
        // L'installation redémarre le service ; si elle échoue, l'île revient au repos.
        main.postDelayed(resetRunnable, 30_000)
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
        if (s.useRealtime) return startLive()
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
            island.addLevel(streamer?.level() ?: recorder.level())
            main.postDelayed(this, 55)
        }
    }

    private val autoStop = Runnable { if (mode == Mode.RECORDING) stopRecording() }

    private fun stopRecording() {
        main.removeCallbacks(levelTick)
        main.removeCallbacks(autoStop)
        if (live != null) return stopLive()
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
                val text = TextFormatter.format(Transcriber.transcribe(file, request), s.formatOptions).trim()
                Graph.usage.record(request.model, duration)
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
                val msg = e.message ?: "Erreur de transcription"
                Graph.history.addFailed(provider.label, request.model, duration, msg, file)
                fail("$msg · gardée dans l'historique pour réessayer")
            } catch (e: Exception) {
                val msg = "Erreur : ${e.message}"
                Graph.history.addFailed(provider.label, request.model, duration, msg, file)
                fail("$msg · gardée dans l'historique pour réessayer")
            } finally {
                file.delete()
            }
        }
    }

    // --- Temps réel ------------------------------------------------------------------------------

    private fun startLive() {
        liveText.setLength(0)
        liveWritten = false
        lastFieldWrite = null
        writtenRawLen = 0
        liveRawBase = 0
        fieldWritePending = false
        main.removeCallbacks(writeField)
        liveField = prepareLiveField()
        val session = MistralRealtime(Graph.settings.apiKey(com.nascozz.dictee.stt.Provider.MISTRAL).orEmpty(), liveListener)
        openPcm()
        val pcm = PcmStreamer {
            session.sendAudio(it)
            writePcm(it)
        }
        if (!pcm.start()) {
            closePcm()?.delete()
            fail("Micro indisponible", "Micro")
            return
        }
        session.start()
        live = session
        streamer = pcm
        mode = Mode.RECORDING
        busy = true
        vibrate(VibrationEffect.EFFECT_CLICK)
        island.setState(IslandView.State.RECORDING)
        main.post(levelTick)
        main.postDelayed(autoStop, MAX_RECORDING_MS)
        main.postDelayed(sentWatch, 600)
    }

    /** Surveille le champ pendant le direct : s'il se vide, le message vient d'être envoyé. */
    private val sentWatch = object : Runnable {
        override fun run() {
            if (mode != Mode.RECORDING || live == null) return
            if (liveWritten && liveField != null) followUserEdits()
            if (mode == Mode.RECORDING && live != null) main.postDelayed(this, 250)
        }
    }


    /** Le message est parti : on arrête d'écouter, sans rien écrire de plus dans le champ vide. */
    private fun onMessageSent() {
        main.removeCallbacks(sentWatch)
        main.removeCallbacks(levelTick)
        main.removeCallbacks(autoStop)
        main.removeCallbacks(writeField)
        fieldWritePending = false
        streamer?.let { liveDurationMs = it.durationMs; it.stop() }
        streamer = null
        live?.cancel()
        live = null
        closePcm()?.delete()
        liveField = null
        Graph.usage.record(MistralRealtime.MODEL, liveDurationMs)
        // Seulement ce qui était écrit au moment de l'envoi, pas un mot arrivé juste après.
        val sent = TextFormatter.format(liveText.substring(0, writtenRawLen.coerceAtMost(liveText.length)), Graph.settings.current.formatOptions).trim()
        if (sent.isNotEmpty()) Graph.history.add(sent, "Mistral · temps réel", MistralRealtime.MODEL, liveDurationMs)
        succeed("Envoyé")
    }

    private fun stopLive() {
        val pcm = streamer
        liveDurationMs = pcm?.durationMs ?: 0L
        pcm?.stop()
        streamer = null
        if (liveDurationMs < 400 && liveText.isEmpty()) {
            cancelLive(restoreField = true)
            fail("Trop court : touche, parle, puis touche à nouveau", "Trop court")
            return
        }
        mode = Mode.TRANSCRIBING
        island.setState(IslandView.State.TRANSCRIBING)
        vibrate(VibrationEffect.EFFECT_TICK)
        live?.endAudio()
    }

    private val liveListener = object : MistralRealtime.Listener {
        override fun onDelta(text: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            val s = Graph.settings.current
            // Longue pause entre deux morceaux de texte : on marque un retour à la ligne.
            if (s.pauseBreak && liveText.isNotBlank() && now - lastDeltaAt >= (s.pauseSeconds * 1000).toLong()) {
                liveText.append(TextFormatter.PAUSE)
            }
            lastDeltaAt = now
            liveText.append(text)
            if (mode != Mode.RECORDING && mode != Mode.TRANSCRIBING) return
            if (liveField != null) {
                scheduleFieldWrite()
            } else if (mode == Mode.RECORDING) {
                island.setCaption(liveFormatted().trim())
                if (island.state == IslandView.State.RECORDING) island.setState(IslandView.State.LIVE)
            }
        }

        override fun onDone(finalText: String) {
            streamer?.let { liveDurationMs = it.durationMs; it.stop() }
            streamer = null
            live = null
            main.removeCallbacks(levelTick)
            main.removeCallbacks(autoStop)
            main.removeCallbacks(writeField)
            main.removeCallbacks(sentWatch)
            fieldWritePending = false
            completeLive(finalText)
        }

        override fun onError(message: String) = handleLiveError(message)
    }

    /**
     * Fin du direct. Si le champ n'affiche pas encore notre dernière écriture, on réessaie un peu plus tard ;
     * s'il a été vidé (message envoyé), on s'arrête sans rien réécrire (onMessageSent s'occupe du reste).
     */
    private fun completeLive(finalText: String) {
        run {
            if (liveWritten && followUserEdits()) {
                if (mode == Mode.TRANSCRIBING) main.postDelayed({ completeLive(finalText) }, 200)
                return
            }
            // Déjà écrit en direct dans le champ : on garde exactement ce texte, on n'y retouche que pour
            // ajouter les derniers mots. Sinon, la version finale de Mistral (sans nos pauses).
            val raw = if (liveWritten || TextFormatter.PAUSE in liveText) liveText.toString()
            else finalText.ifBlank { liveText.toString() }
            val text = TextFormatter.format(raw, Graph.settings.current.formatOptions).trim()
            Graph.usage.record(MistralRealtime.MODEL, liveDurationMs)
            closePcm()?.delete()
            val field = liveField
            liveField = null
            if (text.isEmpty()) {
                field?.let { setFieldText(it.node, it.original, it.prefix.length) }
                fail("Je n'ai rien entendu", "Rien entendu")
                return
            }
            var finalInField = if (field != null && liveRawBase > 0) liveTail(field).trimEnd() else text
            if (field != null && Graph.settings.current.autoSpace) {
                val after = field.suffix.firstOrNull()
                if (after != null && !after.isWhitespace() && after !in ".,;:!?)]}»") finalInField = "$finalInField "
            }
            val inserted = when {
                // Rien de nouveau depuis la dernière écriture directe : on s'arrête là, sans rien recoller.
                field != null && liveWritten && field.prefix + finalInField + field.suffix == lastFieldWrite -> true
                field != null && writeFinal(field, finalInField) -> true
                // La version finale n'a pas pu remplacer le direct, mais le texte est déjà là : on ne recolle pas.
                field != null && liveWritten -> true
                else -> insertText(text)
            }
            Graph.history.add(text, "Mistral · temps réel", MistralRealtime.MODEL, liveDurationMs)
            if (inserted) succeed(if (liveWritten) "Terminé" else "Inséré") else {
                toast("Texte copié : colle-le où tu veux")
                succeed("Copié")
            }
        }
    }

    private fun handleLiveError(message: String) {
        live = null
        streamer?.let { liveDurationMs = it.durationMs; it.stop() }
        streamer = null
        // On garde l'audio pour pouvoir réessayer depuis l'historique.
        val pcm = closePcm()
        if (pcm != null && pcm.length() > PcmStreamer.SAMPLE_RATE * 2 / 2) {
            val wav = WavFile.fromPcm(pcm, java.io.File(cacheDir, pcm.nameWithoutExtension + ".wav"))
            pcm.delete()
            Graph.history.addFailed("Mistral · temps réel", MistralRealtime.MODEL, liveDurationMs, message, wav)
            fail("$message · gardée dans l'historique pour réessayer")
        } else {
            pcm?.delete()
            fail(message)
        }
    }

    private fun openPcm() = synchronized(pcmLock) {
        closePcm()?.delete()
        val f = java.io.File(cacheDir, "live-${System.currentTimeMillis()}.pcm")
        pcmFile = f
        pcmOut = runCatching { f.outputStream().buffered() }.getOrNull()
    }

    private fun writePcm(chunk: ByteArray) = synchronized(pcmLock) { runCatching { pcmOut?.write(chunk) } }

    /** Ferme la copie locale et la rend (null s'il n'y en avait pas). */
    private fun closePcm(): java.io.File? = synchronized(pcmLock) {
        runCatching { pcmOut?.close() }
        pcmOut = null
        pcmFile.also { pcmFile = null }
    }

    /**
     * Décide si on peut écrire en direct dans le champ : il faut connaître son contenu exact.
     * Sinon, le texte s'affichera dans l'île.
     */
    private fun prepareLiveField(): LiveField? {
        val node = target?.takeIf { runCatching { it.refresh() }.getOrDefault(false) } ?: findFocusedEditable() ?: return null
        val known = readField(node)
        val f = known ?: primeField(node) ?: return null
        var prefix = f.text.substring(0, f.start)
        val before = prefix.lastOrNull()
        if (Graph.settings.current.autoSpace && before != null && !before.isWhitespace() && before !in "([{\"'«/") prefix += " "
        return LiveField(node, f.text, prefix, f.text.substring(f.end), primed = known == null)
    }

    /**
     * Dernier recours quand le contenu est incertain (ex. WhatsApp vide) : on colle un espace au curseur.
     * Le champ annonce alors son vrai contenu ; on repère notre espace et on le retire du contenu connu,
     * le premier mot dicté le remplacera.
     */
    private fun primeField(node: AccessibilityNodeInfo): FieldState? {
        val shownBefore = node.text?.toString().orEmpty()
        clipboard().setPrimaryClip(ClipData.newPlainText("Dictée", " "))
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return null
        runCatching { node.refresh() }
        val text = node.text?.toString().orEmpty()
        val cursor = node.textSelectionStart
        if (cursor <= 0 || cursor > text.length || text[cursor - 1] != ' ') return null
        val clean = text.removeRange(cursor - 1, cursor)
        if (clean.isEmpty() && shownBefore.isNotBlank()) learnPlaceholder(node, shownBefore)
        return FieldState(clean, cursor - 1, cursor - 1)
    }

    // --- Textes d'aide (« Message » sur WhatsApp) -------------------------------------------------

    private val placeholderPrefs by lazy { getSharedPreferences("placeholders", MODE_PRIVATE) }

    /** Retient qu'un texte affiché dans les champs de cette appli n'est qu'un texte d'aide. */
    private fun learnPlaceholder(node: AccessibilityNodeInfo, text: String) {
        val pkg = node.packageName?.toString() ?: return
        val t = text.trim()
        if (t.isEmpty() || t.length > 40 || '\n' in t) return
        val known = placeholderPrefs.getStringSet(pkg, emptySet()).orEmpty()
        if (t !in known) placeholderPrefs.edit().putStringSet(pkg, known + t).apply()
    }

    /** Vrai si [shown] n'est que le texte d'aide du champ (signalé par l'appli ou appris). */
    private fun isPlaceholder(node: AccessibilityNodeInfo, shown: String): Boolean {
        if (shown.isEmpty()) return false
        if (node.isShowingHintText || shown == node.hintText?.toString()) return true
        val pkg = node.packageName?.toString() ?: return false
        return shown.trim() in placeholderPrefs.getStringSet(pkg, emptySet()).orEmpty()
    }

    /** Contenu réel d'un champ et position du curseur. */
    private data class FieldState(val text: String, val start: Int, val end: Int)

    /**
     * Lit le contenu réel du champ, ou null si on ne peut pas en être sûr.
     *
     * Beaucoup d'applis (WhatsApp, Messages…) renvoient le texte grisé d'aide (« Message »)
     * comme si c'était le contenu, avec le curseur au début. Dans ce cas ambigu, on fait un test
     * sans risque : on demande de placer le curseur à la fin de ce texte (voir [probeRealText]).
     */
    private fun readField(node: AccessibilityNodeInfo): FieldState? {
        val rawText = node.text?.toString().orEmpty()
        val showingHint = isPlaceholder(node, rawText)
        var existing = if (showingHint) "" else rawText
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (showingHint) {
            start = 0
            end = 0
        }

        if (existing.isNotEmpty() && start <= 0 && end <= 0) {
            when (probeRealText(node, existing)) {
                true -> if (start == 0) {
                    // Vrai texte, curseur au début : on remet le curseur où il était.
                    setSelection(node, 0)
                    end = 0
                }
                false -> {
                    learnPlaceholder(node, existing)
                    existing = ""
                    start = 0
                    end = 0
                }
                null -> return null
            }
        }
        if (start < 0 || end < 0 || start > existing.length) {
            start = existing.length
            end = existing.length
        }
        if (start > end) start = end.also { end = start }
        end = end.coerceAtMost(existing.length)
        return FieldState(existing, start, end)
    }

    /**
     * true = le texte est bien réel ; false = c'est un texte d'aide (champ vide) ; null = impossible à dire.
     *
     * On place le curseur à la fin du texte annoncé. Un vrai texte l'accepte. Un champ Android natif
     * réellement vide le refuse, puisque cette position n'existe pas. On ne conclut « vide » que pour un
     * champ natif avec un texte court sur une ligne, comme un texte d'aide : jamais pour un long texte.
     */
    private fun probeRealText(node: AccessibilityNodeInfo, shown: String): Boolean? {
        val canSelect = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
        if (!canSelect) return null
        val len = shown.length
        if (setSelection(node, len)) {
            runCatching { node.refresh() }
            return if (node.textSelectionStart == len) true else null
        }
        val nativeField = node.className?.toString() == "android.widget.EditText"
        val placeholderLike = len <= 40 && '\n' !in shown
        return if (nativeField && placeholderLike) false else null
    }

    private fun setSelection(node: AccessibilityNodeInfo, cursor: Int): Boolean = node.performAction(
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        },
    )

    private fun scheduleFieldWrite() {
        if (fieldWritePending) return
        fieldWritePending = true
        main.postDelayed(writeField, 90)
    }

    private val writeField = Runnable {
        fieldWritePending = false
        if (liveField == null) return@Runnable
        if (followUserEdits()) return@Runnable
        val field = liveField ?: return@Runnable
        val rawLen = liveText.length
        val text = liveTail(field)
        val full = field.prefix + text + field.suffix
        if (full == lastFieldWrite) return@Runnable
        if (setFieldText(field.node, full, field.prefix.length + text.length)) {
            liveWritten = true
            lastFieldWrite = full
            writtenRawLen = rawLen
        } else if (!liveWritten) {
            // L'appli refuse l'écriture directe : on bascule sur les sous-titres dans l'île.
            liveField = null
            if (mode == Mode.RECORDING) {
                island.setCaption(liveFormatted().trim())
                island.setState(IslandView.State.LIVE)
            }
        }
    }

    private fun liveFormatted() = TextFormatter.format(liveText.toString(), Graph.settings.current.formatOptions)

    /** Ce qu'il reste à écrire après le préfixe : tout le direct, ou seulement la suite après une retouche. */
    private fun liveTail(field: LiveField): String {
        if (liveRawBase == 0) return liveFormatted().trimStart()
        val raw = liveText.substring(liveRawBase.coerceAtMost(liveText.length))
        var t = TextFormatter.format(raw, Graph.settings.current.formatOptions).trimStart()
        if (t.isEmpty()) return ""
        val before = field.prefix.lastOrNull()
        if (raw.trimStart().firstOrNull() == TextFormatter.PAUSE) {
            // Une pause juste après ta retouche : on passe à la ligne.
            val dot = if (before != null && before.isLetterOrDigit() && !Graph.settings.current.minimalPunctuation) "." else ""
            return dot + "\n" + t.replaceFirstChar { it.uppercaseChar() }
        }
        if (before != null && !before.isWhitespace()) t = " $t"
        return t
    }

    /**
     * À appeler avant chaque écriture directe. Renvoie true si le message vient d'être envoyé
     * (champ vidé) : la dictée s'arrête et il ne faut plus rien écrire.
     * Sinon, si tu as retouché le champ au clavier, on adopte ta version et on écrira la suite après.
     */
    private fun followUserEdits(): Boolean {
        val field = liveField ?: return false
        val last = lastFieldWrite ?: return false
        if (!runCatching { field.node.refresh() }.getOrDefault(false)) return false
        val shown = field.node.text?.toString().orEmpty()
        if (shown == last) return false

        // Le champ n'affiche peut-être pas encore notre dernière écriture (léger retard des champs Compose) :
        // on ne conclut rien, on n'écrit pas par-dessus, et on revérifie un peu plus tard.
        if (android.os.SystemClock.elapsedRealtime() - lastWriteAt < SETTLE_MS) {
            if (!fieldWritePending) {
                fieldWritePending = true
                main.postDelayed(writeField, 150)
            }
            return true
        }

        // Champ vidé : vide, texte d'aide connu, ou texte d'aide non signalé (court, curseur au début,
        // et plus rien de notre dictée).
        val cursor = field.node.textSelectionStart
        val unflaggedHint = cursor <= 0 && shown.length <= 40 && '\n' !in shown && !last.contains(shown.trim())
        if (shown.isBlank() || isPlaceholder(field.node, shown) || unflaggedHint) {
            if (shown.isNotBlank()) learnPlaceholder(field.node, shown)
            if (Graph.settings.current.stopOnSend || mode != Mode.RECORDING) {
                onMessageSent()
                return true
            }
            // On continue d'écouter, mais dans un champ vide : jamais « Message » devant.
            liveField = LiveField(field.node, "", "", "", field.primed)
            liveRawBase = writtenRawLen
            lastFieldWrite = ""
            return false
        }

        val keepsSuffix = field.suffix.isNotEmpty() && shown.endsWith(field.suffix)
        val end = if (keepsSuffix) shown.length - field.suffix.length else shown.length
        liveField = LiveField(field.node, field.original, shown.substring(0, end), if (keepsSuffix) field.suffix else "", field.primed)
        liveRawBase = writtenRawLen
        lastFieldWrite = shown
        return false
    }

    /** Remplace le texte direct par la version finale ; une seconde tentative avec un nœud rafraîchi. */
    private fun writeFinal(field: LiveField, finalText: String): Boolean {
        val full = field.prefix + finalText + field.suffix
        val cursor = field.prefix.length + finalText.length
        if (setFieldText(field.node, full, cursor)) return true
        val fresh = runCatching { field.node.refresh() }.getOrDefault(false)
        return fresh && setFieldText(field.node, full, cursor)
    }

    private fun setFieldText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
        if (ok) {
            setSelection(node, cursor)
            lastWriteAt = android.os.SystemClock.elapsedRealtime()
        }
        return ok
    }

    private fun cancelLive(restoreField: Boolean) {
        main.removeCallbacks(writeField)
        main.removeCallbacks(sentWatch)
        fieldWritePending = false
        streamer?.stop()
        streamer = null
        live?.cancel()
        live = null
        closePcm()?.delete()
        val field = liveField
        liveField = null
        if (field != null && ((restoreField && liveText.isNotEmpty()) || field.primed)) {
            setFieldText(field.node, field.original, field.prefix.length.coerceAtMost(field.original.length))
        }
    }

    /**
     * Insère le texte au curseur. Renvoie false si on a dû se contenter du presse-papiers.
     * Quand le contenu du champ est incertain (voir [readField]), on colle au curseur, ce qui est toujours juste.
     */
    private fun insertText(raw: String): Boolean {
        val text = raw.trim()
        val node = target?.takeIf { runCatching { it.refresh() }.getOrDefault(false) } ?: findFocusedEditable()
            ?: return copyToClipboard(text)
        val f = readField(node) ?: return paste(node, text)

        var insert = text
        if (Graph.settings.current.autoSpace) {
            val before = f.text.getOrNull(f.start - 1)
            if (before != null && !before.isWhitespace() && before !in "([{\"'«/") insert = " $insert"
            val after = f.text.getOrNull(f.end)
            if (after != null && !after.isWhitespace() && after !in ".,;:!?)]}»") insert = "$insert "
        }
        val newText = f.text.substring(0, f.start) + insert + f.text.substring(f.end)
        if (setFieldText(node, newText, f.start + insert.length)) return true
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
        Updater.checkIfStale(30 * 60 * 1000L)
        mode = Mode.FEEDBACK
        island.setState(IslandView.State.SUCCESS, label)
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
        main.postDelayed(resetRunnable, 1100)
    }

    private fun fail(message: String, label: String = "Erreur") {
        if (mode == Mode.RECORDING) recorder.cancel()
        main.removeCallbacks(levelTick)
        main.removeCallbacks(autoStop)
        cancelLive(restoreField = false)
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
        if (attached && !hiding) island.setState(idleState())
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
        cancelLive(restoreField = false)
        scope.cancel()
        if (attached) runCatching { wm.removeViewImmediate(island) }
        attached = false
        busy = false
        super.onDestroy()
    }

    companion object {
        private const val MAX_RECORDING_MS = 10 * 60 * 1000L
        private const val DISMISSED_VERSION = "island_dismissed_version"
        /** Délai pendant lequel un champ peut encore afficher l'avant-dernière version de notre texte. */
        private const val SETTLE_MS = 700L

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
