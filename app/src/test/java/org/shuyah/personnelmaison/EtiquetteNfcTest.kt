package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test

/** Tests de l'étiquette NFC des médicaments : contenu, relecture, choix de la prise à confirmer. */
class EtiquetteNfcTest {
    private val minute = 60_000L
    private val heure = 3_600_000L

    @Test fun contenuDeLEtiquetteAllerRetour() {
        val c = EtiquetteNfc.charge("a1b2c3d4", 12)
        assertEquals("pm-med|a1b2c3d4|12", c)
        assertEquals("a1b2c3d4" to 12L, EtiquetteNfc.lireCharge(c))
        assertFalse(c.contains("Paracétamol"))                                                 // aucun nom de médicament sur l'étiquette
    }

    @Test fun uneEtiquetteQuiNEstPasLaNotreEstIgnoree() {
        assertNull(EtiquetteNfc.lireCharge("https://exemple.com"))
        assertNull(EtiquetteNfc.lireCharge("pm-med|a1b2c3d4|abc"))
        assertNull(EtiquetteNfc.lireCharge("autre|a1b2c3d4|12"))
        assertNull(EtiquetteNfc.lireCharge("pm-med|ab|12"))                                    // code trop court
        assertNull(EtiquetteNfc.lireCharge(""))
    }

    @Test fun choixDeLaPriseAConfirmer() {
        val maintenant = 1_000 * heure
        val matin = maintenant - 20 * minute; val soir = maintenant + 8 * heure
        assertEquals(matin, EtiquetteNfc.choisirPrise(listOf(matin, soir), maintenant))                // la plus proche : celle du matin, en retard de 20 min
        assertEquals(maintenant + 30 * minute, EtiquetteNfc.choisirPrise(listOf(maintenant + 30 * minute, soir), maintenant))   // un peu en avance
        assertEquals(maintenant - 5 * heure, EtiquetteNfc.choisirPrise(listOf(maintenant - 5 * heure), maintenant))   // prise tardive rattrapée
        assertNull(EtiquetteNfc.choisirPrise(listOf(maintenant - 7 * heure), maintenant))              // trop ancienne
        assertNull(EtiquetteNfc.choisirPrise(listOf(soir), maintenant))                                // trop tôt (8 h avant la prochaine)
        assertNull(EtiquetteNfc.choisirPrise(emptyList(), maintenant))
    }
}
