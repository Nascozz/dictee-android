package com.nascozz.dictee.stt

import com.nascozz.dictee.stt.TextFormatter.PAUSE
import org.junit.Assert.assertEquals
import org.junit.Test

class TextFormatterTest {
    private val normal = TextFormatter.Options(voiceCommands = true, minimal = false, french = true)
    private val minimal = normal.copy(minimal = true)
    private val off = TextFormatter.Options(voiceCommands = false, minimal = false, french = true)

    private fun f(s: String, o: TextFormatter.Options = normal) = TextFormatter.format(s, o)

    @Test fun texteInchangeSansCommande() =
        assertEquals("Bonjour, comment ça va ?", f("Bonjour, comment ça va ?"))

    @Test fun virgule() = assertEquals("Bonjour, comment ça va", f("Bonjour virgule comment ça va"))

    @Test fun virguleAvecPonctuationDuModele() =
        assertEquals("Bonjour, comment ça va", f("Bonjour, virgule, comment ça va"))

    @Test fun pointEnFin() = assertEquals("C'est fini.", f("C'est fini point"))

    @Test fun pointPuisMajuscule() =
        assertEquals("C'est fini. Ensuite on mange", f("C'est fini point ensuite on mange"))

    @Test fun pointDeVueIntact() =
        assertEquals("De mon point de vue c'est bien", f("De mon point de vue c'est bien"))

    @Test fun pointEnCoursDeDicteeSeCorrige() {
        assertEquals("C'est fini.", f("C'est fini point"))
        assertEquals("C'est fini point de départ", f("C'est fini point de départ"))
    }

    @Test fun pointNomIntact() {
        assertEquals("On fait le point demain", f("On fait le point demain"))
        assertEquals("Tout est au point", f("Tout est au point"))
        assertEquals("Son point fort", f("Son point fort"))
    }

    @Test fun interrogationFrancaise() =
        assertEquals("Tu viens ce soir ? Je pense que oui", f("Tu viens ce soir point d'interrogation je pense que oui"))

    @Test fun apostropheTypographique() = assertEquals("Super !", f("Super point d’exclamation"))

    @Test fun aLaLigne() = assertEquals("Salut\nÇa va", f("Salut à la ligne ça va"))

    @Test fun nouveauParagraphe() = assertEquals("Salut.\n\nÇa va", f("Salut point nouveau paragraphe ça va"))

    @Test fun deuxPoints() = assertEquals("Liste : pain, lait", f("Liste deux points pain virgule lait"))

    @Test fun commandesDesactivees() = assertEquals("Bonjour virgule toi", f("Bonjour virgule toi", off))

    @Test fun minimalRetireVirgulesEtPointFinal() =
        assertEquals("Salut ça va bien", f("Salut, ça va bien.", minimal))

    @Test fun minimalGardeLesQuestions() = assertEquals("Tu viens ?", f("Tu viens ?", minimal))

    @Test fun minimalGardeLaVirguleDictee() =
        assertEquals("Salut, toi", f("Salut virgule toi", minimal))

    @Test fun pauseAjoutePointEtLigne() =
        assertEquals("Premier sujet.\nDeuxième sujet", f("Premier sujet$PAUSE deuxième sujet"))

    @Test fun pauseApresPonctuationExistante() =
        assertEquals("Fini ?\nAutre chose", f("Fini ?$PAUSE autre chose"))

    @Test fun pauseEnMinimalSansPoint() =
        assertEquals("Premier sujet\nDeuxième sujet", f("Premier sujet.$PAUSE deuxième sujet", minimal))

    @Test fun pauseAuDebutIgnoree() = assertEquals("Bonjour", f("${PAUSE}Bonjour"))
}
