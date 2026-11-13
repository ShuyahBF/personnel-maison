package org.shuyah.personnelmaison

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Tests des repas partagés : heures, prochaine heure de repas, événements. */
class RappelsRepasTest {
    private val z = ZoneOffset.UTC as ZoneId
    private fun ms(date: String, h: Int, m: Int = 0) = LocalDate.parse(date).atStartOfDay(z).plusMinutes((h * 60 + m).toLong()).toInstant().toEpochMilli()

    @Test fun repasEtHeuresUsuelles() {
        assertEquals(listOf("petit_dejeuner", "dejeuner", "diner", "collation"), RappelsRepas.REPAS.map { it.code })
        assertEquals(12 * 60 + 30, RappelsRepas.repas("dejeuner")!!.minutesDefaut)
        assertTrue(RappelsRepas.repas("dejeuner")!!.actifDefaut); assertFalse(RappelsRepas.repas("collation")!!.actifDefaut)
        assertNull(RappelsRepas.repas("brunch"))
    }

    @Test fun prochaineHeureDeRepas() {
        val dej = 12 * 60 + 30
        assertEquals(ms("2026-11-13", 12, 30), RappelsRepas.prochaineHeure(dej, ms("2026-11-13", 9), z))          // pas encore passée : aujourd'hui
        assertEquals(ms("2026-11-14", 12, 30), RappelsRepas.prochaineHeure(dej, ms("2026-11-13", 13), z))         // passée : demain
        assertEquals(ms("2026-11-14", 12, 30), RappelsRepas.prochaineHeure(dej, ms("2026-11-13", 12, 30), z))     // à l'heure pile : demain
    }

    @Test fun evenementAllerRetourEtPhrases() {
        val e = RappelsRepas.Evenement("dejeuner", "pas_pris", ms("2026-11-13", 12, 30), "Awa")
        assertEquals(e, RappelsRepas.evenementDepuisTexte(RappelsRepas.evenementVersTexte(e)))
        assertEquals("Awa n'a pas encore pris son déjeuner (prévu à 12:30)", RappelsRepas.libelleEvenement(e, z))
        assertEquals("Awa prend son déjeuner", RappelsRepas.libelleEvenement(e.copy(statut = "prend"), z))
        assertEquals("Awa a fini sa collation", RappelsRepas.libelleEvenement(e.copy(repas = "collation", statut = "termine"), z))
        assertEquals("Il/elle prend son repas", RappelsRepas.libelleEvenement(Evenement2("autre", "prend"), z))
        assertNull(RappelsRepas.evenementDepuisTexte("rien"))
    }

    private fun Evenement2(repas: String, statut: String) = RappelsRepas.Evenement(repas, statut, 0, "")
}
