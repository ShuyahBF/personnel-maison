package org.shuyah.personnelmaison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test

/** Suivi de la paie d'un personnel : avances étalées, retenues, versements, report d'un mois à l'autre et retards de paiement. */
class SuiviPaieTest {
    private val nov = YearMonth.of(2026, 11)
    private val dec = YearMonth.of(2026, 12)
    private fun avance(montant: Long, date: String, mois: Int) = Paiement(1, 1, date, "AVANCE", montant, etalement = mois)
    private fun verse(mois: String, date: String, montant: Long) = Versement(personnelId = 1, mois = mois, date = date, montant = montant)

    @Test fun avanceEtalee_leTotalDeduitEstExactementLeMontantAvance() {
        val a = avance(100000, "2026-11-10", 3)                       // 33 333 + 33 333 + 33 334
        assertEquals(33333L, Calculs.partDuMois(a, nov)); assertEquals(33333L, Calculs.partDuMois(a, dec)); assertEquals(33334L, Calculs.partDuMois(a, YearMonth.of(2027, 1)))
        assertEquals(0L, Calculs.partDuMois(a, YearMonth.of(2026, 10))); assertEquals(0L, Calculs.partDuMois(a, YearMonth.of(2027, 2)))
        assertEquals(100000L, (0..4).sumOf { Calculs.partDuMois(a, nov.plusMonths(it.toLong())) })
    }

    @Test fun netAvecAvanceBonusEtRetenue() {
        val p = listOf(avance(30000, "2026-10-20", 3), Paiement(2, 1, "2026-11-05", "BONUS", 10000), Paiement(3, 1, "2026-11-20", "RETENUE", 4000))
        // novembre : 100 000 + 10 000 − 4 000 − 10 000 (2e tiers de l'avance d'octobre) = 96 000
        assertEquals(96000L, Calculs.netAPayer(100000, p, nov))
        assertEquals(90000L, Calculs.netAPayer(100000, p, YearMonth.of(2026, 10)))
    }

    @Test fun echeance_jourChoisiOuFinDeMois() {
        assertEquals(LocalDate.of(2026, 11, 30), Calculs.echeance(nov, 0))
        assertEquals(LocalDate.of(2026, 11, 5), Calculs.echeance(nov, 5))
        assertEquals(LocalDate.of(2027, 2, 28), Calculs.echeance(YearMonth.of(2027, 2), 30))       // jour absent du mois : dernier jour
    }

    @Test fun payeATempsEtPayeEnRetard() {
        val auj = LocalDate.of(2027, 1, 15)
        val aTemps = Calculs.suiviMois(100000, "2026-01-01", null, 5, emptyList(), listOf(verse("2026-11", "2026-11-05", 100000)), nov, auj)
        assertEquals(StatutPaie.PAYE, aTemps.statut); assertEquals(0, aTemps.retardJours); assertEquals("Payé le 05/11/2026 · à temps", Calculs.texteStatut(aTemps))
        val enRetard = Calculs.suiviMois(100000, "2026-01-01", null, 5, emptyList(), listOf(verse("2026-11", "2026-11-08", 100000)), nov, auj)
        assertEquals(3, enRetard.retardJours); assertEquals("Payé le 08/11/2026 · 3 jour(s) de retard", Calculs.texteStatut(enRetard))
    }

    @Test fun paiementEnDeuxFois_leRetardCourtJusquAuSolde() {
        val v = listOf(verse("2026-11", "2026-11-30", 60000), verse("2026-11", "2026-12-04", 40000))
        val s = Calculs.suiviMois(100000, "2026-01-01", null, 0, emptyList(), v, nov, LocalDate.of(2026, 12, 20))
        assertEquals(StatutPaie.PAYE, s.statut); assertEquals(LocalDate.of(2026, 12, 4), s.datePaiementFinal); assertEquals(4, s.retardJours)
        val partiel = Calculs.suiviMois(100000, "2026-01-01", null, 0, emptyList(), v.take(1), nov, LocalDate.of(2026, 12, 10))
        assertEquals(StatutPaie.PARTIEL, partiel.statut); assertEquals(40000L, partiel.reste); assertEquals(10, partiel.retardJours)
    }

    @Test fun nonPayeEtAVenir() {
        val s = Calculs.suiviMois(100000, "2026-01-01", null, 5, emptyList(), emptyList(), nov, LocalDate.of(2026, 11, 3))
        assertEquals(StatutPaie.A_VENIR, s.statut); assertEquals("À payer le 05/11/2026", Calculs.texteStatut(s))
        val r = Calculs.suiviMois(100000, "2026-01-01", null, 5, emptyList(), emptyList(), nov, LocalDate.of(2026, 11, 12))
        assertEquals(StatutPaie.EN_RETARD, r.statut); assertEquals(7, r.retardJours); assertNull(r.datePaiementFinal)
    }

    @Test fun leResteSeReporteSurLeMoisSuivant() {
        // Novembre : 100 000 dus, 70 000 versés → 30 000 reportés sur décembre ; contrat commencé en novembre
        val v = listOf(verse("2026-11", "2026-11-30", 70000))
        val s = Calculs.suiviMois(100000, "2026-11-15", null, 0, emptyList(), v, dec, LocalDate.of(2026, 12, 5))
        assertEquals(30000L, s.reporte); assertEquals(100000L, s.net); assertEquals(0L, s.verse)
        // Trop-perçu : 120 000 versés pour novembre → −20 000 reportés (crédit)
        assertEquals(-20000L, Calculs.suiviMois(100000, "2026-11-15", null, 0, emptyList(), listOf(verse("2026-11", "2026-11-30", 120000)), dec, LocalDate.of(2026, 12, 5)).reporte)
    }

    @Test fun avantLeContratEtApresLaFin_pasDeSalaire() {
        assertEquals(StatutPaie.RIEN, Calculs.suiviMois(100000, "2026-12-01", null, 0, emptyList(), emptyList(), nov, LocalDate.of(2026, 12, 20)).statut)
        assertEquals(0L, Calculs.suiviMois(100000, "2026-01-01", "2026-10-31", 0, emptyList(), emptyList(), nov, LocalDate.of(2026, 12, 20)).net)
    }
}
