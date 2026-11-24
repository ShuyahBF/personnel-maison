package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comparaison sauvegarde en ligne / téléphone (1.62) : détecte les ajouts faits d'un seul côté. */
class SauvegardeComparaisonTest {
    @Test fun ajoutsDesDeuxCotes() {
        val l = SauvegardeEnLigne.comparer(mapOf("pointages" to 100L, "personnels" to 5L), mapOf("pointages" to 90L, "personnels" to 5L, "paiements" to 3L))
        val pointages = l.first { it.libelle == "Pointages" }
        assertEquals(10, pointages.enPlusEnLigne); assertEquals(0, pointages.enPlusSurTelephone)
        assertTrue(l.first { it.libelle == "Personnes" }.identique)
        assertEquals(3, l.first { it.libelle == "Paiements" }.enPlusSurTelephone)
        assertEquals(3L, SauvegardeEnLigne.ajoutsLocaux(l))
    }
    @Test fun rubriquesVidesIgnoreesEtTelephoneVide() {
        assertTrue(SauvegardeEnLigne.comparer(emptyMap(), emptyMap()).isEmpty())
        assertEquals(0L, SauvegardeEnLigne.ajoutsLocaux(SauvegardeEnLigne.comparer(mapOf("pointages" to 4L), emptyMap())))
    }
}
