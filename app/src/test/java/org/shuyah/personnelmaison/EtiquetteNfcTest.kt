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

    @Test fun etiquetteDeRepas() {
        val c = EtiquetteNfc.chargeRepas("a1b2c3d4", "dejeuner")
        assertEquals("pm-repas|a1b2c3d4|dejeuner", c)
        assertEquals("a1b2c3d4" to "dejeuner", EtiquetteNfc.lireChargeRepas(c))
        assertEquals("a1b2c3d4" to "auto", EtiquetteNfc.lireChargeRepas(EtiquetteNfc.chargeRepas("a1b2c3d4", "auto")))
        assertNull(EtiquetteNfc.lireChargeRepas("pm-repas|a1b2c3d4|brunch")); assertNull(EtiquetteNfc.lireChargeRepas("pm-med|a1b2c3d4|12")); assertNull(EtiquetteNfc.lireCharge(c))
    }

    @Test fun repasSelonLHeure() {
        val suivis = listOf("petit_dejeuner" to 450, "dejeuner" to 750, "diner" to 1170)       // 07:30, 12:30, 19:30
        assertEquals("dejeuner", EtiquetteNfc.choisirRepas(suivis, 12 * 60 + 50))              // 20 min après l'heure
        assertEquals("dejeuner", EtiquetteNfc.choisirRepas(suivis, 11 * 60))                   // 1 h 30 avant
        assertEquals("diner", EtiquetteNfc.choisirRepas(suivis, 19 * 60))
        assertNull(EtiquetteNfc.choisirRepas(suivis, 15 * 60 + 30))                            // entre deux repas : rien
        assertNull(EtiquetteNfc.choisirRepas(emptyList(), 12 * 60))
    }
}
