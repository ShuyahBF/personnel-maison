package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Garde-fou de la phrase secrète et identifiant de sauvegarde (version 1.60). */
class GardeFouPhraseTest {
    @Test fun phraseCourteOuBanaleRefusee() {
        assertFalse(GardeFouPhrase.verifier("trop courte").acceptable)
        assertFalse(GardeFouPhrase.verifier("123456789012").acceptable)                 // chiffres seuls et suite
        assertFalse(GardeFouPhrase.verifier("azertyuiopqsdf").acceptable)               // suite du clavier
        assertFalse(GardeFouPhrase.verifier("aaaaaaaaaaaaaa").acceptable)               // caractère répété
        assertFalse(GardeFouPhrase.verifier("abcabcabcabcabc").acceptable)              // bloc répété
        assertFalse(GardeFouPhrase.verifier("mon mot de passe 2026").acceptable)        // contient « motdepasse »
        assertFalse(GardeFouPhrase.verifier("22670000000 22670000000").acceptable)      // numéro seul
    }

    @Test fun phraseCorrecteAcceptee() {
        assertTrue(GardeFouPhrase.verifier("le soleil se lève derrière le baobab").acceptable)
        assertTrue(GardeFouPhrase.verifier("Ma chèvre Zénobie mange 3 mangues").acceptable)
        assertTrue(GardeFouPhrase.verifier("le soleil se lève derrière le baobab").raisons.isEmpty())
    }

    @Test fun phraseNeReprendPasLIdentifiant() {
        assertFalse(GardeFouPhrase.verifier("awa.badini mange du riz ce soir", "awa.badini@gmail.com").acceptable)
        assertFalse(GardeFouPhrase.verifier("mon numéro 70001234 est secret", "+226 7000 1234").acceptable)
        assertTrue(GardeFouPhrase.verifier("le soleil se lève derrière le baobab", "awa.badini@gmail.com").acceptable)
    }

    @Test fun identifiantNormaliseEtValide() {
        assertEquals("awa.badini@gmail.com", SauvegardeCrypto.normaliserIdentifiant("  Awa.Badini@Gmail.com "))
        assertEquals("22670000000", SauvegardeCrypto.normaliserIdentifiant("+226 70 00 00 00"))
        assertEquals("22670000000", SauvegardeCrypto.normaliserIdentifiant("0022670000000"))
        assertTrue(SauvegardeCrypto.identifiantValide("awa.badini@gmail.com"))
        assertTrue(SauvegardeCrypto.identifiantValide("+226 70 00 00 00"))
        assertFalse(SauvegardeCrypto.identifiantValide("awa"))
        assertFalse(SauvegardeCrypto.identifiantValide("12345"))
        assertEquals("a***@gmail.com", SauvegardeCrypto.masquerIdentifiant("Awa.Badini@gmail.com"))
        assertEquals("***0000", SauvegardeCrypto.masquerIdentifiant("+226 70 00 00 00"))
    }

    @Test fun lIdentifiantChangeLeCasier() {
        val phrase = "le soleil se lève derrière le baobab"
        val awa = SauvegardeCrypto.deriver(phrase, "awa.badini@gmail.com")
        val boris = SauvegardeCrypto.deriver(phrase, "boris@gmail.com")
        assertNotEquals(awa.id, boris.id)                                                 // même phrase, personnes différentes : casiers différents
        assertNotEquals(awa.jeton, boris.jeton)
        assertFalse(awa.chiffre.contentEquals(boris.chiffre))                             // et clés de chiffrement différentes : l'un ne peut pas lire l'autre
        assertEquals(awa.id, SauvegardeCrypto.deriver("  LE soleil se lève   derrière le baobab ", " Awa.Badini@GMAIL.com").id)   // mêmes données saisies autrement : même casier
        assertNotEquals(awa.id, SauvegardeCrypto.deriver(phrase).id)                      // l'ancien casier (phrase seule) reste distinct
    }
}
