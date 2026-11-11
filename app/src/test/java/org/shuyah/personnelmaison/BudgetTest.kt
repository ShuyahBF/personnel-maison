package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Tests de la lecture des tickets de caisse et des statistiques du portefeuille. */
class BudgetTest {
    private val ticket = """
        SUPERMARCHE DU CENTRE
        Tel: 25 30 12 34
        Date: 11/11/2026  Caisse 2
        PAIN BAGUETTE        250
        LAIT 1L              1 200
        RIZ 5KG              4.500
        SAVON                 350
        SOUS-TOTAL           6 300
        TOTAL TTC            6 300
        ESPECES              7 000
        RENDU                  700
        MERCI DE VOTRE VISITE
    """.trimIndent()

    @Test fun lectureDuTicket() {
        val r = AnalyseTicket.analyser(ticket)
        assertEquals("Supermarche Du Centre", r.marchand)
        assertEquals(LocalDate.of(2026, 11, 11), r.date)
        assertEquals(6300L, r.total)
        assertEquals(listOf("PAIN BAGUETTE" to 250L, "LAIT 1L" to 1200L, "RIZ 5KG" to 4500L, "SAVON" to 350L), r.articles.map { it.libelle to it.montant })
    }

    @Test fun ticketSansRien() {
        val r = AnalyseTicket.analyser("texte sans rapport")
        assertNull(r.date); assertNull(r.total); assertTrue(r.articles.isEmpty())
        assertEquals(1500L, AnalyseTicket.montant("1 500")); assertEquals(1500L, AnalyseTicket.montant("1.500")); assertNull(AnalyseTicket.montant("abc"))
        assertEquals(LocalDate.of(2026, 3, 5), AnalyseTicket.analyser("05-03-26").date)
    }

    private fun op(g: String, date: String, m: Long, tiers: String = "", cat: String = "", p: String = "Espèces") = Operation(genre = g, date = date, montant = m, tiers = tiers, categorie = cat, portefeuille = p)
    private val ops = listOf(
        op("RECETTE", "2026-11-01", 200000, "Employeur", "Salaire", "Orange Money"),
        op("DEPENSE", "2026-11-03", 6300, "Supermarché", "Alimentation"), op("DEPENSE", "2026-11-10", 3700, "Supermarché", "Alimentation"),
        op("DEPENSE", "2026-11-05", 15000, "Station", "Transport", "Orange Money"), op("DEPENSE", "2026-10-20", 8000, "Marché", "Alimentation")
    )

    @Test fun totauxEtCategories() {
        val nov = CalculsBudget.duMois(ops, YearMonth.of(2026, 11))
        val t = CalculsBudget.totaux(nov)
        assertEquals(200000L, t.recettes); assertEquals(25000L, t.depenses); assertEquals(175000L, t.solde)
        assertEquals(listOf("Transport" to 15000L, "Alimentation" to 10000L), CalculsBudget.parCategorie(nov, "DEPENSE"))
        val tiers = CalculsBudget.parTiers(nov, "DEPENSE")
        assertEquals("Station", tiers[0].first)                          // 15 000 : le plus gros marchand
        assertEquals(Triple("Supermarché", 10000L, 2), tiers[1])         // 2 opérations, 10 000 au total
        assertEquals(60, CalculsBudget.pourcent(15000, 25000))
        assertEquals(0, CalculsBudget.pourcent(1, 0))
    }

    @Test fun portefeuillesEtSerie() {
        assertEquals(listOf("Espèces" to -18000L, "Orange Money" to 185000L), CalculsBudget.soldeParPortefeuille(ops))
        val serie = CalculsBudget.serieMois(ops, YearMonth.of(2026, 11), 3)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)), serie.map { it.first })
        assertEquals(8000L, serie[1].second.depenses)
        // Dépense moyenne par jour : 25 000 sur 10 jours écoulés du mois en cours
        assertEquals(2500L, CalculsBudget.depenseMoyenneParJour(ops, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 10)))
        assertEquals(8000L / 31, CalculsBudget.depenseMoyenneParJour(ops, YearMonth.of(2026, 10), LocalDate.of(2026, 11, 10)))
        assertEquals(1000L, CalculsBudget.sommeLignes(listOf(LigneOperation(1, 1, "a", 1.0, 400), LigneOperation(2, 1, "b", 1.0, 600))))
        assertTrue(CalculsBudget.bilanTexte(YearMonth.of(2026, 11), ops).contains("Solde : 175 000 FCFA"))
    }
}
