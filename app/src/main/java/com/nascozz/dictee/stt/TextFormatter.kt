package com.nascozz.dictee.stt

/**
 * Mise en forme du texte dicté, sans IA : niveau de ponctuation, pauses et commandes vocales.
 *
 * Toujours appliquée au texte brut complet (jamais par morceaux) : pendant le temps réel, un mot
 * mal interprété au vol (« point » puis « … de vue ») se corrige donc tout seul au mot suivant.
 */
object TextFormatter {
    /** Marqueur inséré dans le texte brut à l'endroit d'une longue pause. */
    const val PAUSE = ' '

    /** Marqueur interne : mettre une majuscule à la prochaine lettre. */
    private const val CAP = '\u0001'

    data class Options(
        val voiceCommands: Boolean,
        val minimal: Boolean,
        /** Typographie française : espace avant « ? ! : ; ». */
        val french: Boolean,
    )

    fun format(raw: String, o: Options): String {
        var t = raw
        if (o.minimal) t = minimal(t)
        t = pauses(t, addPeriod = !o.minimal)
        if (o.voiceCommands) t = commands(t, o.french)
        return tidy(t)
    }

    // --- Niveau minimal : style SMS ----------------------------------------------------------

    private fun minimal(t: String): String = t
        .replace(Regex("\\s*[,;]"), "")
        // Point en fin de phrase avant un retour à la ligne, une pause ou la fin du texte.
        .replace(Regex("(?<![.])\\.(?=[ \\t]*(?:\\n|$PAUSE|$))"), "")

    // --- Pauses ------------------------------------------------------------------------------

    private fun pauses(t: String, addPeriod: Boolean): String {
        if (PAUSE !in t) return t
        val out = StringBuilder()
        t.split(PAUSE).forEachIndexed { i, part ->
            if (i == 0) {
                out.append(part)
                return@forEachIndexed
            }
            while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
            val last = out.lastOrNull()
            if (last == null) {
                out.append(part.trimStart())
                return@forEachIndexed
            }
            if (addPeriod && last.isLetterOrDigit()) out.append('.')
            if (last != '\n') out.append('\n')
            out.append(CAP).append(part.trimStart())
        }
        return out.toString()
    }

    // --- Commandes vocales -------------------------------------------------------------------

    private enum class Kind { INLINE, END, BREAK }

    private data class Command(val pattern: String, val symbol: String, val kind: Kind)

    private const val APOS = "['’]"

    // Du plus long au plus court : « point d'interrogation » avant « point ».
    private val COMMANDS = listOf(
        Command("nouveau paragraphe", "\n\n", Kind.BREAK),
        Command("retour à la ligne", "\n", Kind.BREAK),
        Command("à la ligne", "\n", Kind.BREAK),
        Command("nouvelle ligne", "\n", Kind.BREAK),
        Command("point d${APOS}interrogation", "?", Kind.END),
        Command("point d${APOS}exclamation", "!", Kind.END),
        Command("points de suspension", "…", Kind.END),
        Command("point[- ]virgule", ";", Kind.INLINE),
        Command("deux[- ]points", ":", Kind.INLINE),
        Command("virgule", ",", Kind.INLINE),
        Command("point final", ".", Kind.END),
        // « point » seul : pas quand c'est un nom (« mon point », « au point », « point de vue »,
        // « point fort »…). Sinon c'est de la ponctuation dictée.
        Command(
            "(?<!\\b(?:le|la|un|une|ce|cet|au|aux|à|du|mon|ton|son|notre|votre|leur|chaque|quel|premier|dernier|" +
                "gros|petit|bon|seul|même|en|sur|ce) )point(?![ \\t]+(?:de|d['’]|du|des|fort|faible|commun|mort|" +
                "noir|culminant|chaud|clé|clef|central|précis|sensible)\\b)",
            ".", Kind.END,
        ),
    )

    private val compiled = COMMANDS.map { c ->
        // Avale la ponctuation que le modèle a pu mettre autour du mot (« Bonjour, virgule, … »),
        // mais jamais un retour à la ligne.
        Regex("(?iU)[ \\t,.;:]*\\b${c.pattern}\\b[ \\t,.;:]*") to c
    }

    private fun commands(t: String, french: Boolean): String {
        var s = t
        for ((regex, c) in compiled) {
            s = regex.replace(s) { m ->
                val atEnd = m.range.last == s.length - 1
                val tail = if (atEnd) "" else " "
                when (c.kind) {
                    Kind.BREAK -> c.symbol + CAP
                    Kind.INLINE -> (if (french && c.symbol != ",") " " else "") + c.symbol + tail
                    Kind.END -> (if (french && (c.symbol == "?" || c.symbol == "!")) " " else "") + c.symbol + tail + CAP
                }
            }
        }
        return s
    }

    // --- Nettoyage final ---------------------------------------------------------------------

    private fun tidy(t: String): String {
        var s = t
            .replace(Regex("[ \\t]+($CAP*)\\n"), "$1\n")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex(" +([,.…])"), "$1")
        // Majuscule après une fin de phrase ou un retour à la ligne insérés par nous.
        val out = StringBuilder(s.length)
        var capNext = false
        for (ch in s) {
            when {
                ch == CAP -> capNext = true
                capNext && ch.isLetter() -> {
                    out.append(ch.uppercaseChar())
                    capNext = false
                }
                else -> {
                    if (capNext && !ch.isWhitespace()) capNext = false
                    out.append(ch)
                }
            }
        }
        s = out.toString()
        return s.trimStart(' ')
    }
}
