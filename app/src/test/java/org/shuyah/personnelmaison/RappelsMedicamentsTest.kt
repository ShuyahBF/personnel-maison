package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Tests des rappels de médicaments : heures, jours, prochaine prise, événements partagés. */
class RappelsMedicamentsTest {
    private val z = ZoneOffset.UTC as ZoneId
    private fun ms(date: String, h: Int, m: Int = 0) = LocalDate.parse(date).atStartOfDay(z).plusMinutes((h * 60 + m).toLong()).toInstant().toEpochMilli()
    private fun med(heures: String = "08:00,20:00", jours: Int = 127, debut: String? = null, fin: String? = null, actif: Boolean = true) = Medicament(1, "Paracétamol", "500 mg", heures, jours, debut, fin, true, actif)

    @Test fun heuresDeLaJournee() {
        assertEquals(listOf(480, 1200), RappelsMedicaments.minutesDepuisTexte("20:00, 08:00,08:00,25:00,abc"))
        assertEquals("08:00,20:00", RappelsMedicaments.minutesVersTexte(listOf(1200, 480, 480)))
        assertEquals("07:05", RappelsMedicaments.libelleHeure(425))
    }

    @Test fun joursEtPeriode() {
        val lundi = LocalDate.of(2026, 11, 9); val samedi = LocalDate.of(2026, 11, 14)
        assertTrue(RappelsMedicaments.jourActif(0b0000001, lundi)); assertFalse(RappelsMedicaments.jourActif(0b0000001, samedi))
        assertTrue(RappelsMedicaments.jourActif(0b0100000, samedi))
        assertTrue(RappelsMedicaments.dansPeriode("2026-11-10", "2026-11-12", LocalDate.of(2026, 11, 10)))
        assertFalse(RappelsMedicaments.dansPeriode("2026-11-10", "2026-11-12", LocalDate.of(2026, 11, 13)))
        assertTrue(RappelsMedicaments.dansPeriode(null, null, lundi)); assertFalse(RappelsMedicaments.dansPeriode("2026-11-10", null, lundi))
        assertEquals("Tous les jours", RappelsMedicaments.libelleJours(127)); assertEquals("lun jeu", RappelsMedicaments.libelleJours(0b0001001)); assertEquals("Aucun jour", RappelsMedicaments.libelleJours(0))
    }

    @Test fun prochainePrise() {
        val m = med()
        assertEquals(ms("2026-11-13", 20), RappelsMedicaments.prochainePrise(m, ms("2026-11-13", 9), z))              // après la prise du matin
        assertEquals(ms("2026-11-14", 8), RappelsMedicaments.prochainePrise(m, ms("2026-11-13", 20), z))              // à l'heure pile : la suivante
        assertEquals(ms("2026-11-16", 8), RappelsMedicaments.prochainePrise(med(jours = 0b0000001), ms("2026-11-13", 9), z))   // seulement le lundi
        assertEquals(ms("2026-11-20", 8), RappelsMedicaments.prochainePrise(med(debut = "2026-11-20"), ms("2026-11-13", 9), z))   // le traitement commence plus tard
        assertNull(RappelsMedicaments.prochainePrise(med(fin = "2026-11-12"), ms("2026-11-13", 9), z))                // traitement terminé
        assertNull(RappelsMedicaments.prochainePrise(med(actif = false), ms("2026-11-13", 9), z))                     // arrêté
        assertNull(RappelsMedicaments.prochainePrise(med(jours = 0), ms("2026-11-13", 9), z))
    }

    @Test fun prisesPrevuesDuJour() {
        assertEquals(listOf(ms("2026-11-13", 8), ms("2026-11-13", 20)), RappelsMedicaments.prisesPrevues(med(), LocalDate.of(2026, 11, 13), z))
        assertTrue(RappelsMedicaments.prisesPrevues(med(jours = 0b0000001), LocalDate.of(2026, 11, 13), z).isEmpty())
    }

    @Test fun evenementAllerRetourEtLibelle() {
        val e = RappelsMedicaments.Evenement("Paracétamol \"fort\"", "500 mg", ms("2026-11-13", 8), "oublie", "Awa")
        val lu = RappelsMedicaments.evenementDepuisTexte(RappelsMedicaments.evenementVersTexte(e))!!
        assertEquals("Paracétamol 'fort'", lu.nom); assertEquals("oublie", lu.statut); assertEquals(e.prevuMs, lu.prevuMs)
        assertEquals("Awa n'a pas pris Paracétamol 'fort' 500 mg (prévu à 08:00)", RappelsMedicaments.libelleEvenement(lu, z))
        assertEquals("Awa a pris Paracétamol 500 mg (prévu à 08:00)", RappelsMedicaments.libelleEvenement(e.copy(nom = "Paracétamol", statut = "pris"), z))
        assertNull(RappelsMedicaments.evenementDepuisTexte("rien"))
    }
}
