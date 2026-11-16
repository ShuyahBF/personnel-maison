package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests de la sauvegarde chiffrée : clés dérivées stables, aller-retour, refus d'une mauvaise phrase, archive et réglages typés. */
class SauvegardeCryptoTest {
    @Test fun lesClesDependentDeLaPhraseNormalisee() {
        val a = SauvegardeCrypto.deriver("  Ma Phrase   SECRÈTE 2026 ")
        val b = SauvegardeCrypto.deriver("ma phrase secrète 2026")
        assertEquals(a.id, b.id); assertEquals(a.jeton, b.jeton); assertArrayEquals(a.chiffre, b.chiffre)
        assertEquals(64, a.id.length); assertEquals(64, a.jeton.length); assertEquals(32, a.chiffre.size)
        assertNotEquals(a.id, a.jeton)                                   // le numéro de casier ne donne pas la clé du casier
        assertNotEquals(a.id, SauvegardeCrypto.deriver("une autre phrase secrète").id)
    }

    @Test fun chiffrerPuisDechiffrer() {
        val cle = SauvegardeCrypto.deriver("phrase de test très longue").chiffre
        val clair = "Données de la maison ✓".toByteArray()
        val c = SauvegardeCrypto.chiffrer(cle, clair)
        assertFalse(c.copyOfRange(13, c.size).toList() == clair.toList())
        assertArrayEquals(clair, SauvegardeCrypto.dechiffrer(cle, c))
        assertFalse(c.contentEquals(SauvegardeCrypto.chiffrer(cle, clair)))     // vecteur aléatoire : deux chiffrements diffèrent
    }

    @Test fun mauvaiseClefOuContenuModifieEstRefuse() {
        val cle = SauvegardeCrypto.deriver("phrase de test très longue").chiffre
        val c = SauvegardeCrypto.chiffrer(cle, ByteArray(100) { it.toByte() })
        assertThrows(IllegalArgumentException::class.java) { SauvegardeCrypto.dechiffrer(SauvegardeCrypto.deriver("phrase fausse mais longue").chiffre, c) }
        c[40] = (c[40] + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SauvegardeCrypto.dechiffrer(cle, c) }
    }

    @Test fun phraseTropCourteRefusee() {
        assertFalse(SauvegardeCrypto.phraseValide("trop court")); assertTrue(SauvegardeCrypto.phraseValide("assez longue pour etre ok"))
    }

    @Test fun archiveEtReglagesTypes() {
        val z = SauvegardeCrypto.archiver(mapOf("a/b.bin" to byteArrayOf(1, 2, 3), "c.txt" to "é".toByteArray()))
        val o = SauvegardeCrypto.ouvrir(z)
        assertArrayEquals(byteArrayOf(1, 2, 3), o["a/b.bin"]); assertEquals("é", String(o["c.txt"]!!))
        val p = SauvegardeCrypto.octetsVersPrefs(SauvegardeCrypto.prefsVersOctets(mapOf("t" to "x", "i" to 5, "l" to 7L, "f" to 1.5f, "b" to true, "s" to setOf("u", "v"), "n" to null)))
        assertEquals(mapOf<String, Any>("t" to "x", "i" to 5, "l" to 7L, "f" to 1.5f, "b" to true, "s" to setOf("u", "v")), p)
    }
}
