package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test
import org.shuyah.personnelmaison.CommandeVocaleLogique.Commande

/** Commande vocale de la mesure en direct (v1.53) : quel mot dit par l'utilisateur déclenche quelle action. */
class CommandeVocaleTest {
    @Test fun mots_reconnus_sans_tenir_compte_des_accents_ni_de_la_casse() {
        assertEquals(Commande.POSER, CommandeVocaleLogique.reconnaitre("pose"))
        assertEquals(Commande.POSER, CommandeVocaleLogique.reconnaitre("Posé !"))
        assertEquals(Commande.POSER, CommandeVocaleLogique.reconnaitre("pose un point"))
        assertEquals(Commande.ANNULER, CommandeVocaleLogique.reconnaitre("Annule"))
        assertEquals(Commande.TERMINER, CommandeVocaleLogique.reconnaitre("terminé"))
    }

    @Test fun phrase_sans_mot_cle_ou_vide_ne_fait_rien() {
        assertNull(CommandeVocaleLogique.reconnaitre("bonjour tout le monde"))
        assertNull(CommandeVocaleLogique.reconnaitre(""))
        assertNull(CommandeVocaleLogique.reconnaitre("   "))
    }

    @Test fun plusieurs_mots_cles_le_dernier_l_emporte() {
        assertEquals(Commande.ANNULER, CommandeVocaleLogique.reconnaitre("pose pose annule"))
        assertEquals(Commande.POSER, CommandeVocaleLogique.reconnaitre("annule puis pose"))
    }

    @Test fun meme_commande_en_moins_de_1_5_seconde_est_un_doublon() {
        assertTrue(CommandeVocaleLogique.doublon(Commande.POSER, 1000, Commande.POSER, 2000))      // résultat partiel puis final
        assertFalse(CommandeVocaleLogique.doublon(Commande.POSER, 1000, Commande.POSER, 2600))     // 1,6 s plus tard : nouveau point voulu
        assertFalse(CommandeVocaleLogique.doublon(Commande.POSER, 1000, Commande.ANNULER, 1100))   // autre commande : jamais un doublon
        assertFalse(CommandeVocaleLogique.doublon(null, 0, Commande.POSER, 100))
    }
}
