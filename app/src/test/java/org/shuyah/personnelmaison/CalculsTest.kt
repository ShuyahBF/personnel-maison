package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Tests des calculs (retards, présences du mois, formats) et de la lecture des noms sur une pièce d'identité. */
class CalculsTest {
    private val contrat = ContratInfo(7 * 60, 17 * 60, 63, LocalDate.of(2026, 11, 1), null)   // 07:00–17:00, lundi à samedi, depuis le 01/11/2026

    private fun p(jour: Int, arrivee: Int?, depart: Int?, retard: Boolean = false, absent: Boolean = false) =
        JourPointage(LocalDate.of(2026, 11, jour), arrivee, depart, retard, absent)

    @Test fun retardsAvecTolerance() {
        assertEquals(0, Calculs.retardMinutes(7 * 60 + 8, 7 * 60, 10))     // dans la tolérance
        assertEquals(15, Calculs.retardMinutes(7 * 60 + 15, 7 * 60, 10))
        assertEquals(0, Calculs.retardMinutes(null, 7 * 60, 10))
        assertTrue(Calculs.estRetard(p(3, 7 * 60, null, retard = true), 7 * 60, 10))      // retard signalé à la main
        assertFalse(Calculs.estRetard(p(3, 7 * 60 + 5, null), 7 * 60, 10))
    }

    @Test fun statistiquesDuMois() {
        // Du 02/11 (lundi) au 07/11 (samedi) : semaine complète de 6 jours attendus ; aujourd'hui = 07/11
        val pointages = listOf(
            p(2, 7 * 60, 17 * 60),                        // à l'heure, 10 h
            p(3, 7 * 60 + 30, 17 * 60),                   // 30 min de retard
            p(4, null, null, absent = true),              // absence déclarée
            p(5, 7 * 60 + 5, 15 * 60),                    // à l'heure (tolérance) mais départ anticipé
            // jeudi 06 : aucun pointage → absence ; samedi 07 = aujourd'hui sans pointage : pas encore une absence
        )
        val s = Calculs.statsMois(YearMonth.of(2026, 11), contrat, pointages, 10, LocalDate.of(2026, 11, 7))
        // 1er novembre 2026 = dimanche (non travaillé). Jours attendus : lun 2, mar 3, mer 4, jeu 5, ven 6, sam 7 = 6
        assertEquals(6, s.joursAttendus)
        assertEquals(3, s.presences)
        assertEquals(2, s.absences)               // mer 4 (déclarée) et ven 6 (rien) ; jeu 5 est présent
        assertEquals(1, s.retards)
        assertEquals(30, s.retardTotalMin)
        assertEquals(1, s.departsAnticipes)
        assertEquals(600 + 570 + 475, s.minutesTravaillees)
    }

    @Test fun contratNonCommenceOuTermine() {
        val fini = contrat.copy(dateFin = LocalDate.of(2026, 11, 3))
        val s = Calculs.statsMois(YearMonth.of(2026, 11), fini, emptyList(), 10, LocalDate.of(2026, 11, 30))
        assertEquals(2, s.joursAttendus)          // lundi 2 et mardi 3 seulement
        assertEquals(2, s.absences)
    }

    @Test fun formats() {
        assertEquals("07:05", Calculs.heure(425))
        assertEquals("—", Calculs.heure(null))
        assertEquals("9 h 05", Calculs.duree(545))
        assertEquals("45 min", Calculs.duree(45))
        assertEquals("150 000 FCFA", Calculs.francs(150000))
        assertEquals("11/11/2026", Calculs.dateFr(LocalDate.of(2026, 11, 11)))
        assertEquals("mercredi 11 novembre 2026", Calculs.dateLongue(LocalDate.of(2026, 11, 11)))
        assertEquals("Lun, Mar", Calculs.joursTexte(3))
        assertTrue(Calculs.jourTravaille(63, java.time.DayOfWeek.SATURDAY)); assertFalse(Calculs.jourTravaille(63, java.time.DayOfWeek.SUNDAY))
    }

    @Test fun temoignageSeulementLesEvaluationsPartageables() {
        val ev = listOf(Evaluation(1, 1, "MOIS", "2026-10", 4, "Très sérieux", true), Evaluation(2, 1, "JOUR", "2026-10-05", 1, "privé", false))
        val t = Calculs.temoignage("Maison Plus", ev)
        assertTrue(t.contains("Maison Plus") && t.contains("octobre 2026") && t.contains("Très sérieux"))
        assertFalse(t.contains("privé"))
        assertEquals("", Calculs.temoignage("X", listOf(ev[1])))
        assertEquals(4.0, Calculs.moyenne(listOf(4, 4)), 0.0)
    }

    @Test fun lectureDesNomsSurLaPiece() {
        val a = AnalyseIdentite.analyser("BURKINA FASO\nNom / Surname\nOUEDRAOGO\nPrénom(s) / Given name(s)\nAwa Marie\nNé(e) le 01/01/1990")
        assertEquals("OUEDRAOGO", a.nom); assertEquals("Awa Marie", a.prenoms)
        val b = AnalyseIdentite.analyser("NOM: BADINI\nPRENOMS : Salif")
        assertEquals("BADINI", b.nom); assertEquals("Salif", b.prenoms)
        val c = AnalyseIdentite.analyser("Nom\nPrénom\nSawadogo")           // étiquette sans valeur suivie d'une étiquette
        assertNull(c.nom); assertEquals("Sawadogo", c.prenoms)
        val d = AnalyseIdentite.analyser("texte sans rapport")
        assertNull(d.nom); assertNull(d.prenoms)
    }
}
