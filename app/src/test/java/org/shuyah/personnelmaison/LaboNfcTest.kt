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

/** Tests du tri des cartes (logique pure) : genre, fusion de la liste, bilan. */
class LaboNfcTriTest {
    private fun carte(id: String, g: LaboNfc.GenreCarte) = LaboNfc.CarteVue(id, g, "", "Mifare Classic 1K", 1, 0L)

    @Test fun genreDUneCarte() {
        assertEquals(LaboNfc.GenreCarte.VIERGE, LaboNfc.classer(ndefPresent = false, formatable = true, inscriptible = true, vide = true))
        assertEquals(LaboNfc.GenreCarte.FORMATEE_VIDE, LaboNfc.classer(true, false, true, true))
        assertEquals(LaboNfc.GenreCarte.AVEC_CONTENU, LaboNfc.classer(true, false, true, false))
        assertEquals(LaboNfc.GenreCarte.VERROUILLEE, LaboNfc.classer(true, false, false, false))
        assertEquals(LaboNfc.GenreCarte.NON_INSCRIPTIBLE, LaboNfc.classer(false, false, true, true))
    }

    @Test fun laDerniereCartePasseEnTeteEtLesPassagesSAdditionnent() {
        var l = emptyList<LaboNfc.CarteVue>()
        l = LaboNfc.fusionnerCarte(l, carte("A", LaboNfc.GenreCarte.VIERGE))
        l = LaboNfc.fusionnerCarte(l, carte("B", LaboNfc.GenreCarte.AVEC_CONTENU))
        l = LaboNfc.fusionnerCarte(l, carte("A", LaboNfc.GenreCarte.FORMATEE_VIDE))     // A relue : état mis à jour, en tête, 2 passages
        assertEquals(listOf("A", "B"), l.map { it.id }); assertEquals(2, l[0].passages); assertEquals(LaboNfc.GenreCarte.FORMATEE_VIDE, l[0].genre); assertEquals(1, l[1].passages)
    }

    @Test fun bilanDuTri() {
        assertEquals("Aucune carte lue", LaboNfc.bilanTri(emptyList()))
        val l = listOf(carte("A", LaboNfc.GenreCarte.VIERGE), carte("B", LaboNfc.GenreCarte.VIERGE), carte("C", LaboNfc.GenreCarte.AVEC_CONTENU))
        assertEquals("3 cartes · 2 vierge · 1 contient des données", LaboNfc.bilanTri(l))
        assertEquals("1 carte · 1 formatée, vide", LaboNfc.bilanTri(listOf(carte("D", LaboNfc.GenreCarte.FORMATEE_VIDE))))
    }
}

/** Le lien d'ordonnance de test doit pointer vers le SITE, pas vers l'adresse de l'API (constat du 13/11/2026). */
class LaboNfcLienTest {
    @Test fun lienDOrdonnanceSurLeSite() {
        assertEquals("https://sawalismartsystems.com/officine/ordonnance/", LaboNfc.ADRESSE_SITE_ORDONNANCE)
        assertFalse(LaboNfc.ADRESSE_SITE_ORDONNANCE.contains("api."))
    }
}
