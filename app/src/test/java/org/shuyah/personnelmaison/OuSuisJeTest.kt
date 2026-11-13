package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests de « Où suis-je ? » : balises proches, lieu le plus proche avec anti-rebond, adresses, lecture de la réponse de SAWALI (logique pure). */
class OuSuisJeTest {
    private fun vue(cle: String, rssi: Double, vu: Long) = ScanBalises.Vue(cle, "AA:BB").also { it.rssiLisse = rssi; it.derniereVue = vu }
    private val rayon = OuSuisJe.Lieu("abc234", "Rayon frais")
    private val couloir = OuSuisJe.Lieu("zzz999", "Couloir 2")

    @Test fun candidatsRecentsEtProchesDeLaPlusForteALaPlusFaible() {
        val vues = listOf(vue("a", -70.0, 9_000), vue("b", -55.0, 9_000), vue("loin", -95.0, 9_000), vue("ancienne", -50.0, 1_000), vue("c", -80.0, 8_000))
        assertEquals(listOf("b", "a", "c"), OuSuisJe.candidats(vues, 10_000).map { it.first })          // trop faible et trop ancienne écartées
        assertEquals(1, OuSuisJe.candidats(vues, 10_000, max = 1).size)
    }

    @Test fun plusProcheIgnoreLesBalisesInconnuesEtPrendLaPlusForte() {
        val connus = mapOf("a" to rayon, "b" to couloir)
        assertNull(OuSuisJe.plusProche(listOf("x" to -40.0), connus, null))                              // balise inconnue de SAWALI
        assertNull(OuSuisJe.plusProche(emptyList(), connus, rayon))
        assertEquals(couloir, OuSuisJe.plusProche(listOf("a" to -75.0, "b" to -60.0, "x" to -30.0), connus, null))
    }

    @Test fun antiRebondEntreDeuxLieuxVoisins() {
        val connus = mapOf("a" to rayon, "b" to couloir)
        assertEquals(rayon, OuSuisJe.plusProche(listOf("a" to -70.0, "b" to -66.0), connus, rayon))      // 4 dB d'écart : on reste
        assertEquals(couloir, OuSuisJe.plusProche(listOf("a" to -70.0, "b" to -60.0), connus, rayon))    // 10 dB : on change
        assertEquals(couloir, OuSuisJe.plusProche(listOf("b" to -66.0), connus, rayon))                  // l'ancien n'est plus entendu : on change sans marge
    }

    @Test fun adresses() {
        assertEquals("https://sawalismartsystems.com/lieu/abc234?src=app", OuSuisJe.adressePage("https://sawalismartsystems.com/", "abc234"))
        assertEquals("https://sawalismartsystems.com/lieu/abc234?src=app", OuSuisJe.adressePage("  ", "abc234"))
        assertEquals("https://api.sawalismartsystems.com/api/lieux-balises-resolution?balise=ib%3AUU%3A1%3A2", OuSuisJe.adresseResolution("https://api.sawalismartsystems.com/", "ib:UU:1:2"))
    }

    @Test fun lectureDeLaReponseDeSawali() {
        assertEquals(rayon, OuSuisJe.lireLieu("""{"code":"abc234","nom":"Rayon frais"}"""))
        assertEquals("Café \"Chez Awa\" — étage", OuSuisJe.lireLieu("""{"code":"k4m9p2","nom":"Café \"Chez Awa\" — étage"}""")?.nom)
        assertNull(OuSuisJe.lireLieu("""{"detail":"Aucun lieu"}"""))
        assertNull(OuSuisJe.lireLieu("pas du json"))
        assertNull(OuSuisJe.lireLieu("""{"code":"<script>","nom":"x"}"""))                              // code invalide refusé
    }
}
