package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests du laboratoire NFC : reconnaissance du type de carte et formats d'affichage (logique pure). */
class LaboNfcTest {
    private fun version(famille: Int, capacite: Int) = byteArrayOf(0x00, 0x04, famille.toByte(), 0x02, 0x01, 0x00, capacite.toByte(), 0x03)

    @Test fun typeDeCarteDepuisGetVersion() {
        assertEquals("NTAG213" to 144, LaboNfc.typeDepuisVersion(version(0x04, 0x0F)))
        assertEquals("NTAG215" to 504, LaboNfc.typeDepuisVersion(version(0x04, 0x11)))
        assertEquals("NTAG216" to 888, LaboNfc.typeDepuisVersion(version(0x04, 0x13)))
        assertEquals(48, LaboNfc.typeDepuisVersion(version(0x03, 0x0B))!!.second)
        assertNull(LaboNfc.typeDepuisVersion(version(0x04, 0x55))); assertNull(LaboNfc.typeDepuisVersion(byteArrayOf(1, 2))); assertNull(LaboNfc.typeDepuisVersion(null))
    }

    @Test fun familleDepuisSak() {
        assertTrue(LaboNfc.familleDepuisSak(0x00).contains("inscriptible"))
        assertTrue(LaboNfc.familleDepuisSak(0x08).contains("Classic 1K")); assertTrue(LaboNfc.familleDepuisSak(0x18).contains("4K"))
        assertTrue(LaboNfc.familleDepuisSak(0x20).contains("non inscriptible"))
        assertTrue(LaboNfc.familleDepuisSak(0x77).contains("77"))
    }

    @Test fun identifiantEnHexadecimal() {
        assertEquals("04:A2:0F", LaboNfc.hex(byteArrayOf(0x04, 0xA2.toByte(), 0x0F)))
        assertEquals("04A20F", LaboNfc.hex(byteArrayOf(0x04, 0xA2.toByte(), 0x0F), ""))
    }

    @Test fun resumeDesEnregistrements() {
        val uri = byteArrayOf(4) + "exemple.com/x".toByteArray()                                                 // préfixe 4 = « https:// »
        assertEquals("URI https://exemple.com/x (${uri.size} octets)", LaboNfc.resumeEnregistrement(1, "U".toByteArray(), uri))
        val texte = byteArrayOf(2) + "frBonjour".toByteArray()                                                  // octet d'état : langue sur 2 caractères
        assertTrue(LaboNfc.resumeEnregistrement(1, "T".toByteArray(), texte).contains("Bonjour"))
        assertTrue(LaboNfc.resumeEnregistrement(2, "application/vnd.shuyah.med".toByteArray(), "pm-med|a|1".toByteArray()).startsWith("MIME application/vnd.shuyah.med : pm-med"))
        assertEquals("Enregistrement vide", LaboNfc.resumeEnregistrement(0, ByteArray(0), ByteArray(0)))
    }

    @Test fun contenusDeTestEtJetons() {
        assertEquals(4, LaboNfc.CONTENUS.size)
        assertEquals(8, LaboNfc.jetonDeTest().length); assertNotEquals(LaboNfc.jetonDeTest(), LaboNfc.jetonDeTest())
    }

    @Test fun progressionDuTest() {
        fun e(l: String, s: LaboNfc.Etat = LaboNfc.Etat.OK) = LaboNfc.Etape(l, s)
        assertEquals(0 to "En attente de la carte", LaboNfc.progression(listOf(e("En attente d'une carte", LaboNfc.Etat.EN_COURS))))
        assertEquals(20, LaboNfc.progression(listOf(e("Carte détectée"))).first)
        assertEquals(65, LaboNfc.progression(listOf(e("Carte détectée"), e("État NDEF"), e("Taille du message"), e("Formatage et écriture", LaboNfc.Etat.EN_COURS))).first)
        assertEquals(80, LaboNfc.progression(listOf(e("Carte détectée"), e("Écriture", LaboNfc.Etat.EN_COURS), e("Écriture"))).first)
        // Après un formatage : on attend la présentation suivante, puis la relecture (le pourcentage ne recule jamais)
        val attente = listOf(e("Carte détectée"), e("Formatage et écriture"), e("Vérification à faire", LaboNfc.Etat.EN_COURS))
        assertEquals(85, LaboNfc.progression(attente).first)
        assertEquals(90, LaboNfc.progression(attente + e("Carte représentée")).first)
        assertEquals(100, LaboNfc.progression(attente + e("Carte représentée") + e("Vérification par relecture") + e("Test réussi")).first)
        // Un échec garde le pourcentage atteint et l'indique
        val echec = LaboNfc.progression(listOf(e("Carte détectée"), e("État NDEF", LaboNfc.Etat.ECHEC), e("Arrêt", LaboNfc.Etat.ECHEC)))
        assertEquals(35, echec.first); assertTrue(echec.second.startsWith("Échec"))
    }
}
